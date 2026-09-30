package org.simpmusic.dj.analysis

import org.simpmusic.dj.SyntheticTracks
import org.simpmusic.dj.SyntheticTracks.SectionSpec
import org.simpmusic.dj.model.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DspTrackAnalyzerTest {
    private val analyzer = DspTrackAnalyzer()
    private val majorC = MusicalKey(0, Mode.MAJOR)

    private fun run(spec: SyntheticTracks.Spec): Pair<Eval, TrackAnalysis> {
        val r = SyntheticTracks.render(spec)
        val a = analyzer.analyze(spec.videoId, r.audio)
        return Eval(a, r) to a
    }

    private val tempos = listOf(70f, 90f, 100f, 118f, 124f, 128f, 140f, 150f, 174f)

    @Test
    fun idAndSchema() {
        assertEquals("dsp-1", analyzer.id)
        val (_, a) = run(SyntheticTracks.Spec(bpm = 124f, key = majorC))
        assertEquals("dsp-1", a.analyzerId)
        assertEquals(TrackAnalysis.SCHEMA_VERSION, a.schemaVersion)
        assertEquals(100, a.energyHopMs)
        assertTrue(a.energy.all { it in 0f..1f } && a.energy.max() == 1f)
        assertTrue(a.lowBandEnergy.all { it in 0f..1f })
        assertEquals(13, a.timbre.size)
        assertTrue(a.loudnessDb in -40f..0f)
    }

    @Test
    fun tempoAndBeatsAcrossTempos() {
        for (bpm in tempos) {
            val (e, a) = run(SyntheticTracks.Spec(bpm = bpm, key = majorC))
            println("tempo $bpm: $e")
            assertTrue(e.bpmErrPct < 0.3, "bpm $bpm -> ${a.bpm?.value} (err ${e.bpmErrPct}%)")
            assertTrue(a.bpm!!.confidence >= Trust.BPM, "bpm confidence ${a.bpm!!.confidence} at $bpm")
            assertTrue(a.beatTimesMs!!.confidence >= Trust.BEATS)
            assertTrue(e.beatMedianErr < 15, "median beat error ${e.beatMedianErr} ms at $bpm")
            assertTrue(e.beatWithin40 > 0.95, "only ${e.beatWithin40} of beats within 40 ms at $bpm")
            // beats ascend, roughly evenly spaced
            val beats = a.beatTimesMs!!.value
            assertTrue(beats.zipWithNext().all { (x, y) -> y > x })
        }
    }

    @Test
    fun swingAndOffsetFirstBeat() {
        for (bpm in listOf(90f, 124f, 150f)) for (first in listOf(0, 180, 731, 1234)) for (swing in listOf(0f, 0.15f)) {
            val (e, a) = run(SyntheticTracks.Spec(bpm = bpm, key = majorC, firstBeatMs = first, swing = swing))
            println("bpm=$bpm first=$first swing=$swing: $e")
            assertTrue(e.bpmErrPct < 0.3, "bpm $bpm first=$first swing=$swing -> ${a.bpm?.value}")
            assertTrue(e.beatMedianErr < 15, "median beat error ${e.beatMedianErr} (bpm=$bpm first=$first swing=$swing)")
            assertTrue(e.beatWithin40 > 0.95, "within 40 ms: ${e.beatWithin40} (bpm=$bpm first=$first swing=$swing)")
            assertTrue(e.downbeatRecall >= 0.9 && e.downbeatPrecision >= 0.9, "downbeats rec=${e.downbeatRecall} prec=${e.downbeatPrecision} (bpm=$bpm first=$first swing=$swing)")
            assertTrue(e.sectionBoundaryHit >= 0.75, "sections ${e.sectionBoundaryHit} (bpm=$bpm first=$first swing=$swing)")
            assertTrue(e.keyCorrect, "key (bpm=$bpm first=$first swing=$swing)")
        }
    }

    @Test
    fun downbeatsFourAndThree() {
        for (bpm in tempos) {
            val (e, a) = run(SyntheticTracks.Spec(bpm = bpm, key = majorC))
            assertEquals(4, a.beatsPerBar, "beatsPerBar at $bpm")
            assertTrue(e.downbeatRecall >= 0.9, "downbeat recall ${e.downbeatRecall} at $bpm")
            assertTrue(e.downbeatPrecision >= 0.9, "downbeat precision ${e.downbeatPrecision} at $bpm")
        }
        for (bpm in listOf(90f, 120f, 150f)) {
            val (e, a) = run(SyntheticTracks.Spec(bpm = bpm, key = MusicalKey(5, Mode.MINOR), beatsPerBar = 3))
            println("3/4 @ $bpm: $e")
            assertEquals(3, a.beatsPerBar, "3/4 at $bpm")
            assertTrue(e.bpmErrPct < 0.3)
            assertTrue(e.downbeatRecall >= 0.9 && e.downbeatPrecision >= 0.9, "3/4 downbeats rec=${e.downbeatRecall} prec=${e.downbeatPrecision}")
        }
    }

    @Test
    fun phrasesAreOnDownbeatsAndEveryFourBars() {
        val (e, a) = run(SyntheticTracks.Spec(bpm = 124f, key = majorC))
        val downTimes = e.truth.downbeatBeatIndices.map { e.truth.beatTimesMs[it] }
        assertTrue(a.phraseStartsMs.isNotEmpty())
        // every phrase start sits on a true downbeat, spaced by whole 4-bar groups
        for (p in a.phraseStartsMs) assertTrue(downTimes.any { abs(it - p) <= 60 }, "phrase $p not on a downbeat")
        val bar = 60000.0 / 124 * 4
        for ((x, y) in a.phraseStartsMs.zipWithNext()) assertEquals(4.0, (y - x) / bar, 0.08)
        // aligned to the arrangement: the 8-bar section starts (bar 0, 8, 24, ...) are phrase starts
        for (sec in e.truth.sections.drop(1)) assertTrue(a.phraseStartsMs.any { abs(it - sec.range.startMs) <= 80 }, "section start ${sec.range.startMs} is not a phrase start")
    }

    @Test
    fun allTwentyFourKeys() {
        var wrong = ArrayList<String>()
        for (mode in Mode.values()) for (pc in 0 until 12) {
            val k = MusicalKey(pc, mode)
            val (e, a) = run(SyntheticTracks.Spec(bpm = 124f, key = k))
            if (!e.keyCorrect) wrong += "$k -> ${a.key?.value}"
            else assertTrue(a.key!!.confidence >= Trust.KEY, "confidence ${a.key!!.confidence} for correct $k")
        }
        assertTrue(wrong.isEmpty(), "wrong keys: $wrong")
    }

    @Test
    fun sectionsFollowTheArrangement() {
        var hit = 0.0; var n = 0
        for (bpm in tempos) {
            val (e, a) = run(SyntheticTracks.Spec(bpm = bpm, key = majorC))
            println("sections @ $bpm: hit=${e.sectionBoundaryHit} n=${a.sections?.value?.size} conf=${a.sections?.confidence}")
            hit += e.sectionBoundaryHit; n++
            assertTrue(e.sectionBoundaryHit >= 0.75, "section boundary hit ${e.sectionBoundaryHit} at $bpm")
            val secs = a.sections!!.value
            // contiguous, ordered, covering the music
            assertTrue(secs.zipWithNext().all { (x, y) -> x.range.endMs == y.range.startMs })
            assertTrue(secs.all { it.energy in 0f..1f })
        }
        assertTrue(hit / n >= 0.8, "mean boundary hit ${hit / n}")
    }

    @Test
    fun sectionLabelsAreSensible() {
        val (_, a) = run(SyntheticTracks.Spec(bpm = 128f, key = majorC))
        val secs = a.sections!!.value
        println("labels: " + secs.joinToString { "${it.kind}@${it.range.startMs / 1000}s e=${"%.2f".format(it.energy)}" })
        assertEquals(SectionKind.INTRO, secs.first().kind)
        assertEquals(SectionKind.OUTRO, secs.last().kind)
        assertTrue(secs.any { it.kind == SectionKind.DROP })
        assertTrue(secs.any { it.kind == SectionKind.BREAKDOWN })
        // the drop follows the breakdown
        val bi = secs.indexOfFirst { it.kind == SectionKind.BREAKDOWN }
        assertEquals(SectionKind.DROP, secs[bi + 1].kind)
    }

    @Test
    fun otherSampleRatesGiveTheSameAnswer() {
        for (sr in listOf(8000, 16000, 44100, 48000)) {
            val (e, a) = run(SyntheticTracks.Spec(bpm = 128f, key = MusicalKey(7, Mode.MINOR), sampleRate = sr))
            println("sr=$sr: $e")
            assertTrue(e.bpmErrPct < 0.3, "bpm at $sr Hz -> ${a.bpm?.value}")
            assertTrue(e.beatMedianErr < 15 && e.beatWithin40 > 0.95, "beats at $sr Hz")
            assertTrue(e.downbeatRecall >= 0.9)
            assertEquals(a.key!!.value, MusicalKey(7, Mode.MINOR), "key at $sr Hz")
            assertTrue(abs(a.durationMs - e.truth.durationMs) <= 5)
        }
    }

    @Test
    fun clickTrackIsExact() {
        val r = SyntheticTracks.clickTrack(120f, 32, firstBeatMs = 333, sampleRate = 44100)
        val a = analyzer.analyze("click", r.audio)
        assertEquals(120f, a.bpm!!.value, 0.3f)
        val est = a.beatTimesMs!!.value
        val errs = r.truth.beatTimesMs.map { t -> est.minOf { abs(it - t) } }.sorted()
        println("click: median ${errs[errs.size / 2]} ms max ${errs.last()} ms")
        assertTrue(errs[errs.size / 2] <= 6, "click median error ${errs[errs.size / 2]} ms")
        assertTrue(errs.count { it <= 15 } >= errs.size * 0.95)
    }

    // ---- honesty and robustness

    private fun noise(seconds: Int, sr: Int = 22050, seed: Int = 1): PcmAudio {
        val rnd = Random(seed)
        return PcmAudio(FloatArray(seconds * sr) { (rnd.nextFloat() * 2 - 1) * 0.5f }, sr)
    }

    @Test
    fun whiteNoiseIsNotTrusted() {
        for (seed in 1..3) {
            val a = analyzer.analyze("noise", noise(60, seed = seed))
            println("noise $seed: bpm=${a.bpm} beats=${a.beatTimesMs?.confidence} down=${a.downbeatBeatIndices?.confidence} key=${a.key} sections=${a.sections?.confidence}")
            (a.bpm?.confidence ?: 0f).let { assertTrue(it < 0.25f, "noise bpm confidence $it") }
            (a.beatTimesMs?.confidence ?: 0f).let { assertTrue(it < 0.25f, "noise beats confidence $it") }
            (a.downbeatBeatIndices?.confidence ?: 0f).let { assertTrue(it < Trust.DOWNBEATS, "noise downbeat confidence $it") }
            (a.key?.confidence ?: 0f).let { assertTrue(it < 0.3f, "noise key confidence $it") }
            (a.sections?.confidence ?: 0f).let { assertTrue(it < Trust.SECTIONS, "noise sections confidence $it") }
        }
    }

    @Test
    fun steadyToneHasNoRhythm() {
        val sr = 22050
        val a = analyzer.analyze("tone", PcmAudio(FloatArray(sr * 40) { (0.4 * sin(2 * PI * 440.0 * it / sr)).toFloat() }, sr))
        assertTrue((a.bpm?.confidence ?: 0f) < 0.25f, "tone bpm ${a.bpm}")
        // a pure A is certainly A-ish material but must not claim a confident key from one pitch class... it may, but never rhythm
        assertTrue((a.beatTimesMs?.confidence ?: 0f) < 0.25f)
    }

    @Test
    fun silenceGivesNulls() {
        val a = analyzer.analyze("silence", PcmAudio(FloatArray(22050 * 30), 22050))
        assertNull(a.bpm); assertNull(a.beatTimesMs); assertNull(a.downbeatBeatIndices); assertNull(a.key)
        assertNull(a.sections); assertNull(a.vocals)
        assertTrue(a.energy.all { it == 0f })
        assertEquals(30_000L, a.durationMs)
        assertTrue(a.loudnessDb <= -100f)
        // digital silence at another rate, and an empty buffer
        assertNull(analyzer.analyze("s2", PcmAudio(FloatArray(48000 * 10), 48000)).bpm)
        assertNull(analyzer.analyze("empty", PcmAudio(FloatArray(0), 44100)).bpm)
    }

    @Test
    fun veryShortInputsDoNotCrash() {
        for (len in listOf(1, 10, 300, 1024, 4000, 22050, 22050 * 2, 22050 * 4, 22050 * 5)) {
            val r = SyntheticTracks.render(SyntheticTracks.Spec(bpm = 128f, key = majorC))
            val a = analyzer.analyze("short-$len", PcmAudio(r.audio.samples.copyOf(len), r.audio.sampleRate))
            assertEquals(len * 1000L / 22050, a.durationMs)
            assertTrue(a.energy.all { it in 0f..1f })
        }
        // 4 s of real material: whatever is reported must be self-consistent and not over-confident
        val r = SyntheticTracks.render(SyntheticTracks.Spec(bpm = 128f, key = majorC))
        val a = analyzer.analyze("4s", PcmAudio(r.audio.samples.copyOf(22050 * 4), 22050))
        a.bpm?.let { assertTrue(abs(it.value - 128f) < 4f || it.confidence < 0.6f, "4 s clip bpm ${it}") }
    }

    @Test
    fun dcOffsetAndDamagedSamplesAreHandled() {
        val r = SyntheticTracks.render(SyntheticTracks.Spec(bpm = 128f, key = MusicalKey(2, Mode.MAJOR)))
        val dc = PcmAudio(FloatArray(r.audio.samples.size) { (r.audio.samples[it] * 0.5f + 0.4f) }, r.audio.sampleRate)
        val a = analyzer.analyze("dc", dc)
        val e = Eval(a, r)
        assertTrue(e.bpmErrPct < 0.3, "DC offset bpm ${a.bpm}")
        assertTrue(e.beatWithin40 > 0.95 && e.keyCorrect)
        // NaN / Inf / absurd values in the stream
        val bad = r.audio.samples.copyOf()
        for (i in 1000 until bad.size step 5000) { bad[i] = Float.NaN; bad[i + 1] = Float.POSITIVE_INFINITY; bad[i + 2] = 1e9f }
        val b = analyzer.analyze("bad", PcmAudio(bad, r.audio.sampleRate))
        assertNotNull(b.bpm)
        assertTrue(Eval(b, r).bpmErrPct < 0.5)
    }

    @Test
    fun outOfPhaseStereoMixedToMonoIsSilentNotGarbage() {
        val r = SyntheticTracks.render(SyntheticTracks.Spec(bpm = 128f, key = majorC))
        val l = r.audio.samples
        val mixed = FloatArray(l.size) { (l[it] + (-l[it])) / 2f } // fully out-of-phase channels summed to mono
        val a = analyzer.analyze("oop", PcmAudio(mixed, r.audio.sampleRate))
        assertNull(a.bpm); assertNull(a.key)
    }

    @Test
    fun quietMusicStillAnalysed() {
        val r = SyntheticTracks.render(SyntheticTracks.Spec(bpm = 128f, key = MusicalKey(9, Mode.MINOR)))
        val quiet = PcmAudio(FloatArray(r.audio.samples.size) { r.audio.samples[it] * 0.003f }, r.audio.sampleRate) // -50 dB
        val a = analyzer.analyze("quiet", quiet)
        val e = Eval(a, r)
        assertTrue(e.bpmErrPct < 0.3 && e.beatWithin40 > 0.95 && e.keyCorrect, "quiet: $e")
        assertTrue(a.loudnessDb < -50f)
    }

    // ---- performance

    @Test
    fun fourMinuteTrackIsFast() {
        val spec = SyntheticTracks.Spec(
            bpm = 128f, key = MusicalKey(4, Mode.MINOR), sampleRate = 44100,
            sections = listOf(
                SectionSpec(SectionKind.INTRO, 8), SectionSpec(SectionKind.BODY, 24), SectionSpec(SectionKind.BREAKDOWN, 16),
                SectionSpec(SectionKind.DROP, 32), SectionSpec(SectionKind.BODY, 16), SectionSpec(SectionKind.BREAKDOWN, 8),
                SectionSpec(SectionKind.DROP, 16), SectionSpec(SectionKind.OUTRO, 8),
            ),
        )
        val r = SyntheticTracks.render(spec)
        analyzer.analyze("warmup", PcmAudio(r.audio.samples.copyOf(44100 * 30), 44100))
        var best = Long.MAX_VALUE
        var detail = ""
        repeat(3) {
            val (a, t) = analyzer.analyzeTimed("perf", r.audio)
            val total = t.stages.values.sum()
            if (total < best) { best = total; detail = t.stages.entries.joinToString { "${it.key}=${it.value}ms" } }
            assertNotNull(a.bpm)
        }
        println("4-minute (${r.audio.durationMs / 1000} s @ 44.1 kHz) analysis: $best ms  [$detail]")
        assertTrue(best < 6000, "analysis took $best ms")
        val e = Eval(analyzer.analyze("perf", r.audio), r)
        println("4-minute accuracy: $e")
        assertTrue(e.bpmErrPct < 0.3 && e.beatWithin40 > 0.95 && e.keyCorrect && e.downbeatRecall >= 0.9)
    }
}
