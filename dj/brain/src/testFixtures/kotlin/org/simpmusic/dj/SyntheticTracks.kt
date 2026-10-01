package org.simpmusic.dj

import org.simpmusic.dj.model.*
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * A tiny deterministic "band": four-on-the-floor kick, snare on 2 and 4, hats, a bass line and a chord pad in a
 * known key, arranged in sections. Because we generate it, ground truth (beats, downbeats, key, sections) is
 * known exactly, which lets tests assert analyzer and renderer accuracy without shipping any real music.
 */
object SyntheticTracks {
    data class SectionSpec(val kind: SectionKind, val bars: Int)

    data class Spec(
        val bpm: Float,
        val key: MusicalKey,
        val sections: List<SectionSpec> = listOf(
            SectionSpec(SectionKind.INTRO, 8),
            SectionSpec(SectionKind.BODY, 16),
            SectionSpec(SectionKind.BREAKDOWN, 8),
            SectionSpec(SectionKind.DROP, 16),
            SectionSpec(SectionKind.OUTRO, 8),
        ),
        val beatsPerBar: Int = 4,
        val sampleRate: Int = 22050,
        /** Time of the first beat. Real tracks rarely start exactly on the beat. */
        val firstBeatMs: Int = 180,
        /** 0..0.3: delays the off-beat eighths (swing). The beat grid itself stays straight. */
        val swing: Float = 0f,
        val seed: Long = 1L,
        val videoId: String = "synthetic-${bpm.toInt()}-${key.pitchClass}-${key.mode}",
    )

    class Truth(
        val bpm: Float,
        val key: MusicalKey,
        val beatTimesMs: List<Int>,
        val downbeatBeatIndices: List<Int>,
        val sections: List<Section>,
        val durationMs: Long,
    )

    class Rendered(val audio: PcmAudio, val truth: Truth, val spec: Spec)

    private class Rng(seed: Long) {
        private var s = seed xor 0x5DEECE66DL
        fun next(): Float {
            s = s * 6364136223846793005L + 1442695040888963407L
            return (((s ushr 33).toInt()) / 1073741824f) - 1f // -1..1
        }
    }

    // Diatonic triads (semitone offsets from the tonic) for a I-vi-IV-V (major) / i-VI-III-VII (minor) loop.
    private val MAJOR_PROG = listOf(0 to intArrayOf(0, 4, 7), 9 to intArrayOf(0, 3, 7), 5 to intArrayOf(0, 4, 7), 7 to intArrayOf(0, 4, 7))
    private val MINOR_PROG = listOf(0 to intArrayOf(0, 3, 7), 8 to intArrayOf(0, 4, 7), 3 to intArrayOf(0, 4, 7), 10 to intArrayOf(0, 4, 7))

    fun render(spec: Spec): Rendered {
        val sr = spec.sampleRate
        val beatMs = 60000.0 / spec.bpm
        val totalBars = spec.sections.sumOf { it.bars }
        val totalBeats = totalBars * spec.beatsPerBar
        val durationMs = spec.firstBeatMs + (totalBeats * beatMs).toLong() + 1500L
        val n = (durationMs * sr / 1000).toInt()
        val out = FloatArray(n)
        val rng = Rng(spec.seed)

        val beatTimes = ArrayList<Int>()
        val downbeats = ArrayList<Int>()
        val sections = ArrayList<Section>()

        var barIndex = 0
        val prog = if (spec.key.mode == Mode.MAJOR) MAJOR_PROG else MINOR_PROG
        var sectionStartBar = 0
        for (sec in spec.sections) {
            val energy = when (sec.kind) {
                SectionKind.INTRO -> 0.45f
                SectionKind.BODY -> 0.8f
                SectionKind.BREAKDOWN -> 0.35f
                SectionKind.DROP -> 1f
                SectionKind.OUTRO -> 0.5f
                else -> 0.6f
            }
            for (b in 0 until sec.bars) {
                val (chordRoot, triad) = prog[barIndex % prog.size]
                val barStartMs = spec.firstBeatMs + barIndex * spec.beatsPerBar * beatMs
                val drums = when (sec.kind) {
                    SectionKind.BREAKDOWN -> false
                    else -> true
                }
                val fullDrums = sec.kind == SectionKind.BODY || sec.kind == SectionKind.DROP
                val hats = sec.kind != SectionKind.INTRO || b >= sec.bars / 2
                val bass = sec.kind != SectionKind.INTRO && sec.kind != SectionKind.OUTRO || b >= 0 && sec.kind == SectionKind.OUTRO && b < sec.bars / 2
                // Pad: chord for the whole bar; louder in breakdowns (it is the only thing playing).
                val padGain = when (sec.kind) {
                    SectionKind.BREAKDOWN -> 0.32f
                    SectionKind.INTRO -> 0.22f
                    else -> 0.16f
                }
                addChord(out, sr, barStartMs, spec.beatsPerBar * beatMs, spec.key.pitchClass + chordRoot, triad, padGain)
                if (sec.kind == SectionKind.DROP || sec.kind == SectionKind.BODY) {
                    // arpeggio lead on 8ths gives melodic chroma without masking the drums
                    for (e in 0 until spec.beatsPerBar * 2) {
                        val note = spec.key.pitchClass + chordRoot + triad[e % 3] + 12
                        val t = barStartMs + e * beatMs / 2 + if (e % 2 == 1) spec.swing * beatMs / 2 else 0.0
                        addNote(out, sr, t, beatMs * 0.45, midiFreq(72 + ((note % 12))), 0.05f, harmonics = 3)
                    }
                }
                for (beat in 0 until spec.beatsPerBar) {
                    val t = barStartMs + beat * beatMs
                    beatTimes += t.toInt()
                    if (beat == 0) downbeats += beatTimes.size - 1
                    if (drums) {
                        val accent = if (beat == 0) 1f else 0.85f
                        if (fullDrums || beat % 2 == 0) addKick(out, sr, t, 0.75f * accent * (0.6f + 0.4f * energy))
                        if (fullDrums && beat % 2 == 1) addSnare(out, sr, t, 0.45f, rng)
                        if (hats) {
                            addHat(out, sr, t + if (false) 0.0 else beatMs / 2 + spec.swing * beatMs / 2, 0.18f, rng, open = true)
                            if (fullDrums) addHat(out, sr, t, 0.10f, rng, open = false)
                        }
                    }
                    if (bass) {
                        val root = spec.key.pitchClass + chordRoot
                        addNote(out, sr, t, beatMs * 0.42, midiFreq(36 + root % 12 + 12 * 0), 0.30f, harmonics = 2)
                        addNote(out, sr, t + beatMs / 2 + spec.swing * beatMs / 2, beatMs * 0.3, midiFreq(36 + root % 12 + 12), 0.18f, harmonics = 2)
                    }
                }
                // Crash on the first bar of each 8-bar phrase: a clear phrase/downbeat cue, like real arrangements.
                if (barIndex % 8 == 0 && sec.kind != SectionKind.BREAKDOWN) addCrash(out, sr, barStartMs, 0.35f, rng)
                barIndex++
            }
            val startMs = spec.firstBeatMs + sectionStartBar * spec.beatsPerBar * beatMs
            val endMs = spec.firstBeatMs + (sectionStartBar + sec.bars) * spec.beatsPerBar * beatMs
            sections += Section(TimeRange(startMs.toLong(), endMs.toLong()), sec.kind, energy)
            sectionStartBar += sec.bars
        }
        // soft limiter
        var peak = 0f
        for (v in out) if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
        if (peak > 0.9f) { val g = 0.9f / peak; for (i in out.indices) out[i] *= g }

        val truth = Truth(spec.bpm, spec.key, beatTimes, downbeats, sections, durationMs)
        return Rendered(PcmAudio(out, sr), truth, spec)
    }

    /** Sample-exact click track with an accented downbeat, for measuring renderer alignment to the millisecond. */
    fun clickTrack(bpm: Float, bars: Int, firstBeatMs: Int = 0, beatsPerBar: Int = 4, sampleRate: Int = 44100, tailMs: Int = 1000): Rendered {
        val beatMs = 60000.0 / bpm
        val totalBeats = bars * beatsPerBar
        val durationMs = firstBeatMs + (totalBeats * beatMs).toLong() + tailMs
        val out = FloatArray((durationMs * sampleRate / 1000).toInt())
        val beats = ArrayList<Int>()
        val downs = ArrayList<Int>()
        for (i in 0 until totalBeats) {
            val t = firstBeatMs + i * beatMs
            beats += t.toInt()
            val down = i % beatsPerBar == 0
            if (down) downs += i
            val f = if (down) 1500.0 else 1000.0
            val start = (t * sampleRate / 1000).toInt()
            val len = (0.012 * sampleRate).toInt()
            for (k in 0 until len) {
                if (start + k >= out.size) break
                out[start + k] += (sin(2 * PI * f * k / sampleRate) * exp(-k / (0.003 * sampleRate)) * 0.9).toFloat()
            }
        }
        val truth = Truth(bpm, MusicalKey(0, Mode.MAJOR), beats, downs, emptyList(), durationMs)
        return Rendered(PcmAudio(out, sampleRate), truth, Spec(bpm, truth.key, sampleRate = sampleRate, firstBeatMs = firstBeatMs, videoId = "click-${bpm.toInt()}"))
    }

    /** Click track whose beat i sits at [beatAt] (ms), for tempo curves a constant-bpm [clickTrack] cannot express. */
    fun clickTrackAt(beats: Int, nominalBpm: Float, beatsPerBar: Int = 4, sampleRate: Int = 44100, tailMs: Int = 1000, beatAt: (Int) -> Double): Rendered {
        val durationMs = beatAt(beats - 1).toLong() + tailMs
        val out = FloatArray((durationMs * sampleRate / 1000).toInt())
        val times = ArrayList<Int>()
        val downs = ArrayList<Int>()
        for (i in 0 until beats) {
            val t = beatAt(i)
            times += Math.round(t).toInt()
            val down = i % beatsPerBar == 0
            if (down) downs += i
            val f = if (down) 1500.0 else 1000.0
            val start = Math.round(t * sampleRate / 1000).toInt()
            val len = (0.012 * sampleRate).toInt()
            for (k in 0 until len) {
                if (start + k >= out.size) break
                out[start + k] += (sin(2 * PI * f * k / sampleRate) * exp(-k / (0.003 * sampleRate)) * 0.9).toFloat()
            }
        }
        val truth = Truth(nominalBpm, MusicalKey(0, Mode.MAJOR), times, downs, emptyList(), durationMs)
        return Rendered(PcmAudio(out, sampleRate), truth, Spec(nominalBpm, truth.key, sampleRate = sampleRate, videoId = "clickat-${nominalBpm.toInt()}"))
    }

    private fun midiFreq(m: Int) =440.0 * 2.0.pow((m - 69) / 12.0)

    private fun addNote(out: FloatArray, sr: Int, startMs: Double, lenMs: Double, freq: Double, gain: Float, harmonics: Int) {
        val s = (startMs * sr / 1000).toInt()
        val len = (lenMs * sr / 1000).toInt()
        for (k in 0 until len) {
            val i = s + k
            if (i !in out.indices) continue
            val env = minOf(1.0, k / (0.004 * sr)) * exp(-3.0 * k / len)
            var v = 0.0
            for (h in 1..harmonics) v += sin(2 * PI * freq * h * k / sr) / h
            out[i] += (v * env * gain).toFloat()
        }
    }

    private fun addChord(out: FloatArray, sr: Int, startMs: Double, lenMs: Double, rootPc: Int, triad: IntArray, gain: Float) {
        val s = (startMs * sr / 1000).toInt()
        val len = (lenMs * sr / 1000).toInt()
        for (semi in triad) {
            val midi = 48 + ((rootPc + semi) % 12) // C3..B3 region keeps pitch classes unambiguous
            val f = midiFreq(midi)
            for (k in 0 until len) {
                val i = s + k
                if (i !in out.indices) continue
                val env = minOf(1.0, k / (0.05 * sr)) * minOf(1.0, (len - k) / (0.05 * sr))
                var v = 0.0
                for (h in 1..5) v += sin(2 * PI * f * h * k / sr) / (h * h * 0.7 + 0.3)
                out[i] += (v * env * gain / 3.0).toFloat()
            }
        }
    }

    private fun addKick(out: FloatArray, sr: Int, startMs: Double, gain: Float) {
        val s = (startMs * sr / 1000).toInt()
        val len = (0.22 * sr).toInt()
        var phase = 0.0
        for (k in 0 until len) {
            val i = s + k
            if (i !in out.indices) continue
            val t = k.toDouble() / sr
            val f = 48.0 + 110.0 * exp(-t * 32.0)
            phase += 2 * PI * f / sr
            out[i] += (sin(phase) * exp(-t * 14.0) * gain).toFloat()
        }
    }

    private fun addSnare(out: FloatArray, sr: Int, startMs: Double, gain: Float, rng: Rng) {
        val s = (startMs * sr / 1000).toInt()
        val len = (0.16 * sr).toInt()
        for (k in 0 until len) {
            val i = s + k
            if (i !in out.indices) continue
            val t = k.toDouble() / sr
            val body = sin(2 * PI * 190.0 * t) * exp(-t * 28.0) * 0.5
            val noise = rng.next() * exp(-t * 22.0) * 0.7
            out[i] += ((body + noise) * gain).toFloat()
        }
    }

    private fun addHat(out: FloatArray, sr: Int, startMs: Double, gain: Float, rng: Rng, open: Boolean) {
        val s = (startMs * sr / 1000).toInt()
        val len = ((if (open) 0.09 else 0.035) * sr).toInt()
        var prev = 0f
        for (k in 0 until len) {
            val i = s + k
            if (i !in out.indices) continue
            val t = k.toDouble() / sr
            val x = rng.next()
            val hp = x - prev * 0.95f // crude high-pass
            prev = x
            out[i] += (hp * exp(-t * (if (open) 40.0 else 120.0)) * gain).toFloat()
        }
    }

    private fun addCrash(out: FloatArray, sr: Int, startMs: Double, gain: Float, rng: Rng) {
        val s = (startMs * sr / 1000).toInt()
        val len = (0.9 * sr).toInt()
        var prev = 0f
        for (k in 0 until len) {
            val i = s + k
            if (i !in out.indices) continue
            val t = k.toDouble() / sr
            val x = rng.next()
            val hp = x - prev * 0.9f
            prev = x
            out[i] += (hp * exp(-t * 4.5) * gain).toFloat()
        }
    }
}
