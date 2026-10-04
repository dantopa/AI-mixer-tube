@file:OptIn(UnstableApi::class)

package org.simpmusic.dj.android.splice

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Media3 face of a [SpliceEngine]: first in every player's audio chain (ahead of the equalizer and the effects, so the
 * window gets the same user processing the deck's own audio gets). Passes audio straight through until the DJ arms it.
 *
 * It must stay ACTIVE for every PCM16 format so it is in the chain when a transition needs it (activity is only decided
 * at configure time); the pass-through costs one copy per buffer. Other encodings (float output) leave it inactive and
 * the engine reports [SpliceEngine.isSupported] = false, which sends the DJ to its three-player path.
 */
class WindowSplicerAudioProcessor(
    val engine: SpliceEngine,
) : BaseAudioProcessor() {
    private var inBuf = ShortArray(0)
    private var outBuf = ShortArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val ok = engine.configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, inputAudioFormat.encoding == C.ENCODING_PCM_16BIT)
        return if (ok) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val ch = inputAudioFormat.channelCount
        val frames = bytes / (2 * ch)
        val n = frames * ch
        if (inBuf.size < n) {
            inBuf = ShortArray(n)
            outBuf = ShortArray(n)
        }
        inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer().get(inBuf, 0, n)
        inputBuffer.position(inputBuffer.position() + n * 2)
        engine.process(inBuf, frames, outBuf)
        val out = replaceOutputBuffer(n * 2)
        out.order(ByteOrder.nativeOrder())
        out.asShortBuffer().put(outBuf, 0, n)
        out.position(out.position() + n * 2)
        out.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        engine.onFlush(streamMetadata.positionOffsetUs)
    }

    override fun onReset() {
        engine.reset()
    }
}
