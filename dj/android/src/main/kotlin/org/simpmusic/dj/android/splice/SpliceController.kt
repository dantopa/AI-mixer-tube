package org.simpmusic.dj.android.splice

import org.simpmusic.dj.android.window.AbortResult
import org.simpmusic.dj.android.window.Deck
import org.simpmusic.dj.android.window.DjClock
import org.simpmusic.dj.android.window.LatencyCalibrator
import org.simpmusic.dj.android.window.TransitionEvent
import org.simpmusic.dj.android.window.WindowTimeline
import org.simpmusic.dj.android.window.WindowTuning
import kotlin.math.abs

/** What the [SpliceController] needs from the player adapter. Main thread. */
interface SpliceHost {
    /**
     * Starts the INCOMING track's player silently (volume 0) from [seekSourceMs] and returns it with its splice handle,
     * WITHOUT moving the queue: the outgoing track stays the current one. Null when that is not possible.
     */
    fun startIncoming(seekSourceMs: Long): Pair<Deck, SpliceHandle>?

    /**
     * Point of no return: flip the queue to the incoming track (UI, session, listener), adopting the incoming player
     * that is already playing. [uiPosition] gives the incoming track's position to show while the mix plays. False aborts.
     */
    fun commit(uiPosition: () -> Long?): Boolean

    /** Aborting before the commit: drop the incoming player started by [startIncoming]. */
    fun releaseIncoming()

    /** Ran to completion: the incoming player is alone, audible, at full volume, playing its own audio. */
    fun onFinished()

    /** Gave up by itself (state already cleaned up). */
    fun onFailed(reason: String, result: AbortResult)

    fun onEvent(event: TransitionEvent) {}
}

/**
 * Executes a rendered transition window over TWO decks, with every audio switch done inside the decks' own audio
 * processors ([SpliceEngine]) instead of between players:
 *
 * 1. Outgoing deck: captures a few seconds of its own audio, measures its decode skew against the window's lead-in
 *    ([Correlator]), and enters the window at [SpliceSchedule.spliceInMs] at an exact frame.
 * 2. Incoming deck: started silently, carries the window too; its POINTER (not its position) is moved until it emits
 *    the same window sample as the outgoing deck at the same moment. Pointer moves cost nothing while it is silent,
 *    unlike the seeks of the old lock, which re-armed Media3's position smoothing every time.
 * 3. Hand-off at [SpliceSchedule.handoffMs]: a short fader cross-fade between two decks playing identical samples.
 * 4. The incoming deck glides its pointer (<= [GLIDE_RATE] of read rate) onto the one that lines the window's tail up
 *    with its own audio (its decode skew measured like the outgoing one's) and joins its own audio at an exact frame.
 *
 * Positions reported by the players are only used for step 2, the one alignment between two audio outputs, and only
 * after the incoming deck's start transient has settled. Pure logic over [Deck], [SpliceHandle] and [SpliceHost]: unit
 * tested on the JVM. [tick] from the players' application thread every ~10 ms; [background] runs the correlations.
 */
class SpliceController(
    val timeline: WindowTimeline,
    val schedule: SpliceSchedule,
    private val window: WindowSamples,
    /** Our decode of the incoming track around where its deck will start (null: its skew is assumed 0). */
    private val incomingReference: ReferenceAudio?,
    private val outgoing: Deck,
    private val outgoingSplice: SpliceHandle,
    private val host: SpliceHost,
    private val clock: DjClock,
    private val userVolume: () -> Float,
    private val calibrator: LatencyCalibrator,
    private val log: (String) -> Unit = {},
    private val background: (() -> Unit) -> Unit = { it() },
) {
    enum class Phase { IDLE, OUT_CAPTURE, OUT_SPLICED, HANDOFF, CARRY, DONE, ABORTED }

    var phase = Phase.IDLE
        private set

    val isFinished: Boolean get() = phase == Phase.DONE || phase == Phase.ABORTED

    private var startedAt = 0.0
    private var phaseStart = 0.0
    private var committed = false

    /** Outgoing pointer: window time = outgoing source time + this. */
    private var kOut = -schedule.outgoingStartSourceMs

    @Volatile private var outSkew: Correlator.Result? = null

    @Volatile private var outSkewDone = false
    private var outSkewStarted = false

    @Volatile private var inSkew: Correlator.Result? = null

    @Volatile private var inSkewDone = false
    private var inSkewStarted = false

    private var incoming: Deck? = null
    private var incomingSplice: SpliceHandle? = null
    private var incomingCmd: SpliceCommand.Run? = null
    private var reseeks = 0
    private var goodReadings = 0

    /** Pointer the incoming deck was last (re)started for, and how: what its first settled measurement teaches. */
    private var aimedK = Double.NaN
    private var aimedBy: String? = null
    private var aimedSeekBias = Double.NaN

    /** This deck's seek lag as measured by its last re-seek in THIS mix (exact, unlike the smoothed calibrator). */
    private var measuredSeekLag = Double.NaN
    private var seekLagSum = 0.0
    private var seekLagCount = 0
    private var aligned = false
    private var lastError = Double.NaN

    /** The last fast reading of the incoming offset (NaN after a start or seek moved the deck). */
    private var lastCoarse = Double.NaN
    private var skewSeen = false
    private val steady = SteadyError(spanMs = COARSE_SPAN_MS)
    private val fine = SteadyError(spanMs = FINE_SPAN_MS)
    private var outStalledSince = Double.NaN

    /** For tests and logs: the outgoing deck's pointer, and the incoming deck's current command. */
    val outgoingPointerMs: Double get() = kOut
    val incomingCommand: SpliceCommand.Run? get() = incomingCmd

    private fun kJoin(): Double = schedule.incomingOffsetMs + (inSkew?.takeIf { it.trusted && it.confidence >= MIN_CONFIDENCE }?.deltaMs ?: 0.0)

    fun start() {
        check(phase == Phase.IDLE)
        val now = clock.nowMs()
        startedAt = now
        outgoingSplice.setCommand(SpliceCommand.Run(WindowMap(kOut), capture = true, spliceInAtMs = Double.POSITIVE_INFINITY))
        log("splice: start, $schedule; outgoing deck ${outgoingSplice.diagnostics}")
        setPhase(Phase.OUT_CAPTURE, now)
    }

    /** Window time the outgoing deck is at (its own audio before the splice is the same music at this pointer). */
    private fun outgoingWindowMs(): Double = outClock.positionAt(clock.nowMs(), outgoing) + kOut

    /**
     * The outgoing deck's position, smoothed: it plays steadily for the whole time it matters, so its reported position
     * minus the wall clock is a constant plus noise, and the median of the last second of that removes the device's
     * +-10-30 ms poll-to-poll noise from every aim (a single reading put each silent re-seek up to 15 ms off).
     */
    private val outClock = SmoothedClock()

    /** Incoming track position for the UI once it is the current track. */
    fun uiPositionMs(): Long? {
        if (!committed) return null
        val inc = incoming ?: return null
        val p = inc.positionMs()
        val w = incomingSplice?.windowTimeAt(p) ?: return p.toLong()
        return if (w < schedule.joinMs) timeline.incomingSourceOfWindow(w).toLong() else p.toLong()
    }

    fun tick() {
        if (isFinished || phase == Phase.IDLE) return
        try {
            val now = clock.nowMs()
            if (!committed) {
                if (outgoingSplice.isInterrupted) return fail("outgoing deck jumped (seek) during the mix")
                if (!outgoing.isPlaying) {
                    if (outStalledSince.isNaN()) outStalledSince = now
                    if (now - outStalledSince > OUTGOING_STALL_LIMIT_MS) return fail("outgoing stopped")
                } else {
                    outStalledSince = Double.NaN
                }
            }
            when (phase) {
                Phase.OUT_CAPTURE -> {
                    tickOutCapture(now)
                    tickIncoming(now)
                }
                Phase.OUT_SPLICED -> tickIncoming(now)
                Phase.HANDOFF -> tickHandoff(now)
                Phase.CARRY -> tickCarry(now)
                else -> Unit
            }
        } catch (e: Exception) {
            log("splice tick failed: $e")
            fail("exception: ${e.message}")
        }
    }

    // ---- outgoing ----

    private fun tickOutCapture(now: Double) {
        val out0 = schedule.outgoingStartSourceMs
        if (!outSkewStarted && outgoingSplice.processedMs >= out0 + CAPTURE_FROM_MS + CAPTURE_LEN_MS + 50) {
            outSkewStarted = true
            val cap = outgoingSplice.captureSlice(out0 + CAPTURE_FROM_MS, CAPTURE_LEN_MS)
            val ref = windowReference(CAPTURE_FROM_MS - REF_MARGIN_MS, CAPTURE_LEN_MS + 2 * REF_MARGIN_MS)
            if (cap == null) {
                log("splice: no capture of the outgoing deck (${outgoingSplice.diagnostics}), assuming no skew")
                outSkewDone = true
            } else {
                background {
                    outSkew = runCatching { Correlator.skewRobust(cap, ref) }.getOrNull()
                    outSkewDone = true
                }
            }
        }
        val w = outgoingWindowMs()
        if (!outSkewDone && w < schedule.spliceInMs - SKEW_DEADLINE_BEFORE_SPLICE_MS) return
        val r = outSkew
        val trusted = r != null && r.trusted && r.confidence >= MIN_CONFIDENCE
        val delta = if (trusted) r!!.deltaMs else 0.0
        kOut = delta - out0
        steady.reset()
        fine.reset()
        val spliceAt = schedule.spliceInMs - kOut
        if (outgoingSplice.processedMs > spliceAt - 30) return fail("too late to splice the outgoing deck")
        outgoingSplice.setCommand(
            SpliceCommand.Run(WindowMap(kOut), spliceInAtMs = spliceAt, xfadeMs = if (trusted) SPLICE_XFADE_MS else UNSURE_XFADE_MS),
        )
        log(
            "splice: outgoing skew ${r?.let { "%.2f ms (corr %.3f%s)".format(it.deltaMs, it.confidence, if (it.wide) ", wide search" else "") } ?: "unknown"}" +
                "${if (trusted) "" else " -> assumed 0"}; enters the window at source ${"%.1f".format(spliceAt)} ms",
        )
        host.onEvent(TransitionEvent.LockedOut(delta, 0, w))
        setPhase(Phase.OUT_SPLICED, now)
    }

    private fun windowReference(fromWindowMs: Double, lengthMs: Double): ReferenceAudio {
        val rate = window.sampleRate
        val f0 = (fromWindowMs * rate / 1000.0).toLong()
        val n = (lengthMs * rate / 1000.0).toInt()
        val s = FloatArray(n) { i -> (window.sample(f0 + i, 0) + window.sample(f0 + i, 1)) * (0.5f / 32768f) }
        return ReferenceAudio(rate, schedule.outgoingStartSourceMs + f0 * 1000.0 / rate, s)
    }

    // ---- incoming, before the hand-off ----

    private fun tickIncoming(now: Double) {
        val wOut = outgoingWindowMs()
        var inc = incoming
        if (inc == null) {
            if (wOut < schedule.incomingStartMs) return
            val k = kJoin()
            val target = (wOut + calibrator.startLatencyMs - k).coerceAtLeast(0.0)
            val started = host.startIncoming(target.toLong()) ?: return fail("incoming deck could not start")
            inc = started.first
            incoming = inc
            incomingSplice = started.second
            inc.volume = 0f
            setIncoming(SpliceCommand.Run(WindowMap(k), capture = true, spliceInAtMs = null, allowSeek = true))
            aimedK = k
            aimedBy = "start"
            log("splice: incoming deck started silently at source ${target.toLong()} ms (window ${"%.0f".format(wOut)})")
            return
        }
        val h = incomingSplice!!
        measureIncomingSkew(h)
        // The pointer the incoming deck needs to emit what the outgoing deck emits: window time out - position in. Both
        // advance at rate 1, so it is constant once the incoming deck's start transient has settled.
        val p = inc.positionMs()
        if (inc.isPlaying) {
            steady.add(now, wOut - p)
            fine.add(now, wOut - p)
        }
        // Two readings of the same offset: a fast one (300 ms median) for what a start or seek did, acted on only when it
        // is far off, and a slow one (1 s median) for the last few ms, because the device's positions read +-10-30 ms apart
        // poll to poll and a fast reading moved the pointer after noise (build aa: -4, +10, -7, +14 ms).
        var kCoarse = steady.result(now)
        if (kCoarse != null) lastCoarse = kCoarse
        // the incoming skew decides the join pointer, hence whether a re-seek is needed: the moment it is known, judge it
        // on the last reading instead of waiting for the next one (that wait is what left no time for the re-seek)
        if (!skewSeen && (inSkewDone || incomingReference == null)) {
            skewSeen = true
            if (kCoarse == null && aimedBy == null && !lastCoarse.isNaN()) kCoarse = lastCoarse
        }
        if (kCoarse != null) {
            // Where the deck landed against where it was aimed teaches this phone's start / seek latency (persisted), so
            // the next aim (a re-seek now, the start of the next mix) lands closer.
            when (aimedBy) {
                "start" -> calibrator.observeStart(kCoarse - aimedK)
                "seek" -> {
                    calibrator.observeSeek(aimedK - kCoarse)
                    // averaged over this mix's seeks: each landing reading carries a few ms of noise and the tail of
                    // the reporting transient, and replacing the estimate made consecutive seeks bounce around the truth
                    val lag = aimedSeekBias + (kCoarse - aimedK)
                    seekLagSum += lag
                    seekLagCount++
                    measuredSeekLag = seekLagSum / seekLagCount
                }
            }
            if (aimedBy != null) log("splice: incoming landed ${"%.1f".format(kCoarse - aimedK)} ms from its aim after the $aimedBy (calibrator $calibrator)")
            aimedBy = null
            val g = kJoin() - kCoarse
            val maxGlide = GLIDE_RATE * (schedule.joinMs - maxOf(wOut, schedule.handoffMs) - GLIDE_MARGIN_MS)
            val kCmd = incomingCmd!!.map.k0
            val skewKnown = inSkewDone || incomingReference == null
            // a re-seek costs a start transient: only worth it with time left to settle before the hand-off deadline
            val canReseek = reseeks < MAX_RESEEKS && wOut + RESEEK_COST_MS < handoffDeadline()
            when {
                // only when the glide would leave a real mismatch at the join: a seek lands +-its own lag uncertainty
                skewKnown && canReseek && abs(g) - maxGlide.coerceAtLeast(0.0) > RESEEK_WORTH_MS -> {
                    // too far for a glide: move the (silent) deck itself so the target pointer lines it up
                    val bias = if (measuredSeekLag.isNaN()) calibrator.seekBiasMs else measuredSeekLag
                    val target = (wOut + bias - kJoin()).coerceAtLeast(0.0)
                    aimedSeekBias = bias
                    reseeks++
                    setIncoming(SpliceCommand.Run(WindowMap(kJoin()), capture = true, spliceInAtMs = null, allowSeek = true))
                    inc.seekTo(target.toLong())
                    aimedK = kJoin()
                    aimedBy = "seek"
                    lastCoarse = Double.NaN
                    aligned = false
                    goodReadings = 0
                    fine.reset()
                    log("splice: incoming ${"%.1f".format(g)} ms from its join pointer, re-seeking it (silent) to ${target.toLong()} ms")
                }
                abs(kCmd - kCoarse) > COARSE_MOVE_MS -> {
                    setIncoming(incomingCmd!!.copy(map = WindowMap(kCoarse)))
                    aligned = false
                    goodReadings = 0
                    fine.reset()
                    log("splice: incoming pointer moved by ${"%.2f".format(kCoarse - kCmd)} ms (silent)")
                }
            }
            steady.reset()
        }
        val kFine = fine.result(now)
        if (kFine != null) {
            val kEmitted = h.windowTimeAt(p)?.minus(p)
            lastError = if (kEmitted != null) kEmitted - kFine else Double.NaN
            val kCmd = incomingCmd!!.map.k0
            // what this reading can tell apart from noise: at least the fixed bars, more when the positions scatter
            val noiseBar = NOISE_SIGMAS * fine.lastMedianSe
            if (abs(kCmd - kFine) > maxOf(MOVE_THRESHOLD_MS, noiseBar)) {
                setIncoming(incomingCmd!!.copy(map = WindowMap(kFine)))
                aligned = false
                goodReadings = 0
                log("splice: incoming pointer trimmed by ${"%.2f".format(kFine - kCmd)} ms (silent)")
            } else {
                // two settled readings in a row: the first one after a start or seek can still carry the end of
                // Media3's smoothing transient (a couple of ms that a single reading cannot tell from the truth)
                val good = !lastError.isNaN() && abs(lastError) <= maxOf(LOCK_TOLERANCE_MS, noiseBar)
                goodReadings = if (good) goodReadings + 1 else 0
                aligned = goodReadings >= 2
            }
            fine.reset()
        }
        val deadline = handoffDeadline()
        val ready = aligned && (inSkewDone || incomingReference == null)
        // never hand off while the speaker still plays frames from before the last pointer command (or a seek)
        val kEmitted = if (inc.isPlaying) h.windowTimeAt(p)?.minus(p) else null
        val settled = kEmitted != null && abs(kEmitted - incomingCmd!!.map.k0) < 0.5
        if (phase == Phase.OUT_SPLICED && wOut >= schedule.handoffMs && settled && (ready || wOut >= deadline)) handoff(now, forced = !ready)
    }

    private fun handoffDeadline(): Double = minOf(schedule.handoffMs + HANDOFF_LATE_MS, schedule.joinMs - MIN_GLIDE_AFTER_FORCED_MS)

    private fun measureIncomingSkew(h: SpliceHandle) {
        val ref = incomingReference ?: return
        if (inSkewStarted) return
        val processed = h.processedMs
        if (processed.isNaN() || processed < ref.startMs + IN_CAPTURE_LEN_MS + 200) return
        val from = maxOf(ref.startMs + REF_MARGIN_MS, processed - IN_CAPTURE_LEN_MS - 100)
        if (from + IN_CAPTURE_LEN_MS > ref.endMs - REF_MARGIN_MS) {
            inSkewStarted = true
            inSkewDone = true
            log("splice: incoming deck ran past the reference audio, assuming no skew")
            return
        }
        val cap = h.captureSlice(from, IN_CAPTURE_LEN_MS) ?: return
        inSkewStarted = true
        background {
            inSkew = runCatching { Correlator.skewRobust(cap, ref) }.getOrNull()
            inSkewDone = true
        }
    }

    private fun setIncoming(cmd: SpliceCommand.Run) {
        incomingCmd = cmd
        incomingSplice!!.setCommand(cmd)
    }

    private fun handoff(now: Double, forced: Boolean) {
        val h = incomingSplice!!
        val kCur = incomingCmd!!.map.k0
        val kJ = kJoin()
        val gFrom = h.processedMs + 50
        val gTo = schedule.joinMs - kJ - 300
        val g = kJ - kCur
        val kEnd =
            if (gTo - gFrom < 300) {
                kCur
            } else {
                val room = GLIDE_RATE * (gTo - gFrom)
                kCur + g.coerceIn(-room, room)
            }
        val joinAt = schedule.joinMs - kEnd
        val cmd =
            SpliceCommand.Run(
                map = WindowMap(kCur, kEnd, gFrom, gTo),
                spliceInAtMs = null,
                joinAtMs = joinAt,
                joinGain = schedule.joinGain,
                joinRampMs = WindowTuning.SETTLE_RAMP_MS.toDouble(),
                // a pointer the glide could not fully reach joins two copies a few ms apart: a longer fade turns that
                // into a short phasing instead of a skipped / repeated sliver
                xfadeMs = if (abs(kEnd - kJ) > 1.0) JOIN_XFADE_UNSURE_MS else JOIN_XFADE_MS,
            )
        setIncoming(cmd)
        val s = inSkew
        log(
            "splice: HAND-OFF ${if (forced) "forced " else ""}at window ${"%.0f".format(outgoingWindowMs())}: pointer error ${"%.2f".format(lastError)} ms, " +
                "incoming skew ${s?.let { "%.2f ms (corr %.3f%s%s)".format(it.deltaMs, it.confidence, if (it.wide) ", wide search" else "", if (it.trusted && it.confidence >= MIN_CONFIDENCE) "" else ", not trusted") } ?: "unknown"}, glide ${"%.1f".format(kEnd - kCur)} of ${"%.1f".format(g)} ms" +
                (if (abs(kEnd - kJ) > 0.5) ", ${"%.1f".format(kJ - kEnd)} ms left at the join" else "") + ", joins its own audio at source ${"%.1f".format(joinAt)} ms; incoming deck ${h.diagnostics}",
        )
        if (!host.commit { uiPositionMs() }) return fail("host refused the commit")
        committed = true
        host.onEvent(TransitionEvent.Committed(outgoingWindowMs()))
        setPhase(Phase.HANDOFF, now)
    }

    // ---- after the hand-off ----

    private fun tickHandoff(now: Double) {
        val inc = incoming!!
        val x = ((now - phaseStart) / WindowTuning.XFADE_MS).coerceIn(0.0, 1.0)
        val vol = userVolume()
        outgoing.volume = (vol * (1.0 - x)).toFloat()
        inc.volume = (vol * x).toFloat()
        if (x >= 1.0) {
            outgoing.pause()
            outgoingSplice.setCommand(SpliceCommand.Passthrough)
            host.onEvent(TransitionEvent.Handoff("out->in (spliced)", lastError, forced = !aligned))
            setPhase(Phase.CARRY, now)
        }
    }

    private fun tickCarry(now: Double) {
        val inc = incoming!!
        val h = incomingSplice!!
        inc.volume = userVolume()
        if (h.isInterrupted) return fail("incoming deck jumped (seek) during the mix")
        val end = incomingCmd?.endMs ?: return
        if (h.processedMs >= end + 50 && inc.positionMs() >= end) {
            h.setCommand(SpliceCommand.Passthrough)
            log("splice: joined its own audio, mix done")
            host.onEvent(TransitionEvent.Handoff("window->own audio (spliced)", 0.0, forced = false))
            setPhase(Phase.DONE, now)
            host.onFinished()
        }
    }

    // ---- abort ----

    /** Cancels now and leaves the decks in a state the host can continue from. Idempotent, never throws. */
    fun abort(): AbortResult {
        if (phase == Phase.DONE) return AbortResult(true, incoming?.positionMs()?.toLong(), true)
        if (phase == Phase.ABORTED) return AbortResult(committed, incoming?.positionMs()?.toLong(), incoming?.isPlaying == true)
        val vol = userVolume()
        var result = AbortResult(false, null, false)
        try {
            if (committed) {
                val inc = incoming!!
                incomingSplice?.setCommand(SpliceCommand.Passthrough)
                inc.volume = vol
                outgoing.pause()
                outgoingSplice.setCommand(SpliceCommand.Passthrough)
                result = AbortResult(true, inc.positionMs().toLong(), inc.isPlaying)
            } else {
                outgoingSplice.setCommand(SpliceCommand.Passthrough)
                outgoing.volume = vol
                if (incoming != null) {
                    incomingSplice?.setCommand(SpliceCommand.Passthrough)
                    host.releaseIncoming()
                }
            }
        } catch (e: Exception) {
            log("splice abort cleanup failed: $e")
        }
        setPhase(Phase.ABORTED, clock.nowMs())
        return result
    }

    private fun fail(reason: String) {
        log("splice: giving up: $reason")
        val r = abort()
        host.onFailed(reason, r)
    }

    private fun setPhase(p: Phase, now: Double) {
        log("splice phase $phase -> $p")
        phase = p
        phaseStart = now
    }

    companion object {
        /** Window time (lead-in, plain outgoing audio) captured to measure the outgoing deck's skew. */
        const val CAPTURE_FROM_MS = 4000.0
        const val CAPTURE_LEN_MS = 2500.0
        const val IN_CAPTURE_LEN_MS = 1500.0
        const val REF_MARGIN_MS = 250.0
        const val MIN_CONFIDENCE = 0.8

        /** Without a skew by this long before the splice, enter with an assumed 0 and a longer fade. */
        const val SKEW_DEADLINE_BEFORE_SPLICE_MS = 2500.0
        const val SPLICE_XFADE_MS = 8.0
        const val UNSURE_XFADE_MS = 40.0
        const val JOIN_XFADE_MS = 8.0
        const val JOIN_XFADE_UNSURE_MS = 40.0

        /** Largest pointer glide while audible, as a fraction of read rate (0.006 = 10 cents). */
        const val GLIDE_RATE = 0.006
        const val GLIDE_MARGIN_MS = 800.0
        const val MAX_RESEEKS = 3

        /** Incoming pointer considered on the outgoing one within this (ms): a 100 ms fade between two copies this close is clean. */
        const val LOCK_TOLERANCE_MS = 2.0

        /**
         * Median window over the two decks' reported positions. The device reads them +-10-30 ms apart from one poll to the
         * next (build aa's log: pointer moves of -134, +54, -4, +10, -7, +14 ms), so a 300 ms median chased noise.
         */
        const val FINE_SPAN_MS = 1000.0

        /** Window of the fast reading: long enough to tell a start transient's slope from the device's position noise. */
        const val COARSE_SPAN_MS = 600.0

        /** A fine reading moves or confirms the pointer only beyond this many standard errors of its median. */
        const val NOISE_SIGMAS = 2.5

        /** A fast reading further than this from the pointer is a real landing error (a start or seek), not noise. */
        const val COARSE_MOVE_MS = 15.0

        /** Pointer corrections smaller than this are measurement noise (a 1 s median of the device's positions). */
        const val MOVE_THRESHOLD_MS = 2.0

        /** Join mismatch (ms) left by an incomplete glide below which a re-seek is not worth its risk. */
        const val RESEEK_WORTH_MS = 6.0

        /** A re-seek's price: the seek plus the new start transient settling. */
        const val RESEEK_COST_MS = 2800.0

        /** How long past the planned hand-off a not-yet-aligned incoming deck is waited for. */
        const val HANDOFF_LATE_MS = 6000.0
        const val MIN_GLIDE_AFTER_FORCED_MS = 1500.0
        const val OUTGOING_STALL_LIMIT_MS = 400.0
    }
}

/**
 * The offset between two decks, read from their reported positions, accepted only once it has stopped moving (Media3
 * smooths a freshly started deck's position for up to a second, which reads as a drifting offset). Median of the last
 * [spanMs] of samples, when their least-squares slope is under [maxSlope] ms/ms.
 */
class SteadyError(
    private val spanMs: Double = 300.0,
    private val maxSlope: Double = 0.0025,
    private val maxWaitMs: Double = 3500.0,
) {
    private val t = ArrayDeque<Double>()
    private val e = ArrayDeque<Double>()
    private var since = Double.NaN

    private companion object {
        const val SLOPE_SIGMAS = 3.0
    }

    fun reset() {
        t.clear()
        e.clear()
        since = Double.NaN
    }

    fun add(now: Double, error: Double) {
        if (since.isNaN()) since = now
        t.addLast(now)
        e.addLast(error)
        while (t.isNotEmpty() && now - t.first() > spanMs) {
            t.removeFirst()
            e.removeFirst()
        }
    }

    /** The settled error, or null while it is still moving (or too few samples). */
    fun result(now: Double): Double? {
        if (t.size < 8 || now - t.first() < spanMs * 0.8) return null
        val n = t.size
        val mt = t.average()
        val me = e.average()
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            val dt = t[i] - mt
            num += dt * (e[i] - me)
            den += dt * dt
        }
        val slope = if (den > 0) num / den else 0.0
        // How well the slope is known from these readings: on the device positions read +-10-30 ms apart poll to poll, and
        // a fixed bar then either never passes or passes on noise in the middle of a start transient (the build-aa
        // simulation with that noise measured landings 50-130 ms off). A slope is "moving" only when it is clearly more than
        // its own standard error.
        var rss = 0.0
        for (i in 0 until n) {
            val r = e[i] - (me + slope * (t[i] - mt))
            rss += r * r
        }
        val se = if (den > 0 && n > 2) kotlin.math.sqrt(rss / (n - 2) / den) else 0.0
        if (abs(slope) > maxSlope + SLOPE_SIGMAS * se && now - since < maxWaitMs) return null
        // standard error of a median of n readings with this scatter (1.2533 = sqrt(pi / 2))
        lastMedianSe = 1.2533 * kotlin.math.sqrt(rss / (n - 2).coerceAtLeast(1)) / kotlin.math.sqrt(n.toDouble())
        val sorted = e.sorted()
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }

    /** How uncertain the last [result] is (ms), from the scatter of the readings it was taken over. */
    var lastMedianSe = 0.0
        private set
}

/** Median of (reported position - wall clock) over the last [spanMs] of a steadily playing deck; see [positionAt]. */
class SmoothedClock(private val spanMs: Double = 1000.0) {
    private val t = ArrayDeque<Double>()
    private val d = ArrayDeque<Double>()

    /** Smoothed position of [deck] at [now]; falls back to the raw reading (and forgets) while the deck is not playing. */
    fun positionAt(now: Double, deck: Deck): Double {
        if (!deck.isPlaying) {
            t.clear()
            d.clear()
            return deck.positionMs()
        }
        if (t.isEmpty() || t.last() != now) {
            t.addLast(now)
            d.addLast(deck.positionMs() - now)
            while (now - t.first() > spanMs) {
                t.removeFirst()
                d.removeFirst()
            }
        }
        val sorted = d.sorted()
        val n = sorted.size
        val med = if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
        return now + med
    }
}
