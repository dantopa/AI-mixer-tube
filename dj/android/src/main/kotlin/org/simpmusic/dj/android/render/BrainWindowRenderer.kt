package org.simpmusic.dj.android.render

import org.simpmusic.dj.audio.WavIo
import org.simpmusic.dj.model.settleMs
import org.simpmusic.dj.render.AudioSegment
import org.simpmusic.dj.render.OfflineMixRenderer
import java.io.File
import java.io.IOException
import org.simpmusic.dj.render.StereoPcm as BrainPcm

/**
 * [TransitionWindowRenderer] backed by the brain's [OfflineMixRenderer]: streaming phase-vocoder stretch, biquads and the
 * plan's automation lanes. Pure Kotlin, so it runs on Android exactly as it does in the JVM tests.
 *
 * Window contract: sample 0 is plan time `startRelMs` (the renderer's `leadMs = -startRelMs`), the last sample is
 * `endRelMs` (`tailMs = endRelMs - settleMs`), and the mix is written as PCM16 stereo to a temp name then renamed.
 */
class BrainWindowRenderer : TransitionWindowRenderer {
    override fun render(request: RenderRequest): RenderedWindow {
        val plan = request.plan
        val tailMs = request.endRelMs - plan.settleMs
        if (tailMs < 0) throw IOException("window ends (${request.endRelMs} ms) before the plan settles (${plan.settleMs} ms)")
        val leadMs = (-request.startRelMs).coerceAtLeast(0L)
        val window = OfflineMixRenderer.render(
            plan,
            segment(request.outgoingTail, request.sampleRate),
            segment(request.incomingHead, request.sampleRate),
            OfflineMixRenderer.Options(leadMs = leadMs, tailMs = tailMs),
        )
        request.output.parentFile?.mkdirs()
        val tmp = File(request.output.parentFile, request.output.name + ".tmp")
        try {
            WavIo.write(tmp, WavIo.Wav(arrayOf(window.audio.left, window.audio.right), window.sampleRate))
            if (!tmp.renameTo(request.output)) throw IOException("cannot move ${tmp.name} into place")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        return RenderedWindow(request.output, window.sampleRate, window.frames.toLong())
    }

    private fun segment(pcm: StereoPcm, sampleRate: Int): AudioSegment {
        if (pcm.sampleRate != sampleRate) throw IOException("decoded at ${pcm.sampleRate} Hz but the window is $sampleRate Hz")
        val n = pcm.frames
        val l = FloatArray(n)
        val r = FloatArray(n)
        val src = pcm.interleaved
        for (i in 0 until n) {
            l[i] = src[2 * i]
            r[i] = src[2 * i + 1]
        }
        return AudioSegment(BrainPcm(l, r, sampleRate), pcm.startMs)
    }
}
