package org.simpmusic.dj.planner

import org.simpmusic.dj.model.TimeRange
import kotlin.math.abs
import kotlin.math.min

/**
 * The mix that follows the songs' own structure (the owner, 2026-10-08): a chorus ends, the band plays an instrumental
 * bridge, and the song would start over with its first verse. Instead of letting it start over, the instrumental bridge of
 * the outgoing track is laid over the instrumental intro of the incoming one, and the incoming's FIRST VOICE lands exactly
 * where the outgoing's voice would have come back. Nothing sung is cut, no two voices overlap, and the next song starts
 * from its own phrase 1.
 *
 * Built from the vocal ranges (`VocalClash`, YAMNet) and the bar lines of both tracks. The overlap is a whole number of
 * bars (16, 8, 4 or 2: phrase-sized), ending on the outgoing's "voice returns" bar and the incoming's "first voice" bar.
 */
internal object StructureHandoff {
    /** A vocal island shorter than this (a shout, an "¡ahí va!") inside an instrumental does not end it. */
    const val ISLAND_MS = 2_000L

    /** At least this much voice in the [SUNG_WINDOW_MS] before a break: a sung section has just ended there. */
    const val SUNG_MS = 6_000L
    const val SUNG_WINDOW_MS = 16_000L

    val BAR_CHOICES = intArrayOf(16, 8, 4, 2)

    /** An incoming landed on a later break must still have most of the song ahead. */
    const val MAX_LANDING_FRACTION = 0.6

    /** Start of a hand-off plan's reason. */
    const val TAG = "STRUCTURE: "

    class Handoff(
        val exitMs: Long,
        val entryMs: Long,
        val bars: Int,
        /** Outgoing source time where its voice would come back (or its end, for an outro). */
        val resumeMs: Long,
        /** Incoming source time of its first voice. */
        val firstVoiceMs: Long,
        val outro: Boolean,
        val score: Double,
        /** The incoming sings from its first bar (no intro): it starts singing over the outgoing's instrumental. */
        val singsAtOnce: Boolean = false,
        /** The incoming lands on the voice after one of its own instrumental breaks, not after its intro. */
        val viaBreak: Boolean = false,
    ) {
        fun describe(): String =
            if (singsAtOnce) {
                TAG + "the outgoing's instrumental ${if (outro) "outro" else "after the vocals"} (voice would return at %.1f s) under the incoming's first bars, which sing from the start; %d bars; ".format(resumeMs / 1000.0, bars)
            } else {
                TAG + "the outgoing's instrumental ${if (outro) "outro" else "after the vocals"} (voice would return at %.1f s) over the incoming's ${if (viaBreak) "own instrumental break" else "intro"}, whose ${if (viaBreak) "phrase 1 returns" else "first voice lands"} there (%.1f s); %d bars; ".format(
                    resumeMs / 1000.0, firstVoiceMs / 1000.0, bars,
                )
            }
    }

    /** Vocal ranges with islands shorter than [ISLAND_MS] removed (they count as instrumental). */
    internal fun sung(v: List<TimeRange>): List<TimeRange> = v.filter { it.durationMs >= ISLAND_MS }

    /** Instrumental spans [a, b) of a track: what lies between the sung ranges, the head and the tail included. */
    internal fun instrumentals(v: List<TimeRange>, durationMs: Long): List<LongArray> {
        val s = sung(v)
        val out = ArrayList<LongArray>()
        var prev = 0L
        for (r in s) {
            if (r.startMs > prev) out += longArrayOf(prev, r.startMs)
            prev = maxOf(prev, r.endMs)
        }
        if (durationMs > prev) out += longArrayOf(prev, durationMs)
        return out
    }

    private fun voiceIn(v: List<TimeRange>, a: Long, b: Long): Long = v.sumOf { (min(it.endMs, b) - maxOf(it.startMs, a)).coerceAtLeast(0L) }

    /** Index of the downbeat nearest [t] within half a bar, or -1. */
    private fun bar(d: LongArray, t: Long, barMs: Double): Int {
        if (d.isEmpty()) return -1
        var lo = 0
        var hi = d.size - 1
        while (lo < hi) {
            val m = (lo + hi) ushr 1
            if (d[m] < t) lo = m + 1 else hi = m
        }
        val k = listOf(lo - 1, lo).filter { it in d.indices }.minByOrNull { abs(d[it] - t) } ?: return -1
        return if (abs(d[k] - t) <= barMs / 2) k else -1
    }

    /**
     * Every structure hand-off between the two tracks whose exit lies in [lower, upper], best first. Empty when either
     * track has no vocal detection or untrusted bars, or when the incoming starts singing within two bars.
     */
    /** Where the incoming can be landed: an instrumental span of it ending on its voice (bar [iV]), [bars] long. */
    private class Landing(val iV: Int, val bars: Int, val kind: Kind, val entryIdx: Int = -1) {
        enum class Kind { INTRO, BREAK, SINGS_AT_ONCE }
    }

    /**
     * Every structure hand-off between the two tracks whose exit lies in [lower, upper], best first. Empty when either
     * track has no vocal detection or untrusted bars.
     */
    fun find(out: TrackContext, inc: TrackContext, vOut: List<TimeRange>?, vIn: List<TimeRange>?, lower: Long, upper: Long, wantBars: Int): List<Handoff> {
        if (vOut == null || vIn == null || !out.barsTrusted || !inc.barsTrusted) return emptyList()
        val dOut = out.downbeatTimes
        val dIn = inc.downbeatTimes
        val barOut = out.beatsPerBar * out.medianBeatMs
        val barIn = inc.beatsPerBar * inc.medianBeatMs
        if (barOut <= 0 || barIn <= 0 || dIn.isEmpty()) return emptyList()

        // ---- where the incoming can land: on its first voice after its intro, or (the owner: "it starts singing, but
        // somewhere it has an instrumental alone and then starts phrase 1 again") on the voice after one of its own
        // instrumental breaks, early enough that most of the song is still ahead. A short shout does not end a span.
        val landings = ArrayList<Landing>()
        val sungIn = sung(vIn)
        val first = sungIn.firstOrNull() ?: return emptyList()
        // a voice from before the first marked bar has no bar to snap to: that track simply sings from the start
        val iFirst = bar(dIn, first.startMs, barIn).let { if (it < 0 && first.startMs < dIn[0] + barIn) 0 else it }
        val introBars = if (iFirst < 0) 0 else (0 until iFirst).count { dIn[it] >= inc.firstAudibleMs - inc.medianBeatMs }
        if (introBars >= BAR_CHOICES.last()) landings += Landing(iFirst, introBars, Landing.Kind.INTRO)
        for (span in instrumentals(vIn, inc.audibleEndMs)) {
            val a = span[0]
            val b = span[1]
            if (a <= 0L || b >= inc.audibleEndMs || b > MAX_LANDING_FRACTION * inc.durationMs) continue
            val iV = bar(dIn, b, barIn)
            if (iV <= 0) continue
            val n = (0 until iV).count { dIn[it] >= a - inc.medianBeatMs }
            if (n >= BAR_CHOICES.last()) landings += Landing(iV, n, Landing.Kind.BREAK)
        }
        // No instrumental to land in at all: the incoming enters on its first bar and sings over the outgoing's
        // instrumental, which still keeps one voice at a time.
        if (landings.isEmpty()) {
            val firstBar = dIn.indexOfFirst { it >= inc.firstAudibleMs - inc.medianBeatMs }
            if (firstBar >= 0 && iFirst >= 0) landings += Landing(iFirst, Int.MAX_VALUE, Landing.Kind.SINGS_AT_ONCE, firstBar)
        }

        val sungOut = sung(vOut)
        val result = ArrayList<Handoff>()
        for (span in instrumentals(vOut, out.audibleEndMs)) {
            val a = span[0]
            val b = span[1]
            val outro = b >= out.audibleEndMs
            if (a <= 0L) continue // the outgoing's own intro is not a way out
            if (voiceIn(sungOut, a - SUNG_WINDOW_MS, a) < SUNG_MS) continue // no sung section has just ended here
            // where the voice would come back (or, for an outro, the last bar before the end)
            val iR = if (outro) dOut.indexOfLast { it <= out.audibleEndMs } else bar(dOut, b, barOut)
            if (iR <= 0) continue
            val breakBars = (0 until iR).count { dOut[it] >= a - out.medianBeatMs }
            for (land in landings) {
                val atOnce = land.kind == Landing.Kind.SINGS_AT_ONCE
                val limit = min(min(breakBars, land.bars), wantBars.coerceAtLeast(BAR_CHOICES.last()))
                val bars = BAR_CHOICES.firstOrNull { it <= limit } ?: continue
                if (iR - bars < 0 || (!atOnce && land.iV - bars < 0)) continue
                val exit = dOut[iR - bars]
                val entry = if (atOnce) dIn[land.entryIdx] else dIn[land.iV - bars]
                if (exit < lower || exit > upper) continue
                var score = bars.toDouble() / maxOf(wantBars, bars)
                if (dOut[iR] in out.phraseTimes) score += 0.25
                if (!atOnce && dIn[land.iV] in inc.phraseTimes) score += 0.25
                if (outro) score -= 0.1 // the owner's case is the bridge after a chorus; an outro is the classic fallback
                score += when (land.kind) {
                    Landing.Kind.INTRO -> 0.3 // the whole incoming song is heard
                    Landing.Kind.BREAK -> -0.4 * dIn[land.iV] / maxOf(1L, inc.durationMs) // the later, the more of it is skipped
                    Landing.Kind.SINGS_AT_ONCE -> -0.3
                }
                result += Handoff(exit, entry, bars, dOut[iR], dIn[land.iV], outro, score, atOnce, land.kind == Landing.Kind.BREAK)
            }
        }
        return result.sortedWith(compareByDescending<Handoff> { it.score }.thenBy { it.exitMs })
    }

    private operator fun LongArray.contains(t: Long): Boolean = any { abs(it - t) <= 2 }
}
