package org.simpmusic.dj.ml

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Beat This! front-end, replicated from `beat_this.preprocessing.LogMelSpect` (torchaudio `MelSpectrogram`):
 *
 * * mono audio at [SAMPLE_RATE] (22 050 Hz),
 * * STFT: n_fft 1024, hop 441 (=> 50 frames/s), periodic Hann window, `center = true` with reflect padding,
 * * magnitude (power 1), scaled by 1 / sqrt(n_fft) (torchaudio `normalized = "frame_length"`, NOT the window energy),
 * * 128 Slaney-scale, Slaney-formula triangular filters between 30 Hz and 11 kHz (no area normalisation),
 * * `ln1p(1000 * mel)`.
 *
 * Pure Kotlin, no dependencies. The output is row-major `[frames][128]` (time first, exactly the model input).
 */
class MelFrontEnd(
    val sampleRate: Int = SAMPLE_RATE,
    val nFft: Int = N_FFT,
    val hop: Int = HOP,
    val nMels: Int = N_MELS,
    val fMin: Double = F_MIN,
    val fMax: Double = F_MAX,
    private val logMultiplier: Double = 1000.0,
) {
    private val nBins = nFft / 2 + 1
    private val window = DoubleArray(nFft) { 0.5 - 0.5 * cos(2.0 * PI * it / nFft) }
    private val norm = 1.0 / sqrt(nFft.toDouble()) // torchaudio normalized="frame_length": torch.stft(normalized=True) = n_fft^-0.5
    private val fft = Fft(nFft)

    // Triangular filters stored sparsely: filter m covers bins [lo[m], hi[m]] with weights w[m][...].
    private val lo = IntArray(nMels)
    private val hi = IntArray(nMels)
    private val weights: Array<DoubleArray>

    init {
        val allFreqs = DoubleArray(nBins) { it * (sampleRate / 2.0) / (nBins - 1) }
        val mMin = hzToMel(fMin)
        val mMax = hzToMel(fMax)
        val fPts = DoubleArray(nMels + 2) { melToHz(mMin + (mMax - mMin) * it / (nMels + 1)) }
        val fDiff = DoubleArray(nMels + 1) { fPts[it + 1] - fPts[it] }
        weights = Array(nMels) { m ->
            var first = -1
            var last = -1
            val w = DoubleArray(nBins)
            for (k in 0 until nBins) {
                val down = -(fPts[m] - allFreqs[k]) / fDiff[m]
                val up = (fPts[m + 2] - allFreqs[k]) / fDiff[m + 1]
                val v = maxOf(0.0, minOf(down, up))
                w[k] = v
                if (v > 0.0) {
                    if (first < 0) first = k
                    last = k
                }
            }
            if (first < 0) { first = 0; last = 0 }
            lo[m] = first
            hi[m] = last
            w.copyOfRange(first, last + 1)
        }
    }

    /** Number of frames produced for [samples] input samples (torch: `1 + n / hop`). */
    fun frameCount(samples: Int): Int = 1 + samples / hop

    /** Returns `frameCount(samples.size) * nMels` values, frame-major. [samples] must already be at [sampleRate]. */
    fun compute(samples: FloatArray): FloatArray {
        val n = samples.size
        val frames = frameCount(n)
        val out = FloatArray(frames * nMels)
        if (n < 2) return out
        val pad = nFft / 2
        val re = DoubleArray(nFft)
        val im = DoubleArray(nFft)
        val mag = DoubleArray(nBins)
        for (f in 0 until frames) {
            val start = f * hop - pad
            for (i in 0 until nFft) {
                re[i] = sampleAt(samples, start + i) * window[i]
                im[i] = 0.0
            }
            fft.transform(re, im)
            for (k in 0 until nBins) mag[k] = sqrt(re[k] * re[k] + im[k] * im[k]) * norm
            val base = f * nMels
            for (m in 0 until nMels) {
                val w = weights[m]
                val l = lo[m]
                var acc = 0.0
                for (j in w.indices) acc += w[j] * mag[l + j]
                out[base + m] = ln1p(logMultiplier * acc).toFloat()
            }
        }
        return out
    }

    /** Reflect padding as torch (`pad_mode="reflect"`, edge sample not repeated). */
    private fun sampleAt(x: FloatArray, idx: Int): Double {
        var i = idx
        val n = x.size
        if (i < 0) i = -i
        if (i >= n) i = 2 * (n - 1) - i
        if (i < 0) i = 0 // tiny inputs only
        if (i >= n) i = n - 1
        return x[i].toDouble()
    }

    companion object {
        const val SAMPLE_RATE = 22_050
        const val N_FFT = 1024
        const val HOP = 441
        const val N_MELS = 128
        const val F_MIN = 30.0
        const val F_MAX = 11_000.0

        // Slaney mel scale (linear below 1 kHz, logarithmic above), as torchaudio's `_hz_to_mel(..., "slaney")`.
        private const val F_SP = 200.0 / 3.0
        private const val MIN_LOG_HZ = 1000.0
        private const val MIN_LOG_MEL = MIN_LOG_HZ / F_SP
        private val LOG_STEP = ln(6.4) / 27.0

        fun hzToMel(hz: Double): Double =
            if (hz >= MIN_LOG_HZ) MIN_LOG_MEL + ln(hz / MIN_LOG_HZ) / LOG_STEP else hz / F_SP

        fun melToHz(mel: Double): Double =
            if (mel >= MIN_LOG_MEL) MIN_LOG_HZ * exp(LOG_STEP * (mel - MIN_LOG_MEL)) else F_SP * mel
    }
}

/** In-place iterative radix-2 complex FFT of a fixed power-of-two size. */
class Fft(val n: Int) {
    private val cosT = DoubleArray(n / 2) { cos(2.0 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { -sin(2.0 * PI * it / n) }
    private val rev = IntArray(n)

    init {
        require(n >= 2 && (n and (n - 1)) == 0) { "size must be a power of two: $n" }
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) rev[i] = Integer.reverse(i) ushr (32 - bits)
    }

    fun transform(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosT[k]
                    val wi = sinT[k]
                    val xr = re[j + half] * wr - im[j + half] * wi
                    val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr
                    im[j + half] = im[j] - xi
                    re[j] += xr
                    im[j] += xi
                    k += step
                }
                start += size
            }
            size *= 2
        }
    }
}
