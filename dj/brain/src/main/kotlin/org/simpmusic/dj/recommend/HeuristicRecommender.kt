package org.simpmusic.dj.recommend

import org.simpmusic.dj.analysis.AnalysisRefiner

import org.simpmusic.dj.model.*
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Weights of each component of the "how well does B follow A" score. They are normalised, only ratios matter. */
data class RecommenderWeights(
    val harmony: Float = 0.28f,
    val tempo: Float = 0.24f,
    val energy: Float = 0.14f,
    val timbre: Float = 0.10f,
    val transition: Float = 0.18f,
    val loudness: Float = 0.06f,
)

/**
 * Per-request context. [energyTrend] in -1..1: 0 keeps the floor steady, +1 asks for a build (next intro hotter than
 * this outro), -1 asks for a cool-down. [history] are ids to skip (recently played).
 */
data class RecommendContext(
    val energyTrend: Float = 0f,
    val history: Set<String> = emptySet(),
)

/**
 * Ranks candidate tracks by how well they mix after [current]: harmonic compatibility (Camelot, allowing a small pitch
 * bridge), tempo reachable within the bend limit (half/double aware), energy flow from this outro into the candidate's
 * intro, timbre continuity, loudness and — when a [planner] is supplied — how good the transition it would build is.
 */
class HeuristicRecommender(
    private val planner: TransitionPlanner? = null,
    private val weights: RecommenderWeights = RecommenderWeights(),
    private val context: RecommendContext = RecommendContext(),
) : TrackRecommender {

    fun withContext(context: RecommendContext) = HeuristicRecommender(planner, weights, context)

    override fun recommend(current: TrackAnalysis, candidates: List<TrackAnalysis>, settings: DjSettings, limit: Int): List<Recommendation> =
        candidates.asSequence()
            .filter { it.videoId != current.videoId && it.videoId !in context.history }
            .map { score(current, it, settings) }
            .filter { it.score > 0f }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()

    /** Score of playing [next] after [current], 0..1, with a per-component breakdown. Public: used by [SetBuilder]. */
    fun score(currentRaw: TrackAnalysis, nextRaw: TrackAnalysis, settings: DjSettings): Recommendation {
        // the tempo term reads the bpm field, which a broken grid gets wrong by a whole subdivision (see GridRepair)
        val current = AnalysisRefiner.cached(currentRaw)
        val next = AnalysisRefiner.cached(nextRaw)
        val parts = LinkedHashMap<String, Float>()
        val why = ArrayList<String>()

        val harmony = harmony(current.key, next.key, settings, why)
        val tempo = tempo(current.bpm, next.bpm, settings, why)
        val energy = energy(current, next, context.energyTrend, why)
        val timbre = timbre(current.timbre, next.timbre)
        val loud = 1f - min(1f, abs(current.loudnessDb - next.loudnessDb) / 12f)

        var plan: TransitionPlan? = null
        var transition = 0.5f
        val pl = planner
        if (pl != null) {
            plan = runCatching { pl.plan(current, next, settings) }.getOrNull()
            if (plan != null) {
                transition = when (plan.kind) {
                    PlanKind.BEAT_MATCHED -> 0.6f + 0.4f * plan.confidence
                    PlanKind.CUT, PlanKind.ECHO_OUT -> 0.45f
                    PlanKind.SIMPLE_CROSSFADE -> 0.25f
                }
                why += "transition ${plan.kind.name.lowercase()}"
            }
        }

        parts["harmony"] = harmony
        parts["tempo"] = tempo
        parts["energy"] = energy
        parts["timbre"] = timbre
        parts["loudness"] = loud
        parts["transition"] = transition

        val w = weights
        val sum = w.harmony + w.tempo + w.energy + w.timbre + w.transition + w.loudness
        var s = (harmony * w.harmony + tempo * w.tempo + energy * w.energy + timbre * w.timbre + transition * w.transition + loud * w.loudness) / sum
        // A hard tempo or key clash cannot be rescued by the other terms.
        if (tempo == 0f) s *= 0.35f
        if (harmony < 0.1f) s *= 0.6f
        return Recommendation(next.videoId, s.coerceIn(0f, 1f), plan, parts, why.joinToString(", "))
    }

    private fun harmony(a: Confident<MusicalKey>?, b: Confident<MusicalKey>?, s: DjSettings, why: MutableList<String>): Float {
        if (a == null || b == null || min(a.confidence, b.confidence) < Trust.KEY) {
            why += "key unknown"
            return 0.5f // neutral: do not punish what we cannot measure
        }
        val base = distanceScore(Camelot.distance(a.value, b.value))
        var best = base
        if (s.allowKeyShift) {
            for (shift in 1..s.maxPitchShift) for (sign in intArrayOf(1, -1)) {
                val shifted = MusicalKey(((a.value.pitchClass + sign * shift) % 12 + 12) % 12, a.value.mode)
                best = max(best, distanceScore(Camelot.distance(shifted, b.value)) - 0.08f * shift)
            }
        }
        why += "${a.value.camelot()}→${b.value.camelot()}"
        return best.coerceIn(0f, 1f) * (0.6f + 0.4f * min(a.confidence, b.confidence))
    }

    private fun distanceScore(d: Int) = when (d) {
        0 -> 1f
        1 -> 0.9f
        2 -> 0.5f
        3 -> 0.22f
        else -> 0.05f
    }

    private fun tempo(a: Confident<Float>?, b: Confident<Float>?, s: DjSettings, why: MutableList<String>): Float {
        if (a == null || b == null || min(a.confidence, b.confidence) < Trust.BPM) {
            why += "bpm unknown"
            return 0.4f
        }
        val bend = TempoMath.minBend(a.value, b.value)
        why += "%.0f→%.0f BPM".format(a.value, b.value)
        if (bend > s.maxTempoBend) return 0f
        val sigma = max(0.005f, s.maxTempoBend * 0.55f)
        return exp(-(bend / sigma) * (bend / sigma)).toFloat()
    }

    private fun energy(a: TrackAnalysis, b: TrackAnalysis, trend: Float, why: MutableList<String>): Float {
        val outro = edgeEnergy(a, atEnd = true) ?: return 0.5f
        val intro = edgeEnergy(b, atEnd = false) ?: return 0.5f
        // Preferred change: 0 for a steady floor; trend shifts it by up to +-0.2.
        val wanted = trend * 0.2f
        val delta = intro - outro
        why += "energy %.2f→%.2f".format(outro, intro)
        return (1f - min(1f, abs(delta - wanted) / 0.5f)).coerceIn(0f, 1f)
    }

    /** Mean energy of the last (or first) 20 % of the track, which is what actually overlaps in a mix. */
    private fun edgeEnergy(t: TrackAnalysis, atEnd: Boolean): Float? {
        val e = t.energy
        if (e.size < 10) return null
        val n = max(4, e.size / 5)
        val range = if (atEnd) e.size - n until e.size else 0 until n
        var sum = 0f
        for (i in range) sum += e[i]
        return sum / n
    }

    private fun timbre(a: List<Float>, b: List<Float>): Float {
        if (a.size < 2 || a.size != b.size) return 0.5f
        // cosine over coefficients 1.. (0 is level); mapped from -1..1 to 0..1 with a soft floor
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in 1 until a.size) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na <= 1e-9 || nb <= 1e-9) return 0.5f
        val cos = dot / sqrt(na * nb)
        return ((cos + 1.0) / 2.0).toFloat().coerceIn(0f, 1f)
    }
}

object TempoMath {
    /** Smallest relative tempo bend needed to lock [a] to [b] considering half/double-time (0.05 = 5 %). */
    fun minBend(a: Float, b: Float): Float {
        if (a <= 0f || b <= 0f) return Float.MAX_VALUE
        var best = Float.MAX_VALUE
        // Half/double-time is a real option but slightly worse than a straight match: the groove changes feel.
        for (m in floatArrayOf(0.5f, 1f, 2f)) best = min(best, abs(b * m / a - 1f) + if (m == 1f) 0f else 0.015f)
        return best
    }
}
