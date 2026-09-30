package org.simpmusic.dj

import org.simpmusic.dj.TestAudio.FnAutomation
import org.simpmusic.dj.model.DeckClock
import org.simpmusic.dj.model.Ease
import org.simpmusic.dj.model.Keyframe
import org.simpmusic.dj.model.ParamCurve
import org.simpmusic.dj.render.DeckStretcher
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StretcherTest {
    private val sr = 44100

    @Test
    fun latencyIsReportedAndOutputStartsAtTheAnchor() {
        val st = DeckStretcher(2, sr)
        assertEquals(1024, st.latencyFrames)
        // at rate 1 the vocoder is an identity: output frame i IS source frame start+i (no delay, no fade-in)
        val x = FloatArray(sr * 3) { (0.4 * sin(2 * PI * 330.0 * it / sr) + 0.2 * sin(2 * PI * 1250.0 * it / sr)).toFloat() }
        val y = FloatArray(sr * 3) { (0.3 * sin(2 * PI * 500.0 * it / sr + 1.0)).toFloat() }
        val start = 10_000
        val out = TestAudio.stretch(arrayOf(x, y), sr, start.toDouble(), sr, FnAutomation({ 1.0 }))
        var worst = 0.0
        for (i in 0 until sr) worst = maxOf(worst, abs(out[0][i] - x[start + i]).toDouble(), abs(out[1][i] - y[start + i]).toDouble())
        println("identity at rate 1: max |out - source| = $worst")
        assertTrue(worst < 2e-5, "not an identity: $worst")
    }

    @Test
    fun tempoAccuracyOver30Seconds() {
        val click = SyntheticTracks.clickTrack(120f, 110, sampleRate = sr) // 55 s of clicks, one every 500 ms
        val x = click.audio.samples
        println("rate     periodErr(%)   maxClickPosErr(ms)")
        for (rate in listOf(0.7, 0.85, 0.96, 1.0, 1.04, 1.1, 1.25, 1.4)) {
            val frames = 30 * sr
            val out = TestAudio.stretch(arrayOf(x), sr, 0.0, frames, FnAutomation({ rate }))
            val clicks = TestAudio.detectClicks(out[0], sr)
            val n = clicks.size
            assertTrue(n >= 30 * 2 / (rate + 0.5).toInt().coerceAtLeast(1) / 2, "too few clicks detected: $n at rate $rate")
            // expected wall time of click k: 500 k / rate
            var worst = 0.0
            for ((k, t) in clicks.withIndex()) worst = maxOf(worst, abs(t - 500.0 * k / rate))
            val period = (clicks.last() - clicks.first()) / (n - 1)
            val err = abs(period - 500.0 / rate) / (500.0 / rate) * 100
            println(String.format("%.2f   %.5f        %.3f", rate, err, worst))
            assertTrue(err < 0.05, "tempo error $err% at rate $rate")
            assertTrue(worst < if (abs(rate - 1) < 0.15) 1.0 else 3.5, "click position error $worst ms at rate $rate")
        }
    }

    @Test
    fun sourcePositionIsTheIntegralOfTheRateLane() {
        val x = SyntheticTracks.clickTrack(100f, 100, sampleRate = sr).audio.samples
        val lane = ParamCurve(listOf(Keyframe(0, 1f), Keyframe(4000, 1f), Keyframe(9000, 1.3f, Ease.SMOOTHSTEP), Keyframe(15000, 0.8f, Ease.LINEAR), Keyframe(20000, 0.8f)))
        val clock = DeckClock(lane, 0.0, 0, 40_000)
        val st = DeckStretcher(1, sr)
        val frames = 25 * sr
        st.start(0.0, TestAudio.source(arrayOf(x)), FnAutomation({ lane.valueAt(it * 1000.0 / sr).toDouble() }))
        val out = arrayOf(FloatArray(frames))
        var pos = 0
        var worstAcct = 0.0
        while (pos < frames) {
            val n = minOf(200, frames - pos)
            st.render(out, pos, n)
            pos += n
            // internal accounting vs the exact integral
            val expected = clock.sourceAt(pos * 1000.0 / sr) * sr / 1000.0
            worstAcct = maxOf(worstAcct, abs(st.sourceFrameOfNextOutput() - expected))
        }
        println("source position accounting: max |stretcher - integral| = $worstAcct frames (${worstAcct * 1000 / sr} ms)")
        assertTrue(worstAcct < 2.0, "accounting drift $worstAcct frames")
        // and the audible clicks land where the integral says
        val clicks = TestAudio.detectClicks(out[0], sr, minGapMs = 150.0)
        var worst = 0.0
        for ((k, t) in clicks.withIndex()) worst = maxOf(worst, abs(t - clock.wallAt(600.0 * k)))
        println("clicks under a varying rate lane: ${clicks.size} clicks, max position error ${"%.3f".format(worst)} ms")
        assertTrue(worst < 3.0, "click error $worst ms")
    }

    @Test
    fun pitchShiftAccuracy() {
        println("pitch: semitones rate  f_in  f_out  error(cents)")
        for ((semi, rate) in listOf(2.0 to 1.0, -2.0 to 1.0, 1.0 to 1.0, 5.0 to 1.0, 2.0 to 1.2, -1.0 to 0.85, 0.0 to 1.3)) {
            for (f in listOf(110.0, 440.0, 1760.0)) {
                val x = TestAudio.sine(f, 8.0, sr)
                val ratio = 2.0.pow(semi / 12)
                val out = TestAudio.stretch(arrayOf(x), sr, 0.0, 5 * sr, FnAutomation({ rate }, { ratio }))
                val fo = TestAudio.peakFrequency(out[0], sr, sr)
                val cents = TestAudio.cents(fo, f * ratio)
                println(String.format("pitch %+.0f st rate %.2f %7.1f -> %8.2f  %+.3f cents", semi, rate, f, fo, cents))
                assertTrue(abs(cents) < 10.0, "pitch error $cents cents ($semi st, rate $rate, $f Hz)")
            }
        }
    }

    @Test
    fun noDiscontinuitiesWhenRateAndPitchChange() {
        val f = 440.0
        val x = TestAudio.sine(f, 20.0, sr, amp = 0.5)
        val idealDelta = 0.5 * 2 * PI * f / sr
        data class Case(val name: String, val rate: (Double) -> Double, val pitch: (Double) -> Double)
        val sec = { fr: Double -> fr / sr }
        val cases = listOf(
            Case("const 0.7", { 0.7 }, { 1.0 }),
            Case("const 1.4", { 1.4 }, { 1.0 }),
            Case("const 1.04", { 1.04 }, { 1.0 }),
            Case("sweep 0.7->1.4", { 0.7 + 0.7 * (sec(it) / 10.0).coerceIn(0.0, 1.0) }, { 1.0 }),
            Case("hard step 1.0->1.3 at 3 s", { if (sec(it) < 3.0) 1.0 else 1.3 }, { 1.0 }),
            Case("hard step 0.75->1.0 at 3 s", { if (sec(it) < 3.0) 0.75 else 1.0 }, { 1.0 }),
            Case("pitch sweep 0->+2 st", { 1.0 }, { 2.0.pow(2.0 * (sec(it) / 8.0).coerceIn(0.0, 1.0) / 12) }),
            Case("rate ramp + pitch", { 1.0 + 0.15 * (sec(it) / 6.0).coerceIn(0.0, 1.0) }, { 2.0.pow(-1.0 / 12) }),
        )
        println("continuity (sine 440 Hz): case  maxDelta/idealDelta")
        for (c in cases) {
            val out = TestAudio.stretch(arrayOf(x), sr, 0.0, 10 * sr, FnAutomation(c.rate, c.pitch))
            val skip = sr / 4
            val worst = TestAudio.maxAbsDelta(out[0], skip, out[0].size) / idealDelta
            val amp = (skip until out[0].size).maxOf { abs(out[0][it]) }
            println(String.format("  %-28s %.3f  (peak %.3f)", c.name, worst, amp))
            // a click would show up as a delta many times the ideal one
            val pitchFactor = c.pitch(5.0 * sr)
            assertTrue(worst < 1.25 * maxOf(1.0, pitchFactor) * 1.1, "discontinuity in '${c.name}': ${"%.3f".format(worst)}x the ideal sample step")
            assertTrue(amp < 0.62, "amplitude bump in '${c.name}': $amp")
        }
    }

    @Test
    fun stereoImageIsPreservedUnderStretch() {
        val n = 6 * sr
        val l = FloatArray(n) { (0.4 * sin(2 * PI * 300.0 * it / sr)).toFloat() }
        val r = FloatArray(n) { (0.4 * sin(2 * PI * 300.0 * it / sr + 0.9)).toFloat() } // fixed 0.9 rad phase offset
        val out = TestAudio.stretch(arrayOf(l, r), sr, 0.0, 4 * sr, FnAutomation({ 1.08 }))
        // measure inter-channel phase in the steady state with a single-bin DFT
        fun phase(x: FloatArray): Double {
            var re = 0.0
            var im = 0.0
            for (i in sr until 3 * sr) {
                re += x[i] * kotlin.math.cos(2 * PI * 300.0 * i / sr)
                im -= x[i] * sin(2 * PI * 300.0 * i / sr)
            }
            return kotlin.math.atan2(im, re)
        }
        var d = phase(out[1]) - phase(out[0])
        while (d > PI) d -= 2 * PI
        while (d < -PI) d += 2 * PI
        println("inter-channel phase after stretch: $d rad (input 0.9)")
        assertEquals(0.9, d, 0.05)
    }

    @Test
    fun monoAndStereoBehaveTheSame() {
        val x = TestAudio.sine(523.25, 6.0, sr)
        val mono = TestAudio.stretch(arrayOf(x), sr, 0.0, 3 * sr, FnAutomation({ 1.13 }))
        val st = TestAudio.stretch(arrayOf(x, x), sr, 0.0, 3 * sr, FnAutomation({ 1.13 }))
        var worst = 0.0
        for (i in 0 until 3 * sr) worst = maxOf(worst, abs(mono[0][i] - st[0][i]).toDouble(), abs(st[0][i] - st[1][i]).toDouble())
        println("mono vs stereo: max diff $worst")
        assertTrue(worst < 1e-4)
    }

    @Test
    fun transientsKeepTheirAttack() {
        // a drum-like click train stretched by 8%: each click should stay compact (energy within +-3 ms of its peak)
        val x = SyntheticTracks.clickTrack(120f, 40, sampleRate = sr).audio.samples
        val out = TestAudio.stretch(arrayOf(x), sr, 0.0, 15 * sr, FnAutomation({ 1.08 }))
        val clicks = TestAudio.detectClicks(out[0], sr)
        var share = 1.0
        for (t in clicks.drop(2).take(20)) {
            val c = (t * sr / 1000).toInt()
            val near = TestAudio.rms(out[0], c - (0.003 * sr).toInt(), c + (0.02 * sr).toInt())
            val wide = TestAudio.rms(out[0], c - (0.06 * sr).toInt(), c + (0.06 * sr).toInt())
            share = minOf(share, near * kotlin.math.sqrt(0.023) / (wide * kotlin.math.sqrt(0.12)))
        }
        println("transient compactness (1.0 = perfectly compact): $share")
        assertTrue(share > 0.7, "clicks are smeared: $share")
    }
}
