package org.simpmusic.dj.android.decode

import org.simpmusic.dj.android.render.StereoPcm
import org.simpmusic.dj.model.PcmAudio

/** Sample rate the analyzers get (mono). Matches what the brain's analyzers are specified against. */
const val ANALYSIS_SAMPLE_RATE = 22_050

/** Sample rate of decoded ranges handed to the window renderer (stereo). */
const val RENDER_SAMPLE_RATE = 48_000

open class AudioUnavailableException(message: String) : java.io.IOException(message)

/**
 * The audio is not (completely) cached and the caller may not use the network: the analysis is not broken, it is waiting for
 * a connection it was not allowed to open. The scheduler keeps such a track queued instead of counting a failure.
 */
class AudioNeedsNetworkException(message: String, val partiallyCached: Boolean) : AudioUnavailableException(message)

/** Turns a videoId into PCM. The Android implementation is [MediaCodecTrackDecoder]; tests use fakes. */
interface TrackDecoder {
    /**
     * The whole track as mono float PCM at [ANALYSIS_SAMPLE_RATE] (about 21 MB for four minutes).
     * @throws AudioUnavailableException when neither cache nor (permitted) network can supply the audio.
     */
    suspend fun decodeForAnalysis(videoId: String, allowNetwork: Boolean, cancel: CancelSignal = CancelSignal.NEVER): PcmAudio

    /** One range of the track as full-quality stereo at [RENDER_SAMPLE_RATE]; sample 0 is at the (trimmed) start. */
    suspend fun decodeStereoRange(videoId: String, startMs: Long, endMs: Long, cancel: CancelSignal = CancelSignal.NEVER): StereoPcm
}

/** Growable float buffer: the only thing that scales with track length while decoding. */
internal class GrowableFloats(initial: Int = 1 shl 16) {
    private var data = FloatArray(initial)
    var size = 0
        private set

    fun add(src: FloatArray, count: Int) {
        if (size + count > data.size) data = data.copyOf(maxOf(size + count, data.size * 2))
        System.arraycopy(src, 0, data, size, count)
        size += count
    }

    fun toArray(): FloatArray = data.copyOf(size)
}
