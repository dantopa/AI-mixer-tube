package org.simpmusic.dj.planner

import org.simpmusic.dj.model.Camelot
import org.simpmusic.dj.model.DeckPlan
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
        try {
            planUnsafe(from, to, sanitise(settings))
        } catch (e: Exception) {
            try {
                simplePlan(from?.let { TrackContext(it) }, to?.let { TrackContext(it) }, from?.videoId.orEmpty(), to?.videoId.orEmpty(), sanitise(settings), "planner error (${e.javaClass.simpleName}): ${e.message}")
            } catch (e2: Exception) {
                lastResort(from?.videoId.orEmpty(), to?.videoId.orEmpty(), "planner error twice: ${e2.message}")
            }
        }

    // ---------------------------------------------------------------------------------------------

    private fun sanitise(s: DjSettings): DjSettings = s.copy(
        overlapBars = s.overlapBars.coerceIn(1, 64),
        maxTempoBend = if (s.maxTempoBend.isFinite()) s.maxTempoBend.coerceIn(0f, 0.5f) else 0.08f,
        maxPitchShift = s.maxPitchShift.coerceIn(0, 6),
        minConfidence = if (s.minConfidence.isFinite()) s.minConfidence.coerceIn(0f, 1f) else 0.5f,
        fallbackCrossfadeMs = s.fallbackCrossfadeMs.coerceIn(0L, 60_000L),
    )

    private fun planUnsafe(from: TrackAnalysis?, to: TrackAnalysis?, settings: DjSettings): TransitionPlan {
        val fromId = from?.videoId.orEmpty()
        val toId = to?.videoId.orEmpty()
        if (from == null || to == null) {
            return simplePlan(from?.let { TrackContext(it) }, to?.let { TrackContext(it) }, fromId, toId, settings, "missing analysis (${if (from == null) "outgoing" else "incoming"} track)")
        }
        val out = TrackContext(from)
        val inc = TrackContext(to)

        // ---- gating on what a beat-matched mix needs: a trusted beat grid. The bpm field is informational only ----
        val missing = ArrayList<String>()
        if (out.beatsConf < Trust.BEATS) missing += "outgoing beat grid (${fmt(out.beatsConf)} < ${Trust.BEATS})"
        if (inc.beatsConf < Trust.BEATS) missing += "incoming beat grid (${fmt(inc.beatsConf)} < ${Trust.BEATS})"
        if (out.medianBeatMs <= 0.0 || inc.medianBeatMs <= 0.0) missing += "unusable beat spacing"
        if (out.durationMs < 4000 || inc.durationMs < 4000) missing += "track too short"
        if (missing.isNotEmpty()) {
            return simplePlan(out, inc, fromId, toId, settings, "not beat-matchable: " + missing.joinToString("; "))
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

        // The tempo that matters is the LOCAL one around the exit and entry points. Start from the tempo of the region
        // where each mix happens, choose the points, then re-measure on the windows that actually overlap (twice at most).
        val margin = MARGIN_MS
        val roughOut = out.medianIbiIn(out.audibleEndMs - 60_000L, out.audibleEndMs).takeIf { it > 0 } ?: out.medianBeatMs
        val roughIn = inc.medianIbiIn(inc.firstAudibleMs, inc.firstAudibleMs + 60_000L).takeIf { it > 0 } ?: inc.medianBeatMs
        var tempo = chooseTempo(roughOut, roughIn, settings.maxTempoBend.toDouble())
            ?: return incompatibleTempo(out, inc, fromId, toId, settings, conf)
        val desiredUnits = if (barsMode) settings.overlapBars else min(settings.overlapBars, BEAT_MODE_MAX_BARS) * out.beatsPerBar
        val minUnits = if (barsMode) 1 else 4
        val softFloor = if (barsMode) 2 else 8
        val outroStart = out.outro()?.range?.startMs

        var loc: Located
        var pass = 0
        while (true) {
            loc = locate(out, inc, tempo, barsMode, desiredUnits, minUnits, softFloor, margin, outroStart, settings)
            loc.fail?.let { return simplePlan(out, inc, fromId, toId, settings, it) }
            val t2 = loc.tempo ?: return incompatibleTempo(out, inc, fromId, toId, settings, conf)
            pass++
            val settled = abs(t2.rateOut / tempo.rateOut - 1.0) < 0.02 && abs(t2.rateIn / tempo.rateIn - 1.0) < 0.02
            tempo = t2
            if (settled || pass >= 2) break
        }
        val tempo2 = tempo
        val exitPick = loc.exitPick!!
        val entryPick = loc.entryPick!!
        val localOut = loc.localOut!!
        val localIn = loc.localIn!!
        val iOut = loc.iOut
        val iIn = loc.iIn
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

        // ---- key ----
        val keyOut = from.key?.takeIf { it.confidence >= Trust.KEY }
        val keyIn = to.key?.takeIf { it.confidence >= Trust.KEY }
        var shiftOut = 0
        var shiftIn = 0
        var clash = false
        var keyNote = "keys unknown"
        if (keyOut != null && keyIn != null) {
            val d = Camelot.distance(keyOut.value, keyIn.value)
            keyNote = "${keyOut.value.camelot()}->${keyIn.value.camelot()}"
            if (d <= 1) {
                keyNote += " compatible"
            } else {
                conf = min(conf, min(keyOut.confidence, keyIn.confidence))
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
            return simplePlan(out, inc, fromId, toId, settings, "not enough audio for a ${minUnits}-${if (barsMode) "bar" else "beat"} overlap (room out ${availOut / 1000.0}s, in ${availIn / 1000.0}s)")
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
            return simplePlan(out, inc, fromId, toId, settings, "tempo drifts: beats of the two tracks slide ${fmt1(drift)} ms apart even over a ${minUnits}-${if (barsMode) "bar" else "beat"} overlap")
        }
        if (units < unitsBeforeDrift) notes += "overlap shortened from $unitsBeforeDrift to $units because the local tempo drifts"
        val overlapMs = (units * unit).roundToLong()

        if (conf < settings.minConfidence) {
            return simplePlan(out, inc, fromId, toId, settings, "confidence ${fmt(conf)} below minimum ${fmt(settings.minConfidence)}: " + notes.joinToString())
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
            return simplePlan(out, inc, fromId, toId, settings, "not enough audio before the exit point for the tempo pre-roll")
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
        val reason = buildString {
            append("beat-matched ").append(fmt1(out.gridBpm)).append(" -> ").append(fmt1(inc.gridBpm)).append(" bpm at ").append(fmt1(mixBpm.toDouble()))
            append(" (out x").append(fmt3(rateOut)).append(", in x").append(fmt3(rateIn))
            if (tempo2.strideOut != 1 || tempo2.strideIn != 1) append(", half/double-time lattice ${tempo2.strideOut}:${tempo2.strideIn}")
            append("); exit ").append(exitMs).append(" ms (").append(exitPick.levelName).append(if (exitPick.viaOutro) ", outro" else "")
            append("), entry ").append(entryMs).append(" ms (").append(entryPick.levelName).append("); overlap ")
            append(units).append(if (barsMode) " bars" else " beats").append(" = ").append(overlapMs).append(" ms")
            if (units < desiredUnits) append(" (clamped from $desiredUnits)")
            append("; ").append(keyNote)
            append("; bass swap ").append(if (bassSwap) "on" else if (!settings.bassSwap) "off (setting)" else "off (no bass to swap)")
            if (gainOut < 1.0 || gainIn < 1.0) append("; gain out ${fmt(gainOut.toFloat())} in ${fmt(gainIn.toFloat())}")
            if (notes.isNotEmpty()) append("; ").append(notes.joinToString())
            append("; confidence ").append(fmt(conf))
        }
        return TransitionPlan(
            fromId = fromId, toId = toId, kind = PlanKind.BEAT_MATCHED,
            exitPointMs = exitMs, entryPointMs = entryMs, overlapMs = overlapMs,
            outgoing = DeckPlan(rate = outRate, pitchSemitones = outPitch, volume = outVolume, lowCutHz = outLow, highCutHz = outHigh),
            incoming = DeckPlan(rate = inRate, pitchSemitones = inPitch, volume = inVolume, lowCutHz = inLow, highCutHz = inHigh),
            confidence = conf.coerceIn(0f, 1f), reason = reason, mixBpm = mixBpm,
        )
    }


    internal class Located(
        val fail: String? = null, val tempo: Tempo? = null, val exitPick: Pick? = null, val entryPick: Pick? = null,
        val localOut: LocalGrid? = null, val localIn: LocalGrid? = null, val iOut: Int = 0, val iIn: Int = 0,
    )

    /** Picks exit and entry with the given tempo estimate, then measures the LOCAL tempo of both overlap windows. */
    private fun locate(
        out: TrackContext, inc: TrackContext, tempo: Tempo, barsMode: Boolean, desiredUnits: Int, minUnits: Int,
        softFloor: Int, margin: Double, outroStart: Long?, settings: DjSettings,
    ): Located {
        val lattice = tempo.wallLatticeMs
        val unitWall = if (barsMode) out.beatsPerBar * lattice else lattice
        val rampMin = 4 * out.medianBeatMs + 250.0
        val fullAfterOut = desiredUnits * unitWall * tempo.rateOut + margin
        val minAfterOut = minUnits * unitWall * tempo.rateOut + margin
        val preferAfterOut = softFloor * unitWall * tempo.rateOut + margin
        // Try the best exit/entry first; if a window turns out irregular, step to the previous exit / next entry candidate.
        var maxExit = Long.MAX_VALUE
        var afterEntry = Long.MIN_VALUE
        var lastFail = "no usable exit/entry point"
        val minAfterIn = minUnits * unitWall * tempo.rateIn + margin
        val rough = desiredUnits * unitWall
        val ibiOut = tempo.wallLatticeMs * tempo.rateOut / tempo.strideOut
        val ibiIn = tempo.wallLatticeMs * tempo.rateIn / tempo.strideIn
        val spanOut = max(6, ceil(rough * tempo.rateOut / ibiOut).toInt() + 2)
        val spanIn = max(6, ceil(rough * tempo.rateIn / ibiIn).toInt() + 2)
        for (attempt in 0 until MAX_POINT_RETRIES) {
            val exitPick = pickExit(out, out.audibleEndMs, fullAfterOut, minAfterOut, rampMin, outroStart, preferAfterOut, maxExit)
                ?: return Located(fail = if (attempt == 0) "no exit point with enough room before the end of the outgoing track" else lastFail)
            val entryPick = pickEntry(inc, inc.firstAudibleMs, minAfterIn, afterEntry)
                ?: return Located(fail = if (attempt == 0) "no entry point with enough audio in the incoming track" else lastFail)
            val iOut = out.nearestBeatIndex(exitPick.timeMs)
            val iIn = inc.nearestBeatIndex(entryPick.timeMs)
            val localOut = out.local(iOut - 4, iOut + spanOut)
            val localIn = inc.local(iIn, iIn + spanIn + 4)
            if (localOut == null) { lastFail = "too few beats around the exit point to measure the local tempo"; maxExit = exitPick.timeMs - 1; continue }
            if (localIn == null) { lastFail = "too few beats around the entry point to measure the local tempo"; afterEntry = entryPick.timeMs; continue }
            if (localOut.noiseRms > IRREGULAR_RMS * localOut.medianIbi) {
                lastFail = "outgoing beat grid is irregular around the mix (rms ${fmt1(localOut.noiseRms)} ms of a ${fmt1(localOut.medianIbi)} ms beat)"
                maxExit = exitPick.timeMs - 1; continue
            }
            if (localIn.noiseRms > IRREGULAR_RMS * localIn.medianIbi) {
                lastFail = "incoming beat grid is irregular around the mix (rms ${fmt1(localIn.noiseRms)} ms of a ${fmt1(localIn.medianIbi)} ms beat)"
                afterEntry = entryPick.timeMs; continue
            }
            val endOut = min(localOut.i0 + localOut.n - 1, iOut + spanOut)
            val endIn = min(localIn.i0 + localIn.n - 1, iIn + spanIn)
            val periodOut = localOut.meanPeriod(iOut, endOut)
            val periodIn = localIn.meanPeriod(iIn, endIn)
            if (periodOut <= 0.0 || periodIn <= 0.0) return Located(fail = "beat grid runs backwards around the mix")
            val t2 = chooseTempo(periodOut, periodIn, settings.maxTempoBend.toDouble())
            return Located(tempo = t2, exitPick = exitPick, entryPick = entryPick, localOut = localOut, localIn = localIn, iOut = iOut, iIn = iIn)
        }
        return Located(fail = lastFail)
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

    private fun incompatibleTempo(out: TrackContext, inc: TrackContext, fromId: String, toId: String, settings: DjSettings, conf: Float): TransitionPlan {
        val why = "tempos ${fmt1(out.gridBpm)} -> ${fmt1(inc.gridBpm)} bpm are beyond the +-${(settings.maxTempoBend * 100).toInt()}% bend"
        val gridsOk = out.barsTrusted && inc.barsTrusted && conf >= settings.minConfidence
        if (gridsOk) {
            cutPlan(out, inc, fromId, toId, settings, conf, why)?.let { return it }
        }
        return simplePlan(out, inc, fromId, toId, settings, "$why; no trusted downbeats for a cut")
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

    private fun cutPlan(out: TrackContext, inc: TrackContext, fromId: String, toId: String, settings: DjSettings, conf: Float, why: String): TransitionPlan? {
        val bar = out.beatsPerBar * out.medianBeatMs
        val exit = pickExit(out, out.audibleEndMs, bar + MARGIN_MS, MARGIN_MS + out.medianBeatMs, 0.0, out.outro()?.range?.startMs) ?: return null
        val entry = pickEntry(inc, inc.firstAudibleMs, MARGIN_MS) ?: return null
        if (exit.levelName == "beat" || entry.levelName == "beat") return null // a cut only on downbeats
        val outVol = ParamCurve(listOf(Keyframe(-CUT_FADE_OUT_MS, 1f), Keyframe(0, 0f, Ease.SMOOTHSTEP)))
        val inVol = ParamCurve(listOf(Keyframe(0, 0f), Keyframe(CUT_FADE_IN_MS, 1f, Ease.SMOOTHSTEP)))
        return TransitionPlan(
            fromId = fromId, toId = toId, kind = PlanKind.CUT,
            exitPointMs = exit.timeMs, entryPointMs = entry.timeMs, overlapMs = 0L,
            outgoing = DeckPlan(volume = outVol), incoming = DeckPlan(volume = inVol),
            confidence = conf.coerceIn(0f, 1f),
            reason = "cut on downbeats: $why; exit ${exit.timeMs} ms (${exit.levelName}), entry ${entry.timeMs} ms (${entry.levelName})",
            mixBpm = null,
        )
    }

    /** Equal-power crossfade at the end of the outgoing file. Needs no musical fact, only durations. */
    private fun simplePlan(out: TrackContext?, inc: TrackContext?, fromId: String, toId: String, settings: DjSettings, reason: String): TransitionPlan {
        val endOut = out?.audibleEndMs ?: 0L
        val entry = if (inc != null && inc.firstAudibleMs > 100L) inc.firstAudibleMs else 0L
        var fade = settings.fallbackCrossfadeMs
        if (out != null && endOut > 0) fade = min(fade, endOut)
        if (inc != null && inc.durationMs > 0) fade = min(fade, max(0L, inc.durationMs - entry))
        fade = fade.coerceAtLeast(0L)
        val exit = (endOut - fade).coerceAtLeast(0L)
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
        private const val CUT_FADE_OUT_MS = 10L
        private const val CUT_FADE_IN_MS = 1L
    }
}
