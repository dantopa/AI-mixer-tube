package org.simpmusic.dj.analysis

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal class PhraseResult(
    /** Bars per phrase (2 or 4). */
    val barsPerPhrase: Int,
    /** Indices into the bar (downbeat) list where phrases start. */
    val phraseBars: IntArray,
    /** 0..1 confidence of the phrase phase. */
    val confidence: Double,
    /** Per-bar structural-change score (z-scored), reused for section snapping. */
    val barChange: DoubleArray,
)

/**
 * Phrase phase: among the [barsPerPhrase] possible alignments pick the one whose bars sit on the strongest structural
 * changes (chroma, bass, brightness and loudness of the 4 bars before vs after) and downbeat accents (crash, kick).
 */
internal object PhraseAnalyzer {
    var debug: ((String) -> Unit)? = null
    fun analyze(
        beats: DoubleArray,
        downbeatIdx: IntArray,
        feat: BeatFeatures,
        on: OnsetFeatures,
        sp: SpectralFeatures,
    ): PhraseResult? {
        val bars = downbeatIdx.size
        if (bars < 4) return null
        // one feature vector per bar: chroma(12) + three log-band levels
        val dim = 15
        val vec = Array(bars) { DoubleArray(dim) }
        val ok = BooleanArray(bars)
        val fps = Grid.FPS
        for (j in 0 until bars) {
            val t0 = beats[downbeatIdx[j]]
            val t1 = if (j + 1 < bars) beats[downbeatIdx[j + 1]] else t0 + (t0 - beats[downbeatIdx[max(0, j - 1)]]).coerceAtLeast(1.0)
            var cnt = 0
            val jf0 = max(0, Math.ceil(t0 * 1000.0 / Grid.SPEC_HOP_MS).toInt())
            val jf1 = min(sp.nFrames - 1, Math.floor(t1 * 1000.0 / Grid.SPEC_HOP_MS).toInt())
            for (jf in jf0..jf1) {
                if (!sp.active[jf]) continue
                for (k in 0 until 12) vec[j][k] += sp.chroma[jf * 12 + k]
                cnt++
            }
            if (cnt > 0) { for (k in 0 until 12) vec[j][k] /= cnt; ok[j] = true }
            val f0 = max(0, (t0 * fps).toInt()); val f1 = min(on.nFrames - 1, (t1 * fps).toInt())
            if (f1 >= f0) {
                var a = 0.0; var b = 0.0; var c = 0.0
                for (f in f0..f1) { a += on.levelLow[f]; b += on.levelHigh[f]; c += on.levelAll[f] }
                val m = (f1 - f0 + 1).toDouble()
                vec[j][12] = a / m; vec[j][13] = b / m; vec[j][14] = c / m
            }
        }
        // z-score each dimension across bars so no one feature dominates the distance
        for (d in 0 until dim) {
            var m = 0.0
            for (j in 0 until bars) m += vec[j][d]
            m /= bars
            var s = 0.0
            for (j in 0 until bars) s += (vec[j][d] - m) * (vec[j][d] - m)
            val sd = sqrt(s / bars).coerceAtLeast(1e-9)
            for (j in 0 until bars) vec[j][d] = (vec[j][d] - m) / sd
        }
        val span = 4
        val change = DoubleArray(bars)
        val defined = BooleanArray(bars)
        for (j in span until bars - span + 1) {
            val a0 = j - span; val a1 = j // [a0, a1)
            val b0 = j; val b1 = j + span
            defined[j] = true
            var dist = 0.0
            for (d in 0 until dim) {
                var ma = 0.0; var mb = 0.0
                for (q in a0 until a1) ma += vec[q][d]
                for (q in b0 until b1) mb += vec[q][d]
                ma /= (a1 - a0); mb /= (b1 - b0)
                dist += (ma - mb) * (ma - mb)
            }
            change[j] = sqrt(dist / dim)
        }
        val z = zscore(change, defined)
        // Phrase-level accent: cymbal/crash step, bass entry and kick on the bar line. Chroma change is deliberately
        // left out: it peaks wherever the chord progression moves most, which is not the phrase start.
        val zh = zscore(DoubleArray(bars) { feat.high[downbeatIdx[it]] }, null)
        val zl = zscore(DoubleArray(bars) { feat.lowStep[downbeatIdx[it]] }, null)
        val zk = zscore(DoubleArray(bars) { feat.low[downbeatIdx[it]] }, null)
        val za = DoubleArray(bars) { 1.0 * zh[it] + 0.7 * zl[it] + 0.4 * zk[it] }
        val per = if (bars >= 24) 4 else 2
        val sums = DoubleArray(per); val cnt = IntArray(per)
        for (j in 0 until bars) { sums[j % per] += (if (defined[j]) z[j] else 0.0) + za[j]; cnt[j % per]++ }
        val means = DoubleArray(per) { sums[it] / max(1, cnt[it]) }
        debug?.invoke("phrase means=${means.joinToString { "%.2f".format(it) }} change=" + z.joinToString(" ") { "%.1f".format(it) } + " acc=" + za.joinToString(" ") { "%.1f".format(it) })
        var best = 0
        for (p in 1 until per) if (means[p] > means[best]) best = p
        var second = if (best == 0) 1 else 0
        for (p in 0 until per) if (p != best && means[p] > means[second]) second = p
        val gap = means[best] - means[second]
        val conf = (gap / 0.6).coerceIn(0.0, 1.0)
        val phraseBars = (best until bars step per).toList().toIntArray()
        return PhraseResult(per, phraseBars, conf, z)
    }

    private fun zscore(x: DoubleArray, mask: BooleanArray?): DoubleArray {
        var m = 0.0; var n = 0
        for (i in x.indices) if (mask == null || mask[i]) { m += x[i]; n++ }
        if (n == 0) return DoubleArray(x.size)
        m /= n
        var s = 0.0
        for (i in x.indices) if (mask == null || mask[i]) s += (x[i] - m) * (x[i] - m)
        val sd = sqrt(s / n)
        return if (sd < 1e-9) DoubleArray(x.size) else DoubleArray(x.size) { (x[it] - m) / sd }
    }
}
