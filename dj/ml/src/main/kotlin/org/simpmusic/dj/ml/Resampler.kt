package org.simpmusic.dj.ml

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Band-limited polyphase resampler (Kaiser-windowed sinc) for turning whatever the decoder produced (44.1/48 kHz...)
 * into the 22 050 Hz the model wants. The reference uses `soxr`; any clean low-pass is close enough for a beat
 * tracker (the model only looks below 11 kHz), and the real-audio test compares the end result.
 */
object Resampler {
    private const val ZERO_CROSSINGS = 24
    private const val KAISER_BETA = 9.0

    fun resample(input: FloatArray, inRate: Int, outRate: Int): FloatArray {
        if (inRate == outRate) return input
        require(inRate > 0 && outRate > 0)
        val g = gcd(inRate, outRate)
        val up = outRate / g
        val down = inRate / g
        val nOut = ((input.size.toLong() * up) / down).toInt()
        val out = FloatArray(nOut)
        // Everything is expressed in input samples. `scale` is the low-pass cut-off as a fraction of the input
        // Nyquist (1.0 when going up, outRate/inRate when going down, with a little guard band).
        val scale = minOf(1.0, outRate.toDouble() / inRate) * 0.97
        val halfWidth = ZERO_CROSSINGS / scale
        val radius = ceil(halfWidth).toInt()
        val taps = 2 * radius + 1
        val i0 = bessel0(KAISER_BETA)
        // Output j sits at input position j*down/up = (j*down/up).floor + phase/up, phase = (j*down) % up.
        val phases = Array(up) { p ->
            val frac = p.toDouble() / up
            DoubleArray(taps) { t ->
                val x = (t - radius) - frac
                val r = x / halfWidth
                if (abs(r) >= 1.0) 0.0 else {
                    val w = bessel0(KAISER_BETA * sqrt(1.0 - r * r)) / i0
                    val s = if (abs(x) < 1e-12) 1.0 else sin(PI * scale * x) / (PI * scale * x)
                    scale * s * w
                }
            }
        }
        for (j in 0 until nOut) {
            val pos = j.toLong() * down
            val center = (pos / up).toInt()
            val ph = phases[(pos % up).toInt()]
            val start = center - radius
            var acc = 0.0
            for (t in 0 until taps) {
                val idx = start + t
                if (idx >= 0 && idx < input.size) acc += ph[t] * input[idx]
            }
            out[j] = acc.toFloat()
        }
        return out
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    private fun bessel0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        var k = 1
        while (k < 80) {
            term *= (x / (2.0 * k)) * (x / (2.0 * k))
            sum += term
            if (term < 1e-12 * sum) break
            k++
        }
        return sum
    }
}
