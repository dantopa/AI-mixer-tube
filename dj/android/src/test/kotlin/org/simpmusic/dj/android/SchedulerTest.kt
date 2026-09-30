package org.simpmusic.dj.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.simpmusic.dj.android.decode.AudioUnavailableException
import org.simpmusic.dj.android.decode.CancelSignal
import org.simpmusic.dj.android.decode.TrackDecoder
import org.simpmusic.dj.android.render.StereoPcm
import org.simpmusic.dj.android.scheduler.AnalysisPolicy
import org.simpmusic.dj.android.scheduler.AnalyzerUnavailableException
import org.simpmusic.dj.android.scheduler.DeviceConditions
import org.simpmusic.dj.android.scheduler.DjAnalysisScheduler
import org.simpmusic.dj.android.scheduler.UnavailableAnalyzer
import org.simpmusic.dj.android.store.AnalysisStore
import org.simpmusic.dj.model.AnalysisPriority
import org.simpmusic.dj.model.PcmAudio
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.model.TrackAnalyzer
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class SchedulerTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("djsched").toFile()
    }

    private val scopes = ArrayList<CoroutineScope>()

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

    private class FakeDecoder : TrackDecoder {
        val decoded = ArrayList<String>()
        val networkFlags = HashMap<String, Boolean>()
        var gate: CompletableDeferred<Unit>? = null
        var failWith: (String) -> Exception? = { null }
        var sawCancel = false

        override suspend fun decodeForAnalysis(videoId: String, allowNetwork: Boolean, cancel: CancelSignal): PcmAudio {
            decoded += videoId
            networkFlags[videoId] = allowNetwork
            gate?.let { g ->
                if (decoded.size == 1) g.await()
            }
            if (cancel.isCancelled()) sawCancel = true
            failWith(videoId)?.let { throw it }
            return PcmAudio(FloatArray(22_050), 22_050)
        }

        override suspend fun decodeStereoRange(videoId: String, startMs: Long, endMs: Long, cancel: CancelSignal): StereoPcm = error("unused")
    }

    private class FakeAnalyzer(override val id: String = "fake-1") : TrackAnalyzer {
        var throwUnavailable = false

        override fun analyze(videoId: String, audio: PcmAudio): TrackAnalysis {
            if (throwUnavailable) throw AnalyzerUnavailableException("nope")
            return fakeAnalysis(videoId, analyzerId = id)
        }
    }

    private class Rig(
        val scope: TestScope,
        val decoder: FakeDecoder,
        val analyzer: TrackAnalyzer,
        val conditions: Conditions,
        val scheduler: DjAnalysisScheduler,
        val store: AnalysisStore,
        var meteredAllowed: Boolean = false,
    )

    private fun TestScope.rig(analyzer: TrackAnalyzer = FakeAnalyzer(), conditions: Conditions = Conditions()): Rig {
        val decoder = FakeDecoder()
        val store = AnalysisStore(dir)
        var allowed = false
        val policy = AnalysisPolicy(conditions) { allowed }
        // NOT backgroundScope: advanceUntilIdle() deliberately ignores tasks dispatched from it.
        val worker = StandardTestDispatcher(testScheduler)
        val loopScope = CoroutineScope(SupervisorJob() + worker).also { scopes += it }
        val scheduler =
            DjAnalysisScheduler(
                store, analyzer, decoder, policy, loopScope,
                worker,
                clock = { testScheduler.currentTime },
                blockedRetryMs = 30_000L,
            )
        return Rig(this, decoder, analyzer, conditions, scheduler, store).also { r -> r.meteredAllowed = allowed }
    }

    @Test
    fun analysesStoresAndServesARequestedTrack() =
        runTest {
            val r = rig()
            r.scheduler.request("aaa", AnalysisPriority.NOW_PLAYING)
            advanceUntilIdle()
            assertEquals(listOf("aaa"), r.decoder.decoded)
            assertNotNull(r.scheduler.get("aaa"))
            assertEquals("aaa", r.scheduler.observe("aaa").first()!!.videoId)
            // Already fresh: a second request does not decode again.
            r.scheduler.request("aaa", AnalysisPriority.NOW_PLAYING)
            advanceUntilIdle()
            assertEquals(1, r.decoder.decoded.size)
        }

    @Test
    fun servesInPriorityOrderAndDedups() =
        runTest {
            val r = rig()
            r.decoder.gate = CompletableDeferred()
            r.scheduler.request("first", AnalysisPriority.BACKGROUND) // starts and blocks in the decoder
            advanceUntilIdle()
            r.scheduler.request("bg", AnalysisPriority.BACKGROUND)
            r.scheduler.request("next", AnalysisPriority.NEXT_UP)
            r.scheduler.request("now", AnalysisPriority.NOW_PLAYING)
            r.scheduler.request("bg", AnalysisPriority.BACKGROUND) // duplicate
            r.scheduler.request("bg2", AnalysisPriority.BACKGROUND)
            r.scheduler.request("bg2", AnalysisPriority.NEXT_UP) // raise: bg2 now beats bg but not next/now
            r.decoder.gate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("first", "now", "next", "bg2", "bg"), r.decoder.decoded)
        }

    @Test
    fun cancelStaleDropsQueuedUserFacingWorkButKeepsBackground() =
        runTest {
            val r = rig()
            r.decoder.gate = CompletableDeferred()
            r.scheduler.request("running", AnalysisPriority.BACKGROUND)
            advanceUntilIdle()
            r.scheduler.request("oldNext", AnalysisPriority.NEXT_UP)
            r.scheduler.request("bg", AnalysisPriority.BACKGROUND)
            r.scheduler.request("cur", AnalysisPriority.NOW_PLAYING)
            r.scheduler.cancelStale(current = "cur", next = "newNext")
            r.decoder.gate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("running", "cur", "bg"), r.decoder.decoded)
        }

    @Test
    fun runningStaleJobIsToldToCancel() =
        runTest {
            val r = rig()
            r.decoder.gate = CompletableDeferred()
            r.scheduler.request("stale", AnalysisPriority.NEXT_UP)
            advanceUntilIdle()
            r.scheduler.cancelStale(current = "x", next = "y")
            r.decoder.gate!!.complete(Unit)
            advanceUntilIdle()
            assertTrue(r.decoder.sawCancel)
            assertNull("a cancelled analysis must not be stored", r.scheduler.get("stale"))
        }

    @Test
    fun backgroundWorkWaitsOutBatterySaverAndResumes() =
        runTest {
            val c = Conditions(isBatterySaver = true)
            val r = rig(conditions = c)
            r.scheduler.request("bg", AnalysisPriority.BACKGROUND)
            advanceTimeBy(100_000)
            assertTrue("blocked while saving battery", r.decoder.decoded.isEmpty())
            c.isBatterySaver = false
            advanceTimeBy(31_000)
            advanceUntilIdle()
            assertEquals(listOf("bg"), r.decoder.decoded)
        }

    @Test
    fun lowBatteryBlocksBackgroundButNotChargingNorNowPlaying() =
        runTest {
            val c = Conditions(batteryPercent = 15)
            val r = rig(conditions = c)
            r.scheduler.request("bg", AnalysisPriority.BACKGROUND)
            r.scheduler.request("now", AnalysisPriority.NOW_PLAYING)
            advanceTimeBy(1_000)
            assertEquals(listOf("now"), r.decoder.decoded)
            c.isCharging = true
            advanceTimeBy(31_000)
            advanceUntilIdle()
            assertEquals(listOf("now", "bg"), r.decoder.decoded)
        }

    @Test
    fun meteredNetworkMeansCacheOnlyExceptForNowPlaying() =
        runTest {
            val c = Conditions(isMetered = true)
            val r = rig(conditions = c)
            r.scheduler.request("bg", AnalysisPriority.BACKGROUND)
            r.scheduler.request("next", AnalysisPriority.NEXT_UP)
            r.scheduler.request("now", AnalysisPriority.NOW_PLAYING)
            advanceUntilIdle()
            assertEquals(false, r.decoder.networkFlags["bg"])
            assertEquals(false, r.decoder.networkFlags["next"])
            assertEquals(true, r.decoder.networkFlags["now"])
        }

    @Test
    fun failuresBackOffThenAreRememberedAsFailed() =
        runTest {
            val r = rig()
            r.decoder.failWith = { AudioUnavailableException("not cached") }
            r.scheduler.request("bad", AnalysisPriority.NEXT_UP)
            advanceTimeBy(1_000)
            assertEquals(1, r.decoder.decoded.size)
            advanceTimeBy(5_000)
            assertEquals("second try after the 5 s back-off", 2, r.decoder.decoded.size)
            advanceTimeBy(20_000)
            assertEquals("third try after the 20 s back-off", 3, r.decoder.decoded.size)
            advanceTimeBy(100_000)
            assertEquals("given up after three failures", 3, r.decoder.decoded.size)
            // and a low-priority re-request inside the memory window is ignored
            r.scheduler.request("bad", AnalysisPriority.BACKGROUND)
            advanceTimeBy(60_000)
            assertEquals(3, r.decoder.decoded.size)
            assertNotNull(r.scheduler.state.value.lastError)
        }

    @Test
    fun unavailableAnalyzerNeverQueuesAnything() =
        runTest {
            val r = rig(analyzer = UnavailableAnalyzer())
            r.scheduler.request("x", AnalysisPriority.NOW_PLAYING)
            advanceUntilIdle()
            assertTrue(r.decoder.decoded.isEmpty())
            assertFalse(r.scheduler.state.value.analyzerAvailable)
            assertNull(r.scheduler.get("x"))
        }

    @Test
    fun analyzerThatTurnsOutUnavailableDrainsTheQueue() =
        runTest {
            val a = FakeAnalyzer().also { it.throwUnavailable = true }
            val r = rig(analyzer = a)
            r.scheduler.request("a", AnalysisPriority.NOW_PLAYING)
            r.scheduler.request("b", AnalysisPriority.BACKGROUND)
            advanceUntilIdle()
            assertEquals("worker stopped after the first documented failure: ${r.decoder.decoded}", 1, r.decoder.decoded.size)
            assertFalse(r.scheduler.state.value.analyzerAvailable)
            assertEquals(0, r.scheduler.state.value.queued)
        }

    @Test
    fun staleAnalyzerIdInStoreTriggersReanalysis() =
        runTest {
            AnalysisStore(dir).put(fakeAnalysis("old", analyzerId = "dsp-0"))
            val r = rig(analyzer = FakeAnalyzer("dsp-1"))
            r.scheduler.request("old", AnalysisPriority.NOW_PLAYING)
            advanceUntilIdle()
            assertEquals(listOf("old"), r.decoder.decoded)
            assertEquals("dsp-1", r.scheduler.get("old")!!.analyzerId)
        }
}
