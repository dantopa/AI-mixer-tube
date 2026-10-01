package org.simpmusic.dj.android.auto

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.simpmusic.dj.android.recommend.DjPlayerPort
import org.simpmusic.dj.android.recommend.PlayerSnapshot
import org.simpmusic.dj.model.AnalysisPriority
import org.simpmusic.dj.model.DjSettings
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.model.TrackAnalysisRepository
import org.simpmusic.dj.model.TransitionPlanner
import org.simpmusic.dj.recommend.HeuristicRecommender
import org.simpmusic.dj.recommend.RecommendContext

/**
 * Picks, among the next few tracks the radio already queued, the one that mixes best after the current track, and moves it
 * into the "next" slot. It only REORDERS tracks that are already in the queue: nothing is added or removed, so the radio's
 * own queue (endless extension, trim) is untouched.
 *
 * Cheap on purpose: only [WINDOW] tracks after the current one, analysed one after another (never in parallel) and only
 * once, after the current and the next track are already analysed, so it never delays the analyses the mix itself needs.
 *
 * Only on a YouTube radio queue: a playlist or an album has an order its owner chose, and is left alone, as is a track
 * somebody put in the next slot by hand or the DJ itself. Active while AI DJ and Auto DJ are on.
 */
class QueueLookAhead(
    private val scope: CoroutineScope,
    private val settings: StateFlow<DjSettings>,
    private val port: DjPlayerPort,
    private val analyses: TrackAnalysisRepository,
    private val planner: TransitionPlanner,
    private val setKeep: (Set<String>) -> Unit,
    private val log: (String) -> Unit = {},
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val perTrackWaitMs: Long = 100_000L,
    private val budgetMs: Long = 180_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var decidedFor: String? = null
    private var prevQueue: List<String> = emptyList()
    private val handPlaced = LinkedHashSet<String>()

    fun start() {
        scope.launch {
            settings.map { it.enabled && it.autoDj }.distinctUntilChanged().collectLatest { on ->
                if (on) {
                    try {
                        port.snapshots.collectLatest { handle(it) }
                    } finally {
                        setKeep(emptySet())
                    }
                }
            }
        }
    }

    internal suspend fun handle(s: PlayerSnapshot) {
        val current = s.currentId ?: return
        trackInsertions(s)
        if (s.blockedReason != null || !s.isRadio || s.currentIndex < 0) {
            setKeep(emptySet())
            return
        }
        val window = s.queueIds.drop(s.currentIndex + 1).take(WINDOW)
        if (window.size < 2) return
        if (decidedFor == current) return
        if (window.first() in handPlaced) {
            decidedFor = current
            log("look-ahead: the next track was put there on purpose; leaving it")
            return
        }
        setKeep(window.toSet())

        val from = analysisOf(current, AnalysisPriority.NOW_PLAYING, perTrackWaitMs * 2) ?: return
        val deadline = clock() + budgetMs
        val got = LinkedHashMap<String, TrackAnalysis>()
        for (id in window) {
            val left = deadline - clock()
            if (left <= 0) break
            val a = analysisOf(id, AnalysisPriority.NEXT_UP, minOf(perTrackWaitMs, left))
            if (a != null) got[id] = a
            // the first candidate is the one that plays anyway: nothing to compare until it is known, but do not stall on it
            if (id == window.first() && a == null) break
        }
        if (got.size < 2) {
            log("look-ahead: only ${got.size} of ${window.size} upcoming tracks analysed, keeping the order")
            decidedFor = current
            return
        }
        val settingsNow = settings.value
        val played = s.playedBefore(HISTORY).toSet()
        val scored =
            withContext(compute) {
                val rec = HeuristicRecommender(planner, context = RecommendContext(history = played))
                got.map { (id, a) -> id to rec.score(from, a, settingsNow) }
            }
        val nextId = window.first()
        val nextScore = scored.firstOrNull { it.first == nextId }?.second?.score ?: 0f
        val best = scored.maxByOrNull { it.second.score }!!
        val bestId = best.first
        val line = scored.joinToString { "${it.first}=${"%.2f".format(it.second.score)}" }
        decidedFor = current
        if (bestId == nextId || best.second.score < nextScore + MIN_GAIN || best.second.score < MIN_SCORE) {
            log("look-ahead: keeping $nextId next (scores $line)")
            return
        }
        val moved = withContext(NonCancellable) { port.moveToNext(bestId) }
        log(
            if (moved) "look-ahead: moved $bestId next, ahead of $nextId: ${"%.2f".format(best.second.score)} vs ${"%.2f".format(nextScore)} (scores $line) — ${best.second.reason}"
            else "look-ahead: could not move $bestId up (scores $line)",
        )
    }

    /** A single track appearing right after the current one nobody here put there is somebody's deliberate choice. */
    private fun trackInsertions(s: PlayerSnapshot) {
        val prev = prevQueue
        prevQueue = s.queueIds
        if (prev.isEmpty() || s.queueIds.size != prev.size + 1 || s.currentIndex < 0) return
        val slot = s.queueIds.getOrNull(s.currentIndex + 1) ?: return
        if (slot !in prev) {
            handPlaced += slot
            while (handPlaced.size > 100) handPlaced.remove(handPlaced.first())
        }
    }

    private suspend fun analysisOf(id: String, priority: AnalysisPriority, waitMs: Long): TrackAnalysis? {
        analyses.get(id)?.let { return it }
        analyses.request(id, priority)
        return withTimeoutOrNull(waitMs) { analyses.observe(id).first { it != null } }
    }

    companion object {
        /** Tracks after the current one that are considered (the next one included). */
        const val WINDOW = 4
        const val HISTORY = 30

        /** The challenger must beat the queued next track by this much, or the order stays. */
        const val MIN_GAIN = 0.08f
        const val MIN_SCORE = 0.30f
    }
}
