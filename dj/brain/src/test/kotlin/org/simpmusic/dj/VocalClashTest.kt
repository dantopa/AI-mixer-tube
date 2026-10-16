package org.simpmusic.dj

import org.simpmusic.dj.analysis.VocalClash
import org.simpmusic.dj.model.*
import org.simpmusic.dj.planner.DjTransitionPlanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Voice over voice: measured over the overlap, avoided by the pair choice, cut short when it cannot be avoided. */
class VocalClashTest {
    private val planner = DjTransitionPlanner()
    private val settings = DjSettings(enabled = true)

    @Test
    fun clashIsMeasuredInWallTimeAtEachDeckRate() {
        val out = listOf(TimeRange(0, 20_000))
        val inc = listOf(TimeRange(5_000, 30_000))
        val r = assertNotNull(VocalClash.of(out, 10_000, 1.0, inc, 0, 1.0, 16_000))
        // outgoing sings 10..20 s of its source = wall 0..10 s; incoming from wall 5 s: both 5..10 s
        assertEquals(5_000, r.clashMs)
        assertEquals(5_000, r.firstMs)
        assertTrue(r.clash)
        // the incoming running at x1.25 reaches its voice at wall 4 s
        assertEquals(4_000, VocalClash.of(out, 10_000, 1.0, inc, 0, 1.25, 16_000)!!.firstMs)
        // no detection on one side: no opinion
        assertEquals(null, VocalClash.of(null, 0, 1.0, inc, 0, 1.0, 16_000))
    }

    @Test
    fun thePlannerMixesInstrumentalOnInstrumental() {
        // outgoing sings until 88 s then plays an instrumental outro; incoming has an 8 s instrumental intro
        val out = analysis("out", vocals = listOf(TimeRange(2_000, 88_000)))
        val inc = analysis("in", vocals = listOf(TimeRange(8_000, 95_000)))
        val plan = planner.plan(out, inc, settings)
        println("instrumental on instrumental: ${plan.reason}")
        assertEquals(PlanKind.BEAT_MATCHED, plan.kind)
        val v = VocalClash.of(VocalClash.ranges(out), plan.exitPointMs, 1.0, VocalClash.ranges(inc), plan.entryPointMs, 1.0, plan.overlapMs)!!
        assertTrue(v.clashMs < VocalClash.CLASH_MS, plan.reason)
        val blind = planner.plan(analysis("out"), analysis("in"), settings)
        val vb = VocalClash.of(VocalClash.ranges(out), blind.exitPointMs, 1.0, VocalClash.ranges(inc), blind.entryPointMs, 1.0, blind.overlapMs)!!
        println("without vocal detection the same pair would sing over itself for ${vb.clashMs} ms (exit ${blind.exitPointMs})")
        assertTrue(vb.clashMs > 0)
    }

    @Test
    fun anUnavoidableSecondVoiceEndsTheOverlap() {
        // both sing nearly all the way (under the 90 % a detection is trusted to), except the incoming's first 8 s (4 bars):
        // the overlap must end there
        val out = analysis("out", vocals = listOf(TimeRange(12_000, 101_000)))
        val inc = analysis("in", vocals = listOf(TimeRange(8_000, 97_000)))
        val plan = planner.plan(out, inc, settings)
        println("unavoidable: ${plan.reason}")
        assertEquals(PlanKind.BEAT_MATCHED, plan.kind)
        assertTrue(plan.overlapMs <= 8_000, "overlap ${plan.overlapMs}")
        assertTrue("overlap ended before the second voice" in plan.reason)
    }

    /** A second voice 1 s into the overlap no longer cuts it to a single bar: half of what was asked, at least 4 bars. */
    @Test
    fun anEarlySecondVoiceKeepsAtLeastFourBars() {
        val out = analysis("out", vocals = listOf(TimeRange(12_000, 101_000)))
        val inc = analysis("in", vocals = listOf(TimeRange(1_000, 90_000)))
        val plan = planner.plan(out, inc, settings)
        println("early voice: ${plan.reason}")
        assertEquals(PlanKind.BEAT_MATCHED, plan.kind)
        assertTrue(plan.overlapMs >= 4 * 2_000 - 50, "overlap ${plan.overlapMs}")
    }

    /** A detector that calls 90 % or more of a track voice is not believed: the plan is the one without vocals. */
    @Test
    fun voiceAllOverTheTrackIsIgnored() {
        val a = planner.plan(analysis("out", vocals = listOf(TimeRange(0, 100_000))), analysis("in", vocals = listOf(TimeRange(0, 100_000))), settings)
        val b = planner.plan(analysis("out"), analysis("in"), settings)
        assertEquals(b.exitPointMs, a.exitPointMs)
        assertEquals(b.overlapMs, a.overlapMs)
    }

    @Test
    fun noVocalsFoundChangesNothing() {
        val a = planner.plan(analysis("out", vocals = emptyList()), analysis("in", vocals = emptyList()), settings)
        val b = planner.plan(analysis("out"), analysis("in"), settings)
        assertEquals(b.exitPointMs, a.exitPointMs)
        assertEquals(b.entryPointMs, a.entryPointMs)
        assertEquals(b.overlapMs, a.overlapMs)
    }

    /** 120 bpm, 200 beats, bars of 4, phrases every 16 beats; [vocals] null = no detection. */
    private fun analysis(id: String, vocals: List<TimeRange>? = null): TrackAnalysis {
        val beats = List(200) { it * 500 }
        val downs = List(50) { it * 4 }
        val dur = beats.last() + 1500L
        return TrackAnalysis(
            videoId = id, analyzerId = "custom", analyzedAtEpochMs = 0, durationMs = dur,
            bpm = Confident(120f, 0.9f), beatTimesMs = Confident(beats, 0.9f), downbeatBeatIndices = Confident(downs, 0.9f),
            beatsPerBar = 4, phraseStartsMs = downs.filter { it % 16 == 0 }.map { beats[it].toLong() }, key = null,
            energyHopMs = 100, energy = List((dur / 100).toInt() + 1) { 0.6f }, lowBandEnergy = List((dur / 100).toInt() + 1) { 0.5f },
            sections = null, vocals = vocals?.let { Confident(it, 0.85f) }, loudnessDb = -14f,
        )
    }
}
