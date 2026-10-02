package org.simpmusic.dj.android.splice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class CorrelatorTest {
    /** Music-like test signal defined at any time (ms): a few partials plus a deterministic "noise" band. */
    private fun signal(tMs: Double): Float {
        val t = tMs / 1000.0
        var v = 0.0
        for ((f, a) in listOf(110.0 to 0.3, 233.1 to 0.2, 587.3 to 0.15, 1318.5 to 0.1, 3520.0 to 0.05)) v += a * sin(2 * PI * f * t + f)
        v += 0.1 * sin(2 * PI * 7.3 * t) * sin(2 * PI * 1975.0 * t)
        return v.toFloat()
    }

    private fun capture(rate: Int, startMs: Double, lengthMs: Double, deltaMs: Double) =
        CapturedAudio(rate, startMs, FloatArray((lengthMs * rate / 1000).toInt()) { i -> signal(startMs + i * 1000.0 / rate + deltaMs) })

    private fun reference(startMs: Double, lengthMs: Double) =
        ReferenceAudio(48_000, startMs, FloatArray((lengthMs * 48).toInt()) { i -> signal(startMs + i / 48.0) })

    @Test
    fun findsAWholeSampleSkew() {
        val r = Correlator.skew(capture(48_000, 10_000.0, 2000.0, 3.5), reference(9_900.0, 2200.0))
        assertNotNull(r)
        assertEquals(3.5, r!!.deltaMs, 0.01)
        assertTrue(r.confidence > 0.99)
    }

    @Test
    fun findsAFractionalAndNegativeSkew() {
        val r = Correlator.skew(capture(48_000, 10_000.123, 2000.0, -6.4321), reference(9_900.0, 2200.0))!!
        assertEquals(-6.4321, r.deltaMs, 0.02)
    }

    @Test
    fun worksWhenTheDeckRunsAt44100() {
        val r = Correlator.skew(capture(44_100, 20_000.0, 2000.0, 1.25), reference(19_900.0, 2200.0))!!
        assertEquals(1.25, r.deltaMs, 0.05)
        assertTrue(r.confidence > 0.98)
    }

    /** Music-like but not periodic: low-passed deterministic noise per 48 kHz sample (a sum of sines repeats within 200 ms). */
    private fun noise(n: Long): Float {
        var acc = 0f
        for (j in 0 until 4) {
            var h = (n - j) * 6364136223846793005L + 1442695040888963407L
            h = h xor (h ushr 29)
            h *= -0x40a7b892e31b1a47L
            h = h xor (h ushr 32)
            acc += ((h and 0xffff).toFloat() / 32768f - 1f)
        }
        return acc / 4f
    }

    @Test
    fun theRobustSearchFindsASkewBeyondTheNarrowRange() {
        fun cap(deltaMs: Double) = CapturedAudio(48_000, 10_000.0, FloatArray(96_000) { i -> noise(480_000L + i + Math.round(deltaMs * 48)) })
        val ref = ReferenceAudio(48_000, 9_700.0, FloatArray(2600 * 48) { i -> noise(9_700L * 48 + i) })
        val r = Correlator.skewRobust(cap(123.0), ref)!!
        assertTrue("trusted ${r.trusted} wide ${r.wide} corr ${r.confidence}", r.trusted && r.wide)
        assertEquals(123.0, r.deltaMs, 0.05)
        val n = Correlator.skewRobust(cap(-7.5), ref)!!
        assertTrue(n.trusted && !n.wide)
        assertEquals(-7.5, n.deltaMs, 0.05)
    }

    @Test
    fun theRobustSearchDoesNotTrustUnrelatedAudio() {
        val cap = CapturedAudio(48_000, 0.0, FloatArray(96_000) { i -> sin(2 * PI * 61.7 * i / 48_000 + 1.3).toFloat() * sin(2 * PI * 0.37 * i / 48_000).toFloat() })
        val r = Correlator.skewRobust(cap, reference(-300.0, 2600.0))
        assertTrue(r == null || !r.trusted)
    }

    @Test
    fun unrelatedAudioHasLowConfidence() {
        val cap = CapturedAudio(48_000, 0.0, FloatArray(96_000) { i -> sin(2 * PI * 61.7 * i / 48_000 + 1.3).toFloat() * sin(2 * PI * 0.37 * i / 48_000).toFloat() })
        val r = Correlator.skew(cap, reference(-100.0, 2200.0))
        assertTrue(r == null || r.confidence < 0.8)
    }
}
