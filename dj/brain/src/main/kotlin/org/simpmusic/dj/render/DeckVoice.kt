package org.simpmusic.dj.render

import org.simpmusic.dj.model.DeckClock
import org.simpmusic.dj.model.DeckPlan
import org.simpmusic.dj.model.EchoOutSpec
import org.simpmusic.dj.model.ParamCurve
import org.simpmusic.dj.model.SourceLoop
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * One deck's signal chain, as a streaming voice: the per-deck half of [OfflineMixRenderer], shared by the offline
 * renderer and the real-time bus (`org.simpmusic.dj.bus.MixBusCore`), so both execute a plan with the very same code.
 *
 * Chain while a [Program] runs (identical to the plan's KDoc): [DeckStretcher] (rate + pitch lanes) -> low-cut ->
 * high-cut ([SweepFilter]) -> optional insert effect -> channel fader (volume lane), plus, for an [EchoOutSpec], an
 * aux send into [BeatEcho] taken before the filters and returned after the fader.
 *
 * Three modes:
 *  - [Mode.DIRECT], the fast path for normal playback: source frames are copied straight through at rate 1 (times a
 *    constant gain). No vocoder, no filters. If the source does not have the frames yet the voice STALLS (outputs
 *    silence without advancing), like an AudioTrack underrun.
 *  - [Mode.PROGRAM]: the full chain driven by the plan's lanes, in WINDOW frames (frame 0 = the plan time
 *    [Program.windowStartWallMs]). Internally it is rendered in chunks of [blockFrames] aligned on window frame 0,
 *    exactly the block grid the offline renderer uses, whatever the caller's block size: the lane sampling (fader knots,
 *    filter sub-blocks) is relative to the block start, so this grid is what makes the streaming output bit-identical
 *    to the offline window.
 *  - [Mode.IDLE]: silence; the source position is parked at [parkedFrame].
 *
 * Switching DIRECT -> PROGRAM is seamless at a neutral lane (the vocoder is an identity at rate 1 and starts with its
 * pre-roll already read from the source), and PROGRAM -> DIRECT happens on its own once the lanes have settled on
 * neutral values ([Program.handoffFrame]), through a short cross-fade that is numerically a no-op when the vocoder has
 * re-locked onto the source and hides the sub-sample offset a pitch shift can leave behind otherwise.
 *
 * Not thread-safe: one thread drives a voice. No allocation after construction (a [Program] is built elsewhere).
 */
class DeckVoice(val sampleRate: Int, val blockFrames: Int = OfflineMixRenderer.Options().blockFrames) : DeckStretcher.Automation {
    enum class Mode { IDLE, DIRECT, PROGRAM }

    /** Source of a deck: [read] as for the stretcher (silence outside the audio) plus what the rate-1 path may consume. */
    interface Source : DeckStretcher.Source {
        /** Frames readable contiguously from [startFrame] right now, at most [max]. The rate-1 path stalls past them. */
        fun contiguousFrames(startFrame: Long, max: Int): Int = max
    }

    /**
     * Everything one deck does over one plan, in window-frame coordinates. Single use (it owns the echo's state) and
     * built off the audio thread: this is where the allocations of a plan happen.
     *
     * @param clock the deck's [DeckClock]: its source position as a function of plan time
     * @param startFrame window frame at which the deck starts (the stretcher is started there, lazily)
     * @param startWallMs plan time the lanes are sampled at for the deck's own output frame 0 (= [startFrame])
     * @param endFrame window frame from which the deck is silent for good (exclusive end; Long.MAX_VALUE = open)
     * @param windowStartWallMs plan time of window frame 0
     * @param activeEndFrame with an echo: frames at or after this one get no source audio (echo tail only)
     * @param bypassMixer ignore volume, filter and echo lanes (keep rate/pitch), unity gain
     * @param handoffFrame window frame from which the voice may leave the vocoder for the rate-1 path, when the lanes
     *   end on neutral values ([neutralAtEnd]); it does so on the first chunk boundary at or after it
     */
    class Program(
        val lanes: DeckPlan,
        val clock: DeckClock,
        val startFrame: Long,
        val startWallMs: Double,
        val endFrame: Long,
        val windowStartWallMs: Double,
        val echoSpec: EchoOutSpec? = null,
        val activeEndFrame: Long = endFrame,
        val bypassMixer: Boolean = false,
        val effect: OfflineMixRenderer.BlockEffect? = null,
        val handoffFrame: Long = Long.MAX_VALUE,
        sampleRate: Int,
        /** The deck's source is looped ([LoopSource]): positions past the loop's end play the loop again. */
        val loop: SourceLoop? = null,
    ) {
        internal val useHp = lanes.lowCutHz.keys.any { it.value > 20.5f }
        internal val useLp = lanes.highCutHz.keys.any { it.value < 19_500f }
        internal val echo: BeatEcho? =
            if (bypassMixer) null else echoSpec?.let { BeatEcho(sampleRate, it.delayMs.toDouble(), it.feedback.toDouble(), it.highpassHz.toDouble(), it.dampHz.toDouble(), it.pingPong) }

        /** Gain the fader holds once the volume lane has ended (what the rate-1 path continues with). */
        val finalGain: Float = if (bypassMixer) 1f else lanes.volume.keys.last().value

        /**
         * Every lane ends where the rate-1 copy is the same signal: rate 1, no pitch shift, both filters out of the path
         * ([SweepFilter]'s exact-bypass values), no echo, no insert effect.
         */
        val neutralAtEnd: Boolean = lanes.rate.keys.last().value == 1f &&
            lanes.pitchSemitones.keys.last().value == 0f &&
            (bypassMixer || (lanes.lowCutHz.keys.last().value <= 20.5f && lanes.highCutHz.keys.last().value >= 19_500f)) &&
            echo == null && effect == null

        /** Plan time (ms relative to T0) of window frame [frame]. */
        fun planTimeMs(frame: Long, sampleRate: Int): Double = windowStartWallMs + frame * 1000.0 / sampleRate

        /** Source frame (fractional) the deck plays at window frame [frame] according to the clock. */
        fun sourceFrameAt(frame: Long, sampleRate: Int): Double = clock.sourceAt(planTimeMs(frame, sampleRate)) * sampleRate / 1000.0
    }

    val stretcherLatencyFrames: Int get() = stretcher.latencyFrames

    private val stretcher = DeckStretcher(2, sampleRate)
    private val tmp = arrayOf(FloatArray(blockFrames), FloatArray(blockFrames))
    private val hp = SweepFilter(sampleRate, highpass = true)
    private val lp = SweepFilter(sampleRate, highpass = false)
    private val msPerFrame = 1000.0 / sampleRate
    private val gainKnots = FloatArray(blockFrames / GAIN_STEP + 2)
    private val sendL = FloatArray(blockFrames)
    private val sendR = FloatArray(blockFrames)
    private val wetL = FloatArray(blockFrames)
    private val wetR = FloatArray(blockFrames)
    private val laneGain = FloatArray(blockFrames)
    private val cacheL = FloatArray(blockFrames)
    private val cacheR = FloatArray(blockFrames)
    private val xfL = FloatArray(blockFrames)
    private val xfR = FloatArray(blockFrames)
    private val xfBuf = arrayOf(xfL, xfR)

    var mode: Mode = Mode.IDLE
        private set

    /** The loaded program (PROGRAM mode, and kept for queries until the next start). */
    var program: Program? = null
        private set
    private var source: Source? = null
    private var stretcherStarted = false

    // ---- PROGRAM streaming state ----
    /** Window frame of the next output frame (PROGRAM mode). */
    var windowFrame: Long = 0L
        private set
    private var cacheStart = Long.MIN_VALUE
    private var handoffAt = Long.MAX_VALUE

    // ---- DIRECT state ----
    private var directPos = 0L
    private var directGain = 1f
    private var xfadeLeft = 0
    private var xfadeLen = 0

    /** Source frame at which an IDLE voice is parked (the next frame it would play). */
    var parkedFrame: Long = 0L
        private set

    // ---- fades (streaming only) ----
    private var fadeLeft = 0
    private var fadeLen = 0
    private var fadingOut = false

    val isFadingOut: Boolean get() = fadingOut && fadeLeft > 0

    /** Next source frame of the rate-1 path (DIRECT), or the parked frame (IDLE). */
    val directPosition: Long get() = if (mode == Mode.DIRECT) directPos else parkedFrame

    // =============================================================================================
    // Offline form (exactly the old per-deck runner of OfflineMixRenderer)

    /** Loads [program] for [mixBlock] (no streaming state). */
    fun load(program: Program, source: Source) {
        this.program = program
        this.source = source
        stretcherStarted = false
        hp.reset()
        lp.reset()
    }

    /**
     * Renders the window frames [g0, g0 + count) of the loaded program and ADDS them into dstL/dstR at index
     * `frame - dstBase`. The executable specification of one deck in the plan: identical arithmetic to the renderer
     * this class was extracted from, so the output is bit-identical for the same block grid.
     */
    fun mixBlock(g0: Long, count: Int, dstL: FloatArray, dstR: FloatArray, dstBase: Long) {
        val p = program ?: return
        if (g0 + count <= p.startFrame || g0 >= p.endFrame) return
        val from = max(g0, p.startFrame)
        val to = min(g0 + count, p.endFrame)
        val n = (to - from).toInt()
        if (!stretcherStarted) {
            stretcher.start(p.clock.sourceAt(p.startWallMs) * sampleRate / 1000.0, source!!, this)
            stretcherStarted = true
        }
        val echo = p.echo
        val echoSpec = p.echoSpec
        val bypass = p.bypassMixer
        val live = if (echo == null) n else (min(to, p.activeEndFrame) - from).coerceIn(0L, n.toLong()).toInt()
        if (live > 0) stretcher.render(tmp, 0, live)
        val l = tmp[0]
        val r = tmp[1]
        for (i in live until n) { l[i] = 0f; r[i] = 0f }
        if (!bypass) {
            val lanes = p.lanes
            val wall0 = p.windowStartWallMs + from.toDouble() * msPerFrame
            if (echo != null && echoSpec != null) {
                // aux send: taken BEFORE the deck's own filters (like a send ahead of the channel EQ on a mixer), so the
                // repeats carry the full signal while the dry signal thins out under the rising high-pass
                fillLane(echoSpec.send, wall0, n)
                for (i in 0 until n) { sendL[i] = l[i] * laneGain[i]; sendR[i] = r[i] * laneGain[i] }
            }
            if (p.useHp) hp.process(l, r, 0, n) { i -> lanes.lowCutHz.valueAt(wall0 + i * msPerFrame).toDouble() }
            if (p.useLp) lp.process(l, r, 0, n) { i -> lanes.highCutHz.valueAt(wall0 + i * msPerFrame).toDouble() }
            p.effect?.process(l, r, 0, n)
            if (echo != null && echoSpec != null) {
                echo.processWet(sendL, sendR, wetL, wetR, 0, n)
                fillLane(echoSpec.wet, wall0, n)
                for (i in 0 until n) { wetL[i] *= laneGain[i]; wetR[i] *= laneGain[i] }
            }
            // channel fader: lane sampled every GAIN_STEP frames, linear in between
            val knots = (n + GAIN_STEP - 1) / GAIN_STEP
            for (k in 0..knots) gainKnots[k] = lanes.volume.valueAt(wall0 + min(k * GAIN_STEP, n) * msPerFrame)
            for (i in 0 until n) {
                val k = i / GAIN_STEP
                val f = (i - k * GAIN_STEP) / GAIN_STEP.toFloat()
                val gain = gainKnots[k] + (gainKnots[k + 1] - gainKnots[k]) * f
                l[i] *= gain
                r[i] *= gain
            }
            if (echo != null) {
                // the wet return bypasses the fader: the tail survives it going to 0
                for (i in 0 until n) { l[i] += wetL[i]; r[i] += wetR[i] }
            }
        }
        val base = (from - dstBase).toInt()
        for (i in 0 until n) {
            dstL[base + i] += l[i]
            dstR[base + i] += r[i]
        }
    }

    /** Samples [curve] into [laneGain] for [n] frames starting at wall time [wall0], linear between knots every GAIN_STEP frames. */
    private fun fillLane(curve: ParamCurve, wall0: Double, n: Int) {
        var k = 0
        var a = curve.valueAt(wall0)
        while (k * GAIN_STEP < n) {
            val end = min((k + 1) * GAIN_STEP, n)
            val b = curve.valueAt(wall0 + end * msPerFrame)
            val len = end - k * GAIN_STEP
            for (j in 0 until len) laneGain[k * GAIN_STEP + j] = a + (b - a) * (j / len.toFloat())
            a = b
            k++
        }
    }

    // The stretcher's automation: the lanes as a function of the deck's own output frame (counted from its start).
    override fun rate(outFrame: Double): Double {
        val p = program!!
        return p.lanes.rate.valueAt(p.startWallMs + outFrame * msPerFrame).toDouble()
    }

    override fun pitchRatio(outFrame: Double): Double {
        val p = program!!
        return 2.0.pow(p.lanes.pitchSemitones.valueAt(p.startWallMs + outFrame * msPerFrame) / 12.0)
    }

    // =============================================================================================
    // Streaming form

    /** Rate-1 playback: the next output frame is source frame [sourceFrame], scaled by [gain]. Cancels any fade. */
    fun startDirect(source: Source, sourceFrame: Long, gain: Float = 1f) {
        this.source = source
        mode = Mode.DIRECT
        directPos = sourceFrame
        directGain = gain
        xfadeLeft = 0
        fadeLeft = 0
        fadingOut = false
    }

    /**
     * Runs [program] from window frame [windowFrame] (a multiple of [blockFrames]; the bus uses 0, the instant it
     * switches the deck in) on [source]. Cancels any fade.
     */
    fun startProgram(program: Program, source: Source, windowFrame: Long = 0L) {
        require(Math.floorMod(windowFrame, blockFrames.toLong()) == 0L) { "a program starts on its block grid" }
        load(program, source)
        mode = Mode.PROGRAM
        this.windowFrame = windowFrame
        cacheStart = Long.MIN_VALUE
        handoffAt = if (program.neutralAtEnd && program.handoffFrame != Long.MAX_VALUE) {
            ceilToGrid(max(program.handoffFrame, program.startFrame + 1))
        } else Long.MAX_VALUE
        xfadeLeft = 0
        fadeLeft = 0
        fadingOut = false
    }

    /** Silence from now on; the source position is parked at the next frame the voice would have played. */
    fun stop() {
        parkedFrame = currentSourceFrameRounded()
        mode = Mode.IDLE
        fadeLeft = 0
        fadingOut = false
        xfadeLeft = 0
    }

    /** Silence, parked at [sourceFrame] (where a later [startDirect] would resume). */
    fun park(sourceFrame: Long) {
        stop()
        parkedFrame = sourceFrame
    }

    /**
     * Applies a mode change that is due before the next output frame (a program at its hand-off point switches to the
     * rate-1 path here), so a caller reading the mode or the position before [render] sees what will really play.
     */
    fun settle() {
        if (mode == Mode.PROGRAM && windowFrame >= handoffAt) handOff()
    }

    /** Linear fade to silence over [frames], then [stop]. The chain keeps running (and the source advancing) meanwhile. */
    fun fadeOut(frames: Int) {
        if (mode == Mode.IDLE) return
        fadingOut = true
        fadeLen = max(1, frames)
        fadeLeft = fadeLen
    }

    /** Linear fade from silence over [frames] (after a resume). */
    fun fadeIn(frames: Int) {
        fadingOut = false
        fadeLen = max(1, frames)
        fadeLeft = fadeLen
    }

    /** Window frame from which the program has handed over to the rate-1 path (Long.MAX_VALUE if it never will). */
    val handoffWindowFrame: Long get() = handoffAt

    /** True once a program has passed its [Program.endFrame]: it only produces silence from here. */
    val programFinished: Boolean get() = mode == Mode.PROGRAM && windowFrame >= (program?.endFrame ?: Long.MIN_VALUE)

    /**
     * Frames until the voice's position bookkeeping changes shape (deck start, end of program, hand-off, end of a fade
     * or cross-fade), Int.MAX_VALUE if none is pending. A caller that keeps an exact position history splits its blocks
     * there.
     */
    fun framesUntilModeChange(): Int {
        var m = Long.MAX_VALUE
        if (mode == Mode.PROGRAM) {
            val p = program!!
            if (windowFrame < p.startFrame) m = min(m, p.startFrame - windowFrame)
            if (windowFrame < handoffAt) m = min(m, handoffAt - windowFrame)
            if (windowFrame < p.endFrame) m = min(m, p.endFrame - windowFrame)
        }
        if (mode == Mode.DIRECT && xfadeLeft > 0) m = min(m, xfadeLeft.toLong())
        if (mode != Mode.IDLE && fadeLeft > 0) m = min(m, fadeLeft.toLong())
        return if (m >= Int.MAX_VALUE) Int.MAX_VALUE else m.toInt()
    }

    /** Source frame (fractional) of the next output frame; NaN before a program's deck has started. */
    fun currentSourceFrame(): Double = when (mode) {
        Mode.IDLE -> parkedFrame.toDouble()
        Mode.DIRECT -> directPos.toDouble()
        Mode.PROGRAM -> {
            val p = program!!
            if (windowFrame < p.startFrame) Double.NaN else p.sourceFrameAt(windowFrame, sampleRate)
        }
    }

    /**
     * The REAL source frame of the next output frame: [currentSourceFrame], mapped back into a program's loop. What the
     * deck's ring must still hold, and where the deck resumes after a program is called off.
     */
    fun currentRealSourceFrame(): Double {
        val s = currentSourceFrame()
        val loop = program?.loop?.takeIf { mode == Mode.PROGRAM } ?: return s
        if (s.isNaN()) return s
        val v = floor(s).toLong()
        return loop.realFrame(v, sampleRate) + (s - v)
    }

    private fun currentSourceFrameRounded(): Long {
        val s = currentRealSourceFrame()
        return if (s.isNaN()) parkedFrame else floor(s + 0.5).toLong()
    }

    /**
     * Writes the next [count] output frames into dstL/dstR[offset until offset + count] (overwriting).
     * Returns how many of them advanced the source: [count] in PROGRAM mode, fewer in DIRECT mode when it stalls,
     * 0 when IDLE.
     */
    fun render(dstL: FloatArray, dstR: FloatArray, offset: Int, count: Int): Int {
        var done = 0
        var advanced = 0
        while (done < count) {
            val n0 = count - done
            val fadeSpan = if (fadeLeft > 0) min(n0, fadeLeft) else n0
            val off = offset + done
            val adv: Int
            val n: Int
            when (mode) {
                Mode.IDLE -> {
                    for (i in 0 until n0) { dstL[off + i] = 0f; dstR[off + i] = 0f }
                    return advanced
                }
                Mode.DIRECT -> {
                    val span = if (xfadeLeft > 0) min(fadeSpan, xfadeLeft) else fadeSpan
                    if (xfadeLeft > 0) {
                        renderCrossfade(dstL, dstR, off, span)
                        n = span; adv = span
                    } else {
                        val got = renderDirect(dstL, dstR, off, span)
                        n = span; adv = got
                    }
                }
                Mode.PROGRAM -> {
                    if (windowFrame >= handoffAt) {
                        handOff()
                        continue
                    }
                    // stop exactly at the hand-off so the next turn switches path there
                    val span = min(fadeSpan.toLong(), handoffAt - windowFrame).toInt()
                    renderProgram(dstL, dstR, off, span)
                    n = span; adv = span
                }
            }
            if (fadeLeft > 0) {
                applyFade(dstL, dstR, off, n)
                if (fadeLeft == 0 && fadingOut) {
                    // faded out: park where the source has got to and fall silent
                    done += n
                    advanced += adv
                    stop()
                    continue
                }
            }
            done += n
            advanced += adv
            if (mode == Mode.DIRECT && adv < n) {
                // stalled: the rest of the request stays silent and the position does not move
                for (i in done until count) { dstL[offset + i] = 0f; dstR[offset + i] = 0f }
                return advanced
            }
        }
        return advanced
    }

    private fun applyFade(dstL: FloatArray, dstR: FloatArray, off: Int, n: Int) {
        val len = fadeLen.toFloat()
        for (i in 0 until n) {
            val left = fadeLeft - i
            val g = if (fadingOut) (left - 1) / len else (len - left + 1) / len
            dstL[off + i] *= g
            dstR[off + i] *= g
        }
        fadeLeft -= n
    }

    /** Copies up to [n] frames at rate 1; returns how many were really there. */
    private fun renderDirect(dstL: FloatArray, dstR: FloatArray, off: Int, n: Int): Int {
        val src = source!!
        val avail = src.contiguousFrames(directPos, n).coerceIn(0, n)
        var i = 0
        while (i < avail) {
            val k = min(blockFrames, avail - i)
            src.read(directPos, k, tmp)
            val g = directGain
            for (j in 0 until k) {
                dstL[off + i + j] = tmp[0][j] * g
                dstR[off + i + j] = tmp[1][j] * g
            }
            directPos += k
            i += k
        }
        for (j in avail until n) { dstL[off + j] = 0f; dstR[off + j] = 0f }
        return avail
    }

    private fun renderProgram(dstL: FloatArray, dstR: FloatArray, off: Int, n: Int) {
        var i = 0
        while (i < n) {
            if (cacheStart == Long.MIN_VALUE || windowFrame >= cacheStart + blockFrames) {
                cacheStart = windowFrame
                cacheL.fill(0f)
                cacheR.fill(0f)
                mixBlock(cacheStart, blockFrames, cacheL, cacheR, cacheStart)
            }
            val at = (windowFrame - cacheStart).toInt()
            val k = min(n - i, blockFrames - at)
            System.arraycopy(cacheL, at, dstL, off + i, k)
            System.arraycopy(cacheR, at, dstR, off + i, k)
            windowFrame += k
            i += k
        }
    }

    /**
     * PROGRAM -> DIRECT once the lanes have settled on neutral values: the rate-1 path continues from the source frame
     * the vocoder is about to play, cross-fading from the vocoder over [HANDOFF_XFADE_FRAMES].
     */
    private fun handOff() {
        directPos = floor(stretcher.sourceFrameOfNextOutput() + 0.5).toLong()
        directGain = program!!.finalGain
        mode = Mode.DIRECT
        xfadeLen = HANDOFF_XFADE_FRAMES
        xfadeLeft = xfadeLen
    }

    private fun renderCrossfade(dstL: FloatArray, dstR: FloatArray, off: Int, n: Int) {
        val src = source!!
        var i = 0
        val g = directGain
        while (i < n) {
            val k = min(blockFrames, n - i)
            stretcher.render(xfBuf, 0, k)
            src.read(directPos, k, tmp)
            for (j in 0 until k) {
                val w = (xfadeLen - xfadeLeft + j) / xfadeLen.toFloat()
                val v0 = xfL[j] * g
                val v1 = xfR[j] * g
                dstL[off + i + j] = v0 + (tmp[0][j] * g - v0) * w
                dstR[off + i + j] = v1 + (tmp[1][j] * g - v1) * w
            }
            directPos += k
            xfadeLeft -= k
            i += k
        }
    }

    private fun ceilToGrid(f: Long): Long {
        val b = blockFrames.toLong()
        return Math.floorDiv(f + b - 1, b) * b
    }

    companion object {
        const val GAIN_STEP = 16

        /** Vocoder -> rate-1 cross-fade at a hand-off (~5 ms at 48 kHz). */
        const val HANDOFF_XFADE_FRAMES = 256
    }
}
