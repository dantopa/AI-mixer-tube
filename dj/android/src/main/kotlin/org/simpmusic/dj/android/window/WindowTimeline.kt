package org.simpmusic.dj.android.window

import org.simpmusic.dj.model.DeckPlan
import org.simpmusic.dj.model.ParamCurve
import org.simpmusic.dj.model.PlanKind
import org.simpmusic.dj.model.TransitionPlan
import kotlin.math.abs

/** All the timing constants of the window design in one place (ms unless noted). */
object WindowTuning {
    /**
     * Plain, unprocessed outgoing audio at the start of the window. The window player is started here
     * silently and phase-locked to the live outgoing player before it is made audible, so this must
     * cover: start latency + at least one lock attempt (settle + measure + seek) + the hand-off fade.
     */
    const val LEAD_IN_MS = 2500L

    /** First moment a lock measurement is trusted after the window started (player warm-up). */
    const val LOCK_MEASURE_FROM_MS = 350L

    /** Window time at which the live outgoing -> window cross-fade starts. Must be < LEAD_IN_MS - XFADE_MS. */
    const val XFADE_OUT_AT_MS = 2000L

    /** Length of both hand-off fades. Linear, gain sum 1: the two paths carry identical (correlated) audio. */
    const val XFADE_MS = 100L

    /** After the lanes settle: silent live-incoming lock budget before the hand-off is forced. */
    const val LOCK_IN_DEADLINE_MS = 3000L

    /** Window content after the lanes settle (must exceed LOCK_IN_DEADLINE_MS + XFADE_MS + slack). */
    const val TAIL_MS = 6000L

    /** Both decks are considered phase-locked when the measured offset is within this (ms). */
    const val LOCK_TOLERANCE_MS = 6.0

    /** Give up (abort before commit / force the hand-off after) beyond this offset (ms). */
    const val LOCK_GIVE_UP_MS = 35.0

    /** Loudness-match volume of the incoming track is ramped to 1.0 over this long after the hand-off. */
    const val SETTLE_RAMP_MS = 1500L

    /** Margin decoded around the source ranges the renderer needs. */
    const val DECODE_MARGIN_MS = 750L
}

sealed interface Eligibility {
    data object Ok : Eligibility

    data class Rejected(val reason: String) : Eligibility
}

/**
 * Maps between the three time axes of a rendered transition window:
 *
 *  - **plan time** `t`: the plan's own axis, 0 = T0 (the incoming deck starts), may be negative;
 *  - **window time** `w`: milliseconds since the first sample of the rendered window, `w = t - startRelMs`;
 *  - **source time**: position on the outgoing / incoming track, which advances by `rate(t)` per ms of
 *    plan time (the tempo lanes), so it is the integral of the rate lane.
 *
 * Everything the controller and the UI position mapping need is derived from here, so the on-device
 * hand-offs and the renderer agree on where every sample sits.
 */
class WindowTimeline private constructor(
    val plan: TransitionPlan,
    /** Plan time of window sample 0. */
    val startRelMs: Long,
    /** Plan time from which every lane is constant. */
    val settledRelMs: Long,
    /** Plan time of the window's last sample. */
    val endRelMs: Long,
) {
    private val outCum: DoubleArray
    private val inCum: DoubleArray

    init {
        val n = ((endRelMs - startRelMs) / STEP_MS).toInt() + 2
        outCum = cumulative(plan.outgoing.rate, n)
        inCum = cumulative(plan.incoming.rate, n)
    }

    val windowMs: Long get() = endRelMs - startRelMs

    /** Window time at which the live outgoing -> window hand-off fade starts. */
    val xfadeOutWindowMs: Long get() = WindowTuning.XFADE_OUT_AT_MS

    /** Window time at which lanes are constant and the live incoming deck starts its silent lock. */
    val lockInWindowMs: Long get() = settledRelMs - startRelMs

    val xfadeInWindowMs: Long get() = lockInWindowMs + WindowTuning.LOCK_IN_DEADLINE_MS

    fun planTimeOfWindow(windowMs: Double): Double = windowMs + startRelMs

    fun windowTimeOfPlan(planMs: Double): Double = planMs - startRelMs

    /** Outgoing SOURCE position (ms) at plan time [t]. */
    fun outgoingSourceAt(t: Double): Double = plan.exitPointMs + sample(outCum, plan.outgoing.rate, t)

    /** Incoming SOURCE position (ms) at plan time [t] (only meaningful for t >= 0). */
    fun incomingSourceAt(t: Double): Double = plan.entryPointMs + sample(inCum, plan.incoming.rate, t)

    /** Outgoing source position that window sample 0 shows: where the live outgoing deck must be when it starts. */
    val outgoingSourceAtWindowStart: Double get() = outgoingSourceAt(startRelMs.toDouble())

    /** Window time -> position on the outgoing track (used until the incoming becomes the current track). */
    fun outgoingSourceOfWindow(windowMs: Double): Double = outgoingSourceAt(planTimeOfWindow(windowMs))

    fun incomingSourceOfWindow(windowMs: Double): Double = incomingSourceAt(planTimeOfWindow(windowMs))

    /** Inverse of [outgoingSourceOfWindow]. The mapping is monotone (rates are > 0), so bisection is exact enough. */
    fun windowOfOutgoingSource(sourceMs: Double): Double = invert(sourceMs, ::outgoingSourceOfWindow)

    /** Source range of the outgoing track the renderer needs, margin included. */
    fun outgoingDecodeRange(): LongRange {
        val from = outgoingSourceAtWindowStart - WindowTuning.DECODE_MARGIN_MS
        val to = outgoingSourceAt(plan.overlapMs.toDouble()) + WindowTuning.DECODE_MARGIN_MS
        return from.toLong().coerceAtLeast(0L)..to.toLong()
    }

    /** Source range of the incoming track the renderer needs (only the audible part, from the entry point). */
    fun incomingDecodeRange(): LongRange {
        val from = plan.entryPointMs - WindowTuning.DECODE_MARGIN_MS
        val to = incomingSourceAt(endRelMs.toDouble()) + WindowTuning.DECODE_MARGIN_MS
        return from.coerceAtLeast(0L)..to.toLong()
    }

    private fun invert(target: Double, f: (Double) -> Double): Double {
        var lo = 0.0
        var hi = windowMs.toDouble()
        if (target <= f(lo)) return lo
        if (target >= f(hi)) return hi
        repeat(40) {
            val mid = (lo + hi) / 2
            if (f(mid) < target) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    private fun cumulative(rate: ParamCurve, n: Int): DoubleArray {
        // cum[i] = integral of rate over plan time [0, startRel + i*STEP] (trapezoids; negative for t < 0).
        val out = DoubleArray(n)
        val zeroIdx = ((-startRelMs).toDouble() / STEP_MS)
        val iz = zeroIdx.toInt().coerceIn(0, n - 1)
        // integrate forward from t=0 (index of first step at/after 0) and backward from it
        fun r(t: Double) = rate.valueAt(t).toDouble()
        val t0 = 0.0
        // value at grid point iz is the integral from 0 to t_iz
        val tIz = startRelMs + iz * STEP_MS.toDouble()
        out[iz] = integrate(::r, t0, tIz)
        for (i in iz + 1 until n) {
            val a = startRelMs + (i - 1) * STEP_MS.toDouble()
            out[i] = out[i - 1] + 0.5 * (r(a) + r(a + STEP_MS)) * STEP_MS
        }
        for (i in iz - 1 downTo 0) {
            val a = startRelMs + i * STEP_MS.toDouble()
            out[i] = out[i + 1] - 0.5 * (r(a) + r(a + STEP_MS)) * STEP_MS
        }
        return out
    }

    private fun integrate(f: (Double) -> Double, a: Double, b: Double): Double {
        if (a == b) return 0.0
        val steps = (abs(b - a) / 2.0).toInt().coerceAtLeast(1)
        val h = (b - a) / steps
        var acc = 0.0
        for (i in 0 until steps) acc += 0.5 * (f(a + i * h) + f(a + (i + 1) * h)) * h
        return acc
    }

    private fun sample(cum: DoubleArray, rate: ParamCurve, t: Double): Double {
        val x = (t - startRelMs) / STEP_MS
        if (x <= 0) return cum[0] + (t - startRelMs) * rate.valueAt(startRelMs.toDouble())
        val i = x.toInt()
        if (i >= cum.size - 1) {
            val lastT = startRelMs + (cum.size - 1) * STEP_MS.toDouble()
            return cum[cum.size - 1] + (t - lastT) * rate.valueAt(lastT)
        }
        val f = x - i
        return cum[i] + (cum[i + 1] - cum[i]) * f
    }

    companion object {
        private const val STEP_MS = 10L

        /** Builds the timeline for an eligible plan (call [check] first). */
        fun build(plan: TransitionPlan): WindowTimeline {
            val settled = maxOf(plan.overlapMs, laneEnd(plan.outgoing), laneEnd(plan.incoming))
            val start = plan.preRollMs - WindowTuning.LEAD_IN_MS
            val end = settled + WindowTuning.TAIL_MS
            return WindowTimeline(plan, start, settled, end)
        }

        /**
         * A plan can only be executed as a window when the hand-offs land on plain audio: at the window
         * start the outgoing lane is the identity (the live player already plays exactly that), and once
         * the lanes settle the incoming deck is at rate 1.0, no pitch shift and no filter (so the live
         * player, which cannot do those, can take over seamlessly). Anything else falls back to the
         * existing crossfade.
         */
        fun check(plan: TransitionPlan): Eligibility {
            if (plan.kind != PlanKind.BEAT_MATCHED && plan.kind != PlanKind.CUT) {
                return Eligibility.Rejected("plan kind ${plan.kind}")
            }
            if (plan.overlapMs <= 0) return Eligibility.Rejected("no overlap")
            val settled = maxOf(plan.overlapMs, laneEnd(plan.outgoing), laneEnd(plan.incoming))
            val start = plan.preRollMs - WindowTuning.LEAD_IN_MS
            identity(plan.outgoing, start.toDouble())?.let { return Eligibility.Rejected("outgoing not plain at window start: $it") }
            val inc = plan.incoming
            val t = settled.toDouble()
            if (abs(inc.rate.valueAt(t) - 1f) > 0.003f) return Eligibility.Rejected("incoming rate ${inc.rate.valueAt(t)} != 1 when settled")
            if (abs(inc.pitchSemitones.valueAt(t)) > 0.02f) return Eligibility.Rejected("incoming pitch shifted when settled")
            if (inc.lowCutHz.valueAt(t) > 25f || inc.highCutHz.valueAt(t) < 19_000f) return Eligibility.Rejected("incoming filtered when settled")
            if (plan.outgoing.volume.valueAt(t) > 0.01f) return Eligibility.Rejected("outgoing still audible when settled")
            val g = inc.volume.valueAt(t)
            if (g < 0.05f || g > 4f) return Eligibility.Rejected("incoming end gain $g out of range")
            val minEntry = 0L
            if (plan.entryPointMs < minEntry) return Eligibility.Rejected("negative entry point")
            return Eligibility.Ok
        }

        private fun identity(d: DeckPlan, t: Double): String? {
            if (abs(d.rate.valueAt(t) - 1f) > 0.003f) return "rate"
            if (abs(d.pitchSemitones.valueAt(t)) > 0.02f) return "pitch"
            if (abs(d.volume.valueAt(t) - 1f) > 0.02f) return "volume"
            if (d.lowCutHz.valueAt(t) > 25f) return "lowCut"
            if (d.highCutHz.valueAt(t) < 19_000f) return "highCut"
            return null
        }

        private fun laneEnd(d: DeckPlan): Long =
            maxOf(d.rate.endMs, d.pitchSemitones.endMs, d.volume.endMs, d.lowCutHz.endMs, d.highCutHz.endMs)
    }
}
