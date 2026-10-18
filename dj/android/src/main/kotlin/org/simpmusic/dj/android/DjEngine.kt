package org.simpmusic.dj.android

import org.simpmusic.dj.analysis.AnalysisRefiner
import org.simpmusic.dj.analysis.BarPhase
import org.simpmusic.dj.timeline.TrackTimeline
import org.simpmusic.dj.timeline.Timelines

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
    /** Played live by the real-time engine (no rendered window: [window] is a placeholder). */
    val viaBus: Boolean = false,
) {
    /**
     * Outgoing source position at which the window player must start playing (silently) so it is running at window sample 0.
     * On the real-time engine: when the incoming player is cued ([BUS_CUE_LEAD_MS] before the plan's first frame).
     */
    fun triggerSourceMs(startLatencyMs: Double): Double =
        if (viaBus) busStartSourceMs() - BUS_CUE_LEAD_MS else timeline.outgoingSourceAtWindowStart - startLatencyMs

    /** After this outgoing position the chance is gone (the lock-out budget no longer fits before the mix). */
    fun lastStartSourceMs(): Double =
        if (viaBus) busStartSourceMs() - BUS_LAST_CUE_MS else timeline.outgoingSourceAtWindowStart + LATE_START_SLACK_MS

    /** The outgoing source position where the bus leaves the copy path (T0 minus the pre-roll, at the plan's rates; a bend <= 10 %). */
    fun busStartSourceMs(): Double = plan.exitPointMs - (-plan.preRollMs).coerceAtLeast(0L) * 1.12

    fun isTriggeredAt(outgoingPositionMs: Long, startLatencyMs: Double): Boolean =
        outgoingPositionMs >= triggerSourceMs(startLatencyMs) && outgoingPositionMs <= lastStartSourceMs()

    /** True while a normal crossfade should be held back: the DJ window is ready and its moment has not passed. */
    fun holdsCrossfadeAt(outgoingPositionMs: Long): Boolean = outgoingPositionMs <= lastStartSourceMs()

    private companion object {
        const val LATE_START_SLACK_MS = 600.0

        /** The incoming player is started this long before the bus needs its entry frame (prepare, seek, fill its ring). */
        const val BUS_CUE_LEAD_MS = 5_000.0

        /** A cue later than this before the bus start would arrive too late (the incoming ring would not be filled). */
        const val BUS_LAST_CUE_MS = 1_500.0
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
    /** "AI Mix": what Claude is doing or said ("asking Haiku 5.5…", its choice, or why there is none), and when. */
    val aiStatus: String? = null,
    val aiStatusAtEpochMs: Long? = null,
    val aiAsking: Boolean = false,
    /** The prepared / playing plan is Claude's ([PlanSource.AI_MIX]). */
    val aiPlan: Boolean = false,
    /** How many of the upcoming mixes (after the current one) Claude has already solved, see `DjEngine.aiPlanAhead`. */
    val aiAhead: Int = 0,
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

/** What an "AI Mix" tap did; Claude's answer (or why there is none) arrives later in `DjDebugState.aiStatus`. */
enum class AiMixResult {
    /** Asking Claude now. */
    ASKING,
    /** AI DJ is off. */
    DISABLED,
    /** No Claude API key in Settings -> AI DJ. */
    NO_KEY,
    /** No next track to mix into, or this pair was already played. */
    NO_PAIR,
    /** A mix is playing right now. */
    ALREADY_MIXING,
    /** Still waiting for the previous answer. */
    BUSY,
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

    /**
     * The owner's thumbs up / down on the last mix that ended (written to the mix journal). False when there is none to
     * rate: no mix yet, or it ended more than `MixJournal.RATEABLE_MS` ago. UI, main thread.
     */
    fun rateLastMix(up: Boolean): Boolean = false

    /** The last mix that ended (with its rating, if any), for the rating prompt; null when none. */
    fun lastMix(): org.simpmusic.dj.android.journal.LastMix? = null

    /** Called on the main thread when a window finished rendering for the pair the player is waiting on. */
    fun setOnPrepared(listener: ((PreparedTransition) -> Unit)?)

    /** Playback position of the CURRENT track, from the adapter's 50 ms poll. The planner may not touch what is already gone. */
    fun onPosition(positionMs: Long) {}

    /** The user asked for the mix to happen as soon as possible (UI, main thread). See [MixNowResult]. */
    fun mixNow(): MixNowResult = MixNowResult.DISABLED

    /**
     * "AI Mix": ask Claude where to mix the pair (a per-bar table of both tracks and their lyrics, no audio) and re-plan on
     * its points. UI, main thread; the answer arrives in [debug] (`aiStatus`).
     */
    fun aiMix(): AiMixResult = AiMixResult.DISABLED

    /**
     * Like [aiMix], but an answer Claude already gave for this pair (`AiAnswerCache`, not disliked) is reused instead of
     * paying for a new one: what turning "Auto" on does. [aiMix] itself always asks again (the owner's "recalculate").
     */
    fun aiMixRemembered(): AiMixResult = aiMix()

    /**
     * "Order with AI": Claude puts the radio's next tracks in the order that mixes best (every join a natural continuation).
     * The answer arrives in [debug] (`aiStatus`); the queue is reordered by itself. UI, main thread.
     */
    fun orderWithAi(): AiMixResult = AiMixResult.DISABLED

    /**
     * "Learn from my ratings": one question over every rated mix; Claude writes the owner's rules ([OwnerLessons]), which
     * every later mix question carries in its (cached) system prompt. The result arrives in [debug] (`aiStatus`).
     */
    fun learnFromRatings(): AiMixResult = AiMixResult.DISABLED

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

    /** True when the playing track carries any mark of the owner (Out / In / Solo, the 1, a phrase). */
    fun hasTrackMarks(): Boolean = false

    /**
     * Forget every mark of the playing track (Out / In / Solo, the 1, the phrase): the DJ (and Claude) find the points
     * from the analysis again, and the pair being prepared is planned again. True when there was something to forget.
     */
    fun forgetTrackMarks(): Boolean = false

    /** How many tracks carry any mark of the owner, for Settings. */
    fun markedTrackCount(): Int = 0

    /** Forget every mark of every track (the 👍 / 👎 ratings stay); returns how many tracks had one. */
    fun forgetAllMarks(): Int = 0

    /** Playback position of the current track (ms), for countdowns. */
    val positionMs: Long get() = 0L

    /** Splice mode: one audio processor per player, first in its chain (null = none, the three-player path only). */
    fun createSplicer(): androidx.media3.common.audio.AudioProcessor? = null

    /** The adapter built [player] with [splicer] in its chain. */
    fun bindSplicer(player: androidx.media3.exoplayer.ExoPlayer, splicer: androidx.media3.common.audio.AudioProcessor) {}

    /**
     * The real-time engine: the output a new player writes into (its deck on the one mixer), or null when the player keeps
     * its own AudioTrack (engine off, DJ off). Asked once per player, when the adapter builds it.
     */
    fun createAudioOutputProvider(): androidx.media3.exoplayer.audio.AudioOutputProvider? = null

    /** The rate a player on the real-time engine must deliver (its Sonic processor resamples to it). */
    val busSampleRate: Int get() = org.simpmusic.dj.android.bus.AndroidMixBus.SAMPLE_RATE

    /**
     * The audio processor that must sit first in the chain of a player built on [provider]: it tells the deck where each
     * stream starts in the song (Media3 gives the output renderer time only). Null = none needed.
     */
    fun busStreamTap(provider: androidx.media3.exoplayer.audio.AudioOutputProvider): androidx.media3.common.audio.AudioProcessor? = null

    /** The adapter built [player] on [provider] (from [createAudioOutputProvider]). */
    fun bindBusPlayer(player: androidx.media3.exoplayer.ExoPlayer, provider: androidx.media3.exoplayer.audio.AudioOutputProvider) {}

    /** The real-time engine's mixer, for the transition runner; null = not in use. */
    val mixBus: org.simpmusic.dj.android.bus.AndroidMixBus? get() = null

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
    /** Where the voice is from the songs' synced lyrics (fetched for the playing and the next track); null = detector only. */
    private val lyrics: org.simpmusic.dj.android.lyrics.DjLyricsSource? = null,
    /** The real-time engine's mixer (one output for every player); null = the window + splice path only. */
    private val bus: org.simpmusic.dj.android.bus.AndroidMixBus? = null,
    /** "AI Mix": asks Claude (the key and the model are the owner's, `ClaudeSettings`). */
    private val claude: org.simpmusic.dj.android.ai.ClaudeMixClient = org.simpmusic.dj.android.ai.ClaudeMixClient(),
    /**
     * The tracks queued after the playing one, in playing order (empty when unknown, shuffled or blocked): what "AI Auto"
     * plans ahead ([AI_AHEAD_PAIRS] mixes), like a DJ who has the next records lined up before the current one ends.
     */
    private val upcomingIds: () -> List<String> = { emptyList() },
    /** The app's queue, to reorder it ("Order with AI"); null in tests that do not use it. */
    private val playerPort: () -> org.simpmusic.dj.android.recommend.DjPlayerPort? = { null },
) : DjHooks {
    override fun createAudioOutputProvider(): androidx.media3.exoplayer.audio.AudioOutputProvider? {
        val b = bus ?: return null
        val s = settingsFlow.value
        if (!s.enabled || !s.realtimeEngine) return null
        return org.simpmusic.dj.android.bus.BusAudioOutputProvider(b)
    }

    override fun busStreamTap(provider: androidx.media3.exoplayer.audio.AudioOutputProvider): androidx.media3.common.audio.AudioProcessor? =
        (provider as? org.simpmusic.dj.android.bus.BusAudioOutputProvider)?.streamTap

    override fun bindBusPlayer(player: androidx.media3.exoplayer.ExoPlayer, provider: androidx.media3.exoplayer.audio.AudioOutputProvider) {
        val b = bus ?: return
        (provider as? org.simpmusic.dj.android.bus.BusAudioOutputProvider)?.let { b.bind(player, it) }
    }

    override val mixBus: org.simpmusic.dj.android.bus.AndroidMixBus? get() = bus

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

    /** "AI Mix": the pair Claude chose points for, and the points (main thread). */
    private var aiKey: String? = null
    private var aiPoints: org.simpmusic.dj.model.AiMixPoints? = null
    private var aiJob: kotlinx.coroutines.Job? = null

    /** The pair "auto" already asked Claude about (once per pair, whatever the answer). */
    private var aiAutoKey: String? = null

    /**
     * Claude's answers for UPCOMING pairs (key -> points), solved while an earlier track plays (see [aiPlanAhead]): when a
     * pair becomes the current one its points are used at once, with no question and no wait. Main thread.
     */
    private val aiBook = LinkedHashMap<String, org.simpmusic.dj.model.AiMixPoints>()

    /** Upcoming pairs already asked about, whatever the answer (Claude is asked once per pair). Main thread. */
    private val aiAheadAsked = LinkedHashSet<String>()

    /** Pairs whose answer could not be planned and were asked once more (never twice: the owner's log asked 4 times in 15 s). */
    private val aiRetried = HashSet<String>()

    /** Per pair, where its incoming track plays alone from (entry + overlap, its own source ms): where the next mix may leave. */
    private val handoverOf = HashMap<String, Long>()
    private var aiAheadJob: Job? = null
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
        // lyrics land on a background thread; the pipeline state lives on the engine's scope
        lyrics?.onChanged = { id -> scope.launch { onLyricVocals(id) } }
    }

    @Volatile private var playbackPositionMs: Long = 0L

    override fun onPosition(positionMs: Long) {
        playbackPositionMs = positionMs
    }

    override val positionMs: Long get() = playbackPositionMs

    @Volatile private var currentRaw: TrackAnalysis? = null
    @Volatile private var currentRawAtMs = 0L
    @Volatile private var refreshingCurrent = false
    private var timeline: TrackTimeline? = null

    /**
     * The playing track's timeline: its grid as the planner sees it (repaired, bar phase decided, the owner's marks applied).
     * Main thread; re-read only when the analysis or one of the owner's marks changed (the counter asks at 20 Hz).
     */
    private fun currentTimeline(): TrackTimeline? {
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
        val t = timeline
        if (t != null && t.raw === raw && t.downbeatAnchorMs == BarPhase.anchors(id) &&
            t.phraseAnchorMs == org.simpmusic.dj.analysis.PhraseGrid.anchors(id) && t.mixMarks == org.simpmusic.dj.planner.MixMarks.of(id) &&
            t.lyricVocals == org.simpmusic.dj.timeline.Annotations.installed.lyricVocals(id)
        ) {
            return t
        }
        return Timelines.of(raw).also { timeline = it }
    }

    override fun barBeatAt(positionMs: Long): BarBeat? {
        val p = currentTimeline()?.barPosition(positionMs) ?: return null
        return BarBeat(
            p.beatInBar,
            p.beatsPerBar,
            sure = p.sure,
            anchored = p.anchored,
            barInBlock = p.barInBlock,
            phraseSure = p.phraseSure,
            blockSure = p.blockSure,
            phraseMarked = p.phraseMarked,
        )
    }

    override fun markPhrase(positionMs: Long): Long? {
        val id = pairFrom ?: return null
        val heard = (positionMs - org.simpmusic.dj.android.perfect.UserDownbeats.TAP_DELAY_MS).coerceAtLeast(0L)
        // the nearest bar line (a phrase starts on a 1); without bars yet the raw time is kept and snapped later
        val t = currentTimeline()?.nearestBarLineMs(heard) ?: heard
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
        // a mix point sits on a bar line; without bars yet the raw time is kept (the planner snaps it again)
        val t = currentTimeline()?.nearestBarLineMs(heard) ?: heard
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

    override fun hasTrackMarks(): Boolean {
        val id = pairFrom ?: return false
        return org.simpmusic.dj.android.perfect.UserMixMarks.get(id) != null ||
            org.simpmusic.dj.android.perfect.UserDownbeats.get(id) != null ||
            org.simpmusic.dj.android.perfect.UserPhrases.get(id) != null
    }

    override fun forgetTrackMarks(): Boolean {
        val id = pairFrom ?: return false
        val mix = org.simpmusic.dj.android.perfect.UserMixMarks.clearTrack(id)
        val one = org.simpmusic.dj.android.perfect.UserDownbeats.get(id) != null
        val phrase = org.simpmusic.dj.android.perfect.UserPhrases.get(id) != null
        if (one) org.simpmusic.dj.android.perfect.UserDownbeats.clear(id)
        if (phrase) org.simpmusic.dj.android.perfect.UserPhrases.clear(id)
        tapSession.clear()
        if (!mix && !one && !phrase) return false
        _debug.update { it.copy(lastOutcome = "marks forgotten: the DJ finds the points") }
        // the plan of the pair being prepared read those marks (the exit, the 1 and the phrases of the playing track)
        replanForMarks("marks of $id forgotten")
        return true
    }

    override fun markedTrackCount(): Int =
        (
            org.simpmusic.dj.android.perfect.UserMixMarks.all().keys +
                org.simpmusic.dj.android.perfect.UserDownbeats.all().keys +
                org.simpmusic.dj.android.perfect.UserPhrases.all().keys
            ).size

    override fun forgetAllMarks(): Int {
        val n = markedTrackCount()
        org.simpmusic.dj.android.perfect.UserMixMarks.clearAll()
        org.simpmusic.dj.android.perfect.UserDownbeats.clearAll()
        org.simpmusic.dj.android.perfect.UserPhrases.clearAll()
        tapSession.clear()
        DjLog.i(TAG, "all the owner's marks forgotten ($n tracks)")
        if (n > 0) {
            _debug.update { it.copy(lastOutcome = "all marks forgotten: the DJ finds the points") }
            replanForMarks("all marks forgotten")
        }
        return n
    }

    private fun replanForMarks(why: String = "mix marks changed") {
        val from = pairFrom ?: return
        val to = pairTo ?: return
        if (_debug.value.isMixing || pairKey == consumedKey) return
        DjLog.i(TAG, "$why: re-planning $from -> $to")
        cancelPipeline()
        ready?.let { deleteWindow(it) }
        ready = null
        mixNowKey = null
        startPipeline(from, to)
    }

    /**
     * A track's lyric vocals landed or changed (see `DjLyricsSource`). A pipeline still waiting for the analyses reads them
     * when it plans; a pair already planned (not mixing, not about to) is planned again with them, as for a new mix mark.
     */
    private fun onLyricVocals(id: String) {
        if (id != pairFrom && id != pairTo) return
        val d = _debug.value
        if (d.phase == "waiting-analysis") return
        val mixAt = d.mixAtMs
        if (mixAt != null && mixAt - playbackPositionMs < EARLIEST_EXIT_AHEAD_MS) {
            DjLog.i(TAG, "lyrics of $id landed, but the prepared mix is due in ${mixAt - playbackPositionMs} ms: kept")
            return
        }
        replanForMarks("lyrics of $id changed where the voice is")
    }

    /** Taps of the current session: (beat index, tap time). */
    private val tapSession = ArrayList<Pair<Int, Long>>()
    private var tapSessionTrack: String? = null
    private var lastTapAtMs = 0L

    override fun tapTheOne(positionMs: Long): Long? {
        val id = pairFrom ?: return null
        val tl = currentTimeline()
        val heard = (positionMs - org.simpmusic.dj.android.perfect.UserDownbeats.TAP_DELAY_MS).coerceAtLeast(0L)
        val bi = tl?.nearestBeatIndex(heard)
        if (tl == null || bi == null) {
            // no grid yet: keep the raw time, BarPhase snaps it when the analysis exists
            return org.simpmusic.dj.android.perfect.UserDownbeats.set(id, heard, "tap (no grid yet)")
        }
        val beats = tl.beatsMs
        val now = System.currentTimeMillis()
        if (tapSessionTrack != id || now - lastTapAtMs > TAP_SESSION_GAP_MS) tapSession.clear()
        tapSessionTrack = id
        lastTapAtMs = now
        tapSession += bi to heard
        val bpb = tl.beatsPerBar ?: 4
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
        val t = currentTimeline()?.shiftedOneMs(positionMs) ?: return null
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

    override fun log(tag: String, message: String) {
        // the adapter's poll explains itself every 5 s: logged when what it says changes (numbers aside), or once a minute
        if (tag == "poll" && !pollLines.shouldLog("poll", org.simpmusic.dj.android.log.RepeatFilter.withoutNumbers(message))) return
        DjLog.d(tag, message)
    }

    private val pollLines = org.simpmusic.dj.android.log.RepeatFilter(everyMs = 60_000L)

    override fun prepared(currentId: String, nextId: String): PreparedTransition? =
        ready?.takeIf { it.fromId == currentId && it.toId == nextId && key(currentId, nextId) != consumedKey }

    override fun consumed(prepared: PreparedTransition, outcome: String) {
        DjLog.i(TAG, "consumed ${prepared.fromId}->${prepared.toId}: $outcome")
        // a mix that started is journaled as ended (with the adapter's outcome) and becomes the one the owner may rate
        mixInFlight?.takeIf { it.prepared === prepared }?.let { m ->
            val now = System.currentTimeMillis()
            org.simpmusic.dj.android.journal.MixJournal.ended(m.id, outcome, now)
            lastEndedMix = org.simpmusic.dj.android.journal.LastMix(m.id, prepared.fromId, prepared.toId, endedAtEpochMs = now, ai = prepared.plan.meta?.source == org.simpmusic.dj.model.PlanSource.AI_MIX)
            mixInFlight = null
        }
        consumedKey = key(prepared.fromId, prepared.toId)
        if (ready === prepared) ready = null
        deleteWindow(prepared)
        stopMixView()
        _debug.update { it.copy(phase = "idle", lastOutcome = outcome, mixStartedAtEpochMs = null, mixDurationMs = null, mixAtMs = null, perfect = false) }
    }

    override fun onMixStarted(prepared: PreparedTransition) {
        val plan = prepared.plan
        val total = plan.overlapMs - prepared.timeline.planTimeOfWindow(startWindowMs(prepared)).toLong()
        DjLog.i(TAG, "MIX STARTED ${prepared.fromId} -> ${prepared.toId} (${plan.kind}), overlap ${plan.overlapMs} ms, window runs ${total} ms until the overlap is over")
        val now = System.currentTimeMillis()
        val id = "$now:${prepared.fromId}>${prepared.toId}"
        mixInFlight = MixInFlight(id, prepared)
        org.simpmusic.dj.android.journal.MixJournal.started(id, plan, now)
        startMixTicker(prepared)
        _debug.update { it.copy(phase = "mixing", kind = plan.kind, mixStartedAtEpochMs = System.currentTimeMillis(), mixDurationMs = total, mixAtMs = null) }
    }

    /** The mix the adapter is playing (journaled as started, not yet ended). */
    private class MixInFlight(val id: String, val prepared: PreparedTransition)

    @Volatile private var mixInFlight: MixInFlight? = null

    @Volatile private var lastEndedMix: org.simpmusic.dj.android.journal.LastMix? = null

    override fun rateLastMix(up: Boolean): Boolean {
        val m = lastEndedMix ?: return false
        val now = System.currentTimeMillis()
        if (!m.rateableAt(now)) return false
        // a second tap (a change of mind) is journaled too: the last rating of a mix is the one that counts
        org.simpmusic.dj.android.journal.MixJournal.rated(m.id, up, now)
        lastEndedMix = m.copy(rating = if (up) 1 else -1)
        if (m.ai) org.simpmusic.dj.android.ai.AiAnswerCache.rate(key(m.fromId, m.toId), up)
        DjLog.i(TAG, "mix ${m.id} rated ${if (up) "+1" else "-1"} by the owner")
        return true
    }

    override fun lastMix(): org.simpmusic.dj.android.journal.LastMix? = lastEndedMix

    override fun reset(reason: String) {
        DjLog.i(TAG, "reset: $reason (pair=$pairKey ready=${ready?.let { it.fromId + "->" + it.toId }})")
        cancelPipeline()
        ready?.let { deleteWindow(it) }
        ready = null
        pairKey = null
        mixNowKey = null
        perfectKey = null
        aiKey = null
        aiAutoKey = null
        consumedKey = null
        stopMixView()
    }

    override fun aiMix(): AiMixResult {
        if (!settingsFlow.value.enabled) return AiMixResult.DISABLED
        if (org.simpmusic.dj.android.ai.ClaudeSettings.apiKey() == null) return AiMixResult.NO_KEY
        val k = pairKey
        val from = pairFrom
        val to = pairTo
        if (k == null || from == null || to == null || k == consumedKey) return AiMixResult.NO_PAIR
        if (_debug.value.isMixing) return AiMixResult.ALREADY_MIXING
        if (aiJob?.isActive == true) return AiMixResult.BUSY
        launchAiAsk(k, from, to)
        return AiMixResult.ASKING
    }

    override fun aiMixRemembered(): AiMixResult {
        val k = pairKey
        val from = pairFrom
        val to = pairTo
        val e = k?.let { org.simpmusic.dj.android.ai.AiAnswerCache.usable(it) }
        if (k == null || from == null || to == null || e == null || k == consumedKey || _debug.value.isMixing || aiJob?.isActive == true || !settingsFlow.value.enabled) return aiMix()
        if (aiKey == k && aiPoints == e.points) return AiMixResult.ASKING
        DjLog.i(TAG, "ai mix $from -> $to: remembered answer (${e.model}) reused, not asked")
        aiKey = k
        aiPoints = e.points
        aiAutoKey = k
        cancelPipeline()
        ready?.let { deleteWindow(it) }
        ready = null
        startPipeline(from, to)
        aiSay("AI: from memory${if (e.rating == 1) " 👍" else ""} · out ${clockOf(e.points.exitMs)} → in ${clockOf(e.points.entryMs)}, ${e.points.overlapBars} bars")
        return AiMixResult.ASKING
    }

    private var orderJob: Job? = null
    private var learnJob: Job? = null

    override fun learnFromRatings(): AiMixResult {
        if (org.simpmusic.dj.android.ai.ClaudeSettings.apiKey() == null) return AiMixResult.NO_KEY
        if (learnJob?.isActive == true) return AiMixResult.BUSY
        val model = org.simpmusic.dj.android.ai.ClaudeSettings.state.value.model
        aiSay("AI: learning from your ratings with ${model.label}…", asking = true)
        learnJob =
            scope.launch {
                try {
                    val (rated, answer) =
                        withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val ids = org.simpmusic.dj.android.ai.OwnerTaste.rated().flatMap { (r, _) -> listOfNotNull(r.fromId, r.toId) }.toSet()
                            val titles = ids.associateWith { id -> runCatching { titleOf(id) }.getOrNull() }
                            val rated = org.simpmusic.dj.android.ai.OwnerTaste.allForLearning({ id -> titles[id] }, analysisOf = { id -> kotlinx.coroutines.runBlocking { scheduler.get(id) } })
                            val q = org.simpmusic.dj.android.ai.OwnerLessons.question(rated)
                            rated to q?.let { claude.askJson(org.simpmusic.dj.android.ai.OwnerLessons.SYSTEM_PROMPT, it, org.simpmusic.dj.android.ai.OwnerLessons.SCHEMA, "learn from ${rated.size} ratings") }
                        }
                    if (answer == null) {
                        aiSay("AI: rate at least 3 mixes first (${rated.size} so far)")
                        return@launch
                    }
                    val a = answer.getOrElse { e -> aiSay("AI: ${e.message}"); return@launch }
                    val l = org.simpmusic.dj.android.ai.OwnerLessons.store(a.json, rated.size, model.id).getOrElse { e -> aiSay("AI: ${e.message}"); return@launch }
                    aiSay("AI: learned ${l.lessons.size} rules from ${rated.size} ratings · \$${"%.3f".format(java.util.Locale.ROOT, claude.costUsd(a))} · ${l.summary}")
                } finally {
                    _debug.update { it.copy(aiAsking = false) }
                }
            }
        return AiMixResult.ASKING
    }

    override fun orderWithAi(): AiMixResult {
        if (!settingsFlow.value.enabled) return AiMixResult.DISABLED
        if (org.simpmusic.dj.android.ai.ClaudeSettings.apiKey() == null) return AiMixResult.NO_KEY
        val port = playerPort() ?: return AiMixResult.NO_PAIR
        if (orderJob?.isActive == true) return AiMixResult.BUSY
        val snap = port.snapshot()
        val current = snap.currentId ?: return AiMixResult.NO_PAIR
        if (snap.blockedReason != null || snap.currentIndex < 0) return AiMixResult.NO_PAIR
        val upcoming = snap.queueIds.drop(snap.currentIndex + 1).take(AI_ORDER_TRACKS)
        if (upcoming.size < 3) return AiMixResult.NO_PAIR
        val model = org.simpmusic.dj.android.ai.ClaudeSettings.state.value.model
        aiSay("AI: ordering the next ${upcoming.size} tracks with ${model.label}…", asking = true)
        launchOrder(port, current, upcoming, keep = 0, auto = false)
        return AiMixResult.ASKING
    }

    private fun launchOrder(port: org.simpmusic.dj.android.recommend.DjPlayerPort, current: String, upcoming: List<String>, keep: Int, auto: Boolean) {
        orderJob =
            scope.launch {
                try {
                    orderAhead(port, current, upcoming, keep, auto)
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    DjLog.e(TAG, "order with AI failed", e)
                    if (!auto) aiSay("AI: ${e.javaClass.simpleName}")
                } finally {
                    reordering = false
                    if (!auto) _debug.update { it.copy(aiAsking = false) }
                    // the queue contexts held back while the tracks moved: the final one now
                    pendingContext?.let { pendingContext = null; onQueueContext(it) }
                }
            }
    }

    private var lastAutoOrderAtMs = 0L

    /**
     * "AI Auto" also keeps the radio in mixing order by itself (the owner had to press "Order list" every ten tracks): when
     * the radio's upcoming tracks past the next [AI_AHEAD_PAIRS] (those mixes are being solved ahead and stay where they
     * are) hold at least [AUTO_ORDER_MIN_NEW] tracks Claude has not ordered yet, the next [AUTO_ORDER_TRACKS] of them are
     * ordered. At most once per [AUTO_ORDER_GAP_MS]; a YouTube radio only (a playlist plays in the owner's order).
     */
    private fun maybeAutoOrder() {
        val c = org.simpmusic.dj.android.ai.ClaudeSettings.state.value
        if (!c.auto || !c.autoOrder || org.simpmusic.dj.android.ai.ClaudeSettings.apiKey() == null || !settingsFlow.value.enabled) return
        if (orderJob?.isActive == true || reordering || _debug.value.isMixing) return
        val now = System.currentTimeMillis()
        if (now - lastAutoOrderAtMs < AUTO_ORDER_GAP_MS) return
        val port = playerPort() ?: return
        val snap = runCatching { port.snapshot() }.getOrNull() ?: return
        val current = snap.currentId ?: return
        if (!snap.isRadio || snap.blockedReason != null || snap.currentIndex < 0) return
        val upcoming = snap.queueIds.drop(snap.currentIndex + 1).take(AI_AHEAD_PAIRS + AUTO_ORDER_TRACKS)
        val rest = upcoming.drop(AI_AHEAD_PAIRS)
        if (rest.size < AUTO_ORDER_MIN_NEW + 1) return
        val fresh = rest.count { it !in org.simpmusic.dj.android.ai.ClaudeOrder.ids }
        if (fresh < AUTO_ORDER_MIN_NEW) return
        lastAutoOrderAtMs = now
        DjLog.i(TAG, "order with AI (auto): $fresh of the ${rest.size} tracks after the next $AI_AHEAD_PAIRS are new")
        launchOrder(port, current, upcoming, keep = AI_AHEAD_PAIRS, auto = true)
    }

    /** True while "Order list" moves tracks: the queue contexts in between are transient and only the last one counts. */
    @Volatile private var reordering = false
    private var pendingContext: DjQueueContext? = null

    /**
     * Orders [upcoming] (after [current]) with Claude. The first [keep] stay where they are (their mixes are solved ahead);
     * Claude orders the rest, after the last kept one.
     */
    private suspend fun orderAhead(port: org.simpmusic.dj.android.recommend.DjPlayerPort, current: String, upcoming: List<String>, keep: Int = 0, auto: Boolean = false) {
        val say: (String) -> Unit = { if (auto) DjLog.i(TAG, "order with AI (auto): $it") else aiSay(it) }
        val fixed = upcoming.take(keep)
        val anchor = fixed.lastOrNull() ?: current
        val toOrder = upcoming.drop(keep)
        // only stored analyses (no new work: the order is asked now); the others go by title and what Claude knows
        val tracks =
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                (listOf(anchor) + toOrder).map { id ->
                    val title = port.queuedTitle(id) ?: runCatching { titleOf(id) }.getOrNull() ?: id
                    val a = runCatching { scheduler.get(id) }.getOrNull()
                    org.simpmusic.dj.ai.SetBrief.Track(id, title, a?.let { Timelines.of(it) })
                }
            }
        val brief =
            org.simpmusic.dj.ai.SetBrief.build(
                tracks.first(), tracks.drop(1),
                taste = runCatching { org.simpmusic.dj.ai.MixBrief.taste(org.simpmusic.dj.android.ai.OwnerTaste.recent()) }.getOrNull(),
            ) ?: run {
                say("AI: not enough upcoming tracks to order")
                return
            }
        DjLog.i(TAG, "order with AI: ${tracks.size - 1} upcoming, ${tracks.count { it.timeline != null } - 1} analysed")
        val answer =
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                claude.askJson(brief.system, brief.user, org.simpmusic.dj.ai.SetBrief.SCHEMA, "order the list")
            }.getOrElse {
                say("AI: ${it.message}")
                return
            }
        val order = org.simpmusic.dj.ai.SetBrief.parse(brief, answer.json).getOrElse {
            DjLog.w(TAG, "order with AI: unusable answer (${it.message}): ${answer.json}")
            say("AI: unusable answer (${it.message})")
            return
        }
        // the queue must still be what was ordered
        val now = port.snapshot()
        if (now.currentId != current || now.queueIds.drop(now.currentIndex + 1).take(upcoming.size) != upcoming) {
            say("AI: the queue changed meanwhile, nothing moved")
            return
        }
        val desired = fixed + order.ids
        // what Claude has ordered so far (the look-ahead leaves it alone; the auto order counts what is new)
        org.simpmusic.dj.android.ai.ClaudeOrder.add(desired)
        if (desired == upcoming) {
            DjLog.i(TAG, "order with AI: the radio's order is already the best (${order.why})")
            say("AI: the list was already in order · ${order.why}")
            return
        }
        // into place from the last: each one moved right after the current pushes the ones moved before it back by one.
        // The queue contexts in between are transient pairs: held back (no planning, no question) until the end.
        reordering = true
        var moved = 0
        for (id in desired.asReversed()) {
            if (port.moveToNext(id)) moved++
            // the handler moves one step at a time: wait until the queue shows it in the next slot
            withTimeoutOrNull(ORDER_STEP_WAIT_MS) {
                while (port.snapshot().let { it.queueIds.getOrNull(it.currentIndex + 1) } != id) kotlinx.coroutines.delay(50)
            }
        }
        val result = port.snapshot().let { it.queueIds.drop(it.currentIndex + 1).take(desired.size) }
        DjLog.i(TAG, "order with AI${if (auto) " (auto)" else ""}: ${desired.joinToString(" > ")} (${order.why}); moved $moved, queue now ${result.joinToString(" > ")}; \$${"%.4f".format(java.util.Locale.ROOT, claude.costUsd(answer))}")
        // the mixes solved ahead for pairs that still follow each other stay; the others were for the old order
        val pairs = (listOf(current) + result).zipWithNext { a, b -> key(a, b) }.toSet()
        aiBook.keys.retainAll(pairs)
        aiAheadAsked.retainAll(pairs)
        _debug.update { it.copy(aiAhead = aiBook.size) }
        say("AI: list ordered · ${order.why}")
    }

    private fun launchAiAsk(k: String, from: String, to: String) {
        val model = org.simpmusic.dj.android.ai.ClaudeSettings.state.value.model
        aiSay("AI: asking ${model.label}…", asking = true)
        aiJob =
            scope.launch {
                try {
                    aiAsk(k, from, to)
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    DjLog.e(TAG, "ai mix $from -> $to failed", e)
                    aiSay("AI: ${e.javaClass.simpleName}")
                } finally {
                    _debug.update { it.copy(aiAsking = false) }
                    // the current pair is settled (answered or not): the upcoming ones can be solved now
                    scope.launch { aiPlanAhead() }
                }
            }
    }

    /**
     * "Claude chooses every mix" (`ClaudeSettings.auto`): once per pair, as soon as both analyses are ready. The regular plan
     * is made meanwhile, so a mix is always prepared; Claude's answer re-plans the pair when it lands.
     */
    private fun maybeAutoAsk(fromId: String, toId: String) {
        val k = key(fromId, toId)
        val claude = org.simpmusic.dj.android.ai.ClaudeSettings.state.value
        if (!claude.auto || org.simpmusic.dj.android.ai.ClaudeSettings.apiKey() == null) return
        if (aiKey == k || aiAutoKey == k || aiJob?.isActive == true || k == consumedKey || pairKey != k || _debug.value.isMixing) return
        aiAutoKey = k
        DjLog.i(TAG, "ai mix: auto, asking ${claude.model.id} for $fromId -> $toId")
        launchAiAsk(k, fromId, toId)
    }

    private fun aiSay(status: String, asking: Boolean = false) {
        _debug.update { it.copy(aiStatus = status, aiStatusAtEpochMs = System.currentTimeMillis(), aiAsking = asking) }
    }

    /** Main thread, suspending for the analyses, the brief and Claude's answer. */
    private suspend fun aiAsk(k: String, fromId: String, toId: String) {
        val from = scheduler.get(fromId)
        val to = scheduler.get(toId)
        if (from == null || to == null) {
            aiSay("AI: the tracks are still being analysed")
            return
        }
        val settings = settingsFlow.value
        val withTools = org.simpmusic.dj.android.ai.ClaudeSettings.state.value.tools
        // the answer takes seconds (more with tools: several turns), then the mix needs its usual lead
        val cue = if (bus != null && settings.realtimeEngine) BUS_EARLIEST_EXIT_AHEAD_MS else EARLIEST_EXIT_AHEAD_MS
        val position = playbackPositionMs
        val after = runCatching { upcomingIds() }.getOrDefault(emptyList()).takeIf { it.firstOrNull() == toId }?.getOrNull(1)
        var noRoom = false
        var pts = claudePoints(fromId, toId, from, to, earliestExitMs = position + answerBudgetMs(withTools) + cue, heardFromMs = position, ahead = false, afterId = after, oneAnswer = !withTools, onNoRoom = { noRoom = true }, say = { aiSay(it) })
        if (pts == null && noRoom && withTools) {
            // too little of the track left for a conversation with tools: one quick answer still fits (it takes seconds)
            DjLog.i(TAG, "ai mix $fromId -> $toId: no room for the tools, asking for one answer")
            pts = claudePoints(fromId, toId, from, to, earliestExitMs = position + answerBudgetMs(false) + cue, heardFromMs = position, ahead = false, afterId = after, oneAnswer = true, say = { aiSay(it) })
        }
        if (pts == null) return
        if (pairKey != k || k == consumedKey || _debug.value.isMixing) {
            aiSay("AI: the pair changed meanwhile")
            return
        }
        aiKey = k
        aiPoints = pts
        org.simpmusic.dj.android.ai.AiAnswerCache.put(k, pts, org.simpmusic.dj.android.ai.ClaudeSettings.state.value.model.id)
        mixNowKey = null
        perfectKey = null
        cancelPipeline()
        ready?.let { deleteWindow(it) }
        ready = null
        startPipeline(fromId, toId)
        aiSay("AI: out ${clockOf(pts.exitMs)} → in ${clockOf(pts.entryMs)}, ${pts.overlapBars} bars" + (if (pts.moves.isDefault) "" else " · ${pts.moves.describe()}") + " · ${pts.why}")
    }

    /**
     * Claude's points for [fromId] -> [toId], exits offered from [earliestExitMs] (outgoing source ms); the outgoing is heard
     * from [heardFromMs] (the playhead now, or where an earlier mix hands it over, when [ahead]). Null when there is no usable
     * answer; why is said through [say]. Suspends on IO for the brief, the picture and the request.
     */
    private suspend fun claudePoints(
        fromId: String,
        toId: String,
        from: TrackAnalysis,
        to: TrackAnalysis,
        earliestExitMs: Long,
        heardFromMs: Long,
        ahead: Boolean,
        /** The track after [toId] in the queue: the entry must leave [toId] room to mix into it. */
        afterId: String? = null,
        /** One answer without tools, whatever the setting (when the track has no room left for a conversation). */
        oneAnswer: Boolean = false,
        /** Called when no exit is left from [earliestExitMs]. */
        onNoRoom: () -> Unit = {},
        say: (String) -> Unit,
    ): org.simpmusic.dj.model.AiMixPoints? {
        val settings = settingsFlow.value
        // Tempos the engine cannot meet (it bends each deck at most OWNER_TEMPO_BEND): Claude cannot fix that, so it is not
        // asked (the owner's first Opus session paid for 3 answers at 97 -> 125 and 102 -> 84 bpm, all thrown away).
        val bpmOut = Timelines.of(from).bpm?.value
        val bpmIn = Timelines.of(to).bpm?.value
        if (bpmOut != null && bpmIn != null) {
            val r = bpmIn / bpmOut
            val gap = listOf(r, r * 2f, r / 2f).minOf { kotlin.math.abs(it - 1f) }
            if (gap > AI_MAX_TEMPO_GAP) {
                say("AI: tempos too far apart (%.0f → %.0f bpm), the DJ mixes this one".format(bpmOut, bpmIn))
                DjLog.i(TAG, "ai mix $fromId -> $toId not asked: tempo gap %.0f %% (%.1f -> %.1f bpm)".format(gap * 100, bpmOut, bpmIn))
                return null
            }
        }
        val withTools = org.simpmusic.dj.android.ai.ClaudeSettings.state.value.tools && !oneAnswer
        val titleFrom = runCatching { titleOf(fromId) }.getOrNull() ?: fromId
        val titleTo = runCatching { titleOf(toId) }.getOrNull() ?: toId
        val entryLatest = runCatching { entryLatestFor(toId, to, afterId) }.getOrNull()
        val brief =
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                org.simpmusic.dj.ai.MixBrief.build(
                    org.simpmusic.dj.ai.MixBrief.Track(titleFrom, Timelines.of(from), lyrics?.linesOnTrack(fromId)),
                    org.simpmusic.dj.ai.MixBrief.Track(titleTo, Timelines.of(to), lyrics?.linesOnTrack(toId)),
                    earliestExitMs = earliestExitMs,
                    requestedBars = settings.overlapBars,
                    positionMs = heardFromMs,
                    // the owner's latest 👍 / 👎 with what those mixes were: Claude's memory of their taste
                    taste = runCatching { org.simpmusic.dj.ai.MixBrief.taste(org.simpmusic.dj.android.ai.OwnerTaste.recent(fromId = fromId, toId = toId, analysisOf = { id -> kotlinx.coroutines.runBlocking { scheduler.get(id) } })) }.getOrNull(),
                    ahead = ahead,
                    entryLatestMs = entryLatest,
                )
            }
        if (brief == null) {
            DjLog.i(TAG, "ai mix $fromId -> $toId not asked: no exit left from $earliestExitMs ms (track ${from.durationMs} ms)")
            onNoRoom()
            say("AI: not enough of this track left to mix")
            return null
        }
        val answer =
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                val tFrom = Timelines.of(from)
                val tTo = Timelines.of(to)
                // the fingerprint of both tracks as a picture, for Claude's eye
                val image = org.simpmusic.dj.android.ai.FingerprintImage.png(brief, tFrom, tTo, titleFrom, titleTo)
                val tools =
                    if (withTools) {
                        org.simpmusic.dj.ai.MixTools(brief, from, to, lyrics?.linesOnTrack(fromId), lyrics?.linesOnTrack(toId), settings.copy(mixPoint = MixPoint.ANYWHERE, minPlayedFraction = 0f), earliestExitMs, planner)
                    } else {
                        null
                    }
                claude.ask(brief, image, tools)
            }.getOrElse {
                DjLog.w(TAG, "ai mix: ${it.message}")
                say("AI: ${it.message}")
                return null
            }
        val pts = (answer.committed?.let { Result.success(it) } ?: org.simpmusic.dj.ai.MixBrief.parse(brief, answer.json)).getOrElse {
            DjLog.w(TAG, "ai mix: unusable answer (${it.message}): ${answer.json}")
            say("AI: unusable answer (${it.message})")
            return null
        }
        val cost = claude.costUsd(answer)
        answerTimes.addLast(answer.elapsedMs to (answer.turns > 1 || answer.tries > 0))
        while (answerTimes.size > ANSWER_TIMES) answerTimes.removeFirst()
        DjLog.i(TAG, "ai mix $fromId -> $toId${if (ahead) " (ahead)" else ""}: exit ${pts.exitMs} ms, entry ${pts.entryMs} ms, ${pts.overlapBars} bars, moves ${pts.moves.describe()} (${pts.why}); ${answer.model.id}, ${answer.elapsedMs} ms, ${answer.turns} turns, ${answer.tries} tries, \$${"%.4f".format(java.util.Locale.ROOT, cost)}")
        return pts
    }

    /**
     * "AI Auto" plans AHEAD, like a DJ who already has the next records worked out: while a track plays, Claude solves the
     * mixes of the next [AI_AHEAD_PAIRS] pairs of the queue, one after the other. The next pair's outgoing is heard from where
     * the mix before hands it over ([handoverOf]: entry + overlap), so its exits are offered from there + [AI_AHEAD_SOLO_MS];
     * nothing depends on the playhead, and when a pair becomes current its answer is used at once ([aiBook]).
     *
     * Before this, Claude was asked only when a pair became current. A mix that entered the next track 52 s in (of 1:38)
     * then left no exit after the 60 s an answer may take: "not enough of this track left" (owner, build bd, 2026-10-17).
     * Main thread; at most one ahead question at a time, after the current pair's own question.
     */
    private fun aiPlanAhead() {
        val claudeState = org.simpmusic.dj.android.ai.ClaudeSettings.state.value
        if (!claudeState.auto || org.simpmusic.dj.android.ai.ClaudeSettings.apiKey() == null || !settingsFlow.value.enabled) return
        if (aiAheadJob?.isActive == true || aiJob?.isActive == true) return
        val current = pairFrom ?: return
        val next = pairTo ?: return
        val upcoming = runCatching { upcomingIds() }.getOrDefault(emptyList())
        if (upcoming.firstOrNull() != next) return // the queue moved, or it cannot be read: the next context change retries
        val chain = listOf(current) + upcoming.take(AI_AHEAD_PAIRS)
        scheduler.setAiAhead(upcoming.drop(1).toSet())
        // the first upcoming pair not asked yet, whose outgoing's hand-over is known (the pair before it is planned)
        for (i in 1 until chain.size - 1) {
            val a = chain[i]
            val b = chain[i + 1]
            val k = key(a, b)
            if (k in aiBook || k in aiAheadAsked) continue
            val heard = handoverOf[key(chain[i - 1], a)] ?: return
            aiAheadAsked += k
            while (aiAheadAsked.size > AI_AHEAD_MEMORY) aiAheadAsked.remove(aiAheadAsked.first())
            aiAheadJob =
                scope.launch {
                    try {
                        aiAskAhead(k, a, b, heard, chain.getOrNull(i + 2) ?: runCatching { upcomingIds() }.getOrDefault(emptyList()).getOrNull(i + 1))
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        DjLog.e(TAG, "ai ahead $a -> $b failed", e)
                    }
                    // the next pair down the chain (its hand-over is known now)
                    aiPlanAhead()
                }
            return
        }
    }

    /**
     * The latest entry into [to] that still leaves it room to mix into [afterId] the way the DJ would (its regular / perfect
     * exit into that track, after [AI_AHEAD_SOLO_MS] alone and the owner's usual overlap). Null when [afterId] is unknown or
     * not analysed, or when the limit would leave almost no choice (a short track: Claude then chooses freely).
     */
    private suspend fun entryLatestFor(toId: String, to: TrackAnalysis, afterId: String?): Long? {
        afterId ?: return null
        val after = scheduler.get(afterId) ?: return null
        val settings = settingsFlow.value
        val next = withContext(heavyDispatcher) { planner.plan(to, after, settings, PlanConstraints(earliestExitMs = 0L, preferPerfect = true)) }
        val bpm = Timelines.of(to).bpm?.value?.takeIf { it > 0f } ?: return null
        val overlapMs = (settings.overlapBars * 4 * 60_000f / bpm).toLong()
        val limit = next.exitPointMs - AI_AHEAD_SOLO_MS - overlapMs
        DjLog.i(TAG, "ai chain: $toId leaves into $afterId near ${next.exitPointMs} ms (${next.kind}), so it is entered no later than $limit ms")
        return limit.takeIf { it >= AI_CHAIN_MIN_ENTRY_ROOM_MS }
    }

    private suspend fun aiAskAhead(k: String, fromId: String, toId: String, heardFromMs: Long, afterId: String?) {
        lyrics?.request(fromId)
        lyrics?.request(toId)
        // the upcoming tracks are analysed at NEXT_UP priority, one at a time (kept through cancelStale, see setAiAhead)
        scheduler.request(fromId, AnalysisPriority.NEXT_UP)
        scheduler.request(toId, AnalysisPriority.NEXT_UP)
        val pair =
            withTimeoutOrNull(AI_AHEAD_ANALYSIS_WAIT_MS) {
                combine(scheduler.observe(fromId), scheduler.observe(toId)) { a, b -> if (a != null && b != null) a to b else null }.filterNotNull().first()
            }
        if (pair == null) {
            DjLog.i(TAG, "ai ahead $fromId -> $toId: analyses not ready after ${AI_AHEAD_ANALYSIS_WAIT_MS / 1000} s, asked when the pair comes")
            aiAheadAsked.remove(k)
            return
        }
        val (from, to) = pair
        val earliest = heardFromMs + AI_AHEAD_SOLO_MS
        DjLog.i(TAG, "ai ahead: asking for $fromId -> $toId (outgoing heard from $heardFromMs ms, exits from $earliest ms)")
        // the track after toId may not be analysed yet; it is asked for, and the limit applies when it is there
        afterId?.let { lyrics?.request(it); scheduler.request(it, AnalysisPriority.NEXT_UP) }
        // Claude solved this pair before (a skip back, a radio that repeats, an earlier session): reused, nothing is paid
        val memo = org.simpmusic.dj.android.ai.AiAnswerCache.usable(k)?.points?.takeIf { it.exitMs >= earliest }
        val remembered = memo != null
        if (remembered) DjLog.i(TAG, "ai ahead $fromId -> $toId: remembered answer reused (exit ${memo!!.exitMs} ms, entry ${memo.entryMs} ms), not asked")
        val pts = memo ?: claudePoints(fromId, toId, from, to, earliestExitMs = earliest, heardFromMs = heardFromMs, ahead = true, afterId = afterId, say = { DjLog.i(TAG, "ai ahead $fromId -> $toId: $it") })
        if (pts == null) {
            // no answer (tempos too far apart, no room, an error): the DJ's own plan says where the next pair starts from
            val regular = withContext(heavyDispatcher) { planner.plan(from, to, settingsFlow.value, PlanConstraints(earliestExitMs = earliest, preferPerfect = true)) }
            handoverOf[k] = regular.entryPointMs + regular.overlapMs
            return
        }
        // planned now (milliseconds), to know where the incoming is handed over: the start of the pair after it
        val settings = settingsFlow.value.copy(mixPoint = MixPoint.ANYWHERE, minPlayedFraction = 0f)
        val plan = withContext(heavyDispatcher) { planner.plan(from, to, settings, PlanConstraints(earliestExitMs = earliest, aiPoints = pts)) }
        if (plan.meta?.source != org.simpmusic.dj.model.PlanSource.AI_MIX) {
            DjLog.i(TAG, "ai ahead $fromId -> $toId: Claude's points cannot be planned (${plan.reason.substringAfter(org.simpmusic.dj.model.AiMixPoints.NOT_USED, plan.reason).take(160)}); the DJ plans this pair when it comes")
            handoverOf[k] = plan.entryPointMs + plan.overlapMs
            return
        }
        aiBook[k] = pts
        if (!remembered) org.simpmusic.dj.android.ai.AiAnswerCache.put(k, pts, org.simpmusic.dj.android.ai.ClaudeSettings.state.value.model.id)
        while (aiBook.size > AI_AHEAD_MEMORY) aiBook.remove(aiBook.keys.first())
        handoverOf[k] = plan.entryPointMs + plan.overlapMs
        _debug.update { it.copy(aiAhead = aiBook.size) }
    }

    /**
     * The current pair was planned: its incoming is heard alone from [atMs]. An answer solved ahead for the pair after it
     * that leaves before that (the previous mix moved) is dropped and asked again; then the chain goes on.
     */
    private fun noteHandover(k: String, atMs: Long) {
        handoverOf[k] = atMs
        val to = pairTo
        val after = runCatching { upcomingIds() }.getOrDefault(emptyList()).getOrNull(1)
        if (to != null && after != null) {
            val nk = key(to, after)
            val booked = aiBook[nk]
            if (booked != null && booked.exitMs < atMs + AI_AHEAD_MIN_SOLO_MS) {
                DjLog.i(TAG, "ai ahead $nk: exit ${booked.exitMs} ms comes before the hand-over at $atMs ms; asking again")
                aiBook.remove(nk)
                aiAheadAsked.remove(nk)
                _debug.update { it.copy(aiAhead = aiBook.size) }
            }
        }
        while (handoverOf.size > AI_AHEAD_MEMORY * 2) handoverOf.remove(handoverOf.keys.first())
        aiPlanAhead()
    }

    /** The last answers' durations (ms) and whether they used tools: how long the next one may take. */
    private val answerTimes = ArrayDeque<Pair<Long, Boolean>>()

    /**
     * How long to leave for Claude's answer before the first exit: the 90th percentile of the last answers of that kind
     * plus a margin, between [AI_ANSWER_BUDGET_MS] and the old fixed budget. Measured on the owner's build-bf export, with
     * tools: Sonnet 16 s, Opus 20 s on average, while 60 s was reserved, which left short tracks no exit at all.
     */
    private fun answerBudgetMs(tools: Boolean): Long {
        val fixed = if (tools) AI_TOOLS_BUDGET_MS else AI_ANSWER_BUDGET_MS
        val seen = answerTimes.filter { it.second == tools }.map { it.first }.sorted()
        if (seen.size < 3) return fixed
        val p90 = seen[((seen.size - 1) * 0.9).toInt()]
        return (p90 + ANSWER_MARGIN_MS).coerceIn(AI_ANSWER_BUDGET_MS.coerceAtMost(fixed), fixed)
    }

    private fun clockOf(ms: Long) = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

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
        if (reordering) {
            pendingContext = context
            return
        }
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
        // Where the voice is, from the lyrics: asked once per track per process (a no-op after that).
        lyrics?.request(context.currentId)
        lyrics?.request(next)
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
        aiKey = null
        // Solved ahead while the previous track played: plan Claude's points right away, nothing to ask or wait for.
        aiBook.remove(k)?.let { pts ->
            DjLog.i(TAG, "ai mix ${context.currentId} -> $next: solved ahead (exit ${pts.exitMs} ms, entry ${pts.entryMs} ms, ${pts.overlapBars} bars)")
            aiKey = k
            aiPoints = pts
            aiAutoKey = k
            aiSay("AI: solved ahead · out ${clockOf(pts.exitMs)} → in ${clockOf(pts.entryMs)}, ${pts.overlapBars} bars")
        }
        if (aiKey != k) {
            // answered before (a skip back, an earlier session): reused instead of asking again. The ✨ button still asks.
            org.simpmusic.dj.android.ai.AiAnswerCache.usable(k)?.takeIf { org.simpmusic.dj.android.ai.ClaudeSettings.state.value.auto || it.rating == 1 }?.let { e ->
                val pts = e.points
                DjLog.i(TAG, "ai mix ${context.currentId} -> $next: remembered answer (${e.model}${if (e.rating == 1) ", liked" else ""}): exit ${pts.exitMs} ms, entry ${pts.entryMs} ms, ${pts.overlapBars} bars; not asked")
                aiKey = k
                aiPoints = pts
                aiAutoKey = k
                aiSay("AI: from memory${if (e.rating == 1) " 👍" else ""} · out ${clockOf(pts.exitMs)} → in ${clockOf(pts.entryMs)}, ${pts.overlapBars} bars")
            }
        }
        _debug.update { it.copy(aiAhead = aiBook.size) }
        startPipeline(context.currentId, next)
        maybeAutoOrder()
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
        // A stored analysis can be ready while layers a mix needs (vocals, structure frames, bar evidence) are still being
        // computed: planning now would plan without the voices, so no structure hand-off and no voice-over-voice check, and
        // nothing re-plans when they land. It takes a decode and the vocal model, ~10-20 s; wait for them, bounded, and only
        // while the scheduler is actually working on that track (a layer it cannot compute is not waited for).
        suspend fun upgrading(id: String): Boolean = scheduler.upgradePending(id) && scheduler.missingLayers(id).isNotEmpty()
        if (upgrading(fromId) || upgrading(toId)) {
            val pending = listOf(fromId, toId).filter { upgrading(it) }.associateWith { id -> scheduler.missingLayers(id).map { it.key } }
            DjLog.i(TAG, "waiting for the analysis layers $pending before planning")
            _debug.update { it.copy(lastOutcome = "analysing the voices first…") }
            val done =
                withTimeoutOrNull(UPGRADE_WAIT_MS) {
                    while (upgrading(fromId) || upgrading(toId)) delay(500)
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
        maybeAutoAsk(fromId, toId)
        val settings = settingsFlow.value
        // The window needs the outgoing audio decoded and rendered before it plays, so the mix cannot start sooner than that.
        // The real-time engine only needs the incoming player cued (a few seconds): the mix can come much sooner.
        val earliest = playbackPositionMs + if (bus != null && settings.realtimeEngine) BUS_EARLIEST_EXIT_AHEAD_MS else EARLIEST_EXIT_AHEAD_MS
        val constraints = PlanConstraints(earliestExitMs = earliest)
        val aiFor = aiPoints?.takeIf { aiKey == key(fromId, toId) }
        val plan =
            if (aiFor != null) {
                // "AI Mix": Claude's exit, entry and overlap; anywhere in the track, nothing has to have played
                val anywhere = settings.copy(mixPoint = MixPoint.ANYWHERE, minPlayedFraction = 0f)
                DjLog.i(TAG, "planning AI MIX: exit ${aiFor.exitMs}, entry ${aiFor.entryMs}, ${aiFor.overlapBars} bars, earliest exit $earliest ms (position $playbackPositionMs ms)")
                val p = withContext(heavyDispatcher) { planner.plan(from, to, anywhere, PlanConstraints(earliestExitMs = earliest, aiPoints = aiFor)) }
                if (p.meta?.source == org.simpmusic.dj.model.PlanSource.AI_MIX) {
                    p
                } else {
                    val why = p.reason.substringAfter(org.simpmusic.dj.model.AiMixPoints.NOT_USED, "no beat-matched mix at those points")
                    DjLog.i(TAG, "ai mix: not possible ($why); keeping the regular plan")
                    aiKey = null
                    aiSay("AI mix not possible: ${why.take(120)}")
                    // an answer solved ahead may not fit any more (the previous mix ended elsewhere): ask again, for now
                    val k = key(fromId, toId)
                    if (aiAutoKey == k && aiJob?.isActive != true && !org.simpmusic.dj.android.ai.AiAnswerCache.liked(k) && aiRetried.add(k)) {
                        aiAutoKey = null
                        scope.launch { maybeAutoAsk(fromId, toId) }
                    }
                    withContext(heavyDispatcher) { planner.plan(from, to, settings, constraints.copy(preferPerfect = true)) }
                }
            } else if (perfectKey == key(fromId, toId)) {
                val latest = earliest + PERFECT_SPAN_MS
                DjLog.i(TAG, "planning PERFECT: exit between $earliest and $latest ms (position $playbackPositionMs ms); ${AnalysisRefiner.cached(from).let { BarPhase.describe(it) + "; " + org.simpmusic.dj.analysis.PhraseGrid.describe(it) }} | ${AnalysisRefiner.cached(to).let { BarPhase.describe(it) + "; " + org.simpmusic.dj.analysis.PhraseGrid.describe(it) }}")
                val p = withContext(heavyDispatcher) { planner.plan(from, to, settings, PlanConstraints(earliestExitMs = earliest, latestExitMs = latest, perfect = true)) }
                if (p.kind == PlanKind.BEAT_MATCHED && isPerfect(p)) {
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
        // where the incoming plays alone from: the next mix (solved ahead by Claude) may leave from there
        noteHandover(key(fromId, toId), plan.entryPointMs + plan.overlapMs)
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
        if (bus != null && settings.realtimeEngine) {
            // The real-time engine plays the plan live from the two players: nothing to decode or render.
            val prepared = PreparedTransition(
                fromId, toId, plan, timeline, RenderedWindow(File(windowDir, "none.wav"), bus.sampleRate, 0L), to.durationMs, viaBus = true,
            )
            ready = prepared
            val titles = (runCatching { titleOf(fromId) }.getOrNull() ?: fromId) to (runCatching { titleOf(toId) }.getOrNull() ?: toId)
            _mixView.value = runCatching { DjMixViewBuilder.build(plan, from, to, titles) }.onFailure { DjLog.w(TAG, "mix view could not be built: ${it.message}") }.getOrNull()
            _debug.update { it.copy(phase = "ready", mixAtMs = plan.exitPointMs) }
            DjLog.i(
                TAG,
                "LIVE mix READY $fromId -> $toId in ${since()} ms since pipeline start (real-time engine): bus leaves the copy path at outgoing src " +
                    "${"%.0f".format(prepared.busStartSourceMs())} ms, incoming cued at ${"%.0f".format(prepared.triggerSourceMs(0.0))} ms (${plan.kind}: ${plan.reason})",
            )
            listener?.invoke(prepared)
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
        // (a DJ loop keeps the outgoing inside its looped bars however long the overlap is)
        val outEnd = timeline.outgoingSourceAt(plan.overlapMs.toDouble()).let { v -> plan.outLoop?.let { maxOf(it.endMs.toDouble(), it.realMs(v)) } ?: v }
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
                // the tempo of the repaired grid the plan used, not the raw DSP field (which reads cumbia's tresillo as 134)
                fromBpm = Timelines.of(from).bpm?.value,
                toBpm = Timelines.of(to).bpm?.value,
                fromKey = from.key?.value?.camelot(),
                toKey = to.key?.value?.camelot(),
                confidence = plan.confidence,
                perfect = isPerfect(plan),
                aiPlan = plan.meta?.source == org.simpmusic.dj.model.PlanSource.AI_MIX,
            )
        }
    }

    /** The planner says so in its meta; the reason prefix only for plans made without one (a stand-in planner). */
    private fun isPerfect(plan: TransitionPlan): Boolean = plan.meta?.perfect ?: plan.reason.startsWith(PlanConstraints.PERFECT_OK)

    private fun stopMixView() {
        mixTicker?.cancel()
        mixTicker = null
        _mixView.value = null
    }

    /** ~10 Hz playhead from the wall clock since the adapter started the window (a picture, not a control: a few 10 ms of drift do not matter). */
    private fun startMixTicker(prepared: PreparedTransition) {
        mixTicker?.cancel()
        val t0 = System.nanoTime()
        // Where the mix is when it starts. A rendered window starts at its own sample 0. The real-time engine arms ~5 s
        // before the bus switch (BUS_CUE_LEAD_MS), which is far into the window's lead-in: the owner saw the picture run
        // seconds late ("it starts before the graph shows", 2026-10-16). There the outgoing's heard position says where.
        val startWindowMs = startWindowMs(prepared)
        mixTicker =
            scope.launch {
                while (isActive) {
                    val windowMs = startWindowMs + (System.nanoTime() - t0) / 1_000_000.0
                    if (windowMs > prepared.timeline.windowMs) break
                    _mixView.update { it?.withNow(prepared.timeline.planTimeOfWindow(windowMs)) }
                    delay(100)
                }
            }
    }

    /** Window time the mix is at when the adapter starts it: 0 for a rendered window, the heard outgoing position on the bus. */
    private fun startWindowMs(prepared: PreparedTransition): Double =
        if (prepared.viaBus) prepared.timeline.windowOfOutgoingSource(playbackPositionMs.toDouble()) else 0.0

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

/** With the real-time engine: the cue lead plus the plan's pre-roll (tempo ramp) plus margin. */
private const val BUS_EARLIEST_EXIT_AHEAD_MS = 16_000L

/** "Mix now": how far past the earliest possible exit the planner may look for a good phrase start. */
private const val MIX_NOW_SPAN_MS = 20_000L

/** "AI Mix": how long Claude's answer may take; exits before playhead + this + the usual lead are not offered. */
private const val AI_ANSWER_BUDGET_MS = 25_000L

/** "AI Mix" with tools: several turns (zoom, try, commit) take longer. */
private const val AI_TOOLS_BUDGET_MS = 60_000L

/** Above this tempo gap (half/double aware) Claude is not asked: the engine bends each deck at most 10 %. */
private const val AI_MAX_TEMPO_GAP = 0.12f

/** "AI Auto" solves this many upcoming mixes ahead (current+1 -> +2, ..., the pair ending 3 tracks ahead), see `aiPlanAhead`. */
internal const val AI_AHEAD_PAIRS = 3

/** A mix solved ahead leaves at least this long after its outgoing plays alone (the track is heard on its own a while). */
private const val AI_AHEAD_SOLO_MS = 30_000L

/** Below this much solo time an answer solved ahead no longer fits (the mix before it moved): it is asked again. */
private const val AI_AHEAD_MIN_SOLO_MS = 16_000L

/** How long an ahead question waits for its two analyses (they are NEXT_UP work, one track at a time). */
private const val AI_AHEAD_ANALYSIS_WAIT_MS = 6 * 60_000L

/** How many pairs the ahead book and its bookkeeping remember (old ones fall off: the queue moved on). */
private const val AI_AHEAD_MEMORY = 12

/** Below this, the chain limit on an entry is dropped (a short track: almost no entry would be left to choose from). */
private const val AI_CHAIN_MIN_ENTRY_ROOM_MS = 10_000L

/** The answers [DjEngine.answerBudgetMs] learns from, and the margin over their 90th percentile. */
private const val ANSWER_TIMES = 12
private const val ANSWER_MARGIN_MS = 8_000L

/** "Order with AI" orders this many upcoming tracks. */
private const val AI_ORDER_TRACKS = 10

/** The automatic order orders this many tracks after the ones solved ahead... */
private const val AUTO_ORDER_TRACKS = 8

/** ...when at least this many of them were never ordered by Claude (new radio tracks)... */
private const val AUTO_ORDER_MIN_NEW = 3

/** ...and at most once in this long (each order is one cheap question: ~3k tokens in). */
private const val AUTO_ORDER_GAP_MS = 120_000L

/** How long one move of the reorder may take to show in the queue. */
private const val ORDER_STEP_WAIT_MS = 2_000L

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
