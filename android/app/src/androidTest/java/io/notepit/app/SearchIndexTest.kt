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
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.CancellationSignal
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import android.test.mock.MockContentResolver
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.ln
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real native SQLite/FTS, PDFBox and SAF streams. No model/network is needed for keyword search. */
@RunWith(AndroidJUnit4::class)
class SearchIndexTest {
    private lateinit var context: SearchContext
    private lateinit var provider: SearchProvider
    private lateinit var repository: DocumentsRepository
    private lateinit var model: SemanticEngine
    private lateinit var index: SearchIndex
    private lateinit var root: DocumentEntry

    @Before fun setUp(): Unit = runBlocking {
        context = SearchContext(InstrumentationRegistry.getInstrumentation().targetContext)
        provider = SearchProvider(File(context.filesDir, "provider"))
        provider.attachInfo(context, ProviderInfo().apply {
            authority = AUTHORITY; exported = true; grantUriPermissions = true
            readPermission = Manifest.permission.MANAGE_DOCUMENTS; writePermission = Manifest.permission.MANAGE_DOCUMENTS
        })
        context.resolver.addProvider(AUTHORITY, provider)
        context.getSharedPreferences("folders", 0).edit().putStringSet("roots", setOf(TREE.toString())).commit()
        repository = DocumentsRepository(context)
        root = repository.roots().single()
        assertTrue("Test provider root must be readable before indexing: $root", root.available)
        model = SemanticEngine(context)
        index = SearchIndex(repository, model)
    }

    @After fun tearDown() {
        if (this::index.isInitialized) index.close()
        if (this::model.isInitialized) model.close()
        assertFalse("Search performed provider I/O on the main thread", provider.sawMainThread)
        context.getSharedPreferences("folders", 0).edit().clear().commit()
        context.getSharedPreferences("notepit-library", 0).edit().clear().commit()
        context.getSharedPreferences("notepit-content-versions", 0).edit().clear().commit()
        context.privateDirectory.deleteRecursively()
    }

    @Test fun localSavesSurviveRestartAndOpaqueExternalEditsUseExplicitRebuild(): Unit = runBlocking {
        provider.opaqueMetadata = true
        val first = provider.add("first.txt", "alpha uniqueBefore")
        index.refresh()
        assertEquals("Initial indexing failed: ${index.status.value}", 1, index.status.value.documents)
        assertEquals("first.txt", index.search("uniqueBefore").single().entry.name)
        val oldIds = passageIds(first.id)
        index.refresh()
        assertEquals("Unchanged documents must not be extracted again", oldIds, passageIds(first.id))
        val added = provider.add("added.kt", "val newest = true")
        index.refresh()
        assertEquals("added.kt", index.search("newest").single().entry.name)
        val source = File(context.filesDir, "edit").apply { writeText("alpha uniqueAfter!") }
        val snapshot = File(context.filesDir, "snapshot")
        val firstEntry = repository.query(entry(first))
        val revision = repository.snapshot(firstEntry, snapshot)
        repository.save(firstEntry, source, revision)
        index.close()
        repository = DocumentsRepository(context)
        index = SearchIndex(repository, model)
        index.refresh()
        assertTrue(index.search("uniqueBefore").isEmpty())
        assertEquals(1, index.search("uniqueAfter!").size)
        // An external edit with no metadata change or notification needs explicit verification.
        first.file.writeText("alpha externalNew!")
        index.refresh()
        assertEquals(1, index.search("uniqueAfter!").size)
        assertTrue(index.search("externalNew!").isEmpty())
        index.refresh(force = true)
        assertTrue(index.search("uniqueAfter!").isEmpty())
        assertEquals(1, index.search("externalNew!").size)
        repository.delete(listOf(repository.query(entry(added))))
        index.refresh()
        assertTrue(index.search("newest").isEmpty())
        provider.nodes.remove(first.id)
        assertTrue("Deleted documents must not escape a stale index", index.search("externalNew!").isEmpty())
        provider.add("still.txt", "disconnect token")
        index.refresh()
        repository.disconnect(root)
        assertTrue(index.search("disconnect").isEmpty())
    }

    @Test fun unchangedSourcesStayUnopenedAcrossRestartAndOnlyChangedSourcesAreRead(): Unit = runBlocking {
        val first = provider.add("first.txt", "beforeToken")
        val second = provider.add("second.txt", "untouchedToken")
        index.refresh()
        assertEquals(mapOf(first.id to 1, second.id to 1), provider.reads)
        val originalPassages = passageIds(second.id)
        index.close()
        repository = DocumentsRepository(context)
        index = SearchIndex(repository, model)
        index.refresh()
        assertEquals("Reopening must not read unchanged source contents", mapOf(first.id to 1, second.id to 1), provider.reads)
        val modified = first.file.lastModified()
        first.file.writeText("afterToken!")
        assertTrue(first.file.setLastModified(modified + 2_000))
        index.refresh()
        assertTrue(index.search("beforeToken").isEmpty())
        assertEquals("first.txt", index.search("afterToken!").single().entry.name)
        assertEquals(mapOf(first.id to 2, second.id to 1), provider.reads)
        assertEquals(originalPassages, passageIds(second.id))
        second.file.appendText(" appendedToken")
        index.refresh()
        assertEquals("second.txt", index.search("appendedToken").single().entry.name)
        assertEquals(mapOf(first.id to 2, second.id to 2), provider.reads)
        val checkedReads = provider.reads.toMap()
        index.refresh()
        assertEquals(checkedReads, provider.reads)
        index.refresh(force = true)
        assertEquals(checkedReads.mapValues { it.value + 1 }, provider.reads)
    }

    @Test fun indexingChangingRootMetadataDoesNotScheduleItselfAgain(): Unit = runBlocking {
        provider.add("stable.txt", "stableNeedle")
        provider.changingRootMetadata = true
        val revision = repository.changes.value
        index.refresh()
        assertFalse(index.status.value.busy)
        assertEquals("stable.txt", index.search("stableNeedle").single().entry.name)
        assertEquals("Reading provider metadata must not invalidate the library", revision, repository.changes.value)
        assertEquals(0, index.status.value.failures)
    }

    @Test fun traversesNestedFoldersAndPurgesUnreadableSubtrees(): Unit = runBlocking {
        val folder = provider.add("Nested", byteArrayOf(), Document.MIME_TYPE_DIR)
        val nested = provider.add("inside.rs", "nestedNeedle").also { it.parent = folder.id }
        index.refresh()
        assertTrue(index.search("nestedNeedle").single().path.endsWith("Nested/inside.rs"))
        provider.denied += folder.id
        index.refresh()
        assertTrue(index.search("nestedNeedle").isEmpty())
        assertTrue(index.status.value.failures > 0)
        provider.denied.clear()
        index.refresh()
        assertEquals(nested.name, index.search("nestedNeedle").single().entry.name)
    }

    @Test fun searchesEveryConnectedRootAndDropsOnlyDisconnectedRoot(): Unit = runBlocking {
        provider.add("first-root.txt", "commonRootNeedle")
        val other = SearchProvider(File(context.filesDir, "second-provider"))
        val secondAuthority = "$AUTHORITY.second"
        other.attachInfo(context, ProviderInfo().apply {
            authority = secondAuthority; exported = true; grantUriPermissions = true
            readPermission = Manifest.permission.MANAGE_DOCUMENTS; writePermission = Manifest.permission.MANAGE_DOCUMENTS
        })
        context.resolver.addProvider(secondAuthority, other)
        other.add("second-root.txt", "commonRootNeedle")
        val tree = DocumentsContract.buildTreeDocumentUri(secondAuthority, "root")
        val secondRoot = DocumentEntry(DocumentsContract.buildDocumentUriUsingTree(tree, "root").toString(), "Other root", directory = true, treeUri = tree.toString())
        val roots = org.json.JSONArray().put(root.toJson()).put(secondRoot.toJson())
        context.getSharedPreferences("notepit-library", 0).edit().putString("state", org.json.JSONObject().put("roots", roots).toString()).commit()
        index.close()
        repository = DocumentsRepository(context)
        index = SearchIndex(repository, model)
        index.refresh()
        assertEquals(setOf("first-root.txt", "second-root.txt"), index.search("commonRootNeedle").map { it.entry.name }.toSet())
        repository.disconnect(repository.roots().single { it.treeUri == tree.toString() })
        assertEquals("first-root.txt", index.search("commonRootNeedle").single().entry.name)
        assertFalse(other.sawMainThread)
    }

    @Test fun canceledRefreshDoesNotReconcileUnvisitedDocumentsAndCanResume(): Unit = runBlocking {
        provider.add("one.txt", "retainedOne")
        provider.add("two.txt", "retainedTwo")
        index.refresh()
        val job = launch(Dispatchers.IO, start = CoroutineStart.LAZY) { index.refresh(force = true) }
        provider.beforeOpen = { job.cancel() }
        job.start()
        job.join()
        provider.beforeOpen = null
        assertFalse(index.status.value.busy)
        assertEquals(1, index.search("retainedTwo").size)
        index.refresh()
        assertEquals(2, index.status.value.documents)
    }

    @Test fun publishedKeywordResultsRemainQueryableDuringBlockedRefreshSnapshot(): Unit = runBlocking {
        provider.add("existing.txt", "publishedExistingNeedle")
        index.refresh()
        provider.add("first-new.txt", "publishedProgressNeedle")
        val blocked = provider.add("second-new.txt", "unpublishedLaterNeedle")
        val waiting = CountDownLatch(1)
        val release = CountDownLatch(1)
        provider.beforeOpen = {
            if (it == blocked.id) {
                waiting.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Blocked-provider test was not released" }
            }
        }
        val refresh = async(Dispatchers.IO) { index.refresh() }
        try {
            assertTrue("Refresh must reach the deliberately blocked provider", waiting.await(5, TimeUnit.SECONDS))
            assertTrue(index.status.value.busy)
            val results = withTimeout(2_000) { index.search("publishedProgressNeedle") }
            assertEquals("first-new.txt", results.single().entry.name)
            assertEquals("existing.txt", withTimeout(2_000) { index.search("publishedExistingNeedle") }.single().entry.name)
            assertTrue("Unpublished documents must stay invisible", withTimeout(2_000) { index.search("unpublishedLaterNeedle") }.isEmpty())
        } finally {
            release.countDown()
            provider.beforeOpen = null
            refresh.await()
        }
        assertEquals("second-new.txt", index.search("unpublishedLaterNeedle").single().entry.name)
    }

    @Test fun literalSubstringQueriesDoNotDependOnFtsTokenBoundaries(): Unit = runBlocking {
        provider.add("identifier.kt", "val prefix_needle_unique123_suffix = true\nval tightlyWrappedNeedle = 1")
        index.refresh()
        assertEquals("identifier.kt", index.search("needle_unique").single().entry.name)
        assertEquals("identifier.kt", index.search("Wrapped").single().entry.name)
        assertEquals(1, index.search("_needle_unique123_").single().line)
        assertTrue(index.search("needle_unique OR absent").isEmpty())
    }

    @Test fun nativeFtsMultiTermIdentifiersUseBm25RatherThanLiteralFallback(): Unit = runBlocking {
        provider.add("short-code.kt", "needle_unique")
        provider.add("long-code.kt", "needle_unique " + "filler ".repeat(60))
        index.refresh()
        val hits = index.search("needle_unique")
        assertEquals(2, hits.size)
        assertTrue("Both whole-token identifier hits must have native FTS BM25 scores: $hits", hits.all { it.score > 0.0 })
        assertEquals("short-code.kt", hits.first().entry.name)
        assertTrue("BM25 must favor the shorter equal-frequency passage", hits.first().score > hits.last().score)
    }

    @Test fun exactPunctuationUnicodeBomMixedNewlinesCoordinatesAndBm25(): Unit = runBlocking {
        val source = "first\r\nsecond\rval thing = foo?.bar()\nCafé Ελληνικά\r\nlast"
        provider.add("code.kt", byteArrayOf(0xff.toByte(), 0xfe.toByte()) + source.toByteArray(Charsets.UTF_16LE))
        provider.add("short.txt", "rankingToken")
        provider.add("long.txt", "rankingToken " + "filler ".repeat(60))
        index.refresh()
        val hit = index.search("foo?.bar()").single()
        assertEquals(3, hit.line)
        assertEquals(3, hit.endLine)
        assertTrue(hit.text.contains("foo?.bar()"))
        assertEquals("foo?.bar()", hit.query)
        assertTrue("Punctuated multi-term code query must use native FTS BM25", hit.score > 0.0)
        assertEquals(1, index.search("?.").size)
        assertEquals(1, index.search("CAFÉ").size)
        assertTrue(index.search("foo OR bar").isEmpty())
        val ranked = index.search("rankingToken")
        assertEquals("short.txt", ranked.first().entry.name)
        assertTrue(ranked.first().score > ranked.last().score)
        // p=1,c=1,n=10,a=100,l=50,x=(tf=3,total=8,df=2).
        val blob = ByteBuffer.allocate(8 * 4).order(ByteOrder.nativeOrder()).apply {
            intArrayOf(1, 1, 10, 100, 50, 3, 8, 2).forEach { putInt(it) }
        }.array()
        val expected = ln(1.0 + 8.5 / 2.5) * 3 * 2.2 / (3 + 1.2 * (0.25 + 0.75 * 0.5))
        assertEquals(expected, SearchIndex.bm25(blob), 1e-10)
    }

    @Test fun overlappingLiteralOccurrencesAreNotDuplicated(): Unit = runBlocking {
        provider.add("wide.txt", "x".repeat(300) + " uniqueOverlap " + "x".repeat(400))
        val unicodeSource = "x".repeat(511) + "😀 unicodeBoundary " + "tail ".repeat(80)
        provider.add("unicode.txt", unicodeSource)
        index.refresh()
        val hits = index.search("uniqueOverlap")
        assertEquals(1, hits.size)
        assertEquals(1, hits.single().line)
        val unicode = index.search("😀").single()
        assertTrue(unicodeSource.contains(unicode.text))
        assertTrue(unicode.text.contains("😀"))
    }

    @Test fun pdfPagesAndVisibleBinaryScannedUnreadableExclusions(): Unit = runBlocking {
        provider.add("paper.pdf", pdf(listOf("First page", "PageSecondNeedle", "Third page")), "application/pdf")
        provider.add("binary.txt", byteArrayOf(0, 1, 2, 3))
        provider.add("invalid.txt", byteArrayOf(0xc3.toByte(), 0x28))
        provider.add("scanned.pdf", pdf(listOf("")), "application/pdf")
        val oversized = provider.add("oversized.txt", "")
        java.io.RandomAccessFile(oversized.file, "rw").use { it.setLength(SearchIndex.MAX_TEXT_BYTES + 1) }
        val unreadable = provider.add("locked.txt", "inaccessible")
        provider.denied += unreadable.id
        index.refresh()
        val hit = index.search("PageSecondNeedle").single()
        assertEquals(2, hit.page)
        assertEquals(0, hit.line)
        assertEquals(5, index.status.value.failures)
        assertFalse(index.status.value.busy)
        assertTrue(index.search("inaccessible").isEmpty())
    }

    @Test fun searchingBeforeLibraryInitializationCannotErasePersistedPassages(): Unit = runBlocking {
        provider.add("persisted.txt", "persistedBeforeInitialization")
        index.refresh()
        index.close()
        val loading = CountDownLatch(1)
        val release = CountDownLatch(1)
        context.beforeLibraryRead = {
            loading.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        repository = DocumentsRepository(context)
        index = SearchIndex(repository, model)
        assertTrue(loading.await(5, TimeUnit.SECONDS))
        val query = async(Dispatchers.IO) { index.search("persistedBeforeInitialization") }
        try {
            assertNull("Search must await library initialization, not treat it as disconnected",
                kotlinx.coroutines.withTimeoutOrNull(250) { query.await() })
        } finally {
            context.beforeLibraryRead = null
            release.countDown()
        }
        assertEquals("persisted.txt", query.await().single().entry.name)
        assertEquals("persisted.txt", index.search("persistedBeforeInitialization").single().entry.name)
    }

    @Test fun unchangedExclusionsPersistAcrossRefreshAndRestartAndRecoverOnlyWhenRequested(): Unit = runBlocking {
        val healthy = provider.add("healthy.txt", "healthyNeedle")
        val binary = provider.add("binary.txt", byteArrayOf(0, 1, 2, 3))
        val malformed = provider.add("malformed.pdf", "not a PDF".toByteArray(), "application/pdf")
        val scanned = provider.add("scanned.pdf", pdf(listOf("", "")), "application/pdf")
        val locked = provider.add("locked.txt", "unlockedNeedle")
        provider.unreadableStreams += locked.id
        val oversized = provider.add("oversized.txt", "")
        java.io.RandomAccessFile(oversized.file, "rw").use { it.setLength(SearchIndex.MAX_TEXT_BYTES + 1) }

        fun assertExcluded() {
            assertFalse(index.status.value.busy)
            assertEquals(1, index.status.value.documents)
            assertEquals(5, index.status.value.failures)
        }
        index.refresh()
        assertExcluded()
        assertEquals("healthy.txt", index.search("healthyNeedle").single().entry.name)
        val attempts = mapOf(healthy.id to 1, binary.id to 1, malformed.id to 1, scanned.id to 1, locked.id to 1)
        assertEquals("Oversized metadata must exclude a source without opening it", attempts, provider.readAttempts)
        val passages = index.status.value.passages
        repeat(2) {
            index.refresh()
            assertExcluded()
            assertEquals(passages, index.status.value.passages)
            assertEquals("Unchanged excluded sources must not be opened again", attempts, provider.readAttempts)
        }
        index.close()
        repository = DocumentsRepository(context)
        index = SearchIndex(repository, model)
        index.refresh()
        assertExcluded()
        assertEquals("Exclusion caching must survive index and repository recreation", attempts, provider.readAttempts)

        // Restoring stream access alone does not change provider metadata.
        provider.unreadableStreams -= locked.id
        index.refresh()
        assertExcluded()
        assertTrue(index.search("unlockedNeedle").isEmpty())
        assertEquals(attempts, provider.readAttempts)
        index.refresh(force = true)
        assertEquals(2, index.status.value.documents)
        assertEquals(4, index.status.value.failures)
        assertEquals("locked.txt", index.search("unlockedNeedle").single().entry.name)
        assertEquals(attempts.mapValues { it.value + 1 }, provider.readAttempts)

        val repairs = listOf(
            binary to "binaryRecoveredNeedle".toByteArray(),
            malformed to pdf(listOf("malformedRecoveredNeedle")),
            scanned to pdf(listOf("scannedRecoveredNeedle")),
            oversized to "oversizedRecoveredNeedle".toByteArray(),
        )
        for ((node, bytes) in repairs) {
            val modified = node.file.lastModified()
            node.file.writeBytes(bytes)
            assertTrue(node.file.setLastModified(modified + 2_000))
        }
        index.refresh()
        assertEquals(6, index.status.value.documents)
        assertEquals(0, index.status.value.failures)
        assertEquals("binary.txt", index.search("binaryRecoveredNeedle").single().entry.name)
        assertEquals("malformed.pdf", index.search("malformedRecoveredNeedle").single().entry.name)
        assertEquals("scanned.pdf", index.search("scannedRecoveredNeedle").single().entry.name)
        assertEquals("oversized.txt", index.search("oversizedRecoveredNeedle").single().entry.name)
        val repairedAttempts = attempts.mapValues { (_, count) -> count + 1 }.toMutableMap().apply {
            this[binary.id] = 3; this[malformed.id] = 3; this[scanned.id] = 3; this[oversized.id] = 1
        }
        assertEquals("Only changed sources should be retried", repairedAttempts, provider.readAttempts)
        index.refresh()
        assertEquals(repairedAttempts, provider.readAttempts)
        assertEquals(0, index.status.value.failures)
    }

    @Test fun failedReplacementRemovesOldPassagesAndRecoveryReplacesTheCachedFailure(): Unit = runBlocking {
        val source = provider.add("changing.txt", "obsoleteNeedle")
        index.refresh()
        val modified = source.file.lastModified()
        source.file.writeBytes(byteArrayOf(0, 1, 2, 3))
        assertTrue(source.file.setLastModified(modified + 2_000))
        index.refresh()
        assertEquals(0, index.status.value.documents)
        assertEquals(0, index.status.value.passages)
        assertEquals(1, index.status.value.failures)
        assertTrue(index.search("obsoleteNeedle").isEmpty())
        index.close()
        repository = DocumentsRepository(context)
        index = SearchIndex(repository, model)
        index.refresh()
        assertEquals(mapOf(source.id to 2), provider.readAttempts)
        assertTrue(index.search("obsoleteNeedle").isEmpty())
        source.file.writeText("recoveredNeedle")
        assertTrue(source.file.setLastModified(modified + 4_000))
        index.refresh()
        assertEquals(1, index.status.value.documents)
        assertEquals(0, index.status.value.failures)
        assertEquals("changing.txt", index.search("recoveredNeedle").single().entry.name)
        assertEquals(mapOf(source.id to 3), provider.readAttempts)
    }

    @Test fun partiallyTextualPdfRemainsIndexedWithOnePersistentWarningPerSource(): Unit = runBlocking {
        val paper = provider.add("mixed.pdf", pdf(listOf("", "searchablePdfNeedle", "", "")), "application/pdf")
        fun assertIndexedWithWarning() {
            assertFalse(index.status.value.busy)
            assertEquals(1, index.status.value.documents)
            assertTrue(index.status.value.passages > 0)
            assertEquals("Blank pages must not mark a searchable PDF as excluded", 0, index.status.value.failures)
            assertEquals("Several blank pages are one source warning", 1, index.status.value.warnings)
        }
        index.refresh()
        assertIndexedWithWarning()
        val hit = index.search("searchablePdfNeedle").single()
        assertEquals("mixed.pdf", hit.entry.name)
        assertEquals(2, hit.page)
        index.refresh()
        assertIndexedWithWarning()
        index.close()
        repository = DocumentsRepository(context)
        index = SearchIndex(repository, model)
        index.refresh()
        assertIndexedWithWarning()
        assertEquals(mapOf(paper.id to 1), provider.readAttempts)
        assertEquals(2, index.search("searchablePdfNeedle").single().page)
    }

    @Test fun providerCyclesAndDuplicateRowsAcrossTreeAliasesIndexEachSourceOnce(): Unit = runBlocking {
        val folder = provider.add("Nested", byteArrayOf(), Document.MIME_TYPE_DIR)
        val paper = provider.add("unique.txt", "cycleSafeNeedle").also { it.parent = folder.id }
        val failed = provider.add("binary.txt", byteArrayOf(0, 1, 2, 3)).also { it.parent = folder.id }
        provider.additionalChildren["root"] = listOf(folder.id, paper.id, failed.id)
        provider.additionalChildren[folder.id] = listOf(folder.id, "root", paper.id, failed.id)
        // Both grants expose the same provider document IDs through different tree URIs.
        val nestedTree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, folder.id)
        val nestedRoot = DocumentEntry(
            DocumentsContract.buildDocumentUriUsingTree(nestedTree, folder.id).toString(),
            folder.name, directory = true, treeUri = nestedTree.toString(),
        )
        val roots = org.json.JSONArray().put(root.toJson()).put(nestedRoot.toJson())
        context.getSharedPreferences("notepit-library", 0).edit()
            .putString("state", org.json.JSONObject().put("roots", roots).toString()).commit()
        index.close()
        repository = DocumentsRepository(context)
        index = SearchIndex(repository, model)

        withTimeout(5_000) { index.refresh(force = true) }
        assertFalse(index.status.value.busy)
        assertEquals(1, index.status.value.documents)
        assertEquals("Repeated rows and tree aliases must not multiply exclusions", 1, index.status.value.failures)
        assertEquals("unique.txt", index.search("cycleSafeNeedle").single().entry.name)
        assertEquals("Each canonical source must be opened once even during a rebuild", mapOf(paper.id to 1, failed.id to 1), provider.readAttempts)
        withTimeout(5_000) { index.refresh() }
        assertEquals(1, index.status.value.documents)
        assertEquals(1, index.status.value.failures)
        assertEquals(mapOf(paper.id to 1, failed.id to 1), provider.readAttempts)
        withTimeout(5_000) { index.refresh(force = true) }
        assertEquals(mapOf(paper.id to 2, failed.id to 2), provider.readAttempts)
        assertEquals(1, index.search("cycleSafeNeedle").size)
    }

    /** Runnable smoke/benchmark: logs measured numbers, never hard-codes accuracy/performance claims. */
    @Test fun benchmarkHundredDocumentsWithPdfAndThousandPassages(): Unit = runBlocking {
        val expected = LinkedHashMap<String, String>()
        repeat(99) { document ->
            val name = "bench-$document.txt"
            val text = buildString {
                repeat(10) { passage ->
                    val token = "Needle${document.toString().padStart(3, '0')}P${passage.toString().padStart(2, '0')}"
                    expected[token] = name
                    append("$token "); append("deterministic offline corpus words ".repeat(8)); append('\n')
                }
            }
            provider.add(name, text)
        }
        val pdfToken = "BenchmarkPdfNeedle"
        provider.add("bench.pdf", pdf(listOf("First benchmark page", pdfToken)), "application/pdf")
        expected[pdfToken] = "bench.pdf"
        val corpusBytes = provider.nodes.values.sumOf { it.file.length() }
        val initialStarted = SystemClock.elapsedRealtimeNanos()
        index.refresh()
        val initialMs = (SystemClock.elapsedRealtimeNanos() - initialStarted) / 1_000_000.0
        assertEquals("Benchmark indexing failed: ${index.status.value}", 100, index.status.value.documents)
        assertTrue("Corpus must exercise at least 1000 passages: ${index.status.value}", index.status.value.passages >= 1000)
        val edited = provider.nodes.values.first { it.name == "bench-0.txt" }
        edited.file.appendText("\nIncrementalBenchmarkNeedle")
        val incrementalStarted = SystemClock.elapsedRealtimeNanos()
        index.refresh()
        val incrementalMs = (SystemClock.elapsedRealtimeNanos() - incrementalStarted) / 1_000_000.0
        assertEquals("bench-0.txt", index.search("IncrementalBenchmarkNeedle").single().entry.name)
        // Deterministic sample across the corpus plus the PDF, measuring real end-to-end SAF validation.
        val queries = expected.entries.filterIndexed { position, _ -> position % 10 == 0 || position == expected.size - 1 }
        repeat(5) { index.search(queries[it].key) }
        val latencies = ArrayList<Double>()
        var retrieved = 0
        for ((query, name) in queries) {
            val start = SystemClock.elapsedRealtimeNanos()
            val hits = index.search(query)
            latencies += (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            if (hits.any { it.entry.name == name && it.text.contains(query) }) retrieved++
        }
        latencies.sort()
        fun percentile(value: Double) = latencies[(ceil(value * latencies.size).toInt() - 1).coerceIn(0, latencies.lastIndex)]
        val dbBytes = File(context.noBackupFilesDir, "search").listFiles().orEmpty().sumOf { it.length() }
        val recall = retrieved.toDouble() / queries.size
        Log.i("NotePitSearchBench", "documents=${index.status.value.documents} passages=${index.status.value.passages} corpus_bytes=$corpusBytes db_bytes=$dbBytes initial_ms=$initialMs incremental_ms=$incrementalMs query_p50_ms=${percentile(0.50)} query_p95_ms=${percentile(0.95)} exact_recall=$retrieved/${queries.size} ($recall) model_installed=${model.status.value.installed}")
        assertEquals("Every deterministic literal must retrieve its real source", queries.size, retrieved)
    }

    private suspend fun passageIds(id: String): List<Long> = withContext(Dispatchers.IO) {
        SQLiteDatabase.openDatabase(File(context.noBackupFilesDir, "search/index.sqlite").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT id FROM passages WHERE uri=? ORDER BY id", arrayOf(uri(id).toString())).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
            }
        }
    }

    private fun entry(node: SearchProvider.Node) = DocumentEntry(uri(node.id).toString(), node.name, node.mime, treeUri = TREE.toString(), parentUri = root.uri)
    private suspend fun pdf(pages: List<String>): ByteArray = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)
        PDDocument().use { document ->
            for (text in pages) {
                val page = PDPage(); document.addPage(page)
                if (text.isNotEmpty()) PDPageContentStream(document, page).use { stream ->
                    stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 12f)
                    stream.newLineAtOffset(40f, 700f); stream.showText(text); stream.endText()
                }
            }
            ByteArrayOutputStream().use { out -> document.save(out); out.toByteArray() }
        }
    }

    private class SearchContext(base: Context) : ContextWrapper(base) {
        private val prefix = "search-test-${UUID.randomUUID()}-"
        val privateDirectory = File(base.filesDir, prefix).apply { mkdirs() }
        val resolver = MockContentResolver(this)
        @Volatile var beforeLibraryRead: (() -> Unit)? = null
        override fun getApplicationContext(): Context = this
        override fun getContentResolver() = resolver
        override fun getFilesDir() = File(privateDirectory, "files").apply { mkdirs() }
        override fun getCacheDir() = File(privateDirectory, "cache").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(privateDirectory, "no-backup").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            if (name == "notepit-library") beforeLibraryRead?.invoke()
            return super.getSharedPreferences(prefix + name, mode)
        }
        override fun checkUriPermission(uri: Uri?, pid: Int, uid: Int, modeFlags: Int) = PackageManager.PERMISSION_GRANTED
        override fun checkPermission(permission: String, pid: Int, uid: Int) = PackageManager.PERMISSION_GRANTED
    }

    /** Can withhold metadata to exercise local invalidation and explicit full verification. */
    private class SearchProvider(private val directory: File) : DocumentsProvider() {
        data class Node(val id: String, val name: String, val mime: String, val file: File, var parent: String = "root")
        private val rootNode = Node("root", "Search root", Document.MIME_TYPE_DIR, directory, parent = "")
        val nodes = LinkedHashMap<String, Node>()
        val denied = HashSet<String>()
        val unreadableStreams = HashSet<String>()
        val additionalChildren = HashMap<String, List<String>>()
        val readAttempts = HashMap<String, Int>()
        val reads = HashMap<String, Int>()
        var sawMainThread = false
        var beforeOpen: ((String) -> Unit)? = null
        var changingRootMetadata = false
        var opaqueMetadata = false
        private var rootModified = 0L
        init { directory.mkdirs() }
        fun add(name: String, text: String) = add(name, text.toByteArray(), "text/plain")
        fun add(name: String, bytes: ByteArray, mime: String = "text/plain"): Node {
            val id = "opaque-${nodes.size}-${UUID.randomUUID()}"
            return Node(id, name, mime, File(directory, id).apply { writeBytes(bytes) }).also { nodes[id] = it }
        }
        override fun onCreate() = true
        private fun thread() { if (Looper.myLooper() == Looper.getMainLooper()) sawMainThread = true }
        private fun row(cursor: MatrixCursor, id: String) {
            if (id in denied) throw SecurityException("Unreadable test document")
            val node = if (id == "root") rootNode else (nodes[id] ?: throw FileNotFoundException(id))
            cursor.addRow(cursor.columnNames.map<String, Any?> { name -> when (name) {
                Document.COLUMN_DOCUMENT_ID -> id
                Document.COLUMN_DISPLAY_NAME -> node.name
                Document.COLUMN_MIME_TYPE -> node.mime
                Document.COLUMN_FLAGS -> if (node.mime == Document.MIME_TYPE_DIR) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE
                Document.COLUMN_SIZE -> if (opaqueMetadata || node.mime == Document.MIME_TYPE_DIR) 0L else node.file.length()
                Document.COLUMN_LAST_MODIFIED -> if (id == "root" && changingRootMetadata) ++rootModified else if (opaqueMetadata) 0L else node.file.lastModified()
                else -> null
            } }.toTypedArray())
        }
        override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf("root_id"))
        override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
            thread(); return MatrixCursor(projection ?: COLUMNS).apply { row(this, documentId) }
        }
        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
            thread()
            if (parentDocumentId in denied) throw SecurityException("Unreadable folder")
            if (parentDocumentId != "root" && nodes[parentDocumentId]?.mime != Document.MIME_TYPE_DIR) throw FileNotFoundException(parentDocumentId)
            return MatrixCursor(projection ?: COLUMNS).apply {
                val childIds = nodes.values.filter { it.parent == parentDocumentId }.map { it.id } + additionalChildren[parentDocumentId].orEmpty()
                for (id in childIds) {
                    // Listing can expose a document whose stream subsequently becomes unreadable.
                    val blocked = denied.remove(id)
                    try { row(this, id) } finally { if (blocked) denied.add(id) }
                }
            }
        }
        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
            thread(); signal?.throwIfCanceled()
            if (!mode.contains('w')) readAttempts[documentId] = (readAttempts[documentId] ?: 0) + 1
            beforeOpen?.invoke(documentId)
            if (documentId in denied || documentId in unreadableStreams) throw SecurityException("Unreadable test document")
            val node = nodes[documentId] ?: throw FileNotFoundException(documentId)
            if (!mode.contains('w')) reads[documentId] = (reads[documentId] ?: 0) + 1
            return ParcelFileDescriptor.open(node.file, ParcelFileDescriptor.parseMode(mode))
        }
        override fun deleteDocument(documentId: String) {
            thread(); (nodes.remove(documentId) ?: throw FileNotFoundException(documentId)).file.delete()
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
            private val COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
        }
    }

    companion object {
        private const val AUTHORITY = "io.notepit.search.tests"
        private val TREE = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")
        private fun uri(id: String) = DocumentsContract.buildDocumentUriUsingTree(TREE, id)
    }
}
