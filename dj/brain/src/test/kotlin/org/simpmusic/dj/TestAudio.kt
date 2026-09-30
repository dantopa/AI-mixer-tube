package org.simpmusic.dj

import org.simpmusic.dj.render.DeckStretcher
import org.simpmusic.dj.render.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Shared measurement helpers for the DSP tests. */
object TestAudio {
    fun sine(freq: Double, seconds: Double, sr: Int, amp: Double = 0.5): FloatArray =
        FloatArray((seconds * sr).toInt()) { (amp * sin(2 * PI * freq * it / sr)).toFloat() }

    /** Onset times (ms) of clicks: rising 50% crossing of a lightly smoothed envelope, refractory [minGapMs]. */
    fun detectClicks(x: FloatArray, sr: Int, minGapMs: Double = 120.0, relThreshold: Double = 0.25, from: Int = 0, to: Int = x.size): List<Double> {
        val win = maxOf(1, (0.0004 * sr).toInt())
        val env = FloatArray(x.size)
        var acc = 0.0
        val q = DoubleArray(win)
        var qi = 0
        for (i in x.indices) {
            val v = abs(x[i].toDouble())
            acc += v - q[qi]
            q[qi] = v
            qi = (qi + 1) % win
            env[i] = (acc / win).toFloat()
        }
        var peak = 0f
        for (i in from until to) if (env[i] > peak) peak = env[i]
        val thr = (relThreshold * peak).toFloat()
        val out = ArrayList<Double>()
        var i = from
        val gap = (minGapMs * sr / 1000).toInt()
        while (i < to) {
            if (env[i] >= thr) {
                // find local peak within 15 ms
                var end = minOf(to, i + (0.015 * sr).toInt())
                var pk = env[i]
                var pkAt = i
                for (j in i until end) if (env[j] > pk) { pk = env[j]; pkAt = j }
                // walk back to the 50% crossing of this click's own peak
                val half = pk * 0.5f
                var j = pkAt
                while (j > from && env[j - 1] >= half) j--
                val frac = if (j > from && env[j] > env[j - 1]) (half - env[j - 1]) / (env[j] - env[j - 1]) else 0f
                out += (j - 1 + frac.toDouble()) * 1000.0 / sr - (win / 2.0) * 1000.0 / sr // compensate box smoothing delay
                i = pkAt + gap
            } else i++
        }
        return out
    }

    /** Frequency (Hz) of the strongest spectral line, parabolic-interpolated on a Hann-windowed FFT. */
    fun peakFrequency(x: FloatArray, sr: Int, start: Int = 0, size: Int = 1 shl 17): Double {
        val n = size
        val fft = Fft(n)
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (i in 0 until n) {
            val w = 0.5 - 0.5 * cos(2 * PI * i / n)
            re[i] = if (start + i < x.size) w * x[start + i] else 0.0
        }
        fft.forward(re, im)
        var best = 1
        var bv = 0.0
        val mag = DoubleArray(n / 2) { sqrt(re[it] * re[it] + im[it] * im[it]) }
        for (b in 1 until n / 2 - 1) if (mag[b] > bv) { bv = mag[b]; best = b }
        val a = kotlin.math.ln(mag[best - 1] + 1e-12)
        val b = kotlin.math.ln(mag[best] + 1e-12)
        val c = kotlin.math.ln(mag[best + 1] + 1e-12)
        val d = 0.5 * (a - c) / (a - 2 * b + c)
        return (best + d) * sr / n
    }

    fun cents(f: Double, ref: Double) = 1200.0 * kotlin.math.ln(f / ref) / kotlin.math.ln(2.0)

    fun rms(x: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        val a = from.coerceIn(0, x.size)
        val b = to.coerceIn(a, x.size)
        for (i in a until b) s += x[i] * x[i].toDouble()
        return sqrt(s / maxOf(1, b - a))
    }

    fun db(x: Double) = 20 * log10(x + 1e-12)

    fun maxAbsDelta(x: FloatArray, from: Int, to: Int): Double {
        var m = 0.0
        for (i in maxOf(1, from) until minOf(to, x.size)) m = maxOf(m, abs((x[i] - x[i - 1]).toDouble()))
        return m
    }

    class FnAutomation(val rate: (Double) -> Double, val pitch: (Double) -> Double = { 1.0 }) : DeckStretcher.Automation {
        override fun rate(outFrame: Double) = rate.invoke(outFrame)
        override fun pitchRatio(outFrame: Double) = pitch.invoke(outFrame)
    }

    fun source(x: Array<FloatArray>): DeckStretcher.Source = DeckStretcher.Source { start, count, dst ->
        for (c in dst.indices) {
            val s = x[minOf(c, x.size - 1)]
            for (i in 0 until count) {
                val p = start + i
                dst[c][i] = if (p < 0 || p >= s.size) 0f else s[p.toInt()]
            }
        }
    }

    /** Runs the stretcher for [frames] output frames from source position [startFrame]. */
    fun stretch(x: Array<FloatArray>, sr: Int, startFrame: Double, frames: Int, automation: DeckStretcher.Automation, fft: Int = 2048, overlap: Int = 4): Array<FloatArray> {
        val st = DeckStretcher(x.size, sr, fft, overlap)
        st.start(startFrame, source(x), automation)
        val out = Array(x.size) { FloatArray(frames) }
        var pos = 0
        while (pos < frames) {
            val n = minOf(256, frames - pos)
            st.render(out, pos, n)
            pos += n
        }
        return out
    }
}
