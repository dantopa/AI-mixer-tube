package org.simpmusic.dj.android.recommend

import org.simpmusic.dj.model.DjSettings
import org.simpmusic.dj.model.Recommendation
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.model.TransitionPlanner
import org.simpmusic.dj.recommend.HeuristicRecommender
import org.simpmusic.dj.recommend.RecommendContext
import org.simpmusic.dj.recommend.RecommenderWeights
import org.simpmusic.dj.recommend.SetBuilder

/** The autonomous choice: the pick, and how it was reached. */
data class AutoPick(
    val recommendation: Recommendation,
    /** True when the [SetBuilder] beam search proposed it (pool of at least [DjRanking.BEAM_MIN_POOL]). */
    val viaBeamSearch: Boolean,
    /** The beam's look-ahead path (ids, first = this pick) when [viaBeamSearch], else empty. */
    val lookAhead: List<String>,
    val poolSize: Int,
)

/**
 * Pure ranking on top of the brain's [HeuristicRecommender] / [SetBuilder]. Everything that costs is bounded: the cheap
 * score (no planner) runs over the whole pool, the expensive one (a real [TransitionPlanner] run per pair) only over the
 * shortlist, because the planner is what makes a score honest and also what makes it slow.
 */
object DjRanking {
    /** How many of the cheap ranking's best go on to the planner. */
    const val SHORTLIST = 40

    /** Below this many candidates a beam search has nothing to choose between: rank greedily. */
    const val BEAM_MIN_POOL = 8

    /** Beam look-ahead length (tracks). Only the first is used; the rest is what stops a dead end. */
    const val LOOK_AHEAD = 3

    const val BEAM_WIDTH = 6

    /** Below this score a pick is worse than what the app's own radio would do: do not take over. */
    const val MIN_AUTO_SCORE = 0.30f

    /** The beam's first step is kept unless the planner scores it below this share of the best shortlisted candidate. */
    const val BEAM_VETO_RATIO = 0.75f

    /** Top [limit] of [pool] to play after [current], plan quality included when [planner] is given. */
    fun rank(
        current: TrackAnalysis,
        pool: List<TrackAnalysis>,
        settings: DjSettings,
        planner: TransitionPlanner?,
        trend: Float = 0f,
        history: Set<String> = emptySet(),
        limit: Int = 10,
        weights: RecommenderWeights = RecommenderWeights(),
    ): List<Recommendation> {
        val context = RecommendContext(energyTrend = trend, history = history)
        val cheap = HeuristicRecommender(null, weights, context)
        val shortlist = cheap.recommend(current, pool, settings, SHORTLIST)
        if (planner == null) return shortlist.take(limit)
        val full = HeuristicRecommender(planner, weights, context)
        val byId = pool.associateBy { it.videoId }
        return shortlist
            .mapNotNull { byId[it.videoId] }
            .map { full.score(current, it, settings) }
            .filter { it.score > 0f }
            .sortedByDescending { it.score }
            .take(limit)
    }

    /**
     * The one track to play after [current] out of [pool] ([pool] must already exclude what must not repeat), or null
     * when nothing is good enough. With at least [BEAM_MIN_POOL] candidates a beam search over the cheap score looks
     * [LOOK_AHEAD] tracks ahead so the pick does not paint the set into a corner; the planner then re-scores the beam's
     * pick together with the greedy top few and vetoes a pick whose transition would be poor.
     */
    fun pickNext(
        current: TrackAnalysis,
        pool: List<TrackAnalysis>,
        settings: DjSettings,
        planner: TransitionPlanner?,
        arc: List<Float> = listOf(0f),
        history: Set<String> = emptySet(),
        weights: RecommenderWeights = RecommenderWeights(),
    ): AutoPick? {
        val candidates = pool.filter { it.videoId != current.videoId && it.videoId !in history }
        if (candidates.isEmpty()) return null
        val trend = arc.firstOrNull() ?: 0f
        val context = RecommendContext(energyTrend = trend, history = history)
        val cheap = HeuristicRecommender(null, weights, context)
        val byId = candidates.associateBy { it.videoId }

        var beamPick: TrackAnalysis? = null
        var lookAhead = emptyList<String>()
        if (candidates.size >= BEAM_MIN_POOL) {
            val built = SetBuilder(cheap, BEAM_WIDTH).build(current, candidates, LOOK_AHEAD, settings, arc)
            beamPick = built.tracks.firstOrNull()
            lookAhead = built.tracks.map { it.videoId }
        }

        val greedy = cheap.recommend(current, candidates, settings, 5).mapNotNull { byId[it.videoId] }
        val finalists = (listOfNotNull(beamPick) + greedy).distinctBy { it.videoId }
        if (finalists.isEmpty()) return null
        val full = HeuristicRecommender(planner, weights, context)
        val scored = finalists.map { it to full.score(current, it, settings) }
        val best = scored.maxByOrNull { it.second.score } ?: return null
        val beamScored = beamPick?.let { b -> scored.first { it.first.videoId == b.videoId } }
        val chosen =
            if (beamScored != null && beamScored.second.score >= best.second.score * BEAM_VETO_RATIO) beamScored else best
        if (chosen.second.score < MIN_AUTO_SCORE) return null
        return AutoPick(
            recommendation = chosen.second,
            viaBeamSearch = beamScored != null && chosen === beamScored,
            lookAhead = if (chosen === beamScored) lookAhead else emptyList(),
            poolSize = candidates.size,
        )
    }
}
