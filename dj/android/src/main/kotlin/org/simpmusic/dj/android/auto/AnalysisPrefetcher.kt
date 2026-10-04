package org.simpmusic.dj.android.auto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.simpmusic.dj.android.log.DjLog
import org.simpmusic.dj.android.recommend.DjPlayerPort
import org.simpmusic.dj.android.recommend.PlayerSnapshot
import org.simpmusic.dj.model.AnalysisPriority
import org.simpmusic.dj.model.DjSettings

/** What the prefetcher needs of the scheduler. */
interface AnalysisRequester {
    suspend fun request(videoId: String, priority: AnalysisPriority)

    fun cancelStale(current: String?, next: String?)
}

/**
 * Asks for the analysis of the playing track (NOW_PLAYING) and of the one after it (NEXT_UP) the moment a track STARTS,
 * from the player's own state flows, instead of waiting for the transition engine's 50 ms position poll (which only runs
 * once the track is actually playing). A track's analysis takes tens of seconds on a phone, so every second counts.
 * The engine still asks on its own poll; the scheduler de-duplicates.
 */
class AnalysisPrefetcher(
    private val scope: CoroutineScope,
    private val settings: StateFlow<DjSettings>,
    private val port: DjPlayerPort,
    private val requester: AnalysisRequester,
) {
    private var lastLine: String? = null

    fun start() {
        scope.launch {
            settings.map { it.enabled }.distinctUntilChanged().collectLatest { on ->
                if (on) port.snapshots.collect { prefetch(it) }
            }
        }
    }

    internal suspend fun prefetch(s: PlayerSnapshot) {
        val current = s.currentId ?: return
        if (s.casting || s.listenTogether) return
        val next = s.queueIds.getOrNull(s.currentIndex + 1)?.takeIf { it != current }
        val line = "current=$current next=$next"
        if (line != lastLine) {
            lastLine = line
            DjLog.i(TAG, "track started: $line (queue ${s.queueIds.size}, index ${s.currentIndex}): requesting analyses now")
        }
        requester.cancelStale(current, next)
        requester.request(current, AnalysisPriority.NOW_PLAYING)
        if (next != null) requester.request(next, AnalysisPriority.NEXT_UP)
    }

    private companion object {
        const val TAG = "prefetch"
    }
}
