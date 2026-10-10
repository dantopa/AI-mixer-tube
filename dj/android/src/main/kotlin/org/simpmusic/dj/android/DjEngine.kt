package org.simpmusic.dj.android

import org.simpmusic.dj.analysis.AnalysisRefiner
import org.simpmusic.dj.analysis.BarPhase

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
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
    /** The prepared / playing plan is a "Perfect" one (1 on 1 on trusted bars, see `PlanConstraints.perfect`). */
    val perfect: Boolean = false,
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

/** Where the playing track is in its bar: [beatInBar] 0 = the 1. [sure] = the bars are trusted; [anchored] = the owner set the 1. */
data class BarBeat(
    val beatInBar: Int,
    val beatsPerBar: Int,
    val sure: Boolean,
    val anchored: Boolean,
    /** Bar of the 16-bar block the beat is in (0..15); null without a phrase lattice. */
    val barInBlock: Int? = null,
    /** The 8-bar phrases / the 16-bar line are found or marked (not just a guess). */
    val phraseSure: Boolean = false,
    val blockSure: Boolean = false,
    /** The owner marked where a phrase starts on this track. */
    val phraseMarked: Boolean = false,
)

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
    /** "Perfect mix": the prepared mix already is a perfect one. */
    ALREADY_PERFECT,
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

    /**
     * "Perfect mix": re-plan for the best point within the next couple of minutes where BOTH 1s are trusted, phrase on
     * phrase first. The answer (planned, or why not) arrives in [debug] (`perfect`, `lastOutcome`). UI, main thread.
     */
    fun perfectMix(): MixNowResult = MixNowResult.DISABLED

    /**
     * "Tap the 1": the owner heard a 1 at [positionMs] of the playing track. Taps a few seconds apart form one session, and
     * the beat of the bar most of them land on wins, so one early or late tap does not decide. Returns the stored time.
     */
    fun tapTheOne(positionMs: Long): Long? = null

    /** "Shifted": the DJ's 1 is one beat early; move it to the next beat of the bar. Returns the new 1, or null. */
    fun shiftTheOne(positionMs: Long): Long? = null

    /** Forget the owner's 1 for the playing track (back to the vote). */
    fun clearTheOne() {}

    /** Which beat of the bar the playing track is on at [positionMs], for the 1-2-3-4 counter; null when unknown. */
    fun barBeatAt(positionMs: Long): BarBeat? = null

    /** "A phrase starts here": the owner heard a new phrase begin at [positionMs]; snapped to the nearest bar line. */
    fun markPhrase(positionMs: Long): Long? = null

    /** Forget the owner's phrase mark for the playing track (back to detection). */
    fun clearPhrase() {}

    /** One of the owner's mix marks for the playing track at [positionMs], snapped to the nearest bar line. */
    fun markMix(mark: org.simpmusic.dj.android.perfect.MixMark, positionMs: Long): Long? = null

    /** Forget one of the owner's mix marks for the playing track. */
    fun clearMix(mark: org.simpmusic.dj.android.perfect.MixMark) {}

    /** The owner's mix marks of the playing track, null when it has none. */
    fun mixMarks(): org.simpmusic.dj.planner.MixMarks? = null

    /** Playback position of the current track (ms), for countdowns. */
    val positionMs: Long get() = 0L

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
    /** The running pipeline serves a "perfect mix" request (cleared when the pair changes). */
    private var perfectKey: String? = null
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

    override val positionMs: Long get() = playbackPositionMs

    @Volatile private var currentRaw: TrackAnalysis? = null
    @Volatile private var currentRawAtMs = 0L
    @Volatile private var refreshingCurrent = false
    private var refinedFor: Triple<TrackAnalysis, Long?, Long?>? = null
    private var refined: TrackAnalysis? = null

    /** The playing track's grid as the planner sees it (repaired, bar phase decided, tapped 1 applied). Main thread. */
    private fun currentRefined(): TrackAnalysis? {
        val id = pairFrom ?: return null
        val raw = currentRaw
        val now = System.currentTimeMillis()
        if ((raw == null || raw.videoId != id || now - currentRawAtMs > CURRENT_REFRESH_MS) && !refreshingCurrent) {
            refreshingCurrent = true
            scope.launch {
                try {
                    scheduler.get(id)?.let { currentRaw = it }
                    currentRawAtMs = System.currentTimeMillis()
                } finally {
                    refreshingCurrent = false
                }
            }
        }
        if (raw == null || raw.videoId != id) return null
        val key = Triple(raw, BarPhase.anchors(id), org.simpmusic.dj.analysis.PhraseGrid.anchors(id))
        if (refinedFor?.first !== raw || refinedFor?.second != key.second || refinedFor?.third != key.third) {
            refined = AnalysisRefiner.cached(raw)
            refinedFor = key
        }
        return refined
    }

    override fun barBeatAt(positionMs: Long): BarBeat? {
        val r = currentRefined() ?: return null
        val beats = r.beatTimesMs?.value ?: return null
        val downs = r.downbeatBeatIndices?.value ?: return null
        val bi = lastAtOrBefore(beats, positionMs)
        if (bi < 0 || downs.isEmpty()) return null
        val k = downs.binarySearch(bi).let { if (it >= 0) it else -it - 2 }
        if (k < 0) return null
        val info = r.barPhase
        val ph = r.phrases
        return BarBeat(
            bi - downs[k],
            r.beatsPerBar ?: 4,
            sure = info?.trusted == true,
            anchored = info?.source == "anchored",
            barInBlock = ph?.barPositions?.getOrNull(k),
            phraseSure = ph?.phrasesTrusted == true,
            blockSure = ph?.blocksTrusted == true,
            phraseMarked = ph?.source == "anchored",
        )
    }

    override fun markPhrase(positionMs: Long): Long? {
        val id = pairFrom ?: return null
        val heard = (positionMs - org.simpmusic.dj.android.perfect.UserDownbeats.TAP_DELAY_MS).coerceAtLeast(0L)
        val r = currentRefined()
        val beats = r?.beatTimesMs?.value
        val downs = r?.downbeatBeatIndices?.value
        // the nearest bar line (a phrase starts on a 1); without bars yet the raw time is kept and snapped later
        val t = if (beats.isNullOrEmpty() || downs.isNullOrEmpty()) heard else downs.map { beats[it].toLong() }.minBy { kotlin.math.abs(it - heard) }
        org.simpmusic.dj.android.perfect.UserPhrases.set(id, t, "marked at $heard ms")
        _debug.update { it.copy(lastOutcome = "phrase marked: the 16 starts here") }
        return t
    }

    override fun clearPhrase() {
        val id = pairFrom ?: return
        org.simpmusic.dj.android.perfect.UserPhrases.clear(id)
        _debug.update { it.copy(lastOutcome = "phrases back to automatic") }
    }

    override fun markMix(mark: org.simpmusic.dj.android.perfect.MixMark, positionMs: Long): Long? {
        val id = pairFrom ?: return null
        val heard = (positionMs - org.simpmusic.dj.android.perfect.UserDownbeats.TAP_DELAY_MS).coerceAtLeast(0L)
        val r = currentRefined()
        val beats = r?.beatTimesMs?.value
        val downs = r?.downbeatBeatIndices?.value
        // a mix point sits on a bar line; without bars yet the raw time is kept (the planner snaps it again)
        val t = if (beats.isNullOrEmpty() || downs.isNullOrEmpty()) heard else downs.map { beats[it].toLong() }.minBy { kotlin.math.abs(it - heard) }
        org.simpmusic.dj.android.perfect.UserMixMarks.set(id, mark, t, "marked at $heard ms")
        _debug.update { it.copy(lastOutcome = "mix mark: ${mark.key} at %d:%02d".format(t / 60_000, t / 1000 % 60)) }
        // the exit of the PLAYING track changes the plan of the pair being prepared: plan it again (unless it is mixing)
        if (mark == org.simpmusic.dj.android.perfect.MixMark.EXIT) replanForMarks()
        return t
    }

    override fun clearMix(mark: org.simpmusic.dj.android.perfect.MixMark) {
        val id = pairFrom ?: return
        org.simpmusic.dj.android.perfect.UserMixMarks.clear(id, mark)
        _debug.update { it.copy(lastOutcome = "mix mark ${mark.key} forgotten") }
        if (mark == org.simpmusic.dj.android.perfect.MixMark.EXIT) replanForMarks()
    }

    override fun mixMarks(): org.simpmusic.dj.planner.MixMarks? = pairFrom?.let { org.simpmusic.dj.android.perfect.UserMixMarks.get(it) }

    private fun replanForMarks() {
        val from = pairFrom ?: return
        val to = pairTo ?: return
        if (_debug.value.isMixing || pairKey == consumedKey) return
        DjLog.i(TAG, "mix marks changed: re-planning $from -> $to")
        cancelPipeline()
        ready?.let { deleteWindow(it) }
        ready = null
        mixNowKey = null
        startPipeline(from, to)
    }

    /** Taps of the current session: (beat index, tap time). */
    private val tapSession = ArrayList<Pair<Int, Long>>()
    private var tapSessionTrack: String? = null
    private var lastTapAtMs = 0L

    override fun tapTheOne(positionMs: Long): Long? {
        val id = pairFrom ?: return null
        val r = currentRefined()
        val beats = r?.beatTimesMs?.value
        val heard = (positionMs - org.simpmusic.dj.android.perfect.UserDownbeats.TAP_DELAY_MS).coerceAtLeast(0L)
        if (beats.isNullOrEmpty()) {
            // no grid yet: keep the raw time, BarPhase snaps it when the analysis exists
            return org.simpmusic.dj.android.perfect.UserDownbeats.set(id, heard, "tap (no grid yet)")
        }
        val now = System.currentTimeMillis()
        if (tapSessionTrack != id || now - lastTapAtMs > TAP_SESSION_GAP_MS) tapSession.clear()
        tapSessionTrack = id
        lastTapAtMs = now
        val bi = nearest(beats, heard)
        tapSession += bi to heard
        val bpb = r.beatsPerBar ?: 4
        // the beat of the bar most taps agree on (ties: the latest tap's)
        val votes = tapSession.groupingBy { it.first % bpb }.eachCount()
        val best = votes.maxByOrNull { (ph, n) -> n * 1000 + if (ph == bi % bpb) 1 else 0 }!!.key
        val pick = tapSession.last { it.first % bpb == best }.first
        val t = beats[pick].toLong()
        DjLog.i(TAG, "tap the 1: tap ${tapSession.size} at $heard ms -> beat $bi (bar position ${bi % bpb}); votes $votes -> 1 at $t ms")
        org.simpmusic.dj.android.perfect.UserDownbeats.set(id, t, "tap ${tapSession.size}")
        _debug.update { it.copy(lastOutcome = "1 marked (${tapSession.size} tap${if (tapSession.size > 1) "s" else ""})") }
        return t
    }

    override fun shiftTheOne(positionMs: Long): Long? {
        val id = pairFrom ?: return null
        val r = currentRefined() ?: return null
        val beats = r.beatTimesMs?.value ?: return null
        val downs = r.downbeatBeatIndices?.value ?: return null
        val bi = lastAtOrBefore(beats, positionMs)
        if (bi < 0 || downs.isEmpty()) return null
        val k = downs.binarySearch(bi).let { if (it >= 0) it else -it - 2 }.coerceAtLeast(0)
        val next = downs[k] + 1
        if (next >= beats.size) return null
        val t = beats[next].toLong()
        org.simpmusic.dj.android.perfect.UserDownbeats.set(id, t, "shift +1 beat")
        _debug.update { it.copy(lastOutcome = "1 moved one beat later") }
        return t
    }

    override fun clearTheOne() {
        val id = pairFrom ?: return
        org.simpmusic.dj.android.perfect.UserDownbeats.clear(id)
        tapSession.clear()
        _debug.update { it.copy(lastOutcome = "1 back to automatic") }
    }

    private fun lastAtOrBefore(beats: List<Int>, t: Long): Int {
        var lo = 0
        var hi = beats.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (beats[mid] <= t) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    private fun nearest(beats: List<Int>, t: Long): Int {
        val i = lastAtOrBefore(beats, t)
        if (i < 0) return 0
        if (i + 1 < beats.size && beats[i + 1] - t < t - beats[i]) return i + 1
        return i
    }

    override fun perfectMix(): MixNowResult {
        if (!settingsFlow.value.enabled) return MixNowResult.DISABLED
        val k = pairKey
        val from = pairFrom
        val to = pairTo
        if (k == null || from == null || to == null || k == consumedKey) {
            DjLog.i(TAG, "perfect: no pair to mix (pair=$k consumed=$consumedKey)")
            return MixNowResult.NO_PAIR
        }
        if (_debug.value.isMixing) return MixNowResult.ALREADY_MIXING
        if (ready != null && _debug.value.perfect) return MixNowResult.ALREADY_PERFECT
        DjLog.i(TAG, "perfect: re-planning $from -> $to for a perfect point (position $playbackPositionMs ms)")
        cancelPipeline()
        ready?.let { deleteWindow(it) }
        ready = null
        mixNowKey = null
        perfectKey = k
        startPipeline(from, to)
        return MixNowResult.STARTED
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
        _debug.update { it.copy(phase = "idle", lastOutcome = outcome, mixStartedAtEpochMs = null, mixDurationMs = null, mixAtMs = null, perfect = false) }
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
        perfectKey = null
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
        perfectKey = null
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
            perfectKey = null
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
        perfectKey = null
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
                        // A track whose analysis FAILED (no stream url, decode error) will not be ready within the wait: stop
                        // waiting at once and let the app's own crossfade do this transition, instead of holding it 10 minutes.
                        val failed =
                            flow {
                                while (true) {
                                    if (scheduler.statusOf(fromId) is AnalysisStatus.Failed || scheduler.statusOf(toId) is AnalysisStatus.Failed) emit(null)
                                    delay(1000)
                                }
                            }
                        val ready = combine(scheduler.observe(fromId), scheduler.observe(toId)) { a, b -> if (a != null && b != null) a to b else null }.filterNotNull()
                        merge(ready, failed).first()
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
        var (from, to) = pair!!
        DjLog.i(TAG, "both analyses ready after ${since()} ms")
        // A stored analysis can be ready while its upgrade (vocals, structure frames, bar evidence) is still running: planning
        // now would plan without the voices, so no structure hand-off and no voice-over-voice check, and nothing re-plans
        // when the upgrade lands. It takes a decode and the vocal model, ~10-20 s; wait for it, bounded.
        if (scheduler.upgradePending(fromId) || scheduler.upgradePending(toId)) {
            val pending = listOf(fromId, toId).filter { scheduler.upgradePending(it) }
            DjLog.i(TAG, "waiting for the analysis upgrade of $pending before planning")
            _debug.update { it.copy(lastOutcome = "analysing the voices first…") }
            val done =
                withTimeoutOrNull(UPGRADE_WAIT_MS) {
                    while (scheduler.upgradePending(fromId) || scheduler.upgradePending(toId)) delay(500)
                    true
                }
            scheduler.get(fromId)?.let { from = it }
            scheduler.get(toId)?.let { to = it }
            DjLog.i(TAG, if (done == true) "upgrade done after ${since()} ms: vocals out=${from.vocals != null} in=${to.vocals != null}" else "upgrade still running after ${since()} ms; planning with what is stored")
        }
        currentRaw = from
        currentRawAtMs = System.currentTimeMillis()
        if (perfectKey == key(fromId, toId) && (from.beatDownbeatLogits == null || to.beatDownbeatLogits == null)) {
            // "Perfect" needs the bar-phase evidence, and the re-analysis that adds it is already queued (NOW_PLAYING / NEXT_UP)
            DjLog.i(TAG, "perfect: waiting for the bar-phase evidence (re-analysis) of ${listOfNotNull(fromId.takeIf { from.beatDownbeatLogits == null }, toId.takeIf { to.beatDownbeatLogits == null })}")
            _debug.update { it.copy(lastOutcome = "perfect: analysing the bars first…") }
            val upgraded =
                withTimeoutOrNull(PERFECT_EVIDENCE_WAIT_MS) {
                    combine(scheduler.observe(fromId), scheduler.observe(toId)) { a, b -> if (a?.beatDownbeatLogits != null && b?.beatDownbeatLogits != null) a to b else null }
                        .first { it != null }
                }
            if (upgraded != null) {
                from = upgraded.first
                to = upgraded.second
                currentRaw = from
                DjLog.i(TAG, "perfect: evidence ready after ${since()} ms")
            }
        }
        _debug.update { it.copy(phase = "planning", currentAnalysis = AnalysisStatus.Analysed, nextAnalysis = AnalysisStatus.Analysed) }
        val settings = settingsFlow.value
        // The window needs the outgoing audio decoded and rendered before it plays, so the mix cannot start sooner than that.
        val earliest = playbackPositionMs + EARLIEST_EXIT_AHEAD_MS
        val constraints = PlanConstraints(earliestExitMs = earliest)
        val plan =
            if (perfectKey == key(fromId, toId)) {
                val latest = earliest + PERFECT_SPAN_MS
                DjLog.i(TAG, "planning PERFECT: exit between $earliest and $latest ms (position $playbackPositionMs ms); ${AnalysisRefiner.cached(from).let { BarPhase.describe(it) + "; " + org.simpmusic.dj.analysis.PhraseGrid.describe(it) }} | ${AnalysisRefiner.cached(to).let { BarPhase.describe(it) + "; " + org.simpmusic.dj.analysis.PhraseGrid.describe(it) }}")
                val p = withContext(heavyDispatcher) { planner.plan(from, to, settings, PlanConstraints(earliestExitMs = earliest, latestExitMs = latest, perfect = true)) }
                if (p.kind == PlanKind.BEAT_MATCHED && p.reason.startsWith(PlanConstraints.PERFECT_OK)) {
                    DjLog.i(TAG, "perfect: ${p.reason}")
                    p
                } else {
                    DjLog.i(TAG, "perfect: refused (${p.reason}); keeping the regular plan")
                    perfectKey = null
                    _debug.update { it.copy(lastOutcome = p.reason.removePrefix(PlanConstraints.PERFECT_REFUSED).let { r -> "no perfect point: " + r.take(140) }) }
                    withContext(heavyDispatcher) { planner.plan(from, to, settings, constraints) }
                }
            } else if (mixNowKey == key(fromId, toId)) {
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
                // the default: a perfect mix (1 on 1, the 16 on the 16 where the phrases are known) when the pair qualifies,
                // at the regular timing; the regular plan otherwise (the planner appends why perfect was not possible)
                DjLog.i(TAG, "planning with mixPoint=${settings.mixPoint}, perfect first, earliest exit $earliest ms (position $playbackPositionMs ms)")
                withContext(heavyDispatcher) { planner.plan(from, to, settings, constraints.copy(preferPerfect = true)) }
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
        val spliceOutcome = org.simpmusic.dj.android.splice.SpliceSchedule.plan(timeline, from.durationMs)
        val splice = (spliceOutcome as? org.simpmusic.dj.android.splice.SpliceSchedule.Companion.Outcome.Ok)?.schedule
        val inn = incomingDecodeRange(timeline, splice)
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

    /**
     * The incoming track's decode range: what the renderer needs, widened so it also covers where the spliced incoming
     * deck starts silently (its reference audio must come from the SAME decode the window was rendered from, or the join
     * inherits that decode's own offset). A range starting in the first [DECODE_FROM_ZERO_BELOW_MS] decodes from 0
     * instead: a decode from the start needs no extractor seek, whose landing is the one thing not exact here.
     */
    private fun incomingDecodeRange(timeline: org.simpmusic.dj.android.window.WindowTimeline, splice: org.simpmusic.dj.android.splice.SpliceSchedule?): LongRange {
        val r = timeline.incomingDecodeRange()
        var first = r.first
        if (splice != null) first = minOf(first, (splice.incomingStartSourceMs - REFERENCE_LEAD_MS).toLong())
        if (first < DECODE_FROM_ZERO_BELOW_MS) first = 0L
        return first.coerceAtLeast(0L)..r.last
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
                perfect = plan.reason.startsWith(PlanConstraints.PERFECT_OK),
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

/** "Perfect mix" looks this far past the earliest possible exit for a point where both 1s are trusted. */
private const val PERFECT_SPAN_MS = 120_000L

/** How long the plan waits for a running upgrade (vocals / frames / bar evidence) of a stored analysis. */
private const val UPGRADE_WAIT_MS = 45_000L

/** How long a "Perfect" request waits for the re-analysis that adds the bar-phase evidence. */
private const val PERFECT_EVIDENCE_WAIT_MS = 150_000L

/** The playing track's analysis is re-read this often for the 1-2-3-4 counter (a re-analysis may have replaced it). */
private const val CURRENT_REFRESH_MS = 20_000L

/** Taps further apart than this start a new "tap the 1" session. */
private const val TAP_SESSION_GAP_MS = 12_000L

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

/** The spliced incoming deck's reference starts 300 ms before its start; decode a little more than that. */
private const val REFERENCE_LEAD_MS = 1000.0

/** An incoming decode range starting before this decodes from 0 (no extractor seek): a few seconds at 10x+ real time. */
private const val DECODE_FROM_ZERO_BELOW_MS = 30_000L
