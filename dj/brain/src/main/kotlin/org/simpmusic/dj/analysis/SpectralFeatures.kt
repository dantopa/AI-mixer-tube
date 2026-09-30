package org.simpmusic.dj.analysis

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Slow spectral features on a 100 ms grid (long window = good pitch resolution): tuning-corrected harmonic chroma,
 * MFCCs, and a per-frame "active" flag. The STFT is streamed; only spectral peaks and 13+12 floats per frame are kept.
 */
internal class SpectralFeatures(
    val nFrames: Int,
    /** 12 values per frame, each frame L1-normalised (all zeros when the frame is inactive). */
    val chroma: FloatArray,
    /** 13 MFCC (c1..c13) per frame. */
    val mfcc: FloatArray,
    /** True when the frame carries audible content. */
    val active: BooleanArray,
    /** Estimated tuning deviation from A440 in semitones (-0.5..0.5) and how sharp the evidence is (0..1). */
    val tuning: Float,
    val tuningStrength: Float,
) {
    companion object {
        const val MFCC_N = 13
        private const val MEL_BANDS = 40
        private const val MAX_PEAKS = 24
        private const val PEAK_FMIN = 90.0
        private const val PEAK_FMAX = 2600.0
        private const val HARMONICS = 3
        private const val HARMONIC_DECAY = 0.6f

        fun compute(x: FloatArray): SpectralFeatures {
            val n = Grid.SPEC_N
            val hop = Grid.SPEC_HOP
            val nFrames = if (x.isEmpty()) 0 else x.size / hop + 1
            val fft = RealFft(n)
            val win = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / n)).toFloat() }
            val binHz = Grid.SR.toDouble() / n
            val frame = FloatArray(n)
            val re = FloatArray(n / 2 + 1); val im = FloatArray(n / 2 + 1)
            val mag = FloatArray(n / 2 + 1)
            val norm = 4f / n

            // Mel filterbank for MFCC (power domain).
            val fmax = 8000.0; val fmin = 30.0
            fun mel(f: Double) = 2595.0 * log10(1.0 + f / 700.0)
            fun unmel(m: Double) = 700.0 * (10.0.pow(m / 2595.0) - 1.0)
            val edges = DoubleArray(MEL_BANDS + 2) { unmel(mel(fmin) + (mel(fmax) - mel(fmin)) * it / (MEL_BANDS + 1)) }
            val mStart = IntArray(MEL_BANDS); val mW = arrayOfNulls<FloatArray>(MEL_BANDS)
            for (b in 0 until MEL_BANDS) {
                val lo = edges[b]; val c = edges[b + 1]; val hi = edges[b + 2]
                val b0 = max(1, (lo / binHz).toInt()); val b1 = min(n / 2 - 1, (hi / binHz).toInt() + 1)
                val w = FloatArray(b1 - b0 + 1)
                for (k in b0..b1) {
                    val f = k * binHz
                    w[k - b0] = max(0.0, if (f <= c) (f - lo) / (c - lo) else (hi - f) / (hi - c)).toFloat()
                }
                if (w.all { it == 0f }) w[min(w.size - 1, max(0, ((c / binHz).toInt() - b0)))] = 1f
                mStart[b] = b0; mW[b] = w
            }
            val dct = Array(MFCC_N) { k -> FloatArray(MEL_BANDS) { b -> cos(PI * (k + 1) * (b + 0.5) / MEL_BANDS).toFloat() } }

            val mfcc = FloatArray(nFrames * MFCC_N)
            val active = BooleanArray(nFrames)
            val peakF = FloatArray(nFrames * MAX_PEAKS)
            val peakW = FloatArray(nFrames * MAX_PEAKS)
            val peakN = IntArray(nFrames)
            val frameMaxMag = FloatArray(nFrames)
            val logMel = FloatArray(MEL_BANDS)
            var globalMax = 0f

            val binLo = max(2, (PEAK_FMIN / binHz).toInt()); val binHi = min(n / 2 - 2, (PEAK_FMAX / binHz).toInt())
            for (t in 0 until nFrames) {
                val start = t * hop - n / 2
                for (k in 0 until n) {
                    val i = start + k
                    frame[k] = if (i >= 0 && i < x.size) x[i] * win[k] else 0f
                }
                fft.forward(frame, re, im)
                var fm = 0f
                for (k in binLo..binHi) {
                    val m = sqrt(re[k] * re[k] + im[k] * im[k]) * norm
                    mag[k] = m
                    if (m > fm) fm = m
                }
                frameMaxMag[t] = fm
                if (fm > globalMax) globalMax = fm
                // MFCC
                for (b in 0 until MEL_BANDS) {
                    val w = mW[b]!!; val b0 = mStart[b]
                    var e = 0f
                    for (k in w.indices) { val r = re[b0 + k]; val q = im[b0 + k]; e += w[k] * (r * r + q * q) }
                    logMel[b] = ln(1e-10f + e * norm * norm)
                }
                for (k in 0 until MFCC_N) {
                    var s = 0f
                    val d = dct[k]
                    for (b in 0 until MEL_BANDS) s += d[b] * logMel[b]
                    mfcc[t * MFCC_N + k] = s / MEL_BANDS
                }
                // Peak picking (top MAX_PEAKS local maxima above 3% of the frame maximum).
                if (fm > 1e-5f) {
                    val thr = fm * 0.03f
                    var cnt = 0
                    val base = t * MAX_PEAKS
                    for (k in binLo + 1 until binHi) {
                        val m = mag[k]
                        if (m >= thr && m > mag[k - 1] && m >= mag[k + 1]) {
                            // parabolic interpolation on log magnitude
                            val a = ln(mag[k - 1] + 1e-12f); val b = ln(m + 1e-12f); val c = ln(mag[k + 1] + 1e-12f)
                            val den = a - 2 * b + c
                            val d = if (den < 0f) (0.5f * (a - c) / den).coerceIn(-0.5f, 0.5f) else 0f
                            val f = ((k + d) * binHz).toFloat()
                            val w = (ln(1f + 20f * m / fm) / ln(21f))
                            if (cnt < MAX_PEAKS) {
                                peakF[base + cnt] = f; peakW[base + cnt] = w; cnt++
                            } else {
                                var mi = 0
                                for (j in 1 until MAX_PEAKS) if (peakW[base + j] < peakW[base + mi]) mi = j
                                if (w > peakW[base + mi]) { peakF[base + mi] = f; peakW[base + mi] = w }
                            }
                        }
                    }
                    peakN[t] = cnt
                }
            }
            val gate = globalMax * 0.003f // -50 dB below the loudest frame
            for (t in 0 until nFrames) active[t] = frameMaxMag[t] > gate && peakN[t] > 0

            // Tuning: circular mean of the peaks' distance to the nearest semitone (A440 grid), magnitude weighted.
            var sx = 0.0; var sy = 0.0; var sw = 0.0
            for (t in 0 until nFrames) {
                if (!active[t]) continue
                for (j in 0 until peakN[t]) {
                    val f = peakF[t * MAX_PEAKS + j]
                    if (f < 100f || f > 1500f) continue
                    val semis = 12 * log2(f / 440.0)
                    val ang = 2 * PI * (semis - floor(semis))
                    val w = peakW[t * MAX_PEAKS + j].toDouble()
                    sx += w * cos(ang); sy += w * sin(ang); sw += w
                }
            }
            var tuning = 0f; var tuningStrength = 0f
            if (sw > 0) {
                var dev = atan2(sy, sx) / (2 * PI) // -0.5..0.5 semitone offset of the grid
                if (dev > 0.5) dev -= 1.0
                tuning = dev.toFloat()
                tuningStrength = (sqrt(sx * sx + sy * sy) / sw).toFloat()
            }
            // Only trust a tuning offset when the evidence is clearly peaked; otherwise assume A440.
            if (tuningStrength < 0.35f) tuning = 0f

            val chroma = FloatArray(nFrames * 12)
            for (t in 0 until nFrames) {
                if (!active[t]) continue
                val c = FloatArray(12)
                for (j in 0 until peakN[t]) {
                    val f = peakF[t * MAX_PEAKS + j].toDouble()
                    val w = peakW[t * MAX_PEAKS + j]
                    var wh = w
                    for (h in 1..HARMONICS) {
                        val fund = f / h
                        if (fund < 60.0) break
                        val pitch = 12 * log2(fund / 440.0) + 69.0 - tuning
                        val r = pitch.roundToInt()
                        val dev = abs(pitch - r)
                        if (dev < 0.5) {
                            // soft assignment to the nearest semitone, tapering to the boundary
                            val taper = (1.0 - dev * dev * 2.0).coerceAtLeast(0.5).toFloat()
                            c[((r % 12) + 12) % 12] += wh * taper
                        }
                        wh *= HARMONIC_DECAY
                    }
                }
                var s = 0f
                for (v in c) s += v
                if (s > 0f) for (i in 0 until 12) chroma[t * 12 + i] = c[i] / s
            }
            return SpectralFeatures(nFrames, chroma, mfcc, active, tuning, tuningStrength)
        }
    }
}
