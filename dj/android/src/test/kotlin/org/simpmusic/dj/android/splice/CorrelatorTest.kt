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

    @Test
    fun unrelatedAudioHasLowConfidence() {
        val cap = CapturedAudio(48_000, 0.0, FloatArray(96_000) { i -> sin(2 * PI * 61.7 * i / 48_000 + 1.3).toFloat() * sin(2 * PI * 0.37 * i / 48_000).toFloat() })
        val r = Correlator.skew(cap, reference(-100.0, 2200.0))
        assertTrue(r == null || r.confidence < 0.8)
    }
}
