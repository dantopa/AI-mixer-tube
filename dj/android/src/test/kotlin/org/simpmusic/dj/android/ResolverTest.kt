package org.simpmusic.dj.android

import android.media.MediaExtractor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.simpmusic.dj.android.decode.AudioInput
import org.simpmusic.dj.android.decode.AudioNeedsNetworkException
import org.simpmusic.dj.android.decode.AudioOrigin
import org.simpmusic.dj.android.decode.CacheFirstAudioSourceResolver
import org.simpmusic.dj.android.decode.CacheSource
import org.simpmusic.dj.android.decode.CacheState
import org.simpmusic.dj.android.decode.UrlAudioInput

class ResolverTest {
    private class FakeCache(val name_: String, var state: CacheState) : CacheSource {
        override val name: String get() = name_
        val input = object : AudioInput {
            override fun applyTo(extractor: MediaExtractor) = Unit

            override fun describe() = "fake:$name_"
        }

        override fun state(key: String) = state

        override fun open(key: String, contentLength: Long): AudioInput = input
    }

    private class Urls(var url: String? = "https://host.googlevideo.com/videoplayback?itag=251&clen=1000") : org.simpmusic.dj.android.decode.StreamUrlProvider {
        var calls = 0

        override suspend fun audioUrl(videoId: String): String? {
            calls++
            return url
        }
    }

    @Test
    fun aCompleteCacheIsUsedAndTheNetworkIsNeverAsked() =
        runTest {
            val downloads = FakeCache("download", CacheState(0, 0))
            val player = FakeCache("player", CacheState(1000, 1000))
            val urls = Urls()
            val r = CacheFirstAudioSourceResolver(listOf(downloads, player), urls).resolve("v", allowNetwork = false)!!
            assertEquals(AudioOrigin.CACHE, r.origin)
            assertSame(player.input, r.input)
            assertEquals(0, urls.calls)
        }

    @Test
    fun aPartialCacheFallsBackToFetchingTheWholeStreamInsteadOfFailing() =
        runTest {
            val player = FakeCache("player", CacheState(contentLength = 1000, cachedBytes = 400))
            val urls = Urls()
            val r = CacheFirstAudioSourceResolver(listOf(player), urls).resolve("v", allowNetwork = true)!!
            assertEquals("the partial file is never decoded: the analysis would cover only the buffered part", AudioOrigin.PARTIAL_CACHE_REFETCHED, r.origin)
            assertTrue(r.input is UrlAudioInput)
            assertEquals(1, urls.calls)
        }

    @Test
    fun aPartialCacheWithoutNetworkPermissionSaysItNeedsTheNetworkRatherThanFailing() =
        runTest {
            val player = FakeCache("player", CacheState(contentLength = 1000, cachedBytes = 400))
            try {
                CacheFirstAudioSourceResolver(listOf(player), Urls()).resolve("v", allowNetwork = false)
                fail("expected AudioNeedsNetworkException")
            } catch (e: AudioNeedsNetworkException) {
                assertTrue(e.partiallyCached)
            }
        }

    @Test
    fun nothingCachedAndNetworkForbiddenResolvesToNull_NothingCachedAndAllowedFetches() =
        runTest {
            val empty = FakeCache("player", CacheState(0, 0))
            assertNull(CacheFirstAudioSourceResolver(listOf(empty), Urls()).resolve("v", allowNetwork = false))
            val r = CacheFirstAudioSourceResolver(listOf(empty), Urls()).resolve("v", allowNetwork = true)!!
            assertEquals(AudioOrigin.URL, r.origin)
        }

    @Test
    fun noStreamUrlMeansNoSource() =
        runTest {
            val empty = FakeCache("player", CacheState(0, 0))
            assertNull(CacheFirstAudioSourceResolver(listOf(empty), Urls(url = null)).resolve("v", allowNetwork = true))
        }

    @Test
    fun theDownloadCacheWinsOverThePlayerCache() =
        runTest {
            val downloads = FakeCache("download", CacheState(500, 500))
            val player = FakeCache("player", CacheState(500, 500))
            val r = CacheFirstAudioSourceResolver(listOf(downloads, player), Urls()).resolve("v", allowNetwork = true)!!
            assertSame(downloads.input, r.input)
        }
}
