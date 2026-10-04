package org.simpmusic.dj

import org.simpmusic.dj.model.*
import org.simpmusic.dj.planner.DjTransitionPlanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A plan made late (the analysis arrived with little of the track left) must not lose a good exit because its tempo ramp
 * would start before the earliest allowed position: the ramp is shortened instead. A device log lost a 151 s exit that way
 * and mixed 2 bars at 97 % of the track, too short for the precise splice, so the old clock lock ran and aborted.
 */
class ShortOverlapTest {
    private val planner = DjTransitionPlanner()
    private val s = DjSettings(enabled = true, mixPoint = MixPoint.ANYWHERE, minPlayedFraction = 0f, overlapBars = 16)
    private fun track(id: String, bpm: Float) =
        FakeAnalysis.fromTruth(SyntheticTracks.render(SyntheticTracks.Spec(bpm = bpm, key = MusicalKey(9, Mode.MINOR))).truth, id)

    @Test
    fun aLatePlanShortensItsRampInsteadOfLosingTheExit() {
        val a = track("la", 124f)
        val b = track("lb", 126.5f) // a 2 % bend: a 12-beat (~5.8 s) ramp before the exit
        val free = planner.plan(a, b, s, PlanConstraints(earliestExitMs = 30_000L))
        assertEquals(PlanKind.BEAT_MATCHED, free.kind)
        val exit = free.exitPointMs
        // only 3 s between the earliest allowed position and that exit, and nothing later allowed
        val late = planner.plan(a, b, s, PlanConstraints(earliestExitMs = exit - 3_000L, latestExitMs = exit + 500L))
        println("free: exit $exit preRoll ${free.preRollMs}; late: ${late.kind} exit ${late.exitPointMs} preRoll ${late.preRollMs}: ${late.reason}")
        assertEquals(PlanKind.BEAT_MATCHED, late.kind)
        assertEquals(exit, late.exitPointMs)
        assertTrue(late.exitPointMs + late.preRollMs >= exit - 3_000L)
        assertTrue(free.preRollMs < -3_000L, "the free plan's ramp is longer than the late room")
    }
}
