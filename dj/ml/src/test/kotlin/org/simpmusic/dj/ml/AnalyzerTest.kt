package org.simpmusic.dj.ml

import org.simpmusic.dj.model.Confident
import org.simpmusic.dj.model.MusicalKey
import org.simpmusic.dj.model.Mode
import org.simpmusic.dj.model.PcmAudio
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.model.TrackAnalyzer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AnalyzerTest {
    private val audio = PcmAudio(FloatArray(44100 * 10) { if (it % 22050 == 0) 0.9f else 0.01f }, 44100)

    private class FakeBase : TrackAnalyzer {
        override val id = "fake-dsp"
        override fun analyze(videoId: String, audio: PcmAudio) = TrackAnalysis(
            videoId = videoId, analyzerId = id, analyzedAtEpochMs = 1L, durationMs = audio.durationMs,
            bpm = Confident(99f, 0.2f), beatTimesMs = Confident(listOf(0, 606, 1212), 0.2f),
            downbeatBeatIndices = Confident(listOf(0), 0.2f), beatsPerBar = 4, phraseStartsMs = listOf(0L),
            key = Confident(MusicalKey(9, Mode.MINOR), 0.8f),
            energyHopMs = 500, energy = listOf(0.5f, 1f), lowBandEnergy = listOf(0.1f, 0.2f),
            sections = null, vocals = null, loudnessDb = -14f, timbre = listOf(1f, 2f),
        )
    }

    private fun picks(bpm: Double, beats: Int, bar: Int, jitter: Double = 0.0): BeatPicks {
        val period = 60.0 / bpm
        val b = DoubleArray(beats) { 0.3 + it * period + (if (it % 2 == 0) jitter else -jitter) }
        val d = b.filterIndexed { i, _ -> i % bar == 0 }.toDoubleArray()
        return BeatPicks(b, d, DoubleArray(b.size) { 0.95 }, DoubleArray(d.size) { 0.9 })
    }

    @Test
    fun gridBuilderReadsTempoBarsAndPhrases() {
        val g = assertNotNull(BeatGridBuilder.build(picks(128.0, 130, 4)))
        assertEquals(128f, g.bpm!!, 0.05f)
        assertEquals(4, g.beatsPerBar)
        assertEquals(listOf(0, 4, 8), g.downbeatBeatIndices.take(3))
        assertTrue(g.beatConfidence > 0.9f && g.downbeatConfidence > 0.9f)
        // 16-beat phrases at 128 bpm: every 4th downbeat starting at the first one
        assertEquals(g.beatTimesMs[0].toLong(), g.phraseStartsMs[0])
        assertEquals(g.beatTimesMs[16].toLong(), g.phraseStartsMs[1])
    }

    @Test
    fun waltzAndSlowTempoPhrases() {
        val w = assertNotNull(BeatGridBuilder.build(picks(120.0, 90, 3)))
        assertEquals(3, w.beatsPerBar)
        assertEquals(w.beatTimesMs[12].toLong(), w.phraseStartsMs[1])
        val slow = assertNotNull(BeatGridBuilder.build(picks(70.0, 60, 4)))
        assertEquals(slow.beatTimesMs[8].toLong(), slow.phraseStartsMs[1])
    }

    @Test
    fun oddBarLengthLowersDownbeatTrustAndDropsBeatsPerBar() {
        val g = assertNotNull(BeatGridBuilder.build(picks(100.0, 60, 2)))
        assertNull(g.beatsPerBar)
        assertTrue(g.downbeatConfidence <= 0.3f)
        assertTrue(g.phraseStartsMs.isEmpty())
    }

    @Test
    fun theDownbeatEvidenceOfEveryBeatReachesTheStoredAnalysis() {
        // frames at 50 fps: a beat every 25 frames (120 bpm), downbeat logit +3 on every 4th beat, -2 elsewhere,
        // and a weak +0.4 on the half bar that the peak picker drops but the bar-phase vote needs
        val frames = 25 * 64
        val beat = FloatArray(frames) { -5f }
        val down = FloatArray(frames) { -6f }
        for (k in 0 until 64) {
            val f = 10 + k * 25
            if (f >= frames) break
            beat[f] = 4f
            down[f] = when (k % 4) { 0 -> 3f; 2 -> -0.4f; else -> -2f }
        }
        val p = BeatPostProcessor.pick(FrameLogits(beat, down))
        assertEquals(p.beatsSec.size, p.beatDownbeatLogit.size)
        assertEquals(3f, p.beatDownbeatLogit[0], 1e-6f)
        assertEquals(-0.4f, p.beatDownbeatLogit[2], 1e-6f)
        val g = assertNotNull(BeatGridBuilder.build(p))
        val provider = object : BeatProvider {
            override val id = "fake-beats"
            override fun grid(audio: PcmAudio) = g
        }
        val a = CompositeAnalyzer(FakeBase(), provider, clock = { 42L }).analyze("vid", audio)
        val logits = assertNotNull(a.beatDownbeatLogits)
        assertEquals(a.beatTimesMs!!.value.size, logits.size)
        assertEquals(3f, logits[4], 1e-6f)
    }

    @Test
    fun tooFewBeatsGiveNoGrid() {
        assertNull(BeatGridBuilder.build(picks(120.0, 3, 4)))
    }

    @Test
    fun compositeOverlaysNeuralBeatsOntoBase() {
        val grid = BeatGridBuilder.build(picks(126.0, 100, 4))!!
        val provider = object : BeatProvider {
            override val id = "fake-beats"
            override fun grid(audio: PcmAudio) = grid
        }
        val a = CompositeAnalyzer(FakeBase(), provider, clock = { 42L }).analyze("vid", audio)
        assertEquals("fake-dsp+fake-beats", a.analyzerId)
        assertEquals(42L, a.analyzedAtEpochMs)
        assertEquals(126f, a.bpm!!.value, 0.05f)
        assertEquals(grid.beatTimesMs, a.beatTimesMs!!.value)
        assertEquals(grid.downbeatBeatIndices, a.downbeatBeatIndices!!.value)
        assertEquals(grid.phraseStartsMs, a.phraseStartsMs)
        // untouched from the base
        assertEquals(MusicalKey(9, Mode.MINOR), a.key!!.value)
        assertEquals(listOf(0.5f, 1f), a.energy)
        assertEquals(-14f, a.loudnessDb)
        assertEquals(listOf(1f, 2f), a.timbre)
        assertEquals("vid", a.videoId)
    }

    @Test
    fun compositeKeepsBaseWhenProviderHasNothingOrFails() {
        val nothing = object : BeatProvider { override val id = "n"; override fun grid(audio: PcmAudio): BeatGrid? = null }
        val boom = object : BeatProvider { override val id = "b"; override fun grid(audio: PcmAudio): BeatGrid = error("model missing") }
        val base = FakeBase().analyze("v", audio)
        for (p in listOf(nothing, boom)) {
            val a = CompositeAnalyzer(FakeBase(), p).analyze("v", audio)
            assertEquals(base.bpm, a.bpm)
            assertEquals("fake-dsp", a.analyzerId.substringBefore('+'))
            assertSame(base.key!!.value, a.key!!.value.let { base.key!!.value }.also { assertEquals(it, a.key!!.value) })
        }
    }

    @Test
    fun beatThisAnalyzerStandaloneAndWithBase() {
        val grid = BeatGridBuilder.build(picks(140.0, 100, 4))!!
        val provider = object : BeatProvider { override val id = "p"; override fun grid(audio: PcmAudio) = grid }
        val alone = BeatThisAnalyzer(provider, clock = { 7L }).analyze("x", audio)
        assertEquals("beat-this-onnx-1", alone.analyzerId)
        assertEquals(140f, alone.bpm!!.value, 0.1f)
        assertNull(alone.key)
        assertEquals(audio.durationMs, alone.durationMs)
        assertTrue(alone.energy.isNotEmpty() && alone.energy.max() == 1f)
        val with = BeatThisAnalyzer(provider, FakeBase()).analyze("x", audio)
        assertEquals("fake-dsp+beat-this-onnx-1", with.analyzerId)
        assertNotNull(with.key)
    }
}
