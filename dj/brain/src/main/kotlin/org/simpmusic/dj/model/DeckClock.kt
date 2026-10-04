package org.simpmusic.dj.model

import kotlin.math.floor

/**
 * Reference implementation of the deck timeline: the SOURCE position of a deck as the integral of its
 * [DeckPlan.rate] lane over WALL-CLOCK time, anchored so that the source position is [anchorSourceMs] at wall
 * time 0 (= T0 of the [TransitionPlan]).
 *
 *     sourceMs(t) = anchorSourceMs + integral_0^t rate(s) ds          (t may be negative: pre-roll)
 *
 * The offline renderer and the on-device engine must agree with this. It is tabulated at a 1 ms step
 * (Simpson per step) over [fromMs, toMs]; queries outside are extrapolated with the boundary rate.
 */
class DeckClock(
    private val rate: ParamCurve,
    val anchorSourceMs: Double,
    val fromMs: Long,
    val toMs: Long,
) {
    private val n: Int = (toMs - fromMs).coerceIn(1L, 20_000_000L).toInt()

    // cum[i] = integral of rate from fromMs to fromMs+i (ms of source), before re-anchoring at 0
    private val cum = DoubleArray(n + 1)
    private val zeroOffset: Double

    init {
        for (i in 0 until n) {
            val a = (fromMs + i).toDouble()
            val r0 = rate.valueAt(a).toDouble()
            val rm = rate.valueAt(a + 0.5).toDouble()
            val r1 = rate.valueAt(a + 1.0).toDouble()
            cum[i + 1] = cum[i] + (r0 + 4 * rm + r1) / 6.0
        }
        zeroOffset = rawAt(0.0)
    }

    private fun rawAt(tMs: Double): Double {
        val x = tMs - fromMs
        if (x <= 0.0) return cum[0] + x * rate.valueAt(fromMs.toDouble())
        if (x >= n) return cum[n] + (x - n) * rate.valueAt(toMs.toDouble())
        val i = floor(x).toInt()
        val f = x - i
        val r = rate.valueAt(fromMs + i + f * 0.5).toDouble()
        return cum[i] + f * r
    }

    /** Source position (ms) at wall time [tMs] relative to T0. */
    fun sourceAt(tMs: Double): Double = anchorSourceMs + rawAt(tMs) - zeroOffset

    /** Wall time (ms, relative to T0) at which the deck reaches [sourceMs]. Requires a strictly positive rate lane. */
    fun wallAt(sourceMs: Double): Double {
        val target = sourceMs - anchorSourceMs + zeroOffset
        if (target <= cum[0]) return fromMs + (target - cum[0]) / rate.valueAt(fromMs.toDouble())
        if (target >= cum[n]) return toMs + (target - cum[n]) / rate.valueAt(toMs.toDouble())
        var lo = 0
        var hi = n
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] <= target) lo = mid else hi = mid
        }
        val span = cum[hi] - cum[lo]
        val f = if (span <= 0.0) 0.0 else (target - cum[lo]) / span
        return fromMs + lo + f
    }

    companion object {
        /** Clock of the outgoing deck of [plan]. */
        fun outgoing(plan: TransitionPlan, horizonMs: Long = plan.overlapMs + 60_000L): DeckClock =
            DeckClock(plan.outgoing.rate, plan.exitPointMs.toDouble(), minOf(plan.preRollMs, -1L) - 2_000L, horizonMs)

        /** Clock of the incoming deck of [plan] (it only plays from t = 0). */
        fun incoming(plan: TransitionPlan, horizonMs: Long = plan.overlapMs + 60_000L): DeckClock =
            DeckClock(plan.incoming.rate, plan.entryPointMs.toDouble(), -1L, horizonMs)
    }
}
