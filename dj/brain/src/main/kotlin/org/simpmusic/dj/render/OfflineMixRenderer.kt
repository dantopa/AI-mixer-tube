package org.simpmusic.dj.render

import org.simpmusic.dj.model.DeckClock
import org.simpmusic.dj.model.DeckPlan
import org.simpmusic.dj.model.EchoOutSpec
import org.simpmusic.dj.model.ParamCurve
import org.simpmusic.dj.model.PcmAudio
import org.simpmusic.dj.model.TransitionPlan
import org.simpmusic.dj.model.settleMs
import org.simpmusic.dj.model.silentFromMs
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.tanh

/**
 * Executes a [TransitionPlan] exactly as specified, offline: the executable specification of the lane semantics,
 * and the engine that pre-renders a transition window on device.
 *
 * Model (identical to the plan's KDoc):
 *  - wall clock `t` is measured in ms relative to T0; sample `n0` of the output is T0;
 *  - each deck's SOURCE position is its anchor plus the integral of its `rate` lane over wall time
 *    (outgoing anchored `exitPointMs` at T0, incoming `entryPointMs` at T0), so a source position is a pure
 *    function of the lanes - see [DeckClock];
 *  - the incoming deck only plays from t = 0; the outgoing deck plays from the window start until its volume lane
 *    is 0 for good;
 *  - per deck: time-stretch/pitch ([DeckStretcher]) -> low-cut -> high-cut ([SweepFilter]) -> channel fader
 *    (volume lane) -> master sum -> master safety soft-clip (only above 0.95);
 *  - an [EchoOutSpec] on the plan adds an aux send on the OUTGOING deck: [BeatEcho] is fed from the stretched signal
 *    BEFORE the deck's filters and fader (send lane, like a send ahead of the channel EQ), and its wet return (wet lane)
 *    is added AFTER the fader, so the tail keeps ringing once the fader has closed and the rising high-pass thins
 *    only the dry signal; the deck's source stops at the end of the send/fader and the delay is fed silence.
 *
 * The mixer graph is the loop in [render]; a per-deck or master effect (echo, reverb, beds) is one more stage there.
 */
object OfflineMixRenderer {
    enum class Solo { OUTGOING, INCOMING }

    /**
     * @param leadMs wall-clock ms rendered before T0; null = exactly the plan's pre-roll (`-preRollMs`), which makes
     *   the first output sample plan time `-preRollMs`
     * @param tailMs wall-clock ms rendered after the last lane has settled ([settleMs]); by then the incoming deck
     *   runs at native rate 1.0 (its rate lane ends at 1.0). Keep it >= [RELOCK_MS] to include [Window.handoffMs]
     * @param solo render only one deck (analysis / tests)
     * @param bypassMixer ignore volume and filter lanes (keep rate/pitch), decks at unity gain; the outgoing deck then
     *   plays until the window end instead of stopping when its fader closes
     * @param outgoingEffect / incomingEffect / masterEffect optional [BlockEffect] hooks (not part of the plan)
     * @param masterLimiter apply the master soft-clip (identity below 0.95, never exceeds 1.0)
     */
    /** A per-block audio effect hook (echo, reverb, ...): processes l/r in place, `count` frames from `offset`. */
    fun interface BlockEffect {
        fun process(l: FloatArray, r: FloatArray, offset: Int, count: Int)
    }

    data class Options(
        val leadMs: Long? = null,
        val tailMs: Long = DEFAULT_TAIL_MS,
        val solo: Solo? = null,
        val bypassMixer: Boolean = false,
        val masterLimiter: Boolean = true,
        val blockFrames: Int = 256,
        /** Insert points for later effects: after each deck's filters and before its fader, and on the master sum. */
        val outgoingEffect: BlockEffect? = null,
        val incomingEffect: BlockEffect? = null,
        val masterEffect: BlockEffect? = null,
    )

    /** Rendered transition window plus the wall-time <-> source-position mapping. */
    class Window(
        val audio: StereoPcm,
        /** Plan time (ms relative to T0) of output frame 0. */
        val startMs: Double,
        val plan: TransitionPlan,
        private val outgoingClock: DeckClock,
        private val incomingClock: DeckClock,
    ) {
        val sampleRate: Int get() = audio.sampleRate
        val frames: Int get() = audio.frames

        /**
         * Plan time from which the window's incoming audio IS the native (rate 1.0, unshifted) source, sample for sample:
         * the incoming rate lane has settled at 1.0 and the vocoder's phases have re-locked onto the source
         * ([RELOCK_MS]). A live incoming player can take over here without a tempo or waveform seam; if the window is
         * cut earlier the hand-off is still phase-continuous but not waveform-identical.
         */
        val handoffMs: Double get() = plan.settleMs + RELOCK_MS
        val handoffFrame: Int get() = frameOfPlanTime(handoffMs)

        /** INCOMING track position (ms) at the hand-off point: where a live player must be seeked to. */
        val handoffIncomingSourceMs: Double get() = incomingSourceMs(handoffMs)

        /** Output frame of T0. */
        val t0Frame: Int get() = (-startMs * sampleRate / 1000.0).roundToInt()

        /** Plan time (ms relative to T0) of output [frame]. */
        fun planTimeMs(frame: Int): Double = startMs + frame * 1000.0 / sampleRate
        fun frameOfPlanTime(tMs: Double): Int = ((tMs - startMs) * sampleRate / 1000.0).roundToInt()
        val endMs: Double get() = planTimeMs(frames)

        /** OUTGOING track position (ms) at plan time [tMs]. */
        fun outgoingSourceMs(tMs: Double): Double = outgoingClock.sourceAt(tMs)

        /** INCOMING track position (ms) at plan time [tMs] (before T0 the deck is not playing; value extrapolated). */
        fun incomingSourceMs(tMs: Double): Double = incomingClock.sourceAt(tMs)

        fun outgoingSourceMsAtFrame(frame: Int): Double = outgoingSourceMs(planTimeMs(frame))
        fun incomingSourceMsAtFrame(frame: Int): Double = incomingSourceMs(planTimeMs(frame))

        /** Plan time at which the outgoing deck plays source position [sourceMs]. */
        fun outgoingWallMs(sourceMs: Double): Double = outgoingClock.wallAt(sourceMs)
        fun incomingWallMs(sourceMs: Double): Double = incomingClock.wallAt(sourceMs)
    }

    /** Convenience for tests and the CLI: whole tracks in, stereo window out (starts [leadMs] before T0). */
    fun render(plan: TransitionPlan, fromPcm: PcmAudio, toPcm: PcmAudio, leadMs: Long, tailMs: Long): StereoPcm =
        render(plan, AudioSegment.of(fromPcm), AudioSegment.of(toPcm), Options(leadMs = leadMs, tailMs = tailMs)).audio

    fun render(plan: TransitionPlan, outgoing: AudioSegment, incoming: AudioSegment, options: Options = Options()): Window {
        val sr = outgoing.pcm.sampleRate
        require(incoming.pcm.sampleRate == sr) { "both decks must share one sample rate (${sr} vs ${incoming.pcm.sampleRate})" }
        val leadMs = (options.leadMs ?: -plan.preRollMs).coerceAtLeast(0L)
        val n0 = (leadMs * sr / 1000.0).roundToInt()
        val startWall = -n0 * 1000.0 / sr
        val endWall = (plan.settleMs + max(0L, options.tailMs)).toDouble()
        require(endWall <= MAX_WINDOW_MS && leadMs <= MAX_WINDOW_MS) { "transition window too long: lead $leadMs ms, end $endWall ms" }
        val total = n0 + ceil(endWall * sr / 1000.0).toInt()

        val outClock = DeckClock(plan.outgoing.rate, plan.exitPointMs.toDouble(), floor(startWall).toLong() - 2, ceil(endWall).toLong() + 2)
        val inClock = DeckClock(plan.incoming.rate, plan.entryPointMs.toDouble(), -1L, ceil(endWall).toLong() + 2)

        val block = options.blockFrames.coerceIn(32, 4096)
        val mixL = FloatArray(total)
        val mixR = FloatArray(total)

        val outRunner = if (options.solo != Solo.INCOMING) {
            val silentAt = if (options.bypassMixer) null else plan.outgoing.volume.silentFromMs()
            fun frameOf(ms: Long?): Int = if (ms == null) total else min(total.toDouble(), max(0.0, n0 + ceil(ms * sr / 1000.0))).toInt()
            val echoSpec = if (options.bypassMixer) null else plan.echoOut
            // with an echo the deck's fader may close long before the delay has rung out: the runner keeps going to the
            // window end and only feeds the delay silence once the source is finished
            val activeEnd = if (echoSpec == null) frameOf(silentAt) else max(frameOf(silentAt), frameOf(echoSpec.send.silentFromMs()))
            val endFrame = if (echoSpec == null) activeEnd else total
            Runner(
                sr, outgoing, outClock, plan.outgoing, startFrame = 0, startWallMs = startWall, endFrame = endFrame,
                outputStartWallMs = startWall, block = block, effect = options.outgoingEffect,
                echoSpec = echoSpec, activeEndFrame = activeEnd,
            )
        } else null
        val inRunner = if (options.solo != Solo.OUTGOING) {
            Runner(sr, incoming, inClock, plan.incoming, startFrame = n0, startWallMs = 0.0, endFrame = total, outputStartWallMs = startWall, block = block, effect = options.incomingEffect)
        } else null

        var g = 0
        while (g < total) {
            val n = min(block, total - g)
            outRunner?.renderBlock(g, n, mixL, mixR, options.bypassMixer)
            inRunner?.renderBlock(g, n, mixL, mixR, options.bypassMixer)
            g += n
        }
        options.masterEffect?.let { fx ->
            var i = 0
            while (i < total) {
                val n = min(block, total - i)
                fx.process(mixL, mixR, i, n)
                i += n
            }
        }
        if (options.masterLimiter) {
            for (i in 0 until total) {
                mixL[i] = softClip(mixL[i])
                mixR[i] = softClip(mixR[i])
            }
        }
        return Window(StereoPcm(mixL, mixR, sr), startWall, plan, outClock, inClock)
    }

    /** Identity below [KNEE]; above it a tanh shoulder that approaches (never reaches) 1.0. */
    internal fun softClip(x: Float): Float {
        val a = abs(x)
        if (a <= KNEE) return x
        val y = KNEE + (1f - KNEE) * tanh((a - KNEE) / (1f - KNEE))
        return if (x < 0f) -y else y
    }

    private const val KNEE = 0.95f

    /** Time the vocoder needs after the rate lane settles to converge onto the source waveform (measured: -120 dB residual). */
    const val RELOCK_MS = 300.0

    /** Default tail: long enough to contain the hand-off point. */
    const val DEFAULT_TAIL_MS = 500L
    private const val MAX_WINDOW_MS = 30 * 60_000.0

    // ---------------------------------------------------------------------------------------------

    private class Runner(
        val sr: Int,
        val segment: AudioSegment,
        val clock: DeckClock,
        val lanes: DeckPlan,
        val startFrame: Int,
        val startWallMs: Double,
        val endFrame: Int,
        val outputStartWallMs: Double,
        block: Int,
        val effect: BlockEffect? = null,
        val echoSpec: EchoOutSpec? = null,
        /** Frames at or after this one get no source audio (echo tail only). */
        val activeEndFrame: Int = endFrame,
    ) : DeckStretcher.Automation {
        private val stretcher = DeckStretcher(2, sr)
        private val segStart = (segment.sourceStartMs * sr / 1000.0).roundToLong()
        private val tmp = arrayOf(FloatArray(block), FloatArray(block))
        private val hp = SweepFilter(sr, highpass = true)
        private val lp = SweepFilter(sr, highpass = false)
        private val msPerFrame = 1000.0 / sr
        private var started = false
        private val gainKnots = FloatArray(block / GAIN_STEP + 2)
        private val pcmL = segment.pcm.left
        private val pcmR = segment.pcm.right
        private val useHp = lanes.lowCutHz.keys.any { it.value > 20.5f }
        private val useLp = lanes.highCutHz.keys.any { it.value < 19_500f }
        private val echo: BeatEcho? = echoSpec?.let { BeatEcho(sr, it.delayMs.toDouble(), it.feedback.toDouble(), it.highpassHz.toDouble(), it.dampHz.toDouble(), it.pingPong) }
        private val sendL = FloatArray(if (echoSpec != null) block else 0)
        private val sendR = FloatArray(if (echoSpec != null) block else 0)
        private val wetL = FloatArray(if (echoSpec != null) block else 0)
        private val wetR = FloatArray(if (echoSpec != null) block else 0)
        private val laneGain = FloatArray(if (echoSpec != null) block else 0)

        private val source = DeckStretcher.Source { start, count, dst ->
            val d0 = dst[0]
            val d1 = dst[1]
            for (i in 0 until count) {
                val p = start + i - segStart
                if (p < 0 || p >= pcmL.size) {
                    d0[i] = 0f; d1[i] = 0f
                } else {
                    d0[i] = pcmL[p.toInt()]; d1[i] = pcmR[p.toInt()]
                }
            }
        }

        private fun wallOfDeckFrame(outFrame: Double) = startWallMs + outFrame * msPerFrame

        override fun rate(outFrame: Double): Double = lanes.rate.valueAt(wallOfDeckFrame(outFrame)).toDouble()
        override fun pitchRatio(outFrame: Double): Double = 2.0.pow(lanes.pitchSemitones.valueAt(wallOfDeckFrame(outFrame)) / 12.0)

        fun renderBlock(g0: Int, count: Int, mixL: FloatArray, mixR: FloatArray, bypass: Boolean) {
            if (g0 + count <= startFrame || g0 >= endFrame) return
            val from = max(g0, startFrame)
            val to = min(g0 + count, endFrame)
            val n = to - from
            if (!started) {
                stretcher.start(clock.sourceAt(startWallMs) * sr / 1000.0, source, this)
                started = true
            }
            val live = if (echo == null) n else (min(to, activeEndFrame) - from).coerceIn(0, n)
            if (live > 0) stretcher.render(tmp, 0, live)
            val l = tmp[0]
            val r = tmp[1]
            for (i in live until n) { l[i] = 0f; r[i] = 0f }
            if (!bypass) {
                val wall0 = outputStartWallMs + from * msPerFrame
                if (echo != null && echoSpec != null) {
                    // aux send: taken BEFORE the deck's own filters (like a send ahead of the channel EQ on a mixer), so the
                    // repeats carry the full signal while the dry signal thins out under the rising high-pass
                    fillLane(echoSpec.send, wall0, n)
                    for (i in 0 until n) { sendL[i] = l[i] * laneGain[i]; sendR[i] = r[i] * laneGain[i] }
                }
                if (useHp) hp.process(l, r, 0, n) { i -> lanes.lowCutHz.valueAt(wall0 + i * msPerFrame).toDouble() }
                if (useLp) lp.process(l, r, 0, n) { i -> lanes.highCutHz.valueAt(wall0 + i * msPerFrame).toDouble() }
                effect?.process(l, r, 0, n)
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
            for (i in 0 until n) {
                mixL[from + i] += l[i]
                mixR[from + i] += r[i]
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

        companion object {
            const val GAIN_STEP = 16
        }
    }
}
