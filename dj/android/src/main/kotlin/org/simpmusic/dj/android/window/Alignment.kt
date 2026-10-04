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
    // First guess of the follower's lag after a hard seek. 250 ms came from device lock-outs that measured during Media3's
    // post-seek position smoothing, i.e. from an artifact; 120 ms is a middle guess, learned per deck kind after one seek.
    seekBiasMs: Double = 120.0,
) {
    var startLatencyMs: Double = startLatencyMs
        private set
    var seekBiasMs: Double = seekBiasMs
        private set

    /**
     * The same lag for the WINDOW deck (a local WAV), learned separately: it seeks very differently from a live deck
     * streaming YouTube audio, and one shared estimate was dragged back and forth by the two (a device log showed
     * 34 -> 197 -> 61 -> 200 ms across consecutive lock-outs and lock-ins, with lock-outs failing at 53-111 ms).
     */
    var windowSeekBiasMs: Double = seekBiasMs
        private set

    fun seekBias(target: SeekTarget): Double = if (target == SeekTarget.WINDOW) windowSeekBiasMs else seekBiasMs

    /**
     * Called after every learned change. The values describe this phone's audio path, not one session, so they are
     * saved and handed back through [restore] on the next start: a device log showed the FIRST mix after an app start
     * failing on the defaults (bias 120 ms) while every later one locked on the learned values.
     */
    @Volatile var onChanged: ((LatencyCalibrator) -> Unit)? = null

    /** Values saved by an earlier process; anything outside the learnable range is ignored. */
    fun restore(startLatencyMs: Double, seekBiasMs: Double, windowSeekBiasMs: Double) {
        if (startLatencyMs.isFinite() && startLatencyMs in 0.0..600.0) this.startLatencyMs = startLatencyMs
        if (seekBiasMs.isFinite() && seekBiasMs in -200.0..800.0) this.seekBiasMs = seekBiasMs
        if (windowSeekBiasMs.isFinite() && windowSeekBiasMs in -200.0..800.0) this.windowSeekBiasMs = windowSeekBiasMs
    }

    /** [observedLagMs] = how far the deck's position was behind where it should have been right after starting. */
    fun observeStart(observedLagMs: Double) {
        // The lag we saw is (latency - the head start we gave); the true latency is head start + lag.
        startLatencyMs = ema(startLatencyMs, startLatencyMs + observedLagMs, 0.4).coerceIn(0.0, 600.0)
        onChanged?.invoke(this)
    }

    /** [residualMs] = follower - reference measured after a seek that used the current bias. */
    fun observeSeek(residualMs: Double, target: SeekTarget = SeekTarget.LIVE) {
        // After a seek by (-error + bias) the new residual is exactly (bias - trueLag), so nearly the whole residual can be
        // corrected at once. A learning rate of 0.5 halved the error per seek, and three seeks were not enough to lock.
        if (target == SeekTarget.WINDOW) {
            windowSeekBiasMs = ema(windowSeekBiasMs, windowSeekBiasMs - residualMs, 0.85).coerceIn(-200.0, 800.0)
        } else {
            seekBiasMs = ema(seekBiasMs, seekBiasMs - residualMs, 0.85).coerceIn(-200.0, 800.0)
        }
        onChanged?.invoke(this)
    }

    override fun toString(): String = "startLatency=%.0f ms seekBias live=%.0f window=%.0f ms".format(startLatencyMs, seekBiasMs, windowSeekBiasMs)

    private fun ema(old: Double, sample: Double, alpha: Double) = old + alpha * (sample - old)
}

/** Which kind of deck a corrective seek moves: the pre-rendered window (local WAV) or a live streaming deck. */
enum class SeekTarget { WINDOW, LIVE }

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
    private val measureMs: Double = 300.0,
    private val settleAfterSeekMs: Double = 260.0,
    private val maxSeeks: Int = 3,
    private val minSamples: Int = 6,
    private val target: SeekTarget = SeekTarget.LIVE,
    private val log: (String) -> Unit = {},
    /**
     * A measurement is only trusted once the error has stopped moving: Media3 runs the REPORTED position of a deck that
     * was just started or seeked up to 10 % fast or slow for up to a second while it hands over from the playhead to
     * the AudioTimestamp, so an offset measured then is a reporting artifact (the device's sign-flipping 50-110 ms
     * residuals). Max |d(error)/dt| accepted, in ms per ms.
     */
    private val maxSlope: Double = 0.004,
    /** Give up waiting for a steady signal after this long in one measurement and use what there is. */
    private val maxSteadyWaitMs: Double = 3500.0,
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
    private val sampleTimes = ArrayList<Double>()
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
                    sampleTimes.clear()
                }
                return Step.None
            }
            State.SETTLING -> {
                if (nowMs - phaseStartMs >= settleAfterSeekMs) {
                    state = State.MEASURING
                    phaseStartMs = nowMs
                    samples.clear()
                    sampleTimes.clear()
                }
                return Step.None
            }
            State.MEASURING -> {
                samples.add(errorMs)
                sampleTimes.add(nowMs)
                // keep a sliding window of the last measureMs
                while (sampleTimes.size > minSamples && nowMs - sampleTimes.first() > measureMs) {
                    sampleTimes.removeAt(0)
                    samples.removeAt(0)
                }
                if (nowMs - phaseStartMs < measureMs || samples.size < minSamples) return Step.None
                val slope = slopeOf(sampleTimes, samples)
                if (abs(slope) > maxSlope && nowMs - phaseStartMs < maxSteadyWaitMs) return Step.None
                val steady = abs(slope) <= maxSlope
                if (!steady) log("lock ${target.name.lowercase()}: signal still moving (%.3f ms/ms) after %.0f ms, measuring anyway".format(slope, nowMs - phaseStartMs))
                val median = samples.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
                lastErrorMs = median
                if (firstErrorMs.isNaN()) firstErrorMs = median
                if (lastSeekWasMine) calibrator.observeSeek(median, target)
                log("lock ${target.name.lowercase()}: measured %.1f ms (median of %d, steady after %.0f ms) after %d seeks, bias %.0f ms".format(median, samples.size, nowMs - phaseStartMs, seeksUsed, calibrator.seekBias(target)))
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
                return Step.SeekBy(-median + calibrator.seekBias(target))
            }
        }
    }

    private fun slopeOf(t: List<Double>, y: List<Double>): Double {
        val n = t.size
        if (n < 2) return 0.0
        val mt = t.average()
        val my = y.average()
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            num += (t[i] - mt) * (y[i] - my)
            den += (t[i] - mt) * (t[i] - mt)
        }
        return if (den <= 0.0) 0.0 else num / den
    }
}
