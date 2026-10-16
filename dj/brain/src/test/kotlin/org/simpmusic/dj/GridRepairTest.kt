package org.simpmusic.dj

import org.simpmusic.dj.analysis.GridRepair
import org.simpmusic.dj.model.*
import org.simpmusic.dj.planner.DjTransitionPlanner
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GridRepairTest {
    private fun analysis(beats: List<Int>, downs: List<Int>, perBar: Int = 4, id: String = "t"): TrackAnalysis {
        val dur = beats.last() + 1500L
        return TrackAnalysis(
            videoId = id, analyzerId = "custom", analyzedAtEpochMs = 0, durationMs = dur,
            bpm = Confident(60000f * (beats.size - 1) / (beats.last() - beats.first()), 0.8f), beatTimesMs = Confident(beats, 0.8f),
            downbeatBeatIndices = Confident(downs, 0.6f), beatsPerBar = perBar, phraseStartsMs = emptyList(), key = null,
            energyHopMs = 100, energy = List((dur / 100).toInt() + 1) { 0.6f }, lowBandEnergy = List((dur / 100).toInt() + 1) { 0.5f },
            sections = null, vocals = null, loudnessDb = -14f,
        )
    }

    /**
     * The failure measured on the owner's cumbia radio, with a known truth: a 660 ms beat (90.9 bpm), 4/4. The tracker
     * drops every 7th beat for the first half, then locks onto the tresillo (beats at 0, 220, 440... thirds of the beat,
     * keeping only some), while its downbeats stay on the bars (and sometimes on half bars).
     */
    private fun brokenCumbia(): Pair<TrackAnalysis, List<Double>> {
        val beat = 660.0
        val truth = List(240) { 300 + it * beat }
        val out = ArrayList<Int>()
        val downs = ArrayList<Int>()
        for (i in 0 until 120) {
            if (i % 7 == 6) continue
            if (i % 4 == 0) downs += out.size
            out += truth[i].toInt()
        }
        for (i in 120 until 240) {
            val t = truth[i]
            if (i % 2 == 0) downs += out.size // half bars, as Beat This! often reports
            out += t.toInt()
            if (i % 3 != 2) out += (t + 2 * beat / 3).toInt() // tresillo strokes in between
        }
        return analysis(out, downs) to truth
    }

    @Test
    fun aRegularGridIsLeftAlone() {
        val beats = List(200) { 100 + it * 500 }
        val a = analysis(beats, List(50) { it * 4 })
        assertSame(a, GridRepair.repair(a))
    }

    @Test
    fun aCumbiaGridThatLosesTheBeatIsRebuiltFromTheBar() {
        val (a, truth) = brokenCumbia()
        val (r, rep) = GridRepair.repairWithReport(a)
        println("broken cumbia: ${a.bpm?.value} bpm -> ${r.bpm?.value} | ${rep.reason}")
        assertTrue(rep.repaired, rep.reason)
        assertEquals(90.9f, r.bpm!!.value, 0.5f)
        val grid = r.beatTimesMs!!.value
        // every true beat inside the grid's span has a beat within 15 ms, and the grid invents nothing else
        val inSpan = truth.filter { it >= grid.first() - 15 && it <= grid.last() + 15 }
        val hit = inSpan.count { t -> grid.any { abs(it - t) <= 15 } }
        println("true beats recovered: $hit of ${inSpan.size}, grid ${grid.size} beats")
        assertTrue(hit >= inSpan.size * 0.97, "recovered $hit of ${inSpan.size}")
        assertTrue(grid.size <= inSpan.size + 2)
        // downbeats on the true bars (every 4th true beat counted from the first)
        val downs = r.downbeatBeatIndices!!.value.map { grid[it] }
        val onBars = downs.count { d -> truth.indices.any { it % 4 == 0 && abs(truth[it] - d) <= 15 } }
        assertTrue(onBars >= downs.size * 0.9, "downbeats on bars: $onBars of ${downs.size}")
    }

    @Test
    fun theRepairedPairBeatMatchesWhereTheRawOneDidNot() {
        val (a, _) = brokenCumbia()
        val b = analysis(List(240) { 200 + it * 650 }, List(60) { it * 4 }, id = "b") // 92.3 bpm, clean
        val plan = DjTransitionPlanner().plan(a, b, DjSettings(enabled = true, maxTempoBend = 0.06f, allowEchoOut = false))
        println("broken cumbia -> clean: ${plan.kind} ${plan.reason}")
        assertEquals(PlanKind.BEAT_MATCHED, plan.kind, plan.reason)
    }

    /**
     * The owner's "El Pirata" (cumbia, ~85 bpm 4/4): Beat This! counted the tresillo's three strokes of each half bar as
     * beats, a perfectly regular grid at 460 ms (135 bpm) with a 1 every 3 of them, so the bar counter showed 3/4. The
     * repair used to leave a regular grid alone, and its lattice vote then picked 3 (64 bpm); now a bar made of doubled
     * half-bar gaps is 4/4, and the refined analysis says so.
     */
    @Test
    fun aTresilloLockedRegularGridWithThreeBeatBarsBecomesFourFour() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val a = json.decodeFromString(TrackAnalysis.serializer(), javaClass.getResource("/gridrepair/ybL05qqeVig.json")!!.readText())
        assertEquals(3, a.beatsPerBar)
        val (r, rep) = GridRepair.repairWithReport(a)
        assertTrue(rep.repaired, rep.reason)
        assertEquals(4, r.beatsPerBar)
        val bpm = r.bpm!!.value
        assertTrue(bpm in 82f..88f, "repaired tempo $bpm (${rep.reason})")
        val refined = org.simpmusic.dj.analysis.AnalysisRefiner.cached(a)
        assertEquals(4, refined.beatsPerBar)
        val d = refined.downbeatBeatIndices!!.value
        val gaps = (0 until d.size - 1).map { d[it + 1] - d[it] }
        assertTrue(gaps.count { it == 4 } >= gaps.size * 9 / 10, "bars of 4: ${gaps.groupingBy { it }.eachCount()}")
    }
}
