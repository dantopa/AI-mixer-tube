package org.simpmusic.dj.planner

import org.simpmusic.dj.model.SectionKind
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The knobs of "mix anywhere" scoring, all in one place. A pair (exit on the outgoing track, entry on the incoming
 * one) scores the weighted sum of terms that each live in 0..1; the weights add up to 1.
 */
internal object MixScoring {
    /** The exit sits at a natural section boundary of the outgoing track (end of a BODY/DROP, start of a BREAKDOWN/OUTRO). */
    const val W_EXIT_BOUNDARY = 0.15
    /** The low band (kick / bass) falls away right after the exit: the outgoing track has stopped carrying the base, so the next one can take over without a clash. */
    const val W_BASS_DROP = 0.14
    /** Window (ms) after the exit over which the low band is compared with the REGION_MS before it. */
    const val BASS_AFTER_MS = 4_000L
    /** A low band below this (of the track's own loudest hop) before the exit means there was no base to lose. */
    const val BASS_MIN_BEFORE = 0.12f
    /** How much of the outgoing track has played (never butcher a song; mildly prefer later). */
    const val W_PLAYED = 0.02
    /** Exits in the last part of the track (the outro zone) lose up to this much: the owner asked for mixes anywhere, not always at the end. */
    const val OUTRO_ZONE_PENALTY = 0.10
    const val OUTRO_ZONE_FROM = 0.88
    /** Enough audio left after the exit for the overlap (not absurdly late). */
    const val W_ROOM_OUT = 0.02
    /** Energy continuity between the outgoing exit region and the incoming entry region (broadband), and entry >= exit. */
    const val W_ENERGY = 0.17
    /** Same for the low band (kick / bass presence). */
    const val W_LOW = 0.06
    /** The incoming entry is a phrase start where something happens (intro start, first body, drop), not a breakdown. */
    const val W_ENTRY_KIND = 0.10
    /** The incoming track's low band (kick / bass) STARTS at the entry: its base comes in exactly where the outgoing track's base has gone, instead of two basses sounding together. */
    const val W_BASS_ENTRY = 0.14
    /** Window (ms) around the entry over which the low band before and after it is compared. */
    const val BASS_ENTRY_MS = 3_000L
    /** A low band below this after the entry means the incoming track carries no base there to line up. */
    const val BASS_ENTRY_MIN = 0.12f

    /** Grid level of exit and entry: phrase start > downbeat > beat. */
    const val W_LEVEL = 0.06
    /** Among near-equal candidates leave the incoming track most of its length. */
    const val W_ROOM_IN = 0.08
    /** Smaller tempo bends are preferred (beat-matched pairs only). */
    const val W_TEMPO = 0.06
    /**
     * Penalty (not part of the sum of 1) for an overlap whose two tonal layers clash (`Harmony.Fit.penalty`, 0..1): as
     * heavy as the bass terms, so a consonant or percussive pair a little lower on the other terms wins.
     */
    const val W_HARMONY = 0.35
    /** Penalty for voice over voice in the overlap (`VocalClash.Result.penalty`, 0..1, full at 8 s), not part of the sum of 1. */
    const val W_VOCALS = 0.35

    /** Window (ms) over which the energy before the exit and after the entry is averaged. */
    const val REGION_MS = 8_000L
    /** Exits are at least this far into the track (ms), or `minPlayedFraction` of it, whichever is smaller. */
    const val MIN_PLAYED_CAP_MS = 75_000L
    /** Entries are only considered in the first part of the incoming track. */
    const val ENTRY_MAX_FRACTION = 0.60
    const val MAX_EXIT_CANDIDATES = 40
    const val MAX_ENTRY_CANDIDATES = 24
    const val MAX_BEAT_TRIES = 14
    const val MAX_ECHO_TRIES = 8
}

internal class ExitCand(
    val pick: DjTransitionPlanner.Pick,
    val levelScore: Double,
    val boundary: Double,
    val playedFraction: Double,
    val roomScore: Double,
    val energyBefore: Float?,
    val lowBefore: Float?,
    val inBreakdown: Boolean,
    /** 0..1: how completely the low band falls away after this exit (0 = keeps going, 1 = gone). */
    val bassDrop: Double = 0.0,
) {
    val timeMs: Long get() = pick.timeMs
}

internal class EntryCand(
    val pick: DjTransitionPlanner.Pick,
    val levelScore: Double,
    val kindScore: Double,
    val roomFraction: Double,
    val energyAfter: Float?,
    val lowAfter: Float?,
    val isBreakdownStart: Boolean,
    /** 0..1: how completely the incoming low band STARTS here (0 = already there or absent, 1 = silent before, present after). */
    val bassEntry: Double = 0.0,
) {
    val timeMs: Long get() = pick.timeMs
}

/** Candidate generation and pair scoring for [org.simpmusic.dj.model.MixPoint.ANYWHERE]. */
internal object MixCandidates {

    private fun levelScore(name: String) = when (name) { "phrase" -> 1.0; "downbeat" -> 0.7; else -> 0.4 }

    private fun gridLevels(c: TrackContext): List<Pair<String, LongArray>> {
        val out = ArrayList<Pair<String, LongArray>>(3)
        if (c.barsTrusted) {
            if (c.phraseTimes.isNotEmpty()) out += "phrase" to c.phraseTimes
            if (c.downbeatTimes.isNotEmpty()) out += "downbeat" to c.downbeatTimes
        }
        out += "beat" to c.beats
        return out
    }

    /** Evenly thins [list] to at most [max] entries (always keeping the first and last). */
    private fun <T> thin(list: List<T>, max: Int): List<T> {
        if (list.size <= max) return list
        val step = ceil(list.size / max.toDouble()).toInt()
        val out = ArrayList<T>()
        var i = 0
        while (i < list.size) { out += list[i]; i += step }
        if (out.last() !== list.last()) out += list.last()
        return out
    }

    // ------------------------------------------------------------------------------------------ exits

    /**
     * Exit candidates in [lowerMs, upperMs]: every phrase start (else downbeat, else beat) of the outgoing track, plus
     * the classic end-of-track pick when it is allowed. Each carries the features the pair score needs.
     */
    fun exits(out: TrackContext, lowerMs: Long, upperMs: Long, endPick: DjTransitionPlanner.Pick?, overlapWantedMs: Double, minPlayedFraction: Float, onlyAt: DjTransitionPlanner.Pick? = null): List<ExitCand> {
        val picks = ArrayList<DjTransitionPlanner.Pick>()
        val bar = out.beatsPerBar * out.medianBeatMs
        val levels = if (onlyAt != null) emptyList() else gridLevels(out)
        if (onlyAt != null) picks += onlyAt
        for ((idx, lv) in levels.withIndex()) {
            val (name, all) = lv
            var inRange = all.filter { it in lowerMs..upperMs }
            val last = idx == levels.lastIndex
            if (inRange.size < 2 && !last) continue
            if (last) inRange = inRange.filterIndexed { i, _ -> i % 8 == 0 } // beats only: every 8th, they are not bar-aligned anyway
            for (t in thin(inRange, MixScoring.MAX_EXIT_CANDIDATES)) picks += DjTransitionPlanner.Pick(t, name, false)
            break
        }
        if (onlyAt == null && endPick != null && picks.none { abs(it.timeMs - endPick.timeMs) < 50 } && endPick.timeMs in lowerMs..upperMs) picks += endPick
        val tol = 1.5 * bar
        val end = max(1L, out.audibleEndMs).toDouble()
        val list = ArrayList<ExitCand>(picks.size)
        for (p in picks.sortedBy { it.timeMs }) {
            val e = p.timeMs
            val before = out.meanOver(out.energy, e - MixScoring.REGION_MS, e)
            val after = out.meanOver(out.energy, e, e + MixScoring.REGION_MS)
            val lowBefore = out.meanOver(out.lowBand, e - MixScoring.REGION_MS, e)
            val lowAfterOut = out.meanOver(out.lowBand, e, e + MixScoring.BASS_AFTER_MS)
            val bassDrop = if (lowBefore != null && lowAfterOut != null && lowBefore >= MixScoring.BASS_MIN_BEFORE) ((1.0 - lowAfterOut / lowBefore) / 0.6).coerceIn(0.0, 1.0) else 0.0
            var sectionScore: Double? = null
            var viaOutro = false
            var inBreakdown = false
            out.sections?.let { secs ->
                var best = 0.2
                for (s in secs) {
                    if (abs(s.range.startMs - e) <= tol) {
                        val v = when (s.kind) {
                            SectionKind.OUTRO -> 1.0
                            SectionKind.BREAKDOWN -> 0.95
                            SectionKind.INTRO -> 0.3
                            SectionKind.BODY, SectionKind.DROP -> 0.4
                            SectionKind.UNKNOWN -> 0.5
                        }
                        if (v > best) { best = v; viaOutro = s.kind == SectionKind.OUTRO }
                    }
                    if (abs(s.range.endMs - e) <= tol) {
                        val v = when (s.kind) {
                            SectionKind.BODY, SectionKind.DROP -> 0.9
                            SectionKind.BREAKDOWN, SectionKind.INTRO, SectionKind.UNKNOWN -> 0.5
                            SectionKind.OUTRO -> 0.6
                        }
                        if (v > best) { best = v; viaOutro = false }
                    }
                    if (s.kind == SectionKind.BREAKDOWN && e >= s.range.startMs && e < s.range.endMs) inBreakdown = true
                }
                sectionScore = best
            }
            val edge = if (before != null && after != null) ((before - after) / max(before, 0.1f) * 2.5f).toDouble().coerceIn(0.0, 1.0) else null
            val energyBoundary = edge?.let { 0.2 + 0.8 * it }
            val boundary = when {
                sectionScore != null && energyBoundary != null -> 0.75 * sectionScore!! + 0.25 * energyBoundary
                sectionScore != null -> sectionScore!!
                energyBoundary != null -> energyBoundary
                else -> 0.4
            }
            val played = e / end
            val minP = minPlayedFraction.toDouble()
            val playedScore = 0.5 + 0.5 * ((played - minP) / max(0.05, 0.85 - minP)).coerceIn(0.0, 1.0)
            val room = ((out.audibleEndMs - e) / max(1.0, overlapWantedMs + 4000.0)).coerceIn(0.0, 1.0)
            list += ExitCand(DjTransitionPlanner.Pick(e, p.levelName, viaOutro), levelScore(p.levelName), boundary, playedScore, room, before, lowBefore, inBreakdown, bassDrop)
        }
        return list
    }

    // ------------------------------------------------------------------------------------------ entries

    /** Entry candidates: phrase starts (else downbeat, else beat) from the first audible sound to ~60% of the incoming track. */
    fun entries(inc: TrackContext, minAfterMs: Double, firstPick: DjTransitionPlanner.Pick?, onlyAt: DjTransitionPlanner.Pick? = null): List<EntryCand> {
        val tol = (inc.medianBeatMs * 0.5).toLong()
        val lo = inc.firstAudibleMs - tol
        val hi = (inc.durationMs * MixScoring.ENTRY_MAX_FRACTION).toLong()
        val picks = ArrayList<DjTransitionPlanner.Pick>()
        val levels = if (onlyAt != null) emptyList() else gridLevels(inc)
        if (onlyAt != null) picks += onlyAt
        for ((idx, lv) in levels.withIndex()) {
            val (name, all) = lv
            var inRange = all.filter { it >= lo && it <= hi && inc.durationMs - it >= minAfterMs }
            val last = idx == levels.lastIndex
            if (inRange.isEmpty() && !last) continue
            if (last) inRange = inRange.filterIndexed { i, _ -> i % 16 == 0 }
            for (t in thin(inRange, MixScoring.MAX_ENTRY_CANDIDATES)) picks += DjTransitionPlanner.Pick(t, name, false)
            break
        }
        if (onlyAt == null && firstPick != null && picks.none { abs(it.timeMs - firstPick.timeMs) < 50 }) picks += firstPick
        val bar = inc.beatsPerBar * inc.medianBeatMs
        val tolS = 1.0 * bar
        val list = ArrayList<EntryCand>(picks.size)
        for (p in picks.sortedBy { it.timeMs }) {
            val n = p.timeMs
            val after = inc.meanOver(inc.energy, n, n + MixScoring.REGION_MS)
            val before = inc.meanOver(inc.energy, n - MixScoring.REGION_MS, n)
            val lowAfter = inc.meanOver(inc.lowBand, n, n + MixScoring.REGION_MS)
            val lowNear = inc.meanOver(inc.lowBand, n, n + MixScoring.BASS_ENTRY_MS)
            val lowPrev = if (n < MixScoring.BASS_ENTRY_MS) 0f else inc.meanOver(inc.lowBand, n - MixScoring.BASS_ENTRY_MS, n) ?: 0f
            val bassEntry = if (lowNear != null && lowNear >= MixScoring.BASS_ENTRY_MIN) ((lowNear - lowPrev) / lowNear).toDouble().coerceIn(0.0, 1.0) else 0.0
            var breakdownStart = false
            var kind: Double? = null
            inc.sections?.let { secs ->
                var best = 0.5
                var hit = false
                for (s in secs) {
                    if (abs(s.range.startMs - n) <= tolS) {
                        val v = when (s.kind) {
                            SectionKind.INTRO -> 0.9
                            SectionKind.BODY, SectionKind.DROP -> 1.0
                            SectionKind.BREAKDOWN -> 0.4
                            SectionKind.OUTRO -> 0.15
                            SectionKind.UNKNOWN -> 0.5
                        }
                        if (!hit || v > best) { best = v; hit = true }
                        if (s.kind == SectionKind.BREAKDOWN) breakdownStart = true
                    }
                }
                kind = best
            }
            val energyKind = if (before != null && after != null && n > inc.firstAudibleMs + tol) 0.5 + 0.5 * ((after - before) / 0.3f).toDouble().coerceIn(-1.0, 1.0)
            else if (n <= inc.firstAudibleMs + tol) 0.9 else 0.5
            val kindScore = kind ?: energyKind
            val roomFraction = ((inc.durationMs - n) / max(1.0, inc.durationMs.toDouble())).coerceIn(0.0, 1.0)
            list += EntryCand(p, levelScore(p.levelName), kindScore, roomFraction, after, lowAfter, breakdownStart, bassEntry)
        }
        return list
    }

    // ------------------------------------------------------------------------------------------ pairs

    class ScoredPair(val exit: ExitCand, val entry: EntryCand, val score: Double)

    /** Score of an (exit, entry) pair without the tempo term (added by the caller for beat-matched candidates). */
    fun score(e: ExitCand, n: EntryCand): Double {
        val eo = e.energyBefore
        val ei = n.energyAfter
        val energyTerm = if (eo != null && ei != null) {
            val continuity = 1.0 - min(1.0, abs(eo - ei) / 0.5)
            val atOrAbove = if (ei >= eo - 0.05f) 1.0 else max(0.0, 1.0 - (eo - ei - 0.05) / 0.5)
            // an entry into a breakdown is only fine when the outgoing track is in one as well
            0.5 * continuity + 0.5 * atOrAbove
        } else 0.5
        val lo = e.lowBefore
        val li = n.lowAfter
        val lowTerm = if (lo != null && li != null) 1.0 - min(1.0, abs(lo - li) / 0.5) else 0.5
        val kind = if (n.isBreakdownStart && e.inBreakdown) 1.0 else n.kindScore
        val level = 0.5 * (e.levelScore + n.levelScore)
        val outroZone = MixScoring.OUTRO_ZONE_PENALTY * max(0.0, (e.playedFraction - MixScoring.OUTRO_ZONE_FROM) / (1.0 - MixScoring.OUTRO_ZONE_FROM)).coerceAtMost(1.0)
        return -outroZone + MixScoring.W_BASS_DROP * e.bassDrop + MixScoring.W_BASS_ENTRY * n.bassEntry + MixScoring.W_EXIT_BOUNDARY * e.boundary +
            MixScoring.W_PLAYED * e.playedFraction +
            MixScoring.W_ROOM_OUT * e.roomScore +
            MixScoring.W_ENERGY * energyTerm +
            MixScoring.W_LOW * lowTerm +
            MixScoring.W_ENTRY_KIND * kind +
            MixScoring.W_LEVEL * level +
            MixScoring.W_ROOM_IN * n.roomFraction
    }
}
