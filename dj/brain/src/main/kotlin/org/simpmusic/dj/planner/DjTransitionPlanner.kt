package org.simpmusic.dj.planner

import org.simpmusic.dj.model.Camelot
import org.simpmusic.dj.model.DeckPlan
import org.simpmusic.dj.model.EchoOutSpec
import org.simpmusic.dj.model.MixPoint
import org.simpmusic.dj.model.PlanConstraints
import org.simpmusic.dj.model.DjSettings
import org.simpmusic.dj.model.Ease
import org.simpmusic.dj.model.Keyframe
import org.simpmusic.dj.model.MusicalKey
import org.simpmusic.dj.model.ParamCurve
import org.simpmusic.dj.model.PlanKind
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.model.TransitionPlan
import org.simpmusic.dj.model.TransitionPlanner
import org.simpmusic.dj.model.Trust
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A club-DJ transition planner: a pure function from two [TrackAnalysis] and [DjSettings] to a [TransitionPlan].
 *
 * It never throws: whatever goes wrong (missing or untrusted analysis, degenerate grids, tracks shorter than the
 * overlap, NaNs) ends in a plain equal-power [PlanKind.SIMPLE_CROSSFADE] with a `reason` saying why.
 * See dj/docs/planner.md for the design.
 */
class DjTransitionPlanner : TransitionPlanner {

    override fun plan(from: TrackAnalysis?, to: TrackAnalysis?, settings: DjSettings): TransitionPlan =
        plan(from, to, settings, PlanConstraints())

    override fun plan(from: TrackAnalysis?, to: TrackAnalysis?, settings: DjSettings, constraints: PlanConstraints): TransitionPlan {
        val s = sanitise(settings)
        val earliest = constraints.earliestExitMs.coerceIn(0L, TrackContext.MAX_DURATION_MS)
        return try {
            planUnsafe(from, to, s, earliest)
        } catch (e: Exception) {
            try {
                simplePlan(from?.let { TrackContext(it) }, to?.let { TrackContext(it) }, from?.videoId.orEmpty(), to?.videoId.orEmpty(), s, "planner error (${e.javaClass.simpleName}): ${e.message}", earliest)
            } catch (e2: Exception) {
                lastResort(from?.videoId.orEmpty(), to?.videoId.orEmpty(), "planner error twice: ${e2.message}")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    private fun sanitise(s: DjSettings): DjSettings = s.copy(
        overlapBars = s.overlapBars.coerceIn(1, 64),
        maxTempoBend = if (s.maxTempoBend.isFinite()) s.maxTempoBend.coerceIn(0f, 0.5f) else 0.08f,
        maxPitchShift = s.maxPitchShift.coerceIn(0, 6),
        minConfidence = if (s.minConfidence.isFinite()) s.minConfidence.coerceIn(0f, 1f) else 0.5f,
        fallbackCrossfadeMs = s.fallbackCrossfadeMs.coerceIn(0L, 60_000L),
        minPlayedFraction = if (s.minPlayedFraction.isFinite()) s.minPlayedFraction.coerceIn(0f, 0.95f) else 0.55f,
    )

    /** What a plan attempt produced: a plan, or the reason it did not work out (the next candidate is tried). */
    internal class Attempt(val plan: TransitionPlan?, val fail: String?) {
        companion object {
            fun ok(p: TransitionPlan) = Attempt(p, null)
            fun fail(why: String) = Attempt(null, why)
        }
    }

    /** The pitch decision for a pair of keys (independent of where the mix happens). */
    private class KeyDecision(val shiftOut: Int, val shiftIn: Int, val clash: Boolean, val note: String, val confMin: Float)

    /** Everything about a pair of tracks that does not depend on the chosen exit and entry. */
    private class Ctx(
        val from: TrackAnalysis, val to: TrackAnalysis, val out: TrackContext, val inc: TrackContext,
        val settings: DjSettings, val earliest: Long, val barsMode: Boolean, val conf: Float, val notes: List<String>,
        val key: KeyDecision, val requestedUnits: Int, val minUnits: Int, val softFloor: Int,
    ) {
        val fromId: String get() = out.id
        val toId: String get() = inc.id
    }

    private fun keyDecision(from: TrackAnalysis, to: TrackAnalysis, settings: DjSettings): KeyDecision {
        val keyOut = from.key?.takeIf { it.confidence >= Trust.KEY }
        val keyIn = to.key?.takeIf { it.confidence >= Trust.KEY }
        var shiftOut = 0
        var shiftIn = 0
        var clash = false
        var confMin = 1f
        var keyNote = "keys unknown"
        if (keyOut != null && keyIn != null) {
            val d = Camelot.distance(keyOut.value, keyIn.value)
            keyNote = "${keyOut.value.camelot()}->${keyIn.value.camelot()}"
            if (d <= 1) {
                keyNote += " compatible"
            } else {
                confMin = min(keyOut.confidence, keyIn.confidence)
                val fix = if (settings.allowKeyShift && settings.maxPitchShift > 0) bestShift(keyOut.value, keyIn.value, settings.maxPitchShift) else null
                if (fix != null) {
                    if (fix.first) shiftOut = fix.second else shiftIn = fix.second
                    keyNote += " clash(d=$d) fixed by ${if (fix.first) "outgoing" else "incoming"} ${signed(fix.second)} st"
                } else {
                    clash = true
                    keyNote += " CLASH(d=$d)${if (settings.allowKeyShift) " (no shift within +-${settings.maxPitchShift} st)" else ""}: short EQ-heavy overlap"
                }
            }
        } else if (from.key != null || to.key != null) {
            keyNote = "keys low-confidence (ignored)"
        }
        return KeyDecision(shiftOut, shiftIn, clash, keyNote, confMin)
    }

    private fun planUnsafe(from: TrackAnalysis?, to: TrackAnalysis?, settings: DjSettings, earliest: Long): TransitionPlan {
        val fromId = from?.videoId.orEmpty()
        val toId = to?.videoId.orEmpty()
        if (from == null || to == null) {
            return simplePlan(from?.let { TrackContext(it) }, to?.let { TrackContext(it) }, fromId, toId, settings, "missing analysis (${if (from == null) "outgoing" else "incoming"} track)", earliest)
        }
        val out = TrackContext(from)
        val inc = TrackContext(to)

        // ---- gating on what any grid-based mix needs: a trusted beat grid. The bpm field is informational only ----
        val missing = ArrayList<String>()
        if (out.beatsConf < Trust.BEATS) missing += "outgoing beat grid (${fmt(out.beatsConf)} < ${Trust.BEATS})"
        if (inc.beatsConf < Trust.BEATS) missing += "incoming beat grid (${fmt(inc.beatsConf)} < ${Trust.BEATS})"
        if (out.medianBeatMs <= 0.0 || inc.medianBeatMs <= 0.0) missing += "unusable beat spacing"
        if (out.durationMs < 4000 || inc.durationMs < 4000) missing += "track too short"
        if (missing.isNotEmpty()) {
            return simplePlan(out, inc, fromId, toId, settings, "not beat-matchable: " + missing.joinToString("; "), earliest)
        }

        var conf = minOf(out.beatsConf, inc.beatsConf)
        val notes = ArrayList<String>()
        for ((ctx, name) in listOf(out to "outgoing", inc to "incoming")) {
            val bpm = ctx.bpmValue ?: continue
            val ratio = ctx.gridBpm / bpm
            if (listOf(1.0, 2.0, 0.5).none { abs(ratio / it - 1.0) < 0.08 }) {
                notes += "$name bpm field ${fmt1(bpm)} differs from its grid ${fmt1(ctx.gridBpm)} (grid used)"
            }
        }

        val barsMode = out.barsTrusted && inc.barsTrusted && out.beatsPerBar == inc.beatsPerBar
        if (!barsMode) notes += "downbeats untrusted: aligned on beats only (no bar alignment claimed), overlap capped"
        else conf = min(conf, min(out.downbeatConf, inc.downbeatConf))

        val key = keyDecision(from, to, settings)
        conf = min(conf, key.confMin)
        val requestedUnits = if (barsMode) settings.overlapBars else min(settings.overlapBars, BEAT_MODE_MAX_BARS) * out.beatsPerBar
        val c = Ctx(
            from, to, out, inc, settings, earliest, barsMode, conf, notes, key, requestedUnits,
            minUnits = if (barsMode) 1 else 4, softFloor = if (barsMode) 2 else 8,
        )
        if (settings.mixPoint == MixPoint.ANYWHERE) {
            planAnywhere(c)?.let { return it }
            // nothing anywhere worked out (or nothing is allowed by the constraints): the classic end-of-track mix, else a crossfade
        }
        return planAtEnd(c)
    }

    // ---------------------------------------------------------------------------------------------
    // AT_END: near the outro / last phrase of the outgoing track, first phrase of the incoming one

    private fun planAtEnd(c: Ctx): TransitionPlan {
        val out = c.out
        val inc = c.inc
        val settings = c.settings
        val margin = MARGIN_MS
        // The tempo that matters is the LOCAL one around the exit and entry points. Start from the tempo of the region
        // where each mix happens, choose the points, then re-measure on the windows that actually overlap (twice at most).
        val roughOut = out.medianIbiIn(out.audibleEndMs - 60_000L, out.audibleEndMs).takeIf { it > 0 } ?: out.medianBeatMs
        val roughIn = inc.medianIbiIn(inc.firstAudibleMs, inc.firstAudibleMs + 60_000L).takeIf { it > 0 } ?: inc.medianBeatMs
        var tempo = chooseTempo(roughOut, roughIn, settings.maxTempoBend.toDouble())
            ?: return echoAtEnd(c, tempoWhy(c))
        // The overlap window is what gets checked for a steady beat, so a long overlap sees more of the track (a drumless
        // break, a tempo change) than a short one. When the window is irregular the overlap is halved before the mix is given up.
        var desiredUnits = c.requestedUnits
        val outroStart = out.outro()?.range?.startMs

        var loc: Located
        var pass = 0
        while (true) {
            loc = locate(c, tempo, desiredUnits, margin, outroStart)
            val shortest = max(c.minUnits, c.softFloor)
            if (loc.fail != null && loc.irregular && desiredUnits > shortest) {
                desiredUnits = max(shortest, desiredUnits / 2)
                continue
            }
            loc.fail?.let { return simplePlan(out, inc, c.fromId, c.toId, settings, it + if (desiredUnits < c.requestedUnits) " (also tried overlaps down to $desiredUnits)" else "", c.earliest) }
            val t2 = loc.tempo ?: return echoAtEnd(c, tempoWhy(c))
            pass++
            val settled = abs(t2.rateOut / tempo.rateOut - 1.0) < 0.02 && abs(t2.rateIn / tempo.rateIn - 1.0) < 0.02
            tempo = t2
            if (settled || pass >= 2) break
        }
        val r = finishBeat(c, tempo, loc.exitPick!!, loc.entryPick!!, loc.localOut!!, loc.localIn!!, loc.iOut, loc.iIn, desiredUnits)
        return r.plan ?: simplePlan(out, inc, c.fromId, c.toId, settings, r.fail!!, c.earliest)
    }

    private fun tempoWhy(c: Ctx) =
        "tempos ${fmt1(c.out.gridBpm)} -> ${fmt1(c.inc.gridBpm)} bpm are beyond the +-${(c.settings.maxTempoBend * 100).toInt()}% bend"

    /** Tempos cannot be matched: an echo-out at the end of the outgoing track (never a bare cut). */
    private fun echoAtEnd(c: Ctx, why: String): TransitionPlan {
        val out = c.out
        val inc = c.inc
        val beat = out.medianBeatMs
        val bar = out.beatsPerBar * beat
        val dry = EchoOut.dryFadeMs(bar)
        val exit = pickExit(out, out.audibleEndMs, bar + MARGIN_MS, MARGIN_MS + beat, max(dry + 250.0, c.earliest + dry), out.outro()?.range?.startMs)
        val entry = pickEntry(inc, inc.firstAudibleMs, MARGIN_MS + 2 * inc.beatsPerBar * inc.medianBeatMs)
            ?: pickEntry(inc, inc.firstAudibleMs, MARGIN_MS)
        if (exit == null || entry == null) return simplePlan(out, inc, c.fromId, c.toId, c.settings, "$why; no exit/entry point for an echo-out", c.earliest)
        val a = buildEchoOut(c, exit, entry, why)
        return a.plan ?: simplePlan(out, inc, c.fromId, c.toId, c.settings, "$why; echo-out not possible: ${a.fail}", c.earliest)
    }

    // ---------------------------------------------------------------------------------------------
    // ANYWHERE: candidate exits and entries across the whole tracks, scored as pairs

    private fun planAnywhere(c: Ctx): TransitionPlan? {
        val out = c.out
        val inc = c.inc
        val settings = c.settings
        val beat = out.medianBeatMs
        val bar = out.beatsPerBar * beat
        val minPlayed = min(settings.minPlayedFraction.toDouble() * out.audibleEndMs, MixScoring.MIN_PLAYED_CAP_MS.toDouble()).toLong()
        val lower = max(max(c.earliest, minPlayed), (4 * beat + 250.0).toLong())
        val upper = out.audibleEndMs - (bar + MARGIN_MS).toLong()
        if (upper < lower) return null
        val overlapWanted = c.requestedUnits * if (c.barsMode) bar else beat
        val endPick = pickExit(out, out.audibleEndMs, overlapWanted + MARGIN_MS, bar + MARGIN_MS, lower.toDouble(), out.outro()?.range?.startMs)
        val exits = MixCandidates.exits(out, lower, upper, endPick, overlapWanted, settings.minPlayedFraction)
        val minAfterIn = 2.0 * inc.beatsPerBar * inc.medianBeatMs + MARGIN_MS
        val firstPick = pickEntry(inc, inc.firstAudibleMs, minAfterIn)
        val entries = MixCandidates.entries(inc, minAfterIn, firstPick)
        if (exits.isEmpty() || entries.isEmpty()) return null

        val bend = settings.maxTempoBend.toDouble()
        class Cand(val pair: MixCandidates.ScoredPair, val tempo: Tempo?)
        val all = ArrayList<Cand>(exits.size * entries.size)
        val perOut = HashMap<Long, Double>()
        val perIn = HashMap<Long, Double>()
        for (e in exits) perOut[e.timeMs] = localPeriod(out, e.timeMs, ahead = true)
        for (n in entries) perIn[n.timeMs] = localPeriod(inc, n.timeMs, ahead = true)
        for (e in exits) for (n in entries) {
            val t = chooseTempo(perOut.getValue(e.timeMs), perIn.getValue(n.timeMs), bend)
            val base = MixCandidates.score(e, n)
            val tempoTerm = if (t == null) 0.0 else MixScoring.W_TEMPO * (1.0 - 0.5 * min(1.0, max(abs(t.rateOut - 1.0), abs(t.rateIn - 1.0)) / max(bend, 1e-6)))
            all += Cand(MixCandidates.ScoredPair(e, n, base + tempoTerm), t)
        }
        val order = compareByDescending<Cand> { it.pair.score }.thenBy { it.pair.exit.timeMs }.thenBy { it.pair.entry.timeMs }
        all.sortWith(order)

        var lastFail: String? = null
        // 1. beat-matched, on the best-scoring pairs whose local tempos are compatible
        var tries = 0
        for (cand in all) {
            if (cand.tempo == null) continue
            if (tries++ >= MixScoring.MAX_BEAT_TRIES) break
            val a = beatAttempt(c, cand.pair.exit.pick, cand.pair.entry.pick, cand.tempo)
            a.plan?.let { return it.withReason("mix point ${(100.0 * cand.pair.exit.timeMs / max(1L, out.audibleEndMs)).toInt()}% into the outgoing track (pair score ${fmt(cand.pair.score.toFloat())}); ") }
            if (lastFail == null) lastFail = a.fail
        }
        // 2. nothing beat-matches: an echo-out, on the best-scoring pairs regardless of tempo
        val why = if (tries == 0) tempoWhy(c) + " at every candidate mix point" else "no candidate mix point beat-matches (${lastFail ?: "?"})"
        var echoTries = 0
        var echoFail: String? = null
        for (cand in all) {
            if (echoTries++ >= MixScoring.MAX_ECHO_TRIES) break
            val a = buildEchoOut(c, cand.pair.exit.pick, cand.pair.entry.pick, why, cand.pair.exit, cand.pair.score)
            a.plan?.let { return it }
            if (echoFail == null) echoFail = a.fail
        }
        return null
    }

    private fun TransitionPlan.withReason(prefix: String): TransitionPlan = copy(reason = prefix + reason)

    /** Median beat interval right after (or, when there is none, around) [t]: the local tempo where a mix would happen. */
    private fun localPeriod(c: TrackContext, t: Long, ahead: Boolean): Double {
        if (ahead) c.medianIbiIn(t, t + 24_000L).takeIf { it > 0 }?.let { return it }
        return c.medianIbiIn(t - 24_000L, t + 24_000L).takeIf { it > 0 } ?: c.medianBeatMs
    }

    /**
     * Full beat-matched attempt at ONE (exit, entry) pair: measures the local grids (shortening the overlap when they
     * are irregular over a long one), refines the tempo, then builds the plan.
     */
    private fun beatAttempt(c: Ctx, exitPick: Pick, entryPick: Pick, rough: Tempo): Attempt {
        var desired = c.requestedUnits
        val shortest = max(c.minUnits, c.softFloor)
        var tempo = rough
        var pass = 0
        var loc: Located
        while (true) {
            loc = measurePair(c, tempo, desired, exitPick, entryPick)
            if (loc.fail != null) {
                if (loc.irregular && desired > shortest) { desired = max(shortest, desired / 2); continue }
                return Attempt.fail(loc.fail)
            }
            val t2 = loc.tempo ?: return Attempt.fail("local tempos are beyond the bend")
            pass++
            val settled = abs(t2.rateOut / tempo.rateOut - 1.0) < 0.02 && abs(t2.rateIn / tempo.rateIn - 1.0) < 0.02
            tempo = t2
            if (settled || pass >= 2) break
        }
        return finishBeat(c, tempo, loc.exitPick!!, loc.entryPick!!, loc.localOut!!, loc.localIn!!, loc.iOut, loc.iIn, desired)
    }

    // ---------------------------------------------------------------------------------------------
    // beat-matched plan from a chosen exit / entry

    /** Builds the BEAT_MATCHED plan for a chosen pair; every way it can fail returns the reason instead. */
    private fun finishBeat(
        c: Ctx, tempo2: Tempo, exitPick: Pick, entryPick: Pick, localOut: LocalGrid, localIn: LocalGrid,
        iOut: Int, iIn: Int, desiredUnits: Int,
    ): Attempt {
        val out = c.out
        val inc = c.inc
        val settings = c.settings
        val barsMode = c.barsMode
        val minUnits = c.minUnits
        val softFloor = c.softFloor
        val requestedUnits = c.requestedUnits
        val margin = MARGIN_MS
        val notes = ArrayList(c.notes)
        var conf = c.conf
        val shiftOut = c.key.shiftOut
        val shiftIn = c.key.shiftIn
        val clash = c.key.clash
        val keyNote = c.key.note
        if (exitPick.viaOutro) conf = min(conf, out.sectionsConf)
        conf = min(conf, (1.0 - 2.0 * localOut.noiseRms / out.medianBeatMs).toFloat().coerceIn(0f, 1f))
        conf = min(conf, (1.0 - 2.0 * localIn.noiseRms / inc.medianBeatMs).toFloat().coerceIn(0f, 1f))
        val exitMs = localOut.timeAt(iOut).roundToLong().coerceAtLeast(0L)
        val entryMs = localIn.timeAt(iIn).roundToLong().coerceAtLeast(0L)
        val w = tempo2.wallLatticeMs
        val lattice = w
        val unit = if (barsMode) out.beatsPerBar * w else w
        val rateOut = tempo2.rateOut
        val rateIn = tempo2.rateIn

        // ---- overlap length in whole units ----
        val availOut = (out.audibleEndMs - exitMs - margin).coerceAtLeast(0.0)
        val availIn = (inc.durationMs - entryMs - margin).coerceAtLeast(0.0)
        val hardUnits = floor(min(availOut / rateOut, availIn / rateIn) / unit + UNIT_EPS).toInt()
        val outroLeft = out.outro()?.let { o -> if (exitMs >= o.range.startMs - out.medianBeatMs && exitMs < o.range.endMs) o.range.endMs - exitMs else null }
        val introLeft = inc.introAt(entryMs)?.let { it.range.endMs - entryMs }
        var softUnits = Int.MAX_VALUE
        if (outroLeft != null) softUnits = min(softUnits, floor(outroLeft / rateOut / unit + UNIT_EPS).toInt())
        if (introLeft != null) softUnits = min(softUnits, floor(introLeft / rateIn / unit + UNIT_EPS).toInt())
        if (outroLeft != null) conf = min(conf, out.sectionsConf)
        var units = min(desiredUnits, max(softUnits, min(softFloor, hardUnits)))
        units = min(units, hardUnits)
        if (clash) units = min(units, max(minUnits, floor(CLASH_MAX_MS / unit + UNIT_EPS).toInt()))
        if (units < minUnits) {
            return Attempt.fail("not enough audio for a ${minUnits}-${if (barsMode) "bar" else "beat"} overlap (room out ${availOut / 1000.0}s, in ${availIn / 1000.0}s)")
        }
        // ---- drift: constant rates must keep the two local tempo curves in phase across the overlap ----
        val stepsPerUnit = if (barsMode) out.beatsPerBar else 1
        val exitTrue = localOut.timeAt(iOut)
        val entryTrue = localIn.timeAt(iIn)
        fun lockError(u: Int): Double {
            var worst = 0.0
            for (k in 0..u * stepsPerUnit) {
                val to = (localOut.timeAt(iOut + tempo2.strideOut * k) - exitTrue) / rateOut
                val ti = (localIn.timeAt(iIn + tempo2.strideIn * k) - entryTrue) / rateIn
                worst = max(worst, abs(to - ti))
            }
            return worst
        }
        val unitsBeforeDrift = units
        var drift = lockError(units)
        while (drift > MAX_DRIFT_MS && units > minUnits) { units--; drift = lockError(units) }
        if (drift > MAX_DRIFT_MS) {
            return Attempt.fail("tempo drifts: beats of the two tracks slide ${fmt1(drift)} ms apart even over a ${minUnits}-${if (barsMode) "bar" else "beat"} overlap")
        }
        if (units < unitsBeforeDrift) notes += "overlap shortened from $unitsBeforeDrift to $units because the local tempo drifts"
        val overlapMs = (units * unit).roundToLong()

        if (conf < settings.minConfidence) {
            return Attempt.fail("confidence ${fmt(conf)} below minimum ${fmt(settings.minConfidence)}: " + notes.joinToString())
        }

        // ---- ramps (pre-roll on the outgoing deck, return-to-native on the incoming one) ----
        val bendOut = abs(rateOut - 1.0)
        val bendIn = abs(rateIn - 1.0)
        val rampBeats = when {
            max(bendOut, bendIn) > 0.05 -> 16
            max(bendOut, bendIn) > 0.02 -> 12
            else -> 8
        }.let { if (shiftOut != 0 || shiftIn != 0) max(it, 16) else it }
        val outBeatWall = w / tempo2.strideOut
        val inBeatWall = w / tempo2.strideIn
        // the ramp must fit into the audio before the exit point (the deck plays ~rate during it)
        val beforeRoom = (exitMs - 250L).coerceAtLeast(0L)
        val rampOutMs = min(rampBeats * outBeatWall, beforeRoom / ((1.0 + rateOut) / 2.0)).let { floor(it).toLong() }
        val minRamp = (4 * outBeatWall).toLong()
        if (rampOutMs < minRamp && (bendOut > 1e-4 || shiftOut != 0)) {
            return Attempt.fail("not enough audio before the exit point for the tempo pre-roll")
        }
        val afterRoom = (inc.durationMs - entryMs - 250L - overlapMs * rateIn).coerceAtLeast(0.0)
        val rampBackMs = floor(min(rampBeats * inBeatWall, afterRoom / max(rateIn, 0.5))).toLong().coerceAtLeast(0L)

        // ---- gain matching (attenuate the louder deck only: never boosts, so never clips) ----
        var gainOut = 1.0
        var gainIn = 1.0
        val lo = out.loudnessDb
        val li = inc.loudnessDb
        if (lo != null && li != null) {
            val diff = (lo - li).toDouble().coerceIn(-6.0, 6.0) // >0: outgoing louder
            if (diff > 0) gainOut = 10.0.pow(-diff / 20.0) else gainIn = 10.0.pow(diff / 20.0)
        }

        // ---- bass swap / filters ----
        val lowOut = out.meanOver(out.lowBand, exitMs, exitMs + (overlapMs * rateOut).toLong())
        val lowIn = inc.meanOver(inc.lowBand, entryMs, entryMs + (overlapMs * rateIn).toLong())
        val bassWorth = (lowOut == null || lowIn == null) || (lowOut > BASS_WORTH && lowIn > BASS_WORTH)
        val bassSwap = settings.bassSwap && bassWorth && overlapMs >= 2 * lattice * 2
        val swapWidth = max(200L, min(lattice.toLong(), overlapMs / 4))
        val mid = overlapMs / 2
        val swapStart = (mid - swapWidth / 2).coerceAtLeast(0L)
        val swapEnd = swapStart + swapWidth

        val outRate = if (bendOut > RATE_EPS) ParamCurve(listOf(Keyframe(-rampOutMs, 1f), Keyframe(0, rateOut.toFloat(), Ease.SMOOTHSTEP))) else ParamCurve.constant(1f)
        val inRate = if (bendIn > RATE_EPS) {
            val keys = arrayListOf(Keyframe(0, rateIn.toFloat()), Keyframe(overlapMs, rateIn.toFloat()))
            if (rampBackMs > 0) keys += Keyframe(overlapMs + rampBackMs, 1f, Ease.SMOOTHSTEP)
            ParamCurve(keys)
        } else ParamCurve.constant(1f)
        val outPitch = if (shiftOut != 0) ParamCurve(listOf(Keyframe(-rampOutMs, 0f), Keyframe(0, shiftOut.toFloat(), Ease.SMOOTHSTEP))) else ParamCurve.constant(0f)
        val inPitch = if (shiftIn != 0) {
            ParamCurve(listOf(Keyframe(0, shiftIn.toFloat()), Keyframe(overlapMs, shiftIn.toFloat()), Keyframe(overlapMs + max(rampBackMs, 1L), 0f, Ease.SMOOTHSTEP)))
        } else ParamCurve.constant(0f)

        val outVolume = equalPowerOut(overlapMs, gainOut, if (gainOut < 1.0) rampOutMs else 0L)
        val inVolume = equalPowerIn(overlapMs, gainIn)

        val outLow = if (bassSwap) ParamCurve(listOf(Keyframe(swapStart, 20f), Keyframe(swapEnd, 250f, Ease.EXPONENTIAL))) else ParamCurve.constant(20f)
        val inLow = if (bassSwap) ParamCurve(listOf(Keyframe(swapStart, 250f), Keyframe(swapEnd, 20f, Ease.EXPONENTIAL))) else ParamCurve.constant(20f)
        val outHigh = when {
            clash -> ParamCurve(listOf(Keyframe(0, 20000f), Keyframe(overlapMs, 2500f, Ease.EXPONENTIAL)))
            overlapMs >= 4 * lattice -> ParamCurve(listOf(Keyframe(mid, 20000f), Keyframe(overlapMs, 9000f, Ease.EXPONENTIAL)))
            else -> ParamCurve.constant(20000f)
        }
        val inHigh = if (clash) ParamCurve(listOf(Keyframe(0, 2500f), Keyframe((overlapMs * 0.6).toLong(), 20000f, Ease.EXPONENTIAL))) else ParamCurve.constant(20000f)

        val mixBpm = (60000.0 / w).toFloat()
        val plan = TransitionPlan(
            fromId = c.fromId, toId = c.toId, kind = PlanKind.BEAT_MATCHED,
            exitPointMs = exitMs, entryPointMs = entryMs, overlapMs = overlapMs,
            outgoing = DeckPlan(rate = outRate, pitchSemitones = outPitch, volume = outVolume, lowCutHz = outLow, highCutHz = outHigh),
            incoming = DeckPlan(rate = inRate, pitchSemitones = inPitch, volume = inVolume, lowCutHz = inLow, highCutHz = inHigh),
            confidence = conf.coerceIn(0f, 1f), reason = "", mixBpm = mixBpm,
        )
        if (plan.exitPointMs + plan.preRollMs < c.earliest) {
            return Attempt.fail("the tempo pre-roll would start before the earliest allowed position (${c.earliest} ms)")
        }
        val reason = buildString {
            append("beat-matched ").append(fmt1(out.gridBpm)).append(" -> ").append(fmt1(inc.gridBpm)).append(" bpm at ").append(fmt1(mixBpm.toDouble()))
            append(" (out x").append(fmt3(rateOut)).append(", in x").append(fmt3(rateIn))
            if (tempo2.strideOut != 1 || tempo2.strideIn != 1) append(", half/double-time lattice ${tempo2.strideOut}:${tempo2.strideIn}")
            append("); exit ").append(exitMs).append(" ms (").append(exitPick.levelName).append(if (exitPick.viaOutro) ", outro" else "")
            append("), entry ").append(entryMs).append(" ms (").append(entryPick.levelName).append("); overlap ")
            append(units).append(if (barsMode) " bars" else " beats").append(" = ").append(overlapMs).append(" ms")
            if (units < requestedUnits) append(" (clamped from $requestedUnits" + (if (desiredUnits < requestedUnits) ", the beat grid was irregular over a longer overlap" else "") + ")")
            append("; ").append(keyNote)
            append("; bass swap ").append(if (bassSwap) "on" else if (!settings.bassSwap) "off (setting)" else "off (no bass to swap)")
            if (gainOut < 1.0 || gainIn < 1.0) append("; gain out ${fmt(gainOut.toFloat())} in ${fmt(gainIn.toFloat())}")
            if (notes.isNotEmpty()) append("; ").append(notes.joinToString())
            append("; confidence ").append(fmt(conf))
        }
        return Attempt.ok(plan.copy(reason = reason))
    }

    // ---------------------------------------------------------------------------------------------
    // echo-out

    /** Timing constants of the echo-out (all derived from the outgoing beat). */
    internal object EchoOut {
        /** Length of the outgoing dry fade: about one bar, clamped. */
        fun dryFadeMs(barMs: Double): Double = (if (barMs < 1500.0) 2 * barMs else barMs).coerceIn(1500.0, 4800.0)

        /** 3/4 beat if it lies in 200..750 ms, else 1/2, 1/4, 1 beat. */
        fun delayMs(beatMs: Double): Double {
            for (f in doubleArrayOf(0.75, 0.5, 0.25, 1.0)) { val d = beatMs * f; if (d in 200.0..750.0) return d }
            return (beatMs * 0.5).coerceIn(150.0, 1000.0)
        }
        const val FEEDBACK = 0.6f
        const val WET_LEVEL = 1.0f
        const val LOW_CUT_END_HZ = 700f
        const val INCOMING_FADE_IN_MS = 2L
    }

    /**
     * Echo-out for tracks whose tempos cannot be matched (or whose beat-match fell through): the outgoing dry signal
     * fades over about a bar under a rising high-pass while a beat-synced feedback delay rings out; the incoming deck
     * enters at [entryPick] at native tempo and full level. No tempo automation: the decks are never beat-matched.
     */
    private fun buildEchoOut(c: Ctx, exitPick: Pick, entryPick: Pick, why: String, cand: ExitCand? = null, score: Double = 0.0): Attempt {
        val out = c.out
        val inc = c.inc
        val e = exitPick.timeMs
        val n = entryPick.timeMs
        val beat = out.medianIbiIn(e - 24_000L, e).takeIf { it > 0 } ?: out.medianBeatMs
        val bar = out.beatsPerBar * beat
        val dryMs = EchoOut.dryFadeMs(bar).toLong()
        if (e - dryMs - 250L < 0) return Attempt.fail("not enough audio before the exit point for the dry fade")
        if (e - dryMs < c.earliest) return Attempt.fail("the dry fade would start before the earliest allowed position (${c.earliest} ms)")
        var conf = c.conf
        if (exitPick.viaOutro) conf = min(conf, out.sectionsConf)
        if (conf < c.settings.minConfidence) return Attempt.fail("confidence ${fmt(conf)} below minimum ${fmt(c.settings.minConfidence)}")
        val inRoom = inc.durationMs - n
        var tailMs = (3 * bar).coerceIn(3500.0, 8000.0).toLong()
        tailMs = min(tailMs, inRoom - 500L)
        if (tailMs < 1500L) return Attempt.fail("not enough incoming audio after the entry point for the echo tail")
        val delayMs = EchoOut.delayMs(beat).toFloat()
        val fbk = EchoOut.FEEDBACK

        // outgoing: dry fade over the last bar before T0 (equal power) under a rising high-pass; ends at 0 at T0
        val volKeys = ArrayList<Keyframe>()
        val steps = 12
        for (k in 0..steps) {
            val x = k.toDouble() / steps
            volKeys += Keyframe((-dryMs * (1.0 - x)).roundToLong(), if (k == steps) 0f else cos(x * Math.PI / 2).toFloat())
        }
        val outLow = ParamCurve(listOf(Keyframe(-dryMs, 20f), Keyframe(0, EchoOut.LOW_CUT_END_HZ, Ease.EXPONENTIAL)))
        // aux send: opens over the first half beat of the fade, holds, closes over the last quarter beat (before the fader is 0)
        val openMs = min((beat / 2).toLong(), dryMs / 4)
        val closeMs = min((beat / 4).toLong(), dryMs / 8).coerceAtLeast(1L)
        val send = ParamCurve(listOf(Keyframe(-dryMs, 0f), Keyframe(-dryMs + openMs, 1f, Ease.SMOOTHSTEP), Keyframe(-closeMs, 1f), Keyframe(0, 0f, Ease.SMOOTHSTEP)))
        val fadeTail = min(bar.toLong(), tailMs / 2)
        val wet = ParamCurve(listOf(Keyframe(-dryMs, EchoOut.WET_LEVEL), Keyframe(tailMs - fadeTail, EchoOut.WET_LEVEL), Keyframe(tailMs, 0f, Ease.SMOOTHSTEP)))
        val spec = EchoOutSpec(delayMs = delayMs, feedback = fbk, send = send, wet = wet, tailMs = tailMs)

        // incoming: native tempo, full level from T0 (2 ms de-click); a louder incoming deck is attenuated, then glides to 1.0
        var gainIn = 1.0
        val lo = out.loudnessDb
        val li = inc.loudnessDb
        if (lo != null && li != null) {
            val diff = (lo - li).toDouble().coerceIn(-6.0, 6.0)
            if (diff < 0) gainIn = 10.0.pow(diff / 20.0)
        }
        val inVol = if (gainIn < 1.0) {
            ParamCurve(listOf(Keyframe(0, 0f), Keyframe(EchoOut.INCOMING_FADE_IN_MS, gainIn.toFloat()), Keyframe(tailMs / 2, gainIn.toFloat()), Keyframe(tailMs, 1f)))
        } else ParamCurve(listOf(Keyframe(0, 0f), Keyframe(EchoOut.INCOMING_FADE_IN_MS, 1f)))

        val playedPct = (100.0 * e / max(1L, out.audibleEndMs)).toInt()
        val reason = buildString {
            append("echo-out: ").append(why)
            append("; exit ").append(e).append(" ms (").append(exitPick.levelName).append(if (exitPick.viaOutro) ", outro" else "").append(", $playedPct% played)")
            append(", entry ").append(n).append(" ms (").append(entryPick.levelName).append(")")
            append("; dry fade ").append(dryMs).append(" ms under a rising high-pass, ")
            append(fmt1(delayMs.toDouble())).append(" ms echo (").append(fmt3(delayMs / beat)).append(" beat) x").append(fmt(fbk)).append(" rings out ").append(tailMs).append(" ms")
            append("; ").append(c.key.note).append(" (no pitch shift: the echo tail is high-passed and short)")
            if (cand != null) append("; pair score ").append(fmt(score.toFloat()))
            append("; confidence ").append(fmt(conf))
        }
        val plan = TransitionPlan(
            fromId = c.fromId, toId = c.toId, kind = PlanKind.ECHO_OUT,
            exitPointMs = e, entryPointMs = n, overlapMs = 0L,
            outgoing = DeckPlan(volume = ParamCurve(volKeys), lowCutHz = outLow),
            incoming = DeckPlan(volume = inVol),
            confidence = conf.coerceIn(0f, 1f), reason = reason, mixBpm = null, echoOut = spec,
        )
        return Attempt.ok(plan)
    }

    // ---------------------------------------------------------------------------------------------
    // measuring a pair

    internal class Located(
        val fail: String? = null, val tempo: Tempo? = null, val exitPick: Pick? = null, val entryPick: Pick? = null,
        val localOut: LocalGrid? = null, val localIn: LocalGrid? = null, val iOut: Int = 0, val iIn: Int = 0,
        /** True when the failure was an irregular beat grid in the overlap window (a shorter overlap may still work). */
        val irregular: Boolean = false,
        /** 1: the outgoing window is to blame (try an earlier exit), 2: the incoming one (try a later entry), 0: neither. */
        val side: Int = 0,
    )

    /** Measures the LOCAL tempo of both overlap windows around a fixed exit and entry. */
    private fun measurePair(c: Ctx, tempo: Tempo, desiredUnits: Int, exitPick: Pick, entryPick: Pick): Located {
        val out = c.out
        val inc = c.inc
        val lattice = tempo.wallLatticeMs
        val rough = desiredUnits * if (c.barsMode) out.beatsPerBar * lattice else lattice
        val ibiOut = tempo.wallLatticeMs * tempo.rateOut / tempo.strideOut
        val ibiIn = tempo.wallLatticeMs * tempo.rateIn / tempo.strideIn
        val spanOut = max(6, ceil(rough * tempo.rateOut / ibiOut).toInt() + 2)
        val spanIn = max(6, ceil(rough * tempo.rateIn / ibiIn).toInt() + 2)
        val iOut = out.nearestBeatIndex(exitPick.timeMs)
        val iIn = inc.nearestBeatIndex(entryPick.timeMs)
        val localOut = out.local(iOut - 4, iOut + spanOut)
        val localIn = inc.local(iIn, iIn + spanIn + 4)
        if (localOut == null) return Located(fail = "too few beats around the exit point to measure the local tempo", side = 1)
        if (localIn == null) return Located(fail = "too few beats around the entry point to measure the local tempo", side = 2)
        if (localOut.noiseRms > IRREGULAR_RMS * localOut.medianIbi) {
            return Located(fail = "outgoing beat grid is irregular around the mix (rms ${fmt1(localOut.noiseRms)} ms of a ${fmt1(localOut.medianIbi)} ms beat)", irregular = true, side = 1)
        }
        if (localIn.noiseRms > IRREGULAR_RMS * localIn.medianIbi) {
            return Located(fail = "incoming beat grid is irregular around the mix (rms ${fmt1(localIn.noiseRms)} ms of a ${fmt1(localIn.medianIbi)} ms beat)", irregular = true, side = 2)
        }
        val endOut = min(localOut.i0 + localOut.n - 1, iOut + spanOut)
        val endIn = min(localIn.i0 + localIn.n - 1, iIn + spanIn)
        val periodOut = localOut.meanPeriod(iOut, endOut)
        val periodIn = localIn.meanPeriod(iIn, endIn)
        if (periodOut <= 0.0 || periodIn <= 0.0) return Located(fail = "beat grid runs backwards around the mix")
        val t2 = chooseTempo(periodOut, periodIn, c.settings.maxTempoBend.toDouble())
        return Located(tempo = t2, exitPick = exitPick, entryPick = entryPick, localOut = localOut, localIn = localIn, iOut = iOut, iIn = iIn)
    }

    /** AT_END: picks exit/entry with the given tempo estimate, stepping to the previous exit / next entry when a window is irregular. */
    private fun locate(c: Ctx, tempo: Tempo, desiredUnits: Int, margin: Double, outroStart: Long?): Located {
        val out = c.out
        val inc = c.inc
        val lattice = tempo.wallLatticeMs
        val unitWall = if (c.barsMode) out.beatsPerBar * lattice else lattice
        val rampMin = 4 * out.medianBeatMs + 250.0
        val before = max(rampMin, c.earliest.toDouble())
        val fullAfterOut = desiredUnits * unitWall * tempo.rateOut + margin
        val minAfterOut = c.minUnits * unitWall * tempo.rateOut + margin
        val preferAfterOut = c.softFloor * unitWall * tempo.rateOut + margin
        var maxExit = Long.MAX_VALUE
        var afterEntry = Long.MIN_VALUE
        var lastFail = "no usable exit/entry point"
        val minAfterIn = c.minUnits * unitWall * tempo.rateIn + margin
        for (attempt in 0 until MAX_POINT_RETRIES) {
            val exitPick = pickExit(out, out.audibleEndMs, fullAfterOut, minAfterOut, before, outroStart, preferAfterOut, maxExit)
                ?: return Located(fail = if (attempt == 0) "no exit point with enough room before the end of the outgoing track" + (if (c.earliest > 0) " (earliest allowed exit ${c.earliest} ms)" else "") else lastFail)
            val entryPick = pickEntry(inc, inc.firstAudibleMs, minAfterIn, afterEntry)
                ?: return Located(fail = if (attempt == 0) "no entry point with enough audio in the incoming track" else lastFail)
            val m = measurePair(c, tempo, desiredUnits, exitPick, entryPick)
            if (m.fail != null && m.side != 0) {
                lastFail = m.fail
                if (m.side == 1) maxExit = exitPick.timeMs - 1 else afterEntry = entryPick.timeMs
                continue
            }
            return m
        }
        return Located(fail = lastFail, irregular = lastFail.contains("irregular"))
    }

    // ---------------------------------------------------------------------------------------------
    // tempo

    /**
     * Wall-clock lattice: the mix advances one lattice step every [wallLatticeMs]; the outgoing deck covers
     * `strideOut` of its own beats per step and the incoming deck `strideIn` (2 = half/double-time equivalence).
     */
    internal class Tempo(val strideOut: Int, val strideIn: Int, val wallLatticeMs: Double, val rateOut: Double, val rateIn: Double)

    /**
     * Splits the tempo difference geometric-mean-wise (rateOut = sqrt(Lo/Li), rateIn = sqrt(Li/Lo) with Lo/Li the lattice
     * periods), which minimises the larger of the two stretches; each deck is bent by half the log-difference, so
     * both stay inside [maxBend] up to a total difference of (1+bend)^2. Half/double time is tried through the
     * lattice strides (1:1, 1:2, 2:1) and the smallest bend wins, preferring 1:1 on ties.
     */
    internal fun chooseTempo(periodOut: Double, periodIn: Double, maxBend: Double, fixedStrideOut: Int? = null, fixedStrideIn: Int? = null): Tempo? {
        var best: Tempo? = null
        var bestDev = Double.MAX_VALUE
        val options = listOf(1 to 1, 1 to 2, 2 to 1).filter { (fixedStrideOut == null || it.first == fixedStrideOut) && (fixedStrideIn == null || it.second == fixedStrideIn) }
        for ((so, si) in options) {
            val lo = so * periodOut
            val li = si * periodIn
            val ratio = sqrt(lo / li)
            val ro = ratio
            val ri = 1.0 / ratio
            val dev = max(abs(ro - 1.0), abs(ri - 1.0))
            if (dev <= maxBend + 1e-9 && dev < bestDev - 1e-6) {
                bestDev = dev
                best = Tempo(so, si, sqrt(lo * li), ro, ri)
            }
        }
        return best
    }

    // ---------------------------------------------------------------------------------------------
    // key

    /** Smallest pitch shift (semitones) on one deck that makes the keys Camelot-compatible; ties prefer the outgoing deck. */
    internal fun bestShift(keyOut: MusicalKey, keyIn: MusicalKey, maxShift: Int): Pair<Boolean, Int>? {
        var best: Pair<Boolean, Int>? = null
        var bestCost = Int.MAX_VALUE
        for (mag in 1..maxShift) {
            for (s in intArrayOf(mag, -mag)) {
                val so = Camelot.distance(shift(keyOut, s), keyIn)
                if (so <= 1 && mag < bestCost) { best = true to s; bestCost = mag }
                val si = Camelot.distance(keyOut, shift(keyIn, s))
                if (si <= 1 && mag < bestCost) { best = false to s; bestCost = mag }
            }
            if (best != null) break
        }
        return best
    }

    private fun shift(k: MusicalKey, s: Int) = MusicalKey(((k.pitchClass + s) % 12 + 12) % 12, k.mode)

    // ---------------------------------------------------------------------------------------------
    // exit / entry

    internal class Pick(val timeMs: Long, val levelName: String, val viaOutro: Boolean)

    /**
     * Exit: the start of the outro if trusted, else the last phrase boundary that leaves room for the overlap; falls
     * back from phrase starts to downbeats to beats.
     */
    internal fun pickExit(c: TrackContext, endEff: Long, fullAfter: Double, minAfter: Double, before: Double, outroStart: Long?, preferAfter: Double = minAfter, maxTime: Long = Long.MAX_VALUE): Pick? {
        val levels = listOf("phrase" to c.phraseTimes, "downbeat" to c.downbeatTimes.takeIf { c.barsTrusted }, "beat" to c.beats)
        for ((name, all) in levels) {
            val arr = all?.filter { it <= maxTime }?.toLongArray()
            if (arr == null || arr.isEmpty()) continue
            if (outroStart != null) {
                // 1. the outro start itself (first grid point at or just after it) if it leaves a workable overlap
                val tol = (c.medianBeatMs * c.beatsPerBar * 0.25).toLong()
                arr.firstOrNull { it >= outroStart - tol && it >= before && endEff - it >= preferAfter }?.let { return Pick(it, name, true) }
                // 2. otherwise, around the start of the outro: the candidate that allows the longest overlap (up to the wanted one),
                // ties broken by closeness to the outro start. Looking one phrase earlier lets a short outro whose
                // next phrase start is too close to the end still get its full overlap.
                val window = (c.medianBeatMs * PHRASE_BEATS).toLong()
                var best: Long? = null
                var bestRoom = -1.0
                var bestDist = Long.MAX_VALUE
                for (cand in arr) {
                    if (cand < outroStart - window || cand < before) continue
                    val room = (endEff - cand).toDouble()
                    if (room < minAfter) continue
                    val usable = min(room, fullAfter)
                    val dist = abs(cand - outroStart)
                    if (usable > bestRoom + 1.0 || (abs(usable - bestRoom) <= 1.0 && dist < bestDist)) {
                        best = cand; bestRoom = usable; bestDist = dist
                    }
                }
                if (best != null) return Pick(best, name, true)
            }
            arr.lastOrNull { it >= before && endEff - it >= fullAfter }?.let { return Pick(it, name, false) }
            arr.lastOrNull { it >= before && endEff - it >= minAfter }?.let { return Pick(it, name, false) }
        }
        return null
    }

    /** Entry: the first phrase start (else downbeat, else beat) at or after the first audible sound. */
    internal fun pickEntry(c: TrackContext, firstAudible: Long, minAfter: Double, afterTime: Long = Long.MIN_VALUE): Pick? {
        val tol = (c.medianBeatMs * 0.5).toLong()
        val levels = listOf("phrase" to c.phraseTimes, "downbeat" to c.downbeatTimes.takeIf { c.barsTrusted }, "beat" to c.beats)
        for ((name, all) in levels) {
            val arr = all ?: continue
            if (arr.isEmpty()) continue
            val cand = arr.firstOrNull { it > afterTime && it >= firstAudible - tol && c.durationMs - it >= minAfter }
            if (cand != null) return Pick(cand, name, false)
        }
        return null
    }

    // ---------------------------------------------------------------------------------------------
    // lane builders

    private fun equalPowerOut(overlapMs: Long, gain: Double, dipRampMs: Long): ParamCurve {
        val keys = ArrayList<Keyframe>()
        if (dipRampMs > 0) {
            keys += Keyframe(-dipRampMs, 1f)
            keys += Keyframe(0, gain.toFloat(), Ease.SMOOTHSTEP)
        } else keys += Keyframe(0, gain.toFloat())
        for (k in 1..FADE_STEPS) {
            val x = k.toDouble() / FADE_STEPS
            keys += Keyframe((overlapMs * x).roundToLong(), (gain * cos(x * Math.PI / 2)).toFloat().let { if (k == FADE_STEPS) 0f else it })
        }
        return ParamCurve(keys)
    }

    private fun equalPowerIn(overlapMs: Long, gain: Double): ParamCurve {
        val keys = ArrayList<Keyframe>()
        keys += Keyframe(0, 0f)
        for (k in 1..FADE_STEPS) {
            val x = k.toDouble() / FADE_STEPS
            // level match glides from the matched gain to native over the second half
            val g = if (x <= 0.5) gain else gain.pow(1.0 - (x - 0.5) / 0.5)
            keys += Keyframe((overlapMs * x).roundToLong(), (g * sin(x * Math.PI / 2)).toFloat())
        }
        return ParamCurve(keys)
    }

    // ---------------------------------------------------------------------------------------------
    // fallbacks

    /** Equal-power crossfade at the end of the outgoing file. Needs no musical fact, only durations. */
    private fun simplePlan(out: TrackContext?, inc: TrackContext?, fromId: String, toId: String, settings: DjSettings, reason0: String, earliest: Long = 0L): TransitionPlan {
        val endOut = out?.audibleEndMs ?: 0L
        val entry = if (inc != null && inc.firstAudibleMs > 100L) inc.firstAudibleMs else 0L
        var fade = settings.fallbackCrossfadeMs
        if (out != null && endOut > 0) fade = min(fade, endOut)
        if (inc != null && inc.durationMs > 0) fade = min(fade, max(0L, inc.durationMs - entry))
        fade = fade.coerceAtLeast(0L)
        var exit = (endOut - fade).coerceAtLeast(0L)
        var reason = reason0
        if (earliest > exit && endOut > 0) {
            // the caller has already played past the natural start of the fade: fade as much as is left
            exit = min(earliest, endOut)
            fade = min(fade, (endOut - exit).coerceAtLeast(0L))
            reason += " (earliest allowed exit $earliest ms)"
        }
        return TransitionPlan(
            fromId = fromId, toId = toId, kind = PlanKind.SIMPLE_CROSSFADE,
            exitPointMs = exit, entryPointMs = entry, overlapMs = fade,
            outgoing = DeckPlan(volume = fadeCurve(fade, out = true)),
            incoming = DeckPlan(volume = fadeCurve(fade, out = false)),
            confidence = 1f, reason = "simple crossfade ($fade ms): $reason", mixBpm = null,
        )
    }

    private fun fadeCurve(fadeMs: Long, out: Boolean): ParamCurve {
        if (fadeMs <= 0L) return ParamCurve(if (out) listOf(Keyframe(0, 0f)) else listOf(Keyframe(0, 1f)))
        val keys = ArrayList<Keyframe>()
        for (k in 0..FADE_STEPS) {
            val x = k.toDouble() / FADE_STEPS
            val v = if (out) cos(x * Math.PI / 2) else sin(x * Math.PI / 2)
            keys += Keyframe((fadeMs * x).roundToLong(), if (out && k == FADE_STEPS) 0f else v.toFloat())
        }
        return ParamCurve(keys)
    }

    private fun lastResort(fromId: String, toId: String, reason: String) = TransitionPlan(
        fromId = fromId, toId = toId, kind = PlanKind.SIMPLE_CROSSFADE, exitPointMs = 0L, entryPointMs = 0L, overlapMs = 0L,
        outgoing = DeckPlan(volume = ParamCurve.constant(0f)), incoming = DeckPlan(), confidence = 0f, reason = reason,
    )

    private fun fmt(f: Float) = String.format(java.util.Locale.ROOT, "%.2f", f)
    private fun fmt1(d: Double) = String.format(java.util.Locale.ROOT, "%.1f", d)
    private fun fmt3(d: Double) = String.format(java.util.Locale.ROOT, "%.3f", d)
    private fun signed(i: Int) = if (i > 0) "+$i" else "$i"

    companion object {
        private const val MARGIN_MS = 400.0
        private const val FADE_STEPS = 24
        private const val IRREGULAR_RMS = 0.12
        private const val BASS_WORTH = 0.15f
        private const val RATE_EPS = 2e-5
        private const val UNIT_EPS = 0.03
        private const val MAX_DRIFT_MS = 8.0
        private const val MAX_POINT_RETRIES = 6
        private const val BEAT_MODE_MAX_BARS = 4
        private const val PHRASE_BEATS = 16
        private const val CLASH_MAX_MS = 9000.0
    }
}
