package org.simpmusic.dj.android.window

import kotlin.math.abs

/**
 * Self-calibrating estimates of two device-specific latencies, kept for the life of the process.
 *
 *  - [startLatencyMs]: how long after `play()` a paused, prepared deck actually produces its first
 *    sample (audio pipeline warm-up). Used to issue `play()` early.
 *  - [seekBiasMs]: how far the follower lags the position a seek targeted, by the time the seek has
 *    completed and audio flows again. Added to every corrective seek so the next measurement starts
 *    near zero instead of at minus-the-seek-latency.
 *
 * Both are exponential moving averages of what was actually observed, clamped to sane bounds, so a
 * single bad measurement cannot wreck them.
 */
class LatencyCalibrator(
    startLatencyMs: Double = 120.0,
    seekBiasMs: Double = 80.0,
) {
    var startLatencyMs: Double = startLatencyMs
        private set
    var seekBiasMs: Double = seekBiasMs
        private set

    /** [observedLagMs] = how far the deck's position was behind where it should have been right after starting. */
    fun observeStart(observedLagMs: Double) {
        // The lag we saw is (latency - the head start we gave); the true latency is head start + lag.
        startLatencyMs = ema(startLatencyMs, startLatencyMs + observedLagMs, 0.4).coerceIn(0.0, 600.0)
    }

    /** [residualMs] = follower - reference measured after a seek that used the current bias. */
    fun observeSeek(residualMs: Double) {
        seekBiasMs = ema(seekBiasMs, seekBiasMs - residualMs, 0.5).coerceIn(-200.0, 800.0)
    }

    private fun ema(old: Double, sample: Double, alpha: Double) = old + alpha * (sample - old)
}

/**
 * Closed-loop lock of a *follower* deck onto a *reference* deck that carry identical audio.
 *
 * The follower is silent while this runs, so it can use hard seeks (which flush the audio pipeline and
 * would click if audible) instead of rate nudges: measure the offset, seek by minus that offset,
 * wait for the pipeline to settle, measure again, until within tolerance. Position signals jitter,
 * so an offset is the MEDIAN over a measurement window rather than one reading.
 *
 * Pure state machine: feed it (clock, measured error) once per control tick.
 */
class AlignmentLoop(
    private val calibrator: LatencyCalibrator,
    private val toleranceMs: Double = WindowTuning.LOCK_TOLERANCE_MS,
    private val warmupMs: Double = WindowTuning.LOCK_MEASURE_FROM_MS.toDouble(),
    private val measureMs: Double = 160.0,
    private val settleAfterSeekMs: Double = 260.0,
    private val maxSeeks: Int = 3,
    private val minSamples: Int = 6,
) {
    enum class State { WARMUP, MEASURING, SETTLING, LOCKED, FAILED }

    sealed interface Step {
        data object None : Step

        /** Move the follower by this many ms (already includes the learned seek bias). */
        data class SeekBy(val deltaMs: Double) : Step

        data object Locked : Step

        data object Failed : Step
    }

    var state = State.WARMUP
        private set

    /** Median offset of the last completed measurement (follower - reference, ms); NaN before the first. */
    var lastErrorMs = Double.NaN
        private set

    var seeksUsed = 0
        private set

    /** The very first measured offset, before any correction: feeds the start-latency calibration. */
    var firstErrorMs = Double.NaN
        private set

    private var startedAtMs = Double.NaN
    private var phaseStartMs = 0.0
    private val samples = ArrayList<Double>()
    private var lastSeekWasMine = false

    fun start(nowMs: Double) {
        startedAtMs = nowMs
        phaseStartMs = nowMs
        state = State.WARMUP
        samples.clear()
    }

    /** Called every control tick with the current instantaneous error estimate. */
    fun update(nowMs: Double, errorMs: Double): Step {
        when (state) {
            State.LOCKED -> return Step.Locked
            State.FAILED -> return Step.Failed
            State.WARMUP -> {
                if (nowMs - phaseStartMs >= warmupMs) {
                    state = State.MEASURING
                    phaseStartMs = nowMs
                    samples.clear()
                }
                return Step.None
            }
            State.SETTLING -> {
                if (nowMs - phaseStartMs >= settleAfterSeekMs) {
                    state = State.MEASURING
                    phaseStartMs = nowMs
                    samples.clear()
                }
                return Step.None
            }
            State.MEASURING -> {
                samples.add(errorMs)
                if (nowMs - phaseStartMs < measureMs || samples.size < minSamples) return Step.None
                val median = samples.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
                lastErrorMs = median
                if (firstErrorMs.isNaN()) firstErrorMs = median
                if (lastSeekWasMine) calibrator.observeSeek(median)
                if (abs(median) <= toleranceMs) {
                    state = State.LOCKED
                    return Step.Locked
                }
                if (seeksUsed >= maxSeeks) {
                    state = State.FAILED
                    return Step.Failed
                }
                seeksUsed++
                lastSeekWasMine = true
                state = State.SETTLING
                phaseStartMs = nowMs
                return Step.SeekBy(-median + calibrator.seekBiasMs)
            }
        }
    }
}
