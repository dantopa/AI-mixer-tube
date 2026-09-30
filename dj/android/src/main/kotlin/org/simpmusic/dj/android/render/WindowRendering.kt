package org.simpmusic.dj.android.render

import org.simpmusic.dj.model.TransitionPlan
import java.io.File

/**
 * Full-quality decoded stereo audio of one range of a track.
 *
 * [interleaved] holds L,R,L,R... in -1..1; sample 0 sits at [startMs] on the track's SOURCE timeline
 * (i.e. before any tempo change), so a renderer can address audio by source position.
 */
class StereoPcm(
    val interleaved: FloatArray,
    val sampleRate: Int,
    val startMs: Long,
) {
    val frames: Int get() = interleaved.size / 2
    val durationMs: Long get() = frames * 1000L / sampleRate
    val endMs: Long get() = startMs + durationMs
}

/**
 * What to render. The window covers plan time [startRelMs, endRelMs] (see the plan's T0 convention):
 * sample 0 of the output is the mix at plan time `startRelMs`, sample N-1 the mix at `endRelMs`.
 *
 * Contract the app relies on (and the tests of any implementation must pin):
 *  1. At `startRelMs` the outgoing lane is the identity (plain track audio), and up to `plan.preRollMs`
 *     the output is the untouched outgoing audio from its source position at that instant
 *     (== `outgoingTail.startMs + margin`, the renderer derives it from the plan's rate lane integral).
 *  2. From the moment the lanes settle to `endRelMs` the output is the incoming track alone at rate 1.0,
 *     unfiltered, at the plan's final incoming gain.
 *  3. Output is [sampleRate] Hz, 2 channels, sample-accurate w.r.t. the plan (no start latency, no padding).
 */
class RenderRequest(
    val plan: TransitionPlan,
    val startRelMs: Long,
    val endRelMs: Long,
    val outgoingTail: StereoPcm,
    val incomingHead: StereoPcm,
    val sampleRate: Int,
    /** Where to write the WAV (PCM16 stereo). Written to a temp name and renamed, never half-visible. */
    val output: File,
)

class RenderedWindow(
    val file: File,
    val sampleRate: Int,
    val frames: Long,
) {
    val durationMs: Long get() = frames * 1000L / sampleRate
}

/**
 * Renders a transition plan to a WAV. Blocking and CPU heavy: call off the main thread. The real
 * implementation is `org.simpmusic.dj.render.OfflineMixRenderer` from the brain module (streaming
 * time-stretch, biquads, automation lanes); this interface is the only thing the app depends on.
 */
interface TransitionWindowRenderer {
    @Throws(java.io.IOException::class)
    fun render(request: RenderRequest): RenderedWindow
}

/** Thrown by [UnavailableWindowRenderer]; the engine treats it as "use the plain crossfade". */
class RendererUnavailableException(message: String) : UnsupportedOperationException(message)

/** Placeholder until the offline renderer is wired: every render request fails with a documented exception. */
class UnavailableWindowRenderer : TransitionWindowRenderer {
    override fun render(request: RenderRequest): RenderedWindow =
        throw RendererUnavailableException("No TransitionWindowRenderer installed: bind org.simpmusic.dj.render.OfflineMixRenderer in DjModule")
}
