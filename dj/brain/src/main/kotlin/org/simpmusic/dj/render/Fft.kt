package org.simpmusic.dj.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place radix-2 complex FFT on split real/imag double arrays. Preallocated tables, no allocation per call. */
class Fft(val size: Int) {
    private val levels: Int = size.countTrailingZeroBits()
    private val cosTable = DoubleArray(size / 2)
    private val sinTable = DoubleArray(size / 2)
    private val rev = IntArray(size)

    init {
        require(size >= 2 && (size and (size - 1)) == 0) { "FFT size must be a power of two, was $size" }
        for (i in 0 until size / 2) {
            cosTable[i] = cos(2.0 * PI * i / size)
            sinTable[i] = sin(2.0 * PI * i / size)
        }
        for (i in 0 until size) {
            var r = 0
            var v = i
            for (b in 0 until levels) { r = (r shl 1) or (v and 1); v = v shr 1 }
            rev[i] = r
        }
    }

    /** Forward transform (e^{-i...}); unnormalised. */
    fun forward(re: DoubleArray, im: DoubleArray) = transform(re, im, false)

    /** Inverse transform, scaled by 1/N. */
    fun inverse(re: DoubleArray, im: DoubleArray) {
        transform(re, im, true)
        val s = 1.0 / size
        for (i in 0 until size) {
            re[i] *= s
            im[i] *= s
        }
    }

    private fun transform(re: DoubleArray, im: DoubleArray, inv: Boolean) {
        for (i in 0 until size) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        val sgn = if (inv) 1.0 else -1.0
        var half = 1
        while (half < size) {
            val step = size / (half * 2)
            var i = 0
            while (i < size) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cosTable[k]
                    val wi = sgn * sinTable[k]
                    val l = j + half
                    val tr = re[l] * wr - im[l] * wi
                    val ti = re[l] * wi + im[l] * wr
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                i += half * 2
            }
            half *= 2
        }
    }
}
