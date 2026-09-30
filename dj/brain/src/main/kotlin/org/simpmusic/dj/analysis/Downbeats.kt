package org.simpmusic.dj.analysis

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Per-beat accent evidence plus the bar structure derived from it. */
internal class DownbeatResult(
    val beatsPerBar: Int,
    /** Index (into the beat list) of the first downbeat, 0 until beatsPerBar. */
    val phase: Int,
    /** 0..1 honesty-calibrated confidence of the phase decision. */
    val phaseConfidence: Double,
    /** 0..1 confidence of the 3-vs-4 decision (low when the accent pattern is weak). */
    val meterConfidence: Double,
    /** Combined per-beat accent score (higher = more downbeat-like). */
    val accent: DoubleArray,
    /** Raw per-beat features, exposed for phrase / section analysis. */
    val features: BeatFeatures,
)

internal class BeatFeatures(
    val low: DoubleArray,
    val total: DoubleArray,
    val high: DoubleArray,
    val lowStep: DoubleArray,
    val chroma: DoubleArray,
)

/**
 * Downbeat and meter estimation. Every beat is scored on: low-band onset (kick weight on the one), total onset,
 * cymbal/crash step in the highs, bass entering (low-level step) and chord/chroma change across the beat. The best
 * phase is the one whose beats stand out most consistently. Meter is 4 unless a 3-beat accent cycle is clearly
 * stronger than a 4-beat one.
 */
internal object DownbeatAnalyzer {
    private const val W_CHROMA = 1.5
    private const val W_LOW = 1.0
    private const val W_HIGH = 0.7
    private const val W_LOWSTEP = 0.7
    private const val W_TOTAL = 0.5

    fun beatFeatures(beats: DoubleArray, on: OnsetFeatures, sp: SpectralFeatures): BeatFeatures {
        val n = beats.size
        val low = DoubleArray(n); val tot = DoubleArray(n); val hi = DoubleArray(n)
        val lowStep = DoubleArray(n); val chroma = DoubleArray(n)
        val fps = Grid.FPS
        fun meanRange(a: FloatArray, t0: Double, t1: Double): Double {
            val f0 = max(0, (t0 * fps).toInt()); val f1 = min(a.size - 1, (t1 * fps).toInt())
            if (f1 < f0) return Double.NaN
            var s = 0.0
            for (f in f0..f1) s += a[f]
            return s / (f1 - f0 + 1)
        }
        for (i in 0 until n) {
            val tb = beats[i]
            val period = when {
                i + 1 < n && i > 0 -> (beats[i + 1] - beats[i - 1]) / 2
                i + 1 < n -> beats[i + 1] - beats[i]
                else -> beats[i] - beats[i - 1]
            }
            val f = (tb * fps).toInt()
            var ml = 0f; var mt = 0f
            for (d in -2..2) {
                val j = f + d
                if (j in 0 until on.nFrames) { ml = max(ml, on.fluxLow[j]); mt = max(mt, on.flux[j]) }
            }
            low[i] = ml.toDouble(); tot[i] = mt.toDouble()
            val span = min(0.25, 0.6 * period)
            val stepHi = meanRange(on.levelHigh, tb, tb + span) - meanRange(on.levelHigh, tb - span, tb - 0.02)
            val stepLow = meanRange(on.levelLow, tb, tb + span) - meanRange(on.levelLow, tb - span, tb - 0.02)
            hi[i] = if (stepHi.isNaN()) 0.0 else stepHi
            lowStep[i] = if (stepLow.isNaN()) 0.0 else stepLow
            chroma[i] = chromaChange(sp, tb, period)
        }
        return BeatFeatures(low, tot, hi, lowStep, chroma)
    }

    /** 1 - cosine of the mean chroma over ~2 beats before vs after [tb], skipping the frames that straddle it. */
    private fun chromaChange(sp: SpectralFeatures, tb: Double, period: Double): Double {
        val guard = 0.1
        val span = 2 * period
        val a = meanChroma(sp, tb - span, tb - guard)
        val b = meanChroma(sp, tb + guard, tb + span)
        if (a == null || b == null) return 0.0
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (k in 0 until 12) { dot += a[k] * b[k]; na += a[k] * a[k]; nb += b[k] * b[k] }
        if (na <= 1e-12 || nb <= 1e-12) return 0.0
        return 1.0 - dot / sqrt(na * nb)
    }

    private fun meanChroma(sp: SpectralFeatures, t0: Double, t1: Double): DoubleArray? {
        val j0 = max(0, Math.ceil(t0 * 1000.0 / Grid.SPEC_HOP_MS).toInt())
        val j1 = min(sp.nFrames - 1, Math.floor(t1 * 1000.0 / Grid.SPEC_HOP_MS).toInt())
        if (j1 < j0) return null
        val acc = DoubleArray(12); var cnt = 0
        for (j in j0..j1) {
            if (!sp.active[j]) continue
            for (k in 0 until 12) acc[k] += sp.chroma[j * 12 + k]
            cnt++
        }
        if (cnt == 0) return null
        return acc
    }

    fun analyze(beats: DoubleArray, on: OnsetFeatures, sp: SpectralFeatures): DownbeatResult? {
        val n = beats.size
        if (n < 8) return null
        val f = beatFeatures(beats, on, sp)
        val accent = DoubleArray(n)
        addZ(accent, f.chroma, W_CHROMA); addZ(accent, f.low, W_LOW); addZ(accent, f.high, W_HIGH)
        addZ(accent, f.lowStep, W_LOWSTEP); addZ(accent, f.total, W_TOTAL)

        // meter: does the accent repeat every 3 beats or every 4?
        val ac = autocorr(accent, 12)
        val s3 = (ac[3] + ac[6]) / 2
        val s4 = (ac[4] + ac[8]) / 2
        val three = s3 > s4 + 0.12 && s3 > 0.12
        val bpb = if (three) 3 else 4
        val meterConf = if (n < 16) 0.2 else (if (three) ((s3 - s4) / 0.5) else ((s4 - s3) / 0.5 + 0.3)).coerceIn(0.0, 1.0)

        // phase
        val sums = DoubleArray(bpb); val cnt = IntArray(bpb)
        for (i in 0 until n) { sums[i % bpb] += accent[i]; cnt[i % bpb]++ }
        val means = DoubleArray(bpb) { sums[it] / max(1, cnt[it]) }
        var best = 0
        for (p in 1 until bpb) if (means[p] > means[best]) best = p
        var second = -1
        for (p in 0 until bpb) if (p != best && (second < 0 || means[p] > means[second])) second = p
        var variance = 0.0; var mean = 0.0
        for (v in accent) mean += v
        mean /= n
        for (v in accent) variance += (v - mean) * (v - mean)
        val sd = sqrt(variance / n).coerceAtLeast(1e-9)
        val perCount = n.toDouble() / bpb
        val z = (means[best] - means[second]) / (sd * sqrt(2.0 / perCount))
        val conf = (1 - exp(-z / 6.0)).coerceIn(0.0, 1.0)
        return DownbeatResult(bpb, best, conf, meterConf, accent, f)
    }

    private fun addZ(target: DoubleArray, x: DoubleArray, w: Double) {
        var m = 0.0
        for (v in x) m += v
        m /= x.size
        var s = 0.0
        for (v in x) s += (v - m) * (v - m)
        val sd = sqrt(s / x.size)
        if (sd < 1e-9) return
        for (i in x.indices) target[i] += w * (x[i] - m) / sd
    }

    private fun autocorr(x: DoubleArray, maxLag: Int): DoubleArray {
        val n = x.size
        var m = 0.0
        for (v in x) m += v
        m /= n
        var den = 0.0
        for (v in x) den += (v - m) * (v - m)
        val out = DoubleArray(maxLag + 1)
        if (den < 1e-12) return out
        for (l in 0..maxLag) {
            var s = 0.0
            for (i in 0 until n - l) s += (x[i] - m) * (x[i + l] - m)
            out[l] = s / den * n / max(1, n - l)
        }
        return out
    }
}
