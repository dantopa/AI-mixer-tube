package org.simpmusic.dj.android.splice

import org.simpmusic.dj.android.window.WindowTimeline
import org.simpmusic.dj.android.window.WindowTuning
import kotlin.math.abs

/**
 * When each deck does what in a spliced transition, all in WINDOW time (ms since the window's first sample).
 *
 * ```
 *  outgoing deck  own audio ==[spliceIn]== window (from its own audio processor) ====[handoff]  (paused)
 *  incoming deck                 [incomingStart] silent, window, pointer locked to the outgoing ==[handoff]== window
 *                                                                                ==[join]== its own audio
 * ```
 *
 * The outgoing deck enters the window at [spliceInMs], inside the lead-in where the window is still the outgoing track
 * at rate 1, so the switch replaces audio with the same audio. The incoming deck plays (silently) from
 * [incomingStartMs], carrying the window at a pointer the controller aligns to the outgoing deck WITHOUT seeking (the
 * deck is silent, so the pointer can jump); the two then trade places with a short fader cross-fade at [handoffMs],
 * both playing the same window samples. After that the incoming deck glides its pointer (a fraction of a percent of
 * read rate) onto the one that makes the window's tail, which IS the incoming track, line up with its own audio, and
 * leaves the window for its own audio at [joinMs], inside its own audio processor.
 */
class SpliceSchedule(
    val spliceInMs: Double,
    val incomingStartMs: Double,
    val handoffMs: Double,
    val joinMs: Double,
    /** `window time - incoming source time` once the incoming lane has settled (rate 1): the incoming deck's target pointer. */
    val incomingOffsetMs: Double,
    /** Outgoing source time of window sample 0 (our decode): the outgoing deck's pointer is `-this` (plus the skew). */
    val outgoingStartSourceMs: Double,
    /** Incoming source time the incoming deck is expected to start from (for the reference audio to cover it). */
    val incomingStartSourceMs: Double,
    /** Loudness-match gain the window applies to the incoming track when it settles (ramped to 1 after the join). */
    val joinGain: Float,
) {
    override fun toString(): String =
        "splice in ${spliceInMs.toLong()}, incoming from ${incomingStartMs.toLong()} (src ${incomingStartSourceMs.toLong()}), " +
            "hand-off ${handoffMs.toLong()}, join ${joinMs.toLong()} (window ms), incoming offset ${"%.1f".format(incomingOffsetMs)}"

    companion object {
        /** Ms of window (still the plain outgoing track) between the splice and the start of the tempo pre-roll. */
        const val SPLICE_BEFORE_PREROLL_MS = 1500.0

        /** The incoming deck runs silently this long before the hand-off: start transient + skew measurement + lock. */
        const val INCOMING_WARMUP_MS = 4500.0

        /** Join this long after the lanes settle (the window is plain incoming audio at rate 1 from there). */
        const val JOIN_AFTER_SETTLE_MS = 300.0

        /** Minimum hand-off -> join span over which the incoming pointer glides. */
        const val MIN_GLIDE_SPAN_MS = 2500.0

        /** The outgoing deck must still have this much of its own track left when it hands off. */
        const val OUTGOING_END_MARGIN_MS = 2500.0

        sealed interface Outcome {
            class Ok(val schedule: SpliceSchedule) : Outcome

            class Rejected(val reason: String) : Outcome
        }

        fun plan(timeline: WindowTimeline, outgoingDurationMs: Long): Outcome {
            val lead = WindowTuning.LEAD_IN_MS.toDouble()
            val spliceIn = lead - SPLICE_BEFORE_PREROLL_MS
            val lockIn = timeline.lockInWindowMs.toDouble()
            val sJoin = timeline.incomingSourceOfWindow(lockIn)
            // the lane must really be at rate 1 from the settle point on: check the slope of the mapping
            val slope = timeline.incomingSourceOfWindow(lockIn + 1000.0) - sJoin
            if (abs(slope - 1000.0) > 3.0) return Outcome.Rejected("incoming not at rate 1 after settling")
            val offset = lockIn - sJoin
            val join = lockIn + JOIN_AFTER_SETTLE_MS
            if (join + 500 > timeline.windowMs) return Outcome.Rejected("window too short for the join")
            val handoff = maxOf(spliceIn + 1500.0, offset + INCOMING_WARMUP_MS + 500.0)
            if (join - handoff < MIN_GLIDE_SPAN_MS) {
                return Outcome.Rejected("incoming enters too early in its track (settles at ${sJoin.toLong()} ms) for a silent warm-up")
            }
            val incomingStart = handoff - INCOMING_WARMUP_MS
            val incomingStartSource = incomingStart - offset
            if (incomingStartSource < 0) return Outcome.Rejected("incoming deck would start before its track")
            val outStart = timeline.outgoingSourceAtWindowStart
            if (outStart + handoff + OUTGOING_END_MARGIN_MS > outgoingDurationMs) {
                return Outcome.Rejected("outgoing track ends before the hand-off")
            }
            // the splice replaces plain outgoing audio with the window: the lanes must still be the identity there
            val t = timeline.planTimeOfWindow(spliceIn + 50.0)
            val o = timeline.plan.outgoing
            if (abs(o.rate.valueAt(t) - 1f) > 0.003f || abs(o.volume.valueAt(t) - 1f) > 0.02f || abs(o.pitchSemitones.valueAt(t)) > 0.02f) {
                return Outcome.Rejected("outgoing lane not plain at the splice point")
            }
            val g = timeline.plan.incoming.volume.valueAt(timeline.settledRelMs.toDouble())
            return Outcome.Ok(SpliceSchedule(spliceIn, incomingStart, handoff, join, offset, outStart, incomingStartSource, g))
        }
    }
}
