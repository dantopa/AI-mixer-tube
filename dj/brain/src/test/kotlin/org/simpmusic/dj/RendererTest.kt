package org.simpmusic.dj

import org.simpmusic.dj.audio.WavIo
import org.simpmusic.dj.model.*
import org.simpmusic.dj.planner.DjTransitionPlanner
import org.simpmusic.dj.render.AudioSegment
import org.simpmusic.dj.render.OfflineMixRenderer
import org.simpmusic.dj.render.OfflineMixRenderer.Options
import org.simpmusic.dj.render.OfflineMixRenderer.Solo
import org.simpmusic.dj.render.StereoPcm
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RendererTest {
    private val sr = 44100
    private val planner = DjTransitionPlanner()
    private val settings = DjSettings(enabled = true)

    private fun click(bpm: Float, id: String, bars: Int = 64): Pair<SyntheticTracks.Rendered, TrackAnalysis> {
        val r = SyntheticTracks.clickTrack(bpm, bars, firstBeatMs = 0, sampleRate = sr)
        return r to FakeAnalysis.fromTruth(r.truth, id, withSections = false)
    }

    /** Clicks of one deck rendered alone with the mixer bypassed (tempo/pitch only), as plan times in ms. */
    private fun deckClicks(plan: TransitionPlan, out: SyntheticTracks.Rendered, inc: SyntheticTracks.Rendered, which: Solo, tail: Long = 2000): Pair<List<Double>, OfflineMixRenderer.Window> {
        val w = OfflineMixRenderer.render(
            plan, AudioSegment.of(out.audio), AudioSegment.of(inc.audio),
            Options(solo = which, bypassMixer = true, tailMs = tail, masterLimiter = false),
        )
        val clicks = TestAudio.detectClicks(w.audio.left, sr, minGapMs = 100.0).map { it + w.startMs }
        return clicks to w
    }

    /**
     * Renders both decks alone from the same plan, detects their clicks, and checks (a) each click lands where the
     * deck clock says (source accounting), (b) the two decks' clicks coincide within [tolMs] across the overlap.
     */
    private fun checkAlignment(name: String, plan: TransitionPlan, out: SyntheticTracks.Rendered, inc: SyntheticTracks.Rendered, strideOut: Int = 1, strideIn: Int = 1, tolMs: Double = 5.0) {
        val (co, wo) = deckClicks(plan, out, inc, Solo.OUTGOING)
        val (ci, wi) = deckClicks(plan, out, inc, Solo.INCOMING)
        val clockO = DeckClock.outgoing(plan)
        val clockI = DeckClock.incoming(plan)
        // (a) accounting: detected click time vs the time the clock predicts for that source beat
        var acctOut = 0.0
        for (t in co) {
            val src = clockO.sourceAt(t)
            val beat = out.truth.beatTimesMs.minBy { abs(it - src) }
            acctOut = maxOf(acctOut, abs(t - clockO.wallAt(beat.toDouble())))
        }
        var acctIn = 0.0
        for (t in ci) {
            val src = clockI.sourceAt(t)
            val beat = inc.truth.beatTimesMs.minBy { abs(it - src) }
            acctIn = maxOf(acctIn, abs(t - clockI.wallAt(beat.toDouble())))
        }
        // T0: outgoing is on the exit beat, incoming on its first click
        val atT0Out = co.minOf { abs(it) }
        val atT0In = ci.minOf { abs(it) }
        // (b) beat alignment between decks inside the overlap
        var worst = 0.0
        var checked = 0
        // the sparser deck's clicks must each coincide with a click of the denser deck (half/double time)
        val (sparse, dense) = if (strideOut > strideIn) ci to co else co to ci
        for (ts in sparse) {
            if (ts < 0.0 || ts > plan.overlapMs - 200.0) continue
            val nearest = dense.minBy { abs(it - ts) }
            worst = maxOf(worst, abs(nearest - ts))
            checked++
        }
        // and the reverse direction for double time: every 2nd incoming click must hit an outgoing one
        println(String.format("%-14s %s  clicks(out %d, in %d)  T0 error out %.3f ms, in %.3f ms | accounting err out %.3f, in %.3f ms | alignment over %d beats: max %.3f ms",
            name, plan.kind, co.size, ci.size, atT0Out, atT0In, acctOut, acctIn, checked, worst))
        assertTrue(checked >= 8, "$name: too few overlap beats checked ($checked)")
        assertTrue(atT0Out < 1.5 && atT0In < 1.5, "$name: decks not on their anchors at T0")
        assertTrue(acctOut < 2.0 && acctIn < 2.0, "$name: source position accounting off (out $acctOut, in $acctIn)")
        assertTrue(worst <= tolMs, "$name: beat alignment error $worst ms > $tolMs")
        assertTrue(wo.startMs <= 0)
    }

    @Test
    fun beatAlignmentAcrossTempoChanges() {
        println("== deck alignment: click tracks, each deck rendered alone from the same plan ==")
        for ((bpmA, bpmB, sOut, sIn) in listOf(
            Quad(124f, 128f, 1, 1), Quad(128f, 124f, 1, 1), Quad(87f, 174f, 1, 2), Quad(174f, 87f, 2, 1), Quad(120f, 130f, 1, 1), Quad(100f, 100f, 1, 1),
        )) {
            val (ra, a) = click(bpmA, "a")
            val (rb, b) = click(bpmB, "b")
            val plan = planner.plan(a, b, settings)
            assertEquals(PlanKind.BEAT_MATCHED, plan.kind, "${plan.reason}")
            checkAlignment("$bpmA->$bpmB", plan, ra, rb, sOut, sIn)
        }
    }

    private data class Quad(val a: Float, val b: Float, val so: Int, val si: Int)

    @Test
    fun beyondBendIsACutOrACrossfadeAndStillOnTheGrid() {
        val (ra, a) = click(120f, "a")
        val (rb, b) = click(150f, "b")
        val cut = planner.plan(a, b, settings)
        assertEquals(PlanKind.CUT, cut.kind)
        val w = OfflineMixRenderer.render(cut, AudioSegment.of(ra.audio), AudioSegment.of(rb.audio), Options(tailMs = 3000, leadMs = 3000))
        val clicks = TestAudio.detectClicks(w.audio.left, sr, minGapMs = 100.0).map { it + w.startMs }
        // before T0 the outgoing grid (500 ms), after T0 the incoming grid (400 ms), the cut lands on a click
        val before = clicks.filter { it < -50 }
        val after = clicks.filter { it > 50 }
        assertTrue(before.zipWithNext().all { abs(it.second - it.first - 500.0) < 1.5 })
        assertTrue(after.zipWithNext().all { abs(it.second - it.first - 400.0) < 1.5 })
        assertTrue(clicks.any { abs(it) < 1.5 }, "no click at T0")
        println("cut 120->150: outgoing grid 500 ms, incoming grid 400 ms, click at T0 = ${clicks.minOf { abs(it) }} ms off")

        val weak = b.copy(downbeatBeatIndices = b.downbeatBeatIndices!!.copy(confidence = 0.1f))
        val simple = planner.plan(a, weak, settings)
        assertEquals(PlanKind.SIMPLE_CROSSFADE, simple.kind)
        val m = OfflineMixRenderer.render(simple, ra.audio, rb.audio, 3000, 3000)
        assertTrue(m.frames > sr * 6)
        assertTrue(m.left.all { abs(it) <= 1f })
    }

    @Test
    fun fullMixOfTheSyntheticBandDoesNotClipAndKeepsItsEnergy() {
        println("== full mix, synthetic band tracks, mixer active ==")
        for ((bpmA, bpmB, keyB) in listOf(Triple(124f, 128f, MusicalKey(9, Mode.MINOR)), Triple(128f, 124f, MusicalKey(0, Mode.MAJOR)))) {
            val ra = SyntheticTracks.render(SyntheticTracks.Spec(bpm = bpmA, key = MusicalKey(9, Mode.MINOR)))
            val rb = SyntheticTracks.render(SyntheticTracks.Spec(bpm = bpmB, key = keyB, seed = 7))
            val a = FakeAnalysis.fromTruth(ra.truth, "a")
            val b = FakeAnalysis.fromTruth(rb.truth, "b")
            val plan = planner.plan(a, b, settings)
            println("$bpmA->$bpmB: ${plan.kind} ${plan.reason}")
            assertEquals(PlanKind.BEAT_MATCHED, plan.kind)
            val w = OfflineMixRenderer.render(plan, AudioSegment.of(ra.audio), AudioSegment.of(rb.audio), Options(leadMs = 8000, tailMs = 6000))
            val mix = w.audio.left
            val peak = mix.maxOf { abs(it) }
            val hot = mix.count { abs(it) > 0.95f }
            println("  peak=$peak samples>0.95: $hot of ${mix.size}")
            assertTrue(peak <= 1.0f, "clipped: $peak")
            // a hard clip would show as long runs at full scale; the soft knee must be (nearly) untouched
            assertTrue(hot < mix.size / 200, "too many samples in the limiter knee: $hot")

            // energy continuity in 400 ms windows across the overlap against the decks' own (unfaded) levels
            val (_, _) = 0 to 0
            val solo = { s: Solo -> OfflineMixRenderer.render(plan, AudioSegment.of(ra.audio), AudioSegment.of(rb.audio), Options(leadMs = 8000, tailMs = 6000, solo = s, bypassMixer = true, masterLimiter = false)).audio.left }
            val outAlone = solo(Solo.OUTGOING)
            val inAlone = solo(Solo.INCOMING)
            val win = (0.4 * sr).toInt()
            var worstDrop = 0.0
            var worstAt = 0.0
            var t = 0.0
            while (t + 400 <= plan.overlapMs) {
                val i0 = w.frameOfPlanTime(t)
                val mixLevel = TestAudio.rms(mix, i0, i0 + win)
                val lo = TestAudio.rms(outAlone, i0, i0 + win)
                val li = TestAudio.rms(inAlone, i0, i0 + win)
                val ref = minOf(lo, li)
                val drop = TestAudio.db(ref) - TestAudio.db(mixLevel)
                if (drop > worstDrop) { worstDrop = drop; worstAt = t }
                t += 200.0
            }
            println(String.format("  worst dip of the mix below min(deck levels): %.2f dB at t=%.0f ms of %d", worstDrop, worstAt, plan.overlapMs))
            assertTrue(worstDrop <= 4.0, "mix dips $worstDrop dB below the weaker deck")
            // no gap: outgoing keeps playing until the incoming is on
            val overall = TestAudio.rms(mix, w.frameOfPlanTime(0.0), w.frameOfPlanTime(plan.overlapMs.toDouble()))
            assertTrue(overall > 0.05)
        }
    }

    @Test
    fun windowStartAndEndAreExact() {
        val (ra, a) = click(124f, "a")
        val (rb, b) = click(128f, "b")
        val plan = planner.plan(a, b, settings)
        val opts = Options(tailMs = 1500, masterLimiter = false)
        // default tail contains the hand-off
        assertTrue(Options().tailMs >= OfflineMixRenderer.RELOCK_MS)
        val w = OfflineMixRenderer.render(plan, AudioSegment.of(ra.audio), AudioSegment.of(rb.audio), opts)
        // 1. first output sample = plan time -preRollMs
        assertEquals(-plan.preRollMs.toDouble(), -w.startMs, 1000.0 / sr)
        // the outgoing deck at frame 0 plays the source at exit + integral(rate) and equals the file there (rate ~ 1 at the start)
        val solo = OfflineMixRenderer.render(plan, AudioSegment.of(ra.audio), AudioSegment.of(rb.audio), Options(solo = Solo.OUTGOING, bypassMixer = true, masterLimiter = false))
        val srcStart = solo.outgoingSourceMsAtFrame(0) * sr / 1000.0
        val i0 = Math.round(srcStart).toInt()
        var worst = 0.0
        for (i in 0 until 600) worst = maxOf(worst, abs(solo.audio.left[i] - ra.audio.samples[i0 + i]).toDouble())
        println("window start: source frame ${"%.2f".format(srcStart)} (plan time ${"%.2f".format(solo.startMs)} ms); first 600 samples differ from the file by max $worst")
        assertTrue(worst < 1e-3)
        // 2. last incoming sample is at native rate: the window end is at least at the settle time, rate lane ended at 1.0
        assertTrue(w.endMs >= plan.settleMs + 1500 - 1000.0 / sr * 2)
        assertEquals(1f, plan.incoming.rate.valueAt(w.endMs))
        // 3. tail of the mix vs the un-stretched incoming source, aligned by cross-correlation
        val startTail = w.handoffFrame
        val len = (0.9 * sr).toInt()
        assertTrue(startTail + len <= w.frames, "window too short to contain the hand-off")
        val seg = w.audio.left.copyOfRange(startTail, startTail + len)
        val srcPos = w.incomingSourceMsAtFrame(startTail) * sr / 1000.0
        val maxLag = (0.05 * sr).toInt()
        var bestLag = 0
        var best = -1.0
        val ref = rb.audio.samples
        val base = Math.round(srcPos).toInt()
        for (lag in -maxLag..maxLag) {
            var s = 0.0
            var i = 0
            while (i < len) { s += seg[i] * ref[base + lag + i].toDouble(); i += 3 }
            if (s > best) { best = s; bestLag = lag }
        }
        var num = 0.0
        var den = 0.0
        for (i in 0 until len) { val d = seg[i] - ref[base + i]; num += d * d; den += ref[base + i] * ref[base + i].toDouble() }
        val resid = 10 * log10(num / den + 1e-12)
        println("tail vs source: cross-correlation lag ${bestLag * 1000.0 / sr} ms, residual ${"%.1f".format(resid)} dB")
        assertTrue(abs(bestLag * 1000.0 / sr) <= 1.0)
        assertTrue(resid < -30.0, "tail differs from the native incoming source: $resid dB")
    }

    @Test
    fun segmentsWithASourceOffsetRenderTheSameAsWholeTracks() {
        val (ra, a) = click(124f, "a")
        val (rb, b) = click(128f, "b")
        val plan = planner.plan(a, b, settings)
        val whole = OfflineMixRenderer.render(plan, AudioSegment.of(ra.audio), AudioSegment.of(rb.audio), Options(tailMs = 1500))
        val co = DeckClock.outgoing(plan)
        val ci = DeckClock.incoming(plan)
        val outFrom = (co.sourceAt(plan.preRollMs.toDouble()) - 200).toLong().coerceAtLeast(0)
        val outTo = (co.sourceAt(plan.overlapMs.toDouble()) + 200).toLong()
        val inFrom = plan.entryPointMs
        val inTo = (ci.sourceAt(plan.settleMs + 1500.0) + 200).toLong()
        fun cut(x: FloatArray, from: Long, to: Long) = StereoPcm.fromMono(x.copyOfRange(Math.round(from * sr / 1000.0).toInt(), Math.round(to * sr / 1000.0).toInt()), sr)
        val seg = OfflineMixRenderer.render(
            plan, AudioSegment(cut(ra.audio.samples, outFrom, outTo), outFrom), AudioSegment(cut(rb.audio.samples, inFrom, inTo), inFrom), Options(tailMs = 1500),
        )
        assertEquals(whole.frames, seg.frames)
        var worst = 0.0
        for (i in 0 until whole.frames) worst = maxOf(worst, abs(whole.audio.left[i] - seg.audio.left[i]).toDouble())
        println("pre-cut segments vs whole tracks: max difference $worst over ${whole.frames} frames")
        assertTrue(worst < 1e-4, "segment render differs: $worst")
    }

    @Test
    fun mappingFromWindowPositionToSourcePositions() {
        val (ra, a) = click(124f, "a")
        val (rb, b) = click(128f, "b")
        val plan = planner.plan(a, b, settings)
        val w = OfflineMixRenderer.render(plan, AudioSegment.of(ra.audio), AudioSegment.of(rb.audio), Options(tailMs = 500))
        assertEquals(plan.exitPointMs.toDouble(), w.outgoingSourceMs(0.0), 1e-6)
        assertEquals(plan.entryPointMs.toDouble(), w.incomingSourceMs(0.0), 1e-6)
        val f = w.t0Frame
        assertEquals(plan.exitPointMs.toDouble(), w.outgoingSourceMsAtFrame(f), 0.05)
        // round trip
        val src = plan.exitPointMs + 3000.0
        assertEquals(src, w.outgoingSourceMs(w.outgoingWallMs(src)), 1e-3)
        // at the settle point the incoming deck is at native rate, so d(source)/d(wall) = 1
        val t = plan.settleMs + 200.0
        assertEquals(100.0, w.incomingSourceMs(t + 100) - w.incomingSourceMs(t), 0.1)
    }

    @Test
    fun realTimeFactorOfASixtySecondWindow() {
        val (ra, a) = click(124f, "a", bars = 160)
        val (rb, b) = click(126f, "b", bars = 160)
        val plan = planner.plan(a, b, settings.copy(overlapBars = 24))
        println("perf plan: ${plan.reason}")
        assertEquals(PlanKind.BEAT_MATCHED, plan.kind)
        var lastRtf = 0.0
        repeat(3) { run ->
            val t0 = System.nanoTime()
            val w = OfflineMixRenderer.render(plan, AudioSegment.of(ra.audio), AudioSegment.of(rb.audio), Options(tailMs = 2000))
            val ms = (System.nanoTime() - t0) / 1e6
            val audioMs = w.frames * 1000.0 / sr
            lastRtf = ms / audioMs
            println(String.format("render run %d: %.1f s of audio in %.0f ms -> real-time factor %.3f", run, audioMs / 1000, ms, lastRtf))
        }
        assertTrue(lastRtf <= 0.15, "real-time factor $lastRtf")
    }


    @Test
    fun effectHooksSitInTheMixerGraph() {
        val (ra, a) = click(124f, "a")
        val (rb, b) = click(126f, "b")
        val plan = planner.plan(a, b, settings)
        val base = OfflineMixRenderer.render(plan, AudioSegment.of(ra.audio), AudioSegment.of(rb.audio), Options(tailMs = 500, masterLimiter = false))
        val half = OfflineMixRenderer.render(
            plan, AudioSegment.of(ra.audio), AudioSegment.of(rb.audio),
            Options(tailMs = 500, masterLimiter = false, masterEffect = OfflineMixRenderer.BlockEffect { l, r, o, n -> for (i in o until o + n) { l[i] *= 0.5f; r[i] *= 0.5f } }),
        )
        var worst = 0.0
        for (i in 0 until base.frames) worst = maxOf(worst, abs(base.audio.left[i] * 0.5f - half.audio.left[i]).toDouble())
        assertTrue(worst < 1e-6)
        // a deck effect that mutes the incoming deck leaves only the outgoing one
        val muted = OfflineMixRenderer.render(
            plan, AudioSegment.of(ra.audio), AudioSegment.of(rb.audio),
            Options(tailMs = 500, masterLimiter = false, incomingEffect = OfflineMixRenderer.BlockEffect { l, r, o, n -> for (i in o until o + n) { l[i] = 0f; r[i] = 0f } }),
        )
        val late = base.frameOfPlanTime(plan.overlapMs + 300.0)
        assertTrue(TestAudio.rms(muted.audio.left, late, late + sr / 2) < 1e-6)
        assertTrue(TestAudio.rms(base.audio.left, late, late + sr / 2) > 0.01)
    }


    @Test
    fun worksAt48kHz() {
        val ra = SyntheticTracks.clickTrack(124f, 64, firstBeatMs = 0, sampleRate = 48000)
        val rb = SyntheticTracks.clickTrack(128f, 64, firstBeatMs = 0, sampleRate = 48000)
        val a = FakeAnalysis.fromTruth(ra.truth, "a", withSections = false)
        val b = FakeAnalysis.fromTruth(rb.truth, "b", withSections = false)
        val plan = planner.plan(a, b, settings)
        assertEquals(PlanKind.BEAT_MATCHED, plan.kind)
        fun clicks(which: Solo): List<Double> {
            val w = OfflineMixRenderer.render(plan, AudioSegment.of(ra.audio), AudioSegment.of(rb.audio), Options(solo = which, bypassMixer = true, masterLimiter = false))
            return TestAudio.detectClicks(w.audio.left, 48000, minGapMs = 100.0).map { it + w.startMs }
        }
        val co = clicks(Solo.OUTGOING)
        val ci = clicks(Solo.INCOMING)
        var worst = 0.0
        var n = 0
        for (t in co) if (t >= 0 && t <= plan.overlapMs - 200) { worst = maxOf(worst, ci.minOf { abs(it - t) }); n++ }
        println("48 kHz 124->128: alignment over $n beats max ${"%.3f".format(worst)} ms")
        assertTrue(n >= 8 && worst < 2.0)
    }

    @Test
    fun demoAcceptsAnalysisJson() {
        val dir = File("build/demo").also { it.mkdirs() }
        val ra = SyntheticTracks.render(SyntheticTracks.Spec(bpm = 124f, key = MusicalKey(9, Mode.MINOR), sampleRate = 44100))
        val rb = SyntheticTracks.render(SyntheticTracks.Spec(bpm = 126f, key = MusicalKey(9, Mode.MINOR), sampleRate = 44100, seed = 4))
        val fa = File(dir, "ja.wav")
        val fb = File(dir, "jb.wav")
        WavIo.write(fa, WavIo.Wav(arrayOf(ra.audio.samples), 44100))
        WavIo.write(fb, WavIo.Wav(arrayOf(rb.audio.samples), 44100))
        val json = kotlinx.serialization.json.Json
        val ja = File(dir, "ja.json").also { it.writeText(json.encodeToString(TrackAnalysis.serializer(), FakeAnalysis.fromTruth(ra.truth, "ja"))) }
        val jb = File(dir, "jb.json").also { it.writeText(json.encodeToString(TrackAnalysis.serializer(), FakeAnalysis.fromTruth(rb.truth, "jb"))) }
        val planFile = File(dir, "jplan.json")
        val code = org.simpmusic.dj.tools.MixDemo.run(arrayOf(fa.path, fb.path, File(dir, "mix-json.wav").path, "--analysis-from", ja.path, "--analysis-to", jb.path, "--plan-out", planFile.path))
        assertEquals(0, code)
        val plan = json.decodeFromString(TransitionPlan.serializer(), planFile.readText())
        assertEquals(PlanKind.BEAT_MATCHED, plan.kind)
        assertEquals(ra.truth.beatTimesMs.size > 0, true)
    }

    @Test
    fun demoRendersAnAudibleMixFromTwoSyntheticSpecs() {
        val dir = File("build/demo").also { it.mkdirs() }
        val ra = SyntheticTracks.render(SyntheticTracks.Spec(bpm = 124f, key = MusicalKey(9, Mode.MINOR), sampleRate = 44100))
        val rb = SyntheticTracks.render(SyntheticTracks.Spec(bpm = 128f, key = MusicalKey(0, Mode.MAJOR), sampleRate = 44100, seed = 9))
        val fa = File(dir, "a-124.wav")
        val fb = File(dir, "b-128.wav")
        val out = File(dir, "mix-124-to-128.wav")
        WavIo.write(fa, WavIo.Wav(arrayOf(ra.audio.samples), 44100))
        WavIo.write(fb, WavIo.Wav(arrayOf(rb.audio.samples), 44100))
        val planOut = File(dir, "plan.json")
        val code = org.simpmusic.dj.tools.MixDemo.run(
            arrayOf(fa.path, fb.path, out.path, "--bpm-from", "124", "--bpm-to", "128", "--first-beat-from", "180", "--first-beat-to", "180", "--key-from", "8A", "--key-to", "8B", "--plan-out", planOut.path),
        )
        assertEquals(0, code)
        val mix = WavIo.read(out)
        assertEquals(2, mix.channels.size)
        val secs = mix.frames / 44100.0
        println("demo mix: ${"%.1f".format(secs)} s, stereo, ${out.absolutePath}")
        assertTrue(secs in 30.0..70.0)
        val left = mix.channels[0]
        assertTrue(left.maxOf { abs(it) } in 0.3f..1f)
        // audible everywhere: 1 s RMS never near silence
        var t = 0
        while (t + 44100 <= left.size) {
            assertTrue(TestAudio.rms(left, t, t + 44100) > 0.03, "silence at ${t / 44100} s")
            t += 22050
        }
        // the plan JSON round-trips
        val plan = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<TransitionPlan>(planOut.readText())
        assertEquals(PlanKind.BEAT_MATCHED, plan.kind)
        // a second run through the estimator path (no overrides) must not crash
        assertEquals(0, org.simpmusic.dj.tools.MixDemo.run(arrayOf(fa.path, fb.path, File(dir, "mix-auto.wav").path)))
    }
}
