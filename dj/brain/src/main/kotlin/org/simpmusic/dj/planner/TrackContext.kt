package org.simpmusic.dj.planner

import org.simpmusic.dj.model.Confident
import org.simpmusic.dj.model.Section
import org.simpmusic.dj.model.SectionKind
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.model.Trust
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Least-squares line `time = a + p * index` through a run of beats. */
internal class BeatFit(val a: Double, val p: Double, val rms: Double, val points: Int) {
    fun timeAt(index: Int): Double = a + p * index
}

/**
 * Quadratic least-squares model `time(i) = c0 + c1*x + c2*x^2` (x = i - i0) of the beats i0..i0+n-1: the local tempo curve.
 * [noiseRms] is the residual to that curve (detector jitter, swing), [medianIbi] the median inter-beat interval.
 */
internal class LocalGrid(val i0: Int, val n: Int, val c0: Double, val c1: Double, val c2: Double, val noiseRms: Double, val medianIbi: Double) {
    fun timeAt(i: Int): Double { val x = (i - i0).toDouble(); return c0 + c1 * x + c2 * x * x }

    /** The fitted curve at a fractional beat index. */
    fun timeAtD(i: Double): Double { val x = i - i0; return c0 + c1 * x + c2 * x * x }

    /** Local beat period (ms per beat) of the fitted curve at a fractional beat index: its derivative. */
    fun slopeAt(i: Double): Double = c1 + 2.0 * c2 * (i - i0)

    /** Mean beat period over beats [a, b] (ms), from the curve. */
    fun meanPeriod(a: Int, b: Int): Double = (timeAt(b) - timeAt(a)) / max(1, b - a)

    /** Largest deviation (ms) of the curve from a straight line through beats a and b, over [a, b]: what a constant rate cannot follow. */
    fun driftMs(a: Int, b: Int): Double {
        if (b <= a) return 0.0
        val ta = timeAt(a)
        val p = (timeAt(b) - ta) / (b - a)
        var worst = 0.0
        for (i in a..b) worst = max(worst, abs(timeAt(i) - (ta + p * (i - a))))
        return worst
    }
}

/**
 * A sanitised, trust-annotated view of one [TrackAnalysis]. Everything the planner reads goes through here so that
 * unsorted lists, NaNs, empty grids and absurd durations are dealt with in exactly one place.
 */
internal class TrackContext(val analysis: TrackAnalysis) {
    val id: String = analysis.videoId
    private val rawDuration: Long = analysis.durationMs.coerceIn(0L, MAX_DURATION_MS)

    /** Beat times, ascending, distinct, non-negative. Empty when there is no usable grid. */
    val beats: LongArray = cleanTimes(analysis.beatTimesMs?.value?.map { it.toLong() }, if (rawDuration > 0) rawDuration else MAX_DURATION_MS)
    val beatsConf: Float = if (beats.size >= MIN_BEATS) analysis.beatTimesMs?.confidence ?: 0f else 0f

    val durationMs: Long = if (rawDuration > 0) rawDuration else if (beats.isNotEmpty()) beats.last() + 1000L else 0L

    /** Median beat interval of the grid (ms), 0 if unusable. */
    val medianBeatMs: Double = medianInterval(beats)
    val gridBpm: Double = if (medianBeatMs > 0) 60000.0 / medianBeatMs else 0.0

    /**
     * Tempo from beats per unit of time over the whole grid. Unlike the median interval it is not pulled around by a
     * tracker that inserts or drops beats, so the two disagreeing is a sign the grid is not to be trusted for tempo.
     */
    val avgBpm: Double = if (beats.size >= 2 && beats.last() > beats.first()) 60000.0 * (beats.size - 1) / (beats.last() - beats.first()) else 0.0

    /**
     * True when a tempo measured locally (bpm) is the same tempo the track has as a whole, allowing the half / double
     * lattice: it must match the whole-grid average or the tempo field. Nothing to compare against counts as agreement.
     */
    fun localTempoAgrees(localBpm: Double): Boolean {
        val refs = listOfNotNull(avgBpm.takeIf { it > 0 }, bpmValue)
        if (refs.isEmpty() || localBpm <= 0) return true
        return refs.any { ref -> listOf(1.0, 2.0, 0.5).any { abs(localBpm / ref / it - 1.0) < LOCAL_TEMPO_TOLERANCE } }
    }

    val bpmValue: Double? = analysis.bpm?.value?.toDouble()?.takeIf { it.isFinite() && it in 30.0..320.0 }
    val bpmConf: Float = if (bpmValue != null) analysis.bpm?.confidence ?: 0f else 0f

    val beatsPerBar: Int = (analysis.beatsPerBar ?: 4).let { if (it in 2..8) it else 4 }

    val downbeatTimes: LongArray = run {
        val idx = analysis.downbeatBeatIndices?.value
        if (idx == null || beats.isEmpty()) LongArray(0)
        else cleanTimes(idx.filter { it in beats.indices }.map { beats[it] }, MAX_DURATION_MS)
    }
    val downbeatConf: Float = if (downbeatTimes.size >= 2) analysis.downbeatBeatIndices?.confidence ?: 0f else 0f
    val barsTrusted: Boolean = beatsConf >= Trust.BEATS && downbeatConf >= Trust.DOWNBEATS

    /** Phrase starts snapped onto the beat grid (dropped when they do not land within half a beat of it). */
    val phraseTimes: LongArray = if (!barsTrusted || beats.isEmpty()) LongArray(0) else {
        val snapped = ArrayList<Long>()
        for (p in analysis.phraseStartsMs) {
            val b = beats[nearestBeatIndex(p)]
            if (abs(b - p) <= medianBeatMs / 2) snapped += b
        }
        cleanTimes(snapped, MAX_DURATION_MS)
    }

    val sections: List<Section>? = analysis.sections?.takeIf { it.confidence >= Trust.SECTIONS }?.value
    val sectionsConf: Float = analysis.sections?.confidence ?: 0f

    val hopMs: Int = analysis.energyHopMs.coerceIn(0, 60_000)
    val energy: FloatArray = sanitise(analysis.energy)
    val lowBand: FloatArray = sanitise(analysis.lowBandEnergy)

    /** First time the track is audible (energy above a small floor for two hops), 0 when unknown. */
    val firstAudibleMs: Long = run {
        if (hopMs <= 0 || energy.size < 2) return@run 0L
        for (i in 0 until energy.size - 1) if (energy[i] > SILENCE && energy[i + 1] > SILENCE) return@run i.toLong() * hopMs
        0L
    }

    /** End of the audible part (last hop above the floor, plus a hop), <= duration. */
    val audibleEndMs: Long = run {
        if (hopMs <= 0 || energy.isEmpty()) return@run durationMs
        var last = -1
        for (i in energy.indices) if (energy[i] > SILENCE) last = i
        if (last < 0) durationMs else min(durationMs, (last + 1).toLong() * hopMs + 250L)
    }

    val loudnessDb: Float? = analysis.loudnessDb.takeIf { it.isFinite() && it in -70f..0f }

    fun nearestBeatIndex(t: Long): Int {
        if (beats.isEmpty()) return 0
        var lo = 0
        var hi = beats.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (beats[mid] < t) lo = mid + 1 else hi = mid
        }
        return if (lo > 0 && abs(beats[lo - 1] - t) <= abs(beats[lo] - t)) lo - 1 else lo
    }

    /** Line fit over beats [i0, i1] (clamped). */
    fun fit(i0: Int, i1: Int): BeatFit? {
        val a = max(0, i0)
        val b = min(beats.lastIndex, i1)
        val n = b - a + 1
        if (n < 4) return null
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (i in a..b) {
            val x = (i - a).toDouble()
            val y = beats[i].toDouble()
            sx += x; sy += y; sxx += x * x; sxy += x * y
        }
        val den = n * sxx - sx * sx
        if (den <= 0.0) return null
        val p = (n * sxy - sx * sy) / den
        val c = (sy - p * sx) / n
        var se = 0.0
        for (i in a..b) {
            val e = beats[i] - (c + p * (i - a))
            se += e * e
        }
        // express as index-absolute line: time = (c - p*a) + p*i
        return BeatFit(c - p * a, p, kotlin.math.sqrt(se / n), n)
    }

    /** Local tempo model over beats [i0, i1] (clamped); null when fewer than 6 beats. */
    fun local(i0: Int, i1: Int): LocalGrid? {
        val a = max(0, i0)
        val b = min(beats.lastIndex, i1)
        val n = b - a + 1
        if (n < 6) return null
        // normal equations for [1, x, x^2], x centred on the window to keep them well conditioned
        val mid = (n - 1) / 2.0
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
        var t0 = 0.0; var t1 = 0.0; var t2 = 0.0
        for (k in 0 until n) {
            val x = k - mid
            val y = beats[a + k].toDouble()
            val x2 = x * x
            s0 += 1.0; s1 += x; s2 += x2; s3 += x2 * x; s4 += x2 * x2
            t0 += y; t1 += x * y; t2 += x2 * y
        }
        val det = s0 * (s2 * s4 - s3 * s3) - s1 * (s1 * s4 - s3 * s2) + s2 * (s1 * s3 - s2 * s2)
        if (abs(det) < 1e-9) return null
        val q0 = (t0 * (s2 * s4 - s3 * s3) - s1 * (t1 * s4 - s3 * t2) + s2 * (t1 * s3 - s2 * t2)) / det
        val q1 = (s0 * (t1 * s4 - s3 * t2) - t0 * (s1 * s4 - s3 * s2) + s2 * (s1 * t2 - t1 * s2)) / det
        val q2 = (s0 * (s2 * t2 - t1 * s3) - s1 * (s1 * t2 - t1 * s2) + t0 * (s1 * s3 - s2 * s2)) / det
        // re-express in x = i - a: x' = k, x = k - mid
        val c2 = q2
        val c1 = q1 - 2 * q2 * mid
        val c0 = q0 - q1 * mid + q2 * mid * mid
        var se = 0.0
        for (k in 0 until n) { val e = beats[a + k] - (c0 + c1 * k + c2 * k * k); se += e * e }
        val d = DoubleArray(n - 1) { (beats[a + it + 1] - beats[a + it]).toDouble() }
        d.sort()
        return LocalGrid(a, n, c0, c1, c2, sqrt(se / n), d[d.size / 2])
    }

    /** Median inter-beat interval of the beats inside [fromMs, toMs]; 0 if fewer than 4 beats. */
    fun medianIbiIn(fromMs: Long, toMs: Long): Double {
        val sel = beats.filter { it in fromMs..toMs }
        if (sel.size < 4) return 0.0
        val d = DoubleArray(sel.size - 1) { (sel[it + 1] - sel[it]).toDouble() }
        d.sort()
        return d[d.size / 2]
    }

    fun meanOver(values: FloatArray, fromMs: Long, toMs: Long): Float? {
        if (hopMs <= 0 || values.isEmpty()) return null
        val i0 = (fromMs / hopMs).toInt().coerceIn(0, values.size - 1)
        val i1 = (toMs / hopMs).toInt().coerceIn(i0, values.size - 1)
        var s = 0f
        for (i in i0..i1) s += values[i]
        return s / (i1 - i0 + 1)
    }

    /** The last OUTRO section, if sections are trusted. */
    fun outro(): Section? = sections?.filter { it.kind == SectionKind.OUTRO && it.range.endMs > it.range.startMs }?.maxByOrNull { it.range.startMs }

    /** The INTRO section that contains [t] (or starts within a beat of it). */
    fun introAt(t: Long): Section? = sections?.firstOrNull {
        it.kind == SectionKind.INTRO && t >= it.range.startMs - medianBeatMs && t < it.range.endMs
    }

    companion object {
        const val MAX_DURATION_MS = 24L * 3600_000L
        const val MIN_BEATS = 8
        const val SILENCE = 0.02f

        fun cleanTimes(list: List<Long>?, max: Long): LongArray {
            if (list.isNullOrEmpty()) return LongArray(0)
            return list.filter { it in 0..max }.distinct().sorted().toLongArray()
        }

        fun medianInterval(t: LongArray): Double {
            if (t.size < 3) return 0.0
            val d = DoubleArray(t.size - 1) { (t[it + 1] - t[it]).toDouble() }
            d.sort()
            val m = d[d.size / 2]
            return if (m in 150.0..2000.0) m else 0.0
        }

        private fun sanitise(l: List<Float>): FloatArray = FloatArray(l.size) { l[it].let { v -> if (v.isFinite()) v.coerceIn(0f, 1f) else 0f } }
    }
}

internal fun Double.toLongClamped(): Long = if (this.isNaN()) 0L else this.coerceIn(-9e15, 9e15).roundToLong()

internal fun <T> Confident<T>?.conf(): Float = this?.confidence ?: 0f

/** How far (fraction) a local tempo may sit from the track's overall tempo before its beat grid is distrusted. */
internal const val LOCAL_TEMPO_TOLERANCE = 0.06
