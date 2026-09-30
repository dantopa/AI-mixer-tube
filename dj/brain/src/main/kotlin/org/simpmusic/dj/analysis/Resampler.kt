package org.simpmusic.dj.analysis

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Polyphase windowed-sinc (Kaiser) rational resampler. Low-pass at ~0.47 of the lower rate, ~-80 dB stop band.
 * Phones and the desktop share it; nothing platform specific.
 */
object Resampler {
    private const val ZERO_CROSSINGS = 10
    private const val MAX_PHASES = 1024
    private const val KAISER_BETA = 8.6

    fun resample(x: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        require(srcRate > 0 && dstRate > 0)
        if (srcRate == dstRate) return x.copyOf()
        if (x.isEmpty()) return FloatArray(0)
        val g = gcd(srcRate, dstRate)
        val m = (srcRate / g).toLong() // input step per L outputs
        val l = (dstRate / g).toLong()
        val phases = min(l, MAX_PHASES.toLong()).toInt()
        // Cut-off as a fraction of the SOURCE rate.
        val cutoff = 0.47 * min(1.0, dstRate.toDouble() / srcRate)
        val halfWidth = ceil(ZERO_CROSSINGS / (2 * cutoff)).toInt()
        val taps = 2 * halfWidth
        val table = Array(phases + 1) { p ->
            val frac = p.toDouble() / phases
            val c = FloatArray(taps)
            var sum = 0.0
            val h = DoubleArray(taps)
            for (k in 0 until taps) {
                val t = (k - halfWidth + 1) - frac // distance (src samples) from the output instant
                h[k] = 2 * cutoff * sinc(2 * cutoff * t) * kaiser(t / halfWidth)
                sum += h[k]
            }
            for (k in 0 until taps) c[k] = (h[k] / sum).toFloat()
            c
        }
        val outLen = (x.size.toLong() * l / m).toInt()
        val out = FloatArray(outLen)
        val n = x.size
        for (j in 0 until outLen) {
            val pos = j.toLong() * m
            val base = (pos / l).toInt()
            val r = (pos % l).toInt()
            val p = if (l <= MAX_PHASES) r else ((r.toLong() * phases + l / 2) / l).toInt()
            val c = table[p]
            val first = base - halfWidth + 1
            var acc = 0f
            if (first >= 0 && first + taps <= n) {
                for (k in 0 until taps) acc += x[first + k] * c[k]
            } else {
                for (k in 0 until taps) {
                    val idx = first + k
                    if (idx in 0 until n) acc += x[idx] * c[k]
                }
            }
            out[j] = acc
        }
        return out
    }

    private fun gcd(a: Int, b: Int): Int { var x = a; var y = b; while (y != 0) { val t = x % y; x = y; y = t }; return x }
    private fun sinc(x: Double) = if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)

    private fun kaiser(r: Double): Double {
        if (abs(r) >= 1.0) return 0.0
        return bessel0(KAISER_BETA * sqrt(1 - r * r)) / bessel0(KAISER_BETA)
    }

    private fun bessel0(x: Double): Double {
        var sum = 1.0; var term = 1.0; var k = 1
        while (k < 60) {
            term *= (x / (2 * k)) * (x / (2 * k)); sum += term
            if (term < 1e-12 * sum) break
            k++
        }
        return sum
    }
}
