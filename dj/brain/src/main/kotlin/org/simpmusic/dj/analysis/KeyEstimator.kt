package org.simpmusic.dj.analysis

import org.simpmusic.dj.model.Mode
import org.simpmusic.dj.model.MusicalKey
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal class KeyResult(
    val key: MusicalKey,
    val confidence: Double,
    /** Correlation of the track's chroma with each of the 24 keys (major 0..11 then minor 0..11). */
    val correlations: DoubleArray,
)

/**
 * Global key from the tuning-corrected harmonic chroma. Two profile families (Krumhansl-Kessler and Temperley) are
 * correlated with a weighted chroma sum and their scores averaged, which is steadier across genres than either alone.
 * Frames at phrase starts count extra: the tonic chord is where loops and sections restart, which is the only
 * structural evidence that separates a key from its relative major/minor (they share every pitch class).
 */
internal object KeyEstimator {
    var debug: ((String) -> Unit)? = null
    private val KK_MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val KK_MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
    private val TP_MAJOR = doubleArrayOf(5.0, 2.0, 3.5, 2.0, 4.5, 4.0, 2.0, 4.5, 2.0, 3.5, 1.5, 4.0)
    private val TP_MINOR = doubleArrayOf(5.0, 2.0, 3.5, 4.5, 2.0, 4.0, 2.0, 4.5, 3.5, 2.0, 1.5, 4.0)

    /** How strongly "the harmony at phrase starts is the tonic chord" may tip close calls (scaled by phrase confidence). */
    var tonicWeight = 1.6

    fun estimate(sp: SpectralFeatures, phraseStartsSec: DoubleArray, barSec: Double, phraseConfidence: Double = 0.0): KeyResult? {
        val g = DoubleArray(12)
        var activeFrames = 0
        for (j in 0 until sp.nFrames) {
            if (!sp.active[j]) continue
            activeFrames++
            for (k in 0 until 12) g[k] += sp.chroma[j * 12 + k]
        }
        // chroma of the first bar of every phrase: where loops and sections restart, hence where the tonic chord sits
        val p = DoubleArray(12)
        var pFrames = 0
        if (barSec > 0) for (t in phraseStartsSec) {
            val j0 = max(0, Math.ceil(t * 1000.0 / Grid.SPEC_HOP_MS).toInt())
            val j1 = min(sp.nFrames - 1, ((t + 0.9 * barSec) * 1000.0 / Grid.SPEC_HOP_MS).toInt())
            for (j in j0..j1) if (sp.active[j]) { for (k in 0 until 12) p[k] += sp.chroma[j * 12 + k]; pFrames++ }
        }
        if (activeFrames < 10) return null
        // compress so a few loud chords do not decide everything
        val v = DoubleArray(12) { Math.sqrt(g[it]) }
        val corr = DoubleArray(24)
        for (pc in 0 until 12) {
            corr[pc] = 0.5 * (pearson(v, KK_MAJOR, pc) + pearson(v, TP_MAJOR, pc))
            corr[12 + pc] = 0.5 * (pearson(v, KK_MINOR, pc) + pearson(v, TP_MINOR, pc))
        }
        debug?.invoke("corr(before)=" + corr.joinToString(" ") { "%.2f".format(it) } + " g=" + g.joinToString(" ") { "%.1f".format(it) } + " p=" + p.joinToString(" ") { "%.1f".format(it) } + " pFrames=$pFrames phraseConf=$phraseConfidence")
        if (pFrames >= 8 && phraseConfidence > 0.0) {
            var ps = 0.0
            for (x in p) ps += x
            for (pc in 0 until 12) {
                val maj = (p[pc] + p[(pc + 4) % 12] + p[(pc + 7) % 12]) / ps
                val min = (p[pc] + p[(pc + 3) % 12] + p[(pc + 7) % 12]) / ps
                corr[pc] += tonicWeight * phraseConfidence * (maj - 0.25)
                corr[12 + pc] += tonicWeight * phraseConfidence * (min - 0.25)
            }
        }
        var best = 0
        for (i in 1 until 24) if (corr[i] > corr[best]) best = i
        var second = -1
        for (i in 0 until 24) if (i != best && (second < 0 || corr[i] > corr[second])) second = i
        val key = MusicalKey(best % 12, if (best < 12) Mode.MAJOR else Mode.MINOR)
        // honesty: sharp margin, a clearly tonal profile, and enough material
        val margin = corr[best] - corr[second]
        val marginTerm = (margin / 0.12).coerceIn(0.0, 1.0)
        val fitTerm = ((corr[best] - 0.5) / 0.35).coerceIn(0.0, 1.0)
        val tonal = tonalness(g)
        val lengthTerm = min(1.0, activeFrames / 300.0) // 30 s of music for full trust
        val conf = marginTerm * (0.4 + 0.6 * fitTerm) * tonal * (0.5 + 0.5 * lengthTerm)
        return KeyResult(key, conf.coerceIn(0.0, 1.0), corr)
    }

    /** 0 for a flat chroma (noise, unpitched), 1 when a few pitch classes dominate. */
    private fun tonalness(g: DoubleArray): Double {
        var s = 0.0
        for (x in g) s += x
        if (s <= 0) return 0.0
        var h = 0.0
        for (x in g) { val p = x / s; if (p > 1e-12) h -= p * ln(p) }
        val norm = h / ln(12.0) // 1 = uniform
        return ((0.995 - norm) / 0.12).coerceIn(0.0, 1.0)
    }

    private fun pearson(v: DoubleArray, profile: DoubleArray, rot: Int): Double {
        // profile rotated so that its tonic sits on pitch class [rot]
        var mv = 0.0; var mp = 0.0
        for (i in 0 until 12) { mv += v[i]; mp += profile[i] }
        mv /= 12; mp /= 12
        var num = 0.0; var dv = 0.0; var dp = 0.0
        for (i in 0 until 12) {
            val a = v[(i + rot) % 12] - mv
            val b = profile[i] - mp
            num += a * b; dv += a * a; dp += b * b
        }
        if (dv <= 1e-18 || dp <= 1e-18) return 0.0
        return num / sqrt(dv * dp)
    }
}
