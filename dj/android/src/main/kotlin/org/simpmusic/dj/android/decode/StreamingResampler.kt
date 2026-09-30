package org.simpmusic.dj.android.decode

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Streaming band-limited sample-rate converter for interleaved float PCM.
 *
 * Windowed-sinc (Blackman) with a 1024-phase kernel table, cut off just below the lower of the two
 * Nyquists so downsampling (48 kHz -> 22.05 kHz for analysis) does not alias. Memory is
 * O(kernel), independent of track length: input is fed in chunks and only the last few dozen
 * frames are retained between calls.
 *
 * Output frame k sits at input time k * inRate / outRate (the symmetric kernel's group delay is
 * compensated by construction: an output at time t reads input frames around t).
 */
class StreamingResampler(
    private val inRate: Int,
    private val outRate: Int,
    private val channels: Int,
) {
    private val passthrough = inRate == outRate
    private val step = inRate.toDouble() / outRate
    private val kernel: FloatArray = if (passthrough) FloatArray(0) else buildKernel(min(1.0, outRate.toDouble() / inRate) * CUTOFF)

    // history holds input frames [histStart, histStart + histFrames). Frames before time 0 are zeros.
    private var history = FloatArray(0)
    private var histFrames = 0
    private var histStart = -HALF_TAPS.toLong()
    private var nextOutTime = 0.0 // in input frames
    private var outBuf = FloatArray(0)

    init {
        require(inRate > 0 && outRate > 0 && channels > 0)
        if (!passthrough) {
            history = FloatArray((HALF_TAPS * 2 + 4096) * channels)
            histFrames = HALF_TAPS // virtual zeros before time 0
        }
    }

    /** Feeds [frames] interleaved frames; each produced block is passed to [emit] (reused buffer: copy if you keep it). */
    fun process(input: FloatArray, frames: Int, emit: (FloatArray, Int) -> Unit) {
        if (passthrough) {
            emit(input, frames)
            return
        }
        append(input, frames)
        produce(emit)
        compact()
    }

    /** Drains the tail by padding with zeros so the last real samples still get filtered. */
    fun finish(emit: (FloatArray, Int) -> Unit) {
        if (passthrough) return
        append(FloatArray(HALF_TAPS * channels), HALF_TAPS)
        produce(emit)
    }

    private fun append(input: FloatArray, frames: Int) {
        val need = (histFrames + frames) * channels
        if (need > history.size) history = history.copyOf(maxOf(need, history.size * 2))
        System.arraycopy(input, 0, history, histFrames * channels, frames * channels)
        histFrames += frames
    }

    private fun produce(emit: (FloatArray, Int) -> Unit) {
        val histEnd = histStart + histFrames // exclusive, input frames
        val maxOut = (((histEnd - HALF_TAPS - nextOutTime) / step).toInt() + 2).coerceAtLeast(0)
        if (outBuf.size < maxOut * channels) outBuf = FloatArray(maxOut * channels)
        var n = 0
        while (true) {
            val t = nextOutTime
            val base = floor(t).toLong()
            // Output at t needs input frames base - HALF_TAPS + 1 .. base + HALF_TAPS.
            if (base + HALF_TAPS >= histEnd) break
            val phase = (((t - base) * PHASES) + 0.5).toInt().coerceIn(0, PHASES)
            val koff = phase * TAPS
            val first = base - HALF_TAPS + 1
            for (c in 0 until channels) {
                var acc = 0f
                var idx = ((first - histStart) * channels + c).toInt()
                var j = 0
                while (j < TAPS) {
                    if (idx >= 0) acc += history[idx] * kernel[koff + j]
                    idx += channels
                    j++
                }
                outBuf[n * channels + c] = acc
            }
            n++
            nextOutTime += step
        }
        if (n > 0) emit(outBuf, n)
    }

    private fun compact() {
        val keepFrom = floor(nextOutTime).toLong() - HALF_TAPS
        val drop = (keepFrom - histStart).toInt()
        if (drop > 0 && drop <= histFrames) {
            System.arraycopy(history, drop * channels, history, 0, (histFrames - drop) * channels)
            histFrames -= drop
            histStart += drop
        }
    }

    companion object {
        const val HALF_TAPS = 24
        private const val TAPS = HALF_TAPS * 2
        private const val PHASES = 1024
        private const val CUTOFF = 0.94 // fraction of the lower Nyquist

        /** kernel[phase * TAPS + j] weights input frame (base - HALF_TAPS + 1 + j) for an output at base + phase/PHASES. */
        private fun buildKernel(cutoff: Double): FloatArray {
            val k = FloatArray((PHASES + 1) * TAPS)
            for (p in 0..PHASES) {
                val frac = p.toDouble() / PHASES
                var sum = 0.0
                val row = DoubleArray(TAPS)
                for (j in 0 until TAPS) {
                    val x = (j - HALF_TAPS + 1) - frac // distance from the output instant, in input frames
                    val w = 0.42 + 0.5 * cos(PI * x / HALF_TAPS) + 0.08 * cos(2 * PI * x / HALF_TAPS)
                    val s = if (x == 0.0) cutoff else sin(PI * cutoff * x) / (PI * x)
                    row[j] = s * (if (kotlin.math.abs(x) >= HALF_TAPS) 0.0 else w)
                    sum += row[j]
                }
                for (j in 0 until TAPS) k[p * TAPS + j] = (row[j] / sum).toFloat() // unity DC gain
            }
            return k
        }
    }
}
