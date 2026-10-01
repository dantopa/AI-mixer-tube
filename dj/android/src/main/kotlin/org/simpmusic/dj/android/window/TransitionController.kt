package org.simpmusic.dj.android.window

import kotlin.math.abs

/** What the controller needs from the player adapter. */
interface TransitionHost {
    /**
     * The point of no return, reached once the window is phase-locked to the live outgoing deck. The host
     * flips the queue to the incoming track (UI, MediaSession metadata, listener) and hands back the LIVE
     * incoming deck, prepared and PAUSED. Returning null aborts the transition instead.
     *
     * [seekSourceMs] is where the live incoming deck must be parked: the source position it has to hold when
     * it starts (silently) to take over from the window.
     */
    fun commitToIncoming(seekSourceMs: Long): Deck?

    /** The transition ran to completion: live incoming deck is audible alone at full volume. */
    fun onFinished()

    /** The controller gave up or failed by itself (not through [TransitionController.abort]); state is already cleaned up. */
    fun onFailed(reason: String, result: AbortResult)

    /**
     * The window is about to be paused and released (the live incoming deck carries the audio alone from here):
     * the host must move anything that still points at the window player (MediaSession delegate) to the incoming
     * player NOW, or it would watch a released player for the rest of the settle ramp.
     */
    fun onWindowRetired() {}

    /** Structured record of every hand-off for logs / the debug line. */
    fun onEvent(event: TransitionEvent) {}
}

data class AbortResult(
    /** True when the queue already moved on to the incoming track (host must keep the incoming deck as current). */
    val committed: Boolean,
    /** Where the incoming track is right now, on ITS source timeline; valid when [committed]. */
    val incomingPositionMs: Long?,
    /** True when the live incoming deck is already playing (no seek / start needed by the host). */
    val incomingPlaying: Boolean,
)

sealed interface TransitionEvent {
    data class LockedOut(val errorMs: Double, val seeks: Int, val windowMs: Double) : TransitionEvent

    data class LockedIn(val errorMs: Double, val seeks: Int, val forced: Boolean) : TransitionEvent

    data class Committed(val windowMs: Double) : TransitionEvent

    data class Handoff(val name: String, val residualMs: Double, val forced: Boolean) : TransitionEvent
}

/**
 * Executes a rendered transition window over three physical decks:
 *
 * ```
 *  live outgoing  ==== plain audio ====\ (100 ms linear cross-fade, locked)
 *  window          silent, running, locked ->/==== beat-matched mix ====\ (100 ms)
 *  live incoming                                        silent, locked ->/==== plain audio ===>
 * ```
 *
 * Every hand-off happens between decks playing IDENTICAL audio (the window was rendered from the same
 * decoded source) so a short linear cross-fade (gain sum = 1, correlated content) is seamless PROVIDED
 * they are aligned; alignment is measured and corrected while the follower is still silent
 * ([AlignmentLoop]). Beat alignment INSIDE the mix is not this class's business at all: it is baked into the
 * window's samples, so it is exact by construction.
 *
 * Single-threaded: [tick] must be called from the players' application thread every ~10-20 ms until
 * [isFinished]. Pure logic over [Deck]s, [DjClock] and [TransitionHost]; unit tested on the JVM.
 */
class TransitionController(
    val timeline: WindowTimeline,
    private val outgoing: Deck,
    private val window: Deck,
    private val host: TransitionHost,
    private val clock: DjClock,
    private val userVolume: () -> Float,
    private val calibrator: LatencyCalibrator = LatencyCalibrator(),
    private val log: (String) -> Unit = {},
    /**
     * Constant offset between where OUR decode (MediaCodecPcmDecoder, the source of the window) places a sample
     * and where ExoPlayer's timeline places the same sample: `ourPosition - exoPosition`, in ms. Container
     * pre-skip / encoder-delay trimming may differ slightly between the two decode paths; on a device it can be
     * measured once per codec by cross-correlating an ExoPlayer audio-processor tap against our decode. Unknown
     * here, so 0 (see dj/docs/android.md, "not verified"). Positive = our timeline is ahead of ExoPlayer's.
     */
    private val decodeSkewMs: Double = 0.0,
) {
    enum class Phase { IDLE, LOCK_OUT, XFADE_OUT, WINDOW, LOCK_IN, XFADE_IN, SETTLE, DONE, ABORTED }

    var phase: Phase = Phase.IDLE
        private set

    val isFinished: Boolean get() = phase == Phase.DONE || phase == Phase.ABORTED

    private var incoming: Deck? = null
    private var incomingParked = false
    private var incomingStartIssued = false

    private val outEst = PositionEstimator()
    private val winEst = PositionEstimator()
    private val inEst = PositionEstimator()

    private var align: AlignmentLoop? = null
    private var startedAtMs = 0.0
    private var phaseStartMs = 0.0
    private var startIssuedWindowMs = Double.NaN

    /** Gain baked into the window's incoming at the end of the lanes (loudness match). */
    private val endGain: Float = timeline.plan.incoming.volume.valueAt(timeline.settledRelMs.toDouble())

    // ---- lifecycle ----

    /** Starts the window (silently) alongside the live outgoing deck. The window must be prepared at position 0. */
    fun start() {
        check(phase == Phase.IDLE) { "already started" }
        val now = clock.nowMs()
        startedAtMs = now
        window.volume = 0f
        window.play()
        winEst.reset()
        outEst.reset()
        align = AlignmentLoop(calibrator, target = SeekTarget.WINDOW, log = log).also { it.start(now) }
        setPhase(Phase.LOCK_OUT, now)
    }

    /** Where the window's clock is (ms since its first sample), from the smoothed position signal. */
    fun windowPositionMs(): Double {
        val now = clock.nowMs()
        return if (window.isPlaying && winEst.hasEstimate) winEst.estimate(now, window.positionMs()) else window.positionMs()
    }

    /**
     * Position to show the UI / MediaSession on the INCOMING track's timeline, or null before the incoming
     * became the current track. Frozen at the entry point until T0; afterwards follows the mapped window,
     * then the live incoming deck.
     */
    fun uiPositionMs(): Long? {
        return when (phase) {
            Phase.IDLE, Phase.LOCK_OUT, Phase.ABORTED -> null
            Phase.XFADE_OUT, Phase.WINDOW, Phase.LOCK_IN -> mappedIncomingPosition()
            Phase.XFADE_IN, Phase.SETTLE, Phase.DONE -> incoming?.positionMs()?.toLong() ?: mappedIncomingPosition()
        }
    }

    /** Position of the OUTGOING track for the UI while it is still the current one (before the commit). */
    fun outgoingUiPositionMs(): Long = outgoing.positionMs().toLong()

    private fun mappedIncomingPosition(): Long {
        val t = timeline.planTimeOfWindow(windowPositionMs())
        return if (t <= 0.0) timeline.plan.entryPointMs else timeline.incomingSourceAt(t).toLong()
    }

    fun tick() {
        if (isFinished || phase == Phase.IDLE) return
        try {
            val now = clock.nowMs()
            sampleDecks(now)
            when (phase) {
                Phase.LOCK_OUT -> tickLockOut(now)
                Phase.XFADE_OUT -> tickXfadeOut(now)
                Phase.WINDOW -> tickWindow(now)
                Phase.LOCK_IN -> tickLockIn(now)
                Phase.XFADE_IN -> tickXfadeIn(now)
                Phase.SETTLE -> tickSettle(now)
                else -> Unit
            }
        } catch (e: Exception) {
            log("transition tick failed: ${e.message}")
            val result = abort()
            host.onFailed("exception: ${e.message}", result)
        }
    }

    /**
     * Cancels the transition NOW (user seek / skip / pause, queue change, player error) and leaves
     * every deck in a state the host can commit from. Idempotent. Never throws.
     */
    fun abort(): AbortResult {
        if (phase == Phase.ABORTED || phase == Phase.DONE) return AbortResult(phase == Phase.DONE, incoming?.positionMs()?.toLong(), true)
        val vol = userVolume()
        val committed = incoming != null
        var pos: Long? = null
        var playing = false
        try {
            if (committed) {
                pos =
                    when (phase) {
                        Phase.XFADE_OUT, Phase.WINDOW -> mappedIncomingPosition()
                        else -> incoming!!.positionMs().toLong()
                    }
                val inc = incoming!!
                if (incomingStartIssued && inc.isPlaying) {
                    // Already carrying the right audio; only fix it when clearly off the mapped position.
                    if (phase == Phase.LOCK_IN || phase == Phase.XFADE_IN) {
                        val ref = incomingReferenceAt(windowPositionMs()).toLong()
                        if (abs(inc.positionMs() - ref) > ABORT_RESEEK_MS && phase == Phase.LOCK_IN) inc.seekTo(ref)
                    }
                    playing = true
                } else {
                    inc.seekTo(pos)
                }
                inc.volume = vol
                outgoing.pause()
            } else {
                // Before the commit nothing audible changed: the outgoing deck simply carries on.
                outgoing.volume = vol
            }
        } catch (e: Exception) {
            log("abort cleanup failed: ${e.message}")
        }
        try {
            window.pause()
            window.release()
        } catch (e: Exception) {
            log("abort: window release failed: ${e.message}")
        }
        setPhase(Phase.ABORTED, clock.nowMs())
        return AbortResult(committed, pos, playing)
    }

    // ---- phases ----

    private var outgoingStalledSinceMs = Double.NaN

    private fun tickLockOut(now: Double) {
        // The user paused / the track ended / a seek is rebuffering: before the commit nothing audible has
        // changed, so simply walk away and let the adapter carry on with whatever it is doing.
        if (!outgoing.isPlaying) {
            if (outgoingStalledSinceMs.isNaN()) outgoingStalledSinceMs = now
            if (now - outgoingStalledSinceMs > OUTGOING_STALL_LIMIT_MS) {
                log("outgoing deck stopped playing during lock-out -> abort before commit")
                val result = abort()
                host.onFailed("outgoing stopped", result)
                return
            }
        } else {
            outgoingStalledSinceMs = Double.NaN
        }
        val loop = align!!
        val w = windowPositionMs()
        val ref = outEst.estimate(now, outgoing.positionMs())
        val follower = timeline.outgoingSourceOfWindow(w)
        val err = follower - ref - decodeSkewMs // window (our decode) vs live outgoing (ExoPlayer)
        when (val step = if (window.isPlaying) loop.update(now, err) else AlignmentLoop.Step.None) {
            is AlignmentLoop.Step.SeekBy -> {
                window.seekTo(timeline.windowOfOutgoingSource(follower + step.deltaMs).toLong().coerceAtLeast(0L))
                winEst.reset()
            }
            else -> Unit
        }
        val deadline = w >= WindowTuning.XFADE_OUT_AT_MS || now - startedAtMs > WindowTuning.XFADE_OUT_AT_MS + 1500
        val locked = loop.state == AlignmentLoop.State.LOCKED
        if (locked && w >= EARLIEST_XFADE_OUT_MS || (deadline && !locked)) {
            val residual = if (loop.lastErrorMs.isNaN()) err else loop.lastErrorMs
            if (!locked && abs(residual) > WindowTuning.LOCK_GIVE_UP_MS) {
                log("lock-out failed: residual ${"%.1f".format(residual)} ms after ${loop.seeksUsed} seeks -> abort before commit")
                val result = abort()
                host.onFailed("lock-out failed (${"%.0f".format(residual)} ms)", result)
                return
            }
            // The window began this far behind (-) / ahead (+) of the live deck: teach the start-latency estimate.
            if (!loop.firstErrorMs.isNaN()) calibrator.observeStart(-loop.firstErrorMs.coerceIn(-400.0, 400.0))
            host.onEvent(TransitionEvent.LockedOut(residual, loop.seeksUsed, w))
            beginCommit(now, w)
        }
    }

    private fun beginCommit(now: Double, w: Double) {
        val lockInSource = timeline.incomingSourceOfWindow(timeline.lockInWindowMs.toDouble())
        // Parked where it must be when it starts, INCOMING_EARLY_START_MS before the lanes settle.
        val inc = host.commitToIncoming((lockInSource - WindowTuning.INCOMING_EARLY_START_MS).coerceAtLeast(0.0).toLong())
        if (inc == null) {
            log("host refused to commit")
            val result = abort()
            host.onFailed("host refused commit", result)
            return
        }
        incoming = inc
        inc.volume = 0f
        host.onEvent(TransitionEvent.Committed(w))
        setPhase(Phase.XFADE_OUT, now)
    }

    private fun tickXfadeOut(now: Double) {
        val x = ((now - phaseStartMs) / WindowTuning.XFADE_MS).coerceIn(0.0, 1.0)
        val vol = userVolume()
        window.volume = (vol * x).toFloat()
        outgoing.volume = (vol * (1.0 - x)).toFloat()
        if (x >= 1.0) {
            val residual = align?.lastErrorMs ?: Double.NaN
            outgoing.pause()
            host.onEvent(TransitionEvent.Handoff("live->window", residual, forced = align?.state != AlignmentLoop.State.LOCKED))
            setPhase(Phase.WINDOW, now)
        }
    }

    private fun tickWindow(now: Double) {
        window.volume = userVolume()
        val w = windowPositionMs()
        val inc = incoming ?: return
        // Start the (silent) incoming deck INCOMING_EARLY_START_MS ahead of the settle point, so the position Media3
        // reports for it has stopped smoothing by the time the lock measures it.
        if (!incomingStartIssued && w >= timeline.lockInWindowMs - WindowTuning.INCOMING_EARLY_START_MS - calibrator.startLatencyMs) {
            if (!inc.isReady) {
                if (w >= timeline.xfadeInWindowMs) {
                    log("incoming deck never became ready")
                    val result = abort()
                    host.onFailed("incoming not ready", result)
                }
                return
            }
            inc.volume = 0f
            inc.play()
            inEst.reset()
            incomingStartIssued = true
            startIssuedWindowMs = w
            align = AlignmentLoop(calibrator, target = SeekTarget.LIVE, log = log).also { it.start(now) }
            setPhase(Phase.LOCK_IN, now)
        }
    }

    private fun tickLockIn(now: Double) {
        window.volume = userVolume()
        val inc = incoming!!
        val loop = align!!
        val w = windowPositionMs()
        val ref = incomingReferenceAt(w)
        val follower = inEst.estimate(now, inc.positionMs())
        val err = follower - ref + decodeSkewMs // live incoming (ExoPlayer) vs window (our decode)
        when (val step = if (inc.isPlaying) loop.update(now, err) else AlignmentLoop.Step.None) {
            is AlignmentLoop.Step.SeekBy -> {
                inc.seekTo((follower + step.deltaMs).toLong().coerceAtLeast(0L))
                inEst.reset()
            }
            else -> Unit
        }
        val locked = loop.state == AlignmentLoop.State.LOCKED
        val deadline = w >= timeline.xfadeInWindowMs
        if (deadline && !inc.isPlaying) {
            // A corrective seek issued just before the deadline is still landing: give it a moment (the window has tail
            // to spare) instead of throwing the whole mix away for it.
            if (w < timeline.xfadeInWindowMs + WindowTuning.INCOMING_SEEK_GRACE_MS) return
            log("incoming deck is not playing at the hand-off deadline")
            val result = abort()
            host.onFailed("incoming not playing", result)
            return
        }
        if ((locked && w >= timeline.lockInWindowMs + EARLIEST_XFADE_IN_AFTER_MS) || deadline) {
            val residual = if (loop.lastErrorMs.isNaN()) err else loop.lastErrorMs
            host.onEvent(TransitionEvent.LockedIn(residual, loop.seeksUsed, forced = !locked))
            setPhase(Phase.XFADE_IN, now)
        }
    }

    /**
     * Where the live incoming deck should be at window time [w]. From the settle point on, the window plays the incoming
     * at rate 1, so that is the mapped source. Before it (the deck runs early and silent) the window may still be on
     * a tempo lane, so the target is the settle position projected back at rate 1, which the live deck does play.
     */
    private fun incomingReferenceAt(w: Double): Double {
        val lockIn = timeline.lockInWindowMs.toDouble()
        return if (w >= lockIn) timeline.incomingSourceOfWindow(w) else timeline.incomingSourceOfWindow(lockIn) - (lockIn - w)
    }

    private fun tickXfadeIn(now: Double) {
        val inc = incoming!!
        val x = ((now - phaseStartMs) / WindowTuning.XFADE_MS).coerceIn(0.0, 1.0)
        val vol = userVolume()
        window.volume = (vol * (1.0 - x)).toFloat()
        inc.volume = (vol * endGain * x).toFloat()
        if (x >= 1.0) {
            host.onWindowRetired()
            window.pause()
            window.release()
            inc.volume = vol * endGain
            host.onEvent(TransitionEvent.Handoff("window->live", align?.lastErrorMs ?: Double.NaN, forced = align?.state != AlignmentLoop.State.LOCKED))
            setPhase(Phase.SETTLE, now)
        }
    }

    private fun tickSettle(now: Double) {
        val inc = incoming!!
        val vol = userVolume()
        if (abs(endGain - 1f) < 0.01f) {
            inc.volume = vol
            finish(now)
            return
        }
        val x = ((now - phaseStartMs) / WindowTuning.SETTLE_RAMP_MS).coerceIn(0.0, 1.0)
        inc.volume = (vol * (endGain + (1f - endGain) * x)).toFloat()
        if (x >= 1.0) finish(now)
    }

    private fun finish(now: Double) {
        setPhase(Phase.DONE, now)
        host.onFinished()
    }

    // ---- helpers ----

    private fun sampleDecks(now: Double) {
        if (outgoing.isPlaying) outEst.add(now, outgoing.positionMs())
        if (window.isPlaying) winEst.add(now, window.positionMs())
        val inc = incoming
        if (inc != null && incomingStartIssued && inc.isPlaying) inEst.add(now, inc.positionMs())
    }

    private fun setPhase(p: Phase, now: Double) {
        log("transition phase $phase -> $p")
        phase = p
        phaseStartMs = now
    }

    private companion object {
        /** Do not commit before the window has run this long: the lock measurement needs a warmed-up pipeline. */
        const val EARLIEST_XFADE_OUT_MS = 900.0
        const val EARLIEST_XFADE_IN_AFTER_MS = 900.0
        const val ABORT_RESEEK_MS = 50.0
        const val OUTGOING_STALL_LIMIT_MS = 400.0
    }
}
