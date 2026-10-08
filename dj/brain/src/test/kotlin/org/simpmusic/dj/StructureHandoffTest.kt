package org.simpmusic.dj

import org.simpmusic.dj.analysis.VocalClash
import org.simpmusic.dj.model.*
import org.simpmusic.dj.planner.DjTransitionPlanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The owner's sweet spot: the outgoing's instrumental after a chorus over the incoming's intro, voice handed to voice. */
class StructureHandoffTest {
    private val planner = DjTransitionPlanner()
    private val settings = DjSettings(enabled = true)

    @Test
    fun theIncomingsFirstVoiceLandsWhereTheOutgoingsWouldHaveReturned() {
        // 120 bpm, bars of 2 s. Outgoing: sings 4..96 s, instrumental bridge 96..112 s (8 bars), sings again from 112 s (past the minimum played).
        // Incoming: instrumental intro 0..16 s (8 bars), first voice at 16 s.
        val out = analysis("out", 300, listOf(TimeRange(4_000, 96_000), TimeRange(112_000, 140_000)))
        val inc = analysis("in", 300, listOf(TimeRange(16_000, 140_000)))
        val plan = planner.plan(out, inc, settings)
        println("structure: ${plan.reason}")
        assertEquals(PlanKind.BEAT_MATCHED, plan.kind)
        assertTrue(plan.reason.startsWith("STRUCTURE"), plan.reason)
        assertEquals(96_000, plan.exitPointMs, "the mix starts where the chorus ends")
        assertEquals(0, plan.entryPointMs, "the incoming plays its whole intro")
        assertEquals(16_000, plan.overlapMs, "8 bars: the outgoing is gone exactly when its voice would have come back")
        val v = VocalClash.of(VocalClash.ranges(out), plan.exitPointMs, 1.0, VocalClash.ranges(inc), plan.entryPointMs, 1.0, plan.overlapMs)!!
        assertEquals(0, v.clashMs)
    }

    @Test
    fun aShortIntroGivesAShorterHandoffEndingOnTheSameBars() {
        // the incoming sings after 4 bars: a 4-bar overlap ending at the outgoing's 112 s, starting at 104 s
        val out = analysis("out", 300, listOf(TimeRange(4_000, 96_000), TimeRange(112_000, 140_000)))
        val inc = analysis("in", 300, listOf(TimeRange(8_000, 140_000)))
        val plan = planner.plan(out, inc, settings)
        println("short intro: ${plan.reason}")
        assertTrue(plan.reason.startsWith("STRUCTURE"), plan.reason)
        assertEquals(104_000, plan.exitPointMs)
        assertEquals(0, plan.entryPointMs)
        assertEquals(8_000, plan.overlapMs)
    }

    @Test
    fun aShoutInTheIntroDoesNotEndIt() {
        // "¡hola amigo!" for 1.5 s at 4 s does not count as the incoming's first voice
        val out = analysis("out", 300, listOf(TimeRange(4_000, 96_000), TimeRange(112_000, 140_000)))
        val inc = analysis("in", 300, listOf(TimeRange(4_000, 5_500), TimeRange(16_000, 140_000)))
        val plan = planner.plan(out, inc, settings)
        assertTrue(plan.reason.startsWith("STRUCTURE"), plan.reason)
        assertEquals(16_000, plan.overlapMs)
    }

    @Test
    fun anIncomingThatSingsAtOnceLandsOnItsOwnBreak() {
        // the owner's "Me Enamoré": sings from the start, but has a 4-bar instrumental at 30..38 s and then phrase 1 again.
        // Both instrumentals overlap; the incoming's phrase 1 (38 s) lands where the outgoing's voice would return (112 s).
        val out = analysis("out", 300, listOf(TimeRange(4_000, 96_000), TimeRange(112_000, 140_000)))
        val inc = analysis("in", 300, listOf(TimeRange(500, 30_000), TimeRange(38_000, 140_000)))
        val plan = planner.plan(out, inc, settings)
        println("own break: ${plan.reason}")
        assertTrue(plan.reason.startsWith("STRUCTURE") && "own instrumental break" in plan.reason, plan.reason)
        assertEquals(104_000, plan.exitPointMs)
        assertEquals(30_000, plan.entryPointMs)
        assertEquals(8_000, plan.overlapMs)
    }

    @Test
    fun anIncomingThatSingsAtOnceStartsOverTheOutgoingsInstrumental() {
        // no intro: the incoming sings from 0.5 s; the hand-off lays its first bars over the outgoing's 8-bar bridge
        val out = analysis("out", 300, listOf(TimeRange(4_000, 96_000), TimeRange(112_000, 140_000)))
        val inc = analysis("in", 300, listOf(TimeRange(500, 140_000)))
        val plan = planner.plan(out, inc, settings)
        println("sings at once: ${plan.reason}")
        assertTrue(plan.reason.startsWith("STRUCTURE"), plan.reason)
        assertEquals(96_000, plan.exitPointMs)
        assertEquals(0, plan.entryPointMs)
        val v = VocalClash.of(VocalClash.ranges(out), plan.exitPointMs, 1.0, VocalClash.ranges(inc), plan.entryPointMs, 1.0, plan.overlapMs)!!
        assertEquals(0, v.clashMs)
    }

    @Test
    fun withoutVocalsNothingChanges() {
        val a = planner.plan(analysis("out", 300, null), analysis("in", 300, null), settings)
        assertTrue(!a.reason.startsWith("STRUCTURE"))
    }

    private fun analysis(id: String, n: Int, vocals: List<TimeRange>?): TrackAnalysis {
        val beats = List(n) { it * 500 }
        val downs = List(n / 4) { it * 4 }
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
