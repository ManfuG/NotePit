package io.notepit.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

internal data class SyncEndpoint(val scheme: String, val port: Int) {
    val url: String get() = "$scheme://127.0.0.1:$port"

    companion object {
        fun parse(value: String, allowHttp: Boolean): SyncEndpoint {
            val uri = try { URI(value.trim()) } catch (_: Exception) {
                throw IOException("Enter a loopback URL such as https://127.0.0.1:8384.")
            }
            if (uri.host != "127.0.0.1" || uri.rawUserInfo != null ||
                uri.rawQuery != null || uri.rawFragment != null ||
                (uri.rawPath != null && uri.rawPath != "" && uri.rawPath != "/") ||
                uri.port !in 1..65535 || uri.scheme !in listOf("https", "http")) {
                throw IOException("Only http(s)://127.0.0.1 with an explicit port is allowed; no path, credentials, query or fragment.")
            }
            if (uri.scheme == "http" && !allowHttp) throw IOException("Explicitly accept the HTTP warning first.")
            return SyncEndpoint(uri.scheme, uri.port)
        }
    }
}

internal data class SyncCredentials(val endpoint: SyncEndpoint, val key: String, val pin: String?, val deviceId: String)
internal data class SyncLink(val treeUri: String, val folderId: String, val deviceId: String)
internal data class SyncFolder(
    val id: String, val label: String, val path: String, val type: String,
    val paused: Boolean, val devices: List<String>,
)
internal data class SyncFolderStatus(
    val state: String, val needs: Long, val needBytes: Long, val pullErrors: Long,
    val errors: List<String>, val unavailable: String? = null,
) {
    val localIndexCurrent: Boolean get() = unavailable == null && state == "idle" &&
        needs == 0L && needBytes == 0L && pullErrors == 0L && errors.isEmpty()
}
internal data class SyncSnapshot(
    val deviceId: String, val at: Long, val folders: List<SyncFolder>,
    val connectedDevices: Set<String>, val statuses: Map<String, SyncFolderStatus>,
)

/** Credentials never cross the native boundary. No request bodies or exception details are logged. */
internal class SyncthingClient(context: Context, private val keyAlias: String = "notepit.syncthing.api.v1") {
    private val preferences = context.applicationContext.getSharedPreferences("syncthing", Context.MODE_PRIVATE)

    suspend fun credentials(): SyncCredentials? = withContext(Dispatchers.IO) { loadCredentials() }
    suspend fun links(): List<SyncLink> = withContext(Dispatchers.IO) { loadLinks() }

    suspend fun associate(treeUri: String, folderId: String, deviceId: String) = withContext(Dispatchers.IO) {
        require(treeUri.isNotBlank() && folderId.isNotBlank())
        val links = loadLinks().filterNot { it.treeUri == treeUri } + SyncLink(treeUri, folderId.trim(), deviceId)
        saveLinks(links)
    }

    suspend fun unlink(treeUri: String) = withContext(Dispatchers.IO) {
        saveLinks(loadLinks().filterNot { it.treeUri == treeUri })
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        if (!preferences.edit().remove("credential").commit()) throw IOException("Could not clear API credentials.")
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(keyAlias)) store.deleteEntry(keyAlias)
        // Associations and SAF grants/files deliberately remain intact.
    }

    /** Unauthenticated TLS handshake only. The caller must display and explicitly confirm this pin. */
    suspend fun inspectCertificate(endpoint: SyncEndpoint): String = withContext(Dispatchers.IO) {
        if (endpoint.scheme != "https") throw IOException("HTTP has no certificate to verify.")
        var observed: String? = null
        val manager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { throw CertificateException("Unsupported") }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val certificate = chain.firstOrNull() ?: throw CertificateException("Missing certificate")
                certificate.checkValidity()
                observed = fingerprint(certificate.encoded)
            }
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), SecureRandom()) }
        try {
            Socket().use { tcp ->
                tcp.connect(InetSocketAddress("127.0.0.1", endpoint.port), 4000)
                tcp.soTimeout = 5000
                (tls.socketFactory.createSocket(tcp, "127.0.0.1", endpoint.port, true) as SSLSocket).use { socket ->
                    socket.soTimeout = 5000
                    socket.startHandshake()
                }
            }
            observed ?: throw IOException("No server certificate received.")
        } catch (_: Exception) { throw IOException("Could not inspect the local TLS certificate. Check the Fork service, GUI port and certificate validity.") }
    }

    suspend fun connect(endpoint: SyncEndpoint, key: String, confirmedPin: String?): SyncCredentials = withContext(Dispatchers.IO) {
        validateKey(key)
        if (endpoint.scheme == "https" && !validPin(confirmedPin)) throw IOException("Confirm the certificate fingerprint before connecting.")
        val candidate = SyncCredentials(endpoint, key.trim(), confirmedPin, "")
        val id = JSONObject(request(candidate, "/rest/system/status")).optString("myID")
        if (id.isBlank()) throw IOException("The local service did not identify itself. Credentials were not saved.")
        val credentials = candidate.copy(deviceId = id)
        saveCredentials(credentials)
        credentials
    }

    suspend fun refresh(): SyncSnapshot = withContext(Dispatchers.IO) {
        val credentials = loadCredentials() ?: throw IOException("API monitoring is not connected.")
        val id = JSONObject(request(credentials, "/rest/system/status")).optString("myID")
        if (id != credentials.deviceId) throw IOException("Local Syncthing device identity changed. Disconnect and pair the API again; existing folder links are retained.")
        val folders = parseFolders(JSONArray(request(credentials, "/rest/config/folders")))
        val connected = parseConnections(JSONObject(request(credentials, "/rest/system/connections")))
        val configured = folders.map { it.id }.toSet()
        val statuses = loadLinks().filter { it.deviceId == id && it.folderId in configured }.map { it.folderId }.distinct().associateWith { folder ->
            try {
                val query = "?folder=" + URLEncoder.encode(folder, "UTF-8")
                parseStatus(JSONObject(request(credentials, "/rest/db/status$query")), JSONObject(request(credentials, "/rest/folder/errors$query")))
            } catch (_: IOException) {
                SyncFolderStatus("unknown", -1, -1, -1, emptyList(), "Folder status unavailable; retry Refresh.")
            }
        }
        SyncSnapshot(id, System.currentTimeMillis(), folders, connected, statuses)
    }

    private fun request(credentials: SyncCredentials, path: String): String {
        val connection = URI(credentials.endpoint.url + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 4000
            connection.readTimeout = 6000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            if (connection is HttpsURLConnection) {
                val pin = credentials.pin ?: throw IOException("Certificate trust is missing. Reconnect the API.")
                val manager = object : X509TrustManager {
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { throw CertificateException("Unsupported") }
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                        val certificate = chain.firstOrNull() ?: throw CertificateException("Missing certificate")
                        certificate.checkValidity()
                        if (!pinMatches(pin, certificate.encoded)) throw CertificateException("Certificate pin changed")
                    }
                }
                connection.sslSocketFactory = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), SecureRandom()) }.socketFactory
                // Self-signed Syncthing certificates need not name 127.0.0.1. Identity is the explicit DER pin.
                connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { host, session ->
                    host == "127.0.0.1" && runCatching { pinMatches(pin, session.peerCertificates.first().encoded) }.getOrDefault(false)
                }
                // TLS verifies the pin before the HTTP request (including its admin header) is sent.
            }
            connection.setRequestProperty("X-API-Key", credentials.key)
            val code = connection.responseCode
            if (code == 401 || code == 403) throw IOException("API key was rejected. Copy the current admin API key from Fork settings.")
            if (code !in 200..299) throw IOException("Local Syncthing returned HTTP $code. Check that this is its GUI port.")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (result.length + count > 2_000_000) throw IOException("Local API response is too large.")
                    result.append(buffer, 0, count)
                }
                result.toString()
            }
        } catch (error: IOException) {
            if (error.message?.startsWith("API key was rejected") == true || error.message?.startsWith("Local Syncthing returned HTTP") == true || error.message == "Local API response is too large.") throw error
            throw IOException("Local API unavailable. Check the service, port and certificate; if the certificate changed, disconnect and verify it again.")
        } finally { connection.disconnect() }
    }

    private fun loadCredentials(): SyncCredentials? {
        val encoded = preferences.getString("credential", null) ?: return null
        try {
            val envelope = JSONObject(encoded)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(false), GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)))
            val json = JSONObject(String(cipher.doFinal(Base64.decode(envelope.getString("data"), Base64.NO_WRAP)), Charsets.UTF_8))
            val endpoint = SyncEndpoint.parse(json.getString("url"), json.getBoolean("allowHttp"))
            val pin = json.optString("pin").takeIf { it.isNotEmpty() }
            if (endpoint.scheme == "https" && !validPin(pin)) throw IOException("Invalid stored certificate pin")
            val key = json.getString("key")
            validateKey(key)
            return SyncCredentials(endpoint, key, pin, json.getString("device"))
        } catch (_: Exception) { throw IOException("Saved API credentials cannot be decrypted or are invalid. Disconnect the API, then reconnect. Your files and folder links are unaffected.") }
    }

    private fun saveCredentials(credentials: SyncCredentials) {
        try {
            val payload = JSONObject().put("url", credentials.endpoint.url).put("allowHttp", credentials.endpoint.scheme == "http")
                .put("key", credentials.key).put("pin", credentials.pin ?: "").put("device", credentials.deviceId)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(true))
            val encrypted = cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8))
            val envelope = JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            if (!preferences.edit().putString("credential", envelope.toString()).commit()) throw IOException("Write failed")
        } catch (_: Exception) { throw IOException("Android KeyStore could not securely save API credentials. Monitoring was not connected.") }
    }

    private fun secretKey(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        if (!create) throw IOException("Encryption key is missing")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun loadLinks(): List<SyncLink> {
        val array = JSONArray(preferences.getString("links", "[]"))
        return (0 until array.length()).map { index ->
            val value = array.getJSONObject(index)
            SyncLink(value.getString("tree"), value.getString("folder"), value.optString("device"))
        }
    }

    private fun saveLinks(links: List<SyncLink>) {
        val array = JSONArray()
        links.forEach { array.put(JSONObject().put("tree", it.treeUri).put("folder", it.folderId).put("device", it.deviceId)) }
        if (!preferences.edit().putString("links", array.toString()).commit()) throw IOException("Could not save folder associations.")
    }

    companion object {
        fun fingerprint(der: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(der).joinToString(":") { "%02X".format(it.toInt() and 255) }
        fun validPin(pin: String?): Boolean = pin != null && Regex("[0-9A-F]{2}(:[0-9A-F]{2}){31}").matches(pin)
        fun pinMatches(pin: String, der: ByteArray): Boolean = validPin(pin) && MessageDigest.isEqual(pin.toByteArray(Charsets.US_ASCII), fingerprint(der).toByteArray(Charsets.US_ASCII))
        fun validateKey(key: String) {
            if (key.trim().isEmpty() || key.length > 4096 || key.any { it.code < 33 || it.code > 126 }) throw IOException("Paste a non-empty API key without spaces or line breaks.")
        }
        fun parseFolders(array: JSONArray): List<SyncFolder> = (0 until array.length()).map { index ->
            val value = array.getJSONObject(index)
            val devices = value.optJSONArray("devices") ?: JSONArray()
            SyncFolder(value.getString("id"), value.optString("label"), value.getString("path"), value.optString("type", "sendreceive"), value.optBoolean("paused"),
                (0 until devices.length()).map { devices.getJSONObject(it).getString("deviceID") })
        }
        fun parseConnections(json: JSONObject): Set<String> {
            val connections = json.getJSONObject("connections")
            return connections.keys().asSequence().filter { connections.getJSONObject(it).optBoolean("connected") && !connections.getJSONObject(it).optBoolean("paused") }.toSet()
        }
        fun parseStatus(status: JSONObject, errors: JSONObject): SyncFolderStatus {
            val list = errors.optJSONArray("errors") ?: JSONArray()
            val messages = (0 until list.length()).map { index ->
                val value = list.getJSONObject(index)
                "${value.optString("path")}: ${value.optString("error", "Folder error")}".take(500)
            }.toMutableList()
            // The endpoint may paginate. Never hide the fact that additional errors exist.
            if (errors.optInt("total", list.length()) > list.length()) messages.add("More errors are listed in Syncthing-Fork.")
            return SyncFolderStatus(status.getString("state"), status.getLong("needTotalItems"), status.getLong("needBytes"), status.getLong("pullErrors"), messages)
        }
    }
}
