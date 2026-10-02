package org.simpmusic.dj.android.splice

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Mono reference audio from OUR decode (the one the window was rendered from), first sample at source time [startMs]. */
class ReferenceAudio(
    val sampleRate: Int,
    val startMs: Double,
    val samples: FloatArray,
) {
    val endMs: Double get() = startMs + samples.size * 1000.0 / sampleRate
}

/**
 * Finds the decode skew between a deck's own audio (ExoPlayer's decode, as captured by its [SpliceEngine]) and our
 * decode of the same track: the `delta` (ms) for which `captured(t) == reference(t + delta)`.
 *
 * Both are the same samples from (usually) the same codec, so the normalised cross-correlation peaks near 1 at the
 * right lag; a coarse search on 4x-decimated audio over +-[maxLagMs], then a full-rate search around the best coarse
 * lag, then a parabola through the peak for the fraction of a sample.
 */
object Correlator {
    class Result(
        val deltaMs: Double,
        /** Normalised correlation at the peak, 0..1. */
        val confidence: Double,
        /** Clear enough to act on. [skew] leaves this to the caller's threshold; [skewRobust] decides it. */
        val trusted: Boolean = true,
        /** Found by [skewRobust]'s wide search. */
        val wide: Boolean = false,
    )

    fun skew(
        captured: CapturedAudio,
        reference: ReferenceAudio,
        maxLagMs: Double = 60.0,
    ): Result? {
        val rate = reference.sampleRate
        val c = if (captured.sampleRate == rate) captured.samples else resample(captured.samples, captured.sampleRate, rate)
        val r = reference.samples
        val baseExact = (captured.startMs - reference.startMs) * rate / 1000.0
        val base = Math.round(baseExact).toInt()
        val frac = baseExact - base
        val maxK = (maxLagMs * rate / 1000.0).toInt()
        // n range valid for every lag in [-maxK, maxK]
        val n0 = max(0, -(base - maxK))
        val n1 = min(c.size, r.size - base - maxK)
        if (n1 - n0 < rate / 4) return null

        // coarse, 4x decimated
        val d = 4
        val cd = decimate(c, n0, n1, d)
        val rd = decimate(r, max(0, n0 + base - maxK), min(r.size, n1 + base + maxK), d)
        val rdOffset = max(0, n0 + base - maxK) // r index of rd[0]
        val maxKd = maxK / d
        var bestKd = 0
        var best = Double.NEGATIVE_INFINITY
        val ce = energy(cd, 0, cd.size)
        for (kd in -maxKd..maxKd) {
            // rd index of (n0 + base + kd*d) is (n0 + base + kd*d - rdOffset)/d
            val start = (n0 + base + kd * d - rdOffset) / d
            if (start < 0 || start + cd.size > rd.size) continue
            var num = 0.0
            for (i in cd.indices) num += cd[i] * rd[start + i]
            val v = num / sqrt(ce * energy(rd, start, start + cd.size) + 1e-12)
            if (v > best) {
                best = v
                bestKd = kd
            }
        }
        if (best == Double.NEGATIVE_INFINITY) return null

        // fine, full rate around the coarse peak
        val cEn = energy(c, n0, n1)
        fun corrAt(k: Int): Double {
            var num = 0.0
            var rEn = 0.0
            for (n in n0 until n1) {
                val y = r[n + base + k]
                num += c[n] * y
                rEn += y * y
            }
            return num / sqrt(cEn * rEn + 1e-12)
        }
        val center = bestKd * d
        var bestK = center
        var bestV = Double.NEGATIVE_INFINITY
        for (k in (center - 2 * d).coerceAtLeast(-maxK)..(center + 2 * d).coerceAtMost(maxK)) {
            val v = corrAt(k)
            if (v > bestV) {
                bestV = v
                bestK = k
            }
        }
        var sub = 0.0
        if (bestK > -maxK && bestK < maxK) {
            val ym = corrAt(bestK - 1)
            val yp = corrAt(bestK + 1)
            val den = ym - 2 * bestV + yp
            if (den < 0) sub = (0.5 * (ym - yp) / den).coerceIn(-0.5, 0.5)
        }
        val lag = bestK + sub - frac
        return Result(lag * 1000.0 / rate, bestV.coerceIn(0.0, 1.0))
    }

    /**
     * [skew] over +-[narrowMs]; when that is not a clear match, once more over +-[wideMs], accepted only at [wideMinConfidence].
     * The two decodes are of the same bytes, so a low peak means the lag is beyond the narrow search (a deck whose time
     * base came from a seek has landed 50+ ms off on the device), not that the audio differs. A wide search can lock onto
     * a repeat of the music a beat away, hence the stricter bar; a beat is 300+ ms, outside [wideMs].
     */
    fun skewRobust(
        captured: CapturedAudio,
        reference: ReferenceAudio,
        narrowMs: Double = 60.0,
        wideMs: Double = 200.0,
        narrowMinConfidence: Double = 0.8,
        wideMinConfidence: Double = 0.9,
    ): Result? {
        val narrow = skew(captured, reference, narrowMs)
        if (narrow != null && narrow.confidence >= narrowMinConfidence) return narrow
        val wide = skew(captured, reference, wideMs) ?: return narrow?.let { Result(it.deltaMs, it.confidence, trusted = false) }
        if (wide.confidence >= wideMinConfidence) return Result(wide.deltaMs, wide.confidence, wide = true)
        // neither is trusted: report the better one, marked untrusted (the caller logs it and assumes no skew)
        val best = if (narrow == null || wide.confidence > narrow.confidence) wide else narrow
        return Result(best.deltaMs, best.confidence, trusted = false, wide = best === wide)
    }

    private fun energy(a: FloatArray, from: Int, to: Int): Double {
        var e = 0.0
        for (i in from until to) e += a[i] * a[i]
        return e
    }

    private fun decimate(a: FloatArray, from: Int, to: Int, d: Int): FloatArray {
        val n = (to - from) / d
        val out = FloatArray(maxOf(n, 0))
        for (i in 0 until n) {
            var s = 0f
            for (j in 0 until d) s += a[from + i * d + j]
            out[i] = s / d
        }
        return out
    }

    /** Cubic resampling of [a] from [from] Hz to [to] Hz (same start time). */
    fun resample(a: FloatArray, from: Int, to: Int): FloatArray {
        val n = (a.size.toLong() * to / from).toInt()
        val out = FloatArray(n)
        val step = from.toDouble() / to
        fun at(i: Int) = a[i.coerceIn(0, a.size - 1)]
        for (k in 0 until n) {
            val p = k * step
            val i = p.toInt()
            val t = (p - i).toFloat()
            val y0 = at(i - 1)
            val y1 = at(i)
            val y2 = at(i + 1)
            val y3 = at(i + 2)
            val aa = -0.5f * y0 + 1.5f * y1 - 1.5f * y2 + 0.5f * y3
            val b = y0 - 2.5f * y1 + 2f * y2 - 0.5f * y3
            val c = -0.5f * y0 + 0.5f * y2
            out[k] = ((aa * t + b) * t + c) * t + y1
        }
        return out
    }
}
