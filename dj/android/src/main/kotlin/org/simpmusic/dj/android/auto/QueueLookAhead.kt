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
import org.simpmusic.dj.model.PlanKind
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
 * Cheap on purpose: it only acts when the queued next track would NOT beat-match with the current one (tempos beyond the
 * bend, an unusable grid). Only then are the other [WINDOW] tracks analysed, one after another (never in parallel), after
 * the current and the next track, so it never delays the analyses the mix itself needs; and only a challenger that does
 * beat-match can take the slot.
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
        val from = analysisOf(current, AnalysisPriority.NOW_PLAYING, perTrackWaitMs * 2) ?: return
        val nextId = window.first()
        val settingsNow = settings.value
        val deadline = clock() + budgetMs
        // The queued next is analysed fully anyway (the mix needs it). Only when it CANNOT be beat-matched with the current
        // track are the other candidates worth a full analysis each (~30 s of CPU): most of the time the radio's own order
        // already mixes, and analysing two more tracks per song just to confirm it was the look-ahead's main heat cost.
        val next = analysisOf(nextId, AnalysisPriority.NEXT_UP, minOf(perTrackWaitMs, deadline - clock())) ?: return
        val nextPlan = withContext(compute) { runCatching { planner.plan(from, next, settingsNow) }.getOrNull() }
        if (nextPlan == null || nextPlan.kind == PlanKind.BEAT_MATCHED) {
            decidedFor = current
            log("look-ahead: $nextId mixes with the current track (${nextPlan?.kind}); not analysing the others")
            return
        }
        log("look-ahead: $nextId would not beat-match (${nextPlan.reason}); looking at ${window.size - 1} more")
        setKeep(window.toSet())

        val got = LinkedHashMap<String, TrackAnalysis>()
        got[nextId] = next
        for (id in window.drop(1)) {
            val left = deadline - clock()
            if (left <= 0) break
            val a = analysisOf(id, AnalysisPriority.NEXT_UP, minOf(perTrackWaitMs, left))
            if (a != null) got[id] = a
        }
        decidedFor = current
        if (got.size < 2) {
            log("look-ahead: only ${got.size} of ${window.size} upcoming tracks analysed, keeping the order")
            return
        }
        val played = s.playedBefore(HISTORY).toSet()
        val scored =
            withContext(compute) {
                val rec = HeuristicRecommender(planner, context = RecommendContext(history = played))
                got.map { (id, a) -> id to rec.score(from, a, settingsNow) }
            }
        val nextScore = scored.firstOrNull { it.first == nextId }?.second?.score ?: 0f
        val line = scored.joinToString { "${it.first}=${"%.2f".format(it.second.score)}" }
        // The queued next does not beat-match, so a challenger needs no score margin over it: it only has to beat-match
        // itself and clear the floor. A challenger that would not beat-match either is never worth a reorder.
        val best =
            scored
                .filter { it.first != nextId && it.second.plan?.kind == PlanKind.BEAT_MATCHED && it.second.score >= MIN_SCORE }
                .maxByOrNull { it.second.score }
        if (best == null) {
            log("look-ahead: no upcoming track beat-matches either; keeping $nextId next (scores $line)")
            return
        }
        val bestId = best.first
        val moved = withContext(NonCancellable) { port.moveToNext(bestId) }
        log(
            if (moved) "look-ahead: moved $bestId next, ahead of $nextId (which would not beat-match): ${"%.2f".format(best.second.score)} vs ${"%.2f".format(nextScore)} (scores $line) — ${best.second.reason}"
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
        // Each challenger is a full analysis (~30 s of CPU), but they are only analysed when the queued next would not
        // beat-match, and then a 4th candidate is one more chance to find a track that does.
        const val WINDOW = 4
        const val HISTORY = 30

        /** Floor a challenger must clear to take the next slot from a queued track that would not beat-match. */
        const val MIN_SCORE = 0.30f
    }
}
