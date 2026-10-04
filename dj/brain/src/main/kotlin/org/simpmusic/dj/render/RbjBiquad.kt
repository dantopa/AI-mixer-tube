package org.simpmusic.dj.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * RBJ cookbook biquad (transposed direct form II, double state), two independent channel states.
 * Coefficients can be changed between blocks without resetting the state (time-varying filter).
 */
class RbjBiquad(private val channels: Int = 2) {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private val z1 = DoubleArray(channels)
    private val z2 = DoubleArray(channels)

    fun reset() {
        z1.fill(0.0)
        z2.fill(0.0)
    }

    fun setLowpass(freqHz: Double, sampleRate: Int, q: Double = BUTTERWORTH_Q) = set(freqHz, sampleRate, q, false)
    fun setHighpass(freqHz: Double, sampleRate: Int, q: Double = BUTTERWORTH_Q) = set(freqHz, sampleRate, q, true)

    private fun set(freqHz: Double, sampleRate: Int, q: Double, high: Boolean) {
        val f = min(max(freqHz, 5.0), sampleRate * 0.45)
        val w0 = 2.0 * PI * f / sampleRate
        val cw = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        if (high) {
            b0 = (1.0 + cw) / 2.0 / a0
            b1 = -(1.0 + cw) / a0
            b2 = b0
        } else {
            b0 = (1.0 - cw) / 2.0 / a0
            b1 = (1.0 - cw) / a0
            b2 = b0
        }
        a1 = -2.0 * cw / a0
        a2 = (1.0 - alpha) / a0
    }

    /** Filters buf[offset until offset+count] in place for channel [ch]. */
    fun process(buf: FloatArray, offset: Int, count: Int, ch: Int) {
        var s1 = z1[ch]
        var s2 = z2[ch]
        for (i in offset until offset + count) {
            val x = buf[i].toDouble()
            val y = b0 * x + s1
            s1 = b1 * x - a1 * y + s2
            s2 = b2 * x - a2 * y
            buf[i] = y.toFloat()
        }
        z1[ch] = s1
        z2[ch] = s2
    }

    companion object {
        const val BUTTERWORTH_Q = 0.70710678118
    }
}

/**
 * A low-cut or high-cut stage driven by a cutoff lane: the cutoff is slewed in the log domain per sub-block
 * (no zipper noise), and the stage fades in/out of the signal path instead of being switched, so the lane's
 * "off" values (20 Hz / 20 kHz) are exact bypasses.
 */
class SweepFilter(private val sampleRate: Int, private val highpass: Boolean) {
    private val biquad = RbjBiquad(2)
    private var smoothLn = Double.NaN
    private val alpha = 1.0 - exp(-SUB_BLOCK.toDouble() / sampleRate / SLEW_SECONDS)
    private var active = false

    fun reset() {
        smoothLn = Double.NaN
        active = false
        biquad.reset()
    }

    /** Processes [count] frames of l/r starting at [offset]; [cutoffAt] gives the lane value for the frame index. */
    inline fun process(l: FloatArray, r: FloatArray, offset: Int, count: Int, cutoffAt: (Int) -> Double) {
        var i = 0
        while (i < count) {
            val n = min(SUB_BLOCK, count - i)
            processSub(l, r, offset + i, n, cutoffAt(i + n / 2))
            i += n
        }
    }

    fun processSub(l: FloatArray, r: FloatArray, offset: Int, n: Int, targetHz: Double) {
        val target = ln(max(1.0, targetHz))
        smoothLn = if (smoothLn.isNaN()) target else smoothLn + alpha * (target - smoothLn)
        val fc = exp(smoothLn)
        val wet = if (highpass) ((fc - HP_OFF) / (HP_ON - HP_OFF)).coerceIn(0.0, 1.0)
        else ((LP_OFF - fc) / (LP_OFF - LP_ON)).coerceIn(0.0, 1.0)
        if (wet <= 0.0) {
            if (active) { biquad.reset(); active = false }
            return
        }
        active = true
        if (highpass) biquad.setHighpass(fc, sampleRate) else biquad.setLowpass(fc, sampleRate)
        if (wet >= 1.0) {
            biquad.process(l, offset, n, 0)
            biquad.process(r, offset, n, 1)
        } else {
            // blend dry and wet: y = x + wet (f(x) - x)
            for (ch in 0..1) {
                val buf = if (ch == 0) l else r
                stash(buf, offset, n)
                biquad.process(buf, offset, n, ch)
                for (k in 0 until n) buf[offset + k] = (stashBuf[k] + wet * (buf[offset + k] - stashBuf[k])).toFloat()
            }
        }
    }

    private val stashBuf = DoubleArray(SUB_BLOCK)
    private fun stash(buf: FloatArray, offset: Int, n: Int) {
        for (k in 0 until n) stashBuf[k] = buf[offset + k].toDouble()
    }

    companion object {
        const val SUB_BLOCK = 32
        private const val SLEW_SECONDS = 0.010
        private const val HP_OFF = 20.5
        private const val HP_ON = 45.0
        private const val LP_OFF = 19_500.0
        private const val LP_ON = 15_000.0
    }
}
