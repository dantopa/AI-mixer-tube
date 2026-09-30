package org.simpmusic.dj.android.decode

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.simpmusic.dj.android.log.DjLog
import org.simpmusic.dj.android.render.StereoPcm
import org.simpmusic.dj.model.PcmAudio

/**
 * [TrackDecoder] over MediaExtractor/MediaCodec ([MediaCodecPcmDecoder]) and the cache-first source
 * resolution of [AudioSourceResolver]. Decoding runs on [dispatcher], a single low-priority thread, so it
 * never competes with playback for CPU.
 */
class MediaCodecTrackDecoder(
    private val resolver: AudioSourceResolver,
    private val dispatcher: CoroutineDispatcher,
    private val codec: MediaCodecPcmDecoder = MediaCodecPcmDecoder(),
) : TrackDecoder {
    override suspend fun decodeForAnalysis(videoId: String, allowNetwork: Boolean, cancel: CancelSignal): PcmAudio =
        withContext(dispatcher) {
            val t0 = System.nanoTime()
            val resolved =
                resolver.resolve(videoId, allowNetwork)
                    ?: throw if (allowNetwork) {
                        AudioUnavailableException("no stream url for $videoId")
                    } else {
                        AudioNeedsNetworkException("cache incomplete for $videoId and the network is not allowed", partiallyCached = false)
                    }
            DjLog.i(TAG, "analysis decode $videoId from ${resolved.origin} (resolve ${(System.nanoTime() - t0) / 1_000_000} ms) on thread ${Thread.currentThread().name}")
            val out = GrowableFloats(ANALYSIS_SAMPLE_RATE * 60)
            codec.decode(
                resolved.input,
                DecodeRequest(outSampleRate = ANALYSIS_SAMPLE_RATE, outChannels = 1),
                { buf, n -> out.add(buf, n) },
                cancel,
            )
            DjLog.i(TAG, "analysis decode $videoId produced ${out.size} samples (${out.size * 1000L / ANALYSIS_SAMPLE_RATE} ms of mono @$ANALYSIS_SAMPLE_RATE Hz) in ${(System.nanoTime() - t0) / 1_000_000} ms total")
            if (out.size < ANALYSIS_SAMPLE_RATE) throw AudioUnavailableException("decoded only ${out.size} samples for $videoId")
            PcmAudio(out.toArray(), ANALYSIS_SAMPLE_RATE)
        }

    override suspend fun decodeStereoRange(videoId: String, startMs: Long, endMs: Long, cancel: CancelSignal): StereoPcm =
        withContext(dispatcher) {
            // The next track is about to be streamed anyway, so the network is always allowed here.
            val t0 = System.nanoTime()
            val resolved = resolver.resolve(videoId, allowNetwork = true) ?: throw AudioUnavailableException("no audio source for $videoId")
            DjLog.i(TAG, "range decode $videoId [$startMs,$endMs) ms from ${resolved.origin}")
            val out = GrowableFloats(RENDER_SAMPLE_RATE * 2 * 20)
            codec.decode(
                resolved.input,
                DecodeRequest(startMs = startMs, endMs = endMs, outSampleRate = RENDER_SAMPLE_RATE, outChannels = 2),
                { buf, n -> out.add(buf, n * 2) },
                cancel,
            )
            DjLog.i(TAG, "range decode $videoId done: ${out.size / 2} frames stereo @$RENDER_SAMPLE_RATE Hz in ${(System.nanoTime() - t0) / 1_000_000} ms")
            StereoPcm(out.toArray(), RENDER_SAMPLE_RATE, startMs)
        }

    private companion object {
        const val TAG = "decode"
    }
}
