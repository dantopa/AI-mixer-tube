package org.simpmusic.dj.analysis

import org.simpmusic.dj.model.TrackAnalysis
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How well two stretches of music sound TOGETHER, from the chroma the DSP analyzer stores every 500 ms
 * (`TrackAnalysis.structureFrames`, first 12 values of each frame, L1-normalised).
 *
 * A global key cannot say this: on the owner's export only 8 % of tracks reach a trusted key, songs modulate, and the
 * question is the 16 s that overlap, not the song (see `dj/docs/harmonic-mixing.md`). This uses Tonal Interval Vectors
 * (Bernardes et al. 2016; TIV.lib, DAFx-20): `T(k) = w(k) · DFT_k(c / Σc)`, k = 1..6, w = {3, 8, 11.5, 15, 14.5, 7.5}.
 * Its dissonance is `1 − |T| / |w|`, and the chroma of a mix is the average of the two chromas.
 *
 * The score is the EXCESS dissonance of the mix over the two windows alone: `D(mix) − (D(a) + D(b)) / 2`. A track mixed
 * with itself gives ~0; a flat (percussive, noisy) window gives exactly 0 against anything, which is why drum intros
 * and outros are harmonically free, as DJs know. Measured on the owner's 2026-10-07 export (trusted keys only), the
 * mean excess grows with the distance on the circle of fifths: 0.009 (same key) 0.016 (1 fifth) 0.043 (2) 0.055 (3)
 * 0.085 (4) 0.123 (5) 0.148 (tritone); a track against its own start 0.003. Hence [COMPATIBLE] and [CLASH].
 */
object Harmony {
    private val W = doubleArrayOf(3.0, 8.0, 11.5, 15.0, 14.5, 7.5)
    private val W_NORM = sqrt(W.sumOf { it * it })
    private val COS = Array(6) { k -> DoubleArray(12) { n -> cos(2 * PI * (k + 1) * n / 12) } }
    private val SIN = Array(6) { k -> DoubleArray(12) { n -> sin(2 * PI * (k + 1) * n / 12) } }

    /** Up to here the mix is as consonant as two tracks one fifth apart (Camelot ±1): no penalty. */
    const val COMPATIBLE = 0.025

    /** From here on (about three fifths apart and beyond) two tonal layers clash: full penalty and the clash treatment. */
    const val CLASH = 0.07

    /** Written into a plan's reason when its overlap clashes; the look-ahead reads it to look for a better next track. */
    const val CLASH_TAG = "harmony CLASH"

    /** Windows with less evidence than this (frames with energy) say nothing. */
    private const val MIN_FRAMES = 6

    /** Prefix sums over the frames of one analysis: energy-weighted chroma and the weights, so any window is O(12). */
    class Profile internal constructor(
        val hopMs: Int,
        private val chromaSum: Array<DoubleArray>,
        private val weightSum: DoubleArray,
        private val activeSum: IntArray,
    ) {
        val frames: Int get() = weightSum.size - 1

        /** Energy-weighted mean chroma of [startMs, startMs + durMs), or null without enough frames there. */
        fun window(startMs: Long, durMs: Long): DoubleArray? {
            if (durMs <= 0) return null
            val i0 = (startMs / hopMs).toInt().coerceIn(0, frames)
            val i1 = ((startMs + durMs) / hopMs).toInt().coerceIn(i0, frames)
            if (activeSum[i1] - activeSum[i0] < MIN_FRAMES) return null
            val w = weightSum[i1] - weightSum[i0]
            if (w <= 1e-9) return null
            return DoubleArray(12) { (chromaSum[i1][it] - chromaSum[i0][it]) / w }
        }
    }

    private val cache = object : LinkedHashMap<TrackAnalysis, Profile?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TrackAnalysis, Profile?>?) = size > 64
    }

    /** The [Profile] of [a], cached; null when the analysis carries no structure frames (made before build ae). */
    fun profile(a: TrackAnalysis): Profile? {
        synchronized(cache) { if (cache.containsKey(a)) return cache[a] }
        val p = build(a)
        synchronized(cache) { cache[a] = p }
        return p
    }

    private fun build(a: TrackAnalysis): Profile? {
        val dim = TrackAnalysis.STRUCTURE_DIM
        val hop = a.structureHopMs
        val fr = a.structureFrames
        if (hop <= 0 || fr.size < dim * MIN_FRAMES) return null
        val n = fr.size / dim
        val eHop = a.energyHopMs.coerceAtLeast(1)
        val chromaSum = Array(n + 1) { DoubleArray(12) }
        val weightSum = DoubleArray(n + 1)
        val activeSum = IntArray(n + 1)
        for (i in 0 until n) {
            // weight a frame by the loudness around it, so silence and fades do not vote
            val e0 = (i.toLong() * hop / eHop).toInt()
            val e1 = min(a.energy.size, max(e0 + 1, ((i + 1).toLong() * hop / eHop).toInt()))
            var e = 0.0
            if (e0 < a.energy.size) {
                for (j in e0 until e1) e += a.energy[j]
                e /= (e1 - e0)
            }
            var s = 0.0
            for (k in 0 until 12) s += fr[i * dim + k]
            val w = if (s > 1e-6) e else 0.0
            for (k in 0 until 12) chromaSum[i + 1][k] = chromaSum[i][k] + if (s > 1e-6) w * fr[i * dim + k] / s else 0.0
            weightSum[i + 1] = weightSum[i] + w
            activeSum[i + 1] = activeSum[i] + if (w > 1e-4) 1 else 0
        }
        return Profile(hop, chromaSum, weightSum, activeSum)
    }

    /** TIV dissonance (0 = one pure interval class, 1 = flat chroma) of an L1-normalised chroma. */
    fun dissonance(c: DoubleArray): Double {
        val s = c.sum().takeIf { it > 1e-12 } ?: return 1.0
        var norm2 = 0.0
        for (k in 0 until 6) {
            var re = 0.0
            var im = 0.0
            for (n in 0 until 12) {
                val v = c[n] / s
                re += v * COS[k][n]
                im -= v * SIN[k][n]
            }
            norm2 += W[k] * W[k] * (re * re + im * im)
        }
        return 1.0 - sqrt(norm2) / W_NORM
    }

    /** Excess dissonance of mixing [a] with [b] transposed by [shift] semitones (positive = up). */
    fun excess(a: DoubleArray, b: DoubleArray, shift: Int = 0): Double {
        val bs = DoubleArray(12) { b[Math.floorMod(it - shift, 12)] }
        val m = DoubleArray(12) { 0.5 * a[it] + 0.5 * bs[it] }
        return dissonance(m) - 0.5 * (dissonance(a) + dissonance(bs))
    }

    /** The harmonic fit of one overlap. [penalty] 0 (compatible) .. 1 (clash); [bestShift] the transposition of the incoming that would fit best. */
    class Fit(val excess: Double, val penalty: Double, val clash: Boolean, val bestShift: Int, val bestExcess: Double) {
        fun describe(): String =
            (if (clash) CLASH_TAG else "harmony " + if (penalty > 0) "tense" else "ok") + " (excess %.3f".format(excess) +
                (if (penalty > 0 && bestShift != 0 && bestExcess < excess - 0.01) ", best with the incoming %+d st: %.3f".format(bestShift, bestExcess) else "") + ")"
    }

    /** Fit of the outgoing window [exitMs, +outMs) against the incoming [entryMs, +inMs); null without evidence on either side. */
    fun fit(out: Profile?, exitMs: Long, outMs: Long, inc: Profile?, entryMs: Long, inMs: Long): Fit? {
        val a = out?.window(exitMs, outMs) ?: return null
        val b = inc?.window(entryMs, inMs) ?: return null
        val e0 = excess(a, b)
        var best = 0
        var bestE = e0
        for (s in -6..5) {
            if (s == 0) continue
            val e = excess(a, b, s)
            if (e < bestE - 1e-9) { bestE = e; best = s }
        }
        val penalty = ((e0 - COMPATIBLE) / (CLASH - COMPATIBLE)).coerceIn(0.0, 1.0)
        return Fit(e0, penalty, e0 >= CLASH, best, bestE)
    }
}
