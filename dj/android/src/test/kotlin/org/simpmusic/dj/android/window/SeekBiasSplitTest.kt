package org.simpmusic.dj.android.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window deck (a local WAV) and a live deck (a YouTube stream) lag a seek by very different amounts. Each mix runs a
 * lock-out on the window and then a lock-in on the live deck; with one shared bias every loop started from the other
 * deck's lag. Simulated here with a follower whose error after `SeekBy(d)` is `error + d - lag`.
 */
class SeekBiasSplitTest {
    private fun runLoop(cal: LatencyCalibrator, target: SeekTarget, lagMs: Double, initialErrorMs: Double): AlignmentLoop {
        val loop = AlignmentLoop(cal, target = target)
        var err = initialErrorMs
        var t = 0.0
        loop.start(t)
        while (t < 5000) {
            when (val s = loop.update(t, err)) {
                is AlignmentLoop.Step.SeekBy -> err += s.deltaMs - lagMs
                AlignmentLoop.Step.Locked, AlignmentLoop.Step.Failed -> return loop
                else -> Unit
            }
            t += 16.0
        }
        return loop
    }

    @Test
    fun windowAndLiveLagsAreLearnedSeparatelyAndLockInOneSeekOnceLearned() {
        val cal = LatencyCalibrator()
        repeat(4) { mix ->
            val out = runLoop(cal, SeekTarget.WINDOW, lagMs = 40.0, initialErrorMs = -90.0)
            val inc = runLoop(cal, SeekTarget.LIVE, lagMs = 220.0, initialErrorMs = 120.0)
            println("mix $mix: lock-out ${out.state} in ${out.seeksUsed} seeks, lock-in ${inc.state} in ${inc.seeksUsed} seeks | $cal")
            assertEquals(AlignmentLoop.State.LOCKED, out.state)
            assertEquals(AlignmentLoop.State.LOCKED, inc.state)
            if (mix > 0) assertTrue("learned lags should lock with one seek", out.seeksUsed == 1 && inc.seeksUsed == 1)
        }
        assertEquals(40.0, cal.windowSeekBiasMs, 3.0)
        assertEquals(220.0, cal.seekBiasMs, 3.0)
    }
}
