package org.simpmusic.dj.ml

import org.simpmusic.dj.model.Confident
import org.simpmusic.dj.model.PcmAudio
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.model.TrackAnalyzer
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Runs [base] (key, energy, sections, timbre... e.g. the DSP analyzer) and overlays the beat / downbeat / bpm /
 * phrase fields of [beats] on top of it. When the beat provider has nothing (too short, no beats found, runtime
 * failure) the base analysis is returned untouched, so the planner degrades exactly as it would without the model.
 */
class CompositeAnalyzer(
    private val base: TrackAnalyzer,
    private val beats: BeatProvider,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Fills `vocals` when given. Deliberately NOT part of [id]: a new id would make every stored analysis stale and
     * re-analyse the whole library; the scheduler adds the vocals to the playing and next tracks instead.
     */
    private val vocals: VocalProvider? = null,
) : TrackAnalyzer {
    override val id: String = "${base.id}+${beats.id}"

    override fun analyze(videoId: String, audio: PcmAudio): TrackAnalysis {
        val analysis = base.analyze(videoId, audio)
        val grid = try {
            beats.grid(audio)
        } catch (e: Exception) {
            null // model missing / runtime error: keep the DSP result rather than failing the whole analysis
        }
        // Without a grid the DSP result is kept, but it must still carry THIS analyzer's id: the store treats a row whose
        // analyzerId differs from the analyzer's id as stale and deletes it on sight, so an untouched "dsp-1" row would be
        // re-analysed forever and the playing track would wait for an analysis that never sticks.
        val a = if (grid == null) analysis.copy(analyzerId = id) else overlay(analysis, grid)
        val v = vocals ?: return a
        return try {
            v.detect(audio)?.let { a.copy(vocals = it.ranges, vocalProfile = it.profile) } ?: a
        } catch (e: Exception) {
            a
        }
    }

    private fun overlay(a: TrackAnalysis, g: BeatGrid): TrackAnalysis {
        val snapped = TransientSnap.refine(g.beatTimesMs, g.phraseStartsMs, a.beatTimesMs)
        return a.copy(
            analyzerId = id,
            analyzedAtEpochMs = clock(),
            bpm = g.bpm?.let { Confident(it, g.bpmConfidence) },
            beatTimesMs = Confident(snapped.beatTimesMs, g.beatConfidence),
            downbeatBeatIndices = if (g.downbeatBeatIndices.isEmpty()) null else Confident(g.downbeatBeatIndices, g.downbeatConfidence),
            beatsPerBar = g.beatsPerBar,
            phraseStartsMs = snapped.phraseStartsMs,
            beatDownbeatLogits = g.downbeatLogits?.takeIf { it.size == snapped.beatTimesMs.size },
        )
    }
}

/**
 * Beat This! decides WHICH beats exist and where the downbeats are, but it marks the perceived pulse in the
 * annotation convention of its training data, which sits 2-22 ms away from the physical kick transient depending on the
 * track (measured on the real corpus). The DSP analyzer pins its beats to the onset envelope instead. When the DSP grid is
 * trustworthy and agrees with the neural one, each neural beat takes the time of its DSP twin, so two decks line up on
 * their transients rather than on annotation convention. Otherwise the neural times are kept untouched.
 */
internal object TransientSnap {
    class Result(val beatTimesMs: List<Int>, val phraseStartsMs: List<Long>)

    private const val MAX_SNAP_MS = 35
    private const val MIN_DSP_CONFIDENCE = 0.6f
    private const val MIN_AGREEMENT = 0.8

    fun refine(neural: List<Int>, phrases: List<Long>, dsp: Confident<List<Int>>?): Result {
        val keep = Result(neural, phrases)
        if (dsp == null || dsp.confidence < MIN_DSP_CONFIDENCE || dsp.value.size < 8 || neural.isEmpty()) return keep
        val d = dsp.value
        val twin = IntArray(neural.size) { Int.MIN_VALUE }
        var j = 0
        var matched = 0
        for (i in neural.indices) {
            while (j + 1 < d.size && abs(d[j + 1] - neural[i]) <= abs(d[j] - neural[i])) j++
            if (abs(d[j] - neural[i]) <= MAX_SNAP_MS) { twin[i] = d[j]; matched++ }
        }
        if (matched.toDouble() / neural.size < MIN_AGREEMENT) return keep
        val shifts = neural.indices.filter { twin[it] != Int.MIN_VALUE }.map { twin[it] - neural[it] }.sorted()
        val median = shifts[shifts.size / 2]
        val refined = neural.indices.map { if (twin[it] != Int.MIN_VALUE) twin[it] else neural[it] + median }
        // phrase starts ride on beats: move each by the shift of the beat it sits on
        val phr = phrases.map { p ->
            val k = neural.indices.minByOrNull { abs(neural[it] - p) }
            if (k != null && abs(neural[k] - p) <= 20) refined[k].toLong() + (p - neural[k]) else p
        }
        return Result(refined, phr)
    }

    private fun abs(x: Int) = if (x < 0) -x else x
    private fun abs(x: Long) = if (x < 0) -x else x
}

/**
 * Standalone neural analyzer. Fills bpm / beats / downbeats / beatsPerBar / phrases from Beat This!; everything
 * else comes from the optional [base] (wrap it in a [CompositeAnalyzer] semantic: the base is run first and the
 * neural grid overlaid). Without a base only a coarse energy envelope and loudness are produced (key, sections,
 * vocals and timbre stay empty).
 */
class BeatThisAnalyzer(
    private val tracker: BeatProvider,
    private val base: TrackAnalyzer? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : TrackAnalyzer {
    private val delegate: TrackAnalyzer = CompositeAnalyzer(base ?: EnvelopeOnlyAnalyzer(clock), tracker, clock)
    override val id: String = "beat-this-onnx-1".let { if (base == null) it else "${base.id}+$it" }

    override fun analyze(videoId: String, audio: PcmAudio): TrackAnalysis =
        delegate.analyze(videoId, audio).copy(analyzerId = id)
}

/** Minimal base used when no DSP analyzer is available: duration, loudness and a 500 ms RMS envelope. */
internal class EnvelopeOnlyAnalyzer(private val clock: () -> Long) : TrackAnalyzer {
    override val id: String = "envelope-only"

    override fun analyze(videoId: String, audio: PcmAudio): TrackAnalysis {
        val hopMs = 500
        val hop = audio.sampleRate * hopMs / 1000
        val n = audio.samples.size
        val rms = ArrayList<Float>()
        var total = 0.0
        var i = 0
        while (i < n) {
            val end = minOf(n, i + hop)
            var s = 0.0
            for (k in i until end) s += audio.samples[k].toDouble() * audio.samples[k]
            total += s
            rms.add(sqrt(s / (end - i)).toFloat())
            i = end
        }
        val peak = rms.maxOrNull()?.takeIf { it > 0f } ?: 1f
        val energy = rms.map { it / peak }
        val loudness = if (n == 0 || total <= 0.0) -90f else (10.0 * log10(total / n)).toFloat().coerceAtLeast(-90f)
        return TrackAnalysis(
            videoId = videoId,
            analyzerId = id,
            analyzedAtEpochMs = clock(),
            durationMs = audio.durationMs,
            bpm = null,
            beatTimesMs = null,
            downbeatBeatIndices = null,
            key = null,
            energyHopMs = hopMs,
            energy = energy,
            lowBandEnergy = energy.map { 0f }, // no band split without a DSP analyzer
            sections = null,
            vocals = null,
            loudnessDb = loudness,
        )
    }
}
