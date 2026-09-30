@file:OptIn(UnstableApi::class)

package org.simpmusic.dj.android.decode

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import com.maxrave.domain.extension.now
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.StreamRepository
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.lastOrNull

/** Finds a playable URL for a track when the cache cannot serve it. */
fun interface StreamUrlProvider {
    /** A direct audio URL for [videoId], or null when none can be had. */
    suspend fun audioUrl(videoId: String): String?
}

/** Where an [AudioInput] for a videoId comes from. */
interface AudioSourceResolver {
    /**
     * Cache first (download cache, then the player cache), and only when neither holds the *whole*
     * file the stream URL. Returns null when there is no way to get the audio.
     *
     * A partially cached file is NEVER decoded: the analysis would silently cover only the part
     * that happened to be buffered. It is reported as [AudioOrigin.PARTIAL_CACHE_REFETCHED].
     */
    suspend fun resolve(videoId: String, allowNetwork: Boolean): ResolvedAudio?
}

class CacheFirstAudioSourceResolver(
    /** In priority order: downloads first (complete by construction), then the rolling player cache. */
    private val caches: List<Cache>,
    private val urls: StreamUrlProvider,
    private val log: (String) -> Unit = {},
) : AudioSourceResolver {
    override suspend fun resolve(videoId: String, allowNetwork: Boolean): ResolvedAudio? {
        var sawPartial = false
        for (cache in caches) {
            val state = cache.cacheState(videoId)
            if (state.isComplete) {
                log("dj decode: $videoId fully cached (${state.contentLength} B)")
                return ResolvedAudio(CacheAudioInput(cache, videoId, state.contentLength), AudioOrigin.CACHE)
            }
            if (state.cachedBytes > 0) {
                sawPartial = true
                log("dj decode: $videoId only partially cached (${state.cachedBytes}/${state.contentLength} B)")
            }
        }
        if (!allowNetwork) return null
        val url = urls.audioUrl(videoId) ?: return null
        return ResolvedAudio(UrlAudioInput(url), if (sawPartial) AudioOrigin.PARTIAL_CACHE_REFETCHED else AudioOrigin.URL)
    }
}

/** [StreamUrlProvider] over the app's own stream repository: the stored format's URL, else a fresh resolve. */
class RepositoryStreamUrlProvider(
    private val streamRepository: StreamRepository,
    private val dataStoreManager: DataStoreManager,
) : StreamUrlProvider {
    override suspend fun audioUrl(videoId: String): String? {
        val stored = streamRepository.getNewFormat(videoId).firstOrNull()
        val storedUrl = stored?.audioUrl
        if (storedUrl != null && stored.expiredTime > now()) {
            val is403 = streamRepository.is403Url(storedUrl).firstOrNull() != false
            if (!is403) return storedUrl
        }
        return streamRepository
            .getStream(dataStoreManager, videoId, isDownloading = false, isVideo = false)
            .lastOrNull()
    }
}
