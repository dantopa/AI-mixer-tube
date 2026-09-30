package org.simpmusic.dj.android

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.simpmusic.dj.android.decode.CancelSignal
import org.simpmusic.dj.android.decode.TrackDecoder
import org.simpmusic.dj.android.render.RenderRequest
import org.simpmusic.dj.android.render.RenderedWindow
import org.simpmusic.dj.android.render.TransitionWindowRenderer
import org.simpmusic.dj.android.scheduler.DjAnalysisScheduler
import org.simpmusic.dj.android.window.Eligibility
import org.simpmusic.dj.android.window.LatencyCalibrator
import org.simpmusic.dj.android.window.WindowTimeline
import org.simpmusic.dj.model.AnalysisPriority
import org.simpmusic.dj.model.DeckPlan
import org.simpmusic.dj.model.DjSettings
import org.simpmusic.dj.model.PlanKind
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.model.TransitionPlan
import org.simpmusic.dj.model.TransitionPlanner
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** The two tracks the player is about to mix, plus why DJ mode must sit this one out (null = allowed). */
data class DjQueueContext(
    val currentId: String,
    val nextId: String?,
    /** "video", "repeat one", "casting", "listen together", "playback speed", ... or null. */
    val blockedReason: String? = null,
)

/** A plan that has been decoded, rendered to a WAV and is waiting for the outgoing position to reach its trigger. */
class PreparedTransition(
    val fromId: String,
    val toId: String,
    val plan: TransitionPlan,
    val timeline: WindowTimeline,
    val window: RenderedWindow,
    /** Duration of the incoming track (analysis), for the session's duration while the window is audible. */
    val toDurationMs: Long = 0L,
) {
    /** Outgoing source position at which the window player must start playing (silently) so it is running at window sample 0. */
    fun triggerSourceMs(startLatencyMs: Double): Double = timeline.outgoingSourceAtWindowStart - startLatencyMs

    /** After this outgoing position the chance is gone (the lock-out budget no longer fits before the mix). */
    fun lastStartSourceMs(): Double = timeline.outgoingSourceAtWindowStart + LATE_START_SLACK_MS

    fun isTriggeredAt(outgoingPositionMs: Long, startLatencyMs: Double): Boolean =
        outgoingPositionMs >= triggerSourceMs(startLatencyMs) && outgoingPositionMs <= lastStartSourceMs()

    /** True while a normal crossfade should be held back: the DJ window is ready and its moment has not passed. */
    fun holdsCrossfadeAt(outgoingPositionMs: Long): Boolean = outgoingPositionMs <= lastStartSourceMs()

    private companion object {
        const val LATE_START_SLACK_MS = 600.0
    }
}

/** What the "why" debug line in Settings shows. */
data class DjDebugState(
    val enabled: Boolean = false,
    /** Short machine phase: idle, waiting-analysis, planning, decoding, rendering, ready, mixing, fallback, blocked, error. */
    val phase: String = "idle",
    val fromId: String? = null,
    val toId: String? = null,
    val kind: PlanKind? = null,
    val reason: String? = null,
    val mixBpm: Float? = null,
    val fromBpm: Float? = null,
    val toBpm: Float? = null,
    val fromKey: String? = null,
    val toKey: String? = null,
    val confidence: Float? = null,
    /** Result of the last executed (or attempted) transition, e.g. "hand-offs 2.1 / 3.4 ms" or "lock failed". */
    val lastOutcome: String? = null,
) {
    /** One human line, e.g. "BEAT_MATCHED 124.0 BPM 8A -> 9A (conf 0.82): matched +3.1% ...". */
    fun summary(): String =
        buildString {
            append(kind?.name ?: phase)
            mixBpm?.let { append(" · ${"%.1f".format(it)} BPM") }
            if (fromKey != null && toKey != null) append(" · $fromKey → $toKey")
            confidence?.let { append(" · conf ${"%.2f".format(it)}") }
            reason?.let { append(" — $it") }
            lastOutcome?.let { append(" [$it]") }
        }
}

/**
 * Everything the player adapter sees of the DJ. Narrow on purpose: the adapter patch stays tiny and every
 * decision (analysis, planning, rendering, thresholds) lives here.
 */
interface DjHooks {
    /** Cheap volatile read: when false the adapter must behave EXACTLY as before. */
    val isEnabled: Boolean

    /** Fallback crossfade length while DJ mode is on (mirrors the app's crossfade duration). */
    val fallbackCrossfadeMs: Long

    /** Latency estimates shared across transitions (self-calibrating). */
    val calibrator: LatencyCalibrator

    /** Called on the main thread whenever the (current, next) pair or its eligibility changes. Cheap, idempotent. */
    fun onQueueContext(context: DjQueueContext)

    /** The prepared transition for exactly this pair, or null. Main thread. */
    fun prepared(currentId: String, nextId: String): PreparedTransition?

    /** The pair was executed / attempted: never try it again for this playback of [currentId]. */
    fun consumed(prepared: PreparedTransition, outcome: String)

    /** Drops preparation state (queue cleared, seek, release...). */
    fun reset(reason: String)

    /** Called on the main thread when a window finished rendering for the pair the player is waiting on. */
    fun setOnPrepared(listener: ((PreparedTransition) -> Unit)?)

    val debug: StateFlow<DjDebugState>

    fun log(message: String)
}

/**
 * Orchestrates: analyses -> plan -> decode the two source ranges -> render the window WAV -> notify the adapter.
 * All heavy work happens off the main thread; [onQueueContext] itself only bookkeeps.
 */
class DjEngine(
    private val scope: CoroutineScope,
    private val scheduler: DjAnalysisScheduler,
    private val planner: TransitionPlanner,
    private val renderer: TransitionWindowRenderer,
    private val decoder: TrackDecoder,
    private val settingsFlow: StateFlow<DjSettings>,
    private val heavyDispatcher: CoroutineDispatcher,
    private val windowDir: File,
    override val calibrator: LatencyCalibrator = LatencyCalibrator(),
    private val logger: (String) -> Unit = {},
    /** How long to wait for both analyses before giving up on this pair. */
    private val analysisWaitMs: Long = 10 * 60_000L,
) : DjHooks {
    private val _debug = MutableStateFlow(DjDebugState())
    override val debug: StateFlow<DjDebugState> = _debug.asStateFlow()

    @Volatile
    private var listener: ((PreparedTransition) -> Unit)? = null

    override val isEnabled: Boolean get() = settingsFlow.value.enabled
    override val fallbackCrossfadeMs: Long get() = settingsFlow.value.fallbackCrossfadeMs

    private var pairKey: String? = null
    private var pipeline: Job? = null
    private var pipelineCancel: AtomicBoolean? = null
    private var ready: PreparedTransition? = null
    /** The pair that was just executed or attempted: not retried while the player still sits on it. Cleared when another pair starts. */
    private var consumedKey: String? = null

    init {
        windowDir.mkdirs()
        windowDir.listFiles()?.forEach { it.delete() } // windows never survive a process
        scope.launch { settingsFlow.collect { s -> _debug.update { it.copy(enabled = s.enabled) } } }
    }

    override fun setOnPrepared(listener: ((PreparedTransition) -> Unit)?) {
        this.listener = listener
    }

    override fun log(message: String) = logger(message)

    override fun prepared(currentId: String, nextId: String): PreparedTransition? =
        ready?.takeIf { it.fromId == currentId && it.toId == nextId && key(currentId, nextId) != consumedKey }

    override fun consumed(prepared: PreparedTransition, outcome: String) {
        consumedKey = key(prepared.fromId, prepared.toId)
        if (ready === prepared) ready = null
        deleteWindow(prepared)
        _debug.update { it.copy(phase = "idle", lastOutcome = outcome) }
    }

    override fun reset(reason: String) {
        logger("dj reset: $reason")
        cancelPipeline()
        ready?.let { deleteWindow(it) }
        ready = null
        pairKey = null
        consumedKey = null
    }

    override fun onQueueContext(context: DjQueueContext) {
        val settings = settingsFlow.value
        val next = context.nextId
        if (!settings.enabled) {
            if (pairKey != null) reset("disabled")
            return
        }
        if (context.blockedReason != null || next == null) {
            if (pairKey != null) cancelPipeline()
            pairKey = null
            ready?.let { deleteWindow(it) }
            ready = null
            _debug.update { it.copy(phase = "blocked", reason = context.blockedReason ?: "no next track", kind = null) }
            return
        }
        // Analyses: the current track matters most, the next one is what the transition is about.
        scope.launch {
            scheduler.cancelStale(context.currentId, next)
            scheduler.request(context.currentId, AnalysisPriority.NOW_PLAYING)
            scheduler.request(next, AnalysisPriority.NEXT_UP)
        }
        val k = key(context.currentId, next)
        if (k == pairKey || k == consumedKey) return
        consumedKey = null
        cancelPipeline()
        ready?.let { deleteWindow(it) }
        ready = null
        pairKey = k
        startPipeline(context.currentId, next)
    }

    private fun startPipeline(fromId: String, toId: String) {
        val cancelFlag = AtomicBoolean(false)
        pipelineCancel = cancelFlag
        pipeline =
            scope.launch {
                try {
                    run(fromId, toId, cancelFlag)
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logger("dj pipeline $fromId -> $toId failed: $e")
                    _debug.update { it.copy(phase = "error", fromId = fromId, toId = toId, kind = null, reason = "${e.javaClass.simpleName}: ${e.message}") }
                }
            }
    }

    private suspend fun run(fromId: String, toId: String, cancelFlag: AtomicBoolean) {
        _debug.update { DjDebugState(enabled = true, phase = "waiting-analysis", fromId = fromId, toId = toId, lastOutcome = it.lastOutcome) }
        val pair =
            withTimeoutOrNull(analysisWaitMs) {
                combine(scheduler.observe(fromId), scheduler.observe(toId)) { a, b -> if (a != null && b != null) a to b else null }
                    .first { it != null }
            } ?: run {
                _debug.update { it.copy(phase = "waiting-analysis", reason = "analysis not available") }
                return
            }
        val (from, to) = pair!!
        _debug.update { it.copy(phase = "planning") }
        val settings = settingsFlow.value
        val plan = withContext(heavyDispatcher) { planner.plan(from, to, settings) }
        publishPlan(from, to, plan)
        if (plan.kind == PlanKind.SIMPLE_CROSSFADE) return
        val eligibility = WindowTimeline.check(plan)
        if (eligibility is Eligibility.Rejected) {
            _debug.update { it.copy(phase = "fallback", kind = PlanKind.SIMPLE_CROSSFADE, reason = "window not possible: ${eligibility.reason} (${plan.reason})") }
            return
        }
        val timeline = WindowTimeline.build(plan)
        val bounds = checkBounds(timeline, from, to)
        if (bounds != null) {
            _debug.update { it.copy(phase = "fallback", kind = PlanKind.SIMPLE_CROSSFADE, reason = "$bounds (${plan.reason})") }
            return
        }
        val cancel = CancelSignal { cancelFlag.get() || !scope.isActive }

        _debug.update { it.copy(phase = "decoding") }
        val out = timeline.outgoingDecodeRange()
        val inn = timeline.incomingDecodeRange()
        val outgoingTail = decoder.decodeStereoRange(fromId, out.first, out.last, cancel)
        val incomingHead = decoder.decodeStereoRange(toId, inn.first, inn.last, cancel)
        if (cancelFlag.get()) return

        _debug.update { it.copy(phase = "rendering") }
        val file = File(windowDir, "${fromId}_$toId.wav")
        val rendered =
            withContext(heavyDispatcher) {
                renderer.render(
                    RenderRequest(
                        plan = plan,
                        startRelMs = timeline.startRelMs,
                        endRelMs = timeline.endRelMs,
                        outgoingTail = outgoingTail,
                        incomingHead = incomingHead,
                        sampleRate = outgoingTail.sampleRate,
                        output = file,
                    ),
                )
            }
        if (cancelFlag.get()) {
            file.delete()
            return
        }
        val prepared = PreparedTransition(fromId, toId, plan, timeline, rendered, to.durationMs)
        ready = prepared
        _debug.update { it.copy(phase = "ready") }
        logger("dj window ready $fromId -> $toId (${rendered.durationMs} ms, ${plan.reason})")
        listener?.invoke(prepared)
    }

    private fun checkBounds(timeline: WindowTimeline, from: TrackAnalysis, to: TrackAnalysis): String? {
        val plan = timeline.plan
        val outEnd = timeline.outgoingSourceAt(plan.overlapMs.toDouble())
        if (outEnd > from.durationMs + 300) return "outgoing track ends before the overlap does"
        if (timeline.outgoingSourceAtWindowStart < 2000) return "window would start at the very beginning of the outgoing track"
        if (plan.entryPointMs >= to.durationMs) return "entry point beyond the incoming track"
        if (timeline.incomingSourceAt(timeline.endRelMs.toDouble()) > to.durationMs + 300) return "incoming track too short for the window"
        return null
    }

    private fun publishPlan(from: TrackAnalysis, to: TrackAnalysis, plan: TransitionPlan) {
        _debug.update {
            it.copy(
                kind = plan.kind,
                reason = plan.reason,
                mixBpm = plan.mixBpm,
                fromBpm = from.bpm?.value,
                toBpm = to.bpm?.value,
                fromKey = from.key?.value?.camelot(),
                toKey = to.key?.value?.camelot(),
                confidence = plan.confidence,
            )
        }
    }

    private fun cancelPipeline() {
        pipelineCancel?.set(true)
        pipeline?.cancel()
        pipeline = null
        pipelineCancel = null
    }

    private fun deleteWindow(p: PreparedTransition) {
        try {
            p.window.file.delete()
        } catch (_: Exception) {
        }
    }

    private fun key(a: String, b: String) = "$a>$b"
}

/** Stand-in planner until the real one is bound: always answers "plain crossfade" so DJ mode degrades to the old path. */
class FallbackOnlyPlanner : TransitionPlanner {
    override fun plan(from: TrackAnalysis?, to: TrackAnalysis?, settings: DjSettings): TransitionPlan =
        TransitionPlan(
            fromId = from?.videoId ?: "?",
            toId = to?.videoId ?: "?",
            kind = PlanKind.SIMPLE_CROSSFADE,
            exitPointMs = ((from?.durationMs ?: 0L) - settings.fallbackCrossfadeMs).coerceAtLeast(0L),
            entryPointMs = 0L,
            overlapMs = settings.fallbackCrossfadeMs,
            outgoing = DeckPlan(),
            incoming = DeckPlan(),
            confidence = 0f,
            reason = "no TransitionPlanner installed",
        )
}
