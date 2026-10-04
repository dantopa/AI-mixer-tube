package org.simpmusic.dj.android.recommend

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.simpmusic.dj.android.library.CandidateSelection
import org.simpmusic.dj.android.library.LibrarySource
import org.simpmusic.dj.android.library.LibraryTrack
import org.simpmusic.dj.model.TrackAnalysis

/** Reads stored analyses. Production: the analysis store on an IO thread (NOT the scheduler's single analysis thread). */
fun interface AnalysisLookup {
    suspend fun get(videoId: String): TrackAnalysis?
}

data class PoolEntry(val track: LibraryTrack, val analysis: TrackAnalysis)

/**
 * The analysed part of the library: candidates ([CandidateSelection.select], same cap as the background analysis) that
 * have a fresh stored analysis. Analyses are kept in memory once read (bounded), because the recommendation and the
 * Auto DJ read the whole pool for every decision.
 */
class AnalysisPool(
    private val source: LibrarySource,
    private val lookup: AnalysisLookup,
    private val cap: Int = CandidateSelection.DEFAULT_CAP,
    private val maxCached: Int = 500,
) {
    private val cache =
        object : LinkedHashMap<String, TrackAnalysis>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackAnalysis>?) = size > maxCached
        }

    /** Every analysed candidate. */
    suspend fun snapshot(): List<PoolEntry> {
        val candidates = CandidateSelection.select(source.candidates(), cap)
        val out = ArrayList<PoolEntry>(candidates.size)
        for (c in candidates) {
            val a = analysisOf(c.track.videoId) ?: continue
            out += PoolEntry(c.track, a)
        }
        return out
    }

    /** The analysis of any track (in the pool or not), or null when it has none yet. */
    suspend fun analysisOf(videoId: String): TrackAnalysis? {
        synchronized(cache) { cache[videoId] }?.let { return it }
        val loaded = lookup.get(videoId) ?: return null
        synchronized(cache) { cache[videoId] = loaded }
        return loaded
    }

    /** Forgets [videoId] (a stale or re-analysed track). */
    fun invalidate(videoId: String) {
        synchronized(cache) { cache.remove(videoId) }
    }

    companion object {
        /** Production lookup: the store, on [dispatcher]. */
        fun storeLookup(dispatcher: CoroutineDispatcher = Dispatchers.IO, read: (String) -> TrackAnalysis?): AnalysisLookup =
            AnalysisLookup { id -> withContext(dispatcher) { read(id) } }
    }
}
