package io.notepit.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A failed batch never hides already completed items or a destination that could not be removed. */
data class TransferFailure(val source: DocumentEntry, val reason: String, val retainedDestination: DocumentEntry? = null)
class TransferFailedException(val completed: List<DocumentEntry>, val failures: List<TransferFailure>) : IOException(
    "${completed.size} completed; ${failures.size} failed. " + failures.joinToString("\n") {
        "${it.source.name}: ${it.reason}" + (it.retainedDestination?.let { target -> " Partial copy retained: ${target.uri}" } ?: "")
    },
)
class RecoveryFailedException(val recoveryFile: File, cause: Throwable) : IOException(
    "Save and recovery failed. The original is retained in private app storage: ${recoveryFile.absolutePath}", cause,
)

/** SAF is not transactional. Mutations are serialized; bytes are verified before deleting any source. */
class DocumentsRepository(context: Context) {
    val context: Context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val changeLock = Any()
    private val cacheLock = Any()
    private var cacheGeneration = 0L
    private val contentVersions = this.context.getSharedPreferences("notepit-content-versions", Context.MODE_PRIVATE)
    private val mutableChanges = MutableStateFlow(0L)
    val changes: StateFlow<Long> = mutableChanges.asStateFlow()
    private val mutableRecoveryFiles = MutableStateFlow<List<String>>(emptyList())
    val retainedRecoveryFiles: StateFlow<List<String>> = mutableRecoveryFiles.asStateFlow()
    @Volatile private var loaded = false
    @Volatile private var rootEntries: List<DocumentEntry> = emptyList()
    @Volatile private var favoriteEntries: List<DocumentEntry> = emptyList()
    @Volatile private var recentEntries: List<DocumentEntry> = emptyList()
    private val cache = LinkedHashMap<String, List<DocumentEntry>>(16, .75f, true)
    private val recoveryDirectory get() = File(context.filesDir, "recovery")

    /** Last published grant state; search reads never wait for a provider's blocking stream. */
    internal fun connectedRootsSnapshot(): List<DocumentEntry> = rootEntries

    internal suspend fun ready() {
        if (!loaded) withContext(Dispatchers.IO) { mutex.withLock { load() } }
    }

    internal fun contentVersion(entry: DocumentEntry): Long = contentVersions.getLong(identity(entry), 0L)

    private fun invalidateContent(uri: Uri) {
        val key = try { "${uri.authority}:${DocumentsContract.getDocumentId(uri)}" }
        catch (_: IllegalArgumentException) { return }
        synchronized(changeLock) {
            if (!contentVersions.edit().putLong(key, contentVersions.getLong(key, 0L) + 1).commit()) {
                throw IOException("Could not record the document change.")
            }
        }
    }

    init {
        scope.launch {
            try { mutex.withLock { load() } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* Suspending operations retry and report the actual storage error. */ }
        }
    }

    /** Notifications are hints, not writes. Share physical observation across UI/index subscribers. */
    private val providerHints = callbackFlow<Unit> {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) { onChange(selfChange, null, 0) }
            override fun onChange(selfChange: Boolean, uri: Uri?) { onChange(selfChange, uri, 0) }
            override fun onChange(selfChange: Boolean, uri: Uri?, flags: Int) {
                launch {
                    clearCache()
                    if (flags and android.content.ContentResolver.NOTIFY_UPDATE != 0) {
                        uri?.let { invalidateContent(it) }
                        changed()
                    } else trySend(Unit)
                }
            }
        }
        val registration = launch {
            ready()
            var registered = emptySet<String>()
            changes.collect {
                val watched = rootEntries.map { Uri.Builder().scheme("content").authority(Uri.parse(it.treeUri).authority).build().toString() }.toSet()
                if (registered != watched) {
                    resolver.unregisterContentObserver(observer)
                    watched.forEach { uri ->
                        try { resolver.registerContentObserver(Uri.parse(uri), true, observer) }
                        catch (_: SecurityException) { /* Lost grants/provider observation restrictions. */ }
                    }
                    registered = watched
                }
            }
        }
        awaitClose { registration.cancel(); resolver.unregisterContentObserver(observer) }
    }.shareIn(scope, SharingStarted.WhileSubscribed(), replay = 0)

    private fun clearCache() {
        synchronized(cacheLock) { cache.clear(); cacheGeneration++ }
    }

    private fun changed() {
        clearCache()
        synchronized(changeLock) { mutableChanges.value += 1 }
    }

    private suspend fun load() {
        if (loaded) return
        currentCoroutineContext().ensureActive()
        val preferences = context.getSharedPreferences("notepit-library", Context.MODE_PRIVATE)
        val value = preferences.getString("state", null)?.let { JSONObject(it) } ?: JSONObject()
        fun entries(key: String): List<DocumentEntry> {
            val array = value.optJSONArray(key) ?: return emptyList()
            return (0 until array.length()).map { DocumentEntry.fromJson(array.getJSONObject(it)) }
        }
        rootEntries = entries("roots")
        favoriteEntries = entries("favorites")
        recentEntries = entries("recents").take(50)
        val old = context.getSharedPreferences("folders", Context.MODE_PRIVATE)
        val legacy = old.getStringSet("roots", emptySet()).orEmpty().toSet()
        val migrated = rootEntries.toMutableList()
        for (raw in legacy) {
            currentCoroutineContext().ensureActive()
            if (migrated.any { it.treeUri == raw }) continue
            val tree = Uri.parse(raw)
            try {
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val seed = DocumentEntry(uri.toString(), DocumentsContract.getTreeDocumentId(tree), directory = true, treeUri = raw)
                migrated += try { queryRaw(seed) } catch (error: CancellationException) { throw error }
                catch (_: Exception) { seed.copy(available = false) }
            } catch (error: IllegalArgumentException) {
                throw IOException("A previously connected folder has an invalid URI: $raw", error)
            }
        }
        rootEntries = migrated
        persist()
        if (legacy.isNotEmpty() && !old.edit().remove("roots").commit()) throw IOException("Could not complete the folder migration.")
        recoveryDirectory.listFiles()?.filter { it.name.endsWith(".json") }?.let { descriptors ->
            mutableRecoveryFiles.value = descriptors.mapNotNull { descriptor ->
                val original = File(descriptor.parentFile, descriptor.name.removeSuffix(".json") + ".original")
                original.takeIf { it.isFile }?.absolutePath
            }
        }
        loaded = true
        changed()
    }

    private fun persist() {
        fun array(entries: List<DocumentEntry>) = JSONArray().apply { entries.forEach { put(it.toJson()) } }
        val state = JSONObject().put("roots", array(rootEntries)).put("favorites", array(favoriteEntries))
            .put("recents", array(recentEntries))
        if (!context.getSharedPreferences("notepit-library", Context.MODE_PRIVATE).edit().putString("state", state.toString()).commit()) {
            throw IOException("Could not persist the document library.")
        }
    }

    suspend fun roots(): List<DocumentEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            load()
            val previous = rootEntries
            rootEntries = rootEntries.map { entry ->
                try { queryRaw(entry) } catch (error: CancellationException) { throw error }
                catch (_: Exception) { entry.copy(available = false, writable = false) }
            }
            // Metadata reads must not feed observers back into another browse/index refresh.
            if (rootEntries != previous) persist()
            rootEntries.toList()
        }
    }

    suspend fun connect(tree: Uri, flags: Int): DocumentEntry = withContext(Dispatchers.IO) {
        mutex.withLock {
            load()
            require(tree.scheme == "content" && DocumentsContract.isTreeUri(tree)) { "Select a document folder." }
            val grant = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            require(grant and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) { "The folder must allow reading." }
            resolver.takePersistableUriPermission(tree, grant)
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val seed = rootEntries.firstOrNull { it.treeUri == tree.toString() }
                ?: DocumentEntry(uri.toString(), DocumentsContract.getTreeDocumentId(tree), directory = true, treeUri = tree.toString())
            val entry = try { queryRaw(seed) } catch (error: CancellationException) { throw error }
                catch (_: Exception) { seed.copy(available = false, writable = false) }
            require(entry.directory) { "Select a folder, not a file." }
            rootEntries = rootEntries.filterNot { it.treeUri == tree.toString() } + entry
            persist()
            changed()
            entry
        }
    }

    suspend fun disconnect(root: DocumentEntry): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            load()
            val match = rootEntries.firstOrNull { it.treeUri == root.treeUri || sameDocument(it, root) } ?: return@withLock
            // Persist removal first: a failed preferences write must not silently revoke a connected root.
            rootEntries = rootEntries.filterNot { it.treeUri == match.treeUri }
            favoriteEntries = favoriteEntries.filterNot { it.treeUri == match.treeUri }
            recentEntries = recentEntries.filterNot { it.treeUri == match.treeUri }
            persist()
            try {
                resolver.persistedUriPermissions.firstOrNull { it.uri.toString() == match.treeUri }?.let { permission ->
                    val flags = (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                        (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                    resolver.releasePersistableUriPermission(permission.uri, flags)
                }
            } finally { changed() }
        }
    }

    fun list(parent: DocumentEntry): Flow<List<DocumentEntry>> = flow {
        mutex.withLock { load() }
        val generation = synchronized(cacheLock) { cacheGeneration }
        val cached = synchronized(cacheLock) { cache[parent.uri]?.toList() }
        if (cached != null) emit(cached)
        val freshParent = queryRaw(parent)
        require(freshParent.directory) { "This document is not a folder." }
        val results = ArrayList<DocumentEntry>()
        queryCursor(childrenUri(freshParent)).use { cursor ->
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val entry = cursorEntry(cursor, freshParent)
                if (hidden(entry.name)) continue
                results += entry
                if (results.size % 64 == 0) emit(results.toList())
            }
        }
        currentCoroutineContext().ensureActive()
        val complete = results.toList()
        if (complete.size <= 2_000) synchronized(cacheLock) {
            if (cacheGeneration == generation) {
                cache[parent.uri] = complete
                while (cache.size > 32 || cache.values.sumOf { it.size } > 10_000) cache.remove(cache.keys.first())
            }
        }
        emit(complete)
    }.flowOn(Dispatchers.IO)

    suspend fun query(entry: DocumentEntry): DocumentEntry = withContext(Dispatchers.IO) {
        mutex.withLock {
            load()
            val known = (favoriteEntries + recentEntries + rootEntries).firstOrNull { sameDocument(it, entry) }
            queryRaw(entry.copy(
                name = entry.name.ifEmpty { known?.name.orEmpty() },
                treeUri = entry.treeUri.ifEmpty { known?.treeUri.orEmpty() },
                parentUri = entry.parentUri ?: known?.parentUri,
            ))
        }
    }

    suspend fun create(parent: DocumentEntry, name: String, directory: Boolean): DocumentEntry = withContext(Dispatchers.IO) {
        mutex.withLock {
            load()
            try { createExact(queryRaw(parent), validName(name), if (directory) Document.MIME_TYPE_DIR else mime(name)) }
            finally { changed() }
        }
    }

    suspend fun rename(entry: DocumentEntry, name: String): DocumentEntry = withContext(Dispatchers.IO) {
        mutex.withLock {
            load()
            val original = queryRaw(entry)
            val requested = validName(name)
            if (original.name == requested) return@withLock original
            val parent = parentOf(original)
            requireFlag(original, Document.FLAG_SUPPORTS_RENAME, "renaming")
            ensureNoCollision(parent, requested, original)
            try {
                val renamedUri = DocumentsContract.renameDocument(resolver, Uri.parse(original.uri), requested)
                    ?: throw IOException("The provider could not rename ${original.name}.")
                val renamed = queryRaw(original.copy(uri = renamedUri.toString()))
                if (renamed.name != requested) {
                    val restored = try { DocumentsContract.renameDocument(resolver, renamedUri, original.name) }
                    catch (error: CancellationException) { throw error } catch (_: Exception) { null }
                    throw IOException("The provider changed the requested filename to ${renamed.name}. " +
                        if (restored == null) "The renamed document remains at $renamedUri." else "The original name was restored.")
                }
                replaceMetadata(mapOf(identity(original) to renamed))
                renamed
            } finally { changed() }
        }
    }

    suspend fun delete(entries: List<DocumentEntry>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            load()
            val failures = ArrayList<String>()
            try {
                for (entry in entries.distinctBy { identity(it) }) {
                    currentCoroutineContext().ensureActive()
                    try {
                        val current = queryRaw(entry)
                        requireFlag(current, Document.FLAG_SUPPORTS_DELETE, "deletion")
                        if (!DocumentsContract.deleteDocument(resolver, Uri.parse(current.uri))) throw IOException("The provider refused deletion.")
                        removeMetadata(current)
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { failures += "${entry.name}: ${error.message ?: error.javaClass.simpleName}" }
                }
                if (failures.isNotEmpty()) throw IOException("Some documents could not be deleted:\n" + failures.joinToString("\n"))
            } finally { changed() }
        }
    }

    suspend fun transfer(
        entries: List<DocumentEntry>, destination: DocumentEntry, move: Boolean,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<DocumentEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            load()
            val parent = queryRaw(destination)
            require(parent.directory) { "The destination must be a folder." }
            requireFlag(parent, Document.FLAG_DIR_SUPPORTS_CREATE, "creating documents")
            val selected = entries.distinctBy { identity(it) }
            val completed = ArrayList<DocumentEntry>()
            val failures = ArrayList<TransferFailure>()
            try {
                onProgress(0, selected.size)
                for ((index, selectedEntry) in selected.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    var created: DocumentEntry? = null
                    var deletingSource = false
                    try {
                        val source = queryRaw(selectedEntry)
                        if (move) requireFlag(source, Document.FLAG_SUPPORTS_DELETE, "moving")
                        preventDescendant(source, parent)
                        val copied = copyNode(source, parent, HashSet()) { created = it }
                        verifyNode(copied)
                        if (move) {
                            // Once deletion begins, a failed provider may have removed only part of a directory.
                            // Retain the fully verified destination, even when that deletion reports failure.
                            deletingSource = true
                            currentCoroutineContext().ensureActive()
                            if (!DocumentsContract.deleteDocument(resolver, Uri.parse(source.uri))) throw IOException("Copy verified, but source deletion failed; the destination was retained.")
                            val replacements = HashMap<String, DocumentEntry>()
                            fun map(node: CopiedNode) { replacements[identity(node.source)] = node.destination; node.children.forEach { map(it) } }
                            map(copied)
                            replaceMetadata(replacements)
                        }
                        completed += copied.destination
                    } catch (error: CancellationException) {
                        withContext(NonCancellable) {
                            created?.let { target ->
                                if (deletingSource || !cleanup(target)) error.addSuppressed(IOException("Verified or partial destination retained: ${target.uri}"))
                            }
                        }
                        throw error
                    } catch (error: Exception) {
                        val retained = created?.takeIf { deletingSource || !cleanup(it) }
                        failures += TransferFailure(selectedEntry, error.message ?: error.javaClass.simpleName, retained)
                    }
                    onProgress(index + 1, selected.size)
                }
                if (failures.isNotEmpty()) throw TransferFailedException(completed.toList(), failures.toList())
                completed.toList()
            } finally { changed() }
        }
    }

    suspend fun snapshot(entry: DocumentEntry, target: File, maxBytes: Long = Long.MAX_VALUE): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            load()
            require(maxBytes >= 0) { "The snapshot size limit must be nonnegative." }
            requirePrivate(target)
            if (queryRaw(entry).directory) throw IOException("A folder cannot be opened as a document.")
            val parent = target.parentFile ?: throw IOException("No snapshot directory.")
            if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Could not create the snapshot directory.")
            val temporary = File.createTempFile(".snapshot-", ".tmp", parent)
            try {
                val revision = readDocument(Uri.parse(entry.uri)) { input ->
                    FileOutputStream(temporary).use { output ->
                        val hash = copyBytes(input, output, maxBytes)
                        output.fd.sync()
                        hash
                    }
                }
                currentCoroutineContext().ensureActive()
                replaceLocal(temporary, target)
                revision
            } finally { temporary.delete() }
        }
    }

    suspend fun save(entry: DocumentEntry, source: File, expectedRevision: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            load()
            val current = queryRaw(entry)
            require(!current.directory) { "A folder cannot be saved as a file." }
            requireFlag(current, Document.FLAG_SUPPORTS_WRITE, "writing")
            if (!source.isFile) throw IOException("The edited document is unavailable.")
            if (!recoveryDirectory.isDirectory && !recoveryDirectory.mkdirs()) throw IOException("Could not create persistent recovery storage.")
            val id = UUID.randomUUID().toString()
            val backup = File(recoveryDirectory, "$id.original")
            val staged = File(recoveryDirectory, "$id.pending")
            val descriptor = File(recoveryDirectory, "$id.json")
            var writing = false
            var retain = false
            try {
                val originalHash = readDocument(Uri.parse(current.uri)) { input ->
                    FileOutputStream(backup).use { output ->
                        val hash = copyBytes(input, output)
                        output.fd.sync()
                        hash
                    }
                }
                if (originalHash != expectedRevision) throw FileConflictException("The file changed outside NotePit. Your edits are retained; reopen it or save a copy.")
                val newHash = FileInputStream(source).use { input ->
                    FileOutputStream(staged).use { output ->
                        val hash = copyBytes(input, output)
                        output.fd.sync()
                        hash
                    }
                }
                val info = JSONObject().put("entry", current.toJson()).put("revision", originalHash)
                    .put("replacementRevision", newHash).put("created", System.currentTimeMillis())
                FileOutputStream(descriptor).use { output -> output.write(info.toString().toByteArray(Charsets.UTF_8)); output.fd.sync() }
                // Recheck after staging: never truncate a revision different from the user's snapshot.
                if (hashDocument(Uri.parse(current.uri)) != originalHash) throw FileConflictException("The file changed while preparing the save. Your edits are retained; save a copy.")
                currentCoroutineContext().ensureActive()
                // Persist before writing; snapshots share this mutex and cannot observe a half-save.
                invalidateContent(Uri.parse(current.uri))
                writing = true
                writeDocument(Uri.parse(current.uri), staged)
                if (hashDocument(Uri.parse(current.uri)) != newHash) throw IOException("The provider did not retain the complete saved bytes.")
                newHash
            } catch (error: Exception) {
                if (writing) {
                    val recoveryError = withContext(NonCancellable + Dispatchers.IO) {
                        try {
                            writeDocument(Uri.parse(current.uri), backup)
                            val expected = FileInputStream(backup).use { copyBytes(it, null) }
                            if (hashDocument(Uri.parse(current.uri)) != expected) throw IOException("Restored bytes failed verification.")
                            null
                        } catch (failure: Exception) { failure }
                    }
                    if (recoveryError != null) {
                        retain = true
                        mutableRecoveryFiles.value = (mutableRecoveryFiles.value + backup.absolutePath).distinct()
                        val failure = RecoveryFailedException(backup, recoveryError)
                        failure.addSuppressed(error)
                        if (error is CancellationException) { error.addSuppressed(failure); throw error }
                        throw failure
                    }
                }
                throw error
            } finally {
                staged.delete()
                if (!retain) { backup.delete(); descriptor.delete() }
                changed()
            }
        }
    }

    suspend fun writeCopy(entry: DocumentEntry, source: File, name: String): DocumentEntry = withContext(Dispatchers.IO) {
        mutex.withLock {
            load()
            val parent = parentOf(entry)
            val requested = validName(name)
            if (!source.isFile) throw IOException("The edited document is unavailable.")
            var created: DocumentEntry? = null
            try {
                created = createExact(parent, requested, mime(requested))
                val expected = FileInputStream(source).use { copyBytes(it, null) }
                writeDocument(Uri.parse(created.uri), source)
                if (hashDocument(Uri.parse(created.uri)) != expected || FileInputStream(source).use { copyBytes(it, null) } != expected) {
                    throw IOException("The copy failed byte-integrity verification.")
                }
                queryRaw(created)
            } catch (error: CancellationException) {
                withContext(NonCancellable) {
                    created?.let { if (!cleanup(it)) error.addSuppressed(IOException("Partial copy retained: ${it.uri}")) }
                }
                throw error
            } catch (error: Exception) {
                if (created != null && !cleanup(created)) throw IOException("${error.message} Partial copy retained: ${created.uri}", error)
                throw error
            } finally { changed() }
        }
    }

    fun favorites(): Set<String> = favoriteEntries.mapTo(LinkedHashSet()) { it.uri }
    fun recents(): List<DocumentEntry> = recentEntries.toList()

    suspend fun toggleFavorite(entry: DocumentEntry) = withContext(Dispatchers.IO) {
        mutex.withLock {
            load()
            favoriteEntries = if (favoriteEntries.any { sameDocument(it, entry) }) favoriteEntries.filterNot { sameDocument(it, entry) }
            else favoriteEntries + entry
            persist()
            changed()
        }
    }

    suspend fun recordRecent(entry: DocumentEntry) = withContext(Dispatchers.IO) {
        mutex.withLock {
            load()
            recentEntries = (listOf(entry) + recentEntries.filterNot { sameDocument(it, entry) }).take(50)
            persist()
            changed()
        }
    }

    suspend fun search(query: String, roots: List<DocumentEntry>): List<DocumentEntry> = withContext(Dispatchers.IO) {
        mutex.withLock { load() }
        val needle = query.trim()
        if (needle.isEmpty()) return@withContext emptyList()
        val results = ArrayList<DocumentEntry>()
        val visited = HashSet<String>()
        val pending = java.util.ArrayDeque<DocumentEntry>()
        roots.filter { it.available }.forEach { pending.add(it) }
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val parent = pending.removeFirst()
            if (!visited.add(identity(parent))) continue
            for (entry in childEntries(parent, hideInternal = true)) {
                if (entry.name.contains(needle, ignoreCase = true)) results += entry
                if (entry.directory) pending.add(entry)
            }
        }
        results
    }

    fun observe(onChange: () -> Unit, onProviderChange: () -> Unit = onChange): Closeable {
        val closed = AtomicBoolean(false)
        val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        observerScope.launch {
            ready()
            changes.collect {
                withContext(Dispatchers.Main.immediate) { if (!closed.get()) onChange() }
            }
        }
        observerScope.launch {
            providerHints.collect {
                withContext(Dispatchers.Main.immediate) { if (!closed.get()) onProviderChange() }
            }
        }
        return Closeable { if (closed.compareAndSet(false, true)) observerScope.cancel() }
    }

    private suspend fun queryRaw(entry: DocumentEntry): DocumentEntry {
        val uri = checkedUri(entry.uri)
        return queryCursor(uri).use { cursor ->
            if (!cursor.moveToFirst()) throw IOException("${entry.name.ifEmpty { "The document" }} is unavailable. Reconnect its folder or refresh.")
            fromCursor(cursor, uri, entry.treeUri.ifEmpty { treeFor(uri) }, entry.parentUri)
        }
    }

    private fun treeFor(uri: Uri): String {
        if (DocumentsContract.isTreeUri(uri)) return DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri)).toString()
        return rootEntries.firstOrNull { Uri.parse(it.uri).authority == uri.authority }?.treeUri.orEmpty()
    }

    private fun fromCursor(cursor: Cursor, uri: Uri, tree: String, parent: String?): DocumentEntry {
        fun text(column: String, fallback: String = ""): String {
            val index = cursor.getColumnIndex(column)
            return if (index < 0 || cursor.isNull(index)) fallback else cursor.getString(index)
        }
        fun number(column: String): Long {
            val index = cursor.getColumnIndex(column)
            return if (index < 0 || cursor.isNull(index)) 0 else cursor.getLong(index)
        }
        val name = text(Document.COLUMN_DISPLAY_NAME)
        val type = text(Document.COLUMN_MIME_TYPE).ifEmpty { mime(name) }
        val directory = type == Document.MIME_TYPE_DIR
        val flags = number(Document.COLUMN_FLAGS).toInt()
        val permissionUri = Uri.parse(tree.ifEmpty { uri.toString() })
        val writableGrant = context.checkUriPermission(permissionUri, android.os.Process.myPid(), android.os.Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
        return DocumentEntry(uri.toString(), name, type, directory,
            writableGrant && flags and (if (directory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE) != 0,
            number(Document.COLUMN_SIZE), number(Document.COLUMN_LAST_MODIFIED), tree, parent, true, flags)
    }

    private fun cursorEntry(cursor: Cursor, parent: DocumentEntry): DocumentEntry {
        val id = cursor.getString(cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID))
        val tree = Uri.parse(parent.treeUri.ifEmpty { parent.uri })
        return fromCursor(cursor, DocumentsContract.buildDocumentUriUsingTree(tree, id), parent.treeUri, parent.uri)
    }

    private fun childrenUri(parent: DocumentEntry): Uri {
        require(parent.directory) { "This document is not a folder." }
        return DocumentsContract.buildChildDocumentsUriUsingTree(Uri.parse(parent.treeUri.ifEmpty { parent.uri }), DocumentsContract.getDocumentId(Uri.parse(parent.uri)))
    }

    private suspend fun childEntries(parent: DocumentEntry, hideInternal: Boolean = false): List<DocumentEntry> {
        val result = ArrayList<DocumentEntry>()
        queryCursor(childrenUri(parent)).use { cursor ->
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val entry = cursorEntry(cursor, parent)
                if (!hideInternal || !hidden(entry.name)) result += entry
            }
        }
        return result
    }

    private suspend fun queryCursor(uri: Uri): Cursor = providerCall { signal ->
        resolver.query(uri, COLUMNS, null, null, null, signal) ?: throw IOException("The provider could not read $uri.")
    }

    /** Cancellation reaches providers during binder query/open; streaming also checks the job every chunk. */
    private suspend fun <T : Closeable> providerCall(block: (CancellationSignal) -> T): T = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { scope.launch { signal.cancel() } }
        try {
            val result = block(signal)
            if (continuation.isActive) continuation.resume(result) { _, resource, _ ->
                scope.launch { try { resource.close() } catch (_: IOException) { } }
            }
            else result.close()
        } catch (error: Exception) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

    private suspend fun <T> readDocument(uri: Uri, block: suspend (InputStream) -> T): T {
        val descriptor = providerCall { signal -> resolver.openFileDescriptor(uri, "r", signal) ?: throw IOException("The provider denied reading $uri.") }
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { block(it) }
    }

    private suspend fun writeDocument(uri: Uri, file: File) {
        val descriptor = providerCall { signal -> resolver.openFileDescriptor(uri, "rwt", signal) ?: throw IOException("The provider denied writing $uri.") }
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output -> FileInputStream(file).use { copyBytes(it, output) } }
    }

    private suspend fun hashDocument(uri: Uri): String = readDocument(uri) { copyBytes(it, null) }

    private suspend fun copyBytes(input: InputStream, output: OutputStream?, maxBytes: Long = Long.MAX_VALUE): String {
        val cancellation = currentCoroutineContext()
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                // A provider stream can block in read/write; close it from IO, never from a UI cancel handler.
                scope.launch {
                    try { input.close() } catch (_: IOException) { }
                    try { output?.close() } catch (_: IOException) { }
                }
            }
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                    cancellation.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    if (count.toLong() > maxBytes - copied) throw IOException("The document exceeds the $maxBytes-byte search indexing limit.")
                    copied += count
                    digest.update(buffer, 0, count)
                    output?.write(buffer, 0, count)
                }
                cancellation.ensureActive()
                val bytes = digest.digest()
                val hash = buildString(64) {
                    bytes.forEach { append(HEX[(it.toInt() and 255) ushr 4]); append(HEX[it.toInt() and 15]) }
                }
                if (continuation.isActive) continuation.resume(hash)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }

    private suspend fun parentOf(entry: DocumentEntry): DocumentEntry {
        val uri = entry.parentUri ?: throw IOException("The parent folder is unknown. Open this document from its folder first.")
        return queryRaw(DocumentEntry(uri, "", directory = true, treeUri = entry.treeUri))
    }

    private suspend fun ensureNoCollision(parent: DocumentEntry, name: String, excluding: DocumentEntry? = null): List<DocumentEntry> {
        val children = childEntries(parent)
        if (children.any { it.name.equals(name, ignoreCase = true) && (excluding == null || !sameDocument(it, excluding)) }) {
            throw FileConflictException("A document named $name already exists. Nothing was overwritten.")
        }
        return children
    }

    private suspend fun createExact(parent: DocumentEntry, name: String, type: String): DocumentEntry {
        require(parent.directory) { "The destination must be a folder." }
        requireFlag(parent, Document.FLAG_DIR_SUPPORTS_CREATE, "creating documents")
        val existing = ensureNoCollision(parent, name)
        currentCoroutineContext().ensureActive()
        val uri = DocumentsContract.createDocument(resolver, Uri.parse(parent.uri), type, name)
            ?: throw IOException("The provider could not create $name.")
        val seed = DocumentEntry(uri.toString(), name, type, type == Document.MIME_TYPE_DIR, treeUri = parent.treeUri, parentUri = parent.uri)
        if (existing.any { sameDocument(it, seed) }) throw IOException("The provider returned an existing document; it was not modified by NotePit.")
        try {
            val created = queryRaw(seed)
            if (created.name != name) throw IOException("The provider changed the requested filename to ${created.name}; exact filenames are required.")
            if (created.directory != (type == Document.MIME_TYPE_DIR)) throw IOException("The provider created the wrong document type.")
            return created
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                if (!cleanup(seed)) error.addSuppressed(IOException("Created document retained: ${seed.uri}"))
            }
            throw error
        } catch (error: Exception) {
            if (!cleanup(seed)) throw IOException("${error.message} Created document retained: $uri", error)
            throw error
        }
    }

    private data class CopiedNode(val source: DocumentEntry, val destination: DocumentEntry, val hash: String?, val children: List<CopiedNode>)

    private suspend fun copyNode(source: DocumentEntry, parent: DocumentEntry, visited: MutableSet<String>, created: (DocumentEntry) -> Unit = {}): CopiedNode {
        currentCoroutineContext().ensureActive()
        if (!visited.add(identity(source))) throw IOException("The provider exposed a document cycle or duplicate; transfer stopped safely.")
        val target = createExact(parent, source.name, source.mime)
        created(target)
        if (source.directory) {
            val children = childEntries(source).map { copyNode(it, target, visited) }
            return CopiedNode(source, target, null, children)
        }
        val descriptor = providerCall { signal -> resolver.openFileDescriptor(Uri.parse(target.uri), "rwt", signal) ?: throw IOException("The provider denied writing ${target.name}.") }
        val hash = ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output -> readDocument(Uri.parse(source.uri)) { copyBytes(it, output) } }
        if (hashDocument(Uri.parse(target.uri)) != hash) throw IOException("${source.name} failed byte-integrity verification.")
        return CopiedNode(source, queryRaw(target), hash, emptyList())
    }

    private suspend fun verifyNode(node: CopiedNode) {
        currentCoroutineContext().ensureActive()
        val source = queryRaw(node.source)
        val destination = queryRaw(node.destination)
        if (source.name != node.source.name || destination.name != node.source.name || source.directory != destination.directory) {
            throw FileConflictException("The source or destination changed during transfer.")
        }
        if (source.directory) {
            val originalIds = node.children.map { identity(it.source) }.toSet()
            if (childEntries(source).map { identity(it) }.toSet() != originalIds ||
                childEntries(destination).map { identity(it) }.toSet() != node.children.map { identity(it.destination) }.toSet()) {
                throw FileConflictException("Folder contents changed during transfer; the source was not deleted.")
            }
            node.children.forEach { verifyNode(it) }
        } else if (hashDocument(Uri.parse(source.uri)) != node.hash || hashDocument(Uri.parse(destination.uri)) != node.hash) {
            throw FileConflictException("${source.name} changed during transfer; the source was not deleted.")
        }
    }

    private suspend fun preventDescendant(source: DocumentEntry, destination: DocumentEntry) {
        if (sameDocument(source, destination)) throw IOException("A document cannot be transferred into itself.")
        if (!source.directory || Uri.parse(source.uri).authority != Uri.parse(destination.uri).authority) return
        val pending = java.util.ArrayDeque<DocumentEntry>()
        val visited = HashSet<String>()
        pending.add(source)
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val parent = pending.removeFirst()
            if (!visited.add(identity(parent))) throw IOException("The provider exposed a folder cycle; transfer stopped safely.")
            for (child in childEntries(parent)) {
                if (sameDocument(child, destination)) throw IOException("A folder cannot be transferred into one of its descendants.")
                if (child.directory) pending.add(child)
            }
        }
    }

    private fun cleanup(entry: DocumentEntry): Boolean = try { DocumentsContract.deleteDocument(resolver, Uri.parse(entry.uri)) }
    catch (error: CancellationException) { throw error } catch (_: Exception) { false }

    private fun replaceMetadata(replacements: Map<String, DocumentEntry>) {
        fun update(entries: List<DocumentEntry>) = entries.map { replacements[identity(it)] ?: it }
        rootEntries = update(rootEntries)
        favoriteEntries = update(favoriteEntries)
        recentEntries = update(recentEntries)
        persist()
    }

    private fun removeMetadata(entry: DocumentEntry) {
        val known = rootEntries + favoriteEntries + recentEntries
        val removed = hashSetOf(identity(entry))
        var added: Boolean
        do {
            added = false
            known.forEach { candidate ->
                if (candidate.parentUri != null && identity(candidate.copy(uri = candidate.parentUri)) in removed && removed.add(identity(candidate))) added = true
            }
        } while (added)
        rootEntries = rootEntries.filterNot { identity(it) in removed }
        favoriteEntries = favoriteEntries.filterNot { identity(it) in removed }
        recentEntries = recentEntries.filterNot { identity(it) in removed }
        persist()
    }

    private fun requireFlag(entry: DocumentEntry, flag: Int, operation: String) {
        if (entry.flags and flag == 0) throw IOException("${entry.name} does not support $operation.")
        val permissionUri = Uri.parse(entry.treeUri.ifEmpty { entry.uri })
        if (context.checkUriPermission(permissionUri, android.os.Process.myPid(), android.os.Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("${entry.name} has no write grant. Reconnect its folder with write access.")
        }
    }

    private fun checkedUri(raw: String): Uri = Uri.parse(raw).also { require(it.scheme == "content" && !it.authority.isNullOrEmpty()) { "Invalid document URI." } }
    private fun identity(entry: DocumentEntry): String {
        val uri = Uri.parse(entry.uri)
        return try { "${uri.authority}:${DocumentsContract.getDocumentId(uri)}" } catch (_: IllegalArgumentException) { entry.uri }
    }
    private fun sameDocument(first: DocumentEntry, second: DocumentEntry) = identity(first) == identity(second)
    private fun hidden(name: String) = name == ".stfolder" || name == ".stversions" || name.startsWith(".syncthing", ignoreCase = true)
    private fun validName(name: String): String {
        require(name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' }) { "Enter a valid filename without path separators." }
        return name
    }
    private fun mime(name: String): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT)) ?: "application/octet-stream"
    private fun requirePrivate(file: File) {
        val path = file.canonicalPath
        require(listOf(context.filesDir, context.cacheDir, context.noBackupFilesDir).any { path.startsWith(it.canonicalPath + File.separator) }) { "Document snapshots must use private app storage." }
    }
    private fun replaceLocal(source: File, target: File) {
        try { Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }

    companion object {
        private const val HEX = "0123456789abcdef"
        private val COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
    }
}
