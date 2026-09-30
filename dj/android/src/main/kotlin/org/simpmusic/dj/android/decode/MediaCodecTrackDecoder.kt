package org.simpmusic.dj.android.decode

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
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
    private val log: (String) -> Unit = {},
) : TrackDecoder {
    override suspend fun decodeForAnalysis(videoId: String, allowNetwork: Boolean, cancel: CancelSignal): PcmAudio =
        withContext(dispatcher) {
            val resolved = resolver.resolve(videoId, allowNetwork) ?: throw AudioUnavailableException("no audio source for $videoId (network allowed=$allowNetwork)")
            log("dj analysis decode $videoId from ${resolved.origin}")
            val out = GrowableFloats(ANALYSIS_SAMPLE_RATE * 60)
            codec.decode(
                resolved.input,
                DecodeRequest(outSampleRate = ANALYSIS_SAMPLE_RATE, outChannels = 1),
                { buf, n -> out.add(buf, n) },
                cancel,
            )
            if (out.size < ANALYSIS_SAMPLE_RATE) throw AudioUnavailableException("decoded only ${out.size} samples for $videoId")
            PcmAudio(out.toArray(), ANALYSIS_SAMPLE_RATE)
        }

    override suspend fun decodeStereoRange(videoId: String, startMs: Long, endMs: Long, cancel: CancelSignal): StereoPcm =
        withContext(dispatcher) {
            // The next track is about to be streamed anyway, so the network is always allowed here.
            val resolved = resolver.resolve(videoId, allowNetwork = true) ?: throw AudioUnavailableException("no audio source for $videoId")
            log("dj range decode $videoId [$startMs,$endMs) from ${resolved.origin}")
            val out = GrowableFloats(RENDER_SAMPLE_RATE * 2 * 20)
            codec.decode(
                resolved.input,
                DecodeRequest(startMs = startMs, endMs = endMs, outSampleRate = RENDER_SAMPLE_RATE, outChannels = 2),
                { buf, n -> out.add(buf, n * 2) },
                cancel,
            )
            StereoPcm(out.toArray(), RENDER_SAMPLE_RATE, startMs)
        }
}
