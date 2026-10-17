package org.simpmusic.dj.bus

import org.simpmusic.dj.FakeAnalysis
import org.simpmusic.dj.SyntheticTracks
import org.simpmusic.dj.model.DeckClock
import org.simpmusic.dj.model.DeckPlan
import org.simpmusic.dj.model.DjSettings
import org.simpmusic.dj.model.Mode
import org.simpmusic.dj.model.MusicalKey
import org.simpmusic.dj.model.ParamCurve
import org.simpmusic.dj.model.PlanKind
import org.simpmusic.dj.model.SourceLoop
import org.simpmusic.dj.model.TransitionPlan
import org.simpmusic.dj.planner.DjTransitionPlanner
import org.simpmusic.dj.render.AudioSegment
import org.simpmusic.dj.render.DeckVoice
import org.simpmusic.dj.render.OfflineMixRenderer
import org.simpmusic.dj.render.OfflineMixRenderer.Options
import org.simpmusic.dj.render.OfflineMixRenderer.Solo
import org.simpmusic.dj.render.StereoPcm
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The real-time bus against the offline renderer: the same plan on the same PCM, delivered through [DeckRing]s by a
 * bursty producer and rendered in odd block sizes, must give the offline window sample for sample.
 */
class MixBusTest {
    private val sr = 48_000
    private val planner = DjTransitionPlanner()
    private val settings = DjSettings(enabled = true)

    // ---------------------------------------------------------------------------------------------
    // material

    /** A synthetic band track made stereo (right = left delayed 7 frames, -1 dB), optionally quantised to 16 bits. */
    private fun stereoTrack(bpm: Float, key: MusicalKey, seed: Long, quantise16: Boolean = false): Pair<StereoPcm, SyntheticTracks.Rendered> {
        val r = SyntheticTracks.render(SyntheticTracks.Spec(bpm = bpm, key = key, sampleRate = sr, seed = seed))
        val x = r.audio.samples
        val l = FloatArray(x.size) { x[it] }
        val rr = FloatArray(x.size) { if (it >= 7) x[it - 7] * 0.89f else 0f }
        if (quantise16) for (a in arrayOf(l, rr)) for (i in a.indices) a[i] = Math.round(a[i] * 32768f).coerceIn(-32768, 32767) / 32768f
        return StereoPcm(l, rr, sr) to r
    }

    private class Pair2(val a: StereoPcm, val b: StereoPcm, val plan: TransitionPlan)

    private fun beatMatchedPair(loudIn: Float = -14f): Pair2 {
        val (a, ra) = stereoTrack(124f, MusicalKey(9, Mode.MINOR), 1)
        val (b, rb) = stereoTrack(128f, MusicalKey(0, Mode.MAJOR), 7, quantise16 = true)
        val plan = planner.plan(FakeAnalysis.fromTruth(ra.truth, "a"), FakeAnalysis.fromTruth(rb.truth, "b", loudnessDb = loudIn), settings)
        assertEquals(PlanKind.BEAT_MATCHED, plan.kind, plan.reason)
        return Pair2(a, b, plan)
    }

    private fun echoPair(): Pair2 {
        val (a, ra) = stereoTrack(120f, MusicalKey(9, Mode.MINOR), 1)
        val (b, rb) = stereoTrack(150f, MusicalKey(9, Mode.MINOR), 5, quantise16 = true)
        val plan = planner.plan(FakeAnalysis.fromTruth(ra.truth, "a"), FakeAnalysis.fromTruth(rb.truth, "b"), settings)
        assertEquals(PlanKind.ECHO_OUT, plan.kind, plan.reason)
        return Pair2(a, b, plan)
    }

    // ---------------------------------------------------------------------------------------------
    // harness

    /** A player's audio sink: writes [pcm] from [startFrame] into [ring] in buffers, retrying what the ring refuses. */
    private class Producer(val ring: DeckRing, val pcm: StereoPcm, var next: Long, val pcm16: Boolean, val rnd: Random) {
        private val buf: ByteBuffer = ByteBuffer.allocate(MAX_BURST * 8).order(ByteOrder.LITTLE_ENDIAN)
        private var pendingPts = 0L
        private var hasPending = false
        var stopped = false

        init {
            ring.configure(ring.sampleRate, 2, if (pcm16) DeckRing.Encoding.PCM_16 else DeckRing.Encoding.PCM_FLOAT)
        }

        /** One sink call: the pending buffer again, or a fresh one of [frames]. Returns frames accepted. */
        fun writeOnce(frames: Int): Int {
            if (stopped) return 0
            if (!hasPending) {
                val n = min(frames.toLong(), pcm.frames - next).toInt()
                if (n <= 0) { ring.endOfStream(); stopped = true; return 0 }
                buf.clear()
                for (i in 0 until n) {
                    val p = (next + i).toInt()
                    if (pcm16) {
                        buf.putShort(Math.round(pcm.left[p] * 32768f).coerceIn(-32768, 32767).toShort())
                        buf.putShort(Math.round(pcm.right[p] * 32768f).coerceIn(-32768, 32767).toShort())
                    } else {
                        buf.putFloat(pcm.left[p]); buf.putFloat(pcm.right[p])
                    }
                }
                buf.flip()
                pendingPts = (next * 1_000_000.0 / ring.sampleRate).roundToLong()
                next += n
                hasPending = true
            }
            val got = ring.write(buf, pendingPts)
            if (!buf.hasRemaining()) hasPending = false
            return got
        }

        /** Bursty delivery: a few buffers of random size, more when the deck runs low (a player keeps its buffer up). */
        fun pump(view: DeckRing.View, consumerAt: () -> Long, aheadMin: Int, bursty: Boolean = true) {
            val bursts = if (bursty) rnd.nextInt(3) else 0
            repeat(bursts) { writeOnce(1024 + rnd.nextInt(MAX_BURST - 1024)) }
            var guard = 0
            while (!stopped && guard++ < 64 && (!ring.snapshot(view) || view.end - consumerAt() < aheadMin)) {
                if (writeOnce(1024 + rnd.nextInt(MAX_BURST - 1024)) == 0 && ring.freeFrames() == 0) break
            }
        }

        companion object {
            const val MAX_BURST = 9600
        }
    }

    private class Recorder : MixBusCore.Listener {
        val events = ArrayList<String>()
        var started = -1L
        var incomingAt = -1L
        var done = -1L
        var abort: MixBusCore.AbortReason? = null
        var abortIncomingStarted: Boolean? = null
        override fun onTransitionStarted(planId: Int, outputFrame: Long) { started = outputFrame; events += "start@$outputFrame" }
        override fun onIncomingStarted(planId: Int, outputFrame: Long) { incomingAt = outputFrame; events += "incoming@$outputFrame" }
        override fun onTransitionDone(planId: Int, outputFrame: Long) { done = outputFrame; events += "done@$outputFrame" }
        override fun onTransitionAborted(planId: Int, reason: MixBusCore.AbortReason, incomingStarted: Boolean) {
            abort = reason; abortIncomingStarted = incomingStarted; events += "abort:$reason(incoming=$incomingStarted)"
        }
    }

    /** Everything a scenario produced. */
    private class Run(
        val outL: FloatArray, val outR: FloatArray, val frames: Int, val rec: Recorder, val bus: MixBusCore,
        val outDeck: Int, val inDeck: Int, val outStart: Long, val inStart: Long,
        /** sampled (outputFrame, outgoing source, incoming source, outgoing consumed, incoming consumed) */
        val positions: List<DoubleArray>,
    )

    /**
     * Plays [p] through a bus: the outgoing deck from [leadSec] s before the plan's first frame, the incoming deck cued
     * from [inLeadSec] s before its entry; renders blocks of 192..960 frames until [extraAfterDoneSec] after the end
     * (or [maxSec]). [hook] runs before every block with the window frame of the running transition (or -1).
     */
    private fun runBus(
        p: Pair2, seed: Long = 1L, leadSec: Double = 2.0, inLeadSec: Double = 0.5, extraAfterDoneSec: Double = 1.5,
        maxSec: Double = 120.0, feedIncoming: Boolean = true, aheadMin: Int = 6000,
        hook: (bus: MixBusCore, rec: Recorder, windowFrame: Long, out: Producer, inc: Producer) -> Unit = { _, _, _, _, _ -> },
    ): Run {
        val rec = Recorder()
        val bus = MixBusCore(sr, listener = rec)
        val outDeck = bus.addDeck()
        val inDeck = bus.addDeck(standby = true)
        bus.arm(p.plan, outDeck, inDeck)
        val switch = assertNotNull(bus.armedSwitchFrame())
        val entry = assertNotNull(bus.armedEntryFrame())
        val outStart = (switch - (leadSec * sr).toLong()).coerceAtLeast(0L)
        val inStart = (entry - (inLeadSec * sr).toLong()).coerceAtLeast(0L)
        val rnd = Random(seed)
        val outP = Producer(bus.ring(outDeck), p.a, outStart, pcm16 = false, rnd)
        val inP = Producer(bus.ring(inDeck), p.b, inStart, pcm16 = true, rnd)
        bus.play(outDeck)
        bus.play(inDeck)
        val cap = (maxSec * sr).toInt()
        val outL = FloatArray(cap)
        val outR = FloatArray(cap)
        val block = FloatArray(2 * 960)
        val vo = DeckRing.View()
        val vi = DeckRing.View()
        val positions = ArrayList<DoubleArray>()
        var f = 0
        var stopAt = Int.MAX_VALUE
        while (f < cap && f < stopAt) {
            val wf = if (rec.started >= 0 && bus.state == MixBusCore.State.RUNNING) f - rec.started else -1L
            hook(bus, rec, wf, outP, inP)
            outP.pump(vo, { bus.deckSourceFrameAt(outDeck, bus.renderedFrames).let { if (it.isNaN()) outStart else it.toLong() } }, aheadMin)
            if (feedIncoming) inP.pump(vi, { bus.deckSourceFrameAt(inDeck, bus.renderedFrames).let { if (it.isNaN()) inStart else max(entry, it.toLong()) } }, aheadMin)
            val n = min(192 + rnd.nextInt(960 - 192 + 1), cap - f)
            bus.render(block, n)
            for (i in 0 until n) { outL[f + i] = block[2 * i]; outR[f + i] = block[2 * i + 1] }
            if (rnd.nextInt(4) == 0) {
                val q = f + rnd.nextInt(n)
                positions += doubleArrayOf(
                    q.toDouble(), bus.deckSourceFrameAt(outDeck, q.toLong()), bus.deckSourceFrameAt(inDeck, q.toLong()),
                    bus.deckConsumedFramesAt(outDeck, q.toLong()).toDouble(), bus.deckConsumedFramesAt(inDeck, q.toLong()).toDouble(),
                )
            }
            f += n
            if (stopAt == Int.MAX_VALUE && (rec.done >= 0 || rec.abort != null) && bus.state == MixBusCore.State.IDLE) {
                stopAt = f + (extraAfterDoneSec * sr).toInt()
            }
        }
        return Run(outL, outR, f, rec, bus, outDeck, inDeck, outStart, inStart, positions)
    }

    private fun offline(p: Pair2, options: Options = Options(tailMs = 1500)): OfflineMixRenderer.Window =
        OfflineMixRenderer.render(p.plan, AudioSegment(p.a), AudioSegment(p.b), options)

    /** Window frame from which the incoming voice is on the copy path (first block boundary after settle + relock). */
    private fun handoffWindowFrame(w: OfflineMixRenderer.Window): Int = (w.handoffFrame + 255) / 256 * 256

    private class Diff(val mismatches: Int, val maxAbs: Double, val firstAt: Int)

    private fun diff(aL: FloatArray, aR: FloatArray, aOff: Int, bL: FloatArray, bR: FloatArray, bOff: Int, n: Int): Diff {
        var mism = 0
        var worst = 0.0
        var first = -1
        for (i in 0 until n) {
            val dl = aL[aOff + i].toRawBits() != bL[bOff + i].toRawBits()
            val dr = aR[aOff + i].toRawBits() != bR[bOff + i].toRawBits()
            if (dl || dr) {
                mism++
                if (first < 0) first = i
                worst = max(worst, max(abs(aL[aOff + i] - bL[bOff + i]).toDouble(), abs(aR[aOff + i] - bR[bOff + i]).toDouble()))
            }
        }
        return Diff(mism, worst, first)
    }

    // ---------------------------------------------------------------------------------------------
    // the key test

    private fun checkEquivalence(name: String, p: Pair2, seed: Long) {
        val w = offline(p)
        val run = runBus(p, seed = seed)
        println("$name: ${p.plan.kind} preRoll ${p.plan.preRollMs} ms, overlap ${p.plan.overlapMs} ms, settle ${w.handoffMs - OfflineMixRenderer.RELOCK_MS} ms; events ${run.rec.events}")
        assertTrue(run.rec.started >= 0, "$name: never started")
        assertTrue(run.rec.done > 0, "$name: never done: ${run.rec.events}")
        val w0 = run.rec.started.toInt()
        // 1. before the switch the bus IS the outgoing source, bit for bit (rate-1 copy path)
        val pre = diff(run.outL, run.outR, 0, p.a.left, p.a.right, run.outStart.toInt(), w0)
        assertEquals(0, pre.mismatches, "$name: normal playback differs from the source (max ${pre.maxAbs})")
        assertEquals(w.t0Frame.toLong(), run.rec.incomingAt - w0, "$name: incoming not started at T0")
        // 2. from the switch to the incoming hand-off: the offline window, bit for bit
        val hand = handoffWindowFrame(w)
        val total = w.frames
        assertTrue(hand < total - 512, "$name: window too short")
        val core = diff(run.outL, run.outR, w0, w.audio.left, w.audio.right, 0, hand)
        println(String.format("  window [0, %d) (%.2f s): %d mismatching samples, max %.3g", hand, hand / sr.toDouble(), core.mismatches, core.maxAbs))
        assertEquals(0, core.mismatches, "$name: bus differs from the offline window at window frame ${core.firstAt} (max ${core.maxAbs})")
        // 3. after the hand-off: the copy path against the offline vocoder (which has re-locked onto the source)
        val lastFull = total / 256 * 256 - 256
        val tail = diff(run.outL, run.outR, w0 + hand, w.audio.left, w.audio.right, hand, lastFull - hand)
        println(String.format("  after hand-off [%d, %d): copy path vs offline vocoder max |diff| %.3g (%d samples differ)", hand, lastFull, tail.maxAbs, tail.mismatches))
        assertTrue(tail.maxAbs < 1e-5, "$name: hand-off not seamless: ${tail.maxAbs}")
        // 4. and from the end of the cross-fade on, the bus IS the incoming source, bit for bit
        val d0 = run.bus.deckSourceFrameAt(run.inDeck, (w0 + hand).toLong())
        assertEquals(floor(d0), d0, "$name: incoming not on an integer frame after the hand-off")
        val from = hand + DeckVoice.HANDOFF_XFADE_FRAMES
        val n = run.frames - (w0 + from) - 1
        val copy = diff(run.outL, run.outR, w0 + from, p.b.left, p.b.right, d0.toInt() + DeckVoice.HANDOFF_XFADE_FRAMES, n)
        println(String.format("  copy path for %.2f s after the cross-fade: %d mismatching samples", n / sr.toDouble(), copy.mismatches))
        assertEquals(0, copy.mismatches, "$name: incoming copy path is not the source (max ${copy.maxAbs})")
        // and it continues where the plan's clock says (within half a frame)
        val clockIn = w.incomingSourceMsAtFrame(hand) * sr / 1000.0
        assertTrue(abs(d0 - clockIn) <= 0.5 + 1e-6, "$name: hand-off at source $d0, clock says $clockIn")

        // 5. positions: exact against the plan's clocks
        var checked = 0
        for (s in run.positions) {
            val q = s[0].toLong()
            val j = q - w0
            val srcOut = s[1]
            val srcIn = s[2]
            if (j < 0) {
                assertEquals((run.outStart + q).toDouble(), srcOut, 0.0, "$name: outgoing position before the switch")
                assertEquals(q, s[3].toLong())
                assertEquals(0L, s[4].toLong(), "$name: cued incoming must not have consumed anything")
            } else if (j < w.frames && j < hand) {
                val ref = w.outgoingSourceMsAtFrame(j.toInt()) * sr / 1000.0
                val silentFrom = run.rec.done - w0 // outgoing parked after its end
                if (j < silentFrom && p.plan.outgoing.volume.valueAt(w.planTimeMs(j.toInt())) > 0f) {
                    assertEquals(ref, srcOut, 1e-6, "$name: outgoing position at window frame $j")
                    assertEquals(floor(ref - run.outStart).toLong(), s[3].toLong(), "$name: outgoing consumed at $j")
                }
                if (j >= w.t0Frame) {
                    val refIn = w.incomingSourceMsAtFrame(j.toInt()) * sr / 1000.0
                    assertEquals(refIn, srcIn, 1e-6, "$name: incoming position at window frame $j")
                    assertEquals(floor(refIn - run.inStart).toLong(), s[4].toLong(), "$name: incoming consumed at $j")
                } else {
                    assertEquals(0L, s[4].toLong(), "$name: incoming consumed before T0")
                }
                checked++
            }
        }
        println("  positions: $checked samples inside the window match the plan clocks")
        assertTrue(checked > 20)
        // 6. the released outgoing deck is on standby and its position frozen
        assertTrue(run.bus.isStandby(run.outDeck))
        assertFalse(run.bus.isStandby(run.inDeck))
        val a = run.bus.deckSourceFrameAt(run.outDeck, run.rec.done)
        val b = run.bus.deckSourceFrameAt(run.outDeck, run.frames.toLong() - 1)
        assertEquals(a, b, 0.0, "$name: released outgoing deck still moves")
        assertTrue(run.bus.ring(run.inDeck).underrunFrames == 0L && run.bus.ring(run.outDeck).underrunFrames == 0L, "$name: underruns")
    }

    @Test
    fun beatMatchedTransitionIsTheOfflineWindowSampleForSample() {
        checkEquivalence("beat-matched 124->128", beatMatchedPair(), seed = 3)
        // the louder incoming deck gets a loudness gain that glides back to native inside the plan
        checkEquivalence("beat-matched, gain-matched", beatMatchedPair(loudIn = -10f), seed = 11)
    }

    /** A DJ loop on the outgoing (AI mix moves, build bc): the bus reads its ring through the same LoopSource. */
    @Test
    fun aLoopedOutgoingIsTheOfflineWindowSampleForSample() {
        val p = beatMatchedPair()
        val bar = 4 * 60_000.0 / 124
        val loop = SourceLoop(p.plan.exitPointMs, p.plan.exitPointMs + bar.roundToLong())
        assertTrue(p.plan.overlapMs > 2 * loop.lengthMs, "the overlap must repeat the loop")
        checkEquivalence("looped outgoing (1 bar)", Pair2(p.a, p.b, p.plan.copy(outLoop = loop)), seed = 9)
    }

    @Test
    fun echoOutTransitionIsTheOfflineWindowSampleForSample() {
        checkEquivalence("echo-out 120->150", echoPair(), seed = 5)
    }

    @Test
    fun aRunningTransitionSurvivesARealProducerThread() {
        // the same plan with each player on its own thread, retrying writes the ring refuses, the bus on the test thread
        val p = beatMatchedPair()
        val w = offline(p)
        val rec = Recorder()
        val bus = MixBusCore(sr, listener = rec)
        val outDeck = bus.addDeck(ringCapacityFrames = 2 * sr) // small rings: back-pressure all the time
        val inDeck = bus.addDeck(ringCapacityFrames = 2 * sr, standby = true)
        bus.arm(p.plan, outDeck, inDeck)
        val outStart = bus.armedSwitchFrame()!! - sr
        val inStart = bus.armedEntryFrame()!! - sr / 4
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        fun producer(ring: DeckRing, pcm: StereoPcm, from: Long, pcm16: Boolean, seed: Long) = Thread {
            val rnd = Random(seed)
            val pr = Producer(ring, pcm, from, pcm16, rnd)
            while (!stop.get() && !pr.stopped) {
                if (pr.writeOnce(512 + rnd.nextInt(4096)) == 0) Thread.onSpinWait()
            }
        }.also { it.isDaemon = true; it.start() }
        val t1 = producer(bus.ring(outDeck), p.a, outStart, false, 1)
        val t2 = producer(bus.ring(inDeck), p.b, inStart, true, 2)
        // let both rings fill before the bus starts (a player starts writing before the output plays)
        val v = DeckRing.View()
        while (!(bus.ring(outDeck).snapshot(v) && v.end - v.start >= sr)) Thread.sleep(1)
        while (!(bus.ring(inDeck).snapshot(v) && v.end - v.start >= sr)) Thread.sleep(1)
        bus.play(outDeck)
        bus.play(inDeck)
        val total = ((w.endMs - w.startMs) / 1000.0 * sr).toInt() + 3 * sr
        val outL = FloatArray(total)
        val outR = FloatArray(total)
        val blk = FloatArray(2 * 960)
        val rnd = Random(9)
        var f = 0
        while (f < total) {
            val n = min(192 + rnd.nextInt(769), total - f)
            bus.render(blk, n)
            for (i in 0 until n) { outL[f + i] = blk[2 * i]; outR[f + i] = blk[2 * i + 1] }
            f += n
            // a real-time consumer: 960 frames = 20 ms; run faster than real time but give the producers air
            if (rnd.nextInt(8) == 0) Thread.sleep(0, 200_000)
        }
        stop.set(true)
        t1.join(); t2.join()
        println("threaded producers: ${rec.events}; underruns out ${bus.ring(outDeck).underrunFrames} in ${bus.ring(inDeck).underrunFrames}")
        assertTrue(rec.done > 0, "never done: ${rec.events}")
        val w0 = rec.started.toInt()
        val hand = handoffWindowFrame(w)
        val pre = diff(outL, outR, 0, p.a.left, p.a.right, outStart.toInt(), w0)
        val core = diff(outL, outR, w0, w.audio.left, w.audio.right, 0, hand)
        println("  before the switch: ${pre.mismatches} mismatches; window: ${core.mismatches} mismatches (max ${core.maxAbs})")
        if (bus.ring(outDeck).underrunFrames == 0L && bus.ring(inDeck).underrunFrames == 0L) {
            assertEquals(0, pre.mismatches)
            assertEquals(0, core.mismatches)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // readiness and aborts

    @Test
    fun anIncomingRingWithoutItsEntryIsNotReadyAndTheOutgoingPlaysOn() {
        val p = beatMatchedPair()
        val run = runBus(p, feedIncoming = false, extraAfterDoneSec = 2.0)
        println("empty incoming ring: ${run.rec.events}")
        assertEquals(MixBusCore.AbortReason.INCOMING_NOT_READY, run.rec.abort)
        assertEquals(false, run.rec.abortIncomingStarted)
        assertEquals(-1L, run.rec.started)
        // the outgoing simply played on, bit for bit, across the plan's start
        val d = diff(run.outL, run.outR, 0, p.a.left, p.a.right, run.outStart.toInt(), run.frames)
        assertEquals(0, d.mismatches, "outgoing touched by a refused plan (max ${d.maxAbs})")
        assertEquals(MixBusCore.State.IDLE, run.bus.state)
        assertTrue(run.bus.isStandby(run.inDeck))
    }

    @Test
    fun anIncomingRingThatStartsAfterTheEntryIsNotReady() {
        val p = beatMatchedPair()
        val run = runBus(p, inLeadSec = -0.5, extraAfterDoneSec = 0.5)
        assertEquals(MixBusCore.AbortReason.INCOMING_NOT_READY, run.rec.abort, run.rec.events.toString())
    }

    @Test
    fun armingBehindTheOutgoingIsTooLate() {
        val p = beatMatchedPair()
        val rec = Recorder()
        val bus = MixBusCore(sr, listener = rec)
        val o = bus.addDeck()
        val i = bus.addDeck(standby = true)
        // the outgoing player is already past the plan's first frame
        val probe = MixBusCore(sr).also { it.addDeck(); it.addDeck(); it.arm(p.plan, 0, 1) }
        val pr = Producer(bus.ring(o), p.a, probe.armedSwitchFrame()!! + sr, false, Random(1))
        repeat(4) { pr.writeOnce(9000) }
        bus.play(o)
        val blk = FloatArray(1920)
        bus.render(blk, 960)
        bus.arm(p.plan, o, i)
        bus.render(blk, 960)
        assertEquals(MixBusCore.AbortReason.TOO_LATE, rec.abort)
        assertEquals(MixBusCore.State.IDLE, bus.state)
    }

    private fun checkAbortAfterT0(name: String, act: (MixBusCore, Int, Producer) -> Unit, expected: MixBusCore.AbortReason) {
        val p = beatMatchedPair()
        val w = offline(p)
        val soloIn = offline(p, Options(tailMs = 1500, solo = Solo.INCOMING))
        var abortWf = -1L
        val atWf = w.t0Frame + 3L * sr // 3 s into the overlap
        val run = runBus(p, extraAfterDoneSec = 40.0, maxSec = 100.0, hook = { bus, _, wf, out, _ ->
            if (abortWf < 0 && wf >= atWf) { abortWf = wf; act(bus, 0, out) }
        })
        println("$name: ${run.rec.events}")
        assertEquals(expected, run.rec.abort)
        assertEquals(true, run.rec.abortIncomingStarted)
        val w0 = run.rec.started.toInt()
        // the abort is seen on the block boundary after the action
        val a = abortWf.toInt()
        // until the action: the full mix
        assertEquals(0, diff(run.outL, run.outR, w0, w.audio.left, w.audio.right, 0, a).mismatches)
        // after the 20 ms fade: the incoming deck alone, exactly as the offline solo render, through its own hand-off
        val hand = handoffWindowFrame(w)
        val after = a + MixBusCore.DECLICK_FRAMES
        val solo = diff(run.outL, run.outR, w0 + after, soloIn.audio.left, soloIn.audio.right, after, hand - after)
        println(String.format("  incoming alone after the fade: %d mismatches over %.1f s", solo.mismatches, (hand - after) / sr.toDouble()))
        assertEquals(0, solo.mismatches, "$name: incoming disturbed by the abort (max ${solo.maxAbs})")
        // during the fade: the outgoing's share of the mix only ever shrinks, no step anywhere
        var worstStep = 0.0
        for (k in 0 until MixBusCore.DECLICK_FRAMES) {
            val i = a + k
            val share = abs(run.outL[w0 + i] - soloIn.audio.left[i]).toDouble()
            val full = abs(w.audio.left[i] - soloIn.audio.left[i]).toDouble()
            if (expected == MixBusCore.AbortReason.OUTGOING_PAUSED) {
                // a pause keeps the outgoing's audio: the fade is the full mix's outgoing share, linearly to 0
                val g = (MixBusCore.DECLICK_FRAMES - k - 1) / MixBusCore.DECLICK_FRAMES.toDouble()
                assertTrue(share <= full * g + 1e-5, "$name: fade frame $k: $share > ${full * g}")
            }
            if (k > 0) worstStep = max(worstStep, abs(run.outL[w0 + i] - run.outL[w0 + i - 1]).toDouble())
        }
        var srcStep = 0.0
        for (i in 1 until p.a.frames) srcStep = max(srcStep, abs(p.a.left[i] - p.a.left[i - 1]).toDouble())
        for (i in 1 until p.b.frames) srcStep = max(srcStep, abs(p.b.left[i] - p.b.left[i - 1]).toDouble())
        println(String.format("  largest sample step during the fade %.3f (sources %.3f)", worstStep, srcStep))
        assertTrue(worstStep <= 1.6 * srcStep)
        assertEquals(MixBusCore.State.IDLE, run.bus.state)
    }

    @Test
    fun pausingTheOutgoingMidTransitionFadesItAndKeepsTheIncoming() {
        checkAbortAfterT0("pause", { bus, deck, _ -> bus.pause(deck) }, MixBusCore.AbortReason.OUTGOING_PAUSED)
    }

    @Test
    fun seekingTheOutgoingMidTransitionFadesItAndKeepsTheIncoming() {
        // a seek: the player flushes its output and stops delivering the old stream
        checkAbortAfterT0("flush", { bus, deck, out -> out.stopped = true; bus.ring(deck).flush() }, MixBusCore.AbortReason.OUTGOING_FLUSHED)
    }

    @Test
    fun pausingTheOutgoingDuringThePreRollNeverStartsTheIncoming() {
        val p = beatMatchedPair()
        assertTrue(p.plan.preRollMs < -1000, "needs a tempo pre-roll: ${p.plan.preRollMs}")
        var acted = false
        val run = runBus(p, extraAfterDoneSec = 1.0, hook = { bus, _, wf, _, _ ->
            if (!acted && wf >= 100) { acted = true; bus.pause(0) }
        })
        println("pause in the pre-roll: ${run.rec.events}")
        assertEquals(MixBusCore.AbortReason.OUTGOING_PAUSED, run.rec.abort)
        assertEquals(false, run.rec.abortIncomingStarted)
        assertTrue(run.bus.isStandby(run.inDeck))
        // after the fade: silence, and the incoming deck consumed nothing
        val quietFrom = run.rec.started.toInt() + 2000 + MixBusCore.DECLICK_FRAMES
        for (i in quietFrom until run.frames) assertEquals(0f, run.outL[i], "sound after the abort at $i")
        assertEquals(0L, run.bus.deckConsumedFramesAt(run.inDeck, run.frames.toLong()))
    }

    @Test
    fun anIncomingLostBeforeItIsHeardCallsTheMixOffAndTheOutgoingPlaysOn() {
        // the build-av device log: Media3's stuck-playing detector failed the cued incoming player during the pre-roll;
        // the outgoing then followed the plan's fade into silence. It must go back to its own audio instead.
        val p = beatMatchedPair()
        assertTrue(p.plan.preRollMs < -1000, "needs a tempo pre-roll: ${p.plan.preRollMs}")
        var actedAt = -1L
        val run = runBus(p, extraAfterDoneSec = 3.0, hook = { bus, rec, wf, _, inc ->
            if (actedAt < 0 && wf >= 4800) { actedAt = rec.started + wf; inc.stopped = true; bus.ring(1).flush() }
        })
        println("incoming lost in the pre-roll: ${run.rec.events}")
        assertEquals(MixBusCore.AbortReason.INCOMING_INTERRUPTED, run.rec.abort)
        assertEquals(false, run.rec.abortIncomingStarted)
        assertEquals(-1L, run.rec.incomingAt, "the incoming must never start")
        assertEquals(MixBusCore.State.IDLE, run.bus.state)
        assertTrue(actedAt > 0)
        // after the fade out and in: the outgoing source on the copy path, bit for bit, and still moving
        val from = actedAt + 960 + 2L * MixBusCore.DECLICK_FRAMES + 960
        val src = run.bus.deckSourceFrameAt(run.outDeck, from)
        assertEquals(floor(src), src, "outgoing not back on the copy path")
        val n = (run.frames - from - 1).toInt()
        assertTrue(n > sr, "too little audio after the abort")
        val d = diff(run.outL, run.outR, from.toInt(), p.a.left, p.a.right, src.toInt(), n)
        assertEquals(0, d.mismatches, "outgoing not playing on after the incoming was lost (max ${d.maxAbs})")
        assertTrue(run.bus.deckSourceFrameAt(run.outDeck, run.frames.toLong() - 1) > src + sr / 2)
        // and the incoming deck is free again: it can be removed (no transition holds it)
        run.bus.removeDeck(run.inDeck)
    }

    // ---------------------------------------------------------------------------------------------
    // normal playback

    @Test
    fun normalPlaybackIsTheRingInputBitForBitAndStallsInsteadOfSkipping() {
        val (a, _) = stereoTrack(124f, MusicalKey(9, Mode.MINOR), 1, quantise16 = true)
        for (pcm16 in listOf(false, true)) {
            val bus = MixBusCore(sr)
            val d = bus.addDeck(ringCapacityFrames = sr) // a 1 s ring, so a starved player really runs it dry
            val rnd = Random(if (pcm16) 4 else 3)
            val start = 12_345L
            val pr = Producer(bus.ring(d), a, start, pcm16, rnd)
            bus.play(d)
            val total = 20 * sr
            val outL = FloatArray(total)
            val outR = FloatArray(total)
            val blk = FloatArray(1920)
            var f = 0
            var stalls = 0
            val v = DeckRing.View()
            var starvedUntil = -1
            var lastPos = Double.NaN
            pr.pump(v, { start }, 3000)
            while (f < total) {
                // now and then the player starves the deck for a while (network, decoder hiccup)
                if (starvedUntil < 0 && rnd.nextInt(80) == 0) starvedUntil = f + 3 * sr / 2
                if (f >= starvedUntil) {
                    starvedUntil = -1
                    pr.pump(v, { bus.deckSourceFrameAt(d, bus.renderedFrames).toLong() }, 3000)
                }
                val n = min(192 + rnd.nextInt(769), total - f)
                bus.render(blk, n)
                for (i in 0 until n) { outL[f + i] = blk[2 * i]; outR[f + i] = blk[2 * i + 1] }
                val pos = bus.deckSourceFrameAt(d, (f + n).toLong())
                if (pos == lastPos) stalls++
                lastPos = pos
                f += n
            }
            // remove the stalled (silent, position frozen) frames: what is left is the source, contiguous, bit for bit
            var src = start
            var mism = 0
            var played = 0
            for (i in 0 until total) {
                val s = bus.deckSourceFrameAt(d, i.toLong())
                val next = bus.deckSourceFrameAt(d, i + 1L)
                if (next == s || s.isNaN()) { assertEquals(0f, outL[i], "stalled frame not silent"); continue }
                assertEquals(src.toDouble(), s, 0.0, "position skipped at output $i")
                if (outL[i].toRawBits() != a.left[src.toInt()].toRawBits() || outR[i].toRawBits() != a.right[src.toInt()].toRawBits()) mism++
                assertEquals(src - start, bus.deckConsumedFramesAt(d, i.toLong()))
                src++
                played++
            }
            println("normal playback (${if (pcm16) "16-bit" else "float"} input): $played frames played, $stalls stalled blocks, ${bus.ring(d).underrunFrames} underrun frames, $mism mismatches")
            assertEquals(0, mism)
            assertTrue(stalls > 0, "the test never starved the deck")
            assertTrue(played > total / 2)
        }
    }

    @Test
    fun pauseAndResumeAreDeclickedAndThePositionCountsTheFade() {
        val (a, _) = stereoTrack(124f, MusicalKey(9, Mode.MINOR), 1)
        val bus = MixBusCore(sr)
        val d = bus.addDeck()
        val pr = Producer(bus.ring(d), a, 0L, false, Random(1))
        repeat(20) { pr.writeOnce(9000) }
        bus.play(d)
        val blk = FloatArray(1920)
        bus.render(blk, 480)
        bus.pause(d)
        bus.render(blk, 960)
        // the fade: 960 frames ramping down, then silence
        val k0 = 100
        assertEquals(a.left[480 + k0] * ((960 - k0 - 1) / 960f), blk[2 * k0], 1e-6f)
        bus.render(blk, 480)
        for (i in 0 until 960) assertEquals(0f, blk[i])
        assertEquals((480 + 960).toDouble(), bus.deckSourceFrameAt(d, bus.renderedFrames), 0.0)
        bus.play(d)
        bus.render(blk, 960)
        // resumed exactly where the fade left off, fading in
        val k = 500
        val g = (k + 1) / 960f
        assertEquals(a.left[480 + 960 + k] * g, blk[2 * k], 1e-6f)
        bus.render(blk, 480)
        assertEquals(a.left[480 + 960 + 960 + 10], blk[20], 0f)
    }

    // ---------------------------------------------------------------------------------------------
    // the voice: copy path <-> vocoder at rate 1

    @Test
    fun switchingBetweenCopyPathAndVocoderAtRateOneIsSeamless() {
        val n = 6 * sr
        val rnd = Random(2)
        val l = FloatArray(n) { (0.3 * sin(2 * PI * 220.0 * it / sr) + 0.2 * sin(2 * PI * 3170.0 * it / sr) + 0.1 * rnd.nextGaussian()).toFloat() }
        val r = FloatArray(n) { (0.3 * sin(2 * PI * 330.0 * it / sr + 1) + 0.1 * rnd.nextGaussian()).toFloat() }
        val source = object : DeckVoice.Source {
            override fun read(startFrame: Long, count: Int, dst: Array<FloatArray>) {
                for (i in 0 until count) {
                    val p = startFrame + i
                    dst[0][i] = if (p in 0 until n) l[p.toInt()] else 0f
                    dst[1][i] = if (p in 0 until n) r[p.toInt()] else 0f
                }
            }
        }
        val voice = DeckVoice(sr)
        val switchAt = 100_003L // an odd source frame
        val outL = FloatArray(4 * sr)
        val outR = FloatArray(4 * sr)
        voice.startDirect(source, switchAt - sr)
        voice.render(outL, outR, 0, sr)
        // a neutral program anchored at the switch frame, handing back to the copy path after 1.5 s
        val clock = DeckClock(ParamCurve.constant(1f), switchAt * 1000.0 / sr, -1L, 60_000L)
        val program = DeckVoice.Program(
            DeckPlan(), clock, startFrame = 0L, startWallMs = 0.0, endFrame = Long.MAX_VALUE, windowStartWallMs = 0.0,
            handoffFrame = (1.5 * sr).toLong(), sampleRate = sr,
        )
        voice.startProgram(program, source, 0L)
        var f = sr
        val rr = Random(5)
        while (f < 4 * sr) {
            val k = min(100 + rr.nextInt(900), 4 * sr - f)
            voice.render(outL, outR, f, k)
            f += k
        }
        val base = (switchAt - sr).toInt()
        var worstIn = 0.0
        var worstStep = 0.0
        val handAt = ((1.5 * sr).toInt() + 255) / 256 * 256 + sr // output index of the hand-off
        for (i in 0 until 4 * sr) {
            val e = max(abs(outL[i] - l[base + i]), abs(outR[i] - r[base + i])).toDouble()
            if (i < handAt + DeckVoice.HANDOFF_XFADE_FRAMES) worstIn = max(worstIn, e)
            else assertEquals(0.0, e, "copy path after the hand-off is not the source at $i")
        }
        for (i in sr - 2..sr + 2) worstStep = max(worstStep, abs((outL[i] - l[base + i]) - (outL[i - 1] - l[base + i - 1])).toDouble())
        println(String.format("copy -> vocoder -> copy at rate 1: max |out - source| while on the vocoder %.3g (%.1f dBFS)", worstIn, 20 * kotlin.math.log10(worstIn + 1e-30)))
        assertTrue(worstIn < 2e-5, "vocoder at rate 1 is not the source: $worstIn")
        assertTrue(worstStep < 2e-5)
    }

    @Test
    fun renderDoesNotAllocate() {
        val mx = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val me = Thread.currentThread().id
        val p = beatMatchedPair()
        var phase = "copy path"
        val bytes = HashMap<String, Long>()
        // a listener that allocates nothing itself
        val quiet = object : MixBusCore.Listener {}
        val bus = MixBusCore(sr, listener = quiet)
        val o = bus.addDeck(ringCapacityFrames = 120 * sr)
        val i = bus.addDeck(ringCapacityFrames = 120 * sr, standby = true)
        bus.arm(p.plan, o, i)
        val po = Producer(bus.ring(o), p.a, bus.armedSwitchFrame()!! - 10 * sr, false, Random(1))
        val pi = Producer(bus.ring(i), p.b, bus.armedEntryFrame()!! - 4800, true, Random(2))
        while (po.writeOnce(9600) > 0) { /* fill */ }
        while (pi.writeOnce(9600) > 0) { /* fill */ }
        bus.play(o); bus.play(i)
        val blk = FloatArray(1920)
        // warm up the copy path (class loading, JIT) for 2 s, then measure 5 s of it
        repeat(100) { bus.render(blk, 960) }
        var a0 = mx.getThreadAllocatedBytes(me)
        repeat(250) { bus.render(blk, 960) }
        bytes[phase] = mx.getThreadAllocatedBytes(me) - a0
        // through the switch and the whole transition
        phase = "transition"
        a0 = mx.getThreadAllocatedBytes(me)
        var guard = 0
        while (bus.state != MixBusCore.State.IDLE && guard++ < 10_000) bus.render(blk, 960)
        repeat(100) { bus.render(blk, 960) }
        bytes[phase] = mx.getThreadAllocatedBytes(me) - a0
        println("allocated while rendering: $bytes")
        assertTrue(bus.isStandby(o) && !bus.isStandby(i), "the transition did not run to its end")
        assertEquals(0L, bytes["copy path"])
        assertEquals(0L, bytes["transition"])
    }

    // ---------------------------------------------------------------------------------------------
    // cost

    @Test
    fun renderCostPerSecondOfAudio() {
        val p = beatMatchedPair()
        // one deck on the copy path
        run {
            val (a, _) = stereoTrack(124f, MusicalKey(9, Mode.MINOR), 1)
            var best = Double.MAX_VALUE
            repeat(5) {
                val bus = MixBusCore(sr)
                val d = bus.addDeck(ringCapacityFrames = 40 * sr)
                val pr = Producer(bus.ring(d), a, 0L, false, Random(1))
                while (pr.writeOnce(9600) > 0) { /* fill */ }
                bus.play(d)
                val blk = FloatArray(1920)
                val frames = 30 * sr
                val t0 = System.nanoTime()
                var f = 0
                while (f < frames) { bus.render(blk, 960); f += 960 }
                best = min(best, (System.nanoTime() - t0) / 1e6 / 30.0)
            }
            println(String.format("COST one deck, copy path: %.3f ms of CPU per second of audio (%.4f%% of real time)", best, best / 10.0))
        }
        // two decks in a transition, the vocoder on both: render only the overlap
        var best = Double.MAX_VALUE
        var secs = 0.0
        repeat(4) { rep ->
            val rec = Recorder()
            val bus = MixBusCore(sr, listener = rec)
            val o = bus.addDeck(ringCapacityFrames = 120 * sr)
            val i = bus.addDeck(ringCapacityFrames = 120 * sr, standby = true)
            bus.arm(p.plan, o, i)
            val po = Producer(bus.ring(o), p.a, bus.armedSwitchFrame()!! - 960, false, Random(1))
            val pi = Producer(bus.ring(i), p.b, bus.armedEntryFrame()!! - 4800, true, Random(2))
            while (po.writeOnce(9600) > 0) { /* fill */ }
            while (pi.writeOnce(9600) > 0) { /* fill */ }
            bus.play(o); bus.play(i)
            val blk = FloatArray(1920)
            while (rec.started < 0) bus.render(blk, 960)
            val t0 = System.nanoTime()
            var f = 0L
            while (bus.state == MixBusCore.State.RUNNING) { bus.render(blk, 960); f += 960 }
            val ms = (System.nanoTime() - t0) / 1e6
            secs = f / sr.toDouble()
            best = min(best, ms / secs)
            if (rep == 0) println("cost run: transition of ${"%.1f".format(secs)} s (${rec.events})")
        }
        println(String.format("COST two decks in transition (vocoder + filters on both): %.2f ms of CPU per second of audio (%.2f%% of real time), over %.1f s", best, best / 10.0, secs))
        assertTrue(best < 500.0, "transition rendering is far slower than real time: $best ms/s")
    }
}
