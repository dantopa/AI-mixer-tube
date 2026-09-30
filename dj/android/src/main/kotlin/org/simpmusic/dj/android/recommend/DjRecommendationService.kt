package org.simpmusic.dj.android.recommend

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.simpmusic.dj.android.library.LibrarySource
import org.simpmusic.dj.android.library.LibraryTrack
import org.simpmusic.dj.model.AnalysisPriority
import org.simpmusic.dj.model.DjSettings
import org.simpmusic.dj.model.PlanKind
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.model.TrackAnalysisRepository
import org.simpmusic.dj.model.TransitionPlanner

/** One row of the "DJ: what next?" list. */
data class DjSuggestion(
    val track: LibraryTrack,
    /** 0..1, higher is a better follow-up. */
    val score: Float,
    /** Camelot code ("8A") of the suggestion, when its key is trusted. */
    val camelot: String?,
    val bpm: Float?,
    /** Mean loudness envelope 0..1 (1 = the loudest moment of that track). */
    val energy: Float?,
    /** One line: keys, tempi, energy flow, transition kind. */
    val reason: String,
    /** Per component 0..1 (harmony, tempo, energy, timbre, loudness, transition). */
    val breakdown: Map<String, Float>,
    val transition: PlanKind?,
)

sealed interface DjNextResult {
    /** The playing track has no analysis yet; one is requested. Watch [DjRecommendationService.awaitAnalysis]. */
    data class CurrentNotAnalysed(val videoId: String) : DjNextResult

    /** No analyzer is installed in this build. */
    data object AnalyzerMissing : DjNextResult

    data class Ready(
        val currentCamelot: String?,
        val currentBpm: Float?,
        val items: List<DjSuggestion>,
        /** How many analysed library tracks were compared. */
        val poolSize: Int,
    ) : DjNextResult
}

/**
 * "What should follow this track?" over the analysed library. Ranks with the brain's `HeuristicRecommender` (cheap score
 * over the whole pool, then the real planner over the shortlist, so plan quality counts), leaves out what was played
 * recently and what is already in the queue, and hands back what the UI needs to show and to enqueue each suggestion.
 */
class DjRecommendationService(
    private val pool: AnalysisPool,
    private val source: LibrarySource,
    private val analyses: TrackAnalysisRepository,
    private val planner: TransitionPlanner,
    private val settings: () -> DjSettings,
    private val player: DjPlayerPort,
    private val analyzerAvailable: () -> Boolean = { true },
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val recentWindow: Int = RECENT_WINDOW,
) {
    suspend fun recommend(currentId: String, limit: Int = 10): DjNextResult {
        if (!analyzerAvailable()) return DjNextResult.AnalyzerMissing
        val current = pool.analysisOf(currentId) ?: analyses.get(currentId)
        if (current == null) {
            analyses.request(currentId, AnalysisPriority.NOW_PLAYING)
            return DjNextResult.CurrentNotAnalysed(currentId)
        }
        val entries = pool.snapshot()
        val excluded = excludedIds(currentId)
        val byId = entries.associateBy { it.analysis.videoId }
        val ranked =
            withContext(compute) {
                DjRanking.rank(
                    current = current,
                    pool = entries.map { it.analysis },
                    settings = settings(),
                    planner = planner,
                    history = excluded,
                    limit = limit,
                )
            }
        val items =
            ranked.mapNotNull { rec ->
                val entry = byId[rec.videoId] ?: return@mapNotNull null
                DjSuggestion(
                    track = entry.track,
                    score = rec.score,
                    camelot = entry.analysis.key?.takeIf { it.confidence >= org.simpmusic.dj.model.Trust.KEY }?.value?.camelot(),
                    bpm = entry.analysis.bpm?.takeIf { it.confidence >= org.simpmusic.dj.model.Trust.BPM }?.value,
                    energy = meanEnergy(entry.analysis),
                    reason = rec.reason,
                    breakdown = rec.breakdown,
                    transition = rec.plan?.kind,
                )
            }
        return DjNextResult.Ready(
            currentCamelot = current.key?.takeIf { it.confidence >= org.simpmusic.dj.model.Trust.KEY }?.value?.camelot(),
            currentBpm = current.bpm?.takeIf { it.confidence >= org.simpmusic.dj.model.Trust.BPM }?.value,
            items = items,
            poolSize = entries.size,
        )
    }

    /** Suspends until [videoId] has an analysis (requesting it), or [timeoutMs] passes. */
    suspend fun awaitAnalysis(videoId: String, timeoutMs: Long = 5 * 60_000L): TrackAnalysis? {
        analyses.get(videoId)?.let { return it }
        analyses.request(videoId, AnalysisPriority.NOW_PLAYING)
        return withTimeoutOrNull(timeoutMs) { analyses.observe(videoId).first { it != null } }
    }

    suspend fun playNext(s: DjSuggestion) = player.playNext(s.track.track)

    suspend fun playNow(s: DjSuggestion) = player.playNow(s.track.track)

    /** Recently played ids, everything in the queue, and the playing track: never suggested. */
    private suspend fun excludedIds(currentId: String): Set<String> {
        val snap = player.snapshot()
        return buildSet {
            add(currentId)
            addAll(source.recentlyPlayedIds(recentWindow))
            addAll(snap.queueIds)
        }
    }

    private fun meanEnergy(a: TrackAnalysis): Float? = a.energy.takeIf { it.isNotEmpty() }?.average()?.toFloat()

    companion object {
        /** How many of the last played songs are never suggested again. */
        const val RECENT_WINDOW = 30
    }
}
