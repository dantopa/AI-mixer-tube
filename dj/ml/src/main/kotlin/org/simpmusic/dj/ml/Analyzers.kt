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
) : TrackAnalyzer {
    override val id: String = "${base.id}+${beats.id}"

    override fun analyze(videoId: String, audio: PcmAudio): TrackAnalysis {
        val analysis = base.analyze(videoId, audio)
        val grid = try {
            beats.grid(audio)
        } catch (e: Exception) {
            null // model missing / runtime error: keep the DSP result rather than failing the whole analysis
        } ?: return analysis
        return overlay(analysis, grid)
    }

    private fun overlay(a: TrackAnalysis, g: BeatGrid): TrackAnalysis = a.copy(
        analyzerId = id,
        analyzedAtEpochMs = clock(),
        bpm = g.bpm?.let { Confident(it, g.bpmConfidence) },
        beatTimesMs = Confident(g.beatTimesMs, g.beatConfidence),
        downbeatBeatIndices = if (g.downbeatBeatIndices.isEmpty()) null else Confident(g.downbeatBeatIndices, g.downbeatConfidence),
        beatsPerBar = g.beatsPerBar,
        phraseStartsMs = g.phraseStartsMs,
    )
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
