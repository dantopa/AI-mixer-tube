package org.simpmusic.dj

import org.simpmusic.dj.model.Confident
import org.simpmusic.dj.model.SectionKind
import org.simpmusic.dj.model.TrackAnalysis

/**
 * Turns the exact ground truth of a [SyntheticTracks] render into a "perfect" [TrackAnalysis] so planner and
 * renderer tests need no audio analyzer.
 */
object FakeAnalysis {
    fun fromTruth(
        truth: SyntheticTracks.Truth,
        videoId: String,
        confidence: Float = 0.9f,
        beatsPerBar: Int = 4,
        phraseBeats: Int = 16,
        loudnessDb: Float = -14f,
        energyHopMs: Int = 100,
        /** Everything before this is silent in the energy envelope (a track with a long silent lead-in). */
        leadingSilenceMs: Long = 0L,
        /** Everything after this is silent (trailing silence in the file). */
        trailingSilenceFromMs: Long = Long.MAX_VALUE,
        withSections: Boolean = true,
        withKey: Boolean = true,
    ): TrackAnalysis {
        val n = (truth.durationMs / energyHopMs).toInt() + 1
        val energy = FloatArray(n)
        val low = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toLong() * energyHopMs
            if (t < leadingSilenceMs || t >= trailingSilenceFromMs) continue
            val sec = truth.sections.firstOrNull { t >= it.range.startMs && t < it.range.endMs }
            energy[i] = sec?.energy ?: if (truth.sections.isEmpty()) 0.6f else 0.3f
            low[i] = when (sec?.kind) {
                SectionKind.BREAKDOWN -> 0.05f
                null -> if (truth.sections.isEmpty()) 0.6f else 0.2f
                else -> energy[i] * 0.9f
            }
        }
        val phrases = ArrayList<Long>()
        val step = phraseBeats / beatsPerBar
        var d = 0
        while (d < truth.downbeatBeatIndices.size) {
            phrases += truth.beatTimesMs[truth.downbeatBeatIndices[d]].toLong()
            d += maxOf(1, step)
        }
        return TrackAnalysis(
            videoId = videoId,
            analyzerId = "fake-truth",
            analyzedAtEpochMs = 0L,
            durationMs = truth.durationMs,
            bpm = Confident(truth.bpm, confidence),
            beatTimesMs = Confident(truth.beatTimesMs, confidence),
            downbeatBeatIndices = Confident(truth.downbeatBeatIndices, confidence),
            beatsPerBar = beatsPerBar,
            phraseStartsMs = phrases,
            key = if (withKey) Confident(truth.key, confidence) else null,
            energyHopMs = energyHopMs,
            energy = energy.toList(),
            lowBandEnergy = low.toList(),
            sections = if (withSections && truth.sections.isNotEmpty()) Confident(truth.sections, confidence) else null,
            vocals = null,
            loudnessDb = loudnessDb,
        )
    }
}
