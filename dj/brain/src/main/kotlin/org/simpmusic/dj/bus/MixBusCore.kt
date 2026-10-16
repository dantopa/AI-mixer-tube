package org.simpmusic.dj.bus

import org.simpmusic.dj.model.DeckClock
import org.simpmusic.dj.model.TransitionPlan
import org.simpmusic.dj.model.settleMs
import org.simpmusic.dj.model.silentFromMs
import org.simpmusic.dj.render.DeckVoice
import org.simpmusic.dj.render.OfflineMixRenderer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The DJ mixer as one audio path: N decks -> one bus -> ONE output, so the two decks of a transition share a single
 * sample clock and sync is exact by construction (no seek bias, no lock, no hand-off between players).
 *
 * Each deck is a player's post-processing PCM in a [DeckRing], addressed by SOURCE frame, plus a [DeckVoice] that
 * renders it: at rate 1 (copy path) in normal playback - including the app's own crossfade, which only moves player
 * volumes - and through the plan's lanes during a transition. Pure JVM: the Android side wraps it with an AudioTrack
 * thread and a Media3 `AudioOutput` per player.
 *
 * Transitions: [arm] a [TransitionPlan] (times are SOURCE ms of the two tracks). When the outgoing deck's read position
 * reaches the plan's first frame (T0 minus the pre-roll, through its [DeckClock]) the bus switches the outgoing voice
 * onto the plan's outgoing lanes at that exact frame and starts the incoming voice exactly at its mapped entry frame at
 * T0: sample-exact, the output IS the offline [OfflineMixRenderer] window (same code, same block grid). Then:
 *  - the incoming deck must hold its entry frame when the transition starts, else [AbortReason.INCOMING_NOT_READY]
 *    (the outgoing keeps playing untouched; the caller falls back);
 *  - once the plan has settled the incoming voice drops to the copy path at rate 1 and the outgoing deck is released
 *    (silent, put on standby): [Listener.onTransitionDone]; the caller makes the incoming player the current one;
 *  - the outgoing deck paused, flushed (seek) or ended during the transition: it stops with a 20 ms fade, the incoming
 *    keeps playing as it is, [Listener.onTransitionAborted].
 * Master: plain sum, then the offline renderer's safety soft-clip (identity below 0.95).
 *
 * Thread contract:
 *  - producer threads (one per deck: the player) call [ring] methods (`write`, `flush`, `endOfStream`, `configure`)
 *    and [play], [pause], [setVolume];
 *  - ONE bus thread calls [render] / [renderPlanar]; listener callbacks run on it and must not block;
 *  - any thread calls [addDeck], [removeDeck], [setStandby], [arm], [cancel] and the queries.
 * [render] never allocates and never waits for a lock held by a producer (it takes [historyLock] only to publish
 * positions, for a few instructions).
 *
 * Decks are delivered at the bus rate ([sampleRate], fixed); [DeckRing.configure] refuses any other rate.
 */
class MixBusCore(
    val sampleRate: Int = 48_000,
    /** Largest block [render] accepts. */
    val maxBlockFrames: Int = 4096,
    /** The voices' internal block grid; must match [OfflineMixRenderer.Options.blockFrames] for bit-identical output. */
    val voiceBlockFrames: Int = OfflineMixRenderer.Options().blockFrames,
    private val listener: Listener = object : Listener {},
) {
    enum class AbortReason {
        /** At the start, the incoming ring did not hold the entry frame (and what the vocoder reads right after). */
        INCOMING_NOT_READY,
        /** The outgoing deck was already past the plan's first frame when the plan was picked up. */
        TOO_LATE,
        OUTGOING_PAUSED,
        OUTGOING_FLUSHED,
        OUTGOING_ENDED,
        /**
         * The incoming deck was paused, flushed or put on standby during the transition, or had lost its entry frame at
         * T0. It fades out (or never starts); the outgoing finishes its lanes and is released, so the caller must recover.
         */
        INCOMING_INTERRUPTED,
        /** [cancel] before the start. */
        CANCELLED,
    }

    enum class State { IDLE, ARMED, RUNNING }

    /** Callbacks, on the bus thread. [outputFrame] is the bus output frame of the event. */
    interface Listener {
        /** The outgoing deck switched onto the plan: output frame [outputFrame] is the window's frame 0. */
        fun onTransitionStarted(planId: Int, outputFrame: Long) {}

        /** The incoming deck started at its entry frame (T0). */
        fun onIncomingStarted(planId: Int, outputFrame: Long) {}

        /** The plan has settled: the incoming deck plays on its own, the outgoing deck is released (on standby). */
        fun onTransitionDone(planId: Int, outputFrame: Long) {}

        fun onTransitionAborted(planId: Int, reason: AbortReason, incomingStarted: Boolean) {}

        /** A deck played everything up to its end of stream. */
        fun onDeckDrained(deckId: Int) {}
    }

    private enum class Role { NONE, OUTGOING, INCOMING }

    private val controlLock = Any()

    /** Guards the position history ([deckSourceFrameAt] and friends). */
    val historyLock = Any()

    @Volatile private var decks: Array<Deck> = emptyArray()
    private var nextDeckId = 0
    private var nextPlanId = 1

    @Volatile private var pending: Transition? = null
    @Volatile private var cancelRequested = false
    /** The transition the bus thread runs (read by other threads only for [armedSwitchFrame] / [armedEntryFrame]). */
    @Volatile private var current: Transition? = null

    @Volatile private var publicState = State.IDLE
    @Volatile private var publicPlanId = 0

    /** State of the transition machine. */
    val state: State get() = publicState

    /** Id of the armed or running plan (0 = none). */
    val planId: Int get() = publicPlanId

    /** Output frames rendered so far (bus output frame counter). */
    @Volatile var renderedFrames: Long = 0L
        private set

    private val mixL = FloatArray(maxBlockFrames)
    private val mixR = FloatArray(maxBlockFrames)
    private val tmpL = FloatArray(maxBlockFrames)
    private val tmpR = FloatArray(maxBlockFrames)

    // =============================================================================================
    // decks

    /** Adds a deck; [standby] decks are never heard outside a transition even while their player plays. */
    fun addDeck(ringCapacityFrames: Int = 12 * sampleRate, standby: Boolean = false): Int = synchronized(controlLock) {
        val d = Deck(nextDeckId++, DeckRing(sampleRate, ringCapacityFrames))
        d.standby = standby
        decks = decks + d
        d.id
    }

    fun removeDeck(deckId: Int) = synchronized(controlLock) {
        val d = deck(deckId)
        val t = pending ?: current
        check(t == null || (t.out !== d && t.inc !== d)) { "deck $deckId is part of a transition" }
        decks = decks.filter { it !== d }.toTypedArray()
    }

    /** The deck's ring: the producer side of a deck. */
    fun ring(deckId: Int): DeckRing = deck(deckId).ring

    /** Player started (Media3 `AudioOutput.play`). */
    fun play(deckId: Int) { deck(deckId).playing = true }

    /** Player paused: the deck fades out over 20 ms and stops advancing. */
    fun pause(deckId: Int) { deck(deckId).playing = false }

    /** Player volume (linear), applied after the deck's voice; ramped over ~5 ms so steps do not click. */
    fun setVolume(deckId: Int, volume: Float) { deck(deckId).targetVolume = volume.coerceAtLeast(0f) }

    /** Standby: the deck is silent and not consumed outside a transition (a cued next track, a released outgoing). */
    fun setStandby(deckId: Int, standby: Boolean) { deck(deckId).standby = standby }

    fun isStandby(deckId: Int): Boolean = deck(deckId).standby

    /** The deck has played everything up to its end of stream. */
    fun isDrained(deckId: Int): Boolean = deck(deckId).drained

    private fun deck(id: Int): Deck = decks.firstOrNull { it.id == id } ?: throw IllegalArgumentException("no deck $id")

    // =============================================================================================
    // transitions

    /**
     * Arms [plan] from [outgoingDeck] to [incomingDeck]; returns its plan id. The incoming deck is put on standby at
     * once, so its player can be started (to fill its ring from just before the entry point) without being heard.
     *
     * @param leadMs plan time rendered before T0 by the plan's voices, as in [OfflineMixRenderer.Options.leadMs]
     *   (null = exactly the plan's pre-roll). The outgoing deck leaves the copy path that much before T0.
     * @throws IllegalStateException if a transition is already armed or running
     */
    fun arm(plan: TransitionPlan, outgoingDeck: Int, incomingDeck: Int, leadMs: Long? = null): Int {
        check(pending == null && publicState == State.IDLE) { "a transition is already ${publicState.name.lowercase()}" }
        val out = deck(outgoingDeck)
        val inc = deck(incomingDeck)
        require(out !== inc) { "outgoing and incoming deck are the same" }
        val sr = sampleRate
        // the same arithmetic as OfflineMixRenderer.render: same window origin, same clocks, same frames
        val lead = (leadMs ?: -plan.preRollMs).coerceAtLeast(0L)
        val n0 = (lead * sr / 1000.0).roundToInt()
        val startWall = -n0 * 1000.0 / sr
        val settle = plan.settleMs
        val handoffMs = settle + OfflineMixRenderer.RELOCK_MS
        val endWall = handoffMs + CLOCK_MARGIN_MS
        val outClock = DeckClock(plan.outgoing.rate, plan.exitPointMs.toDouble(), floor(startWall).toLong() - 2, ceil(endWall).toLong() + 2)
        val inClock = DeckClock(plan.incoming.rate, plan.entryPointMs.toDouble(), -1L, ceil(endWall).toLong() + 2)
        fun frameOf(ms: Long): Long = if (ms == Long.MIN_VALUE) 0L else max(0.0, n0 + ceil(ms * sr / 1000.0)).toLong()
        val silentAt = plan.outgoing.volume.silentFromMs()
        val echo = plan.echoOut
        val activeEnd: Long
        val outEnd: Long
        if (echo == null) {
            // a lane that never closes would leave the outgoing deck on for good: it ends with the plan instead
            activeEnd = frameOf(silentAt ?: settle)
            outEnd = activeEnd
        } else {
            activeEnd = max(frameOf(silentAt ?: settle), frameOf(echo.send.silentFromMs() ?: settle))
            outEnd = max(activeEnd, frameOf(settle)) // the tail has rung out at the settle point
        }
        val handoffFrame = ((handoffMs - startWall) * sr / 1000.0).roundToLong()
        val outProgram = DeckVoice.Program(
            plan.outgoing, outClock, startFrame = 0L, startWallMs = startWall, endFrame = outEnd,
            windowStartWallMs = startWall, echoSpec = echo, activeEndFrame = activeEnd, sampleRate = sr,
        )
        val inProgram = DeckVoice.Program(
            plan.incoming, inClock, startFrame = n0.toLong(), startWallMs = 0.0, endFrame = Long.MAX_VALUE,
            windowStartWallMs = startWall, handoffFrame = handoffFrame, sampleRate = sr,
        )
        val switchFrame = floor(outClock.sourceAt(startWall) * sr / 1000.0 + 0.5).toLong()
        val entryFrame = floor(inClock.sourceAt(0.0) * sr / 1000.0 + 0.5).toLong()
        val b = voiceBlockFrames.toLong()
        val inHandoffAt = Math.floorDiv(max(handoffFrame, n0 + 1L) + b - 1, b) * b
        return synchronized(controlLock) {
            check(pending == null && publicState == State.IDLE) { "a transition is already ${publicState.name.lowercase()}" }
            publish(plan, out, inc, n0.toLong(), switchFrame, entryFrame, outProgram, inProgram, outEnd, max(outEnd, inHandoffAt))
        }
    }

    private fun publish(
        plan: TransitionPlan, out: Deck, inc: Deck, n0: Long, switchFrame: Long, entryFrame: Long,
        outProgram: DeckVoice.Program, inProgram: DeckVoice.Program, outEnd: Long, doneFrame: Long,
    ): Int {
        val t = Transition(
            id = nextPlanId++, plan = plan, out = out, inc = inc, n0 = n0, switchFrame = switchFrame,
            entryFrame = entryFrame, outProgram = outProgram, inProgram = inProgram, outEnd = outEnd, doneFrame = doneFrame,
        )
        inc.standby = true
        cancelRequested = false
        pending = t
        publicPlanId = t.id
        publicState = State.ARMED
        return t.id
    }

    /** Cancels an armed transition that has not started; false if none is armed (a running one cannot be cancelled). */
    fun cancel(): Boolean = synchronized(controlLock) {
        if (publicState != State.ARMED) return false
        cancelRequested = true
        true
    }

    /** Source frame of the outgoing deck at which an armed plan will start (the window's frame 0), or null. */
    fun armedSwitchFrame(): Long? = (pending ?: current)?.switchFrame

    /** Incoming entry frame (source frame it plays at T0) of the armed or running plan, or null. */
    fun armedEntryFrame(): Long? = (pending ?: current)?.entryFrame

    // =============================================================================================
    // queries

    /**
     * Source frame (fractional during a transition) deck [deckId] was playing at bus output frame [outputFrame], for
     * any frame already rendered (up to [renderedFrames]; history kept for the last [HISTORY_SEGMENTS] changes of the
     * deck's playback). NaN when unknown (no stream yet, or older than the history).
     */
    fun deckSourceFrameAt(deckId: Int, outputFrame: Long): Double {
        val d = deck(deckId)
        synchronized(historyLock) { return d.history.sourceAt(outputFrame, renderedFrames) }
    }

    /**
     * Frames of deck [deckId] played up to bus output frame [outputFrame], counted from the start of its current stream
     * (its first write after a flush), in the deck's own rate: what a Media3 `AudioOutput.getPositionUs()` reports
     * (`frames * 1e6 / sampleRate`). During a transition it follows the plan's clock, so it is the deck's media position.
     * An incoming deck that was started early to fill its ring jumps from 0 to `entry - streamStart` at T0.
     */
    fun deckConsumedFramesAt(deckId: Int, outputFrame: Long): Long {
        val d = deck(deckId)
        synchronized(historyLock) { return d.history.consumedAt(outputFrame, renderedFrames) }
    }

    // =============================================================================================
    // the bus thread

    /** Renders [frames] (<= [maxBlockFrames]) interleaved stereo float frames into [out] from index 0. */
    fun render(out: FloatArray, frames: Int) {
        renderMix(frames)
        var j = 0
        for (i in 0 until frames) {
            out[j] = OfflineMixRenderer.softClip(mixL[i])
            out[j + 1] = OfflineMixRenderer.softClip(mixR[i])
            j += 2
        }
    }

    /** Planar form of [render]: [frames] frames into outL/outR from [offset]. */
    fun renderPlanar(outL: FloatArray, outR: FloatArray, offset: Int, frames: Int) {
        renderMix(frames)
        for (i in 0 until frames) {
            outL[offset + i] = OfflineMixRenderer.softClip(mixL[i])
            outR[offset + i] = OfflineMixRenderer.softClip(mixR[i])
        }
    }

    private fun renderMix(frames: Int) {
        require(frames in 0..maxBlockFrames) { "block of $frames frames (max $maxBlockFrames)" }
        pickUp()
        for (i in 0 until frames) { mixL[i] = 0f; mixR[i] = 0f }
        var done = 0
        while (done < frames) {
            val ds = decks
            for (d in ds) if (d.role == Role.NONE) updateDeck(d)
            transitionControl()
            ds.forEach { it.voice.settle() }
            var n = frames - done
            n = min(n.toLong(), spanLimit()).toInt()
            for (d in ds) if (d.voice.mode != DeckVoice.Mode.IDLE) n = min(n, d.voice.framesUntilModeChange())
            val t = current
            // summing order matters for bit-identity with the offline window: outgoing, then incoming
            if (t != null) {
                renderDeck(t.out, done, n)
                renderDeck(t.inc, done, n)
            }
            for (d in ds) if (t == null || (d !== t.out && d !== t.inc)) renderDeck(d, done, n)
            done += n
            renderedFrames += n
            if (t != null) t.wf += n
            for (d in ds) d.afterSpan()
        }
    }

    private fun pickUp() {
        val p = pending ?: return
        pending = null
        current = p
        p.state = State.ARMED
    }

    /** Frames until the transition machine must look again. */
    private fun spanLimit(): Long {
        val t = current ?: return Long.MAX_VALUE
        return when (t.state) {
            State.ARMED -> {
                val v = t.out.voice
                if (v.mode == DeckVoice.Mode.DIRECT && v.directPosition < t.switchFrame) t.switchFrame - v.directPosition else Long.MAX_VALUE
            }
            State.RUNNING -> {
                var m = Long.MAX_VALUE
                if (!t.incomingStarted && !t.incomingDead && t.wf < t.n0) m = min(m, t.n0 - t.wf)
                if (!t.outgoingDone && t.wf < t.outEnd) m = min(m, t.outEnd - t.wf)
                if (t.wf < t.doneFrame) m = min(m, t.doneFrame - t.wf)
                m
            }
            State.IDLE -> Long.MAX_VALUE
        }
    }

    private fun transitionControl() {
        val t = current ?: return
        val outputFrame = renderedFrames
        if (t.state == State.ARMED) {
            if (cancelRequested) { endTransition(t); listener.onTransitionAborted(t.id, AbortReason.CANCELLED, false); return }
            // keep the cued incoming's entry in its ring: everything older may go
            t.inc.ring.release(t.entryFrame - RELEASE_KEEP_FRAMES)
            val v = t.out.voice
            val pos = v.directPosition
            if (t.out.origin != DeckRing.UNSET && pos > t.switchFrame) {
                endTransition(t)
                listener.onTransitionAborted(t.id, AbortReason.TOO_LATE, false)
                return
            }
            if (v.mode == DeckVoice.Mode.DIRECT && !v.isFadingOut && pos == t.switchFrame && t.out.playing && !t.out.standby) {
                val inc = t.inc
                val view = inc.view
                val ok = inc.ring.snapshot(view) && view.start <= t.entryFrame &&
                    (view.end >= t.entryFrame + INCOMING_READY_AHEAD_FRAMES || (view.endOfStream && view.end > t.entryFrame))
                if (!ok) {
                    endTransition(t)
                    listener.onTransitionAborted(t.id, AbortReason.INCOMING_NOT_READY, false)
                    return
                }
                // the switch: window frame 0 is this output frame, both voices run the plan from here
                t.state = State.RUNNING
                publicState = State.RUNNING
                t.wf = 0L
                t.out.role = Role.OUTGOING
                inc.role = Role.INCOMING
                t.out.voice.startProgram(t.outProgram, t.out.source, 0L)
                inc.seenGeneration = view.generation
                inc.origin = view.origin
                inc.source.generation = view.generation
                inc.voice.startProgram(t.inProgram, inc.source, 0L)
                listener.onTransitionStarted(t.id, outputFrame)
            }
            return
        }
        if (t.state != State.RUNNING) return
        // ---- outgoing interrupted? ----
        if (!t.outgoingDone) {
            val o = t.out
            val reason = when {
                o.ring.generation != o.source.generation -> AbortReason.OUTGOING_FLUSHED
                !o.playing || o.standby -> AbortReason.OUTGOING_PAUSED
                t.wf < t.outProgram.activeEndFrame && o.ring.snapshot(o.view) && o.view.endOfStream &&
                    o.voice.currentSourceFrame() >= o.view.end -> AbortReason.OUTGOING_ENDED
                else -> null
            }
            if (reason != null) {
                o.voice.fadeOut(DECLICK_FRAMES)
                o.role = Role.NONE
                o.resumeWithFade = true
                // an incoming deck that already plays keeps playing exactly as it is (its lanes, then the copy path);
                // one still waiting for T0 never starts and stays cued
                if (!t.incomingStarted) t.inc.voice.stop()
                t.inc.role = Role.NONE
                val started = t.incomingStarted
                val reported = t.incomingDead // the incoming loss was reported already: one abort per plan
                endTransition(t)
                if (!reported) listener.onTransitionAborted(t.id, reason, started)
                return
            }
        }
        // ---- incoming start at T0 ----
        if (!t.incomingStarted && !t.incomingDead && t.wf >= t.n0) {
            val inc = t.inc
            val ok = inc.ring.generation == inc.source.generation && inc.ring.snapshot(inc.view) &&
                inc.view.start <= t.entryFrame && inc.view.end > t.entryFrame
            if (ok) {
                t.incomingStarted = true
                inc.standby = false
                listener.onIncomingStarted(t.id, outputFrame)
            } else {
                incomingLost(t)
            }
        } else if (t.incomingStarted && !t.incomingDead) {
            val inc = t.inc
            if (inc.ring.generation != inc.source.generation || !inc.playing || inc.standby) incomingLost(t)
        } else if (!t.incomingStarted && !t.incomingDead) {
            t.inc.ring.release(t.entryFrame - RELEASE_KEEP_FRAMES)
            if (t.inc.ring.generation != t.inc.source.generation) incomingLost(t)
        }
        // ---- outgoing done ----
        if (!t.outgoingDone && t.wf >= t.outEnd) {
            t.outgoingDone = true
            t.out.voice.stop()
            t.out.standby = true
            t.out.role = Role.NONE
        }
        // ---- settled ----
        if (t.outgoingDone && t.wf >= t.doneFrame) {
            if (t.inc.role == Role.INCOMING) t.inc.role = Role.NONE
            val ok = !t.incomingDead
            endTransition(t)
            if (ok) listener.onTransitionDone(t.id, outputFrame)
        }
    }

    private fun incomingLost(t: Transition) {
        t.incomingDead = true
        val inc = t.inc
        if (t.incomingStarted) inc.voice.fadeOut(DECLICK_FRAMES) else inc.voice.stop()
        inc.role = Role.NONE
        inc.resumeWithFade = true
        listener.onTransitionAborted(t.id, AbortReason.INCOMING_INTERRUPTED, t.incomingStarted)
    }

    private fun endTransition(t: Transition) {
        // no lock: [arm] refuses while a transition is armed or running, so nothing can be pending here
        if (current === t) current = null
        t.state = State.IDLE
        publicState = State.IDLE
        publicPlanId = 0
    }

    /** Normal-playback state of a deck that is not in a transition: flushes, play/pause, standby, end of stream. */
    private fun updateDeck(d: Deck) {
        val v = d.voice
        val gen = d.ring.generation
        if (gen != d.seenGeneration) {
            // a flush (seek): the old stream is gone. A fade already under way finishes on what the voice still holds
            // (its source stays bound to the old stream, which now reads as silence); anything else stops at once.
            d.seenGeneration = gen
            d.origin = DeckRing.UNSET
            d.drained = false
            d.resumeWithFade = false
            if (v.mode != DeckVoice.Mode.IDLE && !v.isFadingOut) v.stop()
        }
        if (v.mode == DeckVoice.Mode.IDLE && d.origin == DeckRing.UNSET) {
            if (d.ring.snapshot(d.view) && d.view.generation == d.seenGeneration) {
                d.origin = d.view.origin
                d.source.generation = d.view.generation
                v.park(d.view.origin)
            }
        }
        if (v.programFinished) v.stop()
        if (v.mode != DeckVoice.Mode.IDLE && !v.isFadingOut && d.origin != DeckRing.UNSET &&
            d.ring.snapshot(d.view) && d.view.endOfStream && d.view.generation == d.source.generation &&
            v.currentSourceFrame() >= d.view.end
        ) {
            v.stop()
            if (!d.drained) {
                d.drained = true
                listener.onDeckDrained(d.id)
            }
        }
        val want = d.playing && !d.standby && d.origin != DeckRing.UNSET && !d.drained
        if (v.mode == DeckVoice.Mode.IDLE) {
            if (want) {
                v.startDirect(d.source, v.parkedFrame)
                if (d.resumeWithFade) v.fadeIn(DECLICK_FRAMES)
                d.resumeWithFade = false
            }
        } else if (!want && !v.isFadingOut) {
            v.fadeOut(DECLICK_FRAMES)
            d.resumeWithFade = true
        }
    }

    private fun renderDeck(d: Deck, at: Int, n: Int) {
        val v = d.voice
        val outStart = renderedFrames
        // position bookkeeping for this span (the mode is constant over it: spans are split at every change)
        synchronized(historyLock) {
            when (v.mode) {
                DeckVoice.Mode.IDLE ->
                    if (d.origin == DeckRing.UNSET) d.history.none(outStart) else d.history.stall(outStart, v.parkedFrame.toDouble(), d.origin)
                DeckVoice.Mode.DIRECT -> d.history.direct(outStart, v.directPosition, d.origin)
                DeckVoice.Mode.PROGRAM -> {
                    val p = v.program!!
                    if (v.windowFrame < p.startFrame) {
                        d.history.stall(outStart, d.origin.toDouble(), d.origin)
                    } else d.history.program(outStart, p, v.windowFrame, d.origin, sampleRate)
                }
            }
        }
        if (v.mode == DeckVoice.Mode.IDLE) return
        val wasDirect = v.mode == DeckVoice.Mode.DIRECT
        val adv = v.render(tmpL, tmpR, 0, n)
        if (wasDirect && adv < n) {
            if (v.mode == DeckVoice.Mode.DIRECT) d.ring.countStall(n - adv)
            synchronized(historyLock) { d.history.stall(outStart + adv, v.directPosition.toDouble(), d.origin) }
        }
        // player volume, ramped
        var g = d.gain
        val target = d.targetVolume
        if (g == target) {
            for (i in 0 until n) {
                mixL[at + i] += tmpL[i] * g
                mixR[at + i] += tmpR[i] * g
            }
        } else {
            for (i in 0 until n) {
                g = if (g < target) min(target, g + VOLUME_STEP) else max(target, g - VOLUME_STEP)
                mixL[at + i] += tmpL[i] * g
                mixR[at + i] += tmpR[i] * g
            }
            d.gain = g
        }
    }

    // =============================================================================================

    private class Transition(
        val id: Int,
        val plan: TransitionPlan,
        val out: Deck,
        val inc: Deck,
        /** Window frame of T0. */
        val n0: Long,
        /** Source frame of the outgoing deck at window frame 0. */
        val switchFrame: Long,
        /** Source frame of the incoming deck at T0. */
        val entryFrame: Long,
        val outProgram: DeckVoice.Program,
        val inProgram: DeckVoice.Program,
        /** Window frame from which the outgoing deck is silent for good. */
        val outEnd: Long,
        /** Window frame at which the transition is over (outgoing released, incoming on the copy path). */
        val doneFrame: Long,
    ) {
        @Volatile var state = State.IDLE
        /** Current window frame (bus thread). */
        var wf = 0L
        var incomingStarted = false
        var incomingDead = false
        var outgoingDone = false
    }

    private inner class Deck(val id: Int, val ring: DeckRing) {
        val voice = DeckVoice(sampleRate, voiceBlockFrames)
        val view = DeckRing.View()
        val history = History(HISTORY_SEGMENTS)
        val source = RingSource()

        @Volatile var playing = false
        @Volatile var standby = false
        @Volatile var targetVolume = 1f
        @Volatile var drained = false

        // ---- bus thread ----
        var gain = 1f
        var seenGeneration = -1
        var origin = DeckRing.UNSET
        var resumeWithFade = false
        var role = Role.NONE

        /** After a span: free what the voice no longer needs (the vocoder reads up to 1.5 k frames back). */
        fun afterSpan() {
            val low = source.lowestRead
            if (low != Long.MAX_VALUE) {
                ring.release(low - RELEASE_KEEP_FRAMES, source.generation)
                source.lowestRead = Long.MAX_VALUE
            }
        }

        inner class RingSource : DeckVoice.Source {
            /** Stream the voice is bound to: reads of any other stream are silence. */
            var generation = -1
            var lowestRead = Long.MAX_VALUE

            override fun read(startFrame: Long, count: Int, dst: Array<FloatArray>) {
                ring.read(startFrame, count, dst[0], dst[1], 0, generation)
                if (startFrame < lowestRead) lowestRead = startFrame
            }

            override fun contiguousFrames(startFrame: Long, max: Int): Int = ring.contiguousFrames(startFrame, max, generation)
        }
    }

    /**
     * A deck's position over output time, as a ring of segments: from output frame `outStart` on, the deck plays
     * a stall (fixed source frame), the copy path (source advances 1:1), or a program (source = its clock).
     */
    private class History(cap: Int) {
        private val outStart = LongArray(cap)
        private val kind = IntArray(cap)
        private val base = DoubleArray(cap)
        private val wf = LongArray(cap)
        private val prog = arrayOfNulls<DeckVoice.Program>(cap)
        private val origin = LongArray(cap)
        private var head = -1
        private var size = 0
        private var sr = 48_000
        private val capacity = cap

        fun none(at: Long) = append(at, K_NONE, Double.NaN, 0L, null, DeckRing.UNSET)
        fun stall(at: Long, src: Double, o: Long) = append(at, K_STALL, src, 0L, null, o)
        fun direct(at: Long, src: Long, o: Long) = append(at, K_DIRECT, src.toDouble(), 0L, null, o)
        fun program(at: Long, p: DeckVoice.Program, windowFrame: Long, o: Long, sampleRate: Int) {
            sr = sampleRate
            append(at, K_PROGRAM, 0.0, windowFrame, p, o)
        }

        private fun append(at: Long, k: Int, b: Double, w: Long, p: DeckVoice.Program?, o: Long) {
            // a segment that never got to play (the span stalled at once) is replaced, not kept: a deck starved for
            // many blocks must not fill the history with empty segments
            if (size > 0 && outStart[head] == at) {
                head = if (head == 0) capacity - 1 else head - 1
                size--
            }
            if (size > 0) {
                val h = head
                if (kind[h] == k && origin[h] == o) {
                    val dt = at - outStart[h]
                    val same = when (k) {
                        K_NONE -> true
                        K_STALL -> base[h] == b || (base[h].isNaN() && b.isNaN())
                        K_DIRECT -> base[h] + dt == b
                        else -> prog[h] === p && wf[h] + dt == w
                    }
                    if (same) return
                }
            }
            head = (head + 1) % capacity
            if (size < capacity) size++
            set(head, at, k, b, w, p, o)
        }

        private fun set(i: Int, at: Long, k: Int, b: Double, w: Long, p: DeckVoice.Program?, o: Long) {
            outStart[i] = at; kind[i] = k; base[i] = b; wf[i] = w; prog[i] = p; origin[i] = o
        }

        private fun find(frame: Long, rendered: Long): Int {
            if (frame < 0 || frame > rendered || size == 0) return -1
            var i = head
            for (c in 0 until size) {
                if (outStart[i] <= frame) return i
                i = if (i == 0) capacity - 1 else i - 1
            }
            return -1
        }

        fun sourceAt(frame: Long, rendered: Long): Double {
            val i = find(frame, rendered)
            if (i < 0) return Double.NaN
            val dt = frame - outStart[i]
            return when (kind[i]) {
                K_STALL -> base[i]
                K_DIRECT -> base[i] + dt
                K_PROGRAM -> prog[i]!!.sourceFrameAt(wf[i] + dt, sr)
                else -> Double.NaN
            }
        }

        fun consumedAt(frame: Long, rendered: Long): Long {
            val i = find(frame, rendered)
            if (i < 0) return 0L
            val o = origin[i]
            if (o == DeckRing.UNSET) return 0L
            val s = sourceAt(frame, rendered)
            if (s.isNaN()) return 0L
            return floor(s - o).toLong().coerceAtLeast(0L)
        }

        companion object {
            const val K_NONE = 0
            const val K_STALL = 1
            const val K_DIRECT = 2
            const val K_PROGRAM = 3
        }
    }

    companion object {
        /** Fade applied when a deck stops or resumes outside the plan (pause, abort): 20 ms at 48 kHz. */
        const val DECLICK_FRAMES = 960

        /** Frames a voice keeps behind its read position (the vocoder reads 1.5 k frames back when it is switched in). */
        const val RELEASE_KEEP_FRAMES = 4096L

        /**
         * Frames past its entry the incoming ring must hold at the start: what the vocoder reads for its first chunks
         * (2 windows + a block). The producer must then keep running ahead of the deck.
         */
        const val INCOMING_READY_AHEAD_FRAMES = 4096L + 256L

        /** Segments of position history kept per deck. */
        const val HISTORY_SEGMENTS = 2048

        /** Plan time tabulated by the deck clocks beyond the hand-off (extrapolated at the boundary rate after). */
        private const val CLOCK_MARGIN_MS = 2_000.0

        /** Player-volume ramp: full scale in 240 frames (5 ms at 48 kHz). */
        private const val VOLUME_STEP = 1f / 240f
    }
}
