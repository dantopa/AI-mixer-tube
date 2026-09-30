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
) : Deck {
    private val rng = Random(seed)
    private var anchorTime = clock.now
    private var anchorPos = initialPositionMs
    private var moving = startPlaying
    private var wantPlay = startPlaying
    private var startAt: Double? = null
    private var seekTarget: Double? = null
    private var seekDoneAt = 0.0
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
                if (wantPlay && startAt != null) startAt = null
            }
        }
        startAt?.let {
            if (now >= it) {
                anchorTime = it
                moving = true
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

    override fun positionMs(): Double = truePositionMs + (rng.nextDouble() * 2 - 1) * jitterMs

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
