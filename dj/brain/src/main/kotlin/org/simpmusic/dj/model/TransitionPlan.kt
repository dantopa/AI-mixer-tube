package org.simpmusic.dj.model

import kotlinx.serialization.Serializable

/** How a parameter moves between two keyframes (applies to the segment that ENDS at the keyframe). */
@Serializable
enum class Ease { LINEAR, SMOOTHSTEP, EXPONENTIAL, HOLD }

/** [tMs] is WALL-CLOCK time relative to the transition start T0 (the instant the incoming deck starts). May be negative. */
@Serializable
data class Keyframe(val tMs: Long, val value: Float, val ease: Ease = Ease.LINEAR)

/**
 * Automation lane for one mixer parameter. Holds the first value before the first key and the last
 * value after the last key. [EXPONENTIAL] interpolates in the log domain (use it for frequencies and
 * for gains expressed as linear amplitude ratios; values must be > 0 then).
 */
@Serializable
data class ParamCurve(val keys: List<Keyframe>) {
    init {
        require(keys.isNotEmpty()) { "a ParamCurve needs at least one keyframe" }
        for (i in 1 until keys.size) require(keys[i].tMs >= keys[i - 1].tMs) { "keyframes must be sorted by time" }
    }

    fun valueAt(tMs: Long): Float = valueAt(tMs.toDouble())

    fun valueAt(tMs: Double): Float {
        val first = keys.first()
        if (tMs <= first.tMs) return first.value
        val last = keys.last()
        if (tMs >= last.tMs) return last.value
        var i = 1
        while (keys[i].tMs < tMs) i++
        val a = keys[i - 1]
        val b = keys[i]
        val span = (b.tMs - a.tMs).toDouble()
        if (span <= 0.0) return b.value
        val x = (tMs - a.tMs) / span
        return when (b.ease) {
            Ease.HOLD -> a.value
            Ease.LINEAR -> lerp(a.value.toDouble(), b.value.toDouble(), x).toFloat()
            Ease.SMOOTHSTEP -> lerp(a.value.toDouble(), b.value.toDouble(), x * x * (3.0 - 2.0 * x)).toFloat()
            Ease.EXPONENTIAL -> {
                val va = a.value.toDouble().coerceAtLeast(1e-6)
                val vb = b.value.toDouble().coerceAtLeast(1e-6)
                Math.exp(lerp(Math.log(va), Math.log(vb), x)).toFloat()
            }
        }
    }

    val startMs: Long get() = keys.first().tMs
    val endMs: Long get() = keys.last().tMs

    private fun lerp(a: Double, b: Double, x: Double) = a + (b - a) * x

    companion object {
        fun constant(value: Float) = ParamCurve(listOf(Keyframe(0L, value)))
    }
}

/**
 * Everything the mixer does to ONE deck during the transition, as automation over wall-clock time.
 * This is deliberately the vocabulary of a DJ mixer (tempo fader, key lock, channel fader, EQ kill)
 * so the offline renderer and the on-device engine execute the very same plan.
 */
@Serializable
data class DeckPlan(
    /** Playback speed multiplier with pitch preserved (1.0 = native). The tempo fader. */
    val rate: ParamCurve = ParamCurve.constant(1f),
    /** Pitch shift in semitones on top of the key-lock. Used to bring keys into a compatible Camelot slot. */
    val pitchSemitones: ParamCurve = ParamCurve.constant(0f),
    /** Channel fader as LINEAR amplitude (0..1+). Includes loudness matching. */
    val volume: ParamCurve = ParamCurve.constant(1f),
    /** High-pass cutoff in Hz (20 = off). The bass "swap" lives here. */
    val lowCutHz: ParamCurve = ParamCurve.constant(20f),
    /** Low-pass cutoff in Hz (20000 = off). Filter sweeps live here. */
    val highCutHz: ParamCurve = ParamCurve.constant(20000f),
)

@Serializable
enum class PlanKind {
    /** Beat-grid aligned, phrase-aligned, tempo matched. The real thing. */
    BEAT_MATCHED,
    /** Plain equal-power overlap when we do not trust the analysis. Same as the app's existing crossfade. */
    SIMPLE_CROSSFADE,
    /**
     * Straight cut on a downbeat. DEPRECATED: the planner no longer emits it (a bare cut was judged unacceptable);
     * kept so stored plans and old executors keep parsing.
     */
    CUT,
    /**
     * Classic DJ echo-out for tracks whose tempos cannot be matched: the outgoing deck's dry signal fades over about a
     * bar under a rising high-pass while a beat-synced feedback delay ([TransitionPlan.echoOut]) rings out over the
     * incoming deck, which enters at its own phrase start at native tempo. No tempo automation.
     */
    ECHO_OUT,
}

/**
 * Beat-synced feedback delay thrown on the OUTGOING deck: an aux send taken from the deck BEFORE its filters and fader
 * (so the repeats carry the full signal while the dry signal thins out), returned AFTER the fader so the tail survives
 * the fader going to 0. All times are plan time (ms relative to T0).
 */
@Serializable
data class EchoOutSpec(
    /** Delay time (ms): 3/4 or 1/2 of the outgoing beat. */
    val delayMs: Float,
    /** Feedback gain per repeat (< 1). */
    val feedback: Float,
    /** Send level into the delay (0..1) over plan time. */
    val send: ParamCurve,
    /** Wet return level (linear amplitude) over plan time; reaches 0 at [tailMs]. */
    val wet: ParamCurve,
    /** Time after T0 at which the echo is inaudible for good. Counts as settling time. */
    val tailMs: Long,
    /** High-pass inside the feedback loop (Hz): repeats never carry bass. */
    val highpassHz: Float = 280f,
    /** High-cut (damping) inside the loop (Hz). */
    val dampHz: Float = 6000f,
    /** Alternate repeats between left and right. */
    val pingPong: Boolean = true,
)

/**
 * Timeline of a transition. Let T0 be the wall-clock instant the incoming deck starts playing
 * at [entryPointMs] of its own source timeline; the outgoing deck reaches [exitPointMs] at T0.
 * Both decks then run the automation lanes in [outgoing] / [incoming], sampled at
 * (now - T0). The outgoing deck stops being audible once its volume lane hits 0.
 */
@Serializable
data class TransitionPlan(
    val fromId: String,
    val toId: String,
    val kind: PlanKind,

    /** Position on the OUTGOING source timeline that coincides with T0. On a downbeat/phrase for BEAT_MATCHED. */
    val exitPointMs: Long,
    /** Position on the INCOMING source timeline where the deck starts at T0. On a downbeat/phrase for BEAT_MATCHED. */
    val entryPointMs: Long,
    /** Wall-clock length of the overlap, from T0 until the outgoing deck is silent. */
    val overlapMs: Long,

    val outgoing: DeckPlan,
    val incoming: DeckPlan,

    /** Overall trust in this plan (min of the facts it relied on). */
    val confidence: Float,
    /** Human-readable why: shown in logs / a debug sheet. */
    val reason: String,
    /** Effective BPM during the overlap when BEAT_MATCHED; null otherwise. */
    val mixBpm: Float? = null,
    /** Present for [PlanKind.ECHO_OUT]: the delay executed on the outgoing deck. Null otherwise. */
    val echoOut: EchoOutSpec? = null,
) {
    /** Earliest wall-clock time (relative to T0) at which any lane starts moving. Negative if there is a pre-roll ramp. */
    val preRollMs: Long
        get() = minOf(
            0L,
            outgoing.rate.startMs, outgoing.pitchSemitones.startMs, outgoing.volume.startMs,
            outgoing.lowCutHz.startMs, outgoing.highCutHz.startMs,
        )
}

/** Where in the outgoing track the mix may happen. */
@Serializable
enum class MixPoint {
    /** Anywhere the planner finds a good exit/entry pair (the default). */
    ANYWHERE,
    /** Near the outro / last phrase of the outgoing track, at the first phrase of the incoming one. */
    AT_END,
}

/** Constraints from the caller's playback state (the plan must be executable in the future). */
@Serializable
data class PlanConstraints(
    /**
     * Earliest position (ms on the OUTGOING source timeline) the plan may touch: `exitPointMs` and the start of any
     * pre-roll ramp (`exitPointMs + preRollMs`) are both >= this. Typically current playback position plus the time
     * needed to decode and render the window (>= 40 s).
     */
    val earliestExitMs: Long = 0L,
    /**
     * Latest `exitPointMs` an ANYWHERE candidate may use ("mix now": the next good point within a short span). Long.MAX_VALUE
     * = no limit. Only the anywhere search honours it; a plan whose exit lands later (the end-of-track fallback) is the
     * caller's to reject.
     */
    val latestExitMs: Long = Long.MAX_VALUE,
    /**
     * "Perfect mix": only an exit and an entry that both sit on a "1" the bar-phase vote trusts locally
     * (`BarPhase.PERFECT_MARGIN`, or the owner's tapped 1), on bars or phrases, beat-matched. Nothing else: when no such
     * pair exists the plan is a SIMPLE_CROSSFADE whose reason starts with [PERFECT_REFUSED], and a plan that qualifies
     * has a reason starting with [PERFECT_OK].
     */
    val perfect: Boolean = false,
    /**
     * The default mode: try a [perfect] mix first, with the settings' own timing (how much of the outgoing track must
     * have played, unlike the "perfect" button, which may mix right away), and when the pair does not qualify plan the
     * regular way. Only with `MixPoint.ANYWHERE`; ignored when [perfect] is set.
     */
    val preferPerfect: Boolean = false,
) {
    companion object {
        const val PERFECT_OK = "PERFECT: "
        const val PERFECT_REFUSED = "perfect not possible: "
    }
}

/** Knobs the user (or the app) controls. */
@Serializable
data class DjSettings(
    val enabled: Boolean = false,
    /** Target overlap in bars for a beat-matched transition. The planner clamps to what the tracks allow. */
    val overlapBars: Int = 8,
    /** How far tempo may be bent to match (fraction, 0.06 = ±6%). */
    val maxTempoBend: Float = 0.08f,
    /** Allow shifting pitch by up to ±[maxPitchShift] semitones to reach a compatible key. */
    val allowKeyShift: Boolean = true,
    /** Allow the echo-out fallback for tempo-incompatible pairs. Off: those pairs get the app's plain crossfade. */
    val allowEchoOut: Boolean = true,
    val maxPitchShift: Int = 2,
    /** Bass swap (low-cut hand-over) on the transition. */
    val bassSwap: Boolean = true,
    /** Below this overall confidence the planner falls back to a simple crossfade. */
    val minConfidence: Float = 0.5f,
    /** Length of the simple-crossfade fallback. Mirrors the app's crossfade duration. */
    val fallbackCrossfadeMs: Long = 6000L,
    /**
     * Auto DJ: pick and enqueue the next track by itself when the queue is about to run out. Additive field, default off,
     * so settings serialised before it existed still decode.
     */
    val autoDj: Boolean = false,
    /** Auto DJ: how energy should move from one track to the next over the session. */
    val autoDjArc: EnergyArc = EnergyArc.STEADY,
    /** Where the mix may happen. [MixPoint.AT_END] is the pre-2026-09-30 behaviour. */
    val mixPoint: MixPoint = MixPoint.ANYWHERE,
    /** With [MixPoint.ANYWHERE]: the outgoing track must have played at least min(this fraction of its length, 75 s). */
    val minPlayedFraction: Float = 0.55f,
    /**
     * Execute mixes by splicing the rendered window into the two players' own audio at exact frames (two players,
     * pointer-locked) instead of a third player locked by position. Off = the three-player path.
     */
    val preciseSplice: Boolean = true,
)

/** Shape of the energy over an Auto DJ session; feeds [org.simpmusic.dj.recommend.RecommendContext.energyTrend]. */
@Serializable
enum class EnergyArc {
    /** Keep the floor where it is. */
    STEADY,

    /** Every next intro a little hotter than the previous outro. */
    BUILD,

    /** Every next intro a little cooler. */
    COOL_DOWN,

    /** Eight tracks up, eight tracks down. */
    WAVE,
    ;

    /** Energy trend (-1..1) for the [step]th pick of a session (0 based). */
    fun trendAt(step: Int): Float =
        when (this) {
            STEADY -> 0f
            BUILD -> 0.6f
            COOL_DOWN -> -0.6f
            WAVE -> if ((step / 8) % 2 == 0) 0.6f else -0.6f
        }
}
