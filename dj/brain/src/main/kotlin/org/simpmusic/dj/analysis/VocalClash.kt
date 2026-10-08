package org.simpmusic.dj.analysis

import org.simpmusic.dj.model.TimeRange
import org.simpmusic.dj.model.TrackAnalysis

/**
 * Voice over voice in an overlap, from `TrackAnalysis.vocals` (YAMNet, see `VocalDetector`). After the bass, two singers at
 * once is the most audible thing a mix can get wrong (Vande Veire & De Bie 2018 make it a hard rule), and what DJs do is
 * simple: leave before the incoming vocal starts, or enter on its instrumental intro.
 */
object VocalClash {
    /** Sampling step over the overlap (ms). */
    private const val STEP_MS = 100L

    /** This much voice over voice is a full penalty. */
    const val FULL_MS = 8_000L

    /** Under this much it is a breath or a backing "uh", not two singers. */
    const val CLASH_MS = 1_500L

    class Result(
        /** Wall time (ms) in the overlap where both decks have a voice. */
        val clashMs: Long,
        /** First wall instant (ms from the start of the overlap) where both have a voice, or -1. */
        val firstMs: Long,
        /** Wall time with the outgoing / incoming voice alone (for the reason). */
        val outMs: Long,
        val inMs: Long,
    ) {
        val penalty: Double get() = (clashMs.toDouble() / FULL_MS).coerceIn(0.0, 1.0)
        val clash: Boolean get() = clashMs >= CLASH_MS

        fun describe(): String = when {
            clash -> "vocals: voice over voice %.1f s".format(clashMs / 1000.0)
            outMs == 0L && inMs == 0L -> "vocals: none in the overlap"
            else -> "vocals: one at a time"
        }
    }

    /** Vocal ranges of [a], sorted; null when the analysis has no vocal detection (made before build ak). */
    fun ranges(a: TrackAnalysis): List<TimeRange>? = a.vocals?.value?.sortedBy { it.startMs }

    private fun inside(r: List<TimeRange>, t: Long): Boolean {
        var lo = 0
        var hi = r.size - 1
        while (lo <= hi) {
            val m = (lo + hi) ushr 1
            when {
                t < r[m].startMs -> hi = m - 1
                t >= r[m].endMs -> lo = m + 1
                else -> return true
            }
        }
        return false
    }

    /**
     * Over [overlapMs] of wall time the outgoing source runs from [exitMs] at [rateOut] and the incoming from [entryMs] at
     * [rateIn]. Null when either track has no vocal detection.
     */
    fun of(out: List<TimeRange>?, exitMs: Long, rateOut: Double, inc: List<TimeRange>?, entryMs: Long, rateIn: Double, overlapMs: Long): Result? {
        if (out == null || inc == null || overlapMs <= 0) return null
        var both = 0L
        var o = 0L
        var i = 0L
        var first = -1L
        var t = 0L
        while (t < overlapMs) {
            val vo = inside(out, exitMs + (t * rateOut).toLong())
            val vi = inside(inc, entryMs + (t * rateIn).toLong())
            if (vo && vi) {
                both += STEP_MS
                if (first < 0) first = t
            } else if (vo) {
                o += STEP_MS
            } else if (vi) {
                i += STEP_MS
            }
            t += STEP_MS
        }
        return Result(both, first, o, i)
    }
}
