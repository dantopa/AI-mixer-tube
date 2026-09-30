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
                    if (p.kind != PlanKind.BEAT_MATCHED) why.merge(p.reason.take(150), 1, Int::plus)
                }
                println("$name plan kinds: $kinds")
                why.entries.sortedByDescending { it.value }.take(14).forEach { println("   ${it.value}x ${it.key}") }
            }
            tally("DSP only     ", a)
            tally("DSP+BeatThis ", b)

            // Render / grade beat-matched mixes built from the DSP+BeatThis analyses (regularity of the re-analysed overlap).
            val outDir = System.getenv("DJ_OUT")?.let(::File)
            val wavs = HashMap<String, WavIo.Wav>()
            fun wav(id: String) = wavs.getOrPut(id) { WavIo.read(corpus.resolve("audio").resolve(id + "_44100.wav")) }
            fun stereo(w: WavIo.Wav) = org.simpmusic.dj.render.StereoPcm.fromChannels(w.channels, w.sampleRate)
            val matched = ArrayList<Triple<String, String, TransitionPlan>>()
            for ((i, x) in b) for ((j, y) in b) if (i != j) planner.plan(x, y, settings).let { if (it.kind == PlanKind.BEAT_MATCHED) matched += Triple(i, j, it) }
            fun render(plan: TransitionPlan, i: String, j: String): org.simpmusic.dj.render.OfflineMixRenderer.Window {
                val lead = maxOf(8000L, -plan.preRollMs)
                return org.simpmusic.dj.render.OfflineMixRenderer.render(plan, org.simpmusic.dj.render.AudioSegment(stereo(wav(i))), org.simpmusic.dj.render.AudioSegment(stereo(wav(j))),
                    org.simpmusic.dj.render.OfflineMixRenderer.Options(leadMs = lead, tailMs = 8000L))
            }
            fun cv(win: org.simpmusic.dj.render.OfflineMixRenderer.Window, plan: TransitionPlan): Double {
                val sr = win.audio.sampleRate
                val mono = FloatArray(win.frames) { (win.audio.left[it] + win.audio.right[it]) * 0.5f }
                val lead = maxOf(8000L, -plan.preRollMs)
                val from = (lead * sr / 1000).toInt().coerceIn(0, mono.size - 1)
                val to = ((lead + plan.overlapMs) * sr / 1000).toInt().coerceIn(from + 1, mono.size)
                val beats = try { dsp.analyze("mix", PcmAudio(mono.copyOfRange(from, to), sr)).beatTimesMs?.value } catch (e: Exception) { null } ?: return 9.0
                if (beats.size < 6) return 9.0
                val ibi = beats.zipWithNext { p, q -> (q - p).toDouble() }
                val m = ibi.average()
                return kotlin.math.sqrt(ibi.sumOf { (it - m) * (it - m) } / ibi.size) / m
            }
            val sample = matched.filterIndexed { k, _ -> k % maxOf(1, matched.size / 12) == 0 }.take(12)
            var better = 0
            var worse = 0
            for ((i, j, plan) in sample) {
                val dj = render(plan, i, j)
                val xf = planner.plan(b[i], b[j], settings.copy(minConfidence = 1.1f))
                val xw = render(xf, i, j)
                val c1 = cv(dj, plan)
                val c2 = cv(xw, xf)
                if (c1 < c2) better++ else worse++
                println("REG %-22s -> %-22s DJ cv=%.3f xfade cv=%.3f".format(i.take(22), j.take(22), c1, c2))
            }
            println("REGULARITY (DSP+BeatThis matched sample): DJ better=$better worse=$worse of ${sample.size}")
            if (outDir != null) {
                outDir.mkdirs()
                val seen = HashSet<String>()
                var n = 0
                for ((i, j, plan) in matched) {
                    if (n >= 6) break
                    if (!seen.add(i) && matched.size > 12) continue
                    val win = render(plan, i, j)
                    val f = File(outDir, "${i}__${j}__dj.wav")
                    WavIo.write(f, WavIo.Wav(arrayOf(win.audio.left, win.audio.right), win.audio.sampleRate))
                    println("MIX ${f.name} :: ${plan.reason.take(150)}")
                    n++
                }
            }
        }
    }
}
