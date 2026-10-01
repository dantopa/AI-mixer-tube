package org.simpmusic.dj.planner

import org.simpmusic.dj.model.Confident
import org.simpmusic.dj.model.TrackAnalysis
import kotlin.test.Test
import kotlin.test.assertTrue

class LocalTempoAgreementTest {
    /**
     * The device case: a 124 bpm house track whose tracker marked nothing through a beatless break, and whose DSP field
     * read 110.5. The span average falls to ~113, so the 124 bpm measured at the exit was refused as "grid not
     * trustworthy" although every marked beat sits on the 124 bpm lattice.
     */
    @Test
    fun aGridWithABeatlessBreakStillAgreesWithItsOwnTempo() {
        val beat = 60000.0 / 124
        val beats = ArrayList<Int>()
        var t = 500.0
        while (t < 210_000) {
            if (t !in 90_000.0..110_000.0) beats += t.toInt() // 20 s break with no beats
            t += beat
        }
        val dur = beats.last() + 2000L
        val a =
            TrackAnalysis(
                videoId = "house", analyzerId = "custom", analyzedAtEpochMs = 0, durationMs = dur,
                bpm = Confident(110.5f, 0.6f), beatTimesMs = Confident(beats, 0.8f),
                downbeatBeatIndices = null, beatsPerBar = 4, phraseStartsMs = emptyList(), key = null,
                energyHopMs = 100, energy = List((dur / 100).toInt() + 1) { 0.6f }, lowBandEnergy = List((dur / 100).toInt() + 1) { 0.5f },
                sections = null, vocals = null, loudnessDb = -10f,
            )
        val c = TrackContext(a)
        assertTrue(c.avgBpm < 124 * 0.94, "the span average is dragged down by the break (${c.avgBpm})")
        assertTrue(c.localTempoAgrees(124.0), "124 bpm at the exit is the track's own tempo")
        assertTrue(!c.localTempoAgrees(140.0), "a tempo the track does not have is still refused")
    }
}
