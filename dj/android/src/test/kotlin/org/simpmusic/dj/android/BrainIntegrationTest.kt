package org.simpmusic.dj.android

import org.simpmusic.dj.android.render.BrainWindowRenderer
import org.simpmusic.dj.android.render.RenderRequest
import org.simpmusic.dj.android.render.StereoPcm
import org.simpmusic.dj.android.window.Eligibility
import org.simpmusic.dj.android.window.WindowTimeline
import org.simpmusic.dj.audio.WavIo
import org.simpmusic.dj.SyntheticTracks
import org.simpmusic.dj.analysis.DspTrackAnalyzer
import org.simpmusic.dj.model.DjSettings
import org.simpmusic.dj.model.MusicalKey
import org.simpmusic.dj.model.Mode
import org.simpmusic.dj.model.PlanKind
import org.simpmusic.dj.planner.DjTransitionPlanner
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import org.junit.Assert
import org.junit.Test

private fun assertTrue(condition: Boolean, message: String = "") = Assert.assertTrue(message, condition)

private fun assertEquals(expected: Any?, actual: Any?) = Assert.assertEquals(expected, actual)

/** The real planner and the real renderer against the window design's contracts (no fakes). */
class BrainIntegrationTest {
    private val planner = DjTransitionPlanner()
    private val settings = DjSettings(enabled = true)

    private fun band(bpm: Float, key: MusicalKey = MusicalKey(9, Mode.MINOR), id: String = "s$bpm") =
        DspTrackAnalyzer().analyze(id, SyntheticTracks.render(SyntheticTracks.Spec(bpm = bpm, key = key, videoId = id)).audio)

    @Test
    fun realPlansAreEligibleWindows() {
        val bpms = listOf(87f, 100f, 120f, 124f, 128f, 140f, 174f)
        val tracks = bpms.associateWith { band(it) }
        var checked = 0
        val bad = ArrayList<String>()
        val kinds = HashMap<PlanKind, Int>()
        for (a in bpms) for (b in bpms) {
            if (a == b) continue
            val plan = planner.plan(tracks.getValue(a), tracks.getValue(b), settings)
            kinds.merge(plan.kind, 1, Int::plus)
            if (plan.kind == PlanKind.SIMPLE_CROSSFADE) continue
            checked++
            val e = WindowTimeline.check(plan)
            if (e is Eligibility.Rejected) bad += "$a->$b ${plan.kind}: ${e.reason}"
        }
        println("plan kinds: $kinds; real plans checked: $checked, rejected by the window design: ${bad.size}")
        bad.groupBy { it.substringAfter(": ").take(60) }.forEach { (r, l) -> println("  ${l.size}x $r  e.g. ${l.first().substringBefore(":")}") }
        assertTrue(checked > 10, "the planner should beat-match or cut most of these pairs")
        assertTrue(bad.isEmpty(), "window design rejects real plans: $bad")
    }

    /** A click every beat as interleaved 48 kHz stereo covering source range [fromMs, toMs]. */
    private fun clicks(bpm: Float, firstBeatMs: Int, fromMs: Long, toMs: Long, sr: Int = 48_000): StereoPcm {
        val n = ((toMs - fromMs) * sr / 1000).toInt()
        val out = FloatArray(2 * n)
        val beat = 60_000.0 / bpm
        var k = kotlin.math.ceil((fromMs - firstBeatMs) / beat).toInt().coerceAtLeast(0)
        while (true) {
            val t = firstBeatMs + k * beat
            if (t >= toMs) break
            val s0 = ((t - fromMs) * sr / 1000).toInt()
            for (i in 0 until (0.01 * sr).toInt()) {
                val idx = s0 + i
                if (idx !in 0 until n) continue
                val v = (sin(2 * PI * 1000.0 * i / sr) * exp(-i / (0.003 * sr)) * 0.8).toFloat()
                out[2 * idx] = v
                out[2 * idx + 1] = v
            }
            k++
        }
        return StereoPcm(out, sr, fromMs)
    }

    @Test
    fun rendererHonoursTheWindowContract() {
        val a = band(124f)
        val b = band(128f)
        val plan = planner.plan(a, b, settings)
        assertTrue(plan.kind == PlanKind.BEAT_MATCHED, "expected a beat-matched plan, got ${plan.kind}: ${plan.reason}")
        assertTrue(WindowTimeline.check(plan) is Eligibility.Ok)
        val tl = WindowTimeline.build(plan)
        val outR = tl.outgoingDecodeRange()
        val inR = tl.incomingDecodeRange()
        val out = clicks(124f, 0, outR.first, outR.last)
        val inc = clicks(128f, 0, inR.first, inR.last)
        val file = File.createTempFile("window", ".wav")
        val rendered = BrainWindowRenderer().render(RenderRequest(plan, tl.startRelMs, tl.endRelMs, out, inc, 48_000, file))

        val expectedFrames = Math.ceil((tl.endRelMs - tl.startRelMs) * 48.0).toLong()
        assertTrue(abs(rendered.frames - expectedFrames) <= 2, "frames ${rendered.frames} vs $expectedFrames")
        val wav = WavIo.read(file)
        assertEquals(48_000, wav.sampleRate)
        assertEquals(2, wav.channels.size)
        assertEquals(rendered.frames.toInt(), wav.frames)
        file.delete()

        // The first window seconds are plain outgoing audio: their clicks must sit where the outgoing source says.
        val l = wav.channels[0]
        val beat = 60_000.0 / 124.0
        var worst = 0.0
        var found = 0
        var i = 0
        val plainEnd = (tl.xfadeOutWindowMs.coerceAtMost(2000) * 48).toInt()
        while (i < plainEnd) {
            if (abs(l[i]) > 0.4f) {
                val winMs = i * 1000.0 / 48_000
                val src = tl.outgoingSourceOfWindow(winMs)
                val nearest = Math.round(src / beat) * beat
                worst = maxOf(worst, abs(src - nearest))
                found++
                i += (0.05 * 48_000).toInt()
            } else i++
        }
        println("plain-outgoing clicks found: $found, worst offset to the beat grid: ${"%.2f".format(worst)} ms")
        assertTrue(found >= 2, "expected clicks in the lead-in")
        assertTrue(worst < 3.0, "outgoing lead-in is off the beat grid by $worst ms")
    }

    @Test
    fun echoOutPlansRenderAndHandOffAtThePhraseStart() {
        val a = band(87f)
        val b = band(140f)
        val plan = planner.plan(a, b, settings)
        assertTrue(plan.kind == PlanKind.ECHO_OUT, "expected an ECHO_OUT for 87 -> 140 BPM (a bare cut is no longer planned), got ${plan.kind}: ${plan.reason}")
        assertTrue(WindowTimeline.check(plan) is Eligibility.Ok, "an ECHO_OUT must be a window-eligible plan: ${WindowTimeline.check(plan)}")
        val echo = plan.echoOut!!
        assertTrue(WindowTimeline.build(plan).settledRelMs >= echo.tailMs, "the window must last until the echo has rung out")
        val tl = WindowTimeline.build(plan)
        val outR = tl.outgoingDecodeRange()
        val inR = tl.incomingDecodeRange()
        val file = File.createTempFile("cut", ".wav")
        val rendered = BrainWindowRenderer().render(
            RenderRequest(plan, tl.startRelMs, tl.endRelMs, clicks(87f, 0, outR.first, outR.last), clicks(140f, 0, inR.first, inR.last), 48_000, file),
        )
        val wav = WavIo.read(file)
        assertEquals(rendered.frames.toInt(), wav.frames)
        // Before T0 it is the outgoing deck, after it the incoming one: both must be audible somewhere in the window.
        val t0 = ((-tl.startRelMs) * 48).toInt()
        val l = wav.channels[0]
        val before = (0 until t0).count { abs(l[it]) > 0.4f }
        val after = (t0 until wav.frames).count { abs(l[it]) > 0.4f }
        println("echo-out window: $before loud samples before T0, $after after")
        assertTrue(before > 0 && after > 0, "both decks must be heard around the echo-out")
        file.delete()
    }
}
