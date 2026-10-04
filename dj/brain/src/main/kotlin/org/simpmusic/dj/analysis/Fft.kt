package org.simpmusic.dj.analysis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Real-input FFT of power-of-two size [n], built on a complex FFT of size n/2. Float arithmetic, tables
 * precomputed in double. Not thread-safe (owns scratch buffers): use one instance per thread.
 */
class RealFft(val n: Int) {
    private val half = n / 2
    private val bitrev = IntArray(half)
    private val cosT = FloatArray(half / 2 + 1)
    private val sinT = FloatArray(half / 2 + 1)
    private val postCos = FloatArray(half + 1)
    private val postSin = FloatArray(half + 1)
    private val zr = FloatArray(half)
    private val zi = FloatArray(half)

    init {
        require(n >= 4 && n and (n - 1) == 0) { "n must be a power of two >= 4" }
        val bits = Integer.numberOfTrailingZeros(half)
        for (i in 0 until half) bitrev[i] = if (bits == 0) 0 else Integer.reverse(i) ushr (32 - bits)
        for (i in cosT.indices) {
            val a = -2.0 * PI * i / half
            cosT[i] = cos(a).toFloat(); sinT[i] = sin(a).toFloat()
        }
        for (k in 0..half) {
            val a = -2.0 * PI * k / n
            postCos[k] = cos(a).toFloat(); postSin[k] = sin(a).toFloat()
        }
    }

    /**
     * Forward transform of [x] (length n, already windowed). Writes bins 0..n/2 to [re]/[im]
     * (both at least n/2+1 long).
     */
    fun forward(x: FloatArray, re: FloatArray, im: FloatArray) {
        for (i in 0 until half) {
            val j = bitrev[i]
            zr[j] = x[2 * i]; zi[j] = x[2 * i + 1]
        }
        var size = 2
        while (size <= half) {
            val h = size / 2
            val step = half / size
            var start = 0
            while (start < half) {
                var k = 0
                for (j in start until start + h) {
                    val wr = cosT[k]; val wi = sinT[k]
                    val a = j + h
                    val tr = zr[a] * wr - zi[a] * wi
                    val ti = zr[a] * wi + zi[a] * wr
                    zr[a] = zr[j] - tr; zi[a] = zi[j] - ti
                    zr[j] += tr; zi[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
        // Split the packed spectrum into the real-signal spectrum.
        re[0] = zr[0] + zi[0]; im[0] = 0f
        re[half] = zr[0] - zi[0]; im[half] = 0f
        for (k in 1 until half) {
            val a = half - k
            val er = 0.5f * (zr[k] + zr[a]); val ei = 0.5f * (zi[k] - zi[a])
            val orr = 0.5f * (zi[k] + zi[a]); val oi = -0.5f * (zr[k] - zr[a])
            val c = postCos[k]; val s = postSin[k]
            re[k] = er + orr * c - oi * s
            im[k] = ei + orr * s + oi * c
        }
    }
}

/** Complex FFT for autocorrelation-sized transforms (double precision, in place). */
internal object ComplexFft {
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * (if (inverse) 1 else -1)
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }
}
