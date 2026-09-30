package org.simpmusic.dj.model

import kotlinx.coroutines.flow.Flow

/** Mono PCM handed to the analyzer. Sample values are in -1..1. */
class PcmAudio(val samples: FloatArray, val sampleRate: Int) {
    val durationMs: Long get() = (samples.size * 1000L) / sampleRate
}

/** Produces a [TrackAnalysis] from decoded audio. Pure computation, safe to run on a background thread. */
interface TrackAnalyzer {
    val id: String
    fun analyze(videoId: String, audio: PcmAudio): TrackAnalysis
}

/** Picks how a transition between two tracks is done. Pure function: no I/O, no Android. */
interface TransitionPlanner {
    fun plan(from: TrackAnalysis?, to: TrackAnalysis?, settings: DjSettings): TransitionPlan

    /** Same with the caller's playback constraints. The default ignores them (old implementers keep compiling). */
    fun plan(from: TrackAnalysis?, to: TrackAnalysis?, settings: DjSettings, constraints: PlanConstraints): TransitionPlan =
        plan(from, to, settings)
}

enum class AnalysisPriority { NOW_PLAYING, NEXT_UP, BACKGROUND }

/** Where analyses live and how they get computed. */
interface TrackAnalysisRepository {
    suspend fun get(videoId: String): TrackAnalysis?
    fun observe(videoId: String): Flow<TrackAnalysis?>

    /** Ask for an analysis to be computed in the background (no-op when a fresh one is stored). */
    suspend fun request(videoId: String, priority: AnalysisPriority)
}

/** Next-track recommendation. */
data class Recommendation(
    val videoId: String,
    val score: Float,
    val plan: TransitionPlan?,
    /** Why it ranked here, per component (key / bpm / energy / timbre ...). */
    val breakdown: Map<String, Float>,
    val reason: String,
)

interface TrackRecommender {
    fun recommend(current: TrackAnalysis, candidates: List<TrackAnalysis>, settings: DjSettings, limit: Int = 10): List<Recommendation>
}
