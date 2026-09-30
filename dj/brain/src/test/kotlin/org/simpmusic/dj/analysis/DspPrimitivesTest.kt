package org.simpmusic.dj.analysis

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DspPrimitivesTest {
    @Test
    fun realFftMatchesNaiveDft() {
        for (n in intArrayOf(8, 64, 1024)) {
            val rnd = Random(n)
            val x = FloatArray(n) { rnd.nextFloat() * 2 - 1 }
            val re = FloatArray(n / 2 + 1); val im = FloatArray(n / 2 + 1)
            RealFft(n).forward(x, re, im)
            var worst = 0.0
            for (k in 0..n / 2) {
                var sr = 0.0; var si = 0.0
                for (t in 0 until n) { val a = -2 * PI * k * t / n; sr += x[t] * cos(a); si += x[t] * sin(a) }
                worst = maxOf(worst, abs(sr - re[k]), abs(si - im[k]))
            }
            assertTrue(worst < 2e-3 * sqrt(n.toDouble()), "n=$n worst error $worst")
        }
    }

    private fun tone(freq: Double, sr: Int, seconds: Double, amp: Double = 0.5) =
        FloatArray((sr * seconds).toInt()) { (amp * sin(2 * PI * freq * it / sr)).toFloat() }

    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += x[i] * x[i]
        return sqrt(s / (to - from))
    }

    @Test
    fun resamplerKeepsInBandTonesAndKillsAliases() {
        val pairs = listOf(44100 to 22050, 48000 to 22050, 32000 to 22050, 8000 to 22050, 22050 to 22050)
        for ((src, dst) in pairs) {
            val x = tone(1000.0, src, 2.0)
            val y = Resampler.resample(x, src, dst)
            assertTrue(abs(x.size.toLong() * dst / src - y.size) <= 1)
            val inRms = rms(x, src / 2, x.size - src / 2)
            val outRms = rms(y, dst / 2, y.size - dst / 2)
            assertEquals(1.0, outRms / inRms, 0.01, "$src->$dst in-band gain")
        }
        // a 15 kHz tone at 44.1 kHz would alias to 7.05 kHz if not filtered away first
        val hi = tone(15000.0, 44100, 2.0)
        val y = Resampler.resample(hi, 44100, 22050)
        val att = 20 * log10(rms(y, 11025, y.size - 11025) / rms(hi, 22050, hi.size - 22050))
        assertTrue(att < -60, "alias attenuation only $att dB")
    }

    @Test
    fun resamplerHasNoDelay() {
        // an impulse must stay where it was (zero-phase kernel): the analysis grid relies on it
        val x = FloatArray(44100)
        x[20000] = 1f
        val y = Resampler.resample(x, 44100, 22050)
        var peak = 0
        for (i in y.indices) if (abs(y[i]) > abs(y[peak])) peak = i
        assertEquals(10000, peak)
    }
}

class ConfidenceSanitisingTest {
    @kotlin.test.Test
    fun nanAndOutOfRangeBecomeValidConfidences() {
        kotlin.test.assertEquals(0f, unit(Double.NaN))
        kotlin.test.assertEquals(0f, unit(-3.0))
        kotlin.test.assertEquals(1f, unit(7.0))
        kotlin.test.assertEquals(0.25f, unit(0.25))
    }
}
