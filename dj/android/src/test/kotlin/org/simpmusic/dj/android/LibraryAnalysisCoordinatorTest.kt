package org.simpmusic.dj.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.simpmusic.dj.android.library.AnalysisPause
import org.simpmusic.dj.android.library.BackgroundAnalysisPort
import org.simpmusic.dj.android.library.CandidateSelection
import org.simpmusic.dj.android.library.CandidateSource
import org.simpmusic.dj.android.library.LibraryAnalysisCoordinator
import org.simpmusic.dj.android.library.LibraryAnalysisPolicy
import org.simpmusic.dj.android.scheduler.AnalysisOutcome
import org.simpmusic.dj.android.scheduler.DeviceConditions

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryAnalysisCoordinatorTest {
    private val scopes = ArrayList<CoroutineScope>()

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private class Conditions(
        override var isBatterySaver: Boolean = false,
        override var isCharging: Boolean = false,
        override var batteryPercent: Int = 80,
        override var isMetered: Boolean = false,
    ) : DeviceConditions

    private class Port : BackgroundAnalysisPort {
        override var available = true
        val analysed = HashSet<String>()
        val order = ArrayList<String>()
        var running = 0
        var maxRunning = 0
        var cancelled = 0
        var gate: CompletableDeferred<Unit>? = null
        var outcome: (String) -> AnalysisOutcome = { AnalysisOutcome.Done(it) }

        override suspend fun isAnalysed(videoId: String) = videoId in analysed

        override suspend fun analyse(videoId: String): AnalysisOutcome {
            running++
            maxRunning = maxOf(maxRunning, running)
            order += videoId
            try {
                gate?.await()
                val o = outcome(videoId)
                if (o is AnalysisOutcome.Done) analysed += videoId
                return o
            } finally {
                running--
            }
        }

        override fun cancelBackground() {
            cancelled++
        }
    }

    private class Rig(val coordinator: LibraryAnalysisCoordinator, val port: Port, val conditions: Conditions, val source: FakeLibrarySource, var busy: Boolean = false)

    private fun TestScope.rig(
        candidates: List<org.simpmusic.dj.android.library.LibraryCandidate>,
        cap: Int = 300,
        conditions: Conditions = Conditions(),
    ): Rig {
        val port = Port()
        val source = FakeLibrarySource(candidates)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { scopes += it }
        lateinit var rig: Rig
        val c =
            LibraryAnalysisCoordinator(
                source = source,
                port = port,
                policy = LibraryAnalysisPolicy(conditions),
                transitionBusy = { rig.busy },
                scope = scope,
                cap = cap,
                idleRetryMs = 30_000L,
                busyPollMs = 2_000L,
            )
        rig = Rig(c, port, conditions, source)
        return rig
    }

    @Test
    fun analysesInPriorityOrderLikedThenDownloadedThenMostPlayedThenRecent_DeduplicatedAndWithoutVideos() =
        runTest {
            val r =
                rig(
                    listOf(
                        cand("recent1", CandidateSource.RECENT),
                        cand("most1", CandidateSource.MOST_PLAYED),
                        cand("dl1", CandidateSource.DOWNLOADED, downloaded = true),
                        cand("liked1", CandidateSource.LIKED),
                        cand("liked1", CandidateSource.DOWNLOADED, downloaded = true), // same song again: the LIKED entry wins
                        cand("video1", CandidateSource.LIKED, videoType = UGC), // a known video: never analysed
                        cand("short1", CandidateSource.LIKED, durationSeconds = 20), // a jingle
                        cand("mix1", CandidateSource.LIKED, durationSeconds = 3 * 3600), // a DJ mix / podcast
                    ),
                )
            r.coordinator.start()
            advanceUntilIdle()
            assertEquals(listOf("liked1", "dl1", "most1", "recent1"), r.port.order)
            val s = r.coordinator.state.value
            assertEquals(4, s.total)
            assertEquals(4, s.analysed)
            assertTrue(s.finished)
            assertFalse(s.running)
        }

    @Test
    fun theSetIsCappedAndKeepsTheHighestPriorityOnes() =
        runTest {
            val many = (1..10).map { cand("liked$it", CandidateSource.LIKED) } + (1..10).map { cand("recent$it", CandidateSource.RECENT) }
            val r = rig(many, cap = 12)
            r.coordinator.start()
            advanceUntilIdle()
            assertEquals(12, r.coordinator.state.value.total)
            assertTrue(r.port.order.take(10).all { it.startsWith("liked") })
            assertEquals(12, r.port.order.size)
            assertEquals(300, CandidateSelection.DEFAULT_CAP)
        }

    @Test
    fun skipsWhatIsAlreadyAnalysedAndReportsNofM() =
        runTest {
            val r = rig(listOf(cand("a", CandidateSource.LIKED), cand("b", CandidateSource.LIKED), cand("c", CandidateSource.LIKED)))
            r.port.analysed += "a"
            r.coordinator.refreshProgress()
            assertEquals(1, r.coordinator.state.value.analysed)
            assertEquals(3, r.coordinator.state.value.total)
            r.coordinator.start()
            advanceUntilIdle()
            assertEquals(listOf("b", "c"), r.port.order)
            assertEquals(3, r.coordinator.state.value.analysed)
        }

    @Test
    fun oneTrackAtATime() =
        runTest {
            val r = rig((1..6).map { cand("t$it", CandidateSource.LIKED) })
            r.port.gate = CompletableDeferred()
            r.coordinator.start()
            runCurrent()
            assertEquals("the second is not requested while the first runs", listOf("t1"), r.port.order)
            r.port.gate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, r.port.maxRunning)
            assertEquals(6, r.port.order.size)
        }

    @Test
    fun waitsForPowerOrHalfABatteryAndNeverRunsInBatterySaver() =
        runTest {
            val r = rig(listOf(cand("a", CandidateSource.LIKED)), conditions = Conditions(batteryPercent = 29, isCharging = false))
            r.coordinator.start()
            advanceTimeBy(5_000)
            assertTrue(r.port.order.isEmpty())
            assertEquals(AnalysisPause.LOW_BATTERY, r.coordinator.state.value.pause)

            r.conditions.batteryPercent = 30
            r.conditions.isBatterySaver = true
            advanceTimeBy(31_000)
            assertTrue("battery saver blocks even with enough charge", r.port.order.isEmpty())
            assertEquals(AnalysisPause.BATTERY_SAVER, r.coordinator.state.value.pause)

            r.conditions.isBatterySaver = false
            advanceTimeBy(31_000)
            advanceUntilIdle()
            assertEquals(listOf("a"), r.port.order)

            val charging = rig(listOf(cand("b", CandidateSource.LIKED)), conditions = Conditions(batteryPercent = 5, isCharging = true))
            charging.coordinator.start()
            advanceUntilIdle()
            assertEquals("charging is enough at any level", listOf("b"), charging.port.order)
        }

    @Test
    fun onAMeteredNetworkOnlyDownloadedSongsAreAnalysedAndTheRestWaitForWifi() =
        runTest {
            val r =
                rig(
                    listOf(
                        cand("streamed", CandidateSource.LIKED),
                        cand("downloaded", CandidateSource.DOWNLOADED, downloaded = true),
                    ),
                    conditions = Conditions(isMetered = true),
                )
            r.coordinator.start()
            advanceTimeBy(5_000)
            assertEquals("a downloaded song needs no network at all", listOf("downloaded"), r.port.order)
            assertEquals(AnalysisPause.METERED_NETWORK, r.coordinator.state.value.pause)

            r.conditions.isMetered = false
            advanceTimeBy(31_000)
            advanceUntilIdle()
            assertEquals(listOf("downloaded", "streamed"), r.port.order)
        }

    @Test
    fun startsNothingNewWhileADjTransitionIsBeingPreparedAndResumesAfterwards() =
        runTest {
            val r = rig(listOf(cand("a", CandidateSource.LIKED), cand("b", CandidateSource.LIKED)))
            r.busy = true
            r.coordinator.start()
            advanceTimeBy(10_000)
            assertTrue(r.port.order.isEmpty())
            assertEquals(AnalysisPause.TRANSITION_RENDERING, r.coordinator.state.value.pause)
            r.busy = false
            advanceTimeBy(3_000)
            advanceUntilIdle()
            assertEquals(listOf("a", "b"), r.port.order)
        }

    @Test
    fun stopCancelsTheWorkAndKeepsWhatIsDone_ThenStartResumes() =
        runTest {
            val r = rig((1..5).map { cand("t$it", CandidateSource.LIKED) })
            r.port.gate = CompletableDeferred()
            r.coordinator.start()
            runCurrent()
            r.coordinator.stop()
            runCurrent()
            assertEquals(1, r.port.cancelled)
            assertFalse(r.coordinator.state.value.running)
            assertNull(r.coordinator.state.value.current)

            r.port.gate = null
            r.port.order.clear()
            r.coordinator.start()
            advanceUntilIdle()
            assertEquals("resumed: nothing is analysed twice", r.port.order.toSet().size, r.port.order.size)
            assertEquals(5, r.coordinator.state.value.analysed)
        }

    @Test
    fun aTrackThatCannotBeAnalysedIsGivenUpOnForTheSessionAndTheRestContinue() =
        runTest {
            val r = rig(listOf(cand("bad", CandidateSource.LIKED), cand("good", CandidateSource.LIKED)))
            r.port.outcome = { if (it == "bad") AnalysisOutcome.Failed(it, "no audio") else AnalysisOutcome.Done(it) }
            r.coordinator.start()
            advanceUntilIdle()
            assertEquals(listOf("bad", "good"), r.port.order)
            val s = r.coordinator.state.value
            assertEquals(1, s.failed)
            assertEquals(1, s.analysed)
            assertTrue("finished: nothing analysable is left", s.finished)
        }

    @Test
    fun withoutAnAnalyzerItStopsInsteadOfSpinning() =
        runTest {
            val r = rig(listOf(cand("a", CandidateSource.LIKED)))
            r.port.available = false
            r.coordinator.start()
            advanceUntilIdle()
            assertTrue(r.port.order.isEmpty())
            assertEquals(AnalysisPause.ANALYZER_MISSING, r.coordinator.state.value.pause)
            assertFalse(r.coordinator.state.value.running)
        }
}
