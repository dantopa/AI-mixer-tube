package org.simpmusic.dj.mixview

/** What kind of transition the picture shows (mirror of the plan kind, kept here so the UI does not need the brain). */
enum class DjMixKind { BEAT_MATCHED, CUT, SIMPLE_CROSSFADE, ECHO_OUT }

/** Where the mix is, for the one-line status under the picture. */
enum class DjMixPhase {
    /** No plan yet: the tracks are still being analysed (empty state). */
    ANALYSING,

    /** A plan exists but the mix has not started (the window is not playing). */
    READY,

    /** Outgoing track playing on its own, T0 still ahead ([DjMixViewData.nowMs] < 0). */
    LEAD_IN,

    /** Both decks audible: T0 .. end of the overlap. */
    MIXING,

    /** Overlap over, the lanes are settling (the incoming deck is alone or nearly). */
    SETTLING,
}

/**
 * Everything the picture needs about ONE deck, already on the plan's time axis: sample `i` of every series sits at
 * plan time `DjMixViewData.startMs + i * stepMs` (T0 = 0 = the instant the incoming deck starts).
 *
 * The energy series are read THROUGH the deck's tempo lane, so a stretched deck looks stretched: they show what is
 * heard at that plan time, not the raw source curve. Missing analysis parts degrade to empty/zero series (see
 * [hasEnergy] / [beatsMs]) - never to an exception.
 */
data class DjMixDeck(
    val title: String,
    /** Source BPM of the track (before the tempo lane), if known. */
    val bpm: Float?,
    /** Camelot code of the track, if known. */
    val key: String?,
    /** False when the analysis had no energy curve: [energy] / [lowEnergy] are then all zero. */
    val hasEnergy: Boolean,
    /** Loudness envelope 0..1 per step (0 where the deck has no audio: before its start, past the track end). */
    val energy: List<Float>,
    /** Energy under ~200 Hz per step, same normalisation as [energy]. */
    val lowEnergy: List<Float>,
    /** Every beat inside the shown range, plan ms (already through the tempo lane). */
    val beatsMs: List<Float>,
    /** The bar starts among [beatsMs]. */
    val downbeatsMs: List<Float>,
    /** Channel fader, linear amplitude per step. */
    val volume: List<Float>,
    /** High-pass cutoff in Hz per step (20 = off): the bass swap. */
    val lowCutHz: List<Float>,
    /** Low-pass cutoff in Hz per step (20000 = off). */
    val highCutHz: List<Float>,
    /** Tempo lane (1.0 = native speed). */
    val rate: List<Float>,
    /** Pitch shift lane in semitones. */
    val pitchSemitones: List<Float>,
    /** First plan time at which this deck plays (outgoing: [DjMixViewData.startMs], incoming: 0). */
    val activeFromMs: Long,
) {
    companion object {
        fun empty(title: String, size: Int, activeFromMs: Long) = DjMixDeck(
            title = title, bpm = null, key = null, hasEnergy = false,
            energy = List(size) { 0f }, lowEnergy = List(size) { 0f },
            beatsMs = emptyList(), downbeatsMs = emptyList(),
            volume = List(size) { 1f }, lowCutHz = List(size) { 20f }, highCutHz = List(size) { 20_000f },
            rate = List(size) { 1f }, pitchSemitones = List(size) { 0f }, activeFromMs = activeFromMs,
        )
    }
}

/**
 * The whole picture of one transition on ONE time axis: plan time in ms, T0 (the incoming deck starts) = 0.
 *
 * Immutable plain data with no Android/Compose types. Build it once when a plan becomes ready with
 * [DjMixViewBuilder.build], then move the playhead with [withNow] (cheap: shares every series).
 */
data class DjMixViewData(
    /** Null while [phase] is [DjMixPhase.ANALYSING]. */
    val kind: DjMixKind?,
    /** Shown range, plan ms (negative = before T0). */
    val startMs: Long,
    val endMs: Long,
    /** Spacing of every per-step series. */
    val stepMs: Int,
    /** The overlap is `0 .. overlapEndMs` (0 for a cut). */
    val overlapEndMs: Long,
    /** Plan time after which every lane is constant. */
    val settleMs: Long,
    /** Playhead in plan time; null while the window is not playing. */
    val nowMs: Double?,
    val phase: DjMixPhase,
    val outgoing: DjMixDeck,
    val incoming: DjMixDeck,
    /** Effective BPM during a beat-matched overlap. */
    val mixBpm: Float?,
    val confidence: Float,
    /** The planner's own explanation, for a debug line. */
    val reason: String,
) {
    /** Number of samples of every per-step series. */
    val sampleCount: Int get() = outgoing.energy.size

    /** Same picture, playhead at [nowPlanMs] (plan ms), phase re-derived. Null = the window is not playing. */
    fun withNow(nowPlanMs: Double?): DjMixViewData = copy(nowMs = nowPlanMs, phase = phaseFor(nowPlanMs))

    /** Milliseconds until T0 while in [DjMixPhase.LEAD_IN], else null. */
    val msUntilT0: Long? get() = nowMs?.takeIf { it < 0 }?.let { (-it).toLong() }

    /** 0..1 progress through the overlap while mixing, else null. */
    val mixProgress: Float?
        get() {
            val n = nowMs ?: return null
            if (overlapEndMs <= 0 || n < 0) return null
            return (n / overlapEndMs).toFloat().coerceIn(0f, 1f)
        }

    private fun phaseFor(now: Double?): DjMixPhase = when {
        kind == null -> DjMixPhase.ANALYSING
        now == null -> DjMixPhase.READY
        now < 0.0 -> DjMixPhase.LEAD_IN
        now <= overlapEndMs -> DjMixPhase.MIXING
        else -> DjMixPhase.SETTLING
    }

    companion object {
        /** The empty state: no plan yet (tracks being analysed). Titles are shown when known. */
        fun analysing(fromTitle: String = "", toTitle: String = ""): DjMixViewData {
            val step = 100
            val start = -8_000L
            val end = 16_000L
            val n = ((end - start) / step).toInt() + 1
            return DjMixViewData(
                kind = null, startMs = start, endMs = end, stepMs = step, overlapEndMs = 8_000L, settleMs = 8_000L,
                nowMs = null, phase = DjMixPhase.ANALYSING,
                outgoing = DjMixDeck.empty(fromTitle, n, start), incoming = DjMixDeck.empty(toTitle, n, 0L),
                mixBpm = null, confidence = 0f, reason = "",
            )
        }
    }
}
