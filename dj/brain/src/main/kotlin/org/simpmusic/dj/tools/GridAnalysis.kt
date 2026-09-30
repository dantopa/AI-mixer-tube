package org.simpmusic.dj.tools

import org.simpmusic.dj.model.Confident
import org.simpmusic.dj.model.Mode
import org.simpmusic.dj.model.MusicalKey
import org.simpmusic.dj.model.PcmAudio
import org.simpmusic.dj.model.TrackAnalysis
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Stand-in analysis for the CLI demo until the real analyzer is wired in: a uniform beat grid from a (given or
 * roughly estimated) tempo and first-beat time, plus energy envelopes measured from the audio. Not a beat tracker;
 * it exists so [MixDemo] works on any two WAV files with `--bpm-*` / `--first-beat-*` overrides.
 */
object GridAnalysis {
    fun build(
        videoId: String,
        audio: PcmAudio,
        bpm: Float? = null,
        firstBeatMs: Int? = null,
        key: MusicalKey? = null,
        beatsPerBar: Int = 4,
        phraseBeats: Int = 16,
    ): TrackAnalysis {
        val sr = audio.sampleRate
        val x = audio.samples
        val durationMs = x.size * 1000L / sr
        val hop = 100
        val hopN = sr * hop / 1000
        val n = x.size / hopN + 1
        val energy = FloatArray(n)
        val low = FloatArray(n)
        var lp = 0.0
        val a = 1.0 - Math.exp(-2.0 * Math.PI * 200.0 / sr)
        var totalSq = 0.0
        for (i in 0 until n) {
            var s = 0.0
            var sl = 0.0
            val from = i * hopN
            val to = min(x.size, from + hopN)
            for (k in from until to) {
                val v = x[k].toDouble()
                s += v * v
                lp += a * (v - lp)
                sl += lp * lp
            }
            val cnt = max(1, to - from)
            energy[i] = sqrt(s / cnt).toFloat()
            low[i] = sqrt(sl / cnt).toFloat()
            totalSq += s
        }
        val maxE = max(energy.max(), 1e-9f)
        val maxL = max(low.max(), 1e-9f)
        for (i in 0 until n) { energy[i] /= maxE; low[i] /= maxL }
        val rmsDb = (20 * log10(sqrt(totalSq / max(1, x.size)) + 1e-9)).toFloat().coerceIn(-70f, 0f)

        val estimated = if (bpm == null || firstBeatMs == null) estimateTempo(x, sr) else null
        val useBpm = bpm ?: estimated?.first ?: 120f
        val first = firstBeatMs ?: estimated?.second ?: 0
        val conf = if (bpm != null && firstBeatMs != null) 0.9f else if (bpm != null) 0.7f else 0.6f
        val beatMs = 60000.0 / useBpm
        val beats = ArrayList<Int>()
        var t = first.toDouble()
        while (t < durationMs) { beats += t.toInt(); t += beatMs }
        val downs = (beats.indices step beatsPerBar).toList()
        val phrases = (beats.indices step phraseBeats).map { beats[it].toLong() }
        return TrackAnalysis(
            videoId = videoId, analyzerId = "demo-grid-1", analyzedAtEpochMs = 0L, durationMs = durationMs,
            bpm = Confident(useBpm, conf), beatTimesMs = Confident(beats, conf), downbeatBeatIndices = Confident(downs, min(conf, 0.8f)),
            beatsPerBar = beatsPerBar, phraseStartsMs = phrases,
            key = key?.let { Confident(it, 0.9f) },
            energyHopMs = hop, energy = energy.toList(), lowBandEnergy = low.toList(),
            sections = null, vocals = null, loudnessDb = rmsDb,
        )
    }

    /** Crude tempo + first-beat estimate from an onset envelope (autocorrelation in 70..180 bpm). */
    fun estimateTempo(x: FloatArray, sr: Int): Pair<Float, Int> {
        val hopN = max(1, sr / 200) // 5 ms
        val n = x.size / hopN
        if (n < 400) return 120f to 0
        val env = FloatArray(n)
        var prev = 0f
        for (i in 0 until n) {
            var s = 0f
            for (k in i * hopN until (i + 1) * hopN) s += abs(x[k])
            env[i] = max(0f, s - prev)
            prev = s
        }
        val minLag = (60.0 / 180 / 0.005).toInt()
        val maxLag = (60.0 / 70 / 0.005).toInt()
        var bestLag = minLag
        var best = -1.0
        val limit = min(n, 200 * 60) // first minute is plenty
        for (lag in minLag..maxLag) {
            var s = 0.0
            for (i in 0 until limit - lag) s += env[i] * env[i + lag].toDouble()
            // weight against octave errors
            if (s > best) { best = s; bestLag = lag }
        }
        val bpm = (60.0 / (bestLag * 0.005)).toFloat()
        // phase: comb over one period
        var bestPhase = 0
        var bestSum = -1.0
        for (ph in 0 until bestLag) {
            var s = 0.0
            var i = ph
            while (i < limit) { s += env[i]; i += bestLag }
            if (s > bestSum) { bestSum = s; bestPhase = ph }
        }
        return bpm to bestPhase * 5
    }

    /** "8A", "Am", "C", "F#m", "Bbmin" -> key. */
    fun parseKey(text: String): MusicalKey? {
        val s = text.trim()
        Regex("""^(\d{1,2})([ABab])$""").matchEntire(s)?.let { m ->
            val num = m.groupValues[1].toInt()
            val minor = m.groupValues[2].uppercase() == "A"
            for (pc in 0..11) for (mode in Mode.values()) {
                val k = MusicalKey(pc, mode)
                if (org.simpmusic.dj.model.Camelot.number(k) == num && (mode == Mode.MINOR) == minor) return k
            }
            return null
        }
        Regex("""^([A-Ga-g])([#b]?)(m|min|minor)?$""").matchEntire(s)?.let { m ->
            val base = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)[m.groupValues[1].uppercase()[0]]!!
            val pc = (base + when (m.groupValues[2]) { "#" -> 1; "b" -> -1; else -> 0 } + 12) % 12
            return MusicalKey(pc, if (m.groupValues[3].isNotEmpty()) Mode.MINOR else Mode.MAJOR)
        }
        return null
    }
}
