@file:OptIn(UnstableApi::class)

package org.simpmusic.dj.android.decode

import android.media.MediaDataSource
import android.media.MediaExtractor
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import java.io.IOException

/** A source of compressed audio that a [MediaExtractor] can be pointed at. */
interface AudioInput {
    fun applyTo(extractor: MediaExtractor)

    fun describe(): String

    fun close() {}
}

/** Where the bytes came from; surfaced in the DJ debug line and logs. */
enum class AudioOrigin { CACHE, PARTIAL_CACHE_REFETCHED, URL }

class ResolvedAudio(
    val input: AudioInput,
    val origin: AudioOrigin,
)

/** Streams `http(s)` audio directly through the platform's own network stack. */
class UrlAudioInput(private val url: String) : AudioInput {
    override fun applyTo(extractor: MediaExtractor) = extractor.setDataSource(url, emptyMap())

    override fun describe(): String = "url"
}

/** Random-access adapter from a media3 [Cache] entry to a platform [MediaDataSource]. */
class CacheAudioInput(
    private val cache: Cache,
    private val key: String,
    private val length: Long,
) : AudioInput {
    private val source = CacheBackedMediaDataSource(cache, key, length)

    override fun applyTo(extractor: MediaExtractor) = extractor.setDataSource(source)

    override fun describe(): String = "cache:$key"

    override fun close() = source.close()
}

/**
 * Reads a fully-cached entry through a cache-ONLY [CacheDataSource] (no upstream): a read that would
 * need the network fails instead of fetching, so decoding can never compete with playback for
 * bandwidth or silently double-download.
 */
internal class CacheBackedMediaDataSource(
    cache: Cache,
    private val key: String,
    private val length: Long,
) : MediaDataSource() {
    private val factory: DataSource.Factory = CacheDataSource.Factory().setCache(cache)
    private var source: DataSource? = null
    private var position = -1L

    @Synchronized
    override fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        size: Int,
    ): Int {
        if (position >= length) return -1
        if (size == 0) return 0
        var ds = source
        if (ds == null || this.position != position) {
            ds?.close()
            ds = factory.createDataSource()
            ds.open(
                DataSpec
                    .Builder()
                    .setUri(Uri.parse("dj-cache://$key"))
                    .setKey(key)
                    .setPosition(position)
                    .build(),
            )
            source = ds
            this.position = position
        }
        val n = ds.read(buffer, offset, size)
        if (n == C.RESULT_END_OF_INPUT) return -1
        this.position += n
        return n
    }

    override fun getSize(): Long = length

    @Synchronized
    override fun close() {
        try {
            source?.close()
        } catch (_: IOException) {
        }
        source = null
        position = -1L
    }
}

/** What a [Cache] holds for one key. */
data class CacheState(val contentLength: Long, val cachedBytes: Long) {
    val isComplete: Boolean get() = contentLength > 0 && cachedBytes >= contentLength
}

/**
 * The same completeness test core's `Cache.isFullyCached` (Media3Ext.kt) applies, without the position
 * argument: the declared content length is known and every byte of it is cached.
 */
fun Cache.cacheState(key: String): CacheState {
    val total = ContentMetadata.getContentLength(getContentMetadata(key))
    if (total <= 0L) return CacheState(0L, 0L)
    return CacheState(total, getCachedBytes(key, 0L, total))
}
