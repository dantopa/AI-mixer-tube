package org.simpmusic.dj.android.splice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SpliceEngineTest {
    private val rate = 48_000

    /** A window whose every sample encodes its own frame index (left) and its negation (right), so outputs are checkable. */
    private fun indexWindow(frames: Int): ArrayWindowSamples {
        val pcm = ShortArray(frames * 2)
        for (i in 0 until frames) {
            pcm[2 * i] = (i % 30000).toShort()
            pcm[2 * i + 1] = (-(i % 30000)).toShort()
        }
        return ArrayWindowSamples(rate, pcm)
    }

    /** Feeds [totalFrames] of constant input (value [v]) from source [startMs] in random block sizes; returns the output. */
    private fun run(
        engine: SpliceEngine,
        startMs: Double,
        totalFrames: Int,
        v: Short = 1000,
        seed: Int = 1,
        before: (Int) -> Unit = {},
    ): ShortArray {
        val rng = Random(seed)
        engine.onFlush((startMs * 1000).toLong())
        val out = ShortArray(totalFrames * 2)
        var done = 0
        while (done < totalFrames) {
            before(done)
            val n = minOf(totalFrames - done, 1 + rng.nextInt(2048))
            val inp = ShortArray(n * 2) { v }
            val o = ShortArray(n * 2)
            engine.process(inp, n, o)
            System.arraycopy(o, 0, out, done * 2, n * 2)
            done += n
        }
        return out
    }

    @Test
    fun passthroughIsBitExact() {
        val e = SpliceEngine({ indexWindow(10) })
        e.configure(rate, 2, true)
        val out = run(e, 0.0, 5000, v = 1234)
        assertTrue(out.all { it == 1234.toShort() })
    }

    @Test
    fun theOutgoingDeckEntersTheWindowAtTheExactFrame() {
        val win = indexWindow(200_000)
        val e = SpliceEngine({ win })
        e.configure(rate, 2, true)
        // source 10 000 ms = window 0 ms (k = -10 000); splice at source 10 500 ms with a 1-frame fade
        val spliceAt = 10_500.0
        e.setCommand(SpliceCommand.Run(WindowMap(-10_000.0), spliceInAtMs = spliceAt, xfadeMs = 1000.0 / rate))
        val out = run(e, 10_000.0, 48_000)
        val spliceFrame = 24_000 // 500 ms in
        assertEquals(1000, out[2 * (spliceFrame - 1)].toInt())
        for (f in spliceFrame + 1 until 48_000) {
            assertEquals("frame $f", (f % 30000), out[2 * f].toInt())
            assertEquals(-(f % 30000), out[2 * f + 1].toInt())
        }
        assertEquals(10_500.0, e.windowTimeAt(10_500.0)!! + 10_000.0, 1e-9)
    }

    @Test
    fun theIncomingDeckJoinsItsOwnAudioAtTheExactFrameAndRampsItsGain() {
        val win = indexWindow(200_000)
        val e = SpliceEngine({ win })
        e.configure(rate, 2, true)
        // carries from the start (window frame = source frame), joins at source 1000 ms, gain 0.5 -> 1 over 100 ms
        e.setCommand(
            SpliceCommand.Run(WindowMap(0.0), spliceInAtMs = null, joinAtMs = 1000.0, joinGain = 0.5f, joinRampMs = 100.0, xfadeMs = 1000.0 / rate),
        )
        val out = run(e, 0.0, 72_000, v = 10_000)
        assertEquals(47_999 % 30000, out[2 * 47_999].toInt()) // last window frame
        assertEquals(5000.0, out[2 * 48_001].toDouble(), 2.0) // own audio at gain ~0.5
        assertEquals(7500.0, out[2 * (48_000 + 2400)].toDouble(), 3.0) // half-way up the ramp
        assertEquals(10_000, out[2 * 60_000].toInt()) // back to unity
    }

    @Test
    fun aSeekInterruptsARunningCommandButNotOneThatAllowsIt() {
        val e = SpliceEngine({ indexWindow(10_000) })
        e.configure(rate, 2, true)
        e.setCommand(SpliceCommand.Run(WindowMap(0.0), spliceInAtMs = null))
        run(e, 0.0, 4800)
        e.onFlush(0) // Media3's first flush of a seek
        e.onFlush(30_000_000) // and the one with the real position
        assertTrue(e.isInterrupted)
        val o = ShortArray(20)
        e.process(ShortArray(20) { 7 }, 10, o)
        assertTrue("an interrupted engine passes its own audio through", o.all { it == 7.toShort() })

        e.setCommand(SpliceCommand.Run(WindowMap(0.0), spliceInAtMs = null, allowSeek = true))
        run(e, 30_000.0, 4800)
        e.onFlush(10_000_000)
        assertFalse(e.isInterrupted)
    }

    @Test
    fun aReconfigurationFlushAtTheSamePositionIsNotASeek() {
        val e = SpliceEngine({ indexWindow(10_000) })
        e.configure(rate, 2, true)
        e.setCommand(SpliceCommand.Run(WindowMap(0.0), spliceInAtMs = null))
        run(e, 0.0, 4800) // 100 ms
        e.onFlush(100_000)
        assertFalse(e.isInterrupted)
    }

    @Test
    fun captureReturnsTheInputAtItsSourceTime() {
        val e = SpliceEngine({ null })
        e.configure(rate, 2, true)
        e.setCommand(SpliceCommand.Run(WindowMap(0.0), capture = true))
        e.onFlush(2_000_000)
        var frame = 0L
        val rng = Random(3)
        while (frame < 96_000) {
            val n = 1 + rng.nextInt(1000)
            val inp = ShortArray(n * 2) { i -> ((frame + i / 2) % 1000).toShort() }
            e.process(inp, n, ShortArray(n * 2))
            frame += n
        }
        val c = e.captureSlice(2_500.0, 100.0)
        assertNotNull(c)
        assertEquals(2_500.0, c!!.startMs, 1e-6)
        assertEquals(((24_000 % 1000) / 32768f), c.samples[0], 1e-6f)
        assertNull("beyond what was processed", e.captureSlice(3_900.0, 200.0))
    }

    @Test
    fun theHistoryMapsTheSpeakerPositionToTheCommandThatProducedIt() {
        val e = SpliceEngine({ indexWindow(100_000) })
        e.configure(rate, 2, true)
        e.setCommand(SpliceCommand.Run(WindowMap(5.0), spliceInAtMs = null, allowSeek = true))
        run(e, 0.0, 48_000)
        e.setCommand(SpliceCommand.Run(WindowMap(9.0), spliceInAtMs = null, allowSeek = true))
        val o = ShortArray(200)
        e.engineProcessQuietly(o)
        assertEquals(505.0, e.windowTimeAt(500.0)!!, 1e-9) // played before the change
        assertEquals(1009.01, e.windowTimeAt(1000.01)!!, 1e-9)
    }

    private fun SpliceEngine.engineProcessQuietly(o: ShortArray) = process(ShortArray(o.size), o.size / 2, o)
}
