package org.simpmusic.dj.android.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.simpmusic.dj.android.fakePlan
import kotlin.math.abs

class TransitionControllerTest {
    private val plan = fakePlan()
    private val timeline = WindowTimeline.build(plan)
    private val userVol = 0.8f

    private class Rig(
        val clock: FakeClock,
        val outgoing: FakeDeck,
        val window: FakeDeck,
        val host: RecordingHost,
        val controller: TransitionController,
    ) {
        var incomingDeck: FakeDeck? = null

        fun run(maxMs: Double, until: () -> Boolean) {
            val end = clock.now + maxMs
            while (clock.now < end && !until()) {
                clock.now += 10.0
                controller.tick()
            }
        }
    }

    private fun rig(
        windowStartLatency: Double = 140.0,
        jitter: Double = 3.0,
        seekLatency: Double = 70.0,
        seekLandingError: Double = 0.0,
        incomingReadyDelay: Double = 300.0,
        calibrator: LatencyCalibrator = LatencyCalibrator(),
        headStartMs: Double = 0.0, // how far the window starts behind/ahead of the live deck at trigger time
    ): Rig {
        val clock = FakeClock()
        val w0 = timeline.outgoingSourceAtWindowStart
        // live outgoing has been playing; at "now" it is exactly at window-start source position.
        val outgoing = FakeDeck("live-out", clock, w0 + headStartMs, jitterMs = jitter, startPlaying = true, seed = 11).also { it.volume = userVol }
        val window = FakeDeck("window", clock, 0.0, startLatencyMs = windowStartLatency, seekLatencyMs = seekLatency, jitterMs = jitter, seed = 22, seekLandingErrorMs = seekLandingError)
        lateinit var rig: Rig
        val host =
            RecordingHost(clock) { seekSource ->
                FakeDeck("live-in", clock, seekSource.toDouble(), startLatencyMs = 90.0, seekLatencyMs = seekLatency, jitterMs = jitter, readyAtMs = clock.now + incomingReadyDelay, seed = 33, seekLandingErrorMs = seekLandingError)
                    .also { rig.incomingDeck = it }
            }
        val controller = TransitionController(timeline, outgoing, window, host, clock, { userVol }, calibrator)
        rig = Rig(clock, outgoing, window, host, controller)
        return rig
    }

    @Test
    fun timelineIntegratesRateLanes() {
        // Outgoing rate glides from 1.0 (t=-4000) to 1.04 (t=0) then holds: source at T0 is exactly exitPoint.
        assertEquals(plan.exitPointMs.toDouble(), timeline.outgoingSourceAt(0.0), 1e-6)
        // Before T0 the outgoing consumed LESS source than wall time was spent (rate < 1.04 average)...
        val at = timeline.outgoingSourceAt(-4000.0)
        assertTrue("source ${plan.exitPointMs - at} ms consumed over 4 s", (plan.exitPointMs - at) in 4000.0..4160.0)
        // ...and the mapping is invertible.
        val w = 3210.0
        assertEquals(w, timeline.windowOfOutgoingSource(timeline.outgoingSourceOfWindow(w)), 0.05)
        // Incoming starts at the entry point at T0 and advances ~1:1.
        assertEquals(plan.entryPointMs.toDouble(), timeline.incomingSourceAt(0.0), 1e-6)
        assertEquals(plan.entryPointMs + 10_000.0, timeline.incomingSourceAt(10_000.0), 10_000.0 * 0.06)
    }

    @Test
    fun windowLayoutRespectsTheHandoffBudget() {
        assertEquals(plan.preRollMs - WindowTuning.LEAD_IN_MS, timeline.startRelMs)
        assertTrue(timeline.xfadeOutWindowMs + WindowTuning.XFADE_MS < WindowTuning.LEAD_IN_MS)
        assertTrue(timeline.windowMs > timeline.xfadeInWindowMs + WindowTuning.XFADE_MS + 500)
        // Lanes are constant from settledRel on.
        val t = timeline.settledRelMs.toDouble()
        assertEquals(1f, plan.incoming.rate.valueAt(t), 1e-6f)
    }

    @Test
    fun eligibilityRejectsPlansTheLivePlayersCannotTakeOverFrom() {
        assertTrue(WindowTimeline.check(plan) is Eligibility.Ok)
        assertTrue(WindowTimeline.check(fakePlan(inRate = 1.0f, rampBackMs = 0).copy(kind = org.simpmusic.dj.model.PlanKind.SIMPLE_CROSSFADE)) is Eligibility.Rejected)
        // incoming never returns to rate 1.0
        val bent = plan.copy(incoming = plan.incoming.copy(rate = org.simpmusic.dj.model.ParamCurve.constant(1.05f)))
        assertTrue(WindowTimeline.check(bent) is Eligibility.Rejected)
        // outgoing not plain at window start
        val fast = plan.copy(outgoing = plan.outgoing.copy(rate = org.simpmusic.dj.model.ParamCurve.constant(1.05f)))
        assertTrue(WindowTimeline.check(fast) is Eligibility.Rejected)
    }

    @Test
    fun phaseLocksThroughStartLatencyAndJitterAndFinishes() {
        val r = rig()
        r.controller.start()
        var maxHandoffErr = 0.0
        val err = { abs(timeline.outgoingSourceOfWindow(r.window.truePositionMs) - r.outgoing.truePositionMs) }
        var seenXfadeOut = false
        r.run(60_000.0) {
            if (r.controller.phase == TransitionController.Phase.XFADE_OUT && !seenXfadeOut) {
                seenXfadeOut = true
                maxHandoffErr = err()
                // gain sum is 1 (correlated content): checked tick by tick below
            }
            if (r.controller.phase == TransitionController.Phase.XFADE_OUT) {
                assertEquals(userVol.toDouble(), (r.outgoing.volume + r.window.volume).toDouble(), 1e-3)
            }
            r.controller.isFinished
        }
        assertTrue("finished: phase=${r.controller.phase} failed=${r.host.failedReason}", r.host.finished)
        assertTrue("live->window misalignment ${"%.1f".format(maxHandoffErr)} ms", maxHandoffErr <= 8.0)
        val lockedIn = r.host.events.filterIsInstance<TransitionEvent.LockedIn>().single()
        assertFalse("hand-off must not be forced when the lock converges", lockedIn.forced)
        // Ground truth at the end: live incoming carries exactly the audio the window did.
        val inc = r.incomingDeck!!
        assertTrue(inc.isPlaying)
        assertTrue(r.window.released)
        assertEquals(userVol, inc.volume, 1e-3f)
        assertTrue("outgoing paused", r.outgoing.pauseCount >= 1)
        assertNotNull(r.host.committedSeek)
        // Both hand-offs were logged with their residuals.
        val hand = r.host.events.filterIsInstance<TransitionEvent.Handoff>()
        assertEquals(listOf("live->window", "window->live"), hand.map { it.name })
        hand.forEach { assertTrue("${it.name} residual ${it.residualMs}", abs(it.residualMs) <= 8.0) }
    }

    @Test
    fun secondHandoffIsAlignedInTrueTime() {
        val r = rig(jitter = 4.0, seekLatency = 110.0)
        r.controller.start()
        var trueErrAtXfadeIn = Double.NaN
        r.run(60_000.0) {
            if (r.controller.phase == TransitionController.Phase.XFADE_IN && trueErrAtXfadeIn.isNaN()) {
                val w = r.window.truePositionMs
                trueErrAtXfadeIn = r.incomingDeck!!.truePositionMs - timeline.incomingSourceOfWindow(w)
            }
            r.controller.isFinished
        }
        assertTrue(r.host.finished)
        assertTrue("window->live misalignment ${"%.1f".format(trueErrAtXfadeIn)} ms", abs(trueErrAtXfadeIn) <= 10.0)
    }

    @Test
    fun calibratorLearnsStartLatencyAndSeekBias() {
        val cal = LatencyCalibrator(startLatencyMs = 20.0, seekBiasMs = 0.0)
        val first = rig(windowStartLatency = 180.0, seekLatency = 120.0, calibrator = cal)
        first.controller.start()
        first.run(60_000.0) { first.controller.isFinished }
        assertTrue(first.host.finished)
        assertTrue("start latency estimate moved toward 180: ${cal.startLatencyMs}", cal.startLatencyMs > 60.0)
        assertTrue("seek bias moved toward the seek latency: $cal", maxOf(cal.seekBiasMs, cal.windowSeekBiasMs) > 40.0)
    }

    @Test
    fun windowStartingBehindAndAheadBothConverge() {
        for (head in listOf(-220.0, 180.0)) {
            val r = rig(headStartMs = head)
            r.controller.start()
            r.run(60_000.0) { r.controller.isFinished }
            assertTrue("head=$head phase=${r.controller.phase} failed=${r.host.failedReason}", r.host.finished)
        }
    }

    @Test
    fun abortBeforeCommitLeavesTheOutgoingDeckUntouched() {
        val r = rig()
        r.controller.start()
        r.run(500.0) { false }
        assertEquals(TransitionController.Phase.LOCK_OUT, r.controller.phase)
        val result = r.controller.abort()
        assertFalse(result.committed)
        assertNull(result.incomingPositionMs)
        assertTrue(r.window.released)
        assertTrue(r.outgoing.isPlaying)
        assertEquals(userVol, r.outgoing.volume, 1e-6f)
        assertNull(r.host.committedSeek)
        assertTrue(r.controller.isFinished)
        r.controller.tick() // no-op after abort
        // idempotent
        assertFalse(r.controller.abort().committed)
    }

    @Test
    fun userPausingTheOutgoingDeckDuringLockOutEndsTheTransitionBeforeCommit() {
        val r = rig()
        r.controller.start()
        r.run(600.0) { false }
        r.outgoing.pause()
        r.run(2000.0) { r.controller.isFinished }
        assertTrue(r.controller.isFinished)
        assertEquals("outgoing stopped", r.host.failedReason)
        assertFalse(r.host.failedResult!!.committed)
        assertNull(r.host.committedSeek)
        assertTrue(r.window.released)
    }

    @Test
    fun abortInsideTheWindowCommitsTheIncomingAtTheMappedPosition() {
        val r = rig()
        r.controller.start()
        // run into the WINDOW phase, then well past T0
        r.run(30_000.0) { r.controller.phase == TransitionController.Phase.WINDOW && r.controller.windowPositionMs() > timeline.windowTimeOfPlan(6000.0) }
        assertEquals(TransitionController.Phase.WINDOW, r.controller.phase)
        val expected = timeline.incomingSourceAt(timeline.planTimeOfWindow(r.window.truePositionMs))
        val result = r.controller.abort()
        assertTrue(result.committed)
        assertFalse(result.incomingPlaying)
        assertEquals(expected, result.incomingPositionMs!!.toDouble(), 40.0)
        assertTrue(r.window.released)
        assertTrue("outgoing silent", r.outgoing.volume <= 1e-3f || !r.outgoing.isPlaying)
        assertEquals(userVol, r.incomingDeck!!.volume, 1e-6f)
    }

    @Test
    fun abortDuringSilentLockLeavesIncomingPlayingAtFullVolume() {
        val r = rig()
        r.controller.start()
        r.run(60_000.0) { r.controller.phase == TransitionController.Phase.LOCK_IN }
        r.run(200.0) { false }
        val result = r.controller.abort()
        assertTrue(result.committed)
        assertTrue(result.incomingPlaying)
        assertEquals(userVol, r.incomingDeck!!.volume, 1e-6f)
        assertTrue(r.window.released)
    }

    @Test
    fun uiPositionFollowsMappedWindowThenTheLiveDeck() {
        val r = rig()
        r.controller.start()
        r.run(500.0) { false }
        assertNull("before commit the incoming is not the current track", r.controller.uiPositionMs())
        var frozenAtEntry = false
        var advanced = false
        r.run(60_000.0) {
            val p = r.controller.uiPositionMs()
            if (r.controller.phase == TransitionController.Phase.WINDOW && p != null) {
                val t = timeline.planTimeOfWindow(r.controller.windowPositionMs())
                if (t < -500) {
                    assertEquals(plan.entryPointMs, p)
                    frozenAtEntry = true
                }
                if (t > 4000) {
                    advanced = true
                    assertEquals(timeline.incomingSourceAt(t), p.toDouble(), 30.0)
                }
            }
            r.controller.isFinished
        }
        assertTrue(frozenAtEntry)
        assertTrue(advanced)
    }

    @Test
    fun lockOutFailureAbortsCleanlyBeforeAnythingAudibleChanged() {
        // Seeks land 400 ms off, so the loop can never converge inside its budget.
        val r = rig(seekLandingError = 400.0)
        r.controller.start()
        r.run(20_000.0) { r.controller.isFinished }
        assertTrue(r.controller.isFinished)
        assertNotNull(r.host.failedReason)
        assertFalse(r.host.failedResult!!.committed)
        assertNull(r.host.committedSeek)
        assertTrue(r.window.released)
        assertTrue(r.outgoing.isPlaying)
        assertEquals(userVol, r.outgoing.volume, 1e-6f)
        assertFalse(r.host.finished)
    }

    @Test
    fun incomingThatNeverGetsReadyFailsOverToTheHostInsteadOfGoingSilent() {
        val r = rig(incomingReadyDelay = 10_000_000.0)
        r.controller.start()
        r.run(120_000.0) { r.controller.isFinished }
        assertTrue(r.controller.isFinished)
        assertNotNull(r.host.failedReason)
        assertTrue(r.host.failedResult!!.committed)
        assertFalse(r.host.finished)
        assertTrue(r.window.released)
    }

    @Test
    fun exceptionInADeckIsContainedAndAbortsCleanly() {
        val clock = FakeClock()
        val out = FakeDeck("out", clock, timeline.outgoingSourceAtWindowStart, startPlaying = true)
        val boomWindow =
            object : Deck by FakeDeck("w", clock, 0.0) {
                override fun seekTo(ms: Long) = throw IllegalStateException("boom")
            }
        val host = RecordingHost(clock) { null }
        val c = TransitionController(timeline, out, boomWindow, host, clock, { 1f })
        c.start()
        repeat(400) {
            clock.now += 10
            c.tick()
        }
        // Either the controller never needed the seek (no failure) or it contained it: never an escaped exception.
        assertTrue(c.isFinished || c.phase == TransitionController.Phase.LOCK_OUT)
    }

    @Test
    fun positionEstimatorRejectsJitter() {
        val est = PositionEstimator()
        val rnd = kotlin.random.Random(5)
        var worst = 0.0
        for (i in 0 until 200) {
            val now = 1000.0 + i * 10
            val truth = 5000.0 + i * 10
            val raw = truth + (rnd.nextDouble() * 2 - 1) * 8
            est.add(now, raw)
            if (i > 20) worst = maxOf(worst, abs(est.estimate(now, raw) - truth))
        }
        assertTrue("worst estimation error $worst ms (raw jitter +-8)", worst < 4.0)
    }
}
