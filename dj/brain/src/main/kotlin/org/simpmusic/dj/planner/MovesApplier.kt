package org.simpmusic.dj.planner

import org.simpmusic.dj.model.Ease
import org.simpmusic.dj.model.EchoOutSpec
import org.simpmusic.dj.model.FadeMove
import org.simpmusic.dj.model.FilterMove
import org.simpmusic.dj.model.Keyframe
import org.simpmusic.dj.model.MixMoves
import org.simpmusic.dj.model.ParamCurve
import org.simpmusic.dj.model.TransitionPlan
import org.simpmusic.dj.model.silentFromMs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Applies Claude's [MixMoves] to a beat-matched plan the planner has already built: the moves only reshape the lanes
 * (where the bass lanes cross, how the faders move, a filter sweep, an echo send) over the same points, rates and
 * overlap, so everything the planner measured (beat lock, tempo, harmony, voices) still holds. The loop is not here: it
 * changes what the outgoing plays and is planned before (see `DjTransitionPlanner.planLooped`).
 */
internal object MovesApplier {
    class Applied(val plan: TransitionPlan, val notes: List<String>)

    /**
     * @param beatWallMs one beat of the mix (wall clock)
     * @param beatsPerBar of the outgoing track
     */
    fun apply(plan: TransitionPlan, m: MixMoves, beatWallMs: Double, beatsPerBar: Int): Applied {
        if (m.copy(loopBars = 0).isDefault || plan.overlapMs <= 0L || beatWallMs <= 0.0) return Applied(plan, emptyList())
        val o = plan.overlapMs
        val bar = beatWallMs * beatsPerBar.coerceAtLeast(1)
        val notes = ArrayList<String>()
        var out = plan.outgoing
        var inc = plan.incoming
        var echo: EchoOutSpec? = plan.echoOut

        // ---- bass swap ----
        val swap = swapOf(out.lowCutHz, inc.lowCutHz)
        var swapStart = swap?.first ?: (o / 2 - min(bar.toLong(), o / 4) / 2)
        var swapEnd = swap?.second ?: (swapStart + min(bar.toLong(), o / 4))
        m.bassSwapBar?.let { b ->
            if (swap == null) {
                notes += "no bass swap to move (one side has no bass, or the setting is off)"
            } else {
                val width = swapEnd - swapStart
                val center = (b * bar).roundToLong().coerceIn(width / 2, max(width / 2, o - width / 2))
                val delta = center - (swapStart + swapEnd) / 2
                out = out.copy(lowCutHz = shift(out.lowCutHz, delta))
                inc = inc.copy(lowCutHz = shift(inc.lowCutHz, delta))
                // the clash lanes start the outgoing's high fade where the swap ends: it follows the swap
                if (out.highCutHz.keys.first().tMs == swapEnd) out = out.copy(highCutHz = shift(out.highCutHz, delta))
                swapStart += delta
                swapEnd += delta
                notes += "bass swap on bar %d (%.1f s into the overlap)".format(java.util.Locale.ROOT, b, center / 1000.0)
            }
        }

        // ---- faders ----
        if (m.fade != FadeMove.SMOOTH) {
            val gOut = out.volume.valueAt(0.0).toDouble()
            val pre = out.volume.keys.filter { it.tMs < 0 }
            // the incoming's level match: its gain at the middle of the original equal-power rise, and where it ends
            val gIn = (inc.volume.valueAt(o / 2.0) / sin(PI / 4)).toDouble().coerceIn(0.0, 1.5)
            val inEnd = inc.volume.valueAt(o.toDouble()).toDouble()
            val inAfter = inc.volume.keys.filter { it.tMs > o }
            val (riseEnd, holdUntil, fallEnd) =
                when (m.fade) {
                    FadeMove.HOLD_THEN_DROP -> Triple(o / 2, o - max(bar.toLong(), o / 4).coerceAtMost(o), o)
                    else -> Triple(o / 4, o / 4, o * 3 / 4) // EARLY_HANDOVER
                }
            val outKeys = ArrayList(pre)
            outKeys += Keyframe(0, gOut.toFloat())
            if (holdUntil > 0) outKeys += Keyframe(holdUntil, gOut.toFloat())
            for (k in 1..STEPS) {
                val x = k.toDouble() / STEPS
                outKeys += Keyframe(holdUntil + ((fallEnd - holdUntil) * x).roundToLong(), if (k == STEPS) 0f else (gOut * cos(x * PI / 2)).toFloat())
            }
            val inKeys = arrayListOf(Keyframe(0, 0f))
            for (k in 1..STEPS) {
                val x = k.toDouble() / STEPS
                inKeys += Keyframe((riseEnd * x).roundToLong(), (gIn * sin(x * PI / 2)).toFloat())
            }
            if (o > riseEnd) inKeys += Keyframe(o, inEnd.toFloat())
            inKeys += inAfter
            out = out.copy(volume = ParamCurve(dedupe(outKeys)))
            inc = inc.copy(volume = ParamCurve(dedupe(inKeys)))
            notes += when (m.fade) {
                FadeMove.HOLD_THEN_DROP -> "fade: the outgoing holds, then drops over the last %.1f s".format(java.util.Locale.ROOT, (o - holdUntil) / 1000.0)
                else -> "fade: early hand-over (the incoming is up by %.1f s, the outgoing gone by %.1f s)".format(java.util.Locale.ROOT, riseEnd / 1000.0, fallEnd / 1000.0)
            }
        }

        // ---- filter sweep ----
        when (m.filter) {
            FilterMove.NONE -> {}
            FilterMove.LOWPASS_OUT -> {
                out = out.copy(highCutHz = ParamCurve(listOf(Keyframe(0, 20_000f), Keyframe(o, LOWPASS_END_HZ, Ease.EXPONENTIAL))))
                notes += "filter: the outgoing sinks under a low-pass"
            }
            FilterMove.HIGHPASS_OUT -> {
                val keys = if (swap != null) {
                    listOf(Keyframe(swapStart, 20f), Keyframe(swapEnd, 250f, Ease.EXPONENTIAL), Keyframe(max(o, swapEnd + 1), HIGHPASS_OUT_END_HZ, Ease.EXPONENTIAL))
                } else listOf(Keyframe(0, 20f), Keyframe(o, HIGHPASS_OUT_END_HZ, Ease.EXPONENTIAL))
                out = out.copy(lowCutHz = ParamCurve(keys))
                notes += "filter: the outgoing washes out under a high-pass"
            }
            FilterMove.HIGHPASS_IN -> {
                val keys = if (swap != null) {
                    listOf(Keyframe(0, HIGHPASS_IN_START_HZ), Keyframe(max(1L, swapStart), 250f, Ease.EXPONENTIAL), Keyframe(max(2L, swapEnd), 20f, Ease.EXPONENTIAL))
                } else listOf(Keyframe(0, HIGHPASS_IN_START_HZ), Keyframe(max(1L, o / 2), 20f, Ease.EXPONENTIAL))
                inc = inc.copy(lowCutHz = ParamCurve(keys))
                notes += "filter: the incoming enters thin and opens up"
            }
        }

        // ---- echo out: the outgoing's last beat rings out over the incoming ----
        if (m.echoOut) {
            val s = out.volume.silentFromMs()?.takeIf { it != Long.MIN_VALUE } ?: o
            val from = max(0L, s - bar.toLong())
            val delay = DjTransitionPlanner.EchoOut.delayMs(beatWallMs).toFloat()
            val tail = (3 * bar).coerceIn(3_500.0, 8_000.0).toLong()
            val open = max(1L, (beatWallMs / 2).toLong())
            val close = max(1L, (beatWallMs / 4).toLong())
            val send = ParamCurve(
                dedupe(listOf(Keyframe(from, 0f), Keyframe(from + open, 1f, Ease.SMOOTHSTEP), Keyframe(max(from + open, s - close), 1f), Keyframe(max(from + open + 1, s), 0f, Ease.SMOOTHSTEP))),
            )
            val fadeTail = min(bar.toLong(), tail / 2)
            val wet = ParamCurve(listOf(Keyframe(from, ECHO_WET), Keyframe(s + tail - fadeTail, ECHO_WET), Keyframe(s + tail, 0f, Ease.SMOOTHSTEP)))
            echo = EchoOutSpec(delayMs = delay, feedback = ECHO_FEEDBACK, send = send, wet = wet, tailMs = s + tail)
            notes += "echo out: %.0f ms repeats ring %.1f s past the fade".format(java.util.Locale.ROOT, delay, tail / 1000.0)
        }

        return Applied(plan.copy(outgoing = out, incoming = inc, echoOut = echo), notes)
    }

    /** (start, end) of the bass swap crossing when both low-cut lanes are the swap's two-key ramps, else null. */
    private fun swapOf(outLow: ParamCurve, inLow: ParamCurve): Pair<Long, Long>? {
        val a = outLow.keys
        val b = inLow.keys
        if (a.size != 2 || b.size != 2) return null
        if (a[0].value > 20.5f || a[1].value <= 20.5f || b[0].value <= 20.5f || b[1].value > 20.5f) return null
        return a[0].tMs to a[1].tMs
    }

    private fun shift(c: ParamCurve, delta: Long) = ParamCurve(c.keys.map { it.copy(tMs = it.tMs + delta) })

    /** Keyframes in time order with no two at the same time (the later one wins). */
    private fun dedupe(keys: List<Keyframe>): List<Keyframe> {
        val sorted = keys.sortedBy { it.tMs }
        val outList = ArrayList<Keyframe>(sorted.size)
        for (k in sorted) {
            if (outList.isNotEmpty() && outList.last().tMs == k.tMs) outList[outList.size - 1] = k else outList += k
        }
        return outList
    }

    private const val STEPS = 16
    private const val LOWPASS_END_HZ = 700f
    private const val HIGHPASS_OUT_END_HZ = 1_200f
    private const val HIGHPASS_IN_START_HZ = 800f

    /** Under a playing incoming the repeats must not bury it: lower than a plain echo-out's (0.6 / 1.0). */
    private const val ECHO_FEEDBACK = 0.45f
    private const val ECHO_WET = 0.7f
}
