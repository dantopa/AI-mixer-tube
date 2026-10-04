package org.simpmusic.dj.android.mixview

import org.simpmusic.dj.android.window.WindowTimeline
import org.simpmusic.dj.mixview.DjMixDeck
import org.simpmusic.dj.mixview.DjMixKind
import org.simpmusic.dj.mixview.DjMixPhase
import org.simpmusic.dj.mixview.DjMixViewData
import org.simpmusic.dj.model.ParamCurve
import org.simpmusic.dj.model.PlanKind
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.model.TransitionPlan
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Pure mapping from a [TransitionPlan] and the two analyses to the picture ([DjMixViewData]).
 *
 * Every deck is projected onto the plan's wall-clock axis (T0 = 0) with the same [WindowTimeline] the engine and
 * the renderer use: a source position advances by `rate(t)` per ms, so a beat that is 500 ms apart on the source
 * lands 476 ms apart on the axis when the deck runs 5% fast. That is what lets a viewer see whether the two beat
 * grids line up.
 */
object DjMixViewBuilder {
    const val STEP_MS = 100

    /** Always show at least this much of the outgoing deck before T0 so the lead-in has some context. */
    private const val MIN_BEFORE_T0_MS = 6_000L

    /** Show this long after the lanes settle (and at least [MIN_AFTER_T0_MS] after T0). */
    private const val AFTER_SETTLE_MS = 4_000L
    private const val MIN_AFTER_T0_MS = 8_000L

    /**
     * @param titles (outgoing title, incoming title)
     * @param nowPlanMs playhead in plan ms (0 = T0), or null while the window is not playing
     */
    fun build(
        plan: TransitionPlan,
        fromAnalysis: TrackAnalysis?,
        toAnalysis: TrackAnalysis?,
        titles: Pair<String, String>,
        nowPlanMs: Double? = null,
    ): DjMixViewData {
        val tl = WindowTimeline.build(plan)
        val settle = tl.settledRelMs
        val step = STEP_MS
        val start = floorDiv(min(tl.startRelMs, -MIN_BEFORE_T0_MS), step) * step
        val end = ceilDiv(max(settle + AFTER_SETTLE_MS, MIN_AFTER_T0_MS), step) * step
        val n = ((end - start) / step).toInt() + 1

        val outSource = { t: Double -> tl.outgoingSourceAt(t) }
        val inSource = { t: Double -> tl.incomingSourceAt(t) }

        val out = deck(
            title = titles.first, analysis = fromAnalysis, lanes = plan.outgoing, source = outSource,
            start = start, end = end, step = step, n = n, activeFrom = start,
        )
        val inc = deck(
            title = titles.second, analysis = toAnalysis, lanes = plan.incoming, source = inSource,
            start = start, end = end, step = step, n = n, activeFrom = 0L,
        )

        val kind = when (plan.kind) {
            PlanKind.BEAT_MATCHED -> DjMixKind.BEAT_MATCHED
            PlanKind.CUT -> DjMixKind.CUT
            PlanKind.ECHO_OUT -> DjMixKind.ECHO_OUT
            PlanKind.SIMPLE_CROSSFADE -> DjMixKind.SIMPLE_CROSSFADE
        }
        return DjMixViewData(
            kind = kind, startMs = start, endMs = end, stepMs = step,
            overlapEndMs = plan.overlapMs.coerceAtLeast(0L), settleMs = settle,
            nowMs = null, phase = DjMixPhase.READY, outgoing = out, incoming = inc,
            mixBpm = plan.mixBpm, confidence = plan.confidence, reason = plan.reason,
        ).withNow(nowPlanMs)
    }

    private fun deck(
        title: String,
        analysis: TrackAnalysis?,
        lanes: org.simpmusic.dj.model.DeckPlan,
        source: (Double) -> Double,
        start: Long,
        end: Long,
        step: Int,
        n: Int,
        activeFrom: Long,
    ): DjMixDeck {
        fun lane(c: ParamCurve) = List(n) { c.valueAt((start + it.toLong() * step).toDouble()) }

        val hop = analysis?.energyHopMs?.takeIf { it > 0 } ?: 0
        val energyRaw = analysis?.energy.orEmpty()
        val lowRaw = analysis?.lowBandEnergy.orEmpty()
        val hasEnergy = hop > 0 && energyRaw.isNotEmpty()

        val energy = FloatArray(n)
        val low = FloatArray(n)
        if (hasEnergy) {
            for (i in 0 until n) {
                val t = (start + i.toLong() * step).toDouble()
                if (t < activeFrom) continue
                val s0 = source(t - step / 2.0)
                val s1 = source(t + step / 2.0)
                energy[i] = averageOver(energyRaw, hop, s0, s1)
                low[i] = if (lowRaw.isEmpty()) 0f else averageOver(lowRaw, hop, s0, s1)
            }
        }

        // Beats: source ms -> plan ms through the (monotone) source(t) table.
        val grid = analysis?.let(org.simpmusic.dj.analysis.AnalysisRefiner::cached) // the grid the planner used
        val beatsSrc = grid?.beatTimesMs?.value.orEmpty()
        val beats = ArrayList<Float>()
        val downbeats = ArrayList<Float>()
        if (beatsSrc.isNotEmpty()) {
            val from = max(start, activeFrom)
            val inv = SourceInverse(from, end, source)
            val downSet = grid?.downbeatBeatIndices?.value.orEmpty().toHashSet()
            for ((i, b) in beatsSrc.withIndex()) {
                val t = inv.planTimeOf(b.toDouble()) ?: continue
                beats += t.toFloat()
                if (i in downSet) downbeats += t.toFloat()
            }
        }

        return DjMixDeck(
            title = title,
            bpm = analysis?.bpm?.value,
            key = analysis?.key?.value?.camelot(),
            hasEnergy = hasEnergy,
            energy = energy.asList(),
            lowEnergy = low.asList(),
            beatsMs = beats,
            downbeatsMs = downbeats,
            volume = lane(lanes.volume),
            lowCutHz = lane(lanes.lowCutHz),
            highCutHz = lane(lanes.highCutHz),
            rate = lane(lanes.rate),
            pitchSemitones = lane(lanes.pitchSemitones),
            activeFromMs = activeFrom,
        )
    }

    /** Mean of the curve over the source range [s0, s1] (ms); 0 outside the curve. */
    internal fun averageOver(curve: List<Float>, hopMs: Int, s0: Double, s1: Double): Float {
        val last = (curve.size - 1) * hopMs.toDouble()
        if (s1 < 0.0 || s0 > last) return 0f
        val a = max(0.0, s0) / hopMs
        val b = min(last, s1) / hopMs
        if (b - a <= 1.0) {
            val x = (a + b) / 2
            val i = floor(x).toInt().coerceIn(0, curve.size - 1)
            val j = min(i + 1, curve.size - 1)
            val f = (x - i).toFloat()
            return curve[i] + (curve[j] - curve[i]) * f
        }
        var sum = 0.0
        var cnt = 0
        for (k in ceil(a).toInt()..floor(b).toInt()) {
            sum += curve[k]
            cnt++
        }
        return if (cnt == 0) 0f else (sum / cnt).toFloat().coerceIn(0f, 1f)
    }

    /** Inverse of a monotone `source(t)` over a plan-time range, from a 10 ms table. */
    private class SourceInverse(val from: Long, val to: Long, source: (Double) -> Double) {
        private val grid = 10L
        private val count = ((to - from) / grid).toInt() + 2
        private val src = DoubleArray(count) { source((from + it * grid).toDouble()) }

        fun planTimeOf(sourceMs: Double): Double? {
            if (sourceMs < src[0] || sourceMs > src[count - 1]) return null
            var lo = 0
            var hi = count - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (src[mid] <= sourceMs) lo = mid else hi = mid
            }
            val span = src[hi] - src[lo]
            val f = if (span <= 0.0) 0.0 else (sourceMs - src[lo]) / span
            val t = from + (lo + f) * grid
            return if (t > to) null else t
        }
    }

    private fun floorDiv(a: Long, b: Int): Long = Math.floorDiv(a, b.toLong())

    private fun ceilDiv(a: Long, b: Int): Long = -Math.floorDiv(-a, b.toLong())
}
