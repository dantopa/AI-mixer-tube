package org.simpmusic.dj.android.window

/** Monotonic millisecond clock (fractional). Faked in tests, `SystemClock.elapsedRealtimeNanos` on device. */
fun interface DjClock {
    fun nowMs(): Double
}

/**
 * One physical audio path (an ExoPlayer) as the transition controller sees it. All calls are made from
 * the single control thread (the player's application thread), so implementations need no locking.
 */
interface Deck {
    val name: String

    /** Channel fader, linear 0..1. */
    var volume: Float

    /** True once the deck can start producing audio immediately when [play] is called. */
    val isReady: Boolean
    val isPlaying: Boolean

    /**
     * Best available estimate of the deck's playback position in ms, on the deck's OWN timeline
     * (source time for a live track, window time for the rendered window). Continuous while playing.
     */
    fun positionMs(): Double

    /** Seeks (exactly) to [ms] on the deck's own timeline. The deck keeps its play/pause state. */
    fun seekTo(ms: Long)

    fun play()

    fun pause()

    fun release()
}

/**
 * Smooths a jittery position signal into a continuous one.
 *
 * ExoPlayer's `currentPosition` is extrapolated with the system clock between renderer updates and
 * the renderer position itself is quantised by AudioTrack timestamp granularity, so single readings
 * wander by several ms. While the deck plays at a KNOWN constant rate, position - t * rate is a
 * constant, so the median of that quantity over the last [capacity] readings is a far better
 * estimate than any one reading. Reset on every seek / rate change / discontinuity.
 */
class PositionEstimator(
    private val capacity: Int = 24,
) {
    private val offsets = DoubleArray(capacity)
    private var count = 0
    private var next = 0
    private var rate = 1.0

    val hasEstimate: Boolean get() = count >= MIN_SAMPLES

    fun reset(newRate: Double = 1.0) {
        count = 0
        next = 0
        rate = newRate
    }

    /** Feeds one raw reading taken at clock time [nowMs]. */
    fun add(nowMs: Double, positionMs: Double) {
        offsets[next] = positionMs - nowMs * rate
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    /** Estimated position at [nowMs]; falls back to [raw] until enough readings exist. */
    fun estimate(nowMs: Double, raw: Double): Double {
        if (count < MIN_SAMPLES) return raw
        val sorted = offsets.copyOf(count).also { it.sort() }
        val median = if (count % 2 == 1) sorted[count / 2] else (sorted[count / 2 - 1] + sorted[count / 2]) / 2
        return median + nowMs * rate
    }

    private companion object {
        const val MIN_SAMPLES = 5
    }
}
