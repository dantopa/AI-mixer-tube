package org.simpmusic.dj.analysis

import org.simpmusic.dj.model.Confident
import org.simpmusic.dj.model.TrackAnalysis
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Bar-anchored beat-grid repair ("grid arbiter").
 *
 * Measured on 46 real cumbia tracks (owner's radio, Beat This! + DSP analysis): in about half of them the beat grid
 * drops beats (an interval of two beats) and then locks onto the tresillo / güiro subdivision (intervals of 1/3 and 2/3
 * of the beat), so the grid's tempo jumps between ~90 and ~135 BPM inside one song and the planner sees an "irregular
 * grid". The DOWNBEATS of the same analysis stay on a steady bar (or half bar) the whole time.
 *
 * So the bar is the referee: the beat period is taken from the downbeat spacing, and a new grid is rebuilt by dynamic
 * programming (Ellis 2007) over the original beats and downbeats used as onset evidence, with the period allowed to
 * drift slowly (live bands). Each new beat is then moved onto the original beat it matches, when there is one within
 * [SNAP_MS], so the transient precision of the neural grid is kept; beats the tracker missed are filled in.
 *
 * A grid that is already regular is returned unchanged, so clean electronic tracks and the synthetic tests are not
 * touched. Anything that does not fit the expectations (too few beats or bars, no stable bar) is also left alone.
 */
object GridRepair {
    /** Fraction of intervals within [REGULAR_TOL] of the median beyond which the grid is trusted as it is. */
    private const val REGULAR_SHARE = 0.85
    private const val REGULAR_TOL = 0.08
    private const val MIN_BEATS = 32
    private const val MIN_BARS = 8
    /** Folded bar lengths (ms) a bar is looked for in: a 4/4 bar of beats between 60 and 133 BPM. */
    private const val BAR_MIN_MS = 1800.0
    private const val BAR_MAX_MS = 4000.0
    /** Share of downbeat gaps that must agree with the chosen bar. */
    private const val BAR_AGREEMENT = 0.45
    private const val STEP_MS = 10
    private const val EVIDENCE_SIGMA_MS = 25.0
    private const val TIGHTNESS = 400.0
    const val SNAP_MS = 45L

    data class Report(val repaired: Boolean, val reason: String, val barMs: Double = 0.0, val beatMs: Double = 0.0, val snapped: Int = 0, val filled: Int = 0)

    fun repair(a: TrackAnalysis): TrackAnalysis = repairWithReport(a).first

    /** Off only for measurements (the corpus reports compare with and without the repair). */
    @Volatile var enabled: Boolean = true

    private val cache =
        object : LinkedHashMap<TrackAnalysis, TrackAnalysis>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TrackAnalysis, TrackAnalysis>?) = size > 256
        }

    /**
     * [repair] behind a small LRU keyed by the analysis itself (structural equality, so a copy with any field changed
     * is a different key): the recommender plans the same tracks against many others. A repaired grid is regular, so
     * feeding it back in returns it unchanged.
     */
    fun cached(a: TrackAnalysis): TrackAnalysis {
        if (!enabled) return a
        synchronized(cache) { cache[a]?.let { return it } }
        val r = repair(a)
        synchronized(cache) { cache[a] = r }
        return r
    }

    fun repairWithReport(a: TrackAnalysis): Pair<TrackAnalysis, Report> {
        val beatsC = a.beatTimesMs ?: return a to Report(false, "no beats")
        val beats = beatsC.value.map { it.toLong() }.distinct().sorted()
        if (beats.size < MIN_BEATS) return a to Report(false, "too few beats")
        val intervals = DoubleArray(beats.size - 1) { (beats[it + 1] - beats[it]).toDouble() }
        val med = intervals.sorted()[intervals.size / 2]
        val regular = intervals.count { abs(it / med - 1.0) <= REGULAR_TOL }.toDouble() / intervals.size
        if (regular >= REGULAR_SHARE) return a to Report(false, "regular (%.0f%%)".format(regular * 100))

        val downIdx = a.downbeatBeatIndices?.value?.filter { it in beatsC.value.indices }?.distinct()?.sorted().orEmpty()
        val downTimes = downIdx.map { beatsC.value[it].toLong() }.distinct().sorted()
        if (downTimes.size < MIN_BARS) return a to Report(false, "too few downbeats")

        // ---- bar period from the downbeat gaps, folded into one 4/4 bar range ----
        val folded = ArrayList<Double>()
        for (i in 0 until downTimes.size - 1) {
            var d = (downTimes[i + 1] - downTimes[i]).toDouble()
            if (d <= 0) continue
            while (d < BAR_MIN_MS) d *= 2
            while (d > BAR_MAX_MS) d /= 2
            folded += d
        }
        if (folded.size < MIN_BARS - 1) return a to Report(false, "too few bar gaps")
        val bar = modeOf(folded) ?: return a to Report(false, "no bar peak")
        val agree = folded.count { abs(it / bar - 1.0) <= 0.04 }.toDouble() / folded.size
        if (agree < BAR_AGREEMENT) return a to Report(false, "no stable bar (%.0f%% agree)".format(agree * 100))
        // Beats per bar: the tracker's own 3 is not trusted (on cumbia it comes from counting tresillo beats), so the
        // division of the bar the original beats actually sit on decides; 3 has to win clearly over 4.
        val support4 = latticeSupport(beats, downTimes, bar / 4)
        val support3 = latticeSupport(beats, downTimes, bar / 3)
        val perBar = if (support3 > support4 * 1.2) 3 else 4
        val period = bar / perBar

        // ---- onset evidence: original beats (1) and downbeats (2), gaussian-smoothed ----
        val t0 = beats.first()
        val t1 = beats.last()
        val n = ((t1 - t0) / STEP_MS).toInt() + 1
        val ev = DoubleArray(n)
        val reach = (3 * EVIDENCE_SIGMA_MS / STEP_MS).toInt()
        fun splat(t: Long, w: Double) {
            val c = (t - t0).toDouble() / STEP_MS
            val ci = c.roundToInt()
            for (k in max(0, ci - reach)..min(n - 1, ci + reach)) {
                val x = (k - c) * STEP_MS / EVIDENCE_SIGMA_MS
                ev[k] += w * Math.exp(-0.5 * x * x)
            }
        }
        beats.forEach { splat(it, 1.0) }
        downTimes.forEach { splat(it, 1.0) }

        // ---- DP: best beat sequence with spacing near the period (log-ratio penalty, Ellis) ----
        val pSteps = period / STEP_MS
        val lo = (pSteps * 0.88).toInt().coerceAtLeast(1)
        val hi = (pSteps * 1.12).toInt() + 1
        val score = DoubleArray(n)
        val back = IntArray(n) { -1 }
        for (k in 0 until n) {
            var best = 0.0
            var arg = -1
            for (j in k - hi..k - lo) {
                if (j < 0) continue
                val r = ln((k - j) / pSteps)
                val s = score[j] - TIGHTNESS * r * r
                if (arg < 0 || s > best) { best = s; arg = j }
            }
            // Ellis: continue the best chain when it is worth something, otherwise start a new one here
            if (arg >= 0 && best > 0) { score[k] = ev[k] + best; back[k] = arg } else score[k] = ev[k]
        }
        var end = n - 1
        for (k in max(0, n - hi) until n) if (score[k] > score[end]) end = k
        val path = ArrayList<Int>()
        var k = end
        while (k >= 0) { path += k; k = back[k] }
        path.reverse()
        if (path.size < MIN_BEATS) return a to Report(false, "dp path too short")

        // ---- snap each new beat to the original beat it matches, fill the rest ----
        var snapped = 0
        val out = LongArray(path.size)
        for ((i, s) in path.withIndex()) {
            val t = t0 + s.toLong() * STEP_MS
            val near = nearest(beats, t)
            if (abs(near - t) <= SNAP_MS) { out[i] = near; snapped++ } else out[i] = t
        }
        val grid = out.distinct().sorted()
        val newIntervals = DoubleArray(grid.size - 1) { (grid[it + 1] - grid[it]).toDouble() }
        val newRegular = newIntervals.count { abs(it / period - 1.0) <= REGULAR_TOL }.toDouble() / newIntervals.size
        if (newRegular <= regular) return a to Report(false, "repair not more regular (%.0f%% -> %.0f%%)".format(regular * 100, newRegular * 100), bar, period)

        // ---- downbeats: the phase (mod beats per bar) that hits most of the original downbeats ----
        var bestPhase = 0
        var bestHits = -1
        for (ph in 0 until perBar) {
            var hits = 0
            var i = ph
            while (i < grid.size) { if (abs(nearest(downTimes, grid[i]) - grid[i]) <= 70) hits++; i += perBar }
            if (hits > bestHits) { bestHits = hits; bestPhase = ph }
        }
        val newDown = (bestPhase until grid.size step perBar).toList()
        val downShare = bestHits.toDouble() / max(1, newDown.size)
        val downConf = ((a.downbeatBeatIndices?.confidence ?: 0f) * min(1.0, downShare / 0.6).toFloat()).coerceIn(0f, 1f)

        val bpm = 60000.0 * (grid.size - 1) / (grid.last() - grid.first())
        val repaired =
            a.copy(
                beatTimesMs = Confident(grid.map { it.toInt() }, beatsC.confidence),
                downbeatBeatIndices = Confident(newDown, downConf),
                bpm = Confident(bpm.toFloat(), a.bpm?.confidence ?: beatsC.confidence),
            )
        return repaired to Report(true, "bar %.0f ms, beat %.0f ms (%.1f bpm), regular %.0f%% -> %.0f%%".format(bar, period, 60000 / period, regular * 100, newRegular * 100), bar, period, snapped, grid.size - snapped)
    }

    /** Share of [beats] within [SNAP_MS] of a lattice of [period] anchored on the downbeat at or before each beat. */
    private fun latticeSupport(beats: List<Long>, downs: List<Long>, period: Double): Double {
        var hits = 0
        var counted = 0
        for (t in beats) {
            val d = downs.lastOrNull { it <= t } ?: continue
            counted++
            val x = (t - d) % period
            if (min(x, period - x) <= SNAP_MS) hits++
        }
        return if (counted == 0) 0.0 else hits.toDouble() / counted
    }

    private fun nearest(sorted: List<Long>, t: Long): Long {
        var lo = 0
        var hi = sorted.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < t) lo = mid + 1 else hi = mid
        }
        return if (lo > 0 && abs(sorted[lo - 1] - t) <= abs(sorted[lo] - t)) sorted[lo - 1] else sorted[lo]
    }

    /** Peak of a 20 ms histogram (3-bin smoothed), refined as the mean of the values within 4 % of it. */
    private fun modeOf(xs: List<Double>): Double? {
        if (xs.isEmpty()) return null
        val bins = HashMap<Int, Int>()
        xs.forEach { bins.merge((it / 20).roundToInt(), 1, Int::plus) }
        val peak = bins.keys.maxByOrNull { (bins[it - 1] ?: 0) + 2 * (bins[it] ?: 0) + (bins[it + 1] ?: 0) } ?: return null
        val c = peak * 20.0
        val near = xs.filter { abs(it / c - 1.0) <= 0.04 }
        return if (near.isEmpty()) c else near.average()
    }
}
