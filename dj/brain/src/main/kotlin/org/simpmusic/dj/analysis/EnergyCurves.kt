package org.simpmusic.dj.analysis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Block RMS envelopes (total and < ~200 Hz) on the 100 ms grid, plus integrated loudness. */
internal class EnergyCurves(
    /** Linear RMS per 100 ms block, before normalisation. */
    val rms: FloatArray,
    val lowRms: FloatArray,
    val loudnessDb: Float,
) {
    /** RMS scaled so the loudest block is 1. */
    fun normalised(a: FloatArray): FloatArray {
        var m = 0f
        for (v in a) if (v > m) m = v
        if (m <= 1e-9f) return FloatArray(a.size)
        return FloatArray(a.size) { a[it] / m }
    }

    companion object {
        fun compute(x: FloatArray): EnergyCurves {
            val hop = Grid.SPEC_HOP
            val blocks = if (x.isEmpty()) 0 else (x.size + hop - 1) / hop
            val rms = FloatArray(blocks); val low = FloatArray(blocks)
            // 4th-order Butterworth-ish low-pass (two RBJ biquads) at 200 Hz.
            val lp = Biquad.lowPass(200.0, Grid.SR.toDouble(), 0.5412)
            val lp2 = Biquad.lowPass(200.0, Grid.SR.toDouble(), 1.3066)
            var total = 0.0
            for (b in 0 until blocks) {
                val s = b * hop; val e = minOf(x.size, s + hop)
                var acc = 0.0; var accLow = 0.0
                for (i in s until e) {
                    val v = x[i].toDouble()
                    acc += v * v
                    val l = lp2.process(lp.process(v))
                    accLow += l * l
                }
                total += acc
                val len = max(1, e - s)
                rms[b] = sqrt(acc / len).toFloat(); low[b] = sqrt(accLow / len).toFloat()
            }
            val meanSq = if (x.isEmpty()) 0.0 else total / x.size
            val db = if (meanSq <= 1e-12) -120f else max(-120.0, 10 * log10(meanSq)).toFloat()
            return EnergyCurves(rms, low, db)
        }
    }
}

internal class Biquad(private val b0: Double, private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double) {
    private var z1 = 0.0
    private var z2 = 0.0
    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    companion object {
        fun lowPass(fc: Double, fs: Double, q: Double): Biquad {
            val w0 = 2 * PI * fc / fs
            val alpha = sin(w0) / (2 * q)
            val c = cos(w0)
            val a0 = 1 + alpha
            return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }
    }
}
