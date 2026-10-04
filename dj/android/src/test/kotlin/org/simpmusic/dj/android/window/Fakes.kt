package org.simpmusic.dj.android.window

import kotlin.random.Random

class FakeClock(var now: Double = 1_000.0) : DjClock {
    override fun nowMs(): Double = now
}

/**
 * A deck with a physical model: it starts producing audio [startLatencyMs] after play(), a seek freezes
 * the position for [seekLatencyMs] and lands on the target as of the moment it completes, and every
 * position reading carries +-[jitterMs] of uniform noise (AudioTrack timestamp granularity).
 * [truePositionMs] is ground truth for assertions; controllers only ever see [positionMs].
 */
class FakeDeck(
    override val name: String,
    private val clock: FakeClock,
    initialPositionMs: Double,
    private val startLatencyMs: Double = 100.0,
    private val seekLatencyMs: Double = 70.0,
    private val jitterMs: Double = 3.0,
    private val readyAtMs: Double = 0.0,
    startPlaying: Boolean = false,
    seed: Int = 1,
    /** Position bias applied to every seek's landing point (models unknown pipeline flush behaviour). */
    private val seekLandingErrorMs: Double = 0.0,
    /**
     * Media3's AudioTrackPositionTracker hides the jump between the playhead and the AudioTimestamp after every
     * start / flush by running the REPORTED position up to 10 % fast or slow until the two agree
     * (MAX_POSITION_SMOOTHING_SPEED_CHANGE_PERCENT = 10, drift up to 1 s). Modelled as an error of random sign and
     * size in [smoothMinMs, smoothMaxMs] on each start, draining at 10 % of elapsed time, then exponentially.
     * Ground truth is untouched: only [positionMs] carries it. 0 = the old perfect signal.
     */
    private val smoothMinMs: Double = 0.0,
    private val smoothMaxMs: Double = 0.0,
) : Deck {
    private val rng = Random(seed)
    private var anchorTime = clock.now
    private var anchorPos = initialPositionMs
    private var moving = startPlaying
    private var wantPlay = startPlaying
    private var startAt: Double? = null
    private var seekTarget: Double? = null
    private var seekDoneAt = 0.0
    private var smoothE0 = 0.0
    private var smoothFrom = 0.0
    var released = false
        private set
    var pauseCount = 0
        private set
    val volumeLog = ArrayList<Pair<Double, Float>>()

    override var volume: Float = 1f
        set(value) {
            field = value
            volumeLog += clock.now to value
        }

    private fun advance() {
        val now = clock.now
        seekTarget?.let {
            if (now >= seekDoneAt) {
                anchorPos = it // lands on the target as of the moment the seek completes
                anchorTime = seekDoneAt
                seekTarget = null
                moving = wantPlay
                if (moving) armSmoothing(seekDoneAt)
                if (wantPlay && startAt != null) startAt = null
            }
        }
        startAt?.let {
            if (now >= it) {
                anchorTime = it
                moving = true
                armSmoothing(it)
                startAt = null
            }
        }
    }

    val truePositionMs: Double
        get() {
            advance()
            return if (moving) anchorPos + (clock.now - anchorTime) else anchorPos
        }

    override val isReady: Boolean get() = clock.now >= readyAtMs

    override val isPlaying: Boolean
        get() {
            advance()
            return moving && !released
        }

    private fun armSmoothing(at: Double) {
        if (smoothMaxMs <= 0.0) return
        val size = smoothMinMs + rng.nextDouble() * (smoothMaxMs - smoothMinMs)
        smoothE0 = if (rng.nextBoolean()) size else -size
        smoothFrom = at
    }

    /** The reporting error left at [now]: linear drain at 10 % down to 40 ms, then exponential (tau 400 ms). */
    fun reportingErrorMs(now: Double = clock.now): Double {
        if (smoothE0 == 0.0 || !moving) return 0.0
        val t = (now - smoothFrom).coerceAtLeast(0.0)
        val a = kotlin.math.abs(smoothE0)
        val knee = 40.0
        val mag =
            if (a > knee) {
                val t1 = (a - knee) / 0.1
                if (t < t1) a - 0.1 * t else knee * kotlin.math.exp(-(t - t1) / 400.0)
            } else {
                a * kotlin.math.exp(-t / 400.0)
            }
        return kotlin.math.sign(smoothE0) * mag
    }

    override fun positionMs(): Double = truePositionMs + reportingErrorMs() + (rng.nextDouble() * 2 - 1) * jitterMs

    override fun seekTo(ms: Long) {
        advance()
        // Freeze at the current position while the pipeline flushes.
        anchorPos = truePositionMs
        moving = false
        seekTarget = ms.toDouble() + seekLandingErrorMs
        seekDoneAt = clock.now + seekLatencyMs
    }

    override fun play() {
        advance()
        wantPlay = true
        if (!moving && seekTarget == null && startAt == null) startAt = clock.now + startLatencyMs
    }

    override fun pause() {
        advance()
        pauseCount++
        anchorPos = truePositionMs
        moving = false
        wantPlay = false
        startAt = null
    }

    override fun release() {
        released = true
        moving = false
    }
}

class RecordingHost(
    private val clock: FakeClock,
    private val makeIncoming: (seekSourceMs: Long) -> Deck?,
) : TransitionHost {
    val events = ArrayList<TransitionEvent>()
    var finished = false
    var failedReason: String? = null
    var failedResult: AbortResult? = null
    var committedSeek: Long? = null
    var incoming: Deck? = null

    override fun commitToIncoming(seekSourceMs: Long): Deck? {
        committedSeek = seekSourceMs
        incoming = makeIncoming(seekSourceMs)
        return incoming
    }

    override fun onFinished() {
        finished = true
    }

    override fun onFailed(reason: String, result: AbortResult) {
        failedReason = reason
        failedResult = result
    }

    override fun onEvent(event: TransitionEvent) {
        events += event
    }
}
