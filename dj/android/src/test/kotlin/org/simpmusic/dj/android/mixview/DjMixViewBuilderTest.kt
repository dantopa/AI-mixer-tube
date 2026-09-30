package org.simpmusic.dj.android.mixview

import org.simpmusic.dj.mixview.DjMixDeck
import org.simpmusic.dj.mixview.DjMixKind
import org.simpmusic.dj.mixview.DjMixPhase
import org.simpmusic.dj.mixview.DjMixViewData
import org.simpmusic.dj.model.Confident
import org.simpmusic.dj.model.DeckPlan
import org.simpmusic.dj.model.Ease
import org.simpmusic.dj.model.Keyframe
import org.simpmusic.dj.model.Mode
import org.simpmusic.dj.model.MusicalKey
import org.simpmusic.dj.model.ParamCurve
import org.simpmusic.dj.model.PlanKind
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.model.TransitionPlan
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DjMixViewBuilderTest {
    private fun analysis(
        id: String,
        bpm: Float,
        durationMs: Long = 120_000,
        withEnergy: Boolean = true,
        withBeats: Boolean = true,
        energyOf: (Int) -> Float = { 0.5f },
    ): TrackAnalysis {
        val beatMs = 60_000.0 / bpm
        val beats = if (withBeats) List((durationMs / beatMs).toInt()) { (it * beatMs).toInt() } else emptyList()
        val hop = 50
        val n = (durationMs / hop).toInt()
        return TrackAnalysis(
            videoId = id, analyzerId = "test", analyzedAtEpochMs = 0, durationMs = durationMs,
            bpm = Confident(bpm, 0.9f),
            beatTimesMs = if (withBeats) Confident(beats, 0.9f) else null,
            downbeatBeatIndices = if (withBeats) Confident(beats.indices.filter { it % 4 == 0 }, 0.9f) else null,
            beatsPerBar = 4,
            key = Confident(MusicalKey(9, Mode.MINOR), 0.8f),
            energyHopMs = hop,
            energy = if (withEnergy) List(n) { energyOf(it) } else emptyList(),
            lowBandEnergy = if (withEnergy) List(n) { energyOf(it) * 0.5f } else emptyList(),
            sections = null, vocals = null, loudnessDb = -14f,
        )
    }

    /** 120 BPM out is pulled to 126 (x1.05), 126 BPM in stays native; exit on out beat 20, entry on in beat 0. */
    private fun beatMatchedPlan(): TransitionPlan {
        val overlap = 16_000L
        return TransitionPlan(
            fromId = "a", toId = "b", kind = PlanKind.BEAT_MATCHED,
            exitPointMs = 10_000, entryPointMs = 0, overlapMs = overlap,
            outgoing = DeckPlan(
                rate = ParamCurve(listOf(Keyframe(-8_000, 1f), Keyframe(0, 1.05f, Ease.SMOOTHSTEP))),
                volume = ParamCurve(listOf(Keyframe(0, 1f), Keyframe(overlap, 0f))),
                lowCutHz = ParamCurve(listOf(Keyframe(0, 20f), Keyframe(overlap / 2, 20f), Keyframe(overlap / 2 + 1, 300f), Keyframe(overlap, 300f))),
            ),
            incoming = DeckPlan(
                volume = ParamCurve(listOf(Keyframe(0, 0f), Keyframe(overlap, 1f))),
                lowCutHz = ParamCurve(listOf(Keyframe(0, 300f), Keyframe(overlap / 2, 300f), Keyframe(overlap / 2 + 1, 20f))),
            ),
            confidence = 0.9f, reason = "test", mixBpm = 126f,
        )
    }

    private val titles = "Song A" to "Song B"

    @Test
    fun windowBoundsCoverLeadInAndSettle() {
        val plan = beatMatchedPlan()
        val d = DjMixViewBuilder.build(plan, analysis("a", 120f), analysis("b", 126f), titles)
        assertTrue(d.startMs <= -8_000 - 2_000, "shows the pre-roll and the lead-in, got ${d.startMs}")
        assertTrue(d.endMs >= plan.overlapMs + 3_000)
        assertEquals(0L, d.startMs % d.stepMs)
        assertEquals(d.sampleCount, ((d.endMs - d.startMs) / d.stepMs).toInt() + 1)
        for (deck in listOf(d.outgoing, d.incoming)) {
            assertEquals(d.sampleCount, deck.energy.size)
            assertEquals(d.sampleCount, deck.lowEnergy.size)
            assertEquals(d.sampleCount, deck.volume.size)
            assertEquals(d.sampleCount, deck.lowCutHz.size)
            assertEquals(d.sampleCount, deck.highCutHz.size)
            assertEquals(d.sampleCount, deck.rate.size)
            assertEquals(d.sampleCount, deck.pitchSemitones.size)
        }
        assertEquals(DjMixKind.BEAT_MATCHED, d.kind)
        assertEquals(plan.overlapMs, d.overlapEndMs)
        assertEquals("8A", d.outgoing.key)
    }

    @Test
    fun beatTicksOfBothDecksCoincideAfterTempoMapping() {
        val d = DjMixViewBuilder.build(beatMatchedPlan(), analysis("a", 120f), analysis("b", 126f), titles)
        // Outgoing beat 20 is the exit point (t = 0). From there both decks run at 126 BPM, so ticks must coincide.
        val outAfter = d.outgoing.beatsMs.filter { it >= -0.5f && it <= 14_000f }
        val inAfter = d.incoming.beatsMs.filter { it >= -0.5f && it <= 14_000f }
        assertTrue(outAfter.size > 20)
        assertEquals(outAfter.size, inAfter.size)
        for ((o, i) in outAfter.zip(inAfter)) assertTrue(abs(o - i) < 1.0f, "out tick $o vs in tick $i")
        val spacing = outAfter[1] - outAfter[0]
        assertTrue(abs(spacing - 60_000f / 126f) < 1f, "spacing $spacing")
        // Before T0 the outgoing ticks are stretched by the ramp: closer together than the native 500 ms.
        val before = d.outgoing.beatsMs.filter { it < -100f }
        assertTrue(before.size >= 4)
        val gap = before[before.lastIndex] - before[before.lastIndex - 1]
        assertTrue(gap < 500f && gap > 60_000f / 126f - 1f, "ramped gap $gap")
        // The incoming deck has no ticks before T0.
        assertTrue(d.incoming.beatsMs.all { it >= -0.5f })
    }

    @Test
    fun downbeatsAreASubsetOfBeats() {
        val d = DjMixViewBuilder.build(beatMatchedPlan(), analysis("a", 120f), analysis("b", 126f), titles)
        assertTrue(d.outgoing.downbeatsMs.isNotEmpty())
        assertTrue(d.outgoing.beatsMs.containsAll(d.outgoing.downbeatsMs))
        assertTrue(d.outgoing.downbeatsMs.size < d.outgoing.beatsMs.size)
    }

    @Test
    fun energyIsReadThroughTheTempoLane() {
        // Energy is a ramp over source time: source ms / 100 000. A deck running 5% fast reaches a given level sooner.
        val ramp: (Int) -> Float = { (it * 50) / 120_000f }
        val plan = beatMatchedPlan()
        val d = DjMixViewBuilder.build(plan, analysis("a", 120f, energyOf = ramp), analysis("b", 126f, energyOf = ramp), titles)
        fun at(deck: DjMixDeck, t: Long) = deck.energy[((t - d.startMs) / d.stepMs).toInt()]
        // Incoming at native rate: energy at plan time t == source t / 120000.
        assertTrue(abs(at(d.incoming, 6_000) - 6_000 / 120_000f) < 0.01f)
        // Outgoing at t = 4000 s in plan: source = 10000 + ~4000 * 1.05 (rate is 1.05 from t=0).
        val expected = (10_000 + 4_000 * 1.05f) / 120_000f
        assertTrue(abs(at(d.outgoing, 4_000) - expected) < 0.01f, "got ${at(d.outgoing, 4_000)} expected $expected")
        // The incoming deck is silent (zero) before it starts.
        assertEquals(0f, at(d.incoming, -3_000))
    }

    @Test
    fun lanesAreSampledOnTheSameAxis() {
        val plan = beatMatchedPlan()
        val d = DjMixViewBuilder.build(plan, analysis("a", 120f), analysis("b", 126f), titles)
        fun idx(t: Long) = ((t - d.startMs) / d.stepMs).toInt()
        assertEquals(1f, d.outgoing.volume[idx(0)], 1e-4f)
        assertEquals(0.5f, d.outgoing.volume[idx(8_000)], 1e-3f)
        assertEquals(0f, d.outgoing.volume[idx(16_000)], 1e-4f)
        assertEquals(0f, d.incoming.volume[idx(0)], 1e-4f)
        assertEquals(1f, d.incoming.volume[idx(16_000)], 1e-3f)
        // Bass swap: outgoing cut goes 20 -> 300 at the middle, incoming the other way.
        assertEquals(20f, d.outgoing.lowCutHz[idx(4_000)], 1e-3f)
        assertEquals(300f, d.outgoing.lowCutHz[idx(12_000)], 1e-3f)
        assertEquals(300f, d.incoming.lowCutHz[idx(4_000)], 1e-3f)
        assertEquals(20f, d.incoming.lowCutHz[idx(12_000)], 1e-3f)
        assertEquals(1f, d.outgoing.rate[idx(-9_000)], 1e-4f)
        assertEquals(1.05f, d.outgoing.rate[idx(4_000)], 1e-4f)
    }

    @Test
    fun missingAnalysisDegradesToEmptySeries() {
        val plan = beatMatchedPlan()
        val none = DjMixViewBuilder.build(plan, null, null, titles)
        assertFalse(none.outgoing.hasEnergy)
        assertTrue(none.outgoing.energy.all { it == 0f })
        assertTrue(none.outgoing.beatsMs.isEmpty())
        assertTrue(none.incoming.downbeatsMs.isEmpty())
        assertNull(none.outgoing.bpm)
        assertNull(none.outgoing.key)
        assertEquals(none.sampleCount, none.outgoing.volume.size)

        val noBeats = DjMixViewBuilder.build(plan, analysis("a", 120f, withBeats = false), analysis("b", 126f, withEnergy = false), titles)
        assertTrue(noBeats.outgoing.hasEnergy)
        assertTrue(noBeats.outgoing.beatsMs.isEmpty())
        assertFalse(noBeats.incoming.hasEnergy)
        assertTrue(noBeats.incoming.beatsMs.isNotEmpty())
    }

    @Test
    fun simpleCrossfadeDrawsAsTwoEnergyLanesAndFadeCurves() {
        val overlap = 6_000L
        val plan = TransitionPlan(
            fromId = "a", toId = "b", kind = PlanKind.SIMPLE_CROSSFADE, exitPointMs = 50_000, entryPointMs = 0, overlapMs = overlap,
            outgoing = DeckPlan(volume = ParamCurve(listOf(Keyframe(0, 1f), Keyframe(overlap, 0f)))),
            incoming = DeckPlan(volume = ParamCurve(listOf(Keyframe(0, 0f), Keyframe(overlap, 1f)))),
            confidence = 0.3f, reason = "low confidence", mixBpm = null,
        )
        val d = DjMixViewBuilder.build(plan, analysis("a", 100f, withBeats = false), analysis("b", 100f, withBeats = false), titles)
        assertEquals(DjMixKind.SIMPLE_CROSSFADE, d.kind)
        assertNull(d.mixBpm)
        assertTrue(d.outgoing.energy.any { it > 0f })
        assertTrue(d.incoming.energy.any { it > 0f })
        assertTrue(d.outgoing.beatsMs.isEmpty())
        val i = ((3_000 - d.startMs) / d.stepMs).toInt()
        assertEquals(0.5f, d.outgoing.volume[i], 1e-3f)
        assertEquals(0.5f, d.incoming.volume[i], 1e-3f)
        assertEquals(overlap, d.overlapEndMs)
    }

    @Test
    fun cutHasNoOverlapButAWindow() {
        val plan = TransitionPlan(
            fromId = "a", toId = "b", kind = PlanKind.CUT, exitPointMs = 30_000, entryPointMs = 0, overlapMs = 0,
            outgoing = DeckPlan(volume = ParamCurve(listOf(Keyframe(0, 1f), Keyframe(1, 0f)))),
            incoming = DeckPlan(),
            confidence = 0.8f, reason = "cut",
        )
        val d = DjMixViewBuilder.build(plan, analysis("a", 100f), analysis("b", 100f), titles)
        assertEquals(DjMixKind.CUT, d.kind)
        assertEquals(0L, d.overlapEndMs)
        assertTrue(d.startMs <= -6_000)
        assertTrue(d.endMs >= 8_000)
    }

    @Test
    fun playheadAndPhaseFollowNow() {
        val plan = beatMatchedPlan()
        val base = DjMixViewBuilder.build(plan, analysis("a", 120f), analysis("b", 126f), titles)
        assertEquals(DjMixPhase.READY, base.phase)
        assertNull(base.nowMs)
        val lead = base.withNow(-4_000.0)
        assertEquals(DjMixPhase.LEAD_IN, lead.phase)
        assertEquals(4_000L, lead.msUntilT0)
        val mix = base.withNow(4_000.0)
        assertEquals(DjMixPhase.MIXING, mix.phase)
        assertEquals(0.25f, assertNotNull(mix.mixProgress), 1e-4f)
        assertEquals(DjMixPhase.SETTLING, base.withNow(plan.overlapMs + 500.0).phase)
        assertEquals(DjMixPhase.READY, mix.withNow(null).phase)
        // Moving the playhead shares the heavy series.
        assertTrue(mix.outgoing === base.outgoing)
        val built = DjMixViewBuilder.build(plan, analysis("a", 120f), analysis("b", 126f), titles, nowPlanMs = 1_000.0)
        assertEquals(DjMixPhase.MIXING, built.phase)
    }

    @Test
    fun analysingStateIsEmptyAndSized() {
        val d = DjMixViewData.analysing("A", "B")
        assertEquals(DjMixPhase.ANALYSING, d.phase)
        assertNull(d.kind)
        assertEquals(d.sampleCount, d.incoming.volume.size)
        assertEquals(DjMixPhase.ANALYSING, d.withNow(1_000.0).phase)
    }
}
