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

/** Least-squares line `time = a + p * index` through a run of beats. */
internal class BeatFit(val a: Double, val p: Double, val rms: Double, val points: Int) {
    fun timeAt(index: Int): Double = a + p * index
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
