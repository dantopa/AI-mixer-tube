package org.simpmusic.dj.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.simpmusic.dj.android.render.RenderRequest
import org.simpmusic.dj.android.render.RenderedWindow
import org.simpmusic.dj.android.render.StereoPcm
import org.simpmusic.dj.android.render.TransitionWindowRenderer
import org.simpmusic.dj.android.scheduler.AnalysisPolicy
import org.simpmusic.dj.android.scheduler.DeviceConditions
import org.simpmusic.dj.android.scheduler.DjAnalysisScheduler
import org.simpmusic.dj.android.store.AnalysisStore
import org.simpmusic.dj.model.DjSettings
import org.simpmusic.dj.model.PcmAudio
import org.simpmusic.dj.model.PlanConstraints
import org.simpmusic.dj.model.PlanKind
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.model.TrackAnalyzer
import org.simpmusic.dj.model.TransitionPlan
import org.simpmusic.dj.model.TransitionPlanner
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class DjEngineTest {
    private lateinit var dir: File
    private val scopes = ArrayList<CoroutineScope>()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("djengine").toFile()
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        dir.deleteRecursively()
    }

    private class Cond : DeviceConditions {
        override val isBatterySaver = false
        override val isCharging = true
        override val batteryPercent = 100
        override val isMetered = false
    }

    private class Decoder : TrackDecoder {
        val ranges = ArrayList<Triple<String, Long, Long>>()

        /** Tracks whose audio cannot be had (no stream url): every analysis decode of them throws. */
        val unavailable = HashSet<String>()

        /** Virtual time an analysis decode takes. */
        var analysisDecodeMs = 0L

        override suspend fun decodeForAnalysis(videoId: String, allowNetwork: Boolean, cancel: CancelSignal): PcmAudio {
            if (videoId in unavailable) throw AudioUnavailableException("no stream url for $videoId")
            if (analysisDecodeMs > 0) kotlinx.coroutines.delay(analysisDecodeMs)
            return PcmAudio(FloatArray(22_050), 22_050)
        }

        override suspend fun decodeStereoRange(videoId: String, startMs: Long, endMs: Long, cancel: CancelSignal): StereoPcm {
            ranges += Triple(videoId, startMs, endMs)
            return StereoPcm(FloatArray(2 * 48), 48_000, startMs)
        }
    }

    private class Analyzer(override val id: String = "fake-1") : TrackAnalyzer {

        override fun analyze(videoId: String, audio: PcmAudio) = fakeAnalysis(videoId, analyzerId = id)
    }

    private class Planner(var plan: (TrackAnalysis?, TrackAnalysis?) -> TransitionPlan) : TransitionPlanner {
        var calls = 0
        val inputs = ArrayList<Pair<TrackAnalysis?, TrackAnalysis?>>()
        val constraints = ArrayList<PlanConstraints>()

        override fun plan(from: TrackAnalysis?, to: TrackAnalysis?, settings: DjSettings, constraints: PlanConstraints): TransitionPlan {
            this.constraints += constraints
            inputs += from to to
            return plan(from, to, settings)
        }

        override fun plan(from: TrackAnalysis?, to: TrackAnalysis?, settings: DjSettings): TransitionPlan {
            calls++
            return plan(from, to)
        }
    }

    private class Renderer : TransitionWindowRenderer {
        val requests = ArrayList<RenderRequest>()
        var failWith: Exception? = null

        override fun render(request: RenderRequest): RenderedWindow {
            failWith?.let { throw it }
            requests += request
            request.output.writeBytes(ByteArray(64))
            return RenderedWindow(request.output, request.sampleRate, 48_000L * (request.endRelMs - request.startRelMs) / 1000)
        }
    }

    private class Rig(
        val engine: DjEngine,
        val decoder: Decoder,
        val planner: Planner,
        val renderer: Renderer,
        val settings: MutableStateFlow<DjSettings>,
        val prepared: ArrayList<PreparedTransition>,
        val windowDir: File,
    )

    private fun TestScope.rig(
        enabled: Boolean = true,
        plan: (TrackAnalysis?, TrackAnalysis?) -> TransitionPlan = { a, b -> fakePlan(a!!.videoId, b!!.videoId) },
        analyzerId: String = "fake-1",
        vocals: org.simpmusic.dj.ml.VocalProvider? = null,
        stored: List<TrackAnalysis> = emptyList(),
        analysisDecodeMs: Long = 0L,
    ): Rig {
        val worker = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + worker).also { scopes += it }
        val decoder = Decoder().also { it.analysisDecodeMs = analysisDecodeMs }
        val settings = MutableStateFlow(DjSettings(enabled = enabled))
        val store = AnalysisStore(File(dir, "a")).also { st -> stored.forEach { st.put(it) } }
        val scheduler = DjAnalysisScheduler(store, Analyzer(analyzerId), decoder, AnalysisPolicy(Cond()) { true }, scope, worker, io = worker, clock = { testScheduler.currentTime }, vocals = vocals)
        val planner = Planner(plan)
        val renderer = Renderer()
        val windowDir = File(dir, "w")
        val engine = DjEngine(scope, scheduler, planner, renderer, decoder, settings, worker, windowDir)
        val prepared = ArrayList<PreparedTransition>()
        engine.setOnPrepared { prepared += it }
        return Rig(engine, decoder, planner, renderer, settings, prepared, windowDir)
    }

    @Test
    fun pipelineAnalysesPlansDecodesRendersAndNotifies() =
        runTest {
            val r = rig()
            r.engine.onQueueContext(DjQueueContext("A", "B"))
            advanceUntilIdle()
            assertEquals(1, r.planner.calls)
            assertEquals(1, r.renderer.requests.size)
            val req = r.renderer.requests.single()
            val tl = WindowTimelineHelper.build(req.plan)
            assertEquals(tl.startRelMs, req.startRelMs)
            assertEquals(tl.endRelMs, req.endRelMs)
            // Both ranges were requested, with the margins the timeline computed, for the right tracks.
            assertEquals(listOf("A", "B"), r.decoder.ranges.map { it.first })
            assertEquals(tl.outgoingDecodeRange().first, r.decoder.ranges[0].second)
            assertEquals(tl.incomingDecodeRange().last, r.decoder.ranges[1].third)
            assertEquals(1, r.prepared.size)
            val p = r.engine.prepared("A", "B")
            assertNotNull(p)
            assertTrue(p!!.window.file.exists())
            assertEquals("ready", r.engine.debug.value.phase)
            assertEquals(PlanKind.BEAT_MATCHED, r.engine.debug.value.kind)
            assertTrue(r.engine.debug.value.summary().contains("124.0 BPM"))
            // Consuming deletes the window and blocks a retry of the same pair.
            r.engine.consumed(p, "test done")
            assertFalse(p.window.file.exists())
            assertNull(r.engine.prepared("A", "B"))
            r.engine.onQueueContext(DjQueueContext("A", "B"))
            advanceUntilIdle()
            assertEquals(1, r.renderer.requests.size)
        }

    @Test
    fun mixNowReplansWithAShortSpanAndPublishesTheEarlierMix() =
        runTest {
            // the regular plan exits at 180 s; a "mix now" plan exits 5 s after its earliest allowed point
            val r = rig()
            r.engine.onPosition(30_000)
            r.engine.onQueueContext(DjQueueContext("A", "B"))
            advanceUntilIdle()
            assertEquals(180_000L, r.engine.prepared("A", "B")!!.plan.exitPointMs)
            assertEquals(Long.MAX_VALUE, r.planner.constraints.single().latestExitMs)

            r.planner.plan = { a, b ->
                val c = r.planner.constraints.last()
                fakePlan(a!!.videoId, b!!.videoId, exit = c.earliestExitMs + 5_000)
            }
            assertEquals(MixNowResult.STARTED, r.engine.mixNow())
            advanceUntilIdle()
            val c = r.planner.constraints.last()
            assertEquals(55_000L, c.earliestExitMs)
            assertEquals(75_000L, c.latestExitMs)
            val p = r.engine.prepared("A", "B")!!
            assertEquals(60_000L, p.plan.exitPointMs)
            assertEquals(2, r.prepared.size)
            // the new window is close now: a second tap keeps it
            assertEquals(MixNowResult.ALREADY_SOON, r.engine.mixNow())
        }

    @Test
    fun mixNowWithNothingMixableSoonKeepsTheRegularPlan() =
        runTest {
            val r = rig()
            r.engine.onPosition(30_000)
            r.engine.onQueueContext(DjQueueContext("A", "B"))
            advanceUntilIdle()
            // every "mix now" attempt lands beyond the span (e.g. only the end-of-track fallback mixes)
            assertEquals(MixNowResult.STARTED, r.engine.mixNow())
            advanceUntilIdle()
            assertEquals(180_000L, r.engine.prepared("A", "B")!!.plan.exitPointMs)
            assertEquals("mix now: no good point soon", r.engine.debug.value.lastOutcome)
        }

    @Test
    fun mixNowWithoutAPairOrWhileMixingSaysSo() =
        runTest {
            val r = rig()
            assertEquals(MixNowResult.NO_PAIR, r.engine.mixNow())
            r.engine.onQueueContext(DjQueueContext("A", "B"))
            advanceUntilIdle()
            r.engine.onMixStarted(r.engine.prepared("A", "B")!!)
            assertEquals(MixNowResult.ALREADY_MIXING, r.engine.mixNow())
            r.settings.value = DjSettings(enabled = false)
            assertEquals(MixNowResult.DISABLED, r.engine.mixNow())
        }

    @Test
    fun aRunningMixIsVisibleInTheDebugStateAndEndsWithConsumed() =
        runTest {
            val r = rig()
            r.engine.onQueueContext(DjQueueContext("A", "B"))
            advanceUntilIdle()
            val p = r.engine.prepared("A", "B")!!
            val ready = r.engine.debug.value
            assertEquals("ready", ready.phase)
            assertEquals("the countdown target: where the outgoing track reaches the mix", p.plan.exitPointMs, ready.mixAtMs)
            assertFalse(ready.isHeavy && ready.isMixing)

            r.engine.onMixStarted(p)
            val mixing = r.engine.debug.value
            assertTrue(mixing.isMixing)
            assertTrue("busy: the library analysis keeps out of the way", mixing.isHeavy)
            assertNotNull(mixing.mixStartedAtEpochMs)
            assertEquals(p.plan.overlapMs - p.timeline.startRelMs, mixing.mixDurationMs)
            assertEquals(mixing.fromBpm, ready.fromBpm)

            r.engine.consumed(p, "DJ mix done")
            val done = r.engine.debug.value
            assertEquals("idle", done.phase)
            assertNull(done.mixStartedAtEpochMs)
            assertEquals("DJ mix done", done.lastOutcome)
        }

    /** A device log held a transition 10 minutes on a track with no stream url: once its analysis has FAILED, stop waiting. */
    @Test
    fun aFailedAnalysisEndsTheWaitAtOnceInsteadOfAfterTenMinutes() =
        runTest {
            val r = rig()
            r.decoder.unavailable += "B"
            r.engine.onQueueContext(DjQueueContext("A", "B"))
            advanceTimeBy(150_000) // the scheduler's three tries (5 s, 20 s, 80 s back-offs), then a second of the engine's poll
            assertEquals("analysis not available", r.engine.debug.value.reason)
            assertEquals(0, r.planner.calls)
        }

    @Test
    fun theVocalsPatchOfAStoredAnalysisIsWaitedForBeforePlanning() =
        runTest {
            // The device log of 2026-10-08: the next track's stored analysis predated the vocals, the patch was still decoding,
            // and the plan ran without them (no structure hand-off, no voice-over-voice check) and was never redone.
            val full = "dsp-1+beat-this"
            fun old(id: String) = fakeAnalysis(id, analyzerId = full).copy(beatDownbeatLogits = List(400) { 0f }, structureHopMs = 500, structureFrames = List(17) { 0f })
            val voices =
                object : org.simpmusic.dj.ml.VocalProvider {
                    override val id = "test-vocals"

                    override fun vocals(audio: PcmAudio) = org.simpmusic.dj.model.Confident(listOf(org.simpmusic.dj.model.TimeRange(10_000, 50_000)), 0.85f)
                }
            val r = rig(analyzerId = full, vocals = voices, stored = listOf(old("A"), old("B")), analysisDecodeMs = 10_000)
            r.engine.onQueueContext(DjQueueContext("A", "B"))
            advanceUntilIdle()
            val (from, to) = r.planner.inputs.single()
            assertNotNull(from!!.vocals)
            assertNotNull(to!!.vocals)
        }

    @Test
    fun aSimpleCrossfadePlanEndsInFallbackNotInPlanningSoItDoesNotLookBusyForever() =
        runTest {
            val r = rig(plan = { a, b -> fakePlan(a!!.videoId, b!!.videoId).copy(kind = PlanKind.SIMPLE_CROSSFADE, reason = "low confidence") })
            r.engine.onQueueContext(DjQueueContext("A", "B"))
            advanceUntilIdle()
            assertEquals("fallback", r.engine.debug.value.phase)
            assertFalse(r.engine.debug.value.isHeavy)
        }

    @Test
    fun disabledEngineDoesNothingAtAll() =
        runTest {
            val r = rig(enabled = false)
            r.engine.onQueueContext(DjQueueContext("A", "B"))
            advanceUntilIdle()
            assertEquals(0, r.planner.calls)
            assertTrue(r.decoder.ranges.isEmpty())
            assertFalse(r.engine.isEnabled)
        }

    @Test
    fun blockedContextSkipsTheWholePipeline() =
        runTest {
            val r = rig()
            r.engine.onQueueContext(DjQueueContext("A", "B", blockedReason = "casting"))
            advanceUntilIdle()
            assertEquals(0, r.planner.calls)
            assertEquals("blocked", r.engine.debug.value.phase)
            assertEquals("casting", r.engine.debug.value.reason)
            r.engine.onQueueContext(DjQueueContext("A", null))
            advanceUntilIdle()
            assertEquals(0, r.planner.calls)
        }

    @Test
    fun simpleCrossfadePlanNeverRenders() =
        runTest {
            val r = rig(plan = { a, b -> fakePlan(a!!.videoId, b!!.videoId).copy(kind = PlanKind.SIMPLE_CROSSFADE, reason = "low confidence") })
            r.engine.onQueueContext(DjQueueContext("A", "B"))
            advanceUntilIdle()
            assertEquals(1, r.planner.calls)
            assertTrue(r.renderer.requests.isEmpty())
            assertNull(r.engine.prepared("A", "B"))
            assertEquals(PlanKind.SIMPLE_CROSSFADE, r.engine.debug.value.kind)
            assertEquals("low confidence", r.engine.debug.value.reason)
        }

    @Test
    fun ineligiblePlanFallsBackWithAReason() =
        runTest {
            val r = rig(plan = { a, b -> fakePlan(a!!.videoId, b!!.videoId, inRate = 1.06f, rampBackMs = 0).let { p -> p.copy(incoming = p.incoming.copy(rate = org.simpmusic.dj.model.ParamCurve.constant(1.06f))) } })
            r.engine.onQueueContext(DjQueueContext("A", "B"))
            advanceUntilIdle()
            assertTrue(r.renderer.requests.isEmpty())
            assertEquals("fallback", r.engine.debug.value.phase)
            assertTrue(r.engine.debug.value.reason!!.contains("window not possible"))
        }

    @Test
    fun planThatRunsPastTheEndOfATrackFallsBack() =
        runTest {
            val r = rig(plan = { a, b -> fakePlan(a!!.videoId, b!!.videoId, exit = 239_000) }) // 240 s track, 16 s overlap
            r.engine.onQueueContext(DjQueueContext("A", "B"))
            advanceUntilIdle()
            assertTrue(r.renderer.requests.isEmpty())
            assertTrue(r.engine.debug.value.reason!!.contains("ends before the overlap"))
        }

    @Test
    fun rendererFailureIsContainedAndReported() =
        runTest {
            val r = rig()
            r.renderer.failWith = org.simpmusic.dj.android.render.RendererUnavailableException("not bound")
            r.engine.onQueueContext(DjQueueContext("A", "B"))
            advanceUntilIdle()
            assertNull(r.engine.prepared("A", "B"))
            assertEquals("error", r.engine.debug.value.phase)
            assertTrue(r.engine.debug.value.reason!!.contains("not bound"))
        }

    @Test
    fun changingThePairDiscardsTheOldWindow() =
        runTest {
            val r = rig()
            r.engine.onQueueContext(DjQueueContext("A", "B"))
            advanceUntilIdle()
            val first = r.engine.prepared("A", "B")!!
            r.engine.onQueueContext(DjQueueContext("B", "C"))
            assertFalse("old window file removed as soon as the queue moves on", first.window.file.exists())
            advanceUntilIdle()
            assertNull(r.engine.prepared("A", "B"))
            assertNotNull(r.engine.prepared("B", "C"))
        }

    @Test
    fun resetDropsEverythingAndLeavesNoFiles() =
        runTest {
            val r = rig()
            r.engine.onQueueContext(DjQueueContext("A", "B"))
            advanceUntilIdle()
            r.engine.reset("user seek")
            assertNull(r.engine.prepared("A", "B"))
            assertTrue(r.windowDir.listFiles().isNullOrEmpty())
        }

    @Test
    fun triggerWindowIsAroundTheWindowStart() {
        val plan = fakePlan()
        val tl = WindowTimelineHelper.build(plan)
        val p = PreparedTransition("A", "B", plan, tl, RenderedWindow(File("x"), 48_000, 1))
        val start = tl.outgoingSourceAtWindowStart
        assertFalse(p.isTriggeredAt((start - 500).toLong(), 120.0))
        assertTrue(p.isTriggeredAt((start - 100).toLong(), 120.0))
        assertTrue(p.isTriggeredAt(start.toLong() + 300, 120.0))
        assertFalse("too late: the lock budget no longer fits", p.isTriggeredAt(start.toLong() + 2000, 120.0))
        assertTrue(p.holdsCrossfadeAt((start - 20_000).toLong()))
        assertFalse(p.holdsCrossfadeAt(start.toLong() + 5000))
    }
}

/** Test-only shortcut so tests do not import the window package twice. */
internal object WindowTimelineHelper {
    fun build(plan: TransitionPlan) = org.simpmusic.dj.android.window.WindowTimeline.build(plan)
}
