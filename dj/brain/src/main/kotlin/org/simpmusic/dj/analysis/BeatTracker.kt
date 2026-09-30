package org.simpmusic.dj.analysis

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Beat positions in seconds on the source timeline plus tracking quality figures. */
internal class BeatResult(
    val timesSec: DoubleArray,
    /** Median inter-beat interval based tempo. */
    val bpm: Double,
    /** Fraction of beats whose raw position lies within 35 ms of the smoothed grid. */
    val regularity: Double,
    /** Mean detrended onset strength at the beats divided by its overall mean (1 = beats no stronger than random). */
    val support: Double,
)

/**
 * Ellis (2007) dynamic-programming beat tracker on the detrended onset envelope, a second pass that lets the period
 * follow slow drift, then sub-frame refinement (parabolic peak on the raw envelope) and a robust local-line smoothing
 * of the grid.
 */
internal object BeatTracker {
    /** Extra delay between the flux peak's frame centre and the acoustic onset, calibrated on click/kick material. */
    var latencySec = 0.0

    /** DP tightness for steady grids (electronic, quantised) and for tracks whose tempo moves (live, rubato). */
    var rigidTightness = 2000.0
    var flexibleTightness = 150.0

    /** Share of beats that must sit on one straight grid for the tempo to count as steady. */
    var debug: ((String) -> Unit)? = null
    var inlierThreshold = 0.8

    fun track(raw: FloatArray, det: FloatArray, bpm: Double): BeatResult? {
        val n = det.size
        if (n < 200) return null
        val period0 = 60.0 * Grid.FPS / bpm
        val local = gaussianSmooth(det, 1.0)
        // 1) rigid pass: if the result sits on one straight beat grid the tempo is steady and the grid wins
        val rigid = pickBeats(local, period0, rigidTightness, snap = true)
        debug?.invoke("rigid steady=${rigid?.second}")
        // 2) otherwise let the tempo breathe (live drummer, rubato): low tightness and a drifting period
        val chosen = if (rigid != null && rigid.second) rigid.first else (pickBeats(local, period0, flexibleTightness, snap = false)?.first ?: rigid?.first)
        var beats = chosen ?: return null
        if (beats.size < 4) return null
        // sub-frame refinement on the raw envelope
        val t = DoubleArray(beats.size)
        for (i in beats.indices) {
            val b = beats[i]
            var pk = b
            var bestV = raw[b]
            for (d in -2..2) {
                val j = b + d
                if (j in 1 until n - 1 && raw[j] > bestV) { bestV = raw[j]; pk = j }
            }
            var off = 0.0
            if (pk in 1 until n - 1) {
                val y0 = raw[pk - 1].toDouble(); val y1 = raw[pk].toDouble(); val y2 = raw[pk + 1].toDouble()
                val den = y0 - 2 * y1 + y2
                if (den < -1e-12) off = (0.5 * (y0 - y2) / den).coerceIn(-0.5, 0.5)
            }
            t[i] = Grid.onsetFrameTimeSec(pk + off) + latencySec
        }
        // drop leading/trailing beats that sit on nothing (silence / tail)
        val trimmed = trimUnsupported(t, beats, det)
        val times = trimmed.first
        if (times.size < 4) return null
        val smoothed = smoothGrid(times)
        var within = 0
        for (i in times.indices) if (abs(times[i] - smoothed[i]) < 0.035) within++
        val out = DoubleArray(times.size) { if (abs(times[it] - smoothed[it]) < 0.025) smoothed[it] else times[it] }
        // support ratio
        val meanDet = OnsetEnvelope.mean(det)
        var sup = 0.0
        for (bIdx in trimmed.second) {
            var m = 0f
            for (d in -1..1) { val j = bIdx + d; if (j in 0 until n && det[j] > m) m = det[j] }
            sup += m
        }
        sup /= trimmed.second.size
        val ibis = DoubleArray(out.size - 1) { out[it + 1] - out[it] }
        val bpmOut = 60.0 / median(ibiWindowed(out, 8))
        return BeatResult(out, bpmOut, within.toDouble() / times.size, if (meanDet > 1e-9) sup / meanDet else 0.0)
    }

    /** Per-position (t[i+k]-t[i])/k, robust against a missing beat here and there. */
    private fun ibiWindowed(t: DoubleArray, k: Int): DoubleArray {
        val kk = min(k, t.size - 1)
        return DoubleArray(t.size - kk) { (t[it + kk] - t[it]) / kk }
    }


    /** DP beat tracking with a first pass and a period-refined second pass. Returns (beat frames, steadyGrid). */
    private fun pickBeats(local: FloatArray, period0: Double, alpha: Double, snap: Boolean): Pair<IntArray, Boolean>? {
        val n = local.size
        val periods = DoubleArray(n) { period0 }
        var beats = dp(local, periods, alpha)
        if (beats.size < 4) return null
        // Refine the period from the first pass: a constant one when the tempo is steady, a slowly varying one
        // (median filtered local IBIs, +-7 %) when it visibly drifts.
        val ibis = DoubleArray(beats.size - 1) { (beats[it + 1] - beats[it]).toDouble() }
        val globalIbi = median(ibis.copyOf())
        if (globalIbi > 0 && abs(globalIbi / period0 - 1) < 0.08) {
            val w = 12
            val smooth = DoubleArray(ibis.size) { i ->
                val a = max(0, i - w); val b = min(ibis.size, i + w + 1)
                median(ibis.copyOfRange(a, b))
            }
            var drifting = false
            for (v in smooth) if (abs(v / globalIbi - 1) > 0.03) { drifting = true; break }
            for (t in 0 until n) {
                if (!drifting) { periods[t] = globalIbi; continue }
                val bi = lowerBound(beats, t)
                val idx = (bi - 1).coerceIn(0, smooth.size - 1)
                periods[t] = smooth[idx].coerceIn(globalIbi * 0.93, globalIbi * 1.07)
            }
            val second = dp(local, periods, alpha)
            if (second.size >= 4) beats = second
        }
        if (snap) snapToGrid(local, beats)?.let { return it to true }
        return beats to false
    }

    /**
     * When >= 80 % of the tracked beats sit within 12 % of a single straight beat grid the tempo is steady: rebuild
     * the beat list from that grid, each point snapped to the strongest onset within +-15 % of the period (or left on
     * the grid where there is no onset at all, e.g. a breakdown). Returns null when the tempo is not steady.
     */
    internal fun snapToGrid(local: FloatArray, beats: IntArray): IntArray? {
        val nb = beats.size
        if (nb < 16) return null
        val t = DoubleArray(nb) { beats[it].toDouble() }
        val ibis = DoubleArray(nb - 1) { t[it + 1] - t[it] }
        val period = median(ibis)
        if (period < 5) return null
        // beat numbers by cumulative rounding of each interval (a global division would drift when the integer-frame
        // median period is not exactly the true one)
        val k = IntArray(nb)
        for (i in 1 until nb) k[i] = k[i - 1] + max(1, (ibis[i - 1] / period).roundToInt())
        val step = max(1, nb / 4)
        val slopes = ArrayList<Double>()
        for (i in 0 until nb - step) if (k[i + step] != k[i]) slopes.add((t[i + step] - t[i]) / (k[i + step] - k[i]))
        if (slopes.isEmpty()) return null
        val slope = median(slopes.toDoubleArray())
        val icpt = median(DoubleArray(nb) { t[it] - slope * k[it] })
        var inl = 0
        for (i in 0 until nb) if (abs(t[i] - (icpt + slope * k[i])) < 0.12 * slope) inl++
        debug?.invoke("steady-grid inliers=${"%.3f".format(inl.toDouble() / nb)}")
        if (inl < inlierThreshold * nb) return null
        val firstK = k[0]; val lastK = k[nb - 1]
        val out = IntArray(lastK - firstK + 1)
        val peaks = FloatArray(out.size)
        val win = max(1, (0.15 * slope).roundToInt())
        for (j in out.indices) {
            val g = icpt + slope * (firstK + j)
            val gf = g.roundToInt()
            var best = gf; var bv = -1f
            for (f in gf - win..gf + win) if (f in 0 until local.size && local[f] > bv) { bv = local[f]; best = f }
            out[j] = if (bv >= 0f) best else gf.coerceIn(0, local.size - 1)
            peaks[j] = max(bv, 0f)
        }
        val ref = median(DoubleArray(peaks.size) { peaks[it].toDouble() })
        for (j in out.indices) if (peaks[j] < 0.2 * ref) out[j] = (icpt + slope * (firstK + j)).roundToInt().coerceIn(0, local.size - 1)
        return out
    }

    private fun dp(local: FloatArray, periods: DoubleArray, tightness: Double): IntArray {
        val n = local.size
        val score = DoubleArray(n)
        val prev = IntArray(n) { -1 }
        for (t in 0 until n) {
            val p = periods[t]
            val lo = max(0, t - (2 * p).roundToInt())
            val hi = t - max(1, (p / 2).roundToInt())
            var best = 0.0; var arg = -1
            for (tau in lo..hi) {
                val ratio = (t - tau) / p
                val v = score[tau] - tightness * ln(ratio) * ln(ratio)
                if (v > best) { best = v; arg = tau }
            }
            score[t] = local[t] + best
            prev[t] = arg
        }
        // start the backtrace from the best-scoring local maximum in the final beat period
        var thr = 0.0
        val maxima = ArrayList<Double>()
        for (t in 1 until n - 1) if (score[t] >= score[t - 1] && score[t] > score[t + 1]) maxima.add(score[t])
        if (maxima.isNotEmpty()) { maxima.sort(); thr = 0.5 * maxima[maxima.size / 2] }
        var last = -1
        for (t in n - 2 downTo 1) if (score[t] >= score[t - 1] && score[t] > score[t + 1] && score[t] >= thr) { last = t; break }
        if (last < 0) return IntArray(0)
        val list = ArrayList<Int>()
        var c = last
        while (c >= 0) { list.add(c); c = prev[c] }
        list.reverse()
        return list.toIntArray()
    }

    private fun trimUnsupported(t: DoubleArray, frames: IntArray, det: FloatArray): Pair<DoubleArray, IntArray> {
        val n = det.size
        val mean = OnsetEnvelope.mean(det)
        fun strength(i: Int): Float {
            var m = 0f
            for (d in -2..2) { val j = frames[i] + d; if (j in 0 until n && det[j] > m) m = det[j] }
            return m
        }
        var a = 0; var b = t.size
        val thr = mean * 0.6f
        while (a < b - 4 && strength(a) < thr) a++
        while (b > a + 4 && strength(b - 1) < thr) b--
        return t.copyOfRange(a, b) to frames.copyOfRange(a, b)
    }

    /** Sliding robust line fit over +-8 beats; follows tempo drift, removes frame quantisation noise. */
    fun smoothGrid(t: DoubleArray): DoubleArray {
        val n = t.size
        val out = DoubleArray(n)
        val half = 8
        for (i in 0 until n) {
            val a = max(0, i - half); val b = min(n, i + half + 1)
            var (slope, icpt) = fitLine(t, a, b, i, null)
            // one robust refit without the outliers (>20 ms)
            val keep = BooleanArray(b - a) { abs(t[a + it] - (icpt + slope * (a + it - i))) < 0.02 }
            if (keep.count { it } >= 5) {
                val r = fitLine(t, a, b, i, keep); slope = r.first; icpt = r.second
            }
            out[i] = icpt
        }
        return out
    }

    private fun fitLine(t: DoubleArray, a: Int, b: Int, center: Int, keep: BooleanArray?): Pair<Double, Double> {
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0; var cnt = 0
        for (j in a until b) {
            if (keep != null && !keep[j - a]) continue
            val x = (j - center).toDouble(); val y = t[j]
            sx += x; sy += y; sxx += x * x; sxy += x * y; cnt++
        }
        val den = cnt * sxx - sx * sx
        val slope = if (abs(den) < 1e-9) 0.0 else (cnt * sxy - sx * sy) / den
        return slope to (sy - slope * sx) / cnt
    }

    private fun gaussianSmooth(x: FloatArray, sigma: Double): FloatArray {
        val r = (sigma * 3).roundToInt().coerceAtLeast(1)
        val w = DoubleArray(2 * r + 1) { exp(-0.5 * ((it - r) / sigma) * ((it - r) / sigma)) }
        val s = w.sum()
        val out = FloatArray(x.size)
        for (i in x.indices) {
            var acc = 0.0
            for (k in -r..r) { val j = i + k; if (j in x.indices) acc += x[j] * w[k + r] }
            out[i] = (acc / s).toFloat()
        }
        return out
    }

    private fun lowerBound(a: IntArray, v: Int): Int {
        var lo = 0; var hi = a.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (a[m] < v) lo = m + 1 else hi = m }
        return lo
    }

    internal fun median(a: DoubleArray): Double {
        if (a.isEmpty()) return 0.0
        a.sort()
        return if (a.size % 2 == 1) a[a.size / 2] else 0.5 * (a[a.size / 2 - 1] + a[a.size / 2])
    }
}
