package org.simpmusic.dj.ml

import kotlin.math.abs
import kotlin.math.exp

/** Beat and downbeat times (seconds) plus how sure the network was at each picked frame (0..1 sigmoid). */
class BeatPicks(
    val beatsSec: DoubleArray,
    val downbeatsSec: DoubleArray,
    val beatProb: DoubleArray,
    val downbeatProb: DoubleArray,
    /**
     * Per beat: the network's downbeat logit (log-odds that this beat is a "1"), the max within +-2 frames of the beat.
     * The peak picker keeps only downbeats whose logit clears 0 and throws the rest away; summed over a track these say
     * which beat of the bar is the 1 far more reliably than the picks (cumbia/dembow downbeats hover around 0).
     */
    val beatDownbeatLogit: FloatArray = FloatArray(0),
)

/**
 * Beat This!'s "minimal" post-processor (`Postprocessor(type="minimal")`, no DBN):
 * local maxima within +/-3 frames (70 ms) with logit > 0 (p > 0.5), adjacent picks merged by averaging their
 * frame index, downbeats moved onto the nearest beat and de-duplicated.
 */
object BeatPostProcessor {
    const val FPS = 50
    private const val POOL_RADIUS = 3

    fun pick(logits: FrameLogits, fps: Int = FPS): BeatPicks {
        val beatFrames = peaks(logits.beat)
        val downFrames = peaks(logits.downbeat)
        val beatF = dedupe(beatFrames)
        val downF = dedupe(downFrames)
        val beats = DoubleArray(beatF.size) { beatF[it] / fps }
        var downs = DoubleArray(downF.size) { downF[it] / fps }
        if (beats.isNotEmpty()) {
            for (i in downs.indices) {
                var best = 0
                var bd = abs(beats[0] - downs[i])
                for (j in 1 until beats.size) {
                    val d = abs(beats[j] - downs[i])
                    if (d < bd) { bd = d; best = j }
                }
                downs[i] = beats[best]
            }
        }
        downs = downs.distinct().sorted().toDoubleArray()
        val bp = DoubleArray(beats.size) { sigmoid(logits.beat[nearestFrame(beatF[it], logits.frames)]) }
        val dp = DoubleArray(downs.size) { sigmoid(logits.downbeat[nearestFrame(downs[it] * fps, logits.frames)]) }
        val dl = FloatArray(beats.size) { i ->
            val f = nearestFrame(beatF[i], logits.frames)
            var m = Float.NEGATIVE_INFINITY
            for (k in maxOf(0, f - 2)..minOf(logits.frames - 1, f + 2)) m = maxOf(m, logits.downbeat[k])
            m
        }
        return BeatPicks(beats, downs, bp, dp, dl)
    }

    private fun nearestFrame(frame: Double, n: Int): Int = Math.round(frame).toInt().coerceIn(0, n - 1)

    private fun sigmoid(x: Float): Double = 1.0 / (1.0 + exp(-x.toDouble()))

    /** Frames where the logit is a max of its +/-3 window (torch `max_pool1d(7, 1, 3)`, -inf padded) and > 0. */
    internal fun peaks(x: FloatArray): IntArray {
        val out = ArrayList<Int>()
        for (i in x.indices) {
            val v = x[i]
            if (v <= 0f) continue
            var isMax = true
            for (k in maxOf(0, i - POOL_RADIUS)..minOf(x.size - 1, i + POOL_RADIUS)) {
                if (x[k] > v) { isMax = false; break }
            }
            if (isMax) out.add(i)
        }
        return out.toIntArray()
    }

    /** `deduplicate_peaks(width=1)`: runs of picks at most one frame apart collapse to the running mean. */
    internal fun dedupe(peaks: IntArray, width: Int = 1): DoubleArray {
        if (peaks.isEmpty()) return DoubleArray(0)
        val result = ArrayList<Double>()
        var p = peaks[0].toDouble()
        var c = 1
        for (i in 1 until peaks.size) {
            val p2 = peaks[i]
            if (p2 - p <= width) {
                c += 1
                p += (p2 - p) / c
            } else {
                result.add(p)
                p = p2.toDouble()
                c = 1
            }
        }
        result.add(p)
        return result.toDoubleArray()
    }
}
