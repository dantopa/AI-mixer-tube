package org.simpmusic.dj.android.splice

/**
 * Maps a deck's SOURCE time (its own media time, ms) to WINDOW time (ms since the rendered window's first sample):
 * `w = t + k(t)`. `k` is [k0] up to [glideFromMs], moves linearly to [k1] by [glideToMs] and stays there: the glide
 * lets a deck that is already audible slide its pointer by a few ms without a jump (a read rate of `1 + dk/dt`, a few
 * cents of pitch for well under a second of correction).
 */
data class WindowMap(
    val k0: Double,
    val k1: Double = k0,
    val glideFromMs: Double = Double.POSITIVE_INFINITY,
    val glideToMs: Double = Double.POSITIVE_INFINITY,
) {
    fun k(t: Double): Double =
        when {
            t <= glideFromMs -> k0
            t >= glideToMs -> k1
            else -> k0 + (k1 - k0) * (t - glideFromMs) / (glideToMs - glideFromMs)
        }

    fun windowMs(t: Double): Double = t + k(t)
}

/** What a [SpliceEngine] does with its deck's audio. Immutable: the control thread swaps whole commands. */
sealed interface SpliceCommand {
    /** The deck's own audio, untouched. */
    data object Passthrough : SpliceCommand

    /**
     * Output = the window at [map] between [spliceInAtMs] and [joinAtMs] (source times), the deck's own audio outside.
     *
     * - Before [spliceInAtMs]: the input. From it, a [xfadeMs] linear fade into the window (the two carry the same
     *   music when the controller placed the splice right, so the fade only hides a residual of a sample or two).
     *   `null` = the window from the first frame (a silent deck that carries the mix).
     * - From [joinAtMs]: a [xfadeMs] fade back to the input times [joinGain], which then ramps to 1 over [joinRampMs]
     *   (the loudness match the window applied to the incoming track ends sample-exactly here, not on a player fader).
     *   `null` = never.
     * - [capture]: keep the last seconds of input (mono) for [SpliceEngine.captureSlice], to measure the decode skew.
     * - [allowSeek]: a discontinuity (seek) re-anchors instead of interrupting. Only for a deck that is still silent.
     */
    data class Run(
        val map: WindowMap,
        val capture: Boolean = false,
        val spliceInAtMs: Double? = Double.POSITIVE_INFINITY,
        val joinAtMs: Double? = null,
        val joinGain: Float = 1f,
        val joinRampMs: Double = 0.0,
        val xfadeMs: Double = 5.0,
        val allowSeek: Boolean = false,
    ) : SpliceCommand {
        /** Last source time touched by this command: after it the output is the plain input again. */
        val endMs: Double? get() = joinAtMs?.let { it + maxOf(xfadeMs, joinRampMs) }
    }
}

/**
 * The commands an engine applied, each from the source time of the first frame it processed: what the deck EMITTED
 * at any source time, so a position read at the speaker (which lags processing by the sink's buffer) maps to the right
 * window time even right after a command change.
 */
class CommandHistory(
    private val capacity: Int = 32,
) {
    private val from = DoubleArray(capacity)
    private val cmds = arrayOfNulls<SpliceCommand>(capacity)
    private var size = 0

    @Synchronized
    fun record(fromMs: Double, cmd: SpliceCommand) {
        if (size == capacity) {
            System.arraycopy(from, 1, from, 0, capacity - 1)
            System.arraycopy(cmds, 1, cmds, 0, capacity - 1)
            size--
        }
        from[size] = fromMs
        cmds[size] = cmd
        size++
    }

    @Synchronized
    fun clear() {
        size = 0
    }

    /** Command in force for the frame at source time [t], or null before the first record. */
    @Synchronized
    fun at(t: Double): SpliceCommand? {
        for (i in size - 1 downTo 0) if (from[i] <= t) return cmds[i]
        return if (size > 0) cmds[0] else null
    }

    /** Window time the deck emitted (or will emit) at source time [t]; null when it plays its own audio there. */
    fun windowTimeAt(t: Double): Double? = (at(t) as? SpliceCommand.Run)?.map?.windowMs(t)
}
