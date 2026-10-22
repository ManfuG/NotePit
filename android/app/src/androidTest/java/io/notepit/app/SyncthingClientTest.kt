package io.notepit.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.Collections
import java.util.UUID
import kotlin.concurrent.thread

/** Real loopback HTTP transport + AndroidKeyStore persistence; no Syncthing installation is required. */
@RunWith(AndroidJUnit4::class)
class SyncthingClientTest {
    private lateinit var client: SyncthingClient
    private lateinit var preferences: SharedPreferences
    private lateinit var server: LocalApi

    @Before fun setUp() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val isolated = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences("$name-test-$suffix", mode)
        }
        preferences = isolated.getSharedPreferences("syncthing", Context.MODE_PRIVATE)
        client = SyncthingClient(isolated, "notepit.syncthing.test.$suffix")
        server = LocalApi()
    }

    @After fun tearDown(): Unit = runBlocking {
        server.close()
        client.disconnect()
        preferences.edit().clear().commit()
        Unit
    }

    @Test fun endpointValidationRejectsNonLoopbackAndAmbiguousUrls(): Unit = runBlocking {
        assertEquals("https://127.0.0.1:8384", SyncEndpoint.parse("https://127.0.0.1:8384/", false).url)
        assertEquals("http", SyncEndpoint.parse("http://127.0.0.1:8384", true).scheme)
        listOf(
            "https://example.org:8384", "https://127.0.0.1.example.org:8384", "https://localhost:8384",
            "https://0.0.0.0:8384", "https://127.1:8384", "https://2130706433:8384",
            "https://127.0.0.1", "https://127.0.0.1:0", "https://127.0.0.1:65536",
            "https://key@127.0.0.1:8384", "https://127.0.0.1:8384/rest",
            "https://127.0.0.1:8384?key=x", "https://127.0.0.1:8384#x", "file://127.0.0.1:8384",
        ).forEach { value -> expectIo { SyncEndpoint.parse(value, true) } }
        expectIo { SyncEndpoint.parse("http://127.0.0.1:8384", false) }
    }

    @Test fun pinsAndKeysAreStrictAndTlsCannotConnectWithoutConfirmedPin(): Unit = runBlocking {
        val der = byteArrayOf(1, 2, 3, 4)
        val pin = SyncthingClient.fingerprint(der)
        assertTrue(SyncthingClient.validPin(pin))
        assertTrue(SyncthingClient.pinMatches(pin, der))
        assertFalse(SyncthingClient.pinMatches(pin, byteArrayOf(1, 2, 3, 5)))
        assertFalse(SyncthingClient.validPin("00:11"))
        assertFalse(SyncthingClient.validPin(pin.lowercase()))
        listOf("", " ", "abc\r\nHeader: x", "abc def", "é", "x".repeat(4097)).forEach { key ->
            expectIo { SyncthingClient.validateKey(key) }
        }
        SyncthingClient.validateKey("A-good-key_123")
        expectIo { client.connect(SyncEndpoint("https", server.port), server.key, null) }
        assertTrue(server.paths.isEmpty())
        assertNull(client.credentials())
    }

    @Test fun authenticatedIdentityAndEncryptedCredentialsSurviveReopen(): Unit = runBlocking {
        val credentials = client.connect(SyncEndpoint("http", server.port), server.key, null)
        assertEquals("LOCAL-DEVICE", credentials.deviceId)
        assertEquals(server.key, client.credentials()!!.key)
        val persisted = preferences.getString("credential", "")!!
        assertFalse(persisted.contains(server.key))
        assertFalse(persisted.contains("LOCAL-DEVICE"))
        assertFalse(persisted.contains("127.0.0.1"))
        assertTrue(JSONObject(persisted).has("iv"))
        assertTrue(JSONObject(persisted).has("data"))
        assertTrue(server.headers.all { it == server.key })
        server.identity = "OTHER-DEVICE"
        assertTrue(expectIo { client.refresh() }.message!!.contains("identity changed"))
        assertEquals("LOCAL-DEVICE", client.credentials()!!.deviceId)
    }

    @Test fun refreshChecksOnlyBoundFoldersAndPreservesOfflineLinksOnDisconnect(): Unit = runBlocking {
        client.connect(SyncEndpoint("http", server.port), server.key, null)
        client.associate("content://provider/tree/one", "folder one", "LOCAL-DEVICE")
        client.associate("content://provider/tree/two", "folder-two", "LOCAL-DEVICE")
        client.associate("content://provider/tree/offline", "manual", "")
        client.associate("content://provider/tree/other", "other", "OTHER-DEVICE")
        val snapshot = client.refresh()
        assertEquals(setOf("folder one", "folder-two"), snapshot.statuses.keys)
        assertTrue(snapshot.statuses.getValue("folder one").localIndexCurrent)
        assertEquals(setOf("PEER-ONLINE"), snapshot.connectedDevices)
        assertTrue(server.paths.contains("/rest/db/status?folder=folder+one"))
        assertTrue(server.paths.contains("/rest/folder/errors?folder=folder+one"))
        assertFalse(server.paths.any { it.contains("folder=manual") || it.contains("folder=other") })
        val before = client.links()
        client.disconnect()
        assertNull(client.credentials())
        assertEquals(before, client.links())
        assertFalse(preferences.contains("credential"))
        client.unlink("content://provider/tree/one")
        assertEquals(3, client.links().size)
    }

    @Test fun authenticationErrorsAndRedirectsDoNotSaveCredentials(): Unit = runBlocking {
        val rejected = expectIo { client.connect(SyncEndpoint("http", server.port), "wrong-key", null) }
        assertTrue(rejected.message!!.contains("rejected"))
        assertFalse(rejected.message!!.contains("wrong-key"))
        assertNull(client.credentials())
        server.redirect = true
        val redirect = expectIo { client.connect(SyncEndpoint("http", server.port), server.key, null) }
        assertTrue(redirect.message!!.contains("HTTP 302"))
        assertNull(client.credentials())
    }

    @Test fun corruptCiphertextHasActionableErrorAndDoesNotDeleteAssociations(): Unit = runBlocking {
        client.associate("content://provider/tree/manual", "folder", "")
        preferences.edit().putString("credential", "not-json").commit()
        val failure = expectIo { client.credentials() }
        assertTrue(failure.message!!.contains("Disconnect"))
        assertEquals(1, client.links().size)
        client.disconnect()
        assertNull(client.credentials())
        assertEquals(1, client.links().size)
    }

    @Test fun statusParsingDoesNotConfuseLocalIndexWithErrorsOrOfflinePeers(): Unit = runBlocking {
        val status = JSONObject("""{"state":"idle","needTotalItems":0,"needBytes":0,"pullErrors":0}""")
        assertTrue(SyncthingClient.parseStatus(status, JSONObject("""{"errors":null}""")).localIndexCurrent)
        val errors = JSONObject("""{"errors":[{"path":"case.txt","error":"case conflict"}],"total":2}""")
        val parsed = SyncthingClient.parseStatus(status, errors)
        assertFalse(parsed.localIndexCurrent)
        assertEquals(2, parsed.errors.size)
        status.put("pullErrors", 1)
        assertFalse(SyncthingClient.parseStatus(status, JSONObject()).localIndexCurrent)
        status.put("pullErrors", 0).put("state", "scanning")
        assertFalse(SyncthingClient.parseStatus(status, JSONObject()).localIndexCurrent)
        val folders = SyncthingClient.parseFolders(JSONArray("""[{"id":"stable-id","label":"Rename me","path":"/storage/emulated/0/Notes","type":"sendreceive","paused":true,"devices":[{"deviceID":"PEER"}]}]"""))
        assertEquals("stable-id", folders.single().id)
        assertTrue(folders.single().paused)
        assertEquals(listOf("PEER"), folders.single().devices)
    }

    private suspend fun expectIo(block: suspend () -> Any?): IOException {
        try { block() } catch (failure: IOException) { return failure }
        throw AssertionError("Expected IOException")
    }

    private class LocalApi : Closeable {
        val key = "SECRET-${UUID.randomUUID()}"
        @Volatile var identity = "LOCAL-DEVICE"
        @Volatile var redirect = false
        val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val headers: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        private val worker = thread(name = "notepit-sync-test") {
            while (!socket.isClosed) {
                val connection = try { socket.accept() } catch (_: SocketException) { break }
                connection.use { client ->
                    client.soTimeout = 5000
                    val reader = client.getInputStream().bufferedReader()
                    val request = reader.readLine() ?: return@use
                    val path = request.split(' ')[1]
                    var apiKey = ""
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("X-API-Key:", ignoreCase = true)) apiKey = line.substringAfter(':').trim()
                    }
                    paths.add(path)
                    headers.add(apiKey)
                    val code = if (apiKey != key) 401 else if (redirect) 302 else 200
                    val body = when {
                        code != 200 -> "{}"
                        path == "/rest/system/status" -> JSONObject().put("myID", identity).toString()
                        path == "/rest/config/folders" -> """[{"id":"folder one","path":"/local/one","devices":[{"deviceID":"PEER-ONLINE"}]},{"id":"folder-two","path":"/local/two","devices":[{"deviceID":"PEER-OFFLINE"}]}]"""
                        path == "/rest/system/connections" -> """{"connections":{"PEER-ONLINE":{"connected":true},"PEER-OFFLINE":{"connected":false}}}"""
                        path.startsWith("/rest/db/status?") -> """{"state":"idle","needTotalItems":0,"needBytes":0,"pullErrors":0}"""
                        path.startsWith("/rest/folder/errors?") -> """{"errors":[]}"""
                        else -> "{}"
                    }.toByteArray(Charsets.UTF_8)
                    val location = if (code == 302) "Location: http://127.0.0.1:1/must-not-follow\r\n" else ""
                    client.getOutputStream().apply {
                        write("HTTP/1.1 $code Response\r\n${location}Content-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        write(body)
                        flush()
                    }
                }
            }
        }

        override fun close() {
            socket.close()
            worker.join(6000)
        }
    }
}
