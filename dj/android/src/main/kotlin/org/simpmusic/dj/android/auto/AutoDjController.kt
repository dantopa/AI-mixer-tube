package org.simpmusic.dj.android.auto

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.simpmusic.dj.android.library.LibrarySource
import org.simpmusic.dj.android.recommend.AnalysisPool
import org.simpmusic.dj.android.recommend.DjPlayerPort
import org.simpmusic.dj.android.recommend.DjRanking
import org.simpmusic.dj.android.recommend.PlayerSnapshot
import org.simpmusic.dj.model.AnalysisPriority
import org.simpmusic.dj.model.DjSettings
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.model.TrackAnalysisRepository
import org.simpmusic.dj.model.TransitionPlanner

/** What Auto DJ did (or why it did nothing) at one moment. The settings screen shows the last one. */
data class AutoDjDecision(
    val atMs: Long,
    /** The track the decision was made after (the playing one, or the last queued one when appending). */
    val anchorId: String?,
    val pickedId: String?,
    val pickedTitle: String?,
    val action: Action,
    val reason: String,
    val score: Float? = null,
) {
    enum class Action {
        /** Put right after the current track (radio queues). */
        INSERT_NEXT,

        /** Added at the end of the queue (playlists, albums and other finite lists about to run out). */
        APPEND,

        /** Left the queue alone; [reason] says why and the app's own queue carries on. */
        SKIPPED,
    }

    fun summary(): String =
        when (action) {
            Action.SKIPPED -> "Auto DJ idle — $reason"
            else -> "${if (action == Action.INSERT_NEXT) "Next" else "Queued"}: ${pickedTitle ?: pickedId}" + (score?.let { " (score ${"%.2f".format(it)})" } ?: "") + " — $reason"
        }
}

/**
 * Auto DJ: keeps the queue fed with tracks chosen by the DJ brain instead of by the app's radio.
 *
 * **Precedence with the app's own Endless queue / radio** (the one rule that keeps the two from fighting):
 *
 *  1. The DJ only ever ADDS tracks, through the player's own "play next" / "add to queue". It never removes or reorders
 *     anything, so the radio's queue is always intact underneath it.
 *  2. **Radio queue** (a YouTube `RD…` radio): the DJ adds nothing. YouTube's radio picks tracks of the same style far
 *     better than tempo and energy can; `QueueLookAhead` only reorders the radio's own next tracks for the best mix.
 *     (Until 2026-10-08 the DJ inserted its own pick as "play next" here, and filled cumbia radios with ~85 BPM reggaeton.)
 *  3. **Finite queue** (playlist, album, local list, favourites...): untouched until it is about to run out
 *     (`tracksAhead <= APPEND_WHEN_AHEAD`), then the DJ APPENDS. The app's endless queue only reacts at 1 track left, the
 *     DJ acts at 2, so with the DJ succeeding the radio is never asked for more.
 *  4. **The DJ decides only when it can**: the analysed pool must hold at least [minPool] other tracks, the anchor track
 *     must be analysed, and the best follow-up must clear [DjRanking.MIN_AUTO_SCORE]. Otherwise it does nothing and the
 *     normal radio / endless queue carries on, so a small or cold library never leaves the queue empty.
 *  5. Never a track from the last [HISTORY] played, from the DJ's own last picks, or already in the queue.
 *  6. Off in every case the transition engine is off in: casting, Listen Together, repeat one, video; plus repeat all
 *     (the queue never runs out) and shuffle (the order is not the queue's own).
 *
 * Only active while both AI DJ mode and Auto DJ are on.
 */
class AutoDjController(
    private val scope: CoroutineScope,
    private val settings: StateFlow<DjSettings>,
    private val port: DjPlayerPort,
    private val pool: AnalysisPool,
    private val analyses: TrackAnalysisRepository,
    private val source: LibrarySource,
    private val planner: TransitionPlanner,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val minPool: Int = MIN_POOL,
    private val waitForAnalysisMs: Long = 90_000L,
) {
    private val _last = MutableStateFlow<AutoDjDecision?>(null)

    /** The most recent decision, for the settings screen. */
    val lastDecision: StateFlow<AutoDjDecision?> = _last.asStateFlow()

    private val _recent = MutableStateFlow<List<AutoDjDecision>>(emptyList())

    /** The last [LOG_SIZE] decisions, oldest first. */
    val recentDecisions: StateFlow<List<AutoDjDecision>> = _recent.asStateFlow()

    /** Ids the DJ itself queued (a radio's "next" slot that already holds one needs nothing). */
    private val owned = LinkedHashSet<String>()

    /** Ids the user queued by hand next to the current track: the DJ never jumps over these. */
    private val respected = LinkedHashSet<String>()

    /** The DJ's own last picks, newest last. */
    private val history = ArrayDeque<String>()
    private var picks = 0
    private var lastKey: String? = null
    private var lastBlockedReason: String? = null
    private var prevQueue: List<String> = emptyList()
    private var pendingOwn: String? = null

    fun start() {
        scope.launch {
            settings.map { it.enabled && it.autoDj }.distinctUntilChanged().collectLatest { on ->
                if (on) {
                    lastKey = null
                    port.snapshots.collectLatest { handle(it) }
                }
            }
        }
    }

    internal suspend fun handle(s: PlayerSnapshot) {
        val current = s.currentId ?: return
        trackUserInsertions(s)
        val blocked = s.blockedReason
        if (blocked != null) {
            if (blocked != lastBlockedReason) {
                lastBlockedReason = blocked
                record(AutoDjDecision(clock(), current, null, null, AutoDjDecision.Action.SKIPPED, "off while $blocked"))
            }
            return
        }
        lastBlockedReason = null
        if (s.isRadio) {
            // YouTube's radio already chooses tracks of the same style, and much better than tempo and energy can (build an
            // inserted Ozuna and a reggaeton remix into a cumbia radio because they were all ~85 BPM). The DJ only reorders
            // the radio's own upcoming tracks (QueueLookAhead) and never adds its own here.
            val key = "radio|$current"
            if (key != lastKey) {
                lastKey = key
                record(AutoDjDecision(clock(), current, null, null, AutoDjDecision.Action.SKIPPED, "YouTube's radio picks the style; the DJ only reorders its next tracks"))
            }
            return
        }
        val mode = AutoDjDecision.Action.APPEND
        when (mode) {
            AutoDjDecision.Action.APPEND -> if (s.tracksAhead > APPEND_WHEN_AHEAD) return
            else -> {
                val next = s.queueIds.getOrNull(s.currentIndex + 1)
                if (next != null && (next in owned || next in respected)) return
            }
        }
        val anchorId = if (mode == AutoDjDecision.Action.APPEND) s.queueIds.lastOrNull() ?: current else current
        val key = "$anchorId|${s.queueIds.size}|$mode"
        if (key == lastKey) return

        val anchor = analysisOf(anchorId, if (anchorId == current) AnalysisPriority.NOW_PLAYING else AnalysisPriority.NEXT_UP)
        if (anchor == null) {
            record(AutoDjDecision(clock(), anchorId, null, null, AutoDjDecision.Action.SKIPPED, "track not analysed yet"))
            return // no key: the next snapshot tries again
        }
        val entries = pool.snapshot()
        if (entries.size < minPool) {
            lastKey = key
            record(AutoDjDecision(clock(), anchorId, null, null, AutoDjDecision.Action.SKIPPED, "only ${entries.size} of $minPool analysed tracks: the normal queue continues"))
            return
        }
        val excluded = excludedIds(s, anchorId)
        val candidates = entries.filter { it.analysis.videoId !in excluded }
        val settingsNow = settings.value
        val arc = List(DjRanking.LOOK_AHEAD) { settingsNow.autoDjArc.trendAt(picks + it) }
        val pick =
            withContext(compute) {
                DjRanking.pickNext(anchor, candidates.map { it.analysis }, settingsNow, planner, arc)
            }
        lastKey = key
        if (pick == null) {
            record(AutoDjDecision(clock(), anchorId, null, null, AutoDjDecision.Action.SKIPPED, "nothing fits well after this track (${candidates.size} candidates): the normal queue continues"))
            return
        }
        val entry = candidates.first { it.analysis.videoId == pick.recommendation.videoId }
        val id = entry.track.videoId
        pendingOwn = id
        // The bookkeeping lives inside the non-cancellable block too: a newer snapshot cancels this handler as soon as the
        // queue changes, and a cancellation raised on the way out must not lose the fact that this track is the DJ's.
        withContext(NonCancellable) {
            when (mode) {
                AutoDjDecision.Action.APPEND -> port.append(entry.track.track)
                else -> port.playNext(entry.track.track)
            }
            owned += id
            while (owned.size > 200) owned.remove(owned.first())
            history.addLast(id)
            while (history.size > HISTORY) history.removeFirst()
            picks++
            val how = if (pick.viaBeamSearch) "beam search over ${pick.poolSize}" else "best of ${pick.poolSize}"
            record(AutoDjDecision(clock(), anchorId, id, entry.track.title, mode, "$how: ${pick.recommendation.reason}", pick.recommendation.score))
        }
    }

    /** A queue that grew by exactly one track nobody here queued is the user's own "play next"/"add to queue". */
    private fun trackUserInsertions(s: PlayerSnapshot) {
        val prev = prevQueue
        prevQueue = s.queueIds
        if (prev.isEmpty() || s.queueIds.size != prev.size + 1) return
        val counts = HashMap<String, Int>()
        for (id in prev) counts.merge(id, 1, Int::plus)
        val added = s.queueIds.firstOrNull { id -> (counts[id] ?: 0).let { c -> if (c > 0) { counts[id] = c - 1; false } else true } } ?: return
        if (added == pendingOwn || added in owned) {
            pendingOwn = null
            return
        }
        respected += added
        while (respected.size > 200) respected.remove(respected.first())
    }

    private suspend fun excludedIds(s: PlayerSnapshot, anchorId: String): Set<String> =
        buildSet {
            add(anchorId)
            s.currentId?.let { add(it) }
            addAll(s.queueIds)
            addAll(history)
            addAll(source.recentlyPlayedIds(HISTORY))
        }

    /** The analysis of [id], requesting it and waiting (bounded, and cancelled by the next snapshot) when absent. */
    private suspend fun analysisOf(id: String, priority: AnalysisPriority): TrackAnalysis? {
        pool.analysisOf(id)?.let { return it }
        analyses.get(id)?.let { return it }
        analyses.request(id, priority)
        return withTimeoutOrNull(waitForAnalysisMs) { analyses.observe(id).first { it != null } }
    }

    private fun record(d: AutoDjDecision) {
        // Repeated idle lines for the same reason would flood the log.
        val prev = _last.value
        if (d.action == AutoDjDecision.Action.SKIPPED && prev?.action == AutoDjDecision.Action.SKIPPED && prev.reason == d.reason && prev.anchorId == d.anchorId) return
        _last.value = d
        _recent.update { (it + d).takeLast(LOG_SIZE) }
        log("auto dj: ${d.summary()}")
    }

    companion object {
        /** The DJ acts when this many tracks or fewer remain after the current one; the app's own endless queue waits for 1. */
        const val APPEND_WHEN_AHEAD = 2

        /** Analysed library tracks needed before the DJ takes over from the app's radio. */
        const val MIN_POOL = 8

        /** How far back a track may not repeat. */
        const val HISTORY = 30
        const val LOG_SIZE = 20
    }
}
