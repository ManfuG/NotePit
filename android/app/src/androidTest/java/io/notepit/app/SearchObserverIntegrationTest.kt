package io.notepit.app

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual DocumentsProvider -> ContentService -> repository -> index refresh queue. */
@RunWith(AndroidJUnit4::class)
class SearchObserverIntegrationTest {
    private lateinit var context: IsolatedSearchContext
    private lateinit var repository: DocumentsRepository
    private lateinit var model: SemanticEngine
    private lateinit var index: SearchIndex
    private var harness: RefreshHarness? = null

    @Before fun setUp(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.MANAGE_DOCUMENTS)
        context = IsolatedSearchContext(instrumentation.targetContext)
        call("reset", extras = Bundle().apply { putString("package", context.packageName) })
        context.contentResolver.takePersistableUriPermission(NotifyingSearchProvider.TREE,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        context.getSharedPreferences("folders", 0).edit()
            .putStringSet("roots", setOf(NotifyingSearchProvider.TREE.toString())).commit()
        repository = DocumentsRepository(context)
        assertTrue("Real SAF grant must be readable", repository.roots().single().available)
        model = SemanticEngine(context)
        index = SearchIndex(repository, model)
    }

    @After fun tearDown(): Unit = runBlocking {
        try {
            if (this@SearchObserverIntegrationTest::context.isInitialized) call("release")
            harness?.close()
            if (this@SearchObserverIntegrationTest::index.isInitialized) index.close()
            if (this@SearchObserverIntegrationTest::model.isInitialized) model.close()
            if (this@SearchObserverIntegrationTest::context.isInitialized) {
                context.contentResolver.releasePersistableUriPermission(NotifyingSearchProvider.TREE,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                call("clear")
                context.clearPreferences()
                context.privateDirectory.deleteRecursively()
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test fun noisyProviderSettlesAt44DocumentsAndColdRestartReadsOnlyExplicitlyChangedSources(): Unit = runBlocking {
        var active = watch()
        awaitSettled(active, afterPass = 0)
        assertIndexed()
        val initialReads = expectedReads()
        assertEquals(initialReads, metrics().opens)
        assertTrue("Initial scan plus validation must remain bounded", active.passes.get() <= MAX_SETTLING_PASSES)

        active.close()
        index.close()
        repository = DocumentsRepository(context)
        index = SearchIndex(repository, model)
        active = watch()
        awaitSettled(active, afterPass = 0)
        assertIndexed()
        assertEquals("A cold repository/index must retain successful and failed source revisions", initialReads, metrics().opens)

        val beforeVersion = repository.contentVersion(entry("healthy-00"))
        var completed = active.passes.get()
        call("edit", "healthy-00", Bundle().apply {
            putString("text", "modifiedNeedle00"); putBoolean("preserveMetadata", true)
        })
        awaitSettled(active, afterPass = completed)
        assertEquals("Two subscribers must share a single canonical content-version bump",
            beforeVersion + 1, repository.contentVersion(entry("healthy-00")))
        val afterOpaqueEdit = initialReads.toMutableMap().apply { this["healthy-00"] = 2 }
        assertEquals("Typed updates must read the changed source even with unchanged size and timestamp", afterOpaqueEdit, metrics().opens)
        assertTrue(index.search("originalNeedle00").isEmpty())
        assertEquals("healthy-00.txt", index.search("modifiedNeedle00").single().entry.name)
        awaitSettled(active)

        completed = active.passes.get()
        call("edit", "healthy-02", Bundle().apply { putString("text", "modifiedNeedle02") })
        awaitSettled(active, afterPass = completed)
        assertEquals(afterOpaqueEdit.toMutableMap().apply { this["healthy-02"] = 2 }, metrics().opens)
        assertIndexed()
        assertFalse("Observer and queue callbacks must run on Main", active.offMain.get())
    }

    @Test fun closingOneObserverKeepsTheOtherFunctionalWithoutDuplicateVersionIncrements(): Unit = runBlocking {
        val active = watch()
        awaitSettled(active, afterPass = 0)
        val entry = entry("healthy-00")
        val before = repository.contentVersion(entry)
        var completed = active.passes.get()
        call("edit", "healthy-00", Bundle().apply {
            putString("text", "modifiedNeedle00"); putBoolean("preserveMetadata", true)
        })
        awaitSettled(active, afterPass = completed)
        assertEquals(before + 1, repository.contentVersion(entry))
        assertTrue(active.firstChanges.get() > 0)
        assertTrue(active.secondChanges.get() > 0)
        withContext(Dispatchers.Main) { active.closeFirst() }
        val firstChanges = active.firstChanges.get()
        val secondChanges = active.secondChanges.get()
        completed = active.passes.get()
        call("edit", "healthy-00", Bundle().apply {
            putString("text", "originalNeedle00"); putBoolean("preserveMetadata", true)
        })
        awaitSettled(active, afterPass = completed)
        assertEquals(before + 2, repository.contentVersion(entry))
        assertEquals("Closed subscribers must not receive later updates", firstChanges, active.firstChanges.get())
        assertTrue("The remaining subscriber must still observe updates", active.secondChanges.get() > secondChanges)
        assertEquals(expectedReads().toMutableMap().apply { this["healthy-00"] = 3 }, metrics().opens)
        assertEquals("healthy-00.txt", index.search("originalNeedle00").single().entry.name)
        assertTrue(index.search("modifiedNeedle00").isEmpty())
        awaitSettled(active)
        assertFalse(active.offMain.get())
    }

    @Test fun editsAddsAndDeletesDuringAnInitialScanReceiveAFiniteCatchUpPass(): Unit = runBlocking {
        call("block", "healthy-43")
        val active = watch()
        try {
            withTimeout(10_000) { while (!metrics().blocked) delay(40) }
            call("edit", "healthy-00", Bundle().apply {
                putString("text", "modifiedNeedle00"); putBoolean("preserveMetadata", true)
            })
            call("add", "added-during-scan", Bundle().apply { putString("text", "addedDuringScanNeedle") })
            call("remove", "healthy-01")
        } finally {
            call("release")
        }
        awaitSettled(active, afterPass = 0)
        assertIndexed()
        assertTrue(index.search("originalNeedle00").isEmpty())
        assertTrue(index.search("originalNeedle01").isEmpty())
        assertEquals("healthy-00.txt", index.search("modifiedNeedle00").single().entry.name)
        assertEquals("added-during-scan.txt", index.search("addedDuringScanNeedle").single().entry.name)
        val expected = expectedReads().toMutableMap().apply {
            this["healthy-00"] = 2; this["added-during-scan"] = 1
        }
        assertEquals("Catch-up must not reread unchanged successful or failed documents", expected, metrics().opens)
        awaitSettled(active)
        assertFalse(active.offMain.get())
    }

    private fun assertIndexed() {
        assertFalse(index.status.value.busy)
        assertEquals("Every healthy source must be indexed despite two binary exclusions", 44, index.status.value.documents)
        assertEquals(2, index.status.value.failures)
        assertEquals(0, index.status.value.warnings)
    }

    private suspend fun watch(): RefreshHarness = RefreshHarness(repository, index).also {
        harness = it
        it.start()
    }

    private data class ProviderMetrics(val queries: Int, val opens: Map<String, Int>, val blocked: Boolean)

    private suspend fun metrics(): ProviderMetrics {
        val state = call("metrics")
        val opens = requireNotNull(state.getBundle("opens"))
        return ProviderMetrics(state.getInt("queries"), opens.keySet().associateWith { opens.getInt(it) }, state.getBoolean("blocked"))
    }

    /** Quiet real-provider I/O and completed passes, not just a transient busy=false between passes. */
    private suspend fun awaitSettled(active: RefreshHarness, afterPass: Int = -1) {
        withTimeout(15_000) {
            var previous: Pair<ProviderMetrics, Int>? = null
            var quietSince = SystemClock.elapsedRealtime()
            val startPass = active.passes.get()
            while (true) {
                active.failure.get()?.let { throw AssertionError("Refresh worker failed", it) }
                val snapshot = metrics() to active.passes.get()
                assertTrue("Provider notification feedback must have a finite pass count: $snapshot",
                    snapshot.second - startPass <= MAX_SETTLING_PASSES)
                val now = SystemClock.elapsedRealtime()
                if (snapshot != previous || index.status.value.busy) quietSince = now
                if (snapshot.second > afterPass && !index.status.value.busy && now - quietSince >= 800) return@withTimeout
                previous = snapshot
                delay(50)
            }
        }
    }

    private suspend fun call(method: String, arg: String? = null, extras: Bundle? = null): Bundle = withContext(Dispatchers.IO) {
        requireNotNull(context.contentResolver.call(NotifyingSearchProvider.CONTROL, method, arg, extras))
    }

    private fun entry(id: String) = DocumentEntry(NotifyingSearchProvider.documentUri(id).toString(), "$id.txt",
        "text/plain", treeUri = NotifyingSearchProvider.TREE.toString())

    private fun expectedReads() = buildMap {
        repeat(44) { put("healthy-${it.toString().padStart(2, '0')}", 1) }
        repeat(2) { put("bad-$it", 1) }
    }

    private class RefreshHarness(private val repository: DocumentsRepository, private val index: SearchIndex) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val queue = IndexRefreshQueue()
        private var first: Closeable? = null
        private var second: Closeable? = null
        private var worker: Job? = null
        val passes = AtomicInteger()
        val firstChanges = AtomicInteger()
        val secondChanges = AtomicInteger()
        val offMain = AtomicBoolean()
        val failure = AtomicReference<Throwable?>()

        suspend fun start() = withContext(Dispatchers.Main) {
            worker = scope.launch {
                try {
                    queue.run { force ->
                        recordThread()
                        val changed = index.refresh(force)
                        passes.incrementAndGet()
                        changed
                    }
                } catch (error: CancellationException) { throw error }
                catch (error: Throwable) { failure.set(error) }
            }
            first = subscribe(firstChanges)
            second = subscribe(secondChanges)
            queue.request()
        }

        private fun subscribe(changes: AtomicInteger) = repository.observe(
            onChange = { recordThread(); changes.incrementAndGet(); queue.request() },
            onProviderChange = { recordThread(); queue.request(providerHint = true) },
        )

        private fun recordThread() {
            if (Looper.myLooper() != Looper.getMainLooper()) offMain.set(true)
        }

        fun closeFirst() { first?.close(); first = null }

        suspend fun close() = withContext(Dispatchers.Main) {
            closeFirst(); second?.close(); second = null
            worker?.cancelAndJoin(); worker = null
            scope.cancel()
        }
    }

    private class IsolatedSearchContext(base: Context) : ContextWrapper(base) {
        private val prefix = "search-observer-${UUID.randomUUID()}-"
        private val preferences = mutableSetOf<String>()
        val privateDirectory = File(base.filesDir, prefix).apply { mkdirs() }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(privateDirectory, "files").apply { mkdirs() }
        override fun getCacheDir() = File(privateDirectory, "cache").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(privateDirectory, "no-backup").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            synchronized(preferences) { preferences.add(name) }
            return super.getSharedPreferences(prefix + name, mode)
        }
        fun clearPreferences() = synchronized(preferences) {
            preferences.forEach { super.getSharedPreferences(prefix + it, 0).edit().clear().commit() }
        }
    }

    companion object { private const val MAX_SETTLING_PASSES = 6 }
}
