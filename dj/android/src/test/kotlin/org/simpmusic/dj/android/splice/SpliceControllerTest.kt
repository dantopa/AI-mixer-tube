package org.simpmusic.dj.android.splice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.simpmusic.dj.android.fakePlan
import org.simpmusic.dj.android.window.AbortResult
import org.simpmusic.dj.android.window.Deck
import org.simpmusic.dj.android.window.FakeClock
import org.simpmusic.dj.android.window.FakeDeck
import org.simpmusic.dj.android.window.LatencyCalibrator
import org.simpmusic.dj.android.window.WindowTimeline
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * End-to-end simulation of a spliced transition over two [FakeDeck]s with Media3-like start transients, random start
 * and seek latencies and per-track decode skews. Ground truth is checked, not what the controller believes:
 * - the outgoing deck enters the window at the pointer its true skew requires;
 * - at the hand-off both decks emit the same window sample at the same true moment;
 * - the incoming deck ends on the pointer that lines the window's tail up with its own audio.
 */
class SpliceControllerTest {
    private val rate = 48_000

    /** A track's "music", defined per 48 kHz sample index of OUR decode: low-passed deterministic noise. */
    private fun music(seed: Long): (Long) -> Float = { n ->
        var acc = 0f
        for (j in 0 until 4) {
            var h = (n - j) * 6364136223846793005L + seed * 1442695040888963407L
            h = h xor (h ushr 29)
            h *= -0x40a7b892e31b1a47L
            h = h xor (h ushr 32)
            acc += ((h and 0xffff).toFloat() / 32768f - 1f)
        }
        acc / 4f
    }

    /** Seeks are what interrupt a splice: the handle must hear about them. */
    private class SeekWatch(private val inner: FakeDeck, private val onSeek: () -> Unit) : Deck by inner {
        override fun seekTo(ms: Long) {
            onSeek()
            inner.seekTo(ms)
        }
    }

    /**
     * Engine model: processes [leadMs] ahead of the speaker, records commands from there, captures the deck's audio,
     * which is its track's music shifted by its decode skew [skewMs] (`own(t) = ours(t + skew)`).
     */
    private class FakeHandle(
        private val deck: FakeDeck,
        private val leadMs: Double,
        private val signal: (Long) -> Float,
        private val skewMs: Double,
    ) : SpliceHandle {
        val history = CommandHistory()
        private var cmd: SpliceCommand = SpliceCommand.Passthrough
        override val isSupported = true
        override var isInterrupted = false

        override val processedMs: Double get() = deck.truePositionMs + if (deck.isPlaying) leadMs else 0.0

        override fun setCommand(cmd: SpliceCommand) {
            this.cmd = cmd
            isInterrupted = false
            history.record(processedMs, cmd)
        }

        fun onSeek() {
            val c = cmd
            if (c is SpliceCommand.Run && !c.allowSeek) isInterrupted = true
        }

        override fun windowTimeAt(sourceMs: Double): Double? = history.windowTimeAt(sourceMs)

        override fun captureSlice(fromMs: Double, lengthMs: Double): CapturedAudio? {
            if (fromMs + lengthMs > processedMs) return null
            val n = (lengthMs * 48).toInt()
            return CapturedAudio(48_000, fromMs, FloatArray(n) { i -> signal(((fromMs + i / 48.0 + skewMs) * 48).roundToLong()) })
        }
    }

    private class Window(private val outStart: Double, private val signal: (Long) -> Float) : WindowSamples {
        override val sampleRate = 48_000
        override val frames = 48_000L * 120

        // only the lead-in matters to the controller: the plain outgoing track at our decode's timing
        override fun sample(frame: Long, ch: Int): Int = (signal(Math.round(outStart * 48) + frame) * 32767).toInt()
    }

    private class Outcome(
        val failed: String?,
        val spliceErrorMs: Double,
        val handoffErrorMs: Double,
        val joinErrorMs: Double,
        val committed: Boolean,
    )

    private fun simulate(
        seed: Int,
        timeline: WindowTimeline,
        outSkew: Double,
        inSkew: Double,
        calibrator: LatencyCalibrator = LatencyCalibrator(),
        verbose: Boolean = false,
        jitterMs: Double = 2.0,
    ): Outcome {
        val rng = Random(seed)
        val schedule = (SpliceSchedule.plan(timeline, 240_000) as SpliceSchedule.Companion.Outcome.Ok).schedule
        val clock = FakeClock()
        val outMusic = music(11L + seed)
        val inMusic = music(97L + seed)
        val outStart = schedule.outgoingStartSourceMs
        val outDeck = FakeDeck("out", clock, outStart - 50.0, startPlaying = true, jitterMs = jitterMs, seed = seed)
        val outHandle = FakeHandle(outDeck, leadMs = 250.0 + rng.nextDouble() * 400, signal = outMusic, skewMs = outSkew)
        val outWatched = SeekWatch(outDeck) { outHandle.onSeek() }
        var inDeck: FakeDeck? = null
        var inHandle: FakeHandle? = null
        var committed = false
        var failed: String? = null
        var finished = false
        var handoffError = Double.NaN
        val host =
            object : SpliceHost {
                override fun startIncoming(seekSourceMs: Long): Pair<Deck, SpliceHandle> {
                    val d =
                        FakeDeck(
                            "in", clock, seekSourceMs.toDouble(),
                            startLatencyMs = 80.0 + rng.nextDouble() * 250,
                            seekLatencyMs = 120.0 + rng.nextDouble() * 150,
                            jitterMs = jitterMs, seed = seed + 7,
                            smoothMinMs = 30.0, smoothMaxMs = 160.0,
                        )
                    d.play()
                    val h = FakeHandle(d, leadMs = 250.0 + rng.nextDouble() * 400, signal = inMusic, skewMs = inSkew)
                    inDeck = d
                    inHandle = h
                    return SeekWatch(d) { h.onSeek() } to h
                }

                override fun commit(uiPosition: () -> Long?): Boolean {
                    committed = true
                    // ground truth at the hand-off: what each deck emits at the speaker right now
                    val wOut = outHandle.history.windowTimeAt(outDeck.truePositionMs)!!
                    val wIn = inHandle!!.history.windowTimeAt(inDeck!!.truePositionMs)!!
                    handoffError = wIn - wOut
                    return true
                }

                override fun releaseIncoming() {}

                override fun onFinished() {
                    finished = true
                }

                override fun onFailed(reason: String, result: AbortResult) {
                    failed = reason
                }
            }
        val incomingRef =
            ReferenceAudio(48_000, schedule.incomingStartSourceMs - 300, FloatArray(7000 * 48) { i -> inMusic(((schedule.incomingStartSourceMs - 300) * 48).roundToLong() + i) })
        var ctl: SpliceController? = null
        val c =
            SpliceController(
                timeline = timeline,
                schedule = schedule,
                window = Window(outStart, outMusic),
                incomingReference = incomingRef,
                outgoing = outWatched,
                outgoingSplice = outHandle,
                host = host,
                clock = clock,
                userVolume = { 1f },
                calibrator = calibrator,
                log = {
                    if (verbose) {
                        val d = inDeck
                        val truth = if (d != null && d.isPlaying) " [true offset %.1f, in report err %.1f]".format(outDeck.truePositionMs + ctl!!.outgoingPointerMs - d.truePositionMs, d.reportingErrorMs()) else ""
                        println("%.0f ".format(clock.now) + it + truth)
                    }
                },
            )
        ctl = c
        c.start()
        val end = clock.now + timeline.windowMs + 5000
        while (clock.now < end && !c.isFinished) {
            clock.now += 10.0
            c.tick()
        }
        val trueKOut = outSkew - outStart
        val finalK = (c.incomingCommand?.map?.k1 ?: Double.NaN)
        val trueKJoin = schedule.incomingOffsetMs + inSkew
        return Outcome(
            failed = failed ?: if (!finished) "did not finish (phase ${c.phase})" else null,
            spliceErrorMs = c.outgoingPointerMs - trueKOut,
            handoffErrorMs = handoffError,
            joinErrorMs = finalK - trueKJoin,
            committed = committed,
        )
    }

    private val electronicPlan = WindowTimeline.build(fakePlan(exit = 180_000, entry = 16_000, overlapMs = 16_000, rampBackMs = 20_000))

    // entry near the incoming track's start, short overlap: the tight case (as on the cumbia radio)
    private val earlyEntryPlan = WindowTimeline.build(fakePlan(exit = 150_000, entry = 1000, overlapMs = 11_000, rampBackMs = 4000, inRate = 1.01f))

    @Test
    fun forty_seeds_both_plans_are_spliced_aligned_and_joined_exactly() {
        var worstHandoff = 0.0
        var worstJoin = 0.0
        var worstSplice = 0.0
        for (plan in listOf(electronicPlan, earlyEntryPlan)) {
            for (seed in 1..40) {
                val rng = Random(seed * 31)
                val o = simulate(seed, plan, outSkew = rng.nextDouble(-8.0, 8.0), inSkew = rng.nextDouble(-8.0, 8.0))
                println("seed $seed ${if (plan === electronicPlan) "electronic" else "early"}: hand-off ${"%.2f".format(o.handoffErrorMs)} join ${"%.3f".format(o.joinErrorMs)}")
                if (abs(o.handoffErrorMs) > 3 || abs(o.joinErrorMs) > 8.0 || o.failed != null) {
                    println("BAD seed $seed: handoff ${o.handoffErrorMs} join ${o.joinErrorMs} failed ${o.failed}")
                    simulate(seed, plan, outSkew = Random(seed * 31).nextDouble(-8.0, 8.0), inSkew = Random(seed * 31).let { it.nextDouble(); it.nextDouble(-8.0, 8.0) }, verbose = true)
                }
                assertNull("seed $seed: ${o.failed}", o.failed)
                worstSplice = maxOf(worstSplice, abs(o.spliceErrorMs))
                worstHandoff = maxOf(worstHandoff, abs(o.handoffErrorMs))
                worstJoin = maxOf(worstJoin, abs(o.joinErrorMs))
            }
        }
        println("splice worst: outgoing entry ${"%.3f".format(worstSplice)} ms, hand-off ${"%.2f".format(worstHandoff)} ms, join ${"%.3f".format(worstJoin)} ms")
        assertTrue("outgoing entry $worstSplice ms", worstSplice < 0.05)
        assertTrue("hand-off $worstHandoff ms", worstHandoff < 3.0)
        // by design a pointer the glide cannot fully reach is left (up to RESEEK_WORTH_MS plus what the hand-off slips)
        // rather than gambling on a re-seek; it is joined with a 40 ms fade. Typical joins are exact (see the log).
        assertTrue("join $worstJoin ms", worstJoin < 8.0)
    }

    @Test
    fun a_badly_calibrated_start_latency_is_absorbed_by_a_silent_reseek() {
        val cal = LatencyCalibrator().apply { restore(550.0, 120.0, 120.0) } // far from the decks' real 80-330 ms
        for (seed in 1..20) {
            val o = simulate(seed, earlyEntryPlan, outSkew = 2.0, inSkew = -3.0, calibrator = cal)
            assertNull("seed $seed: ${o.failed}", o.failed)
            // a start latency off by ~300 ms costs re-seeks and leaves less time to settle: a few ms more allowed
            assertTrue("seed $seed hand-off ${o.handoffErrorMs}", abs(o.handoffErrorMs) < 6.0)
            assertTrue("seed $seed join ${o.joinErrorMs}", abs(o.joinErrorMs) < 8.0)
        }
    }

    @Test
    fun device_like_position_noise_and_skews_beyond_the_narrow_search_still_land_close() {
        // build aa's device log: positions read +-10-30 ms apart poll to poll, and decks whose time base came from a seek
        // carried ~50-60 ms of skew, at the edge of the old +-60 ms search
        var worstHandoff = 0.0
        var worstJoin = 0.0
        for (seed in 1..30) {
            val rng = Random(seed * 17)
            val outSkew = (if (rng.nextBoolean()) 1 else -1) * rng.nextDouble(45.0, 140.0)
            val inSkew = (if (rng.nextBoolean()) 1 else -1) * rng.nextDouble(45.0, 140.0)
            val o = simulate(seed, earlyEntryPlan, outSkew = outSkew, inSkew = inSkew, jitterMs = 15.0)
            if (abs(o.joinErrorMs) > 8 || abs(o.handoffErrorMs) > 8) {
                println("BAD noisy seed $seed (out skew ${"%.1f".format(outSkew)}, in skew ${"%.1f".format(inSkew)}): hand-off ${o.handoffErrorMs} join ${o.joinErrorMs}")
                simulate(seed, earlyEntryPlan, outSkew = outSkew, inSkew = inSkew, jitterMs = 15.0, verbose = true)
            }
            assertNull("seed $seed: ${o.failed}", o.failed)
            assertTrue("seed $seed outgoing entry ${o.spliceErrorMs} (skew $outSkew)", abs(o.spliceErrorMs) < 0.05)
            worstHandoff = maxOf(worstHandoff, abs(o.handoffErrorMs))
            worstJoin = maxOf(worstJoin, abs(o.joinErrorMs))
        }
        println("device-like noise: hand-off worst ${"%.2f".format(worstHandoff)} ms, join worst ${"%.2f".format(worstJoin)} ms")
        // measured: hand-off worst 3.9 ms; joins exact in most seeds, worst 11 ms (a 60 ms incoming skew found late, after
        // the silent re-seeks had used their time). Before build ab these skews were beyond the search and assumed 0, i.e.
        // the join missed by the whole skew (45-140 ms).
        assertTrue("hand-off $worstHandoff ms", worstHandoff < 6.0)
        assertTrue("join $worstJoin ms", worstJoin < 15.0)
    }

    @Test
    fun the_schedule_rejects_an_incoming_track_entered_at_its_very_start() {
        val tl = WindowTimeline.build(fakePlan(exit = 150_000, entry = 0, overlapMs = 4000, rampBackMs = 2000))
        val r = SpliceSchedule.plan(tl, 240_000)
        assertTrue(r is SpliceSchedule.Companion.Outcome.Rejected)
        assertEquals(true, (r as SpliceSchedule.Companion.Outcome.Rejected).reason.isNotEmpty())
    }
}
