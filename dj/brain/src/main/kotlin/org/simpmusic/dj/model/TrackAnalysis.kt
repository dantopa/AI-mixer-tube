package org.simpmusic.dj.model

import kotlinx.serialization.Serializable

/**
 * A value together with how much the producer trusts it (0..1).
 *
 * Every derived musical fact carries one so the planner can degrade to a plain crossfade instead of
 * beat-matching on a guess.
 */
@Serializable
data class Confident<T>(val value: T, val confidence: Float) {
    init {
        require(confidence in 0f..1f) { "confidence must be in 0..1 but was $confidence" }
    }
}

@Serializable
enum class Mode { MAJOR, MINOR }

/** Musical key. [pitchClass] 0 = C, 1 = C#/Db ... 11 = B. */
@Serializable
data class MusicalKey(val pitchClass: Int, val mode: Mode) {
    init {
        require(pitchClass in 0..11) { "pitchClass must be in 0..11 but was $pitchClass" }
    }

    /** Camelot wheel code, e.g. "8A" (A minor) or "8B" (C major). */
    fun camelot(): String = Camelot.code(this)

    override fun toString(): String = "${NOTE_NAMES[pitchClass]} ${if (mode == Mode.MAJOR) "major" else "minor"} (${camelot()})"

    companion object {
        val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    }
}

@Serializable
data class TimeRange(val startMs: Long, val endMs: Long) {
    init {
        require(endMs >= startMs) { "range end $endMs before start $startMs" }
    }

    val durationMs: Long get() = endMs - startMs
}

@Serializable
enum class SectionKind { INTRO, BODY, BREAKDOWN, DROP, OUTRO, UNKNOWN }

@Serializable
data class Section(
    val range: TimeRange,
    val kind: SectionKind,
    /** Mean normalised energy (0..1) over the section. */
    val energy: Float,
)

/**
 * Everything the DJ needs to know about one track. Produced by an analyzer (Dev A side), consumed by the
 * planner, the recommender and the engine.
 *
 * All times are milliseconds on the SOURCE audio timeline (i.e. before any tempo change).
 */
@Serializable
data class TrackAnalysis(
    val videoId: String,
    /** Bumped when the meaning/layout of this class changes; stale rows are re-analysed. */
    val schemaVersion: Int = SCHEMA_VERSION,
    /** Which analyzer produced it ("dsp-1", "beat-this-onnx-1"...). Lets us invalidate per analyzer. */
    val analyzerId: String,
    val analyzedAtEpochMs: Long,
    val durationMs: Long,

    /** Global tempo in beats per minute of the source audio. */
    val bpm: Confident<Float>?,
    /** Beat positions (ms), ascending. The grid the planner aligns to. */
    val beatTimesMs: Confident<List<Int>>?,
    /** Indices into [beatTimesMs] that are bar starts (downbeats), ascending. */
    val downbeatBeatIndices: Confident<List<Int>>?,
    /** Beats per bar (3 or 4 in practice); null when unknown. */
    val beatsPerBar: Int? = null,
    /** Phrase starts (ms): every 8 or 16 beats counted from a downbeat. Derived from the two above. */
    val phraseStartsMs: List<Long> = emptyList(),

    val key: Confident<MusicalKey>?,

    /** Hop between [energy] samples. */
    val energyHopMs: Int,
    /** Overall loudness envelope normalised to 0..1 (1 = the loudest hop of this track). */
    val energy: List<Float>,
    /** Energy under ~200 Hz, same normalisation. Tells the planner where the kick/bass is (bass swap). */
    val lowBandEnergy: List<Float>,

    val sections: Confident<List<Section>>?,
    /** Where a lead vocal is likely present. Low confidence by nature (heuristic/ML dependent). */
    val vocals: Confident<List<TimeRange>>?,

    /** Integrated loudness estimate in dBFS (RMS based, K-weighting not applied). For gain matching. */
    val loudnessDb: Float,
    /** Mean spectral timbre (13 MFCCs, first is the level-free shape). For recommendation similarity. */
    val timbre: List<Float> = emptyList(),
    /**
     * Per beat (same order as [beatTimesMs]): the neural tracker's downbeat log-odds. The evidence `BarPhase` votes the
     * bar phase from. Null for analyses made before it was stored (and for DSP-only analyses).
     */
    val beatDownbeatLogits: List<Float>? = null,
    /** How the bar phase was decided; set on read by `BarPhase`, never by an analyzer. */
    val barPhase: BarPhaseInfo? = null,
    /**
     * Level above ~6 kHz (hats, cymbals, crashes), same hop and normalisation as [energy]. Phrase evidence: a crash or an
     * open hat pattern starting on the line. Empty for analyses made before it was stored.
     */
    val highBandEnergy: List<Float> = emptyList(),
    /** Hop of [structureFrames] (ms); 0 when they are absent. */
    val structureHopMs: Int = 0,
    /**
     * Per [structureHopMs] frame, [STRUCTURE_DIM] values: the chroma (12, L1-normalised: what is being played) then MFCC
     * c1..c5 (the timbre: which instruments). What changes at a phrase line beyond loudness. Rounded to 2 decimals.
     */
    val structureFrames: List<Float> = emptyList(),
    /** Where the phrases and 16-bar blocks start; set on read by `PhraseGrid`, never by an analyzer. */
    val phrases: PhraseInfo? = null,
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val STRUCTURE_DIM = 17
    }
}

/**
 * The result of voting which beat of the bar is the "1" (see `BarPhase`).
 *
 * [barMarginLogits] holds, per bar of the decided lattice (bar k starts at downbeat k), how much better the chosen 1 explains
 * the network's evidence than the best other beat of the bar, averaged over the bars around it (log-odds per bar).
 */
@Serializable
data class BarPhaseInfo(
    /** "voted", "anchored" (the owner tapped the 1) or "none" (no evidence: the tracker's own downbeats are kept). */
    val source: String,
    /** Median of [barMarginLogits]. */
    val margin: Float,
    /** The bars of this track are trusted (median margin >= `BarPhase.TRUST_MARGIN`, few slips, or anchored); a "Perfect" mix point must also clear `BarPhase.PERFECT_MARGIN` locally. */
    val trusted: Boolean,
    /** Places where the decided lattice skips or repeats a beat (metric irregularities or grid errors). */
    val slips: Int,
    /** Bars where the 1 moved compared with the tracker's own downbeats. */
    val changedBars: Int,
    val barMarginLogits: List<Float> = emptyList(),
)

/**
 * The phrase lattice found by `PhraseGrid` on the decided bars: per bar, its position in a 16-bar block. The 8-bar phrase
 * starts are also in [TrackAnalysis.phraseStartsMs].
 */
@Serializable
data class PhraseInfo(
    /** "detected" or "anchored" (the owner marked a phrase start). */
    val source: String,
    /** How much better the lattice explains the evidence than any shift other than half a block. */
    val phraseMargin: Float,
    /** How much better than the same lattice shifted by half a block (8 bars): is the 16 really the 16. */
    val blockMargin: Float,
    val phrasesTrusted: Boolean,
    val blocksTrusted: Boolean,
    /** Blocks that started somewhere other than a 4-bar boundary of the previous one (pickups, odd intros, stretched breaks). */
    val irregular: Int,
    /** Start (ms) of every 16-bar block. */
    val blockStartsMs: List<Long>,
    /** Start (ms) of every 4-bar line of the lattice ("16" in beats, as the owner counts; used by a Perfect mix when [phrasesTrusted]). */
    val phraseStartsMs: List<Long> = emptyList(),
    /** Per bar (same order as the downbeats): its position in its block, 0..15. */
    val barPositions: List<Int>,
)

/** Lowest confidence at which each fact is trusted for a beat-matched transition. */
object Trust {
    const val BEATS = 0.55f
    const val BPM = 0.55f
    const val DOWNBEATS = 0.45f
    const val KEY = 0.45f
    const val SECTIONS = 0.4f
}
