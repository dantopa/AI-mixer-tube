package org.simpmusic.dj.render

import org.simpmusic.dj.model.PcmAudio

/** Stereo PCM, float -1..1, planar. For mono material [left] and [right] may be the same array. */
class StereoPcm(val left: FloatArray, val right: FloatArray, val sampleRate: Int) {
    init {
        require(left.size == right.size) { "channel length mismatch" }
        require(sampleRate > 0)
    }

    val frames: Int get() = left.size
    val durationMs: Long get() = frames * 1000L / sampleRate

    fun toMono(): FloatArray = FloatArray(frames) { 0.5f * (left[it] + right[it]) }

    companion object {
        fun fromMono(pcm: PcmAudio) = StereoPcm(pcm.samples, pcm.samples, pcm.sampleRate)
        fun fromMono(samples: FloatArray, sampleRate: Int) = StereoPcm(samples, samples, sampleRate)
        fun fromChannels(channels: Array<FloatArray>, sampleRate: Int) =
            if (channels.size == 1) fromMono(channels[0], sampleRate) else StereoPcm(channels[0], channels[1], sampleRate)
    }
}

/**
 * A stretch of a track's audio. [pcm] holds the source audio from [sourceStartMs] (on the SOURCE track timeline)
 * onwards, so a caller can decode just the part a transition needs while plan times stay in source-track time.
 * Reads outside the segment return silence.
 */
class AudioSegment(val pcm: StereoPcm, val sourceStartMs: Long = 0L) {
    val sourceEndMs: Long get() = sourceStartMs + pcm.durationMs

    companion object {
        fun of(pcm: PcmAudio) = AudioSegment(StereoPcm.fromMono(pcm), 0L)
    }
}
