package org.simpmusic.dj.analysis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Fixed analysis grid. Everything downstream works at [SR] Hz. */
internal object Grid {
    const val SR = 22050

    /** Onset STFT: 46 ms window, ~10 ms hop. */
    const val ONSET_N = 1024
    const val ONSET_HOP = 220
    const val FPS = SR.toDouble() / ONSET_HOP // 100.227 frames per second

    /** Spectral STFT (chroma, MFCC, energy grid): 186 ms window, exactly 100 ms hop. */
    const val SPEC_N = 4096
    const val SPEC_HOP = 2205
    const val SPEC_HOP_MS = 100

    fun onsetFrameTimeSec(frame: Double) = frame * ONSET_HOP / SR
    fun secToOnsetFrame(sec: Double) = sec * SR / ONSET_HOP
}

/**
 * Multi-band log-compressed spectral-flux (SuperFlux style: maximum filter across neighbouring bands on the lagged
 * frame suppresses vibrato) computed in a single streaming pass over the signal. Only 3 frames of log spectrum
 * are alive at any time; the outputs are compact per-frame curves.
 */
internal class OnsetFeatures(
    val nFrames: Int,
    /** Half-wave rectified log-spectral flux over all bands (mean per band). */
    val flux: FloatArray,
    /** Same restricted to bands below ~250 Hz (kick, bass). */
    val fluxLow: FloatArray,
    /** Same restricted to bands above ~6 kHz (hats, cymbals). */
    val fluxHigh: FloatArray,
    /** Mean log-band level (not flux) per group. */
    val levelLow: FloatArray,
    val levelHigh: FloatArray,
    val levelAll: FloatArray,
    /** Coarse spectrum: [COARSE] log-band levels per frame (frame-major), for beat-synchronous timbre. */
    val coarse: FloatArray,
) {
    companion object {
        private const val BANDS = 48
        const val COARSE = 16
        private const val F_MIN = 30.0
        private const val F_MAX = 10500.0
        private const val GAMMA = 1000f
        private const val LAG = 2
        private const val LOW_CUT_HZ = 250.0
        private const val HIGH_CUT_HZ = 6000.0

        private fun hz2mel(f: Double) = 2595.0 * log10(1.0 + f / 700.0)
        private fun mel2hz(m: Double) = 700.0 * (Math.pow(10.0, m / 2595.0) - 1.0)

        fun compute(x: FloatArray): OnsetFeatures {
            val n = Grid.ONSET_N
            val hop = Grid.ONSET_HOP
            val nFrames = if (x.size < 1) 0 else x.size / hop + 1
            val flux = FloatArray(nFrames)
            val fluxLow = FloatArray(nFrames)
            val fluxHigh = FloatArray(nFrames)
            val levelLow = FloatArray(nFrames)
            val levelHigh = FloatArray(nFrames)
            val levelAll = FloatArray(nFrames)
            val coarse = FloatArray(nFrames * COARSE)
            if (nFrames == 0) return OnsetFeatures(0, flux, fluxLow, fluxHigh, levelLow, levelHigh, levelAll, coarse)

            val fft = RealFft(n)
            val win = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / n)).toFloat() }
            val binHz = Grid.SR.toDouble() / n
            // Triangular mel filters with unit peak, applied to squared magnitudes.
            val melLo = hz2mel(F_MIN); val melHi = hz2mel(F_MAX)
            val edges = DoubleArray(BANDS + 2) { mel2hz(melLo + (melHi - melLo) * it / (BANDS + 1)) }
            val bandStart = IntArray(BANDS); val bandW = arrayOfNulls<FloatArray>(BANDS)
            var lowEnd = 0; var highStart = BANDS
            for (b in 0 until BANDS) {
                val lo = edges[b]; val c = edges[b + 1]; val hi = edges[b + 2]
                val b0 = max(1, (lo / binHz).toInt()); val b1 = min(n / 2 - 1, (hi / binHz).toInt() + 1)
                val w = FloatArray(b1 - b0 + 1)
                for (k in b0..b1) {
                    val f = k * binHz
                    val v = if (f <= c) (f - lo) / (c - lo) else (hi - f) / (hi - c)
                    w[k - b0] = max(0.0, v).toFloat()
                }
                // A band narrower than one bin would be empty: give it the nearest bin.
                if (w.all { it == 0f }) w[min(w.size - 1, max(0, ((c / binHz).toInt() - b0)))] = 1f
                bandStart[b] = b0; bandW[b] = w
                if (c < LOW_CUT_HZ) lowEnd = b + 1
                if (c <= HIGH_CUT_HZ) highStart = b + 1
            }
            val nLow = max(1, lowEnd); val nHigh = max(1, BANDS - highStart)

            val frame = FloatArray(n)
            val re = FloatArray(n / 2 + 1); val im = FloatArray(n / 2 + 1)
            // Ring of the last LAG+1 log frames.
            val ring = Array(LAG + 1) { FloatArray(BANDS) }
            val maxed = FloatArray(BANDS)
            val norm = 4f / n // full-scale sine -> magnitude 1

            for (t in 0 until nFrames) {
                val start = t * hop - n / 2
                for (k in 0 until n) {
                    val i = start + k
                    frame[k] = if (i >= 0 && i < x.size) x[i] * win[k] else 0f
                }
                fft.forward(frame, re, im)
                val cur = ring[t % (LAG + 1)]
                var sumAll = 0f; var sumLow = 0f; var sumHigh = 0f
                for (b in 0 until BANDS) {
                    val w = bandW[b]!!; val b0 = bandStart[b]
                    var e = 0f
                    for (k in w.indices) {
                        val r = re[b0 + k]; val q = im[b0 + k]
                        e += w[k] * (r * r + q * q)
                    }
                    val level = ln(1f + GAMMA * norm * sqrt(e))
                    cur[b] = level
                    sumAll += level
                    if (b < lowEnd) sumLow += level
                    if (b >= highStart) sumHigh += level
                }
                for (g in 0 until COARSE) {
                    var a = 0f
                    for (b in g * 3 until g * 3 + 3) a += cur[b]
                    coarse[t * COARSE + g] = a / 3f
                }
                levelAll[t] = sumAll / BANDS
                levelLow[t] = sumLow / nLow
                levelHigh[t] = sumHigh / nHigh
                if (t >= LAG) {
                    val prev = ring[(t - LAG) % (LAG + 1)]
                    for (b in 0 until BANDS) {
                        var m = prev[b]
                        if (b > 0 && prev[b - 1] > m) m = prev[b - 1]
                        if (b < BANDS - 1 && prev[b + 1] > m) m = prev[b + 1]
                        maxed[b] = m
                    }
                    var fa = 0f; var fl = 0f; var fh = 0f
                    for (b in 0 until BANDS) {
                        val d = cur[b] - maxed[b]
                        if (d > 0f) {
                            fa += d
                            if (b < lowEnd) fl += d
                            if (b >= highStart) fh += d
                        }
                    }
                    flux[t] = fa / BANDS; fluxLow[t] = fl / nLow; fluxHigh[t] = fh / nHigh
                }
            }
            return OnsetFeatures(nFrames, flux, fluxLow, fluxHigh, levelLow, levelHigh, levelAll, coarse)
        }
    }
}
