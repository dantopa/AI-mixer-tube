@file:OptIn(UnstableApi::class)

package org.simpmusic.dj.android.decode

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import com.maxrave.domain.extension.now
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.StreamRepository
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.lastOrNull
import org.simpmusic.dj.android.log.DjLog

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

/** One cache the resolver may read from: what it holds for a key and how to read it back. */
interface CacheSource {
    /** Short name for the log ("download", "player"). */
    val name: String get() = "cache"

    fun state(key: String): CacheState

    fun open(key: String, contentLength: Long): AudioInput
}

/** A media3 [Cache] as a [CacheSource]. */
class MediaCacheSource(private val cache: Cache, override val name: String = "cache") : CacheSource {
    override fun state(key: String): CacheState = cache.cacheState(key)

    override fun open(key: String, contentLength: Long): AudioInput = CacheAudioInput(cache, key, contentLength)
}

class CacheFirstAudioSourceResolver(
    /** In priority order: downloads first (complete by construction), then the rolling player cache. */
    private val caches: List<CacheSource>,
    private val urls: StreamUrlProvider,
) : AudioSourceResolver {
    override suspend fun resolve(videoId: String, allowNetwork: Boolean): ResolvedAudio? {
        val t0 = System.nanoTime()
        var sawPartial = false
        for (cache in caches) {
            val state = cache.state(videoId)
            DjLog.d(TAG, "$videoId ${cache.name}: cached=${state.cachedBytes} of ${state.contentLength} B complete=${state.isComplete}")
            if (state.isComplete) {
                DjLog.i(TAG, "$videoId -> CACHE ${cache.name} (${state.contentLength} B, decided in ${ms(t0)} ms)")
                return ResolvedAudio(cache.open(videoId, state.contentLength), AudioOrigin.CACHE)
            }
            if (state.cachedBytes > 0) sawPartial = true
        }
        // Whatever the cache holds is NOT enough to decode from (a partial file would silently analyse only the buffered
        // part), so the whole stream is fetched instead of failing, as long as the network is allowed.
        if (!allowNetwork) {
            DjLog.w(TAG, "$videoId not fully cached (partial=$sawPartial) and network not allowed -> cannot resolve")
            if (sawPartial) throw AudioNeedsNetworkException("cache incomplete for $videoId, need network", partiallyCached = true)
            return null
        }
        val tUrl = System.nanoTime()
        val url =
            try {
                urls.audioUrl(videoId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                DjLog.e(TAG, "$videoId stream url lookup FAILED after ${ms(tUrl)} ms", e)
                throw e
            }
        if (url == null) {
            DjLog.w(TAG, "$videoId no stream url could be obtained (${ms(tUrl)} ms)")
            return null
        }
        DjLog.i(TAG, "$videoId -> NETWORK fetch (partial cache=$sawPartial) ${DjLog.redactUrl(url)}, url lookup ${ms(tUrl)} ms")
        return ResolvedAudio(UrlAudioInput(url), if (sawPartial) AudioOrigin.PARTIAL_CACHE_REFETCHED else AudioOrigin.URL)
    }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    companion object {
        private const val TAG = "resolve"

        fun fromCaches(downloads: Cache, player: Cache, urls: StreamUrlProvider) =
            CacheFirstAudioSourceResolver(listOf(MediaCacheSource(downloads, "download"), MediaCacheSource(player, "player")), urls)
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
            DjLog.d(TAG, "$videoId stored format: itag=${stored.itag} expires=${stored.expiredTime} clen=${stored.contentLength} is403=$is403 ${DjLog.redactUrl(storedUrl)}")
            if (!is403) return storedUrl
        } else {
            DjLog.d(TAG, "$videoId no usable stored format (present=${stored != null}, url=${storedUrl != null})")
        }
        val t0 = System.nanoTime()
        val url =
            streamRepository
                .getStream(dataStoreManager, videoId, isDownloading = false, isVideo = false)
                .lastOrNull()
        DjLog.i(TAG, "$videoId fresh stream resolved in ${(System.nanoTime() - t0) / 1_000_000} ms: ${DjLog.redactUrl(url)}")
        return url
    }

    private companion object {
        const val TAG = "resolve"
    }
}
