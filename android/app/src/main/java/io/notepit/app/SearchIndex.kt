package io.notepit.app

import android.content.ContentValues
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract.Document
import android.database.sqlite.SQLiteDatabase
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.PushbackInputStream
import java.io.PushbackReader
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ln
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class SearchHit(
    val entry: DocumentEntry, val path: String, val page: Int = 0, val line: Int = 0,
    val endLine: Int = 0, val text: String, val score: Double,
    val semantic: Boolean = false, val query: String = "",
)

data class SearchStatus(
    val busy: Boolean = false, val documents: Int = 0, val passages: Int = 0,
    val message: String = "", val failures: Int = 0,
)

/** Private, disposable index. It never writes to a provider or to an editor's live document. */
class SearchIndex(private val repository: DocumentsRepository, private val semanticEngine: SemanticEngine) {
    private val mutex = Mutex()
    private val refreshMutex = Mutex()
    @Volatile private var refreshJob: Job? = null
    private val closed = AtomicBoolean(false)
    private var database: SQLiteDatabase? = null
    private val mutableStatus = MutableStateFlow(SearchStatus())
    val status: StateFlow<SearchStatus> = mutableStatus.asStateFlow()
    private val context get() = repository.context
    private val indexFile get() = File(context.noBackupFilesDir, "search/index.sqlite")

    private fun db(): SQLiteDatabase {
        check(!closed.get()) { "Search is closed." }
        database?.let { return it }
        indexFile.parentFile!!.mkdirs()
        return SQLiteDatabase.openOrCreateDatabase(indexFile, null).also { db ->
            db.execSQL("PRAGMA foreign_keys=ON")
            db.execSQL("CREATE TABLE IF NOT EXISTS documents (uri TEXT PRIMARY KEY, root TEXT NOT NULL, entry TEXT NOT NULL, path TEXT NOT NULL, revision TEXT NOT NULL, generation INTEGER NOT NULL, warning TEXT NOT NULL DEFAULT '')")
            db.execSQL("CREATE TABLE IF NOT EXISTS passages (id INTEGER PRIMARY KEY AUTOINCREMENT, uri TEXT NOT NULL REFERENCES documents(uri) ON DELETE CASCADE, page INTEGER NOT NULL, line INTEGER NOT NULL, end_line INTEGER NOT NULL, text TEXT NOT NULL, vector BLOB)")
            db.execSQL("CREATE INDEX IF NOT EXISTS passages_document ON passages(uri)")
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS terms USING fts4(text, tokenize=unicode61)")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS passage_delete AFTER DELETE ON passages BEGIN DELETE FROM terms WHERE docid=old.id; END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS passage_insert AFTER INSERT ON passages BEGIN INSERT INTO terms(docid,text) VALUES(new.id,new.text); END")
            database = db
        }
    }

    private suspend fun <T> withDatabase(block: suspend (SQLiteDatabase) -> T): T = mutex.withLock {
        val db = db()
        try { block(db) }
        finally { if (closed.get()) { database?.close(); database = null } }
    }

    /** Reconcile metadata; read contents only for new/changed sources or an explicit rebuild. */
    suspend fun refresh(force: Boolean = false) = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            check(!closed.get()) { "Search is closed." }
            refreshJob = currentCoroutineContext()[Job]
            mutableStatus.value = mutableStatus.value.copy(busy = true, failures = 0, message = "Indexing connected folders…")
            var failures = 0
            var lastFailure = ""
            fun excluded(path: String, error: Exception) {
                failures++
                lastFailure = "$path: ${error.message ?: "unreadable document"}"
                mutableStatus.value = mutableStatus.value.copy(failures = failures)
            }
            val snapshot = File(context.cacheDir, "search-snapshot-${System.nanoTime()}")
            try {
                val generation = withDatabase { scalar(it, "SELECT COALESCE(MAX(generation),0)+1 FROM documents") }
                val roots = repository.roots()
                val rootUris = roots.map { it.treeUri }.toSet()
                withDatabase { db ->
                    removeDisconnected(db, rootUris)
                    if (!semanticEngine.status.value.installed && !semanticEngine.status.value.busy) db.execSQL("UPDATE passages SET vector=NULL WHERE vector IS NOT NULL")
                    mutableStatus.value = mutableStatus.value.copy(documents = scalar(db, "SELECT COUNT(*) FROM documents").toInt(), passages = scalar(db, "SELECT COUNT(*) FROM passages").toInt())
                }
                for (root in roots) {
                    currentCoroutineContext().ensureActive()
                    if (!root.available) {
                        withDatabase { it.delete("documents", "root=?", arrayOf(root.treeUri)) }
                        excluded(root.name, IOException("Folder unavailable or permission lost"))
                        continue
                    }
                    val folders = ArrayDeque<Pair<DocumentEntry, String>>()
                    val visited = HashSet<String>()
                    folders.add(root to root.name)
                    while (folders.isNotEmpty()) {
                        currentCoroutineContext().ensureActive()
                        val (folder, path) = folders.removeLast()
                        if (!visited.add(folder.uri)) continue
                        try {
                            var children = emptyList<DocumentEntry>()
                            repository.list(folder).collect { children = it }
                            for (entry in children) {
                                currentCoroutineContext().ensureActive()
                                val sourcePath = "$path/${entry.name}"
                                if (entry.directory) {
                                    folders.add(entry to sourcePath)
                                    continue
                                }
                                if (!supported(entry)) continue
                                try {
                                    val maximum = if (entry.pdf || entry.mime == "application/pdf") MAX_PDF_BYTES else MAX_TEXT_BYTES
                                    if (entry.size > maximum) throw IOException("Excluded: exceeds ${maximum / 1024 / 1024} MiB indexing limit")
                                    val version = repository.contentVersion(entry)
                                    val previous = withDatabase { db ->
                                        db.rawQuery("SELECT entry,revision,warning FROM documents WHERE uri=?", arrayOf(entry.uri)).use {
                                            if (it.moveToFirst()) {
                                                val metadata = JSONObject(it.getString(0))
                                                IndexedSource(DocumentEntry.fromJson(metadata), it.getString(1), metadata.optLong("contentVersion"), it.getString(2))
                                            } else null
                                        }
                                    }
                                    val formatChanged = previous != null && (previous.entry.mime != entry.mime || previous.entry.pdf != entry.pdf)
                                    val revision = if (force || previous == null || formatChanged || previous.version != version ||
                                        previous.entry.size != entry.size || previous.entry.modified != entry.modified) {
                                        repository.snapshot(entry, snapshot, maximum)
                                    } else previous.revision
                                    var warning = previous?.warning.orEmpty()
                                    if (force || previous?.revision != revision || formatChanged) {
                                        replaceDocument(entry, sourcePath, revision, generation, snapshot, version)
                                        withDatabase { db ->
                                            warning = db.rawQuery("SELECT warning FROM documents WHERE uri=?", arrayOf(entry.uri)).use { it.moveToFirst(); it.getString(0) }
                                            mutableStatus.value = mutableStatus.value.copy(documents = scalar(db, "SELECT COUNT(*) FROM documents").toInt(), passages = scalar(db, "SELECT COUNT(*) FROM passages").toInt())
                                        }
                                    } else {
                                        withDatabase { db ->
                                            db.update("documents", ContentValues().apply {
                                                put("entry", entry.toJson().put("contentVersion", version).toString()); put("path", sourcePath)
                                                put("root", entry.treeUri); put("generation", generation)
                                            }, "uri=?", arrayOf(entry.uri))
                                        }
                                    }
                                    if (warning.isNotEmpty()) excluded(sourcePath, IOException(warning))
                                    if (semanticEngine.status.value.installed) {
                                        try { embedMissing(entry.uri) }
                                        catch (error: CancellationException) { throw error }
                                        catch (error: Exception) { excluded(sourcePath, IOException("Keyword index retained; embedding failed: ${error.message}", error)) }
                                    }
                                } catch (error: CancellationException) { throw error }
                                catch (error: Exception) {
                                    // Never serve a previous revision when the replacement cannot be read/indexed.
                                    withDatabase { it.delete("documents", "uri=?", arrayOf(entry.uri)) }
                                    excluded(sourcePath, error)
                                } finally { snapshot.delete() }
                            }
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) { excluded(path, error) }
                    }
                    currentCoroutineContext().ensureActive()
                    // Only a completed traversal reconciles deletions. Failed subtrees are excluded, not kept stale.
                    withDatabase { it.delete("documents", "root=? AND generation<>?", arrayOf(root.treeUri, generation.toString())) }
                }
                val connected = repository.roots().map { it.treeUri }.toSet()
                withDatabase { db ->
                    removeDisconnected(db, connected)
                    mutableStatus.value = SearchStatus(
                        documents = scalar(db, "SELECT COUNT(*) FROM documents").toInt(),
                        passages = scalar(db, "SELECT COUNT(*) FROM passages").toInt(), failures = failures,
                        message = if (failures == 0) "Index up to date" else "$failures exclusions/errors. $lastFailure",
                    )
                }
            } catch (error: CancellationException) {
                mutableStatus.value = mutableStatus.value.copy(busy = false, message = "Indexing paused; refresh to finish", failures = failures)
                throw error
            } catch (error: Exception) {
                mutableStatus.value = mutableStatus.value.copy(busy = false, failures = failures + 1, message = "Indexing failed: ${error.message}")
                throw error
            } finally {
                snapshot.delete()
                refreshJob = null
            }
        }
    }

    /** Extraction is disk-spooled without a SQL lock; only a complete document is published. */
    private suspend fun replaceDocument(entry: DocumentEntry, path: String, revision: String, generation: Long, snapshot: File, version: Long) {
        val staged = File.createTempFile("search-passages-", ".tmp", context.cacheDir)
        var count = 0
        var warning = ""
        try {
            DataOutputStream(staged.outputStream().buffered()).use { output ->
                suspend fun append(text: String, page: Int, line: Int, endLine: Int) {
                    if (text.isBlank()) return
                    currentCoroutineContext().ensureActive()
                    if (++count > MAX_PASSAGES) throw IOException("Excluded: exceeds $MAX_PASSAGES passages per document")
                    output.writeInt(page); output.writeInt(line); output.writeInt(endLine); output.writeUTF(text)
                }
                if (entry.pdf || entry.mime == "application/pdf") {
                    PDFBoxResourceLoader.init(context)
                    PDDocument.load(snapshot, MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)).use { pdf ->
                        if (pdf.isEncrypted) throw IOException("Excluded: encrypted PDF")
                        if (!pdf.currentAccessPermission.canExtractContent()) throw IOException("Excluded: PDF does not permit text extraction")
                        if (pdf.numberOfPages > MAX_PDF_PAGES) throw IOException("Excluded: exceeds $MAX_PDF_PAGES PDF pages")
                        val stripper = PDFTextStripper().apply { sortByPosition = true }
                        var emptyPages = 0
                        for (page in 1..pdf.numberOfPages) {
                            currentCoroutineContext().ensureActive()
                            stripper.startPage = page; stripper.endPage = page
                            val writer = PageWriter(MAX_PAGE_CHARS, currentCoroutineContext())
                            stripper.writeText(pdf, writer)
                            val text = writer.toString()
                            if (text.isBlank()) emptyPages++
                            else chunks(PushbackReader(StringReader(text), 1), false) { passage, _, _ -> append(passage, page, 0, 0) }
                        }
                        if (count == 0) throw IOException("Excluded: scanned/image-only PDF; offline text extraction cannot perform OCR")
                        if (emptyPages > 0) warning = "$emptyPages PDF pages have no extractable text (blank/image-only); no OCR"
                    }
                } else {
                    textReader(snapshot).use { reader -> chunks(reader, true) { text, line, endLine -> append(text, 0, line, endLine) } }
                }
            }
            currentCoroutineContext().ensureActive()
            withDatabase { db ->
                db.beginTransaction()
                try {
                    db.delete("documents", "uri=?", arrayOf(entry.uri))
                    db.insertOrThrow("documents", null, ContentValues().apply {
                        put("uri", entry.uri); put("root", entry.treeUri); put("entry", entry.toJson().put("contentVersion", version).toString())
                        put("path", path); put("revision", revision); put("generation", generation); put("warning", warning)
                    })
                    DataInputStream(staged.inputStream().buffered()).use { input ->
                        repeat(count) {
                            currentCoroutineContext().ensureActive()
                            db.insertOrThrow("passages", null, ContentValues().apply {
                                put("uri", entry.uri); put("page", input.readInt()); put("line", input.readInt())
                                put("end_line", input.readInt()); put("text", input.readUTF())
                            })
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
        } finally { staged.delete() }
    }

    private suspend fun embedMissing(uri: String) {
        // One vector/passage at a time; no corpus-sized in-memory embedding cache.
        var after = 0L
        while (semanticEngine.status.value.installed) {
            currentCoroutineContext().ensureActive()
            val pending = withDatabase { db ->
                db.rawQuery("SELECT id,text FROM passages WHERE uri=? AND vector IS NULL AND id>? ORDER BY id LIMIT 1", arrayOf(uri, after.toString())).use {
                    if (it.moveToFirst()) it.getLong(0) to it.getString(1) else null
                }
            } ?: break
            after = pending.first
            val vector = semanticEngine.encode(pending.second)
            withDatabase { db ->
                if (semanticEngine.status.value.installed) db.update("passages", ContentValues().apply { put("vector", vectorBytes(vector)) }, "id=? AND uri=? AND vector IS NULL", arrayOf(after.toString(), uri))
            }
        }
    }

    suspend fun search(query: String, semantic: Boolean = false, limit: Int = 100): List<SearchHit> = withContext(Dispatchers.IO) {
        require(query.length <= MAX_QUERY_CHARS) { "Search supports up to $MAX_QUERY_CHARS characters." }
        if (query.isBlank() || limit <= 0) return@withContext emptyList()
        val encoded = if (semantic) {
            check(semanticEngine.status.value.installed) { "Install the offline model to use hybrid search." }
            semanticEngine.encode(query)
        } else null
        mutex.withLock {
            val db = db()
            try {
                val roots = repository.connectedRootsSnapshot().filter { it.available }.map { it.treeUri }.toSet()
                removeDisconnected(db, roots)
                val count = limit.coerceAtMost(100)
                val lexical = top(count)
                val distinct = HashMap<String, Ranked>()
                // User input is always quoted token data, never FTS operators or syntax.
                val terms = TOKEN.findAll(query).map { "\"${it.value}\"" }.toList()
                suspend fun collectCandidates(sql: String, arguments: Array<String>?) {
                    db.rawQuery(sql, arguments).use { cursor ->
                        while (cursor.moveToNext()) {
                            currentCoroutineContext().ensureActive()
                            val text = cursor.getString(1)
                            val position = text.indexOf(query, ignoreCase = true)
                            if (position < 0) continue
                            val score = if (cursor.isNull(2)) 0.0 else bm25(cursor.getBlob(2))
                            val line = cursor.getInt(5) + text.substring(0, position).count { it == '\n' }
                            val key = "${cursor.getString(3)}:${cursor.getInt(4)}:${if (cursor.getInt(4) > 0) 0 else line}"
                            val item = Ranked(cursor.getLong(0), score)
                            val previous = distinct[key]
                            if (previous != null) {
                                if (RANKING.compare(item, previous) >= 0) continue
                                lexical.remove(previous)
                            }
                            offer(lexical, item, count)
                            if (item in lexical) distinct[key] = item
                            if (distinct.size > count) distinct.entries.removeAll { it.value !in lexical }
                        }
                    }
                }
                if (terms.isNotEmpty()) collectCandidates(
                    "SELECT p.id,p.text,matchinfo(terms,'pcnalx'),p.uri,p.page,p.line FROM terms JOIN passages p ON p.id=terms.docid WHERE terms MATCH ?",
                    // Whitespace is conjunction in both legacy and enhanced native FTS4 syntax.
                    arrayOf(terms.joinToString(" ")),
                )
                // FTS whole tokens cannot represent every literal substring/code identifier.
                // Stream the fallback with the same bounded heap; BM25 candidates retain priority.
                if (lexical.size < count || lexical.peek()?.score == 0.0) collectCandidates(
                    "SELECT p.id,p.text,NULL,p.uri,p.page,p.line FROM passages p ORDER BY p.id", null,
                )
                val keyword = lexical.toList().sortedWith(RANKING)
                val scores = LinkedHashMap<Long, Double>()
                keyword.forEachIndexed { rank, result -> scores[result.id] = if (semantic) 1.0 / (60 + rank + 1) else result.score }
                if (semantic) {
                    val dense = top(count)
                    db.rawQuery("SELECT id,vector FROM passages WHERE vector IS NOT NULL", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            currentCoroutineContext().ensureActive()
                            val score = cosine(encoded!!, cursor.getBlob(1))
                            if (score.isFinite()) offer(dense, Ranked(cursor.getLong(0), score), count)
                        }
                    }
                    dense.toList().sortedWith(RANKING).forEachIndexed { rank, result ->
                        scores[result.id] = (scores[result.id] ?: 0.0) + 1.0 / (60 + rank + 1)
                    }
                }
                val result = ArrayList<SearchHit>(count)
                val validated = HashMap<String, DocumentEntry?>()
                val revealed = HashSet<String>()
                for ((id, score) in scores.entries.sortedWith(compareByDescending<Map.Entry<Long, Double>> { it.value }.thenBy { it.key })) {
                    currentCoroutineContext().ensureActive()
                    val hit = db.rawQuery("SELECT d.entry,d.path,p.page,p.line,p.end_line,p.text FROM passages p JOIN documents d ON d.uri=p.uri WHERE p.id=?", arrayOf(id.toString())).use {
                        if (!it.moveToFirst()) null else literalHit(SearchHit(DocumentEntry.fromJson(JSONObject(it.getString(0))), it.getString(1), it.getInt(2), it.getInt(3), it.getInt(4), it.getString(5), score, semantic, query), query)
                    } ?: continue
                    if (!validated.containsKey(hit.entry.uri)) {
                        validated[hit.entry.uri] = try { accessible(hit.entry)?.takeIf { it.available && it.treeUri in roots } }
                        catch (error: CancellationException) { throw error }
                        catch (_: Exception) { null }
                    }
                    val entry = validated[hit.entry.uri]
                    if (entry == null) { db.delete("documents", "uri=?", arrayOf(hit.entry.uri)); continue }
                    val key = if (!hit.semantic) "${entry.uri}:${hit.page}:${hit.line}:${hit.endLine}" else "semantic:$id"
                    if (!revealed.add(key)) continue
                    result += hit.copy(entry = entry)
                    if (result.size == count) break
                }
                // A disconnect while querying must not leak results from its released grant.
                val connected = repository.connectedRootsSnapshot().filter { it.available }.map { it.treeUri }.toSet()
                result.filter { it.entry.treeUri in connected }
            } finally { if (closed.get()) { database?.close(); database = null } }
        }
    }

    fun close() {
        closed.set(true)
        refreshJob?.cancel()
        if (mutex.tryLock()) {
            try { database?.close(); database = null } finally { mutex.unlock() }
        }
    }

    /** Provider validation deliberately avoids the repository's snapshot/mutation mutex. */
    private suspend fun accessible(entry: DocumentEntry): DocumentEntry? = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        try {
            val present = context.contentResolver.query(Uri.parse(entry.uri), arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null, signal)?.use { it.moveToFirst() } == true
            if (continuation.isActive) continuation.resume(if (present) entry else null)
        } catch (error: Exception) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

    private fun removeDisconnected(db: SQLiteDatabase, roots: Set<String>) {
        val stale = ArrayList<String>()
        db.rawQuery("SELECT DISTINCT root FROM documents", null).use { while (it.moveToNext()) if (it.getString(0) !in roots) stale += it.getString(0) }
        stale.forEach { db.delete("documents", "root=?", arrayOf(it)) }
    }

    private data class IndexedSource(val entry: DocumentEntry, val revision: String, val version: Long, val warning: String)
    private data class Ranked(val id: Long, val score: Double)
    private fun top(limit: Int) = PriorityQueue<Ranked>(limit, compareBy<Ranked> { it.score }.thenByDescending { it.id })
    private fun offer(heap: PriorityQueue<Ranked>, item: Ranked, limit: Int) {
        if (heap.size < limit) heap.add(item)
        else if (RANKING.compare(item, heap.peek()) < 0) { heap.poll(); heap.add(item) }
    }

    private class PageWriter(private val maximum: Int, private val cancellation: kotlin.coroutines.CoroutineContext) : java.io.Writer() {
        private val text = StringBuilder()
        override fun write(buffer: CharArray, offset: Int, length: Int) {
            cancellation.ensureActive()
            if (length > maximum - text.length) throw IOException("Excluded: PDF page exceeds $maximum extracted characters")
            text.append(buffer, offset, length)
        }
        override fun flush() = Unit
        override fun close() = Unit
        override fun toString() = text.toString()
    }

    private fun literalHit(hit: SearchHit, query: String): SearchHit {
        val start = hit.text.indexOf(query, ignoreCase = true)
        if (start < 0) return hit
        if (hit.page > 0) return hit.copy(semantic = false)
        val line = hit.line + hit.text.substring(0, start).count { it == '\n' }
        val endLine = line + query.count { it == '\n' }
        val from = hit.text.lastIndexOf('\n', start - 1).let { if (it < 0) 0 else it + 1 }
        val through = hit.text.indexOf('\n', start + query.length).let { if (it < 0) hit.text.length else it }
        return hit.copy(line = line, endLine = endLine, text = hit.text.substring(from, through), semantic = false)
    }

    companion object {
        const val MAX_TEXT_BYTES = 16L * 1024 * 1024
        const val MAX_PDF_BYTES = 64L * 1024 * 1024
        const val MAX_PDF_PAGES = 2_000
        const val MAX_QUERY_CHARS = 256
        private const val MAX_PASSAGES = 25_000
        private const val MAX_PAGE_CHARS = 1_000_000
        internal const val CHUNK_CHARS = 512
        internal const val OVERLAP_CHARS = 256
        private val TOKEN = Regex("[\\p{L}\\p{N}\\p{M}]+")
        private val RANKING = compareByDescending<Ranked> { it.score }.thenBy { it.id }
        private fun scalar(db: SQLiteDatabase, query: String) = db.rawQuery(query, null).use { it.moveToFirst(); it.getLong(0) }
        private fun supported(entry: DocumentEntry): Boolean {
            if (entry.pdf || entry.mime == "application/pdf" || entry.mime.startsWith("text/") ||
                entry.mime.isBlank() || entry.mime == "application/octet-stream" ||
                entry.mime in TEXT_MIMES || entry.mime.endsWith("+json") || entry.mime.endsWith("+xml")) return true
            val extension = entry.name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
            return extension in TEXT_EXTENSIONS || entry.name.lowercase(java.util.Locale.ROOT) in TEXT_NAMES
        }
        private val TEXT_MIMES = setOf("application/json", "application/xml", "application/javascript", "application/x-javascript", "application/x-sh", "application/sql", "application/toml", "application/yaml", "application/x-yaml", "application/x-empty")
        private val TEXT_NAMES = setOf("makefile", "dockerfile", "license", "readme", "gemfile", "rakefile", ".gitignore", ".env", ".editorconfig")
        private val TEXT_EXTENSIONS = setOf("txt", "md", "markdown", "mdown", "rst", "log", "csv", "tsv", "json", "jsonl", "ndjson", "yaml", "yml", "xml", "html", "htm", "css", "scss", "sass", "less", "js", "jsx", "ts", "tsx", "mjs", "cjs", "java", "kt", "kts", "py", "pyw", "rb", "rs", "go", "c", "h", "cc", "cpp", "cxx", "hpp", "cs", "swift", "m", "mm", "php", "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd", "sql", "toml", "ini", "conf", "config", "properties", "gradle", "svelte", "vue", "tex", "r", "lua", "pl", "ex", "exs", "erl", "hrl", "clj", "cljs", "dart", "ipynb", "svg")

        private fun textReader(file: File): PushbackReader {
            val input = PushbackInputStream(file.inputStream().buffered(), 4)
            try {
                val prefix = ByteArray(4)
                var length = 0
                while (length < prefix.size) { val value = input.read(); if (value < 0) break; prefix[length++] = value.toByte() }
                fun byte(index: Int) = if (index < length) prefix[index].toInt() and 255 else -1
                var offset = 0
                val charset = when {
                    byte(0) == 255 && byte(1) == 254 -> {
                        if (byte(2) == 0 && byte(3) == 0) throw IOException("Excluded: UTF-32 is unsupported")
                        offset = 2; Charsets.UTF_16LE
                    }
                    byte(0) == 254 && byte(1) == 255 -> { offset = 2; Charsets.UTF_16BE }
                    byte(0) == 239 && byte(1) == 187 && byte(2) == 191 -> { offset = 3; Charsets.UTF_8 }
                    else -> Charsets.UTF_8
                }
                if (length > offset) input.unread(prefix, offset, length - offset)
                return PushbackReader(InputStreamReader(input, charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)), 1)
            } catch (error: Exception) { input.close(); throw error }
        }

        /** Fixed overlapping windows preserve characters and editor-normalized line coordinates. */
        private suspend fun chunks(reader: PushbackReader, strict: Boolean, consume: suspend (String, Int, Int) -> Unit) {
            val buffer = StringBuilder(CHUNK_CHARS)
            var firstLine = 1
            var endLine = 1
            var index = 0
            var newCharacters = false
            while (true) {
                if (++index % 512 == 0) currentCoroutineContext().ensureActive()
                var value = reader.read()
                if (value < 0) break
                if (strict && (value in 0..8 || value == 11 || value in 14..31 || value == 127)) throw IOException("Excluded: binary or unsupported text")
                if (value == 13) { val next = reader.read(); if (next >= 0 && next != 10) reader.unread(next); value = 10 }
                buffer.append(value.toChar())
                newCharacters = true
                if (value == 10) endLine++
                if (buffer.length >= CHUNK_CHARS && !Character.isHighSurrogate(buffer.last())) {
                    consume(buffer.toString(), firstLine, endLine - if (buffer.last() == '\n') 1 else 0)
                    var remove = buffer.length - OVERLAP_CHARS
                    if (Character.isLowSurrogate(buffer[remove])) remove--
                    for (position in 0 until remove) if (buffer[position] == '\n') firstLine++
                    buffer.delete(0, remove)
                    newCharacters = false
                }
            }
            if (newCharacters) consume(buffer.toString(), firstLine, endLine - if (buffer.last() == '\n') 1 else 0)
        }

        /** FTS4 native-endian p,c,n,a,l,x statistics; Okapi BM25, k1=1.2 and b=.75. */
        internal fun bm25(blob: ByteArray): Double {
            val data = ByteBuffer.wrap(blob).order(ByteOrder.nativeOrder()).asIntBuffer()
            val phrases = data[0]
            val columns = data[1]
            val documents = data[2].toLong() and 0xffffffffL
            var result = 0.0
            for (phrase in 0 until phrases) for (column in 0 until columns) {
                val offset = 3 + columns * 2 + (phrase * columns + column) * 3
                val frequency = (data[offset].toLong() and 0xffffffffL).toDouble()
                if (frequency == 0.0) continue
                val matchingDocuments = (data[offset + 2].toLong() and 0xffffffffL).toDouble()
                val average = (data[3 + column].toLong() and 0xffffffffL).toDouble().coerceAtLeast(1.0)
                val length = (data[3 + columns + column].toLong() and 0xffffffffL).toDouble()
                val idf = ln(1.0 + (documents - matchingDocuments + 0.5) / (matchingDocuments + 0.5))
                result += idf * frequency * 2.2 / (frequency + 1.2 * (0.25 + 0.75 * length / average))
            }
            return result
        }
        private fun vectorBytes(vector: FloatArray): ByteArray = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { vector.forEach { putFloat(it) } }.array()
        private fun cosine(query: FloatArray, bytes: ByteArray): Double {
            if (bytes.size != query.size * 4) return Double.NaN
            val vector = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var dot = 0.0; var left = 0.0; var right = 0.0
            for (value in query) { val other = vector.float.toDouble(); dot += value * other; left += value * value; right += other * other }
            return if (left == 0.0 || right == 0.0) Double.NaN else dot / sqrt(left * right)
        }
    }
}
