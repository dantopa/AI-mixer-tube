package org.simpmusic.dj

import org.simpmusic.dj.model.*
import org.simpmusic.dj.planner.DjTransitionPlanner
import org.simpmusic.dj.render.AudioSegment
import org.simpmusic.dj.render.BeatEcho
import org.simpmusic.dj.render.OfflineMixRenderer
import org.simpmusic.dj.render.OfflineMixRenderer.Options
import org.simpmusic.dj.render.OfflineMixRenderer.Solo
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EchoOutRenderTest {
    private val sr = 44100
    private val planner = DjTransitionPlanner()
    private val settings = DjSettings(enabled = true)

    private class Pair2(val ra: SyntheticTracks.Rendered, val rb: SyntheticTracks.Rendered, val plan: TransitionPlan)

    private fun echoPair(bpmA: Float = 120f, bpmB: Float = 150f): Pair2 {
        val ra = SyntheticTracks.render(SyntheticTracks.Spec(bpm = bpmA, key = MusicalKey(9, Mode.MINOR), sampleRate = sr))
        val rb = SyntheticTracks.render(SyntheticTracks.Spec(bpm = bpmB, key = MusicalKey(9, Mode.MINOR), sampleRate = sr, seed = 5))
        val plan = planner.plan(FakeAnalysis.fromTruth(ra.truth, "a"), FakeAnalysis.fromTruth(rb.truth, "b"), settings)
        assertEquals(PlanKind.ECHO_OUT, plan.kind, plan.reason)
        return Pair2(ra, rb, plan)
    }

    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        val a = from.coerceIn(0, x.size)
        val b = to.coerceIn(a, x.size)
        if (b == a) return 0.0
        var s = 0.0
        for (i in a until b) s += x[i].toDouble() * x[i]
        return sqrt(s / (b - a))
    }

    private fun db(x: Double) = 20 * log10(x.coerceAtLeast(1e-12))

    private fun maxStep(x: FloatArray, from: Int = 1, to: Int = x.size): Double {
        var m = 0.0
        for (i in maxOf(1, from) until minOf(to, x.size)) m = maxOf(m, abs((x[i] - x[i - 1]).toDouble()))
        return m
    }

    @Test
    fun windowContractHolds() {
        val (ra, rb, plan) = echoPair().let { Triple(it.ra, it.rb, it.plan) }
        val w = OfflineMixRenderer.render(plan, AudioSegment.of(ra.audio), AudioSegment.of(rb.audio), Options())
        // first output sample is plan time -preRollMs, T0 is where the plan says
        assertEquals(plan.preRollMs.toDouble(), w.startMs, 1000.0 / sr)
        assertEquals(-plan.preRollMs * sr / 1000.0, w.t0Frame.toDouble(), 1.0)
        // the window covers the whole echo tail (settling time) plus the default tail
        assertTrue(w.endMs >= plan.settleMs, "window ends at ${w.endMs}, settles at ${plan.settleMs}")
        assertTrue(plan.settleMs >= plan.echoOut!!.tailMs)
        println("echo-out window: ${plan.preRollMs} .. ${w.endMs.roundToInt()} ms, tail ${plan.echoOut!!.tailMs} ms, delay ${plan.echoOut!!.delayMs} ms")
    }

    @Test
    fun theEchoTailDecaysMonotonicallyAfterTheDrySignalIsGone() {
        val (ra, rb, plan) = echoPair().let { Triple(it.ra, it.rb, it.plan) }
        val w = OfflineMixRenderer.render(plan, AudioSegment.of(ra.audio), AudioSegment.of(rb.audio), Options(solo = Solo.OUTGOING, tailMs = 1000L))
        val x = w.audio.left
        val t0 = w.t0Frame
        // the dry signal is gone at T0: only the delay is left from there
        val tail = plan.echoOut!!.tailMs
        val step = sr // 1 s windows: longer than the delay so the pattern of repeats averages out
        val levels = ArrayList<Double>()
        var f = t0
        while (f + step <= t0 + (tail * sr / 1000L).toInt()) {
            levels += rms(x, f, f + step)
            f += step
        }
        println("echo tail levels (dB, 1 s windows after T0): " + levels.joinToString { "%.1f".format(db(it)) })
        assertTrue(levels.size >= 3)
        assertTrue(levels[0] > 1e-3, "the echo is not audible at all")
        for (i in 1 until levels.size) assertTrue(levels[i] <= levels[i - 1] * 1.05, "tail grew: window $i ${db(levels[i])} dB vs ${db(levels[i - 1])} dB")
        assertTrue(db(levels.last()) < db(levels.first()) - 12.0, "tail did not fall enough")
        // after the tail is over the outgoing deck is silent for good
        val tailEnd = t0 + (tail * sr / 1000L).toInt() + 2
        assertTrue(rms(x, tailEnd, x.size) < 1e-7, "outgoing deck still sounds after its tail")
    }

    @Test
    fun theMixStaysUpAfterT0AndTheIncomingDeckIsBitExactNativeOnceSettled() {
        val (ra, rb, plan) = echoPair().let { Triple(it.ra, it.rb, it.plan) }
        val opts = Options(tailMs = 3000L)
        val w = OfflineMixRenderer.render(plan, AudioSegment.of(ra.audio), AudioSegment.of(rb.audio), opts)
        val solo = OfflineMixRenderer.render(plan, AudioSegment.of(ra.audio), AudioSegment.of(rb.audio), opts.copy(solo = Solo.INCOMING))
        val mix = w.audio.left
        val inc = solo.audio.left
        val t0 = w.t0Frame
        // energy: from T0 on, in 250 ms blocks, the mix is never more than 6 dB below the incoming deck alone
        val blk = sr / 4
        var worst = 0.0
        var f = t0
        while (f + blk <= mix.size) {
            val ri = rms(inc, f, f + blk)
            if (ri > 0.02) worst = minOf(worst, db(rms(mix, f, f + blk)) - db(ri))
            f += blk
        }
        println("mix vs incoming deck alone after T0: worst ${"%.2f".format(worst)} dB")
        assertTrue(worst > -6.0, "energy dropped ${worst} dB below the incoming deck")

        // once the echo has rung out the window IS the incoming source, sample for sample
        val start = w.handoffFrame
        assertTrue(start < w.frames - sr / 2, "no window left after the hand-off")
        var maxErr = 0.0
        for (i in start until w.frames) {
            val srcFrame = (w.incomingSourceMsAtFrame(i) * sr / 1000.0).roundToInt()
            val ref = rb.audio.samples[srcFrame]
            maxErr = maxOf(maxErr, abs((mix[i] - ref).toDouble()))
        }
        println("incoming deck vs native source after settle+relock: max error $maxErr (${"%.1f".format(db(maxErr))} dBFS)")
        assertTrue(maxErr < 1e-5, "incoming deck is not native after settle: $maxErr")
    }

    @Test
    fun noDiscontinuitySpikes() {
        val (ra, rb, plan) = echoPair().let { Triple(it.ra, it.rb, it.plan) }
        val w = OfflineMixRenderer.render(plan, AudioSegment.of(ra.audio), AudioSegment.of(rb.audio), Options(tailMs = 2000L))
        val mix = w.audio.left
        val srcStep = maxOf(maxStep(ra.audio.samples), maxStep(rb.audio.samples))
        val mixStep = maxStep(mix)
        println("largest sample step: mix $mixStep, sources $srcStep")
        assertTrue(mixStep <= 1.6 * srcStep, "spike in the mix: $mixStep vs $srcStep")
        assertTrue(mix.all { abs(it) <= 1f })
        // around the hand-over from the fading outgoing deck to the incoming one nothing jumps
        val t0 = w.t0Frame
        assertTrue(maxStep(mix, t0 - 200, t0 + 200) <= 1.6 * srcStep)
    }

    @Test
    fun beatEchoImpulseResponseIsAGeometricSeries() {
        // transparent loop filters so the repeats are exact copies
        val e = BeatEcho(sr, 250.0, 0.5, highpassHz = 5.0, dampHz = 20_000.0, pingPong = false)
        val n = sr * 3
        val l = FloatArray(n)
        val r = FloatArray(n)
        l[0] = 1f
        r[0] = 1f
        val wl = FloatArray(n)
        val wr = FloatArray(n)
        e.processWet(l, r, wl, wr, 0, n)
        val d = e.delayInFrames
        assertEquals(0f, wl[0])
        // every hop through the loop scales the repeat by the feedback (0.5), less what the loop filters smear away
        val peaks = (1..7).map { k -> (k * d - 3..k * d + 3).maxOf { abs(wl[it]) } }
        for (k in 1 until peaks.size) {
            val ratio = peaks[k] / peaks[k - 1]
            assertTrue(ratio in 0.38..0.52, "repeat ${k + 1} / repeat $k = $ratio")
        }
    }

    @Test
    fun beatEchoPingPongAlternatesChannelsAndStaysStable() {
        val e = BeatEcho(sr, 300.0, 0.6, pingPong = true)
        val n = sr * 2
        val l = FloatArray(n)
        val r = FloatArray(n)
        l[0] = 1f
        val wl = FloatArray(n)
        val wr = FloatArray(n)
        e.processWet(l, r, wl, wr, 0, n)
        val d = e.delayInFrames
        // first repeat on the left, second on the right, third left again
        val a = (d - 3..d + 3).maxOf { abs(wl[it]) }
        val b = (2 * d - 6..2 * d + 6).maxOf { abs(wr[it]) }
        val c = (3 * d - 9..3 * d + 9).maxOf { abs(wl[it]) }
        assertTrue(a > 0.05 && b > 0.05 && c > 0.02, "$a $b $c")
        assertTrue((d - 3..d + 3).maxOf { abs(wr[it]) } < 1e-6, "right channel must be silent on the first repeat")
        assertTrue(b < a && c < b, "repeats must decay")
        // stability with the maximum feedback on noise: bounded for a long time
        val hot = BeatEcho(sr, 100.0, 0.99)
        val rnd = java.util.Random(3)
        val blockN = 4096
        val bl = FloatArray(blockN)
        val br = FloatArray(blockN)
        val ol = FloatArray(blockN)
        val or = FloatArray(blockN)
        var peak = 0f
        repeat(400) {
            for (i in 0 until blockN) { bl[i] = rnd.nextGaussian().toFloat() * 0.3f; br[i] = rnd.nextGaussian().toFloat() * 0.3f }
            hot.processWet(bl, br, ol, or, 0, blockN)
            for (i in 0 until blockN) peak = maxOf(peak, abs(ol[i]), abs(or[i]))
        }
        assertTrue(peak < 20f && peak.isFinite(), "unstable: peak $peak")
    }

    @Test
    fun beatEchoInsertFormAddsTheDelayedSignalToTheDrySignal() {
        val a = BeatEcho(sr, 100.0, 0.5)
        val b = BeatEcho(sr, 100.0, 0.5)
        val n = 20_000
        val rnd = java.util.Random(1)
        val l = FloatArray(n) { rnd.nextGaussian().toFloat() * 0.1f }
        val r = FloatArray(n) { rnd.nextGaussian().toFloat() * 0.1f }
        val l2 = l.copyOf()
        val r2 = r.copyOf()
        val wl = FloatArray(n)
        val wr = FloatArray(n)
        a.processWet(l, r, wl, wr, 0, n)
        b.process(l2, r2, 0, n)
        for (i in 0 until n) {
            assertEquals(l[i] + wl[i], l2[i], 1e-6f)
            assertEquals(r[i] + wr[i], r2[i], 1e-6f)
        }
    }
}
