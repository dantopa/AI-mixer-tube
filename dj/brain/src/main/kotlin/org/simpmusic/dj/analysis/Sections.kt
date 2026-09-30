package org.simpmusic.dj.analysis

import org.simpmusic.dj.model.Section
import org.simpmusic.dj.model.SectionKind
import org.simpmusic.dj.model.TimeRange
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal class SectionResult(val sections: List<Section>, val confidence: Double, val boundariesSec: DoubleArray)

/**
 * Novelty-based structure segmentation (Foote 2000): a self-similarity matrix over beat-synchronous chroma + timbre +
 * loudness, a Gaussian-tapered checkerboard kernel slid along its diagonal, peak picking, then boundaries snapped to
 * phrase starts / downbeats when a grid exists. Labels come from position and relative energy.
 */
internal object SectionAnalyzer {
    fun analyze(
        durationSec: Double,
        activeStartSec: Double,
        activeEndSec: Double,
        energy: FloatArray, // 100 ms grid, 0..1
        low: FloatArray,
        sp: SpectralFeatures,
        beats: DoubleArray?, // sec, may be null
        downbeatTimesSec: DoubleArray?,
        phraseTimesSec: DoubleArray?,
    ): SectionResult? {
        if (activeEndSec - activeStartSec < 20.0) return null
        // ---- 1. analysis units
        val unitStart: DoubleArray
        val unitEnd: DoubleArray
        if (beats != null && beats.size >= 16) {
            val idx = beats.indices.filter { beats[it] >= activeStartSec - 0.5 && beats[it] <= activeEndSec }
            val s = ArrayList<Double>(); val e = ArrayList<Double>()
            for (q in idx) {
                val a = beats[q]
                val b = if (q + 1 < beats.size) beats[q + 1] else a + (a - beats[max(0, q - 1)])
                s.add(a); e.add(min(b, activeEndSec))
            }
            unitStart = s.toDoubleArray(); unitEnd = e.toDoubleArray()
        } else {
            val n = ((activeEndSec - activeStartSec) / 1.0).toInt()
            unitStart = DoubleArray(n) { activeStartSec + it }
            unitEnd = DoubleArray(n) { activeStartSec + it + 1.0 }
        }
        val n = unitStart.size
        if (n < 24) return null
        val meanUnit = (unitEnd[n - 1] - unitStart[0]) / n

        // ---- 2. feature vectors
        val dimC = 12; val dimM = SpectralFeatures.MFCC_N; val dimE = 2
        val dim = dimC + dimM + dimE
        val v = Array(n) { FloatArray(dim) }
        for (i in 0 until n) {
            val j0 = max(0, Math.ceil(unitStart[i] * 10.0).toInt())
            val j1 = min(sp.nFrames - 1, max(j0, Math.floor(unitEnd[i] * 10.0).toInt() - 1))
            var cnt = 0
            var eSum = 0.0; var lSum = 0.0
            for (j in j0..j1) {
                if (j < energy.size) { eSum += energy[j]; lSum += low[j] }
                if (!sp.active[j]) continue
                for (k in 0 until dimC) v[i][k] += sp.chroma[j * 12 + k]
                for (k in 0 until dimM) v[i][dimC + k] += sp.mfcc[j * dimM + k]
                cnt++
            }
            if (cnt > 0) for (k in 0 until dimC + dimM) v[i][k] /= cnt
            val m = (j1 - j0 + 1).toDouble()
            v[i][dimC + dimM] = ln(1e-3 + eSum / m).toFloat()
            v[i][dimC + dimM + 1] = ln(1e-3 + lSum / m).toFloat()
        }
        // z-score per dimension; block weights so chroma, timbre and loudness count comparably
        val blockW = doubleArrayOf(1.0 / dimC, 1.0 / dimM, 1.5 / dimE)
        for (d in 0 until dim) {
            var m = 0.0
            for (i in 0 until n) m += v[i][d]
            m /= n
            var s = 0.0
            for (i in 0 until n) s += (v[i][d] - m) * (v[i][d] - m)
            val sd = sqrt(s / n).coerceAtLeast(1e-6)
            val w = sqrt(if (d < dimC) blockW[0] else if (d < dimC + dimM) blockW[1] else blockW[2])
            for (i in 0 until n) v[i][d] = ((v[i][d] - m) / sd * w).toFloat()
        }

        // ---- 3. banded cosine self-similarity + checkerboard novelty
        val half = (7.0 / meanUnit).toInt().coerceIn(8, 32)
        val band = 2 * half
        val norms = DoubleArray(n) { i -> var s = 0.0; for (d in 0 until dim) s += v[i][d] * v[i][d]; sqrt(s).coerceAtLeast(1e-9) }
        val sim = FloatArray(n * (2 * band + 1))
        fun s(x: Int, y: Int): Double {
            if (x < 0 || y < 0 || x >= n || y >= n) return 0.0
            val d = y - x
            if (d < -band || d > band) return 0.0
            return sim[x * (2 * band + 1) + d + band].toDouble()
        }
        for (x in 0 until n) for (d in -band..band) {
            val y = x + d
            if (y < 0 || y >= n || y < x) continue
            var dot = 0.0
            for (k in 0 until dim) dot += v[x][k] * v[y][k]
            val c = (dot / (norms[x] * norms[y])).toFloat()
            sim[x * (2 * band + 1) + d + band] = c
            sim[y * (2 * band + 1) - d + band] = c
        }
        val novelty = DoubleArray(n)
        val g = Array(half) { a -> DoubleArray(half) { b -> exp(-2.0 * ((a + 0.5) / half).let { it * it } - 2.0 * ((b + 0.5) / half).let { it * it }) } }
        for (i in half until n - half) {
            var acc = 0.0
            for (a in 0 until half) for (b in 0 until half) {
                val w = g[a][b]
                acc += w * (s(i - 1 - a, i - 1 - b) + s(i + a, i + b) - s(i - 1 - a, i + b) - s(i + a, i - 1 - b))
            }
            novelty[i] = max(0.0, acc)
        }
        // ---- 4. peak picking
        var mean = 0.0; var mx = 0.0
        for (x in novelty) { mean += x; if (x > mx) mx = x }
        mean /= n
        var sd = 0.0
        for (x in novelty) sd += (x - mean) * (x - mean)
        sd = sqrt(sd / n)
        val threshold = max(mean + 0.8 * sd, 0.2 * mx)
        val cand = ArrayList<Int>()
        for (i in half until n - half) {
            val x = novelty[i]
            if (x < threshold) continue
            var isMax = true
            for (d in -half / 2..half / 2) { val j = i + d; if (j in 0 until n && novelty[j] > x) { isMax = false; break } }
            if (isMax) cand.add(i)
        }
        cand.sortByDescending { novelty[it] }
        val picked = ArrayList<Int>()
        for (c in cand) if (picked.all { abs(it - c) >= half }) picked.add(c)
        picked.sort()

        // ---- 5. snap to the grid
        val bounds = ArrayList<Double>()
        for (i in picked) {
            var t = unitStart[i]
            val bar = if (downbeatTimesSec != null && downbeatTimesSec.size > 2) (downbeatTimesSec[downbeatTimesSec.size - 1] - downbeatTimesSec[0]) / (downbeatTimesSec.size - 1) else 0.0
            if (bar > 0) {
                val ph = phraseTimesSec?.minByOrNull { abs(it - t) }
                val db = downbeatTimesSec!!.minByOrNull { abs(it - t) }
                if (ph != null && abs(ph - t) <= 1.5 * bar) t = ph
                else if (db != null && abs(db - t) <= 1.0 * bar) t = db
            }
            if (t - activeStartSec > 4.0 && activeEndSec - t > 4.0 && (bounds.isEmpty() || t - bounds.last() > 4.0)) bounds.add(t)
        }
        bounds.sort()

        // ---- 6. sections + labels
        val edges = ArrayList<Double>()
        edges.add(activeStartSec); edges.addAll(bounds); edges.add(activeEndSec)
        val secEnergy = FloatArray(edges.size - 1)
        for (k in 0 until edges.size - 1) {
            val j0 = max(0, (edges[k] * 10).toInt()); val j1 = min(energy.size - 1, (edges[k + 1] * 10).toInt())
            var s2 = 0.0
            for (j in j0..max(j0, j1)) s2 += energy[min(j, energy.size - 1)]
            secEnergy[k] = (s2 / (max(j0, j1) - j0 + 1)).toFloat()
        }
        val kinds = label(secEnergy, edges)
        val list = (0 until edges.size - 1).map {
            Section(TimeRange((edges[it] * 1000).toLong(), (edges[it + 1] * 1000).toLong()), kinds[it], secEnergy[it])
        }
        // confidence: how far the picked peaks stand above the novelty floor
        val conf = if (picked.isEmpty()) 0.25 else {
            var m = 0.0
            for (i in picked) m += novelty[i]
            m /= picked.size
            (((m / (mean + 1e-9)) - 2.5) / 6.0).coerceIn(0.0, 1.0) * 0.9 + 0.05
        }
        return SectionResult(list, conf, bounds.toDoubleArray())
    }

    private fun label(e: FloatArray, edges: List<Double>): List<SectionKind> {
        val n = e.size
        if (n == 1) return listOf(SectionKind.UNKNOWN)
        val mx = e.max()
        val total = edges.last() - edges.first()
        return List(n) { i ->
            val rel = if (mx > 0) e[i] / mx else 0f
            val dur = edges[i + 1] - edges[i]
            when {
                i == 0 && (rel < 0.85f || dur < 0.2 * total) && rel < 0.95f -> SectionKind.INTRO
                i == n - 1 && rel < 0.85f -> SectionKind.OUTRO
                rel < 0.55f -> SectionKind.BREAKDOWN
                rel >= 0.92f && (i == 0 || e[i - 1] < 0.92f * e[i]) -> SectionKind.DROP
                else -> SectionKind.BODY
            }
        }
    }
}
