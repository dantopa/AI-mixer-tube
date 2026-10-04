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
    /**
     * Score of the best NON-octave rival divided by the winner's (0..1): near 1 means two unrelated readings (e.g.
     * 3:2 siblings) fit equally well. Octave siblings are excluded: half/double time is a known, planner-handled ambiguity.
     */
    val ambiguity: Double = 0.0,
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

internal class TempoParams(
    /** Prior centre and width (octaves). Wide on purpose: dance music spans 70-180 BPM. */
    val priorCenter: Double = 120.0,
    val priorSigmaOct: Double = 1.0,
    /** Multiples of the period contribute with weight k^-gamma. */
    val gamma: Double = 0.0,
    val maxMultiples: Int = 8,
    /** Extra exponent on the autocorrelation at the period itself (the multiples alone cannot tell 3:2 siblings apart). */
    val directPower: Double = 0.0,
    val useLowBand: Boolean = true,
    val lowBandMinPulse: Double = 0.25,
    val demoteRatio: Double = 0.45,
    val promoteRatio: Double = 0.6,
    val promoteCeiling: Double = 182.0,
    val demoteFloor: Double = 60.0,
)

internal object TempoEstimator {
    const val BPM_MIN = 50.0
    const val BPM_MAX = 220.0
    private const val STEP = 0.25
    private const val MAX_LAG_SEC = 6.0

    /**
     * [env] is the detrended beat envelope, [low] the detrended low-band (kick/bass) flux, used only to decide between
     * octaves: the beat level is the fastest one at which the kick still keeps firing on every period.
     */
    fun estimate(env: FloatArray, low: FloatArray? = null, prm: TempoParams = TempoParams()): TempoEstimate? {
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
            val k = max(1, min(prm.maxMultiples, (maxLag / p).toInt()))
            var s = 0.0; var ws = 0.0
            for (m in 1..k) { val w = Math.pow(m.toDouble(), -prm.gamma); s += w * acf.at(m * p); ws += w }
            val comb = s / ws
            val oct = ln(bpms[i] / prm.priorCenter) / ln(2.0)
            val direct = if (prm.directPower == 0.0) 1.0 else Math.pow(max(acf.at(p), 0.01), prm.directPower)
            score[i] = max(0.0, comb) * direct * exp(-0.5 * (oct / prm.priorSigmaOct) * (oct / prm.priorSigmaOct))
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
        if (low != null && prm.useLowBand) {
            val lowAcf = Acf(low, maxLagFrames)
            if (lowAcf.ok) bpm = decideOctave(bpm, lowAcf, acf, prm)
        }
        val pulse = acf.atBpm(bpm).coerceIn(0.0, 1.0)
        var amb = 0.0
        for (j in peaks) {
            val r = bpms[j] / bpm
            val octave = abs(r - 1) < 0.04 || abs(r - 2) < 0.08 || abs(r - 0.5) < 0.04
            if (!octave) amb = max(amb, score[j] / score[peaks[0]])
        }
        return TempoEstimate(bpm, pulse, prominence, peaks.take(6).map { bpms[it] to score[it] }, amb.coerceIn(0.0, 1.0))
    }

    /** Refines the octave using the low band: is there a kick on every period of this tempo, or only on every other? */
    internal fun decideOctave(start: Double, low: Acf, all: Acf, prm: TempoParams): Double {
        var b = start
        fun lowAt(x: Double) = low.atBpm(x)
        var guard = 0
        while (guard++ < 2) {
            val half = b / 2
            if (half < prm.demoteFloor) break
            val here = lowAt(b); val there = lowAt(half)
            if (max(here, there) >= prm.lowBandMinPulse && here < prm.demoteRatio * there && there > 0.0) b = half else break
        }
        guard = 0
        while (guard++ < 2) {
            val dbl = b * 2
            if (dbl > prm.promoteCeiling) break
            val here = lowAt(b); val there = lowAt(dbl)
            if (max(here, there) >= prm.lowBandMinPulse && there >= prm.promoteRatio * here && there > 0.0 && all.atBpm(dbl) > 0.3) b = dbl else break
        }
        return b
    }
}
