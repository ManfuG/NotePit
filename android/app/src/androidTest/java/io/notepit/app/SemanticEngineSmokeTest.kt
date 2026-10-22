package io.notepit.app

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Opt-in only: install the model through the consent UI, disable device networking, then run with
 * -e class io.notepit.app.SemanticEngineSmokeTest -e semanticSmoke true.
 * No test path downloads or stages a model, and the normal suite skips this test before disk access.
 */
@RunWith(AndroidJUnit4::class)
class SemanticEngineSmokeTest {
    @Test fun realOfflineInferenceQualityLatencyAndMemory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Pass -e semanticSmoke true after explicit model installation", InstrumentationRegistry.getArguments().getString("semanticSmoke") == "true")
        val engine = SemanticEngine(instrumentation.targetContext)
        try {
            runBlocking {
                withTimeout(10_000) { engine.status.first { !it.busy } }
                assertTrue("Install the verified model through the consent UI first", engine.status.value.installed)
                val before = memory()
                val passages = listOf(
                    "Deleted notes stay in the trash. Open the trash folder and choose Restore to recover an accidentally removed document.",
                    "To save battery power, reduce screen brightness and enable the phone's power saving mode. Close applications you do not need.",
                    "The tomato soup recipe needs fresh tomatoes, an onion and vegetable stock. Simmer the vegetables for twenty minutes before blending.",
                    "A train ticket can be purchased at the station or booked online. Check the departure platform before boarding the railway service."
                )
                val questions = listOf(
                    "How do I get back a file I erased by mistake?",
                    "My mobile runs out of charge quickly. How can it last longer?",
                    "What ingredients should I cook for a warm vegetable meal?",
                    "Where can I buy a fare for a journey by rail?"
                )
                val coldStart = SystemClock.elapsedRealtimeNanos()
                val first = engine.encode(passages.first())
                val coldMs = elapsedMs(coldStart)
                assertEquals(SemanticEngine.DIMENSIONS, first.size)
                assertTrue("Embedding must be normalized and finite", abs(norm(first) - 1.0) < 0.0001)
                val documents = listOf(first) + passages.drop(1).map { engine.encode(it) }
                val queries = questions.map { engine.encode(it) }
                val matrix = queries.map { query -> documents.map { cosine(query, it) } }
                val winners = matrix.map { row -> row.indices.maxByOrNull { row[it] }!! }
                val correct = winners.withIndex().count { it.index == it.value }
                val margins = matrix.mapIndexed { expected, row -> row[expected] - row.filterIndexed { index, _ -> index != expected }.max() }
                val warm = ArrayList<Double>()
                repeat(8) { index ->
                    val start = SystemClock.elapsedRealtimeNanos()
                    val vector = engine.encode(questions[index % questions.size])
                    warm.add(elapsedMs(start))
                    assertTrue("Repeated inference must remain normalized", abs(norm(vector) - 1.0) < 0.0001)
                    assertTrue("Identical input must be deterministic", cosine(vector, queries[index % queries.size]) > 0.9999)
                }
                val after = memory()
                val report = buildString {
                    appendLine("model=${SemanticEngine.MODEL_NAME}; revision=${SemanticEngine.MODEL_REVISION}; threads=1; tokens=${SemanticEngine.MAX_TOKENS}")
                    appendLine("cold_ms=${number(coldMs)}; warm_median_ms=${number(warm.sorted()[warm.size / 2])}; warm_max_ms=${number(warm.max())}")
                    appendLine("memory_before=$before; memory_after=$after")
                    appendLine("nonidentical_question_top1=$correct/${questions.size}; winners=$winners; mean_cosine_margin=${number(margins.average())}")
                    matrix.forEachIndexed { index, row -> appendLine("query$index cosine=" + row.joinToString(",") { number(it) }) }
                }
                Log.i("SemanticSmoke", report)
                instrumentation.sendStatus(0, Bundle().apply { putString("semantic_smoke", report) })
                assertTrue("Related non-identical questions must outrank unrelated passages. $report", correct >= 3)
                assertTrue("Expected passages must have positive average cosine margin. $report", margins.average() > 0.0)
                assertTrue("Distinct source passages must not collapse to one vector", cosine(documents[0], documents[2]) < 0.95)
                engine.close()
                instrumentation.sendStatus(0, Bundle().apply { putString("semantic_memory_after_close", memory().toString()) })
            }
        } finally {
            engine.close()
        }
    }

    private data class Memory(val totalPssKiB: Int, val nativeHeapBytes: Long, val javaHeapBytes: Long)

    private fun memory(): Memory {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        val runtime = java.lang.Runtime.getRuntime()
        return Memory(info.totalPss, Debug.getNativeHeapAllocatedSize(), runtime.totalMemory() - runtime.freeMemory())
    }

    private fun elapsedMs(start: Long): Double = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
    private fun number(value: Double): String = String.format(Locale.ROOT, "%.4f", value)
    private fun norm(vector: FloatArray): Double = sqrt(vector.sumOf { it.toDouble() * it })
    private fun cosine(left: FloatArray, right: FloatArray): Double = left.indices.sumOf { left[it].toDouble() * right[it] }
}
