package org.simpmusic.dj.analysis

import org.simpmusic.dj.model.Confident
import org.simpmusic.dj.model.PcmAudio
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.model.TrackAnalyzer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Dependency-free DSP analyzer ("dsp-1"): resampling, STFT/onset envelope, tempo, Ellis beat tracking, downbeats and
 * phrases, key, energy curves, sections and timbre. Pure Kotlin + kotlin.math, so it runs unchanged on the JVM and on
 * Android. See dj/docs/analysis.md for the algorithm choices and measured accuracy.
 *
 * Confidence semantics: every [Confident] is calibrated so that <= ~0.3 means "do not act on this" and >= ~0.7 means
 * "as reliable as this analyzer gets". Noise, silence and pitch-less material come out near 0 or null instead of
 * garbage-with-high-confidence.
 */
class DspTrackAnalyzer : TrackAnalyzer {
    override val id: String = ID

    override fun analyze(videoId: String, audio: PcmAudio): TrackAnalysis {
        val timings = Timings()
        return analyzeInternal(videoId, audio, timings)
    }

    /** Per-stage wall-clock milliseconds of the last [analyzeTimed] call; for benchmarks. */
    class Timings {
        val stages = LinkedHashMap<String, Long>()
        inline fun <T> stage(name: String, block: () -> T): T {
            val t0 = System.nanoTime()
            val r = block()
            stages[name] = (System.nanoTime() - t0) / 1_000_000
            return r
        }
    }

    fun analyzeTimed(videoId: String, audio: PcmAudio): Pair<TrackAnalysis, Timings> {
        val t = Timings()
        return analyzeInternal(videoId, audio, t) to t
    }

    private fun analyzeInternal(videoId: String, audio: PcmAudio, tm: Timings): TrackAnalysis {
        val durationMs = audio.durationMs
        val x = tm.stage("prepare") { prepare(audio) }
        val energy = tm.stage("energy") { EnergyCurves.compute(x) }
        val eNorm = energy.normalised(smooth121(energy.rms))
        val lowNorm = energy.normalised(smooth121(energy.lowRms))
        val silent = x.isEmpty() || energy.loudnessDb < SILENCE_DB
        val base = TrackAnalysis(
            videoId = videoId,
            analyzerId = ID,
            analyzedAtEpochMs = System.currentTimeMillis(),
            durationMs = durationMs,
            bpm = null, beatTimesMs = null, downbeatBeatIndices = null, beatsPerBar = null,
            key = null,
            energyHopMs = Grid.SPEC_HOP_MS,
            energy = if (silent) List(eNorm.size) { 0f } else eNorm.toList(),
            lowBandEnergy = if (silent) List(lowNorm.size) { 0f } else lowNorm.toList(),
            sections = null, vocals = null,
            loudnessDb = energy.loudnessDb,
        )
        if (silent || x.size < Grid.SR * 1.5) return base

        val on = tm.stage("onset-stft") { OnsetFeatures.compute(x) }
        val sp = tm.stage("spectral-stft") { SpectralFeatures.compute(x) }
        val timbre = meanMfcc(sp)

        // ---- tempo + beats
        val raw = OnsetEnvelope.combined(on)
        val det = OnsetEnvelope.detrend(raw)
        val detLow = OnsetEnvelope.detrend(on.fluxLow)
        val tempo = tm.stage("tempo") { TempoEstimator.estimate(det, detLow) }
        val beat = tm.stage("beats") { tempo?.let { BeatTracker.track(raw, det, it.bpm) } }

        var bpm: Confident<Float>? = null
        var beatsC: Confident<List<Int>>? = null
        var downC: Confident<List<Int>>? = null
        var bpb: Int? = null
        var phrases: List<Long> = emptyList()
        var downTimes: DoubleArray? = null
        var phraseTimes: DoubleArray? = null
        var barSec = 0.0
        var phraseConf = 0.0
        if (tempo != null && beat != null) {
            val conf = rhythmConfidence(tempo, beat, x.size / Grid.SR.toDouble())
            bpm = Confident(beat.bpm.toFloat(), conf.bpm.toFloat())
            val beatMs = beat.timesSec.map { (it * 1000.0).roundToInt() }
            beatsC = Confident(beatMs, conf.beats.toFloat())
            val db = tm.stage("downbeats") { DownbeatAnalyzer.analyze(beat.timesSec, on, sp) }
            if (db != null) {
                bpb = db.beatsPerBar
                val idx = (db.phase until beat.timesSec.size step db.beatsPerBar).toList()
                val dconf = (db.phaseConfidence * min(1.0, conf.beats + 0.15) * (0.6 + 0.4 * db.meterConfidence))
                downC = Confident(idx, dconf.coerceIn(0.0, 1.0).toFloat())
                downTimes = DoubleArray(idx.size) { beat.timesSec[idx[it]] }
                barSec = (beat.timesSec[idx.last()] - beat.timesSec[idx.first()]) / max(1, idx.size - 1)
                val ph = tm.stage("phrases") { PhraseAnalyzer.analyze(beat.timesSec, idx.toIntArray(), db.features, on, sp) }
                if (ph != null) {
                    phraseConf = ph.confidence
                    phraseTimes = DoubleArray(ph.phraseBars.size) { downTimes!![ph.phraseBars[it]] }
                    phrases = phraseTimes!!.map { (it * 1000).toLong() }
                } else if (downTimes.size >= 4) {
                    phraseTimes = DoubleArray(downTimes.size / 4 + 1) { downTimes!![it * 4] }.filter { it > 0 }.toDoubleArray()
                    phrases = phraseTimes!!.map { (it * 1000).toLong() }
                }
            }
        }

        // ---- key
        val keyRes = tm.stage("key") { KeyEstimator.estimate(sp, phraseTimes ?: DoubleArray(0), barSec, phraseConf) }
        val key = keyRes?.let { Confident(it.key, it.confidence.toFloat()) }

        // ---- sections
        val (aStart, aEnd) = activeRange(energy.rms, eNorm)
        val sec = tm.stage("sections") {
            SectionAnalyzer.analyze(
                durationSec = x.size / Grid.SR.toDouble(), activeStartSec = aStart, activeEndSec = aEnd,
                energy = eNorm, low = lowNorm, activity = activityGrid(on), sp = sp,
                beats = if (beatsC != null && (beatsC.confidence >= 0.3f)) beat!!.timesSec else null,
                downbeatTimesSec = downTimes, phraseTimesSec = phraseTimes,
            )
        }
        val sectionsC = sec?.let { Confident(it.sections, it.confidence.toFloat().coerceIn(0f, 1f)) }
        return base.copy(
            bpm = bpm, beatTimesMs = beatsC, downbeatBeatIndices = downC, beatsPerBar = bpb,
            phraseStartsMs = phrases, key = key, sections = sectionsC, timbre = timbre,
        )
    }

    /** [1 2 1]/4 smoothing: removes the beat-phase flutter of 100 ms RMS blocks without blurring section edges. */
    private fun smooth121(a: FloatArray): FloatArray {
        if (a.size < 3) return a
        return FloatArray(a.size) { i -> 0.25f * a[max(0, i - 1)] + 0.5f * a[i] + 0.25f * a[min(a.size - 1, i + 1)] }
    }

    /** Mean onset flux per 100 ms block, scaled to 0..1 (how busy the arrangement is). */
    private fun activityGrid(on: OnsetFeatures): FloatArray {
        val blocks = (on.nFrames * Grid.ONSET_HOP + Grid.SPEC_HOP - 1) / Grid.SPEC_HOP
        val out = FloatArray(blocks); val cnt = IntArray(blocks)
        for (f in 0 until on.nFrames) {
            val b = min(blocks - 1, f * Grid.ONSET_HOP / Grid.SPEC_HOP)
            out[b] += on.flux[f]; cnt[b]++
        }
        var m = 0f
        for (b in 0 until blocks) { if (cnt[b] > 0) out[b] /= cnt[b]; if (out[b] > m) m = out[b] }
        if (m > 0f) for (b in 0 until blocks) out[b] /= m
        return out
    }

    private class RhythmConf(val bpm: Double, val beats: Double)

    /**
     * Honest rhythm confidence. Three independent witnesses: how periodic the onset envelope is at the chosen tempo
     * (autocorrelation), how much stronger onsets are on the beats than elsewhere, and how regular the tracked
     * grid is. Noise fails the first two, silence-with-clicks fails the last.
     */
    private fun rhythmConfidence(t: TempoEstimate, b: BeatResult, seconds: Double): RhythmConf {
        val periodic = ((t.pulse - 0.10) / 0.30).coerceIn(0.0, 1.0)
        val support = ((b.support - 1.6) / 3.0).coerceIn(0.0, 1.0)
        val regular = ((b.regularity - 0.5) / 0.4).coerceIn(0.0, 1.0)
        val enough = min(1.0, b.timesSec.size / 24.0)
        // two unrelated tempo readings (not just half/double time) that fit equally well: say so
        val ambiguity = 1.0 - 0.45 * ((t.ambiguity - 0.8) / 0.2).coerceIn(0.0, 1.0)
        val core = periodic * (0.3 + 0.7 * support) * ambiguity
        val bpmC = core * (0.4 + 0.6 * regular) * (0.5 + 0.5 * enough)
        val beatsC = core * (0.2 + 0.8 * regular) * (0.5 + 0.5 * enough)
        return RhythmConf(bpmC.coerceIn(0.0, 1.0), beatsC.coerceIn(0.0, 1.0))
    }

    private fun meanMfcc(sp: SpectralFeatures): List<Float> {
        val out = DoubleArray(SpectralFeatures.MFCC_N)
        var cnt = 0
        for (j in 0 until sp.nFrames) {
            if (!sp.active[j]) continue
            for (k in out.indices) out[k] += sp.mfcc[j * SpectralFeatures.MFCC_N + k]
            cnt++
        }
        if (cnt == 0) return emptyList()
        return out.map { (it / cnt).toFloat() }
    }

    /** First and last 100 ms blocks above 2 % of the peak level (trims leading / trailing silence). */
    private fun activeRange(rms: FloatArray, norm: FloatArray): Pair<Double, Double> {
        var a = 0; var b = norm.size - 1
        while (a < b && norm[a] < 0.02f) a++
        while (b > a && norm[b] < 0.02f) b--
        return (a * Grid.SPEC_HOP_MS / 1000.0) to ((b + 1) * Grid.SPEC_HOP_MS / 1000.0)
    }

    /** Mono float at [Grid.SR], finite, DC free. */
    internal fun prepare(audio: PcmAudio): FloatArray {
        val src = audio.samples
        val clean = FloatArray(src.size)
        for (i in src.indices) {
            val v = src[i]
            clean[i] = if (v.isNaN() || v.isInfinite()) 0f else v.coerceIn(-4f, 4f)
        }
        val x = if (audio.sampleRate == Grid.SR) clean else Resampler.resample(clean, max(1, audio.sampleRate), Grid.SR)
        // one-pole DC blocker (~20 Hz)
        var px = 0f; var py = 0f
        val r = 0.9943f
        for (i in x.indices) {
            val y = x[i] - px + r * py
            px = x[i]; py = y
            x[i] = y
        }
        return x
    }

    companion object {
        const val ID = "dsp-1"
        private const val SILENCE_DB = -75f
    }
}
