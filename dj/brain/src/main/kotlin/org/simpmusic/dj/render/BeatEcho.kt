package org.simpmusic.dj.render

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Beat-synced feedback delay for the echo-out transition.
 *
 * Two delay lines of [delayMs] each. In ping-pong mode the (mono-summed) input enters the left line, the left output
 * feeds the right line and the right output feeds back into the left one, so repeats alternate left/right and every
 * hop through a line multiplies by the feedback (repeat k has gain feedback^(k-1)); otherwise each channel feeds back
 * on itself. Every write into a line goes through a one-pole high-pass ([highpassHz]: repeats never carry bass, which
 * is what keeps the tail from fighting the incoming deck's kick) and a one-pole low-pass ([dampHz]: each repeat is a
 * little darker). Both filters have gain <= 1 at every frequency, so the loop is stable for any feedback < 1.
 *
 * Fixed-size rings, no allocation while processing (the scratch arrays of the in-place [OfflineMixRenderer.BlockEffect]
 * form grow only if a block is larger than any seen so far).
 *
 * Two forms:
 *  - [processWet]: aux-send form used by the renderer: reads the send signal, writes ONLY the delayed signal (wet).
 *  - [process] (BlockEffect): insert form, `out = in + wet`, for use through the effect hooks.
 */
class BeatEcho(
    sampleRate: Int,
    delayMs: Double,
    feedback: Double,
    highpassHz: Double = 280.0,
    dampHz: Double = 6000.0,
    private val pingPong: Boolean = true,
) : OfflineMixRenderer.BlockEffect {
    private val delayFrames = max(1, (delayMs.coerceIn(1.0, 4000.0) * sampleRate / 1000.0).roundToInt())
    private val fb = feedback.coerceIn(0.0, MAX_FEEDBACK).toFloat()
    private val ringL = FloatArray(delayFrames)
    private val ringR = FloatArray(delayFrames)
    private var pos = 0

    private val hpA: Float = run {
        val rc = 1.0 / (2.0 * PI * highpassHz.coerceIn(5.0, sampleRate * 0.45))
        (rc / (rc + 1.0 / sampleRate)).toFloat()
    }
    private val lpB: Float = (1.0 - exp(-2.0 * PI * dampHz.coerceIn(200.0, sampleRate * 0.45) / sampleRate)).toFloat()

    // filter state per line: previous input / output of the high-pass, output of the low-pass
    private var hpXL = 0f
    private var hpYL = 0f
    private var lpYL = 0f
    private var hpXR = 0f
    private var hpYR = 0f
    private var lpYR = 0f

    private var scratchL = FloatArray(0)
    private var scratchR = FloatArray(0)

    /** Delay length in frames after rounding to the sample grid. */
    val delayInFrames: Int get() = delayFrames

    fun reset() {
        ringL.fill(0f)
        ringR.fill(0f)
        pos = 0
        hpXL = 0f; hpYL = 0f; lpYL = 0f; hpXR = 0f; hpYR = 0f; lpYR = 0f
    }

    /**
     * Feeds [count] frames of the send signal (sendL/sendR from [offset]) and writes the delayed signal to
     * wetL/wetR (same indices, overwritten). The arrays may not alias.
     */
    fun processWet(sendL: FloatArray, sendR: FloatArray, wetL: FloatArray, wetR: FloatArray, offset: Int, count: Int) {
        var p = pos
        var xL = hpXL
        var yL = hpYL
        var zL = lpYL
        var xR = hpXR
        var yR = hpYR
        var zR = lpYR
        val a = hpA
        val b = lpB
        val g = fb
        for (i in offset until offset + count) {
            val outL = ringL[p]
            val outR = ringR[p]
            val inL: Float
            val inR: Float
            if (pingPong) {
                inL = 0.5f * (sendL[i] + sendR[i]) + g * outR
                inR = g * outL
            } else {
                inL = sendL[i] + g * outL
                inR = sendR[i] + g * outR
            }
            // write path of each line: high-pass then low-pass
            yL = a * (yL + inL - xL)
            xL = inL
            zL += b * (yL - zL)
            yR = a * (yR + inR - xR)
            xR = inR
            zR += b * (yR - zR)
            if (abs(zL) < DENORMAL) zL = 0f
            if (abs(zR) < DENORMAL) zR = 0f
            if (abs(yL) < DENORMAL) yL = 0f
            if (abs(yR) < DENORMAL) yR = 0f
            ringL[p] = zL
            ringR[p] = zR
            wetL[i] = outL
            wetR[i] = outR
            p++
            if (p == delayFrames) p = 0
        }
        pos = p
        hpXL = xL; hpYL = yL; lpYL = zL
        hpXR = xR; hpYR = yR; lpYR = zR
    }

    /** Insert form: `out = in + wet`. */
    override fun process(l: FloatArray, r: FloatArray, offset: Int, count: Int) {
        if (scratchL.size < offset + count) {
            scratchL = FloatArray(offset + count)
            scratchR = FloatArray(offset + count)
        }
        processWet(l, r, scratchL, scratchR, offset, count)
        for (i in offset until offset + count) {
            l[i] += scratchL[i]
            r[i] += scratchR[i]
        }
    }

    companion object {
        const val MAX_FEEDBACK = 0.97
        private const val DENORMAL = 1e-20f

        /** Number of repeats needed for the tail to fall [db] dB (ignoring the in-loop filters, which only help). */
        fun repeatsForDecay(feedback: Double, db: Double = 60.0): Int {
            val f = feedback.coerceIn(0.01, MAX_FEEDBACK)
            return min(1000, kotlin.math.ceil(db / (-20.0 * kotlin.math.log10(f))).toInt())
        }
    }
}
