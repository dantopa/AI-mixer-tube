package org.simpmusic.dj.android

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
import org.simpmusic.dj.android.log.DjLog
import org.simpmusic.dj.android.scheduler.AnalysisStatus
import org.simpmusic.dj.android.scheduler.DjAnalysisScheduler
import org.simpmusic.dj.android.mixview.DjMixViewBuilder
import org.simpmusic.dj.android.window.Eligibility
import org.simpmusic.dj.android.window.LatencyCalibrator
import org.simpmusic.dj.android.window.WindowTimeline
import org.simpmusic.dj.mixview.DjMixViewData
import org.simpmusic.dj.model.AnalysisPriority
import org.simpmusic.dj.model.DeckPlan
import org.simpmusic.dj.model.DjSettings
import org.simpmusic.dj.model.MixPoint
import org.simpmusic.dj.model.PlanConstraints
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
    /** How to splice this window into the two players' own audio; null = only the three-player path. */
    val splice: org.simpmusic.dj.android.splice.SpliceSchedule? = null,
    /** Our decode of the incoming track where its player will start (to measure that player's decode skew). */
    val incomingReference: org.simpmusic.dj.android.splice.ReferenceAudio? = null,
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
    /** What is happening to the analysis of the playing track / of the next track (null until the engine looked). */
    val currentAnalysis: AnalysisStatus? = null,
    val nextAnalysis: AnalysisStatus? = null,
    /** The last three analysis failures (`id: message`), so a screenshot of the settings line is enough to diagnose. */
    val recentErrors: List<String> = emptyList(),
    /** Outgoing source position at which the prepared mix becomes audible (plan exit point), when [phase] is ready. */
    val mixAtMs: Long? = null,
    /** While [phase] is "mixing": wall-clock time the window started and how long it runs until the overlap is over. */
    val mixStartedAtEpochMs: Long? = null,
    val mixDurationMs: Long? = null,
) {
    /** A DJ mix is audible right now (the adapter started the window). */
    val isMixing: Boolean get() = phase == "mixing"

    /** The engine is busy planning, decoding, rendering or mixing: background library analysis keeps out of the way. */
    val isHeavy: Boolean get() = phase == "planning" || phase == "decoding" || phase == "rendering" || phase == "mixing"

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

/** What a "mix now" request did. */
enum class MixNowResult {
    /** Re-planning for the next good phrase within the short span; the chip follows it as usual. */
    STARTED,
    /** AI DJ is off. */
    DISABLED,
    /** No next track to mix into (blocked, end of queue), or this pair was already played. */
    NO_PAIR,
    /** A mix is playing right now. */
    ALREADY_MIXING,
    /** The prepared mix starts within the same span anyway. */
    ALREADY_SOON,
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

    /** The adapter started the prepared window (the mix is now running). Main thread. */
    fun onMixStarted(prepared: PreparedTransition) {}

    /** Called on the main thread when a window finished rendering for the pair the player is waiting on. */
    fun setOnPrepared(listener: ((PreparedTransition) -> Unit)?)

    /** Playback position of the CURRENT track, from the adapter's 50 ms poll. The planner may not touch what is already gone. */
    fun onPosition(positionMs: Long) {}

    /** The user asked for the mix to happen as soon as possible (UI, main thread). See [MixNowResult]. */
    fun mixNow(): MixNowResult = MixNowResult.DISABLED

    /** Splice mode: one audio processor per player, first in its chain (null = none, the three-player path only). */
    fun createSplicer(): androidx.media3.common.audio.AudioProcessor? = null

    /** The adapter built [player] with [splicer] in its chain. */
    fun bindSplicer(player: androidx.media3.exoplayer.ExoPlayer, splicer: androidx.media3.common.audio.AudioProcessor) {}

    /** The players' splicers, for the transition runner. */
    val splicers: org.simpmusic.dj.android.splice.SplicerRegistry? get() = null

    /** Splice mode allowed by the user (the three-player path is used otherwise). */
    val spliceEnabled: Boolean get() = false

    val debug: StateFlow<DjDebugState>

    /** The picture of the mix being prepared / played (null = nothing to show). */
    val mixView: StateFlow<DjMixViewData?>
        get() = MutableStateFlow(null)

    fun log(message: String)

    /** Same, under a component [tag] (`adapter`, `xition`...) so the DJ log can be read per component. */
    fun log(tag: String, message: String) = log("[$tag] $message")
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
    private val logger: (String) -> Unit = { DjLog.d("hook", it) },
    /** How long to wait for both analyses before giving up on this pair. */
    private val analysisWaitMs: Long = 10 * 60_000L,
    /** Human title of a track for the mix picture; null = show the id. */
    private val titleOf: suspend (String) -> String? = { null },
    private val splicerRegistry: org.simpmusic.dj.android.splice.SplicerRegistry = org.simpmusic.dj.android.splice.SplicerRegistry(),
) : DjHooks {
    override fun createSplicer(): androidx.media3.common.audio.AudioProcessor = splicerRegistry.create()

    override fun bindSplicer(player: androidx.media3.exoplayer.ExoPlayer, splicer: androidx.media3.common.audio.AudioProcessor) =
        splicerRegistry.bind(player, splicer)

    override val splicers: org.simpmusic.dj.android.splice.SplicerRegistry get() = splicerRegistry

    override val spliceEnabled: Boolean get() = settingsFlow.value.preciseSplice

    private val _debug = MutableStateFlow(DjDebugState())
    override val debug: StateFlow<DjDebugState> = _debug.asStateFlow()

    private val _mixView = MutableStateFlow<DjMixViewData?>(null)
    override val mixView: StateFlow<DjMixViewData?> = _mixView.asStateFlow()
    private var mixTicker: Job? = null

    @Volatile
    private var listener: ((PreparedTransition) -> Unit)? = null

    override val isEnabled: Boolean get() = settingsFlow.value.enabled
    override val fallbackCrossfadeMs: Long get() = settingsFlow.value.fallbackCrossfadeMs

    private var pairKey: String? = null
    private var pairFrom: String? = null
    private var pairTo: String? = null
    /** The running pipeline serves a "mix now" request (cleared when the pair changes). */
    private var mixNowKey: String? = null
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

    @Volatile private var playbackPositionMs: Long = 0L

    override fun onPosition(positionMs: Long) {
        playbackPositionMs = positionMs
    }

    override fun setOnPrepared(listener: ((PreparedTransition) -> Unit)?) {
        this.listener = listener
    }

    override fun log(message: String) = logger(message)

    override fun log(tag: String, message: String) = DjLog.d(tag, message)

    override fun prepared(currentId: String, nextId: String): PreparedTransition? =
        ready?.takeIf { it.fromId == currentId && it.toId == nextId && key(currentId, nextId) != consumedKey }

    override fun consumed(prepared: PreparedTransition, outcome: String) {
        DjLog.i(TAG, "consumed ${prepared.fromId}->${prepared.toId}: $outcome")
        consumedKey = key(prepared.fromId, prepared.toId)
        if (ready === prepared) ready = null
        deleteWindow(prepared)
        stopMixView()
        _debug.update { it.copy(phase = "idle", lastOutcome = outcome, mixStartedAtEpochMs = null, mixDurationMs = null, mixAtMs = null) }
    }

    override fun onMixStarted(prepared: PreparedTransition) {
        val plan = prepared.plan
        val total = plan.overlapMs - prepared.timeline.startRelMs
        DjLog.i(TAG, "MIX STARTED ${prepared.fromId} -> ${prepared.toId} (${plan.kind}), overlap ${plan.overlapMs} ms, window runs ${total} ms until the overlap is over")
        startMixTicker(prepared)
        _debug.update { it.copy(phase = "mixing", kind = plan.kind, mixStartedAtEpochMs = System.currentTimeMillis(), mixDurationMs = total, mixAtMs = null) }
    }

    override fun reset(reason: String) {
        DjLog.i(TAG, "reset: $reason (pair=$pairKey ready=${ready?.let { it.fromId + "->" + it.toId }})")
        cancelPipeline()
        ready?.let { deleteWindow(it) }
        ready = null
        pairKey = null
        mixNowKey = null
        consumedKey = null
        stopMixView()
    }

    override fun mixNow(): MixNowResult {
        if (!settingsFlow.value.enabled) return MixNowResult.DISABLED
        val k = pairKey
        val from = pairFrom
        val to = pairTo
        if (k == null || from == null || to == null || k == consumedKey) {
            DjLog.i(TAG, "mix now: no pair to mix (pair=$k consumed=$consumedKey)")
            return MixNowResult.NO_PAIR
        }
        if (_debug.value.isMixing) return MixNowResult.ALREADY_MIXING
        val r = ready
        if (r != null) {
            val inMs = r.triggerSourceMs(calibrator.startLatencyMs) - playbackPositionMs
            if (inMs <= EARLIEST_EXIT_AHEAD_MS + MIX_NOW_SPAN_MS) {
                DjLog.i(TAG, "mix now: the prepared mix starts in ${inMs.toLong()} ms anyway, keeping it")
                return MixNowResult.ALREADY_SOON
            }
        }
        DjLog.i(TAG, "mix now: re-planning $from -> $to for the next good point (position $playbackPositionMs ms)")
        cancelPipeline()
        ready?.let { deleteWindow(it) }
        ready = null
        mixNowKey = k
        startPipeline(from, to)
        return MixNowResult.STARTED
    }

    private var lastLoggedContext: String? = null

    override fun onQueueContext(context: DjQueueContext) {
        val settings = settingsFlow.value
        val next = context.nextId
        val ctxLine = "current=${context.currentId} next=${context.nextId} blocked=${context.blockedReason} enabled=${settings.enabled}"
        if (ctxLine != lastLoggedContext) {
            lastLoggedContext = ctxLine
            DjLog.i(TAG, "queue context: $ctxLine")
        }
        if (!settings.enabled) {
            if (pairKey != null) reset("disabled")
            return
        }
        if (context.blockedReason != null || next == null) {
            if (pairKey != null) cancelPipeline()
            pairKey = null
            mixNowKey = null
            ready?.let { deleteWindow(it) }
            ready = null
            _mixView.value = null
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
        pairFrom = context.currentId
        pairTo = next
        mixNowKey = null
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
                    DjLog.e(TAG, "pipeline $fromId -> $toId FAILED", e)
                    _mixView.value = null
                    _debug.update { it.copy(phase = "error", fromId = fromId, toId = toId, kind = null, reason = "${e.javaClass.simpleName}: ${e.message}") }
                }
            }
    }

    private suspend fun run(fromId: String, toId: String, cancelFlag: AtomicBoolean) {
        val tRun = System.nanoTime()
        fun since() = (System.nanoTime() - tRun) / 1_000_000
        DjLog.i(TAG, "pipeline start $fromId -> $toId")
        _debug.update { DjDebugState(enabled = true, phase = "waiting-analysis", fromId = fromId, toId = toId, lastOutcome = it.lastOutcome) }
        if (mixTicker?.isActive != true) _mixView.value = DjMixViewData.analysing()
        val pair =
            coroutineScope {
                // While the analyses are pending, publish what is happening to each track once a second.
                val ticker =
                    launch {
                        var lastLine = ""
                        while (isActive) {
                            val cur = scheduler.statusOf(fromId)
                            val nxt = scheduler.statusOf(toId)
                            val errors = scheduler.state.value.recentErrors
                            _debug.update { it.copy(currentAnalysis = cur, nextAnalysis = nxt, recentErrors = errors) }
                            val line = "analysis wait ($fromId=$cur, $toId=$nxt)"
                            if (line != lastLine) {
                                lastLine = line
                                DjLog.i(TAG, "$line after ${since()} ms")
                            }
                            delay(1000)
                        }
                    }
                try {
                    withTimeoutOrNull(analysisWaitMs) {
                        combine(scheduler.observe(fromId), scheduler.observe(toId)) { a, b -> if (a != null && b != null) a to b else null }
                            .first { it != null }
                    }
                } finally {
                    ticker.cancel()
                }
            } ?: run {
                DjLog.w(TAG, "gave up waiting for analyses after ${since()} ms: $fromId=${scheduler.statusOf(fromId)} $toId=${scheduler.statusOf(toId)}")
                _mixView.value = null
                _debug.update { it.copy(phase = "waiting-analysis", reason = "analysis not available") }
                return
            }
        val (from, to) = pair!!
        DjLog.i(TAG, "both analyses ready after ${since()} ms")
        _debug.update { it.copy(phase = "planning", currentAnalysis = AnalysisStatus.Analysed, nextAnalysis = AnalysisStatus.Analysed) }
        val settings = settingsFlow.value
        // The window needs the outgoing audio decoded and rendered before it plays, so the mix cannot start sooner than that.
        val earliest = playbackPositionMs + EARLIEST_EXIT_AHEAD_MS
        val constraints = PlanConstraints(earliestExitMs = earliest)
        val plan =
            if (mixNowKey == key(fromId, toId)) {
                // "Mix now": anywhere in the track, nothing has to have played, and only the next MIX_NOW_SPAN_MS of exits count.
                val now = settings.copy(mixPoint = MixPoint.ANYWHERE, minPlayedFraction = 0f)
                val latest = earliest + MIX_NOW_SPAN_MS
                DjLog.i(TAG, "planning MIX NOW: exit between $earliest and $latest ms (position $playbackPositionMs ms)")
                val p = withContext(heavyDispatcher) { planner.plan(from, to, now, PlanConstraints(earliestExitMs = earliest, latestExitMs = latest)) }
                if (p.kind != PlanKind.SIMPLE_CROSSFADE && p.exitPointMs <= latest) {
                    p
                } else {
                    // nothing that mixes in the short span: keep the regular mix, and say why
                    DjLog.i(TAG, "mix now: no mixable point before $latest ms (${p.kind} exit ${p.exitPointMs}: ${p.reason}); keeping the regular plan")
                    mixNowKey = null
                    _debug.update { it.copy(lastOutcome = "mix now: no good point soon") }
                    withContext(heavyDispatcher) { planner.plan(from, to, settings, constraints) }
                }
            } else {
                DjLog.i(TAG, "planning with mixPoint=${settings.mixPoint}, earliest exit $earliest ms (position $playbackPositionMs ms)")
                withContext(heavyDispatcher) { planner.plan(from, to, settings, constraints) }
            }
        publishPlan(from, to, plan)
        if (plan.kind == PlanKind.SIMPLE_CROSSFADE) {
            DjLog.i(TAG, "plan is SIMPLE_CROSSFADE (${plan.reason}): the app's normal crossfade will do this transition")
            _mixView.value = null
            _debug.update { it.copy(phase = "fallback") }
            return
        }
        val eligibility = WindowTimeline.check(plan)
        if (eligibility is Eligibility.Rejected) {
            DjLog.w(TAG, "window not possible for ${plan.kind}: ${eligibility.reason} (${plan.reason}) -> normal crossfade")
            _mixView.value = null
            _debug.update { it.copy(phase = "fallback", kind = PlanKind.SIMPLE_CROSSFADE, reason = "window not possible: ${eligibility.reason} (${plan.reason})") }
            return
        }
        val timeline = WindowTimeline.build(plan)
        val bounds = checkBounds(timeline, from, to)
        if (bounds != null) {
            DjLog.w(TAG, "window bounds rejected: $bounds (${plan.reason}) -> normal crossfade")
            _mixView.value = null
            _debug.update { it.copy(phase = "fallback", kind = PlanKind.SIMPLE_CROSSFADE, reason = "$bounds (${plan.reason})") }
            return
        }
        val cancel = CancelSignal { cancelFlag.get() || !scope.isActive }

        _debug.update { it.copy(phase = "decoding") }
        val out = timeline.outgoingDecodeRange()
        val inn = timeline.incomingDecodeRange()
        DjLog.i(TAG, "decoding windows: outgoing $fromId src ${out.first}..${out.last} ms, incoming $toId src ${inn.first}..${inn.last} ms (window plan time ${timeline.startRelMs}..${timeline.endRelMs})")
        val tDec = System.nanoTime()
        val outgoingTail = decoder.decodeStereoRange(fromId, out.first, out.last, cancel)
        val incomingHead = decoder.decodeStereoRange(toId, inn.first, inn.last, cancel)
        DjLog.i(TAG, "both ranges decoded in ${(System.nanoTime() - tDec) / 1_000_000} ms (${outgoingTail.frames} + ${incomingHead.frames} frames)")
        if (cancelFlag.get()) {
            DjLog.i(TAG, "pipeline cancelled after decode")
            return
        }

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
            DjLog.i(TAG, "pipeline cancelled after render, deleting ${file.name}")
            file.delete()
            return
        }
        val spliceOutcome = org.simpmusic.dj.android.splice.SpliceSchedule.plan(timeline, from.durationMs)
        val splice = (spliceOutcome as? org.simpmusic.dj.android.splice.SpliceSchedule.Companion.Outcome.Ok)?.schedule
        val reference = splice?.let { referenceAround(incomingHead, it.incomingStartSourceMs - 300.0, 7000.0) }
        DjLog.i(
            TAG,
            if (splice != null) "splice plan: $splice, reference ${reference?.let { "%.0f..%.0f ms".format(it.startMs, it.endMs) } ?: "none"}"
            else "splice not possible: ${(spliceOutcome as org.simpmusic.dj.android.splice.SpliceSchedule.Companion.Outcome.Rejected).reason} (three-player path)",
        )
        val prepared = PreparedTransition(fromId, toId, plan, timeline, rendered, to.durationMs, splice, reference)
        ready = prepared
        val titles = (runCatching { titleOf(fromId) }.getOrNull() ?: fromId) to (runCatching { titleOf(toId) }.getOrNull() ?: toId)
        _mixView.value = runCatching { DjMixViewBuilder.build(plan, from, to, titles) }.onFailure { DjLog.w(TAG, "mix view could not be built: ${it.message}") }.getOrNull()
        _debug.update { it.copy(phase = "ready", mixAtMs = plan.exitPointMs) }
        DjLog.i(
            TAG,
            "window READY $fromId -> $toId in ${since()} ms since pipeline start: ${rendered.durationMs} ms of mix, mix audible at outgoing src ${plan.exitPointMs} ms, " +
                "window player must start at src ${"%.0f".format(prepared.triggerSourceMs(calibrator.startLatencyMs))} ms (${plan.kind}: ${plan.reason})",
        )
        listener?.invoke(prepared)
    }

    /** Mono copy of [pcm] over [fromMs, fromMs + lengthMs] (clipped to what was decoded), or null when too little is left. */
    private fun referenceAround(pcm: org.simpmusic.dj.android.render.StereoPcm, fromMs: Double, lengthMs: Double): org.simpmusic.dj.android.splice.ReferenceAudio? {
        val rate = pcm.sampleRate
        val first = ((fromMs - pcm.startMs) * rate / 1000.0).toLong().coerceIn(0L, pcm.frames.toLong())
        val last = ((fromMs + lengthMs - pcm.startMs) * rate / 1000.0).toLong().coerceIn(0L, pcm.frames.toLong())
        val n = (last - first).toInt()
        if (n < rate * 2) return null
        val out = FloatArray(n)
        val src = pcm.interleaved
        for (i in 0 until n) {
            val f = ((first + i) * 2).toInt()
            out[i] = (src[f] + src[f + 1]) * 0.5f
        }
        return org.simpmusic.dj.android.splice.ReferenceAudio(rate, pcm.startMs + first * 1000.0 / rate, out)
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

    private fun stopMixView() {
        mixTicker?.cancel()
        mixTicker = null
        _mixView.value = null
    }

    /** ~10 Hz playhead from the wall clock since the adapter started the window (a picture, not a control: a few 10 ms of drift do not matter). */
    private fun startMixTicker(prepared: PreparedTransition) {
        mixTicker?.cancel()
        val t0 = System.nanoTime()
        mixTicker =
            scope.launch {
                while (isActive) {
                    val windowMs = (System.nanoTime() - t0) / 1_000_000.0
                    if (windowMs > prepared.timeline.windowMs) break
                    _mixView.update { it?.withNow(prepared.timeline.planTimeOfWindow(windowMs)) }
                    delay(100)
                }
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

private const val TAG = "engine"

/** Time the pipeline needs (decode + render + player warm-up; measured 9.4 s end to end on a Pixel 10 Pro, 2026-09-30) between "now" and the earliest audible instant of a mix. */
private const val EARLIEST_EXIT_AHEAD_MS = 25_000L

/** "Mix now": how far past the earliest possible exit the planner may look for a good phrase start. */
private const val MIX_NOW_SPAN_MS = 20_000L

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
