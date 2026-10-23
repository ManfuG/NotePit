package io.notepit.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class IndexRefreshQueueTest {
    @Test fun readNotificationsSettleAndALaterIndependentHintStillRuns() = runTest {
        val requests = IndexRefreshQueue()
        var passes = 0
        backgroundScope.launch {
            requests.run {
                passes++
                requests.request(providerHint = true)
                passes == 1
            }
        }
        requests.request()
        advanceTimeBy(10_000); runCurrent()
        assertEquals("Read-generated hints must settle after validation", 2, passes)
        requests.request(providerHint = true)
        advanceTimeBy(10_000); runCurrent()
        assertEquals("An independent hint must receive a fresh pass and validation", 4, passes)
    }

    @Test fun unchangedNormalPassStillValidatesAnInterveningEdit() = runTest {
        val requests = IndexRefreshQueue()
        var source = 0
        var indexed = 0
        var passes = 0
        backgroundScope.launch {
            requests.run {
                passes++
                val changed = source != indexed
                indexed = source
                if (passes == 1) source++ // A write to an already visited source.
                requests.request(providerHint = true)
                changed
            }
        }
        requests.request()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(source, indexed)
        assertEquals("The changed validation must itself be validated before settling", 3, passes)
    }

    @Test fun forcedRequestDuringValidationCannotBeDrainedByReadHints() = runTest {
        val requests = IndexRefreshQueue()
        val forces = mutableListOf<Boolean>()
        backgroundScope.launch {
            requests.run { force ->
                forces += force
                if (forces.size == 2) requests.request(force = true)
                requests.request(providerHint = true)
                force || forces.size == 1
            }
        }
        requests.request()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf(false, false, true, false), forces)
    }

    @Test fun cancelledForcedPassReturnsOwnershipBeforeReplacementClaimsWork() = runTest {
        val requests = IndexRefreshQueue()
        val forces = mutableListOf<Boolean>()
        val first = backgroundScope.launch {
            try {
                requests.run { force ->
                    forces += force
                    try { awaitCancellation() }
                    finally { withContext(NonCancellable) { delay(2_000) } }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
        }
        requests.request(force = true)
        advanceTimeBy(651); runCurrent()
        assertEquals(listOf(true), forces)
        first.cancel()
        backgroundScope.launch { requests.run { force -> forces += force; false } }
        advanceTimeBy(10_000); runCurrent()
        assertEquals("A late cancellation must not strand or downgrade the forced rebuild", listOf(true, true), forces)
    }

    @Test fun cancellationDuringDebounceDoesNotConsumeTheOnlyForcedWakeup() = runTest {
        val requests = IndexRefreshQueue()
        val forces = mutableListOf<Boolean>()
        val first = backgroundScope.launch { requests.run { force -> forces += force; false } }
        requests.request(force = true)
        advanceTimeBy(100); runCurrent()
        first.cancel()
        backgroundScope.launch { requests.run { force -> forces += force; false } }
        advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf(true), forces)
    }
}
