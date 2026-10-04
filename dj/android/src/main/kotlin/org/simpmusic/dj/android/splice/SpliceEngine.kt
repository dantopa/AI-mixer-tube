package org.simpmusic.dj.android.splice

import kotlin.math.abs

/** A slice of a deck's captured input: mono floats at [sampleRate], first sample at source time [startMs]. */
class CapturedAudio(
    val sampleRate: Int,
    val startMs: Double,
    val samples: FloatArray,
)

/** What the transition controller can see and steer of one deck's [SpliceEngine]. */
interface SpliceHandle {
    /** The deck's audio format is one the engine can splice (PCM16, mono or stereo) and it has been configured. */
    val isSupported: Boolean

    /** Source time (ms) just past the last frame processed; NaN before any input. Runs ahead of the speaker. */
    val processedMs: Double

    /** The deck jumped (seek) while a command that does not [SpliceCommand.Run.allowSeek] was running. Cleared by [setCommand]. */
    val isInterrupted: Boolean

    fun setCommand(cmd: SpliceCommand)

    /** Window time the deck emits at its source time [sourceMs]; null when it plays its own audio there. */
    fun windowTimeAt(sourceMs: Double): Double?

    /** Format, last flush and capture-ring state, for the log. */
    val diagnostics: String get() = ""

    /** [lengthMs] of captured input from source time [fromMs], or null when not (all) captured. */
    fun captureSlice(fromMs: Double, lengthMs: Double): CapturedAudio?
}

/**
 * Sample-exact splicing of a pre-rendered transition window into one deck's own audio, inside its audio pipeline.
 *
 * The two-player hand-offs of the original design compared POSITIONS that Media3 reports (smoothed for up to a second
 * after every start or seek), and corrected them with seeks, which re-armed the smoothing: on the device 3 of 13 mixes
 * aborted and the forced hand-offs landed 40-170 ms off. Here the deck's audio processor knows the source time of every
 * frame it handles (`flush(StreamMetadata).positionOffsetUs`, then counting frames), so it can swap its output for the
 * window at an exact frame, and swap back to its own audio at an exact frame. No clock is involved in either switch.
 *
 * Threading: [process] / [onFlush] / [configure] run on the playback thread; [setCommand] and the reads run on the
 * control thread. Commands are immutable and published through a volatile field; the playback thread picks a new one
 * up at the start of a block and records it in [history] from that block's first frame.
 */
class SpliceEngine(
    private val window: () -> WindowSamples?,
    /** Seconds of input kept for [captureSlice]. */
    private val captureSeconds: Int = 10,
) : SpliceHandle {
    @Volatile
    private var command: SpliceCommand = SpliceCommand.Passthrough

    @Volatile
    private var generation = 0
    private var appliedGeneration = -1
    private val history = CommandHistory()

    private var rate = 0
    private var channels = 0

    @Volatile
    override var isSupported = false
        private set

    /** Source time of the next input frame. */
    private var nextMs = Double.NaN

    @Volatile
    override var processedMs = Double.NaN
        private set

    @Volatile
    override var isInterrupted = false
        private set

    @Volatile
    private var interruptedGeneration = -1

    // capture ring (mono)
    private var ring: FloatArray? = null
    private var ringWrite = 0L // total samples ever written since the ring was (re)started
    private var ringStartMs = Double.NaN // source time of sample index 0 of the current run
    private val ringLock = Any()

    /** Times a capture run found the ring stale and restarted it (diagnostics). */
    @Volatile
    var ringRestarts = 0
        private set

    /** The last pipeline flush, for the log: `source ms (expected ms) jump|re-anchor`. */
    @Volatile
    var lastFlush: String = "none"
        private set

    override val diagnostics: String
        get() {
            val ring = synchronized(ringLock) { if (ringStartMs.isNaN()) "empty" else "%.0f..%.0f ms".format(ringStartMs, ringStartMs + ringWrite * 1000.0 / rate.coerceAtLeast(1)) }
            return "${rate} Hz x$channels${if (isSupported) "" else " (unsupported)"}, processed ${"%.0f".format(processedMs)} ms, " +
                "last flush $lastFlush, capture $ring, ring restarts $ringRestarts"
        }

    fun configure(sampleRate: Int, channelCount: Int, pcm16: Boolean): Boolean {
        rate = sampleRate
        channels = channelCount
        isSupported = pcm16 && (channelCount == 1 || channelCount == 2) && sampleRate > 0
        return isSupported
    }

    /** A pipeline flush: [positionUs] is the source time of the next frame (0 when Media3 does not know it yet). */
    fun onFlush(positionUs: Long) {
        val t = positionUs / 1000.0
        val expected = nextMs
        val jumped = !expected.isNaN() && abs(t - expected) > DISCONTINUITY_MS
        if (jumped) {
            val cmd = command
            if (cmd is SpliceCommand.Run && !cmd.allowSeek) {
                isInterrupted = true
                interruptedGeneration = generation
            }
            synchronized(ringLock) {
                ringWrite = 0
                ringStartMs = Double.NaN
            }
        }
        if (positionUs != 0L || expected.isNaN()) {
            lastFlush = "%.1f (expected %s)%s".format(t, if (expected.isNaN()) "-" else "%.1f".format(expected), if (jumped) " jump" else "")
        }
        nextMs = t
        processedMs = t
    }

    override fun setCommand(cmd: SpliceCommand) {
        command = cmd
        generation++
        isInterrupted = false
    }

    override fun windowTimeAt(sourceMs: Double): Double? = history.windowTimeAt(sourceMs)

    /** Clears every trace of a previous transition (player reset / reuse). */
    fun reset() {
        command = SpliceCommand.Passthrough
        generation++
        history.clear()
        isInterrupted = false
        nextMs = Double.NaN
        processedMs = Double.NaN
        synchronized(ringLock) {
            ring = null
            ringWrite = 0
            ringStartMs = Double.NaN
        }
    }

    /**
     * Processes [frames] interleaved PCM16 frames of [input] into [output] (same layout, same length). Playback thread.
     */
    fun process(input: ShortArray, frames: Int, output: ShortArray) {
        val t0 = if (nextMs.isNaN()) 0.0 else nextMs
        val cmd = command
        val gen = generation
        if (gen != appliedGeneration) {
            appliedGeneration = gen
            history.record(t0, cmd)
        }
        val dt = 1000.0 / rate
        val ch = channels
        val win = if (cmd is SpliceCommand.Run) window() else null
        val run = cmd as? SpliceCommand.Run
        val dead = run == null || win == null || (isInterrupted && interruptedGeneration == gen)

        if (run != null && run.capture) captureBlock(input, frames, t0)

        if (dead) {
            System.arraycopy(input, 0, output, 0, frames * ch)
        } else {
            val s = run!!.spliceInAtMs ?: Double.NEGATIVE_INFINITY
            val j = run.joinAtMs ?: Double.POSITIVE_INFINITY
            val xf = run.xfadeMs.coerceAtLeast(0.001)
            val winRate = win!!.sampleRate / 1000.0
            for (i in 0 until frames) {
                val t = t0 + i * dt
                val base = i * ch
                when {
                    t < s -> for (c in 0 until ch) output[base + c] = input[base + c]
                    t >= j + xf && t >= j + run.joinRampMs -> for (c in 0 until ch) output[base + c] = input[base + c]
                    else -> {
                        val pos = run.map.windowMs(t) * winRate
                        for (c in 0 until ch) {
                            val w = windowValue(win, pos, c, ch)
                            val x = input[base + c].toFloat()
                            val v =
                                when {
                                    t < s + xf -> {
                                        val a = ((t - s) / xf).toFloat()
                                        x * (1f - a) + w * a
                                    }
                                    t < j -> w
                                    else -> {
                                        val g = joinGain(run, t - j)
                                        if (t < j + xf) {
                                            val a = ((t - j) / xf).toFloat()
                                            w * (1f - a) + x * g * a
                                        } else {
                                            x * g
                                        }
                                    }
                                }
                            output[base + c] = clip(v)
                        }
                    }
                }
            }
        }
        nextMs = t0 + frames * dt
        processedMs = nextMs
    }

    private fun joinGain(run: SpliceCommand.Run, sinceJoin: Double): Float {
        if (run.joinRampMs <= 0.0 || sinceJoin >= run.joinRampMs) return 1f
        val a = (sinceJoin / run.joinRampMs).toFloat()
        return run.joinGain + (1f - run.joinGain) * a
    }

    private fun windowValue(win: WindowSamples, pos: Double, c: Int, ch: Int): Float =
        if (ch == 2) {
            win.interpolate(pos, c)
        } else {
            (win.interpolate(pos, 0) + win.interpolate(pos, 1)) * 0.5f
        }

    private fun clip(v: Float): Short = v.toInt().coerceIn(-32768, 32767).toShort()

    private fun captureBlock(input: ShortArray, frames: Int, t0: Double) {
        synchronized(ringLock) {
            val r = ring?.takeIf { it.size == captureSeconds * rate } ?: FloatArray(captureSeconds * rate).also { ring = it; ringStartMs = Double.NaN }
            // A capture run continues the ring only if this block starts exactly where the ring ends. Otherwise (capture
            // switched off and on again, as on a deck that was the previous mix's incoming, or the time base re-anchored by
            // a flush) the ring is restarted: appending would label every new sample with a time minutes off.
            if (!ringStartMs.isNaN() && abs(t0 - (ringStartMs + ringWrite * 1000.0 / rate)) > 1000.0 / rate) {
                ringRestarts++
                ringStartMs = Double.NaN
            }
            if (ringStartMs.isNaN()) {
                ringStartMs = t0
                ringWrite = 0
            }
            val ch = channels
            for (i in 0 until frames) {
                val v =
                    if (ch == 2) {
                        (input[i * 2].toFloat() + input[i * 2 + 1].toFloat()) * (0.5f / 32768f)
                    } else {
                        input[i] / 32768f
                    }
                r[((ringWrite + i) % r.size).toInt()] = v
            }
            ringWrite += frames
        }
    }

    override fun captureSlice(fromMs: Double, lengthMs: Double): CapturedAudio? {
        synchronized(ringLock) {
            val r = ring ?: return null
            if (ringStartMs.isNaN() || rate <= 0) return null
            val first = Math.round((fromMs - ringStartMs) * rate / 1000.0)
            val n = (lengthMs * rate / 1000.0).toInt()
            if (n <= 0 || first < 0 || first + n > ringWrite || first < ringWrite - r.size) return null
            val out = FloatArray(n)
            for (k in 0 until n) out[k] = r[((first + k) % r.size).toInt()]
            return CapturedAudio(rate, ringStartMs + first * 1000.0 / rate, out)
        }
    }

    private companion object {
        /** A flush landing further than this from where the stream was is a seek, not a reconfiguration. */
        const val DISCONTINUITY_MS = 40.0
    }
}
