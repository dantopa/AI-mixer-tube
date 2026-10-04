package org.simpmusic.dj.analysis

import org.simpmusic.dj.SyntheticTracks
import org.simpmusic.dj.model.TrackAnalysis
import kotlin.math.abs

/** Ground-truth comparison used by the tests and the benchmark. */
class Eval(val a: TrackAnalysis, val r: SyntheticTracks.Rendered) {
    val truth = r.truth
    val bpmErrPct: Double = a.bpm?.let { abs(it.value - truth.bpm).toDouble() / truth.bpm * 100 } ?: Double.NaN

    private val est: List<Int> = a.beatTimesMs?.value ?: emptyList()

    /** For each truth beat: distance to the nearest estimated beat (ms). */
    val beatErrors: List<Double> = truth.beatTimesMs.map { t -> est.minOfOrNull { abs(it - t).toDouble() } ?: Double.MAX_VALUE }
    val beatMedianErr: Double get() = beatErrors.sorted().let { it[it.size / 2] }
    val beatWithin40: Double get() = beatErrors.count { it <= 40 }.toDouble() / beatErrors.size

    /** Fraction of truth downbeats matched by an estimated downbeat within 60 ms. */
    val downbeatRecall: Double
        get() {
            val idx = a.downbeatBeatIndices?.value ?: return 0.0
            val times = idx.map { est[it] }
            val td = truth.downbeatBeatIndices.map { truth.beatTimesMs[it] }
            return td.count { t -> times.any { abs(it - t) <= 60 } }.toDouble() / td.size
        }

    /** Fraction of estimated downbeats (inside the music) that sit on a truth downbeat. */
    val downbeatPrecision: Double
        get() {
            val idx = a.downbeatBeatIndices?.value ?: return 0.0
            val times = idx.map { est[it] }.filter { it >= truth.beatTimesMs.first() - 60 && it <= truth.beatTimesMs.last() + 60 }
            val td = truth.downbeatBeatIndices.map { truth.beatTimesMs[it] }
            if (times.isEmpty()) return 0.0
            return times.count { t -> td.any { abs(it - t) <= 60 } }.toDouble() / times.size
        }

    val keyCorrect: Boolean get() = a.key?.value == truth.key

    /** Fraction of the truth's internal section boundaries with an estimated boundary within one bar. */
    val sectionBoundaryHit: Double
        get() {
            val secs = a.sections?.value ?: return 0.0
            val bar = 60000.0 / truth.bpm * r.spec.beatsPerBar
            val estB = secs.drop(1).map { it.range.startMs }
            val truthB = truth.sections.drop(1).map { it.range.startMs }
            return truthB.count { t -> estB.any { abs(it - t) <= bar } }.toDouble() / truthB.size
        }

    override fun toString(): String =
        "bpm=${a.bpm?.value?.let { "%.2f".format(it) }}(${a.bpm?.confidence?.let { "%.2f".format(it) }}) err=${"%.3f".format(bpmErrPct)}% " +
            "beats=${est.size}/${truth.beatTimesMs.size} c=${a.beatTimesMs?.confidence?.let { "%.2f".format(it) }} med=${"%.1f".format(beatMedianErr)}ms in40=${"%.3f".format(beatWithin40)} " +
            "down rec=${"%.2f".format(downbeatRecall)} prec=${"%.2f".format(downbeatPrecision)} c=${a.downbeatBeatIndices?.confidence?.let { "%.2f".format(it) }} bpb=${a.beatsPerBar} " +
            "key=${a.key?.value}(${a.key?.confidence?.let { "%.2f".format(it) }}) ok=$keyCorrect sect=${"%.2f".format(sectionBoundaryHit)}(${a.sections?.confidence?.let { "%.2f".format(it) }}, n=${a.sections?.value?.size})"
}
