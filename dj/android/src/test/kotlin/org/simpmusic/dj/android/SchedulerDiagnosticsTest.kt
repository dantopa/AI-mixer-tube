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
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.simpmusic.dj.android.decode.AudioNeedsNetworkException
import org.simpmusic.dj.android.decode.AudioUnavailableException
import org.simpmusic.dj.android.decode.CancelSignal
import org.simpmusic.dj.android.decode.TrackDecoder
import org.simpmusic.dj.android.render.StereoPcm
import org.simpmusic.dj.android.scheduler.AnalysisPolicy
import org.simpmusic.dj.android.scheduler.AnalysisStatus
import org.simpmusic.dj.android.scheduler.BlockReason
import org.simpmusic.dj.android.scheduler.DeviceConditions
import org.simpmusic.dj.android.scheduler.DjAnalysisScheduler
import org.simpmusic.dj.android.store.AnalysisStore
import org.simpmusic.dj.model.AnalysisPriority
import org.simpmusic.dj.model.PcmAudio
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.model.TrackAnalyzer
import java.io.File
import java.nio.file.Files

/** What the screen says per track ([DjAnalysisScheduler.statusOf]) and that no failure can end the worker. */
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulerDiagnosticsTest {
    private lateinit var dir: File
    private val scopes = ArrayList<CoroutineScope>()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("djdiag").toFile()
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        dir.deleteRecursively()
    }

    private class Conditions(
        override var isBatterySaver: Boolean = false,
        override var isCharging: Boolean = false,
        override var batteryPercent: Int = 80,
        override var isMetered: Boolean = false,
    ) : DeviceConditions

    private class Decoder : TrackDecoder {
        var gate: CompletableDeferred<Unit>? = null
        var fail: (String, Boolean) -> Throwable? = { _, _ -> null }
        val networkAllowed = HashMap<String, Boolean>()

        override suspend fun decodeForAnalysis(videoId: String, allowNetwork: Boolean, cancel: CancelSignal): PcmAudio {
            networkAllowed[videoId] = allowNetwork
            gate?.await()
            fail(videoId, allowNetwork)?.let { throw it }
            return PcmAudio(FloatArray(22_050), 22_050)
        }

        override suspend fun decodeStereoRange(videoId: String, startMs: Long, endMs: Long, cancel: CancelSignal): StereoPcm = error("unused")
    }

    private class Analyzer : TrackAnalyzer {
        override val id = "fake-1"
        var error: Throwable? = null

        override fun analyze(videoId: String, audio: PcmAudio): TrackAnalysis {
            error?.let { throw it }
            return fakeAnalysis(videoId, analyzerId = id)
        }
    }

    private class Rig(val scheduler: DjAnalysisScheduler, val decoder: Decoder, val analyzer: Analyzer, val conditions: Conditions)

    private fun TestScope.rig(conditions: Conditions = Conditions(), analyzeOnMetered: Boolean = true): Rig {
        val decoder = Decoder()
        val analyzer = Analyzer()
        val worker = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + worker).also { scopes += it }
        val s =
            DjAnalysisScheduler(
                AnalysisStore(dir), analyzer, decoder, AnalysisPolicy(conditions) { analyzeOnMetered }, scope, worker,
                io = worker, clock = { testScheduler.currentTime }, blockedRetryMs = 30_000L,
            )
        return Rig(s, decoder, analyzer, conditions)
    }

    @Test
    fun statusFollowsATrackFromNothingToAnalysed() =
        runTest {
            val r = rig()
            assertEquals(AnalysisStatus.NotRequested, r.scheduler.statusOf("a"))
            r.decoder.gate = CompletableDeferred()
            r.scheduler.request("a", AnalysisPriority.NOW_PLAYING)
            r.scheduler.request("b", AnalysisPriority.NEXT_UP)
            advanceTimeBy(3_000)
            assertTrue("running one is Analysing", r.scheduler.statusOf("a") is AnalysisStatus.Analysing)
            assertEquals("the other waits its turn", AnalysisStatus.Queued, r.scheduler.statusOf("b"))
            r.decoder.gate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(AnalysisStatus.Analysed, r.scheduler.statusOf("a"))
            assertEquals(AnalysisStatus.Analysed, r.scheduler.statusOf("b"))
        }

    @Test
    fun batterySaverShowsAsBlockedNotAsFailure() =
        runTest {
            val r = rig(Conditions(isBatterySaver = true, batteryPercent = 80))
            r.scheduler.request("a", AnalysisPriority.NEXT_UP)
            advanceTimeBy(5_000)
            assertEquals(AnalysisStatus.Blocked(BlockReason.BATTERY_SAVER), r.scheduler.statusOf("a"))
            assertTrue(r.decoder.networkAllowed.isEmpty())
            r.scheduler.shutdown() // a blocked entry keeps the worker polling forever: runTest would never go idle
        }

    @Test
    fun aDecodeThatMayNotUseTheNetworkStaysQueuedAsNeedsNetworkAndIsNotCountedAsAFailure() =
        runTest {
            val r = rig(Conditions(isMetered = true), analyzeOnMetered = false)
            r.decoder.fail = { _, allowed -> if (!allowed) AudioNeedsNetworkException("cache incomplete", partiallyCached = true) else null }
            r.scheduler.request("a", AnalysisPriority.NEXT_UP)
            advanceTimeBy(2_000)
            assertEquals(false, r.decoder.networkAllowed["a"])
            assertEquals(AnalysisStatus.NeedsNetwork(metered = true), r.scheduler.statusOf("a"))
            assertTrue("not a failure: nothing in the recent errors", r.scheduler.state.value.recentErrors.isEmpty())

            // The user switches "analyze on mobile data" on (or Wi-Fi arrives): the next retry gets through.
            r.conditions.isMetered = false
            advanceTimeBy(31_000)
            advanceUntilIdle()
            assertEquals(AnalysisStatus.Analysed, r.scheduler.statusOf("a"))
        }

    @Test
    fun onMobileDataTheNextTrackIsAnalysedByDefaultButTheLibraryNever() =
        runTest {
            val policy = AnalysisPolicy(Conditions(isMetered = true)) { true }
            assertTrue(policy.mayUseNetwork(AnalysisPriority.NOW_PLAYING))
            assertTrue("the default setting lets the next track use mobile data", policy.mayUseNetwork(AnalysisPriority.NEXT_UP))
            assertTrue("library warming never spends the data plan", !policy.mayUseNetwork(AnalysisPriority.BACKGROUND))
            val off = AnalysisPolicy(Conditions(isMetered = true)) { false }
            assertTrue(!off.mayUseNetwork(AnalysisPriority.NEXT_UP))
        }

    @Test
    fun failuresShowTheirMessageRetryAndFinallyFailedAndKeepTheLastThree() =
        runTest {
            val r = rig()
            r.decoder.fail = { id, _ -> AudioUnavailableException("no stream url for $id") }
            r.scheduler.request("a", AnalysisPriority.NEXT_UP)
            advanceTimeBy(1_000)
            val retrying = r.scheduler.statusOf("a")
            assertTrue("first failure waits out a back-off: $retrying", retrying is AnalysisStatus.Retrying)
            assertTrue((retrying as AnalysisStatus.Retrying).lastError!!.contains("no stream url for a"))
            advanceTimeBy(200_000) // 5 s, 20 s, 80 s back-offs
            advanceUntilIdle()
            val failed = r.scheduler.statusOf("a")
            assertTrue("after three failures it is given up on: $failed", failed is AnalysisStatus.Failed)
            assertTrue((failed as AnalysisStatus.Failed).message!!.contains("no stream url for a"))
            assertEquals(3, r.scheduler.state.value.recentErrors.size)
            listOf("b", "c").forEach {
                r.scheduler.request(it, AnalysisPriority.NEXT_UP)
            }
            advanceTimeBy(200_000)
            advanceUntilIdle()
            assertEquals("only the last three failure messages are kept", 3, r.scheduler.state.value.recentErrors.size)
            assertTrue(r.scheduler.state.value.recentErrors.last().startsWith("c:") || r.scheduler.state.value.recentErrors.last().startsWith("b:"))
        }

    @Test
    fun anErrorFromTheAnalyzerFailsThatJobAndTheWorkerCarriesOn() =
        runTest {
            val r = rig()
            // e.g. UnsatisfiedLinkError from a missing native library: an Error, not an Exception
            r.analyzer.error = UnsatisfiedLinkError("libonnxruntime.so not found")
            r.scheduler.request("a", AnalysisPriority.NOW_PLAYING)
            advanceTimeBy(1_000)
            assertTrue(r.scheduler.state.value.lastError!!.contains("libonnxruntime"))
            r.analyzer.error = null
            r.scheduler.request("b", AnalysisPriority.NOW_PLAYING)
            advanceUntilIdle()
            assertNotNull("the loop survived the Error and analysed the next track", r.scheduler.get("b"))
        }

    @Test
    fun aRequestForTheNextTrackPreemptsARunningLibraryAnalysis() =
        runTest {
            val r = rig()
            r.decoder.gate = CompletableDeferred()
            r.scheduler.request("lib", AnalysisPriority.BACKGROUND)
            advanceTimeBy(1_000)
            assertTrue(r.scheduler.statusOf("lib") is AnalysisStatus.Analysing)
            r.scheduler.request("next", AnalysisPriority.NEXT_UP)
            r.decoder.gate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(AnalysisStatus.Analysed, r.scheduler.statusOf("next"))
        }
}
