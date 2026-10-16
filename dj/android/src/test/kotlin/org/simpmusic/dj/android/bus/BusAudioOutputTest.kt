@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package org.simpmusic.dj.android.bus

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.AudioOutputProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.simpmusic.dj.bus.DeckRing
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The build-au device log: every live mix fell back with TOO_LATE, because Media3 hands `AudioOutput.write` renderer
 * time (song time + ExoPlayer's 1e12 us renderer offset), and the 1-2-3-4 counter jumped in ~1.1 s steps because the
 * output reported its 12 s ring as its buffer. These pin both fixes: decks are addressed in SONG time (from the stream
 * tap's flush), and the reported buffer is small.
 */
class BusAudioOutputTest {
    private val rendererOffsetUs = 1_000_000_000_000L

    private fun output(provider: BusAudioOutputProvider) =
        provider.getAudioOutput(
            AudioOutputProvider.OutputConfig.Builder()
                .setEncoding(C.ENCODING_PCM_16BIT)
                .setSampleRate(AndroidMixBus.SAMPLE_RATE)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO)
                .setBufferSize(AndroidMixBus.SAMPLE_RATE * 4)
                .build(),
        )

    private fun pcm(frames: Int): ByteBuffer = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder()).apply {
        repeat(frames * 2) { putShort(1000) }
        flip()
    }

    private fun tapFlush(provider: BusAudioOutputProvider, positionOffsetUs: Long) {
        val tap = provider.streamTap
        tap.configure(AudioProcessor.AudioFormat(AndroidMixBus.SAMPLE_RATE, 2, C.ENCODING_PCM_16BIT))
        tap.flush(AudioProcessor.StreamMetadata.Builder().setPositionOffsetUs(positionOffsetUs).build())
    }

    @Test
    fun theDeckIsAddressedInSongTimeNotRendererTime() {
        val bus = AndroidMixBus()
        val provider = BusAudioOutputProvider(bus)
        provider.getOutputConfig(
            AudioOutputProvider.FormatConfig.Builder(
                Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT)
                    .setSampleRate(AndroidMixBus.SAMPLE_RATE).setChannelCount(2).build(),
            ).build(),
        )
        // a seek to 90 s: the sink builds the output, then flushes its processors with the song time, then writes
        val out = output(provider)
        tapFlush(provider, 90_000_000L)
        out.write(pcm(480), 1, rendererOffsetUs + 90_000_000L)
        val view = DeckRing.View()
        bus.core.ring(provider.deckId).snapshot(view)
        assertEquals(90L * AndroidMixBus.SAMPLE_RATE, view.origin)

        // the next seek (a new output) starts a new stream at its own song time
        out.release()
        val out2 = output(provider)
        tapFlush(provider, 0L) // the flush the sink sends with no time yet...
        tapFlush(provider, 12_500_000L) // ...and the real one before the first write
        out2.write(pcm(480), 1, rendererOffsetUs + 12_500_000L)
        bus.core.ring(provider.deckId).snapshot(view)
        assertEquals(12_500L * AndroidMixBus.SAMPLE_RATE / 1000, view.origin)
    }

    @Test
    fun theReportedBufferIsSmallSoThePlayerWakesOften() {
        val provider = BusAudioOutputProvider(AndroidMixBus())
        val out = output(provider)
        assertEquals(BusAudioOutputProvider.REPORTED_BUFFER_MS * AndroidMixBus.SAMPLE_RATE / 1000, out.bufferSizeInFrames)
    }
}
