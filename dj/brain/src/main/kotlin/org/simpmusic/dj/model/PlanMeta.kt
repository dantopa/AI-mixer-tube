package org.simpmusic.dj.model

import kotlinx.serialization.Serializable

/**
 * What the planner did, as data (2026-10-16). The plan's `reason` stays the sentence the owner and the log read; code
 * that decides (the planner's own strategy order, the look-ahead, the engine, the mix journal) reads this instead of
 * searching that sentence.
 */

/** Which strategy produced a plan. */
@Serializable
enum class PlanSource {
    /** Both points from the owner's mix marks (an exit marked on the outgoing and an entry on the incoming). */
    OWNER_MARKS,

    /** One side from the owner's marks, the other chosen by the planner. */
    OWNER_PARTIAL,

    /** A structure hand-off: the outgoing's instrumental over the incoming's (first tier, `StructureHandoff`). */
    STRUCTURE,

    /** The structure hand-off's second tier: the outgoing's instrumental ends under the incoming's phrase 1, which sings. */
    STRUCTURE_SECOND_TIER,

    /** A "perfect" 1-on-1 from the scored-pair search on trusted 1s. */
    PERFECT,

    /** The scored exit/entry pairs anywhere in the tracks. */
    REGULAR,

    /** The classic mix at the end of the outgoing track (`MixPoint.AT_END`, or the fallback when nothing anywhere worked). */
    END_OF_TRACK,

    /** Echo-out for tempos that cannot be matched. */
    ECHO_OUT,

    /** The plain crossfade fallback ([PlanMeta.failCode] says why). */
    CROSSFADE,
}

/** How well a plan mixes, worst first (declaration order is the ranking). */
@Serializable
enum class MixQuality {
    /** Not beat-matched, or a harmonic or voice-over-voice clash. */
    OTHER,

    /** A clean beat-matched crossfade. */
    CLEAN,

    /** The structure hand-off's second tier: the outgoing's instrumental under the incoming's phrase 1. */
    TAIL,

    /** Instrumental over instrumental, or a mix the owner marked. */
    BEST,
    ;

    companion object {
        /** The ranking the look-ahead used to compute from the reason text, from the structured facts. */
        fun of(kind: PlanKind, source: PlanSource, features: MixFeatures?): MixQuality = when {
            kind == PlanKind.BEAT_MATCHED && (source == PlanSource.OWNER_MARKS || source == PlanSource.OWNER_PARTIAL) -> BEST
            kind != PlanKind.BEAT_MATCHED || features?.harmonyClash == true || features?.voiceClash == true -> OTHER
            source == PlanSource.STRUCTURE -> BEST
            source == PlanSource.STRUCTURE_SECOND_TIER -> TAIL
            else -> CLEAN
        }
    }
}

/** Why a plan is not beat-matched (on a crossfade or an echo-out). */
@Serializable
enum class PlanFailCode { NO_ANALYSIS, GRID_UNTRUSTED, TEMPO_BEYOND_BEND, NO_ROOM, TRACK_TOO_SHORT, NOTHING_IN_RANGE, PLANNER_ERROR, OTHER }

/**
 * Everything the planner knew when it built a beat-matched plan: the training features of a learned ranker. Numbers and
 * flags, not text.
 */
@Serializable
data class MixFeatures(
    /** Overlap length in whole units. */
    val overlapUnits: Int,
    /** The unit is a bar (both tracks' bars trusted); false = beats. */
    val unitIsBar: Boolean,
    val overlapMs: Long,
    /** Exit point as a fraction of the outgoing track's duration. */
    val exitFraction: Float,
    /** Entry point as a fraction of the incoming track's duration. */
    val entryFraction: Float,
    /** Grid level of the exit point: "phrase", "downbeat" or "beat". */
    val exitLevel: String,
    val entryLevel: String,
    /** Each deck's rate at T0. */
    val rateOut: Float,
    val rateIn: Float,
    /** The planner had (and trusted) each track's vocal ranges. */
    val vocalsKnownOut: Boolean,
    val vocalsKnownIn: Boolean,
    /** Voice over voice in the overlap (ms), null when either side's vocals are unknown. */
    val voiceOverVoiceMs: Long?,
    /** The voice over voice is a real clash (`VocalClash.CLASH_MS` or more). */
    val voiceClash: Boolean,
    /** Local harmonic excess dissonance of the overlap (`Harmony.fit`), null without structure frames. */
    val harmonyExcess: Float?,
    val harmonyClash: Boolean,
    /** The global keys clash and could not be fixed by a pitch shift. */
    val keyClash: Boolean,
    val bassSwap: Boolean,
    val phraseOnPhrase: Boolean,
    /** The scored-pair search's score, when the plan came from it. */
    val pairScore: Float? = null,
    /** What cut the overlap below the length asked for: null, "voice", "harmony", "room", "intro", "outro" or "drift". */
    val cutBy: String? = null,
    /** Tempo-following (a rate ramp) instead of constant rates. */
    val warped: Boolean = false,
)

/** The structured half of a plan (see the file comment). */
@Serializable
data class PlanMeta(
    val source: PlanSource,
    val quality: MixQuality,
    val features: MixFeatures? = null,
    val failCode: PlanFailCode? = null,
    /** The plan is a "perfect" one (its reason starts with `PlanConstraints.PERFECT_OK`). */
    val perfect: Boolean = false,
    /** What happened to the owner's mix marks for this pair, when any were set (used, partly used, or why not). */
    val ownerMarksNote: String? = null,
) {
    /** The same plan attributed to another strategy; the quality follows. */
    fun withSource(kind: PlanKind, source: PlanSource): PlanMeta = copy(source = source, quality = MixQuality.of(kind, source, features))

    companion object {
        fun of(kind: PlanKind, source: PlanSource, features: MixFeatures? = null, failCode: PlanFailCode? = null) =
            PlanMeta(source, MixQuality.of(kind, source, features), features, failCode)

        fun crossfade(failCode: PlanFailCode) = of(PlanKind.SIMPLE_CROSSFADE, PlanSource.CROSSFADE, failCode = failCode)
    }
}
