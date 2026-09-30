package org.simpmusic.dj.recommend

import org.simpmusic.dj.model.DjSettings
import org.simpmusic.dj.model.TrackAnalysis

/**
 * Chains tracks into a set: beam search over [HeuristicRecommender.score] so a locally best next track does not paint the
 * set into a corner (e.g. burning the only compatible key early). [arc] shapes energy over the set, one value per slot in
 * -1..1 (see [RecommendContext.energyTrend]); when shorter than the set it is repeated from its last value.
 */
class SetBuilder(private val recommender: HeuristicRecommender, private val beamWidth: Int = 8) {
    data class Result(val tracks: List<TrackAnalysis>, val totalScore: Float, val stepScores: List<Float>)

    fun build(seed: TrackAnalysis, pool: List<TrackAnalysis>, length: Int, settings: DjSettings, arc: List<Float> = emptyList()): Result {
        class Beam(val tracks: List<TrackAnalysis>, val used: Set<String>, val scores: List<Float>) {
            val total: Float get() = scores.sum()
        }
        var beams = listOf(Beam(listOf(seed), setOf(seed.videoId), emptyList()))
        for (step in 0 until length) {
            val trend = if (arc.isEmpty()) 0f else arc[minOf(step, arc.size - 1)]
            val rec = recommender.withContext(RecommendContext(energyTrend = trend, history = emptySet()))
            val next = ArrayList<Beam>()
            for (b in beams) {
                val cur = b.tracks.last()
                val scored = pool.asSequence()
                    .filter { it.videoId !in b.used }
                    .map { it to rec.score(cur, it, settings).score }
                    .sortedByDescending { it.second }
                    .take(beamWidth)
                for ((t, s) in scored) next += Beam(b.tracks + t, b.used + t.videoId, b.scores + s)
            }
            if (next.isEmpty()) break
            beams = next.sortedByDescending { it.total }.take(beamWidth)
        }
        val best = beams.maxByOrNull { it.total }!!
        return Result(best.tracks.drop(1), best.total, best.scores)
    }
}
