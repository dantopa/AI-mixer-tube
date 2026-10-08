package org.simpmusic.dj.analysis

import org.simpmusic.dj.model.TimeRange
import org.simpmusic.dj.model.TrackAnalysis
import kotlin.math.sqrt

/**
 * A song's map as the owner hears it: intro, voice, instrumental, voice again... with the sung parts that come back
 * (the chorus) told apart from the ones that do not. Built from the vocal ranges (YAMNet, `VocalClash`) and the stored
 * structure frames (chroma + timbre every 500 ms).
 *
 * "Comes back" is measured as repetition: the frame-by-frame cosine similarity (z-scored features) between a sung window
 * and the best-matching window elsewhere in the track at least its own length away. A chorus is the sung material a
 * song repeats; a verse repeats its melody with other words, which scores lower but not zero, so this is a degree
 * ([repeatScore]), not a label. Not checked against human labels: no annotated cumbia exists here.
 */
object SongMap {
    /** A shout this short inside an instrumental does not end it (same rule as the hand-off). */
    const val ISLAND_MS = 2_000L

    /** Repetition from which a sung window is called chorus-like in the map. */
    const val CHORUS = 0.55

    private class Frames(val hopMs: Int, val dim: Int, val z: Array<DoubleArray>)

    private val cache = object : LinkedHashMap<TrackAnalysis, Frames?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TrackAnalysis, Frames?>?) = size > 32
    }

    private fun frames(a: TrackAnalysis): Frames? {
        synchronized(cache) { if (cache.containsKey(a)) return cache[a] }
        val f = build(a)
        synchronized(cache) { cache[a] = f }
        return f
    }

    private fun build(a: TrackAnalysis): Frames? {
        val dim = TrackAnalysis.STRUCTURE_DIM
        val raw = a.structureFrames
        if (a.structureHopMs <= 0 || raw.size < dim * 16) return null
        val n = raw.size / dim
        val mean = DoubleArray(dim)
        val sd = DoubleArray(dim)
        for (i in 0 until n) for (k in 0 until dim) mean[k] += raw[i * dim + k].toDouble() / n
        for (i in 0 until n) for (k in 0 until dim) { val d = raw[i * dim + k] - mean[k]; sd[k] += d * d / n }
        for (k in 0 until dim) sd[k] = sqrt(sd[k]).coerceAtLeast(1e-6)
        val z = Array(n) { i ->
            val v = DoubleArray(dim) { k -> (raw[i * dim + k] - mean[k]) / sd[k] }
            val norm = sqrt(v.sumOf { it * it }).coerceAtLeast(1e-9)
            for (k in 0 until dim) v[k] /= norm
            v
        }
        return Frames(a.structureHopMs, dim, z)
    }

    /**
     * How strongly the window [endMs - lenMs, endMs) of [a] comes back elsewhere in the track: the best mean
     * frame-by-frame cosine against any other window at least [lenMs] away (0..1). Null without structure frames.
     */
    fun repeatScore(a: TrackAnalysis, endMs: Long, lenMs: Long): Double? {
        val f = frames(a) ?: return null
        val w = (lenMs / f.hopMs).toInt()
        val e = (endMs / f.hopMs).toInt().coerceAtMost(f.z.size)
        val s0 = e - w
        if (w < 8 || s0 < 0) return null
        var best = 0.0
        for (s in 0..f.z.size - w) {
            if (kotlin.math.abs(s - s0) < w) continue
            var sum = 0.0
            for (t in 0 until w) {
                val x = f.z[s0 + t]
                val y = f.z[s + t]
                var c = 0.0
                for (k in 0 until f.dim) c += x[k] * y[k]
                sum += c
            }
            best = maxOf(best, sum / w)
        }
        return best
    }

    /** One line: "inst 0-13 | voice 13-142 (chorus-like .71) | inst 142-148 | ...", or null without vocals. */
    fun describe(a: TrackAnalysis): String? {
        val v = a.vocals?.value ?: return null
        val sung = v.filter { it.durationMs >= ISLAND_MS }
        val parts = ArrayList<String>()
        var prev = 0L
        fun inst(x: Long, y: Long) { if (y - x >= 1_000) parts += "inst %d-%d".format(x / 1000, y / 1000) }
        for (r in sung) {
            inst(prev, r.startMs)
            val rep = repeatScore(a, r.endMs, minOf(r.durationMs, 16_000L))
            parts += "voice %d-%d".format(r.startMs / 1000, r.endMs / 1000) + (rep?.let { if (it >= CHORUS) " (chorus-like %.2f)".format(it) else " (rep %.2f)".format(it) } ?: "")
            prev = maxOf(prev, r.endMs)
        }
        inst(prev, a.durationMs)
        return parts.joinToString(" | ")
    }

    /** Sung ranges with shouts removed. */
    fun sung(v: List<TimeRange>): List<TimeRange> = v.filter { it.durationMs >= ISLAND_MS }
}
