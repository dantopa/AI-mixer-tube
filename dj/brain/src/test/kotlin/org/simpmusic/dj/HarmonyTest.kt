package org.simpmusic.dj

import org.simpmusic.dj.analysis.Harmony
import org.simpmusic.dj.model.*
import org.simpmusic.dj.planner.DjTransitionPlanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The local harmonic fit (TIV excess dissonance over the overlap) and how the planner uses it. */
class HarmonyTest {
    private val planner = DjTransitionPlanner()
    private val settings = DjSettings(enabled = true)

    /** A major-key chroma: tonic triad heavy, the rest of the scale lighter, over a floor as smeared as real mixes (on the owner's export a fifth apart averages 0.016, a tritone 0.148; this gives 0.021 and 0.143). */
    private fun major(root: Int): DoubleArray {
        val c = DoubleArray(12) { 0.3 }
        for ((deg, w) in listOf(0 to 1.0, 2 to 0.35, 4 to 0.8, 5 to 0.35, 7 to 0.9, 9 to 0.35, 11 to 0.25)) c[(root + deg) % 12] += w
        val s = c.sum()
        return DoubleArray(12) { c[it] / s }
    }

    private val flat = DoubleArray(12) { 1.0 / 12 }

    @Test
    fun theExcessFollowsTheCircleOfFifths() {
        val c = major(0)
        assertEquals(0.0, Harmony.excess(c, c), 1e-9, "a key with itself adds no dissonance")
        assertEquals(0.0, Harmony.excess(c, flat), 1e-9, "a flat (percussive) window is harmonically free")
        val fifth = Harmony.excess(c, major(7))
        val tritone = Harmony.excess(c, major(6))
        val semitone = Harmony.excess(c, major(1))
        println("excess: fifth %.3f, semitone %.3f, tritone %.3f".format(fifth, semitone, tritone))
        assertTrue(fifth < Harmony.COMPATIBLE, "one fifth apart is compatible (Camelot +-1): $fifth")
        assertTrue(tritone >= Harmony.CLASH, "a tritone clashes: $tritone")
        assertTrue(semitone >= Harmony.CLASH, "a semitone (7 on the wheel) clashes: $semitone")
    }

    @Test
    fun theFitFindsTheTranspositionThatHeals() {
        val out = analysis("out", 200) { major(0) }
        val inc = analysis("in", 200) { major(1) } // a semitone up: the classic +-1 st fix
        val fit = assertNotNull(Harmony.fit(Harmony.profile(out), 20_000, 16_000, Harmony.profile(inc), 10_000, 16_000))
        println(fit.describe())
        assertTrue(fit.clash)
        assertEquals(-1, fit.bestShift, "the incoming must come down one semitone")
        assertTrue(fit.bestExcess < 1e-6)
    }

    @Test
    fun noFramesNoOpinion() {
        val a = analysis("a", 200, frames = false) { major(0) }
        assertNull(Harmony.profile(a))
        assertNull(Harmony.fit(Harmony.profile(a), 0, 16_000, Harmony.profile(a), 0, 16_000))
    }

    @Test
    fun aClashingPairGetsTheOneLayerAtATimeTreatment() {
        val out = analysis("out", 200) { major(0) }
        val inc = analysis("in", 200) { major(6) }
        val plan = planner.plan(out, inc, settings)
        println("clash: ${plan.reason}")
        assertEquals(PlanKind.BEAT_MATCHED, plan.kind)
        assertTrue("harmony CLASH" in plan.reason)
        assertTrue(plan.overlapMs <= 9_000 + 2_000, "a clash is mixed short: ${plan.overlapMs}")
        // the incoming enters with its bass and mids cut, the outgoing keeps only its top after the swap
        assertTrue(plan.incoming.lowCutHz.valueAt(0) >= 800f)
        assertTrue(plan.incoming.lowCutHz.valueAt(plan.overlapMs) <= 25f)
        assertTrue(plan.outgoing.lowCutHz.valueAt(plan.overlapMs) >= 800f)
        assertEquals(20000f, plan.incoming.highCutHz.valueAt(0), "the incoming's top is never cut (the old lanes brought in only its mids)")
    }

    @Test
    fun aConsonantPairMixesAsBefore() {
        val out = analysis("out", 200) { major(0) }
        val inc = analysis("in", 200) { major(7) }
        val withFrames = planner.plan(out, inc, settings)
        val without = planner.plan(analysis("out", 200, frames = false) { major(0) }, analysis("in", 200, frames = false) { major(7) }, settings)
        println("consonant: ${withFrames.reason}")
        assertTrue("harmony ok" in withFrames.reason)
        assertEquals(without.exitPointMs, withFrames.exitPointMs)
        assertEquals(without.entryPointMs, withFrames.entryPointMs)
        assertEquals(without.overlapMs, withFrames.overlapMs)
    }

    @Test
    fun thePlannerLeavesWhileTheHarmonyStillFits() {
        // the outgoing track modulates to a tritone away 70 s in; the incoming stays in the original key. Without chroma the
        // planner may exit anywhere after the minimum played; with it, the overlap must not sit on the clashing part.
        val out = analysis("out", 200) { t -> if (t < 70_000) major(0) else major(6) }
        val inc = analysis("in", 200) { major(0) }
        val plan = planner.plan(out, inc, settings)
        val blind = planner.plan(analysis("out", 200, frames = false) { major(0) }, analysis("in", 200, frames = false) { major(0) }, settings)
        println("modulating: blind exit ${blind.exitPointMs}, with harmony ${plan.exitPointMs}: ${plan.reason}")
        assertEquals(PlanKind.BEAT_MATCHED, plan.kind)
        assertTrue("CLASH" !in plan.reason, plan.reason)
        assertTrue(plan.exitPointMs < blind.exitPointMs, "the exit moved earlier, onto the part that still fits")
    }

    /** 120 bpm, beat every 500 ms, bars of 4, phrases every 16 beats; chroma frames every 500 ms from [chroma] (time in ms). */
    private fun analysis(id: String, n: Int, frames: Boolean = true, chroma: (Long) -> DoubleArray): TrackAnalysis {
        val beats = List(n) { it * 500 }
        val downs = List(n / 4) { it * 4 }
        val dur = beats.last() + 1500L
        val hop = 500
        val f = ArrayList<Float>()
        if (frames) {
            for (i in 0 until (dur / hop).toInt()) {
                val c = chroma(i.toLong() * hop)
                for (k in 0 until 12) f += c[k].toFloat()
                repeat(TrackAnalysis.STRUCTURE_DIM - 12) { f += 0f }
            }
        }
        return TrackAnalysis(
            videoId = id, analyzerId = "custom", analyzedAtEpochMs = 0, durationMs = dur,
            bpm = Confident(120f, 0.9f), beatTimesMs = Confident(beats, 0.9f), downbeatBeatIndices = Confident(downs, 0.9f),
            beatsPerBar = 4, phraseStartsMs = downs.filter { it % 16 == 0 }.map { beats[it].toLong() }, key = null,
            energyHopMs = 100, energy = List((dur / 100).toInt() + 1) { 0.6f }, lowBandEnergy = List((dur / 100).toInt() + 1) { 0.5f },
            sections = null, vocals = null, loudnessDb = -14f,
            structureHopMs = if (frames) hop else 0, structureFrames = f,
        )
    }
}
