package io.notepit.app

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.test.mock.MockContentResolver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real resolver/provider streams and durable metadata, not implementation text or wiring. */
@RunWith(AndroidJUnit4::class)
class DocumentsRepositoryTest {
    private lateinit var context: StorageContext
    private lateinit var provider: MemoryDocumentsProvider
    private lateinit var repository: DocumentsRepository
    private lateinit var root: DocumentEntry

    @Before fun setUp(): Unit = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        context = StorageContext(base)
        provider = MemoryDocumentsProvider(File(context.filesDir, "provider"))
        provider.attachInfo(context, ProviderInfo().apply {
            authority = AUTHORITY
            exported = true
            grantUriPermissions = true
            readPermission = Manifest.permission.MANAGE_DOCUMENTS
            writePermission = Manifest.permission.MANAGE_DOCUMENTS
        })
        context.resolver.addProvider(AUTHORITY, provider)
        context.getSharedPreferences("folders", 0).edit().putStringSet("roots", setOf(TREE.toString())).commit()
        repository = DocumentsRepository(context)
        root = repository.roots().single()
    }

    @After fun tearDown() {
        assertFalse("A provider operation ran on the main thread", provider.sawMainThread)
        context.getSharedPreferences("folders", 0).edit().clear().commit()
        context.getSharedPreferences("notepit-library", 0).edit().clear().commit()
        context.getSharedPreferences("notepit-content-versions", 0).edit().clear().commit()
        context.privateDirectory.deleteRecursively()
    }

    @Test fun migratesRootsAndRetainsUnavailableFoldersAndFavoriteParents(): Unit = runBlocking {
        assertTrue(root.available)
        assertFalse(context.getSharedPreferences("folders", 0).contains("roots"))
        val directory = provider.add("root", "Notes", Document.MIME_TYPE_DIR)
        val file = provider.add(directory.id, "first.txt", "text/plain", "hello".toByteArray())
        val entry = entry(file)
        repository.toggleFavorite(entry)
        repository.recordRecent(entry)
        val reopened = DocumentsRepository(context)
        reopened.roots()
        assertEquals(setOf(entry.uri), reopened.favorites())
        assertEquals(entry.uri, reopened.recents().single().uri)
        assertEquals(entry.parentUri, reopened.query(DocumentEntry(entry.uri, "")).parentUri)
        provider.deniedQueries += "root"
        val missing = reopened.roots().single()
        assertFalse(missing.available)
        assertFalse(missing.writable)
        assertEquals("Test folder", missing.name)
        assertEquals(TREE.toString(), DocumentsRepository(context).roots().single().treeUri)
        provider.deniedQueries.clear()
        assertTrue(reopened.roots().single().available)
        reopened.disconnect(root)
        assertTrue(reopened.roots().isEmpty())
        assertTrue(provider.contains(file.id))
        assertTrue(DocumentsRepository(context).roots().isEmpty())
    }

    @Test fun createsExactNamesAndUsesMimeLookupWithoutInventingExtensions(): Unit = runBlocking {
        val unknown = repository.create(root, " exact.name.unregistered_extension ", false)
        assertEquals(" exact.name.unregistered_extension ", unknown.name)
        assertEquals("application/octet-stream", unknown.mime)
        assertEquals("application/pdf", repository.create(root, "Paper.PDF", false).mime)
        provider.changeCreatedName = true
        expect<IOException> { repository.create(root, "kept.txt", false) }
        assertFalse(provider.children("root").any { it.name == "kept.txt.provider" })
        expect<IllegalArgumentException> { repository.create(root, "../bad.txt", false) }
    }

    @Test fun recursivelyCopiesBytesAndMimeIncludingHiddenDataWithoutRemovingSource(): Unit = runBlocking {
        val source = provider.add("root", "project", Document.MIME_TYPE_DIR)
        val nested = provider.add(source.id, "nested", Document.MIME_TYPE_DIR)
        val bytes = ByteArray(210_017) { (it * 31).toByte() }
        provider.add(nested.id, "raw.custom", "application/x-custom", bytes)
        provider.add(source.id, ".stfolder", "application/octet-stream", byteArrayOf(1, 2, 3))
        val destination = provider.add("root", "destination", Document.MIME_TYPE_DIR)
        val progress = mutableListOf<Pair<Int, Int>>()
        val copied = repository.transfer(listOf(entry(source)), entry(destination), false) { done, total -> progress += done to total }.single()
        val newDirectory = provider.node(copied)
        val newNested = provider.children(newDirectory.id).single { it.name == "nested" }
        val newFile = provider.children(newNested.id).single()
        assertArrayEquals(bytes, newFile.file.readBytes())
        assertEquals("application/x-custom", newFile.mime)
        assertTrue(provider.children(newDirectory.id).any { it.name == ".stfolder" })
        assertTrue(provider.contains(source.id))
        assertEquals(listOf(0 to 1, 1 to 1), progress)
    }

    @Test fun rejectsSelfDescendantAndExistingNameWithoutOverwriting(): Unit = runBlocking {
        val source = provider.add("root", "source", Document.MIME_TYPE_DIR)
        val nested = provider.add(source.id, "nested", Document.MIME_TYPE_DIR)
        val file = provider.add(source.id, "keep.txt", "text/plain", "original".toByteArray())
        val before = provider.createdCount
        expect<TransferFailedException> { repository.transfer(listOf(entry(source)), entry(source), false) }
        expect<TransferFailedException> { repository.transfer(listOf(entry(source)), entry(nested), true) }
        expect<TransferFailedException> { repository.transfer(listOf(entry(file)), entry(source), false) }
        assertEquals(before, provider.createdCount)
        assertArrayEquals("original".toByteArray(), file.file.readBytes())
        assertTrue(provider.contains(source.id))
    }

    @Test fun failedIntegrityNeverDeletesSourceAndReportsRetainedPartialCopy(): Unit = runBlocking {
        val bytes = ByteArray(150_003) { it.toByte() }
        val source = provider.add("root", "source.bin", "application/octet-stream", bytes)
        val destination = provider.add("root", "destination", Document.MIME_TYPE_DIR)
        provider.corruptCreatedAfterWrite = true
        provider.denyDeletionOfCreated = true
        val error = expect<TransferFailedException> { repository.transfer(listOf(entry(source)), entry(destination), true) }
        assertTrue(error.completed.isEmpty())
        assertNotNull(error.failures.single().retainedDestination)
        assertTrue(error.message!!.contains("Partial copy retained"))
        assertArrayEquals(bytes, source.file.readBytes())
        assertFalse(provider.deletedIds.contains(source.id))
        assertTrue(provider.contains(source.id))
    }

    @Test fun changedSourceDuringTransferIsNotDeletedAndIncompleteTargetIsCleaned(): Unit = runBlocking {
        val source = provider.add("root", "source.txt", "text/plain", "before".toByteArray())
        val destination = provider.add("root", "destination", Document.MIME_TYPE_DIR)
        provider.beforeRead = { node, count -> if (node.id == source.id && count == 2) node.file.writeText("changed externally") }
        expect<TransferFailedException> { repository.transfer(listOf(entry(source)), entry(destination), true) }
        assertEquals("changed externally", source.file.readText())
        assertFalse(provider.deletedIds.contains(source.id))
        assertTrue(provider.children(destination.id).isEmpty())
    }

    @Test fun successfulMoveRemapsFavoritesAndRecentsAfterFullVerification(): Unit = runBlocking {
        val source = provider.add("root", "source", Document.MIME_TYPE_DIR)
        val file = provider.add(source.id, "note.txt", "text/plain", "retained bytes".toByteArray())
        val destination = provider.add("root", "destination", Document.MIME_TYPE_DIR)
        repository.toggleFavorite(entry(file))
        repository.recordRecent(entry(file))
        val copied = repository.transfer(listOf(entry(source)), entry(destination), true).single()
        assertFalse(provider.contains(source.id))
        val copiedFile = provider.children(provider.node(copied).id).single()
        assertEquals("retained bytes", copiedFile.file.readText())
        assertEquals(setOf(uri(copiedFile.id).toString()), repository.favorites())
        assertEquals(uri(copiedFile.id).toString(), repository.recents().single().uri)
        assertEquals(copied.uri, repository.recents().single().parentUri)
    }

    @Test fun failedSourceDeletionRetainsVerifiedDestination(): Unit = runBlocking {
        val source = provider.add("root", "source.txt", "text/plain", "original".toByteArray())
        val destination = provider.add("root", "destination", Document.MIME_TYPE_DIR)
        provider.deniedDeletes += source.id
        val error = expect<TransferFailedException> { repository.transfer(listOf(entry(source)), entry(destination), true) }
        val retained = error.failures.single().retainedDestination!!
        assertEquals("original", provider.node(retained).file.readText())
        assertEquals("original", source.file.readText())
    }

    @Test fun batchesReportCompletedDocumentsAlongsideFailures(): Unit = runBlocking {
        val good = provider.add("root", "good.txt", "text/plain", "good".toByteArray())
        val bad = provider.add("root", "same.txt", "text/plain", "new".toByteArray())
        val destination = provider.add("root", "destination", Document.MIME_TYPE_DIR)
        val existing = provider.add(destination.id, "same.txt", "text/plain", "must survive".toByteArray())
        val error = expect<TransferFailedException> { repository.transfer(listOf(entry(bad), entry(good)), entry(destination), false) }
        assertEquals(listOf("good.txt"), error.completed.map { it.name })
        assertEquals("same.txt", error.failures.single().source.name)
        assertEquals("must survive", existing.file.readText())
        assertTrue(provider.contains(good.id))
        assertTrue(provider.contains(bad.id))
    }

    @Test fun boundedSnapshotRejectsOversizeStreamsWithoutReplacingPreviousSnapshot(): Unit = runBlocking {
        val original = provider.add("root", "bounded.txt", "text/plain", ByteArray(70_000) { 65 })
        val target = File(context.filesDir, "bounded-snapshot").apply { writeText("retained snapshot") }
        expect<IOException> { repository.snapshot(entry(original), target, 65_536) }
        assertEquals("retained snapshot", target.readText())
        assertFalse(target.parentFile!!.listFiles()!!.any { it.name.startsWith(".snapshot-") })
        assertEquals(hash(original.file.readBytes()), repository.snapshot(entry(original), target, 70_000))
        assertArrayEquals(original.file.readBytes(), target.readBytes())
    }

    @Test fun snapshotAndSaveCommitExactHashesAndRefuseExternalChanges(): Unit = runBlocking {
        val original = provider.add("root", "note.txt", "text/plain", "original".toByteArray())
        val snapshot = File(context.filesDir, "snapshot.bin")
        val revision = repository.snapshot(entry(original), snapshot)
        assertEquals(hash("original".toByteArray()), revision)
        assertEquals("original", snapshot.readText())
        val edited = File(context.filesDir, "edited.bin").apply { writeText("edited document") }
        val saved = repository.save(entry(original), edited, revision)
        assertEquals(hash(edited.readBytes()), saved)
        assertArrayEquals(edited.readBytes(), original.file.readBytes())
        assertTrue(File(context.filesDir, "recovery").listFiles().orEmpty().isEmpty())
        original.file.writeText("changed externally")
        expect<FileConflictException> { repository.save(entry(original), edited, saved) }
        assertEquals("changed externally", original.file.readText())
        assertEquals(1, provider.writeAttempts[original.id])
    }

    @Test fun failedSaveRestoresAndVerifiesOriginal(): Unit = runBlocking {
        val original = provider.add("root", "note.txt", "text/plain", "valuable original".toByteArray())
        val edited = File(context.filesDir, "edited.bin").apply { writeText("replacement") }
        provider.failedWrites += original.id to 1
        expect<IOException> { repository.save(entry(original), edited, hash("valuable original".toByteArray())) }
        assertEquals("valuable original", original.file.readText())
        assertEquals(2, provider.writeAttempts[original.id])
        assertTrue(repository.retainedRecoveryFiles.value.isEmpty())
        assertTrue(File(context.filesDir, "recovery").listFiles().orEmpty().isEmpty())
    }

    @Test fun failedRecoveryRetainsPersistentOriginalAndReportsItsAbsolutePathAfterReopen(): Unit = runBlocking {
        val original = provider.add("root", "note.txt", "text/plain", "irreplaceable".toByteArray())
        val edited = File(context.filesDir, "edited.bin").apply { writeText("replacement") }
        provider.failedWrites += original.id to 1
        provider.failedWrites += original.id to 2
        val error = expect<RecoveryFailedException> { repository.save(entry(original), edited, hash("irreplaceable".toByteArray())) }
        assertEquals("irreplaceable", error.recoveryFile.readText())
        assertTrue(error.recoveryFile.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
        assertFalse(error.recoveryFile.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
        assertTrue(error.message!!.contains(error.recoveryFile.absolutePath))
        val reopened = DocumentsRepository(context)
        reopened.roots()
        assertEquals(listOf(error.recoveryFile.absolutePath), reopened.retainedRecoveryFiles.value)
    }

    @Test fun wrongCommittedBytesTriggerVerifiedRestore(): Unit = runBlocking {
        val original = provider.add("root", "note.txt", "text/plain", "original".toByteArray())
        val edited = File(context.filesDir, "edited.bin").apply { writeText("edited") }
        provider.corruptReadAfterWrite[original.id] = 1
        expect<IOException> { repository.save(entry(original), edited, hash("original".toByteArray())) }
        assertEquals("original", original.file.readText())
        assertEquals(2, provider.writeAttempts[original.id])
    }

    @Test fun cancellingSaveRestoresOriginalWithoutSwallowingCancellation(): Unit = runBlocking {
        val original = provider.add("root", "cancel-save.txt", "text/plain", "original".toByteArray())
        val edited = File(context.filesDir, "edited.bin").apply { writeText("replacement") }
        lateinit var saving: kotlinx.coroutines.Deferred<String>
        provider.beforeRead = { node, count ->
            if (node.id == original.id && count == 3) saving.cancel(CancellationException("Cancel verification"))
        }
        saving = async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            repository.save(entry(original), edited, hash("original".toByteArray()))
        }
        saving.start()
        expect<CancellationException> { saving.await() }
        assertEquals("original", original.file.readText())
        assertEquals(2, provider.writeAttempts[original.id])
        assertTrue(repository.retainedRecoveryFiles.value.isEmpty())
    }

    @Test fun mutationsInvalidateCachedChildrenAndRenameNeverOverwrites(): Unit = runBlocking {
        val first = repository.create(root, "first.txt", false)
        assertEquals(listOf("first.txt"), repository.list(root).toListLast().map { it.name })
        val second = repository.create(root, "second.txt", false)
        assertEquals(setOf("first.txt", "second.txt"), repository.list(root).first().map { it.name }.toSet())
        expect<FileConflictException> { repository.rename(first, "second.txt") }
        val caseRenamed = repository.rename(first, "FIRST.txt")
        assertEquals("FIRST.txt", caseRenamed.name)
        val renamed = repository.rename(caseRenamed, "renamed.txt")
        assertEquals("renamed.txt", renamed.name)
        assertEquals(root.uri, renamed.parentUri)
        assertEquals(setOf("renamed.txt", "second.txt"), repository.list(root).first().map { it.name }.toSet())
        repository.delete(listOf(renamed, second))
        assertTrue(repository.list(root).first().isEmpty())
    }

    @Test fun writeCopyKeepsOriginalAndRejectsMissingParentAndCollisions(): Unit = runBlocking {
        val original = provider.add("root", "original.txt", "text/plain", "original".toByteArray())
        val edited = File(context.filesDir, "edited.bin").apply { writeText("copy bytes") }
        val copied = repository.writeCopy(entry(original), edited, "copy.unknown_extension")
        assertEquals("application/octet-stream", copied.mime)
        assertEquals("copy bytes", provider.node(copied).file.readText())
        assertEquals("original", original.file.readText())
        expect<FileConflictException> { repository.writeCopy(entry(original), edited, original.name) }
        expect<IOException> { repository.writeCopy(entry(original).copy(parentUri = null), edited, "another.txt") }
    }

    @Test fun cancellationPropagatesAndDoesNotDeleteSource(): Unit = runBlocking {
        val source = provider.add("root", "cancel.bin", "application/octet-stream", ByteArray(200_000) { it.toByte() })
        val destination = provider.add("root", "destination", Document.MIME_TYPE_DIR)
        lateinit var transfer: Job
        provider.beforeRead = { node, _ -> if (node.id == source.id) transfer.cancel(CancellationException("Cancel transfer")) }
        transfer = launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            repository.transfer(listOf(entry(source)), entry(destination), true)
        }
        transfer.start()
        transfer.join()
        assertTrue(transfer.isCancelled)
        assertTrue(provider.contains(source.id))
        assertFalse(provider.deletedIds.contains(source.id))
        assertTrue(provider.children(destination.id).isEmpty())
    }

    @Test fun directListingIsProgressiveAndSearchHidesOnlySyncInternals(): Unit = runBlocking {
        repeat(130) { provider.add("root", "note-$it.txt", "text/plain") }
        provider.add("root", ".stfolder", Document.MIME_TYPE_DIR)
        val versions = provider.add("root", ".stversions", Document.MIME_TYPE_DIR)
        provider.add(versions.id, "note-hidden.txt", "text/plain")
        provider.add("root", ".syncthing.note.tmp", "text/plain")
        val conflict = provider.add("root", "note.sync-conflict-20261021.txt", "text/plain")
        val nested = provider.add("root", "nested", Document.MIME_TYPE_DIR)
        provider.add(nested.id, "note-deep.txt", "text/plain")
        val closedBefore = provider.closedCursors
        val firstBatch = repository.list(root).take(1).first()
        assertEquals(64, firstBatch.size)
        assertTrue(provider.closedCursors > closedBefore)
        val complete = repository.list(root).toListLast()
        assertFalse(complete.any { it.name == "note-deep.txt" })
        assertFalse(complete.any { it.name.startsWith(".st") || it.name.startsWith(".syncthing") })
        assertTrue(complete.any { it.uri == uri(conflict.id).toString() && it.conflict })
        val found = repository.search("note", listOf(root))
        assertTrue(found.any { it.name == "note-deep.txt" })
        assertTrue(found.any { it.conflict })
        assertFalse(found.any { it.name == "note-hidden.txt" || it.name.startsWith(".syncthing") })
    }

    @Test fun permissionAndProviderFlagsAreConservativeBoundaries(): Unit = runBlocking {
        val original = provider.add("root", "readonly.txt", "text/plain", "original".toByteArray())
        original.flags = Document.FLAG_SUPPORTS_DELETE
        val edited = File(context.filesDir, "edited.bin").apply { writeText("new") }
        expect<IOException> { repository.save(entry(original), edited, hash("original".toByteArray())) }
        assertNull(provider.writeAttempts[original.id])
        context.writePermission = false
        assertFalse(repository.query(entry(original)).writable)
        expect<SecurityException> { repository.create(root, "no-grant.txt", false) }
        context.writePermission = true
        provider.deniedQueries += original.id
        expect<SecurityException> { repository.query(entry(original)) }
        provider.deniedQueries.clear()
        provider.nodes.getValue("root").flags = 0
        expect<IOException> { repository.create(root, "not-created.txt", false) }
        assertEquals("original", original.file.readText())
        assertFalse(provider.children("root").any { it.name == "not-created.txt" })
    }

    private fun entry(node: MemoryDocumentsProvider.Node) = DocumentEntry(
        uri(node.id).toString(), node.name, node.mime, node.mime == Document.MIME_TYPE_DIR,
        writable = true, size = node.file.length(), treeUri = TREE.toString(),
        parentUri = node.parent?.let { uri(it).toString() }, flags = node.flags,
    )

    private suspend inline fun <reified T : Throwable> expect(crossinline block: suspend () -> Unit): T {
        try { block() } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("Expected ${T::class.java.name}, got ${error.javaClass.name}", error)
        }
        throw AssertionError("Expected ${T::class.java.name}")
    }

    private suspend fun kotlinx.coroutines.flow.Flow<List<DocumentEntry>>.toListLast(): List<DocumentEntry> {
        var last = emptyList<DocumentEntry>()
        collect { last = it }
        return last
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private class StorageContext(base: Context) : ContextWrapper(base) {
        private val prefix = "storage-test-${UUID.randomUUID()}-"
        val privateDirectory = File(base.filesDir, prefix).apply { mkdirs() }
        val resolver = MockContentResolver(this)
        var writePermission = true
        override fun getApplicationContext(): Context = this
        override fun getContentResolver() = resolver
        override fun getFilesDir() = File(privateDirectory, "files").apply { mkdirs() }
        override fun getCacheDir() = File(privateDirectory, "cache").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(privateDirectory, "no-backup").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(prefix + name, mode)
        override fun checkUriPermission(uri: Uri?, pid: Int, uid: Int, modeFlags: Int): Int =
            if (modeFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0 && !writePermission) PackageManager.PERMISSION_DENIED else PackageManager.PERMISSION_GRANTED
        override fun checkPermission(permission: String, pid: Int, uid: Int) = PackageManager.PERMISSION_GRANTED
    }

    /** Opaque document IDs deliberately prevent path-prefix ancestry assumptions. */
    private class MemoryDocumentsProvider(private val directory: File) : DocumentsProvider() {
        data class Node(val id: String, var name: String, val mime: String, val parent: String?, val file: File, var flags: Int)
        val nodes = LinkedHashMap<String, Node>()
        val deniedQueries = HashSet<String>()
        val deniedDeletes = HashSet<String>()
        val failedWrites = HashSet<Pair<String, Int>>()
        val writeAttempts = HashMap<String, Int>()
        val corruptReadAfterWrite = HashMap<String, Int>()
        val deletedIds = ArrayList<String>()
        private val createdIds = HashSet<String>()
        private val readCounts = HashMap<String, Int>()
        var beforeRead: ((Node, Int) -> Unit)? = null
        var corruptCreatedAfterWrite = false
        var denyDeletionOfCreated = false
        var changeCreatedName = false
        var createdCount = 0
        var closedCursors = 0
        var sawMainThread = false

        init {
            directory.mkdirs()
            nodes["root"] = Node("root", "Test folder", Document.MIME_TYPE_DIR, null, File(directory, "root").apply { mkdirs() }, directoryFlags())
        }

        fun add(parent: String, name: String, mime: String, bytes: ByteArray = byteArrayOf()): Node {
            val id = UUID.randomUUID().toString()
            val file = File(directory, id)
            if (mime == Document.MIME_TYPE_DIR) file.mkdirs() else file.writeBytes(bytes)
            return Node(id, name, mime, parent, file, if (mime == Document.MIME_TYPE_DIR) directoryFlags() else fileFlags()).also { nodes[id] = it }
        }
        fun contains(id: String) = nodes.containsKey(id)
        fun node(entry: DocumentEntry) = nodes.getValue(DocumentsContract.getDocumentId(Uri.parse(entry.uri)))
        fun children(id: String) = nodes.values.filter { it.parent == id }
        override fun onCreate() = true
        private fun providerThread() { if (Looper.myLooper() == Looper.getMainLooper()) sawMainThread = true }
        private fun cursor(columns: Array<out String>) = object : MatrixCursor(columns) {
            override fun close() { super.close(); closedCursors++ }
        }
        private fun row(cursor: MatrixCursor, node: Node) {
            cursor.addRow(cursor.columnNames.map { column ->
                when (column) {
                    Document.COLUMN_DOCUMENT_ID -> node.id
                    Document.COLUMN_DISPLAY_NAME -> node.name
                    Document.COLUMN_MIME_TYPE -> node.mime
                    Document.COLUMN_FLAGS -> node.flags
                    Document.COLUMN_SIZE -> if (node.mime == Document.MIME_TYPE_DIR) 0L else node.file.length()
                    Document.COLUMN_LAST_MODIFIED -> node.file.lastModified()
                    else -> null
                }
            }.toTypedArray())
        }
        override fun queryRoots(projection: Array<out String>?): Cursor {
            providerThread()
            return cursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS)).apply {
                addRow(columnNames.map { column -> when (column) {
                    Root.COLUMN_ROOT_ID -> "test"
                    Root.COLUMN_DOCUMENT_ID -> "root"
                    Root.COLUMN_TITLE -> "Test folder"
                    Root.COLUMN_FLAGS -> Root.FLAG_SUPPORTS_CREATE
                    else -> null
                } }.toTypedArray())
            }
        }
        override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
            providerThread()
            if (documentId in deniedQueries) throw SecurityException("Permission revoked")
            val node = nodes[documentId] ?: throw FileNotFoundException(documentId)
            return cursor(projection ?: COLUMNS).apply { row(this, node) }
        }
        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
            providerThread()
            if (parentDocumentId in deniedQueries) throw SecurityException("Permission revoked")
            if (parentDocumentId !in nodes) throw FileNotFoundException(parentDocumentId)
            return cursor(projection ?: COLUMNS).apply { children(parentDocumentId).forEach { row(this, it) } }
        }
        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
            providerThread()
            signal?.throwIfCanceled()
            if (documentId in deniedQueries) throw SecurityException("Permission revoked")
            val node = nodes[documentId] ?: throw FileNotFoundException(documentId)
            if (mode.contains('w')) {
                val attempt = (writeAttempts[documentId] ?: 0) + 1
                writeAttempts[documentId] = attempt
                if (documentId to attempt in failedWrites) {
                    node.file.writeText("partially written")
                    throw FileNotFoundException("Injected write failure")
                }
                if (corruptCreatedAfterWrite && documentId in createdIds) corruptReadAfterWrite[documentId] = 1
            } else {
                val reads = (readCounts[documentId] ?: 0) + 1
                readCounts[documentId] = reads
                beforeRead?.invoke(node, reads)
                if ((writeAttempts[documentId] ?: 0) > 0 && (corruptReadAfterWrite[documentId] ?: 0) > 0) {
                    corruptReadAfterWrite[documentId] = corruptReadAfterWrite.getValue(documentId) - 1
                    node.file.writeText("corrupt bytes")
                }
            }
            return ParcelFileDescriptor.open(node.file, ParcelFileDescriptor.parseMode(mode))
        }
        override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            providerThread()
            if (nodes.getValue(parentDocumentId).flags and Document.FLAG_DIR_SUPPORTS_CREATE == 0) throw FileNotFoundException("Read only")
            if (children(parentDocumentId).any { it.name.equals(displayName, true) }) throw FileNotFoundException("Already exists")
            val node = add(parentDocumentId, if (changeCreatedName) "$displayName.provider" else displayName, mimeType)
            createdIds += node.id
            createdCount++
            return node.id
        }
        override fun renameDocument(documentId: String, displayName: String): String {
            providerThread()
            val node = nodes.getValue(documentId)
            if (children(node.parent!!).any { it.id != node.id && it.name.equals(displayName, true) }) throw FileNotFoundException("Already exists")
            node.name = displayName
            return node.id
        }
        override fun deleteDocument(documentId: String) {
            providerThread()
            if (documentId in deniedDeletes || denyDeletionOfCreated && documentId in createdIds) throw FileNotFoundException("Deletion denied")
            val node = nodes[documentId] ?: throw FileNotFoundException(documentId)
            children(documentId).forEach { deleteDocument(it.id) }
            node.file.deleteRecursively()
            nodes.remove(documentId)
            deletedIds += documentId
        }
        override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
            var current = nodes[documentId]?.parent
            while (current != null) {
                if (current == parentDocumentId) return true
                current = nodes[current]?.parent
            }
            return false
        }
        companion object {
            private fun directoryFlags() = Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
            private fun fileFlags() = Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
            private val COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
                Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
        }
    }

    companion object {
        private const val AUTHORITY = "io.notepit.storage.tests"
        private val TREE = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")
        private fun uri(id: String) = DocumentsContract.buildDocumentUriUsingTree(TREE, id)
    }
}
