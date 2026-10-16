@file:OptIn(UnstableApi::class)

package org.simpmusic.dj.android.bus

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Tells a [BusAudioOutputProvider] where each of its player's streams starts in the SONG (media time), first in the
 * player's audio chain, passing audio through unchanged.
 *
 * Why it exists: Media3 1.11.1's `DefaultAudioSink` hands `AudioOutput.write` the buffer's RENDERER time, which carries
 * the renderer's stream offset (ExoPlayer starts renderers at 1_000_000_000_000 us), so a deck addressed by it sits
 * ~11.6 days into the song and every plan (in song time) is "too late" at once. The sink subtracts that offset only for
 * its audio processors: `flush(StreamMetadata)` carries `positionOffsetUs` = song time of the first frame of the stream
 * that follows, and the sink always flushes its processors so between building a new output and writing to it.
 * Inactive processors are flushed too, but this one stays active for every PCM format so it never depends on that.
 */
class BusStreamTap(private val provider: BusAudioOutputProvider) : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        replaceOutputBuffer(n).put(inputBuffer).flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        provider.onStreamStart(streamMetadata.positionOffsetUs)
    }
}
