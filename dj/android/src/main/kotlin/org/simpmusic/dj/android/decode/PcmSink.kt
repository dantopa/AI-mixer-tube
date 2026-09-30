package org.simpmusic.dj.android.decode

/** Receives decoded, already resampled/remixed interleaved float PCM in chunks. */
fun interface PcmSink {
    /** [buffer] is reused by the caller: copy what you keep. */
    fun write(buffer: FloatArray, frames: Int)
}

/** Which slice of the track to decode and in what shape. */
data class DecodeRequest(
    /** Start of the wanted range in source milliseconds (seek lands on the previous sync frame; the excess is trimmed). */
    val startMs: Long = 0L,
    /** End of the wanted range, exclusive; [Long.MAX_VALUE] decodes to the end of the stream. */
    val endMs: Long = Long.MAX_VALUE,
    val outSampleRate: Int,
    /** 1 = mono downmix, 2 = stereo (mono sources are duplicated). */
    val outChannels: Int,
) {
    init {
        require(startMs >= 0 && endMs > startMs) { "bad range $startMs..$endMs" }
        require(outChannels == 1 || outChannels == 2)
    }
}

/** Interleaved-channel conversion helpers (pure, unit-tested). */
object ChannelMixer {
    /** Converts [frames] frames of [inCh] interleaved channels in [src] to [outCh] channels into [dst]. */
    fun convert(src: FloatArray, frames: Int, inCh: Int, outCh: Int, dst: FloatArray) {
        when {
            inCh == outCh -> System.arraycopy(src, 0, dst, 0, frames * inCh)
            outCh == 1 -> {
                for (f in 0 until frames) {
                    var acc = 0f
                    for (c in 0 until inCh) acc += src[f * inCh + c]
                    dst[f] = acc / inCh
                }
            }
            inCh == 1 -> {
                for (f in 0 until frames) {
                    dst[2 * f] = src[f]
                    dst[2 * f + 1] = src[f]
                }
            }
            else -> { // inCh > 2 -> stereo: front L/R only (music is authored for them)
                for (f in 0 until frames) {
                    dst[2 * f] = src[f * inCh]
                    dst[2 * f + 1] = src[f * inCh + 1]
                }
            }
        }
    }
}
