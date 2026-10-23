package io.notepit.app

import android.app.Application
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import java.io.Closeable
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class OpenDocument(val id: String, var entry: DocumentEntry, val directory: File, var revision: String) {
    val base get() = File(directory, "original")
    val draft get() = File(directory, "draft")
    val journal get() = File(directory, "journal.jsonl")
    val metadata get() = File(directory, "session.json")
    var dirty by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var externalChanged by mutableStateOf(false)
    var reload by mutableStateOf(0)
    var pendingReveal by mutableStateOf<JSONObject?>(null)
    var state: Any = JSONObject()
    var sequence = 0L
    var draftSequence = 0L
    val current: File get() = if (entry.pdf && draft.exists()) draft else base
}

/** Document buffers and reading state live independently of activities and renderer lifetimes. */
class DocumentSessions(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    val repository = DocumentsRepository(application)
    val semanticEngine = SemanticEngine(application)
    val searchIndex = SearchIndex(repository, semanticEngine)
    var searchVisible by mutableStateOf(savedState.get<Boolean>("searchVisible") ?: false)
        private set
    var searchQuery by mutableStateOf(savedState.get<String>("searchQuery") ?: "")
        private set
    var semanticSearch by mutableStateOf(savedState.get<Boolean>("semanticSearch") ?: false)
        private set
    var searchHits by mutableStateOf<List<SearchHit>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    var searchError by mutableStateOf<String?>(null)
        private set
    var selectedHit by mutableStateOf(savedState.get<Int>("selectedHit") ?: -1)
        private set
    private var foreground = false
    private var refreshJob: Job? = null
    private var queryJob: Job? = null
    private var modelJob: Job? = null
    private var providerObserver: Closeable? = null
    private val refreshRequests = IndexRefreshQueue()
    private var queryVersion = 0L
    var downloadingSearchModel by mutableStateOf(false)
        private set
    val documents = mutableStateListOf<OpenDocument>()
    var activeId by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var opening by mutableStateOf(false)
        private set
    var initialized by mutableStateOf(false)
        private set
    var themeMode by mutableStateOf("system")
        private set
    private val directory = File(application.filesDir, "documents")
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val index get() = File(directory, "open.json")

    init {
        viewModelScope.launch {
            var installed = false
            semanticEngine.status.collect { state ->
                if (state.installed != installed) {
                    installed = state.installed
                    refreshSearch()
                    runSearch()
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            directory.mkdirs()
            val savedTheme = application.getSharedPreferences("appearance", 0).getString("mode", "system") ?: "system"
            withContext(Dispatchers.Main) { themeMode = savedTheme }
            try {
                if (index.exists()) {
                    val saved = JSONObject(index.readText())
                    val ids = saved.optJSONArray("ids") ?: JSONArray()
                    val recovered = (0 until ids.length()).mapNotNull { i ->
                        val folder = File(directory, ids.getString(i))
                        if (File(folder, "session.json").exists()) load(folder) else null
                    }
                    withContext(Dispatchers.Main) {
                        documents.addAll(recovered)
                        activeId = saved.optString("active").takeIf { id -> recovered.any { it.id == id } }
                    }
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) { message = "Session recovery failed. Original recovery files remain in app storage. ${error.message}" }
            } finally {
                withContext(Dispatchers.Main) {
                    initialized = true
                    savedState.get<String>("pendingSearchOpen")?.let { pending ->
                        try {
                            val request = JSONObject(pending)
                            open(DocumentEntry.fromJson(request.getJSONObject("entry")), request.getJSONObject("reveal"))
                        } catch (error: Exception) { message = "The saved search location could not be restored: ${error.message}" }
                    }
                }
            }
            for (operation in queue) {
                try { operation() }
                catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (error: Exception) { withContext(Dispatchers.Main) { message = error.message ?: "Document operation failed." } }
            }
        }
    }

    private fun load(folder: File): OpenDocument {
        val saved = JSONObject(AtomicFile(File(folder, "session.json")).openRead().bufferedReader().use { it.readText() })
        return OpenDocument(folder.name, DocumentEntry.fromJson(saved.getJSONObject("entry")), folder, saved.getString("revision")).also {
            it.dirty = saved.optBoolean("dirty")
            it.state = saved.opt("state") ?: JSONObject()
            it.pendingReveal = saved.optJSONObject("pendingReveal")
            it.sequence = saved.optLong("sequence")
            it.draftSequence = saved.optLong("draftSequence")
            if (it.journal.exists()) it.journal.forEachLine { line ->
                if (line.isNotBlank()) {
                    it.sequence = maxOf(it.sequence, JSONObject(line).getLong("seq"))
                    it.dirty = true
                }
            }
        }
    }

    private fun atomic(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val target = AtomicFile(file)
        val stream = target.startWrite()
        try { stream.write(bytes); target.finishWrite(stream) }
        catch (error: Exception) { target.failWrite(stream); throw error }
    }

    private fun persist(session: OpenDocument) {
        atomic(session.metadata, JSONObject().put("entry", session.entry.toJson()).put("revision", session.revision)
            .put("dirty", session.dirty).put("state", session.state).put("sequence", session.sequence)
            .put("draftSequence", session.draftSequence).put("pendingReveal", session.pendingReveal).toString().toByteArray())
    }

    private suspend fun persistIndex() {
        val saved = withContext(Dispatchers.Main) {
            JSONObject().put("ids", JSONArray(documents.map { it.id })).put("active", activeId)
        }
        atomic(index, saved.toString().toByteArray())
    }

    fun open(entry: DocumentEntry, reveal: JSONObject? = null) {
        if (entry.directory || opening) return
        val existing = documents.firstOrNull { it.entry.uri == entry.uri }
        if (existing != null) {
            if (reveal != null) queue.trySend {
                check(existing)
                withContext(Dispatchers.Main) { existing.pendingReveal = reveal }
                persist(existing)
            }
            activate(existing)
            queue.trySend { persist(existing) }
            return
        }
        opening = true
        queue.trySend {
            try {
                val id = android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(entry.uri.toByteArray()), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                val folder = File(directory, id).apply { mkdirs() }
                val session = if (File(folder, "session.json").exists()) load(folder) else {
                    val base = File(folder, "original")
                    val revision = repository.snapshot(entry, base)
                    OpenDocument(id, entry, folder, revision)
                }
                if (!session.base.exists() && !session.dirty) session.revision = repository.snapshot(entry, session.base)
                if (!session.base.exists()) throw java.io.IOException("The cached document is unavailable. Reopen the original folder.")
                session.pendingReveal = reveal ?: session.pendingReveal
                check(session)
                persist(session)
                repository.recordRecent(entry)
                withContext(Dispatchers.Main) { documents.add(session); activeId = id }
                persistIndex()
            } catch (error: Exception) {
                withContext(Dispatchers.Main) { message = error.message ?: "Unable to open document." }
            } finally { withContext(Dispatchers.Main) { opening = false } }
        }
    }

    fun activate(session: OpenDocument?) {
        activeId = session?.id
        queue.trySend { persistIndex(); session?.let { check(it) } }
    }

    private suspend fun check(session: OpenDocument) {
        try {
            val actual = repository.query(session.entry)
            withContext(Dispatchers.Main) {
                session.externalChanged = actual.modified != session.entry.modified || actual.size != session.entry.size
                if (session.error?.startsWith("File unavailable:") == true) session.error = null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            withContext(Dispatchers.Main) { session.error = "File unavailable: ${error.message}. The cached buffer and drafts are retained." }
        }
    }

    fun checkActive() { documents.firstOrNull { it.id == activeId }?.let { session -> queue.trySend { check(session) } } }
    suspend fun verifySearchSource(session: OpenDocument) { check(session) }

    fun accept(session: OpenDocument, event: JSONObject) {
        queue.trySend {
            when (event.optString("type")) {
                "journal" -> {
                    val seq = event.getLong("seq")
                    if (seq > session.sequence) {
                        FileOutputStream(session.journal, true).bufferedWriter().use { it.append(event.toString()).append('\n') }
                        session.sequence = seq
                        withContext(Dispatchers.Main) { session.dirty = true }
                        persist(session)
                    }
                }
                "state" -> { session.state = event.opt("state") ?: JSONObject(); persist(session) }
                "dirty" -> { withContext(Dispatchers.Main) { session.dirty = event.optBoolean("dirty") }; persist(session) }
                "error" -> withContext(Dispatchers.Main) { session.error = event.optString("message", "Document engine failed."); session.busy = false }
            }
        }
    }

    fun checkpoint(session: OpenDocument, done: () -> Unit) {
        queue.trySend { persist(session); withContext(Dispatchers.Main) { done() } }
    }

    fun config(session: OpenDocument, dark: Boolean): JSONObject = runBlocking {
        val result = CompletableDeferred<JSONObject>()
        queue.send {
            try { result.complete(buildConfig(session, dark)) }
            catch (error: Exception) { result.completeExceptionally(error) }
        }
        result.await()
    }

    private fun buildConfig(session: OpenDocument, dark: Boolean): JSONObject {
        val journal = JSONArray()
        if (!session.entry.pdf && session.journal.exists()) session.journal.forEachLine { line ->
            if (line.isNotBlank()) journal.put(JSONObject(line).opt("data"))
        }
        return JSONObject().put("name", session.entry.name).put("pdf", session.entry.pdf)
            .put("readOnly", !session.entry.writable).put("dark", dark).put("state", session.state)
            .put("journal", journal).put("sequence", session.sequence).put("recoveredDirty", session.dirty)
            .put("url", "/session/${session.id}/document")
    }

    fun receive(session: OpenDocument, transfer: JSONObject, bytes: ByteArray, acknowledge: (Boolean, String?) -> Unit) {
        session.busy = transfer.optString("purpose") != "draft"
        queue.trySend {
            val purpose = transfer.getString("purpose")
            val seq = transfer.getLong("seq")
            val outgoing = File(session.directory, "outgoing")
            try {
                if (purpose == "draft") {
                    if (seq >= session.draftSequence && seq >= transfer.optLong("minimumSeq")) {
                        atomic(session.draft, bytes)
                        session.draftSequence = seq
                        withContext(Dispatchers.Main) { session.dirty = true }
                        persist(session)
                    }
                } else {
                    atomic(outgoing, bytes)
                    if (purpose == "copy") {
                        val copy = repository.writeCopy(session.entry, outgoing, transfer.getString("name"))
                        withContext(Dispatchers.Main) { message = "Saved a separate copy: ${copy.name}" }
                        outgoing.delete()
                    } else {
                        val revision = repository.save(session.entry, outgoing, session.revision)
                        atomic(session.base, bytes)
                        session.revision = revision
                        session.entry = repository.query(session.entry)
                        val remaining = if (session.journal.exists()) session.journal.readLines().filter { line -> line.isNotBlank() && JSONObject(line).getLong("seq") > seq } else emptyList()
                        atomic(session.journal, remaining.joinToString("\n", postfix = if (remaining.isEmpty()) "" else "\n").toByteArray())
                        if (session.draftSequence <= seq) { session.draft.delete(); session.draftSequence = 0 }
                        withContext(Dispatchers.Main) { session.externalChanged = false; session.error = null; session.dirty = remaining.isNotEmpty() }
                        persist(session)
                        outgoing.delete()
                    }
                }
                withContext(Dispatchers.Main) { acknowledge(true, null) }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) { session.error = error.message ?: "Save failed. Your edits are retained."; acknowledge(false, session.error) }
            } finally { withContext(Dispatchers.Main) { session.busy = false } }
        }
    }

    fun reload(session: OpenDocument) {
        session.busy = true
        queue.trySend {
            try {
                val fresh = repository.query(session.entry)
                session.revision = repository.snapshot(fresh, session.base)
                session.entry = fresh
                session.draft.delete(); session.journal.delete(); session.sequence = 0; session.draftSequence = 0
                withContext(Dispatchers.Main) { session.dirty = false; session.externalChanged = false; session.error = null; session.reload++ }
                persist(session)
            } catch (error: Exception) { withContext(Dispatchers.Main) { session.error = error.message } }
            finally { withContext(Dispatchers.Main) { session.busy = false } }
        }
    }

    fun close(session: OpenDocument, discard: Boolean) {
        queue.trySend {
            if (discard) session.directory.deleteRecursively() else persist(session)
            withContext(Dispatchers.Main) {
                documents.remove(session)
                if (activeId == session.id) activeId = documents.lastOrNull()?.id
            }
            persistIndex()
        }
    }

    fun showSearch(visible: Boolean) {
        searchVisible = visible
        savedState["searchVisible"] = visible
        if (visible) { activate(null); runSearch() }
    }

    fun updateSearch(query: String = searchQuery, semantic: Boolean = semanticSearch) {
        searchQuery = query.take(256)
        semanticSearch = semantic
        savedState["searchQuery"] = searchQuery
        savedState["semanticSearch"] = semanticSearch
        selectedHit = -1
        savedState["selectedHit"] = -1
        searchHits = emptyList()
        runSearch()
    }

    private fun runSearch() {
        queryJob?.cancel()
        val version = ++queryVersion
        queryJob = null
        searching = false
        searchError = null
        if (!foreground || searchQuery.isBlank()) { searchHits = emptyList(); return }
        if (semanticSearch && !semanticEngine.status.value.installed) { searchHits = emptyList(); return }
        val query = searchQuery
        val semantic = semanticSearch
        val selection = searchHits.getOrNull(selectedHit)
        queryJob = viewModelScope.launch {
            searching = true
            try {
                delay(300)
                val found = searchIndex.search(query, semantic)
                searchHits = found
                if (selection != null) {
                    selectedHit = found.indexOfFirst { it.entry.uri == selection.entry.uri && it.page == selection.page && it.line == selection.line && it.text == selection.text }
                } else if (selectedHit >= found.size) selectedHit = -1
                savedState["selectedHit"] = selectedHit
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                searchError = error.message ?: "Search failed. Keyword search remains available."
            }
            finally { if (version == queryVersion) searching = false }
        }
    }

    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (!value) {
            providerObserver?.close()
            providerObserver = null
            refreshJob?.cancel()
            refreshJob = null
            queryJob?.cancel()
            queryJob = null
            searching = false
            return
        }
        providerObserver = repository.observe(onChange = { refreshSearch() },
            onProviderChange = { refreshRequests.request(providerHint = true) })
        refreshSearch()
        runSearch()
        checkActive()
    }

    fun refreshSearch(force: Boolean = false) {
        refreshRequests.request(force = force)
        if (!foreground || refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            try { refreshRequests.run { rebuild -> searchIndex.refresh(rebuild).also { runSearch() } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { searchError = error.message ?: "The search index could not be refreshed." }
        }
    }

    fun downloadSearchModel() {
        if (modelJob?.isActive == true) return
        downloadingSearchModel = true
        modelJob = viewModelScope.launch {
            try { semanticEngine.download(); refreshSearch(); runSearch() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { searchError = error.message ?: "Model installation failed." }
            finally { downloadingSearchModel = false }
        }
    }

    fun cancelSearchModelDownload() { modelJob?.cancel() }

    fun removeSearchModel() {
        modelJob?.cancel()
        modelJob = viewModelScope.launch {
            try { semanticEngine.remove(); updateSearch(semantic = false); refreshSearch() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { searchError = error.message ?: "The model could not be removed." }
        }
    }

    fun openSearchHit(index: Int, expected: SearchHit? = null) {
        if (opening) return
        val hit = expected ?: searchHits.getOrNull(index) ?: return
        selectedHit = searchHits.indexOf(hit)
        savedState["selectedHit"] = selectedHit
        val reveal = JSONObject().put("query", hit.query.ifBlank { searchQuery }).put("page", hit.page)
            .put("line", hit.line).put("endLine", hit.endLine).put("text", hit.text).put("semantic", hit.semantic)
            .put("sourceModified", hit.entry.modified).put("sourceSize", hit.entry.size)
        savedState["pendingSearchOpen"] = JSONObject().put("entry", hit.entry.toJson()).put("reveal", reveal).toString()
        open(hit.entry, reveal)
    }

    fun revealDelivered(session: OpenDocument, reveal: JSONObject) {
        if (session.pendingReveal !== reveal) return
        session.pendingReveal = null
        savedState.remove<String>("pendingSearchOpen")
        queue.trySend { persist(session) }
    }

    override fun onCleared() {
        providerObserver?.close()
        refreshJob?.cancel()
        queryJob?.cancel()
        modelJob?.cancel()
        searchIndex.close()
        semanticEngine.close()
        super.onCleared()
    }

    fun setTheme(mode: String) {
        require(mode in listOf("system", "light", "dark"))
        themeMode = mode
        queue.trySend { getApplication<Application>().getSharedPreferences("appearance", 0).edit().putString("mode", mode).commit() }
    }
}
