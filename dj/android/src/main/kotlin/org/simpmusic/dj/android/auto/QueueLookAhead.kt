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
import org.simpmusic.dj.analysis.Harmony
import org.simpmusic.dj.analysis.VocalClash
import org.simpmusic.dj.android.recommend.DjPlayerPort
import org.simpmusic.dj.android.recommend.PlayerSnapshot
import org.simpmusic.dj.model.AnalysisPriority
import org.simpmusic.dj.model.DjSettings
import org.simpmusic.dj.model.MixQuality
import org.simpmusic.dj.model.PlanKind
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.model.TrackAnalysisRepository
import org.simpmusic.dj.model.TransitionPlan
import org.simpmusic.dj.model.TransitionPlanner
import org.simpmusic.dj.planner.PlanTags
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
    /** How many upcoming tracks to order; the module passes a smaller window when the phone is warm (each is a full analysis). */
    private val windowSize: () -> Int = { WINDOW },
) {
    private var decidedFor: String? = null
    private var prevQueue: List<String> = emptyList()
    private val handPlaced = LinkedHashSet<String>()

    fun start() {
        scope.launch {
            // AI DJ alone turns it on (not Auto DJ): ordering the radio's own tracks so they mix is the DJ's job
            settings.map { it.enabled }.distinctUntilChanged().collectLatest { on ->
                if (on) {
                    try {
                        // only what the decision depends on restarts it: a decision cancelled by an unrelated emission never
                        // finished on the owner's device, and the 97 -> 125 bpm pair it should have avoided was played
                        port.snapshots.distinctUntilChanged { a, b -> keyOf(a) == keyOf(b) }.collectLatest { handle(it) }
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
        val window = s.queueIds.drop(s.currentIndex + 1).take(windowSize().coerceIn(2, org.simpmusic.dj.recommend.SetOrder.MAX_TRACKS))
        if (window.size < 2) return
        if (decidedFor == current) return
        if (window.first() in org.simpmusic.dj.android.ai.ClaudeOrder.ids) {
            decidedFor = current
            log("look-ahead: Claude ordered this list; leaving it")
            return
        }
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
        if (nextPlan == null || nextPlan.quality() == Quality.BEST) {
            decidedFor = current
            log("look-ahead: $nextId mixes instrumental over instrumental with the current track (or could not be planned); not analysing the others")
            return
        }
        val nextQuality = nextPlan.quality()
        // YouTube's radio already picks tracks in the same style (the owner: "YouTube Music has an AI that finds tracks of the
        // same vibe; use it"), so the DJ only chooses AMONG them: the one whose instrumental lands on the current track's.
        val nextWhy = "mixes only as ${nextQuality.label}"
        log("look-ahead: $nextId $nextWhy (${nextPlan.reason}); looking at ${window.size - 1} more")
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
        // The set, not just the next slot: the tempo path over the whole window. Greedy "best next" only pushed an
        // off-tempo track one slot down and it came straight back (owner's radio: 97 -> 125 -> 97 -> 122).
        val bend = settingsNow.maxTempoBend.toDouble().coerceAtLeast(0.02)
        val curBpm = bpmOf(from)
        val tracks = got.mapNotNull { (id, a) -> bpmOf(a)?.let { org.simpmusic.dj.recommend.SetOrder.Track(id, it) } }
        val qualityOf = scored.associate { (id, r) -> id to (r.plan?.quality() ?: Quality.OTHER) }
        // jumps of the best path that plays [id] next; a first step the planner cannot beat-match counts as one, tempo or not
        fun jumpsIf(id: String): Int? {
            if (curBpm == null || tracks.size != got.size) return null
            val p = org.simpmusic.dj.recommend.SetOrder.best(curBpm, tracks, bend, first = id) ?: return null
            val firstTempoJump = org.simpmusic.dj.recommend.SetOrder.isJump(curBpm, tracks.first { it.id == id }.bpm, bend)
            return p.jumps - (if (firstTempoJump) 1 else 0) + (if (qualityOf[id] == Quality.OTHER) 1 else 0)
        }
        val nextJumps = jumpsIf(nextId)
        curBpm?.let { c ->
            org.simpmusic.dj.recommend.SetOrder.best(c, tracks, bend)?.let { p ->
                log("look-ahead: tempo order ${p.ids.joinToString(" > ") { id -> "%.0f".format(tracks.first { it.id == id }.bpm) }} (${p.jumps} jumps; the radio's order ${org.simpmusic.dj.recommend.SetOrder.jumpsInOrder(c, tracks, bend)})")
            }
        }
        // A challenger must beat-match the current track and clear the floor. It takes the slot when its path has fewer
        // tempo jumps than the queued next's, or as many and a better tier (instrumental over instrumental > the outgoing's
        // instrumental under the incoming's phrase 1 > a clean beat-matched crossfade > anything else); then the score.
        // Never a reorder for a tie.
        val best =
            scored
                .filter { it.first != nextId && it.second.score >= MIN_SCORE }
                .mapNotNull { (id, r) -> r.plan?.quality()?.takeIf { it > Quality.OTHER }?.let { Triple(id, r, it) } }
                .map { t -> t to jumpsIf(t.first) }
                .filter { (t, j) ->
                    when {
                        j != null && nextJumps != null && j < nextJumps -> true
                        j != null && nextJumps != null && j > nextJumps -> false
                        else -> t.third > nextQuality
                    }
                }
                .minWithOrNull(compareBy<Pair<Triple<String, org.simpmusic.dj.model.Recommendation, Quality>, Int?>> { it.second ?: Int.MAX_VALUE }.thenByDescending { it.first.third }.thenByDescending { it.first.second.score })
                ?.first?.let { it.first to it.second }
        if (best == null) {
            log("look-ahead: no upcoming track mixes better than $nextId ($nextWhy); keeping the order (scores $line)")
            return
        }
        val bestId = best.first
        val moved = withContext(NonCancellable) { port.moveToNext(bestId) }
        log(
            if (moved) "look-ahead: moved $bestId next, ahead of $nextId (which $nextWhy): ${"%.2f".format(best.second.score)} vs ${"%.2f".format(nextScore)} (scores $line) — ${best.second.reason}"
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

    /** Beat-matched with no harmonic clash over the chosen overlap. */
    /** How well a pair mixes, best last (declaration order is the ranking). */
    internal enum class Quality(val label: String) {
        OTHER("no clean beat-matched mix"),
        CLEAN("a beat-matched crossfade"),
        TAIL("the outgoing's instrumental under the incoming's phrase 1"),
        BEST("instrumental over instrumental"),
    }

    private fun TransitionPlan.quality(): Quality {
        // the planner says it as data; the reason text is read only for plans made without it
        meta?.let { m ->
            return when (m.quality) {
                MixQuality.OTHER -> Quality.OTHER
                MixQuality.CLEAN -> Quality.CLEAN
                MixQuality.TAIL -> Quality.TAIL
                MixQuality.BEST -> Quality.BEST
            }
        }
        val own = reason.substringBefore(" | ")
        return when {
            kind == PlanKind.BEAT_MATCHED && own.startsWith(org.simpmusic.dj.planner.MixMarks.TAG) -> Quality.BEST
            kind != PlanKind.BEAT_MATCHED || Harmony.CLASH_TAG in own || VocalClash.CLASH_TAG in own -> Quality.OTHER
            PlanTags.STRUCTURE in own -> if (PlanTags.SECOND_TIER in own) Quality.TAIL else Quality.BEST
            else -> Quality.CLEAN
        }
    }

    /** Tempo of the refined grid (what the planner beat-matches), else the analyzer's field. */
    private fun bpmOf(a: TrackAnalysis): Double? =
        (runCatching { org.simpmusic.dj.timeline.Timelines.of(a).bpm?.value }.getOrNull() ?: a.bpm?.value)?.toDouble()?.takeIf { it > 0 }

    /** What a decision depends on: the current track, the guards, the upcoming tracks it may order. */
    private fun keyOf(s: PlayerSnapshot) =
        listOf(s.currentId, s.currentIndex, s.isRadio, s.blockedReason, s.queueIds.drop(s.currentIndex + 1).take(windowSize().coerceIn(2, org.simpmusic.dj.recommend.SetOrder.MAX_TRACKS)))

    private suspend fun analysisOf(id: String, priority: AnalysisPriority, waitMs: Long): TrackAnalysis? {
        analyses.get(id)?.let { return it }
        analyses.request(id, priority)
        return withTimeoutOrNull(waitMs) { analyses.observe(id).first { it != null } }
    }

    companion object {
        /** Tracks after the current one that are considered (the next one included). */
        // Each challenger is a full analysis (~30 s of CPU), but they are only analysed when the queued next would not
        // beat-match, stay in the store (a queued track is analysed once, whenever it is played), and six give the tempo
        // path room to group an off-tempo track with its neighbours instead of bouncing (SetOrder).
        const val WINDOW = 6

        /** The window while the phone is warm (thermal MODERATE or worse). */
        const val WARM_WINDOW = 4
        const val HISTORY = 30

        /** Floor a challenger must clear to take the next slot from a queued track that would not beat-match. */
        const val MIN_SCORE = 0.30f
    }
}
