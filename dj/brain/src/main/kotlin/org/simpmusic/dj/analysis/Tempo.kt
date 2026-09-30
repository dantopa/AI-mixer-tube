package org.simpmusic.dj.analysis

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal object OnsetEnvelope {
    /**
     * Beat-tracking envelope: overall flux plus a boost for the low bands (kick), each scaled by its own mean so the
     * mix does not depend on the track's level. Kicks sit on the beat far more often than hats do, which is what
     * keeps the tracker off the off-beat.
     */
    fun combined(f: OnsetFeatures): FloatArray {
        val n = f.nFrames
        val out = FloatArray(n)
        val mt = mean(f.flux); val ml = mean(f.fluxLow)
        if (mt <= 1e-9f) return out
        val wl = if (ml > 1e-9f) LOW_WEIGHT / ml else 0f
        for (i in 0 until n) out[i] = f.flux[i] / mt + f.fluxLow[i] * wl
        return out
    }

    private const val LOW_WEIGHT = 0.75f

    /** Subtract a ~1 s moving average, clip at zero, scale to unit standard deviation (of the result). */
    fun detrend(raw: FloatArray, windowFrames: Int = 100): FloatArray {
        val n = raw.size
        val out = FloatArray(n)
        if (n == 0) return out
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + raw[i]
        val h = windowFrames / 2
        for (i in 0 until n) {
            val a = max(0, i - h); val b = min(n, i + h + 1)
            val m = (prefix[b] - prefix[a]) / (b - a)
            out[i] = max(0.0, raw[i] - m).toFloat()
        }
        var s = 0.0
        for (v in out) s += v.toDouble() * v
        val sd = sqrt(s / n)
        if (sd > 1e-9) for (i in 0 until n) out[i] = (out[i] / sd).toFloat()
        return out
    }

    fun mean(a: FloatArray): Float {
        if (a.isEmpty()) return 0f
        var s = 0.0
        for (v in a) s += v
        return (s / a.size).toFloat()
    }
}

internal class TempoEstimate(
    /** Most plausible tempo (after prior / octave decision). */
    val bpm: Double,
    /** Normalised autocorrelation of the onset envelope at that tempo's period (0..1); the raw periodicity strength. */
    val pulse: Double,
    /** Peak height over the mean of the tempo score curve, a scale-free "how much does this stand out" (>=1). */
    val prominence: Double,
    /** Other tempo candidates (bpm, score), best first; the octave siblings live here. */
    val alternates: List<Pair<Double, Double>>,
)

/** Zero-mean, unbiased, normalised autocorrelation of an envelope, evaluable at fractional lags. */
internal class Acf(env: FloatArray, maxLagWanted: Int) {
    val ok: Boolean
    private val acs: DoubleArray
    val maxLag: Int

    init {
        val n = env.size
        var size = 1
        while (size < 2 * n) size = size shl 1
        val re = DoubleArray(size); val im = DoubleArray(size)
        var mean = 0.0
        for (v in env) mean += v
        mean /= max(1, n)
        for (i in 0 until n) re[i] = env[i] - mean
        ComplexFft.transform(re, im, false)
        for (i in 0 until size) { re[i] = re[i] * re[i] + im[i] * im[i]; im[i] = 0.0 }
        ComplexFft.transform(re, im, true)
        val ac0 = re[0]
        maxLag = min(maxLagWanted, n / 2)
        ok = ac0 > 1e-9
        val ac = DoubleArray(maxLag + 2)
        if (ok) for (l in 0..maxLag) ac[l] = re[l] / ac0 * n.toDouble() / (n - l)
        acs = DoubleArray(ac.size)
        for (l in ac.indices) {
            acs[l] = 0.25 * ac[max(0, l - 1)] + 0.5 * ac[l] + 0.25 * ac[min(ac.size - 1, l + 1)]
        }
    }

    fun at(lag: Double): Double {
        if (lag < 1 || lag >= maxLag) return 0.0
        val i = floor(lag).toInt(); val f = lag - i
        return acs[i] * (1 - f) + acs[i + 1] * f
    }

    fun atBpm(bpm: Double) = at(60.0 * Grid.FPS / bpm)
}

internal object TempoEstimator {
    const val BPM_MIN = 50.0
    const val BPM_MAX = 220.0
    private const val STEP = 0.25
    private const val MAX_LAG_SEC = 6.0

    /** Prior centre and width (octaves). Wide on purpose: dance music spans 70-180 BPM. */
    var priorCenter = 120.0
    var priorSigmaOct = 1.0

    /** Octave decision thresholds (see [decideOctave]). */
    var lowBandMinPulse = 0.25
    var demoteRatio = 0.45
    var promoteRatio = 0.6
    var promoteCeiling = 182.0
    var demoteFloor = 60.0

    /**
     * [env] is the detrended beat envelope, [low] the detrended low-band (kick/bass) flux, used only to decide between
     * octaves: the beat level is the fastest one at which the kick still keeps firing on every period.
     */
    fun estimate(env: FloatArray, low: FloatArray? = null): TempoEstimate? {
        val n = env.size
        val fps = Grid.FPS
        if (n < fps * 4) return null
        val maxLagFrames = (MAX_LAG_SEC * fps).toInt() + 2
        val acf = Acf(env, maxLagFrames)
        if (!acf.ok) return null
        val maxLag = acf.maxLag
        val count = ((BPM_MAX - BPM_MIN) / STEP).toInt() + 1
        val bpms = DoubleArray(count) { BPM_MIN + it * STEP }
        val score = DoubleArray(count)
        for (i in 0 until count) {
            val p = 60.0 * fps / bpms[i]
            val k = max(1, min(8, (maxLag / p).toInt()))
            var s = 0.0
            for (m in 1..k) s += acf.at(m * p)
            val comb = s / k
            val oct = ln(bpms[i] / priorCenter) / ln(2.0)
            score[i] = max(0.0, comb) * exp(-0.5 * (oct / priorSigmaOct) * (oct / priorSigmaOct))
        }
        val peaks = ArrayList<Int>()
        for (i in 1 until count - 1) if (score[i] > 0 && score[i] >= score[i - 1] && score[i] > score[i + 1]) peaks.add(i)
        if (peaks.isEmpty()) return null
        peaks.sortByDescending { score[it] }
        var meanScore = 0.0
        for (v in score) meanScore += v
        meanScore /= count
        var bpm = bpms[peaks[0]]
        val prominence = if (meanScore > 0) score[peaks[0]] / meanScore else 1.0
        if (low != null) {
            val lowAcf = Acf(low, maxLagFrames)
            if (lowAcf.ok) bpm = decideOctave(bpm, lowAcf, acf)
        }
        // snap to the strongest nearby lag of the plain autocorrelation (the comb grid is only 0.25 BPM fine)
        val pulse = acf.atBpm(bpm).coerceIn(0.0, 1.0)
        return TempoEstimate(bpm, pulse, prominence, peaks.take(6).map { bpms[it] to score[it] })
    }

    /** Refines the octave using the low band: is there a kick on every period of this tempo, or only on every other? */
    internal fun decideOctave(start: Double, low: Acf, all: Acf): Double {
        var b = start
        fun lowAt(x: Double) = low.atBpm(x)
        // demote: at this tempo the kick is not firing every period, but it does at half the tempo
        var guard = 0
        while (guard++ < 2) {
            val half = b / 2
            if (half < demoteFloor) break
            val here = lowAt(b); val there = lowAt(half)
            if (max(here, there) >= lowBandMinPulse && here < demoteRatio * there && there > 0.0) b = half else break
        }
        // promote: the kick keeps firing every period at double the tempo
        guard = 0
        while (guard++ < 2) {
            val dbl = b * 2
            if (dbl > promoteCeiling) break
            val here = lowAt(b); val there = lowAt(dbl)
            if (max(here, there) >= lowBandMinPulse && there >= promoteRatio * here && there > 0.0 && all.atBpm(dbl) > 0.3) b = dbl else break
        }
        return b
    }
}
