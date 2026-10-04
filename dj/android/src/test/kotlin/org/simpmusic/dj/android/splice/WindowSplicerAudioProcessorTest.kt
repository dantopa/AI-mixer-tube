package org.simpmusic.dj.android.splice

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(UnstableApi::class)
class WindowSplicerAudioProcessorTest {
    private fun pcm(frames: Int, value: Short): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        repeat(frames * 2) { b.putShort(value) }
        b.flip()
        return b
    }

    private fun drain(p: AudioProcessor): ShortArray {
        val out = p.output
        val s = ShortArray(out.remaining() / 2)
        out.order(ByteOrder.nativeOrder()).asShortBuffer().get(s)
        return s
    }

    @Test
    fun passesThroughThenSplicesAtTheFlushedPosition() {
        val window = ArrayWindowSamples(48_000, ShortArray(48_000 * 2) { 5000 })
        val p = WindowSplicerAudioProcessor(SpliceEngine({ window }))
        val fmt = p.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        assertEquals(48_000, fmt.sampleRate)
        assertTrue(p.isActive)
        p.flush(AudioProcessor.StreamMetadata(10_000_000)) // source 10 s
        p.queueInput(pcm(480, 100))
        assertTrue(drain(p).all { it == 100.toShort() })
        // window from source 10.010 s, pointer -10 000 ms (window 0 = source 10 s)
        p.engine.setCommand(SpliceCommand.Run(WindowMap(-10_000.0), spliceInAtMs = 10_010.0, xfadeMs = 0.001))
        p.queueInput(pcm(960, 100))
        val o = drain(p)
        assertEquals(1920, o.size)
        assertTrue("before the splice", o.take(2).all { it == 100.toShort() })
        assertTrue("after the splice", o.takeLast(2).all { it == 5000.toShort() })
    }

    @Test
    fun floatOutputLeavesItInactive() {
        val p = WindowSplicerAudioProcessor(SpliceEngine({ null }))
        p.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT))
        assertTrue(!p.isActive)
        assertTrue(!p.engine.isSupported)
    }
}
