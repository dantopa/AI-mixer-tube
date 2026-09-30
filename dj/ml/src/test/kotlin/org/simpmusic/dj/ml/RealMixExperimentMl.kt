package org.simpmusic.dj.ml

import org.simpmusic.dj.analysis.DspTrackAnalyzer
import org.simpmusic.dj.audio.WavIo
import org.simpmusic.dj.model.*
import org.simpmusic.dj.planner.DjTransitionPlanner
import java.io.File
import kotlin.test.Test

/** Manual: DSP-only vs DSP + Beat This! on the real corpus (needs BEAT_THIS_MODEL and BEAT_THIS_CORPUS). */
class RealMixExperimentMl {
    @Test
    fun compare() {
        val model = System.getenv("BEAT_THIS_MODEL")?.let(::File)?.takeIf { it.exists() } ?: return println("skipped: no model")
        val corpus = System.getenv("BEAT_THIS_CORPUS")?.let(::File) ?: return println("skipped: no corpus")
        val files = corpus.resolve("audio").listFiles { f -> f.name.endsWith("_44100.wav") }!!.sortedBy { it.name }
        val dsp = DspTrackAnalyzer()
        val planner = DjTransitionPlanner()
        val settings = DjSettings(enabled = true)
        OnnxBeatModel.fromFile(model, threads = 2).use { onnx ->
            val composite = CompositeAnalyzer(dsp, BeatThisTracker(onnx))
            val a = LinkedHashMap<String, TrackAnalysis>()
            val b = LinkedHashMap<String, TrackAnalysis>()
            for (f in files) {
                val id = f.name.removeSuffix("_44100.wav")
                val w = WavIo.read(f)
                val pcm = PcmAudio(w.mono(), w.sampleRate)
                a[id] = dsp.analyze(id, pcm)
                val t0 = System.nanoTime()
                b[id] = composite.analyze(id, pcm)
                val x = b.getValue(id)
                println("%-26s dsp bpm=%6.1f(%.2f) | +beatthis bpm=%6.1f beats=%.2f downb=%.2f  (%.1fs)".format(id,
                    a[id]!!.bpm?.value ?: 0f, a[id]!!.bpm?.confidence ?: 0f, x.bpm?.value ?: 0f,
                    x.beatTimesMs?.confidence ?: 0f, x.downbeatBeatIndices?.confidence ?: 0f, (System.nanoTime() - t0) / 1e9))
            }
            fun tally(name: String, m: Map<String, TrackAnalysis>) {
                val kinds = HashMap<PlanKind, Int>()
                val why = HashMap<String, Int>()
                for ((i, x) in m) for ((j, y) in m) if (i != j) {
                    val p = planner.plan(x, y, settings)
                    kinds.merge(p.kind, 1, Int::plus)
                    if (p.kind != PlanKind.BEAT_MATCHED) why.merge(p.reason.take(80), 1, Int::plus)
                }
                println("$name plan kinds: $kinds")
                why.entries.sortedByDescending { it.value }.take(5).forEach { println("   ${it.value}x ${it.key}") }
            }
            tally("DSP only     ", a)
            tally("DSP+BeatThis ", b)
        }
    }
}
