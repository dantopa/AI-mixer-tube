package org.simpmusic.dj

import org.simpmusic.dj.analysis.DspTrackAnalyzer
import org.simpmusic.dj.audio.WavIo
import org.simpmusic.dj.model.*
import org.simpmusic.dj.planner.DjTransitionPlanner
import org.simpmusic.dj.render.AudioSegment
import org.simpmusic.dj.render.OfflineMixRenderer
import org.simpmusic.dj.render.StereoPcm
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * Manual experiment on REAL music (skipped unless DJ_CORPUS points at a folder with the 44100 Hz WAVs under audio/).
 * Plans every ordered pair, tallies plan kinds and fallback reasons, renders a sample of beat-matched mixes and grades
 * them objectively: a re-analysis of the mixed audio must find ONE steady beat grid across the overlap.
 * The baseline is the same pair through the plain equal-power crossfade fallback.
 */
class RealMixExperiment {
    private val corpus = System.getenv("DJ_CORPUS")?.let(::File)
    private val out = System.getenv("DJ_OUT")?.let(::File)

    private fun load(f: File): PcmAudio { val w = WavIo.read(f); return PcmAudio(w.mono(), w.sampleRate) }

    @Test
    fun run() {
        val dir = corpus?.resolve("audio") ?: run { println("DJ_CORPUS not set: skipped"); return }
        val files = dir.listFiles { f -> f.name.endsWith("_44100.wav") }!!.sortedBy { it.name }
        val analyzer = DspTrackAnalyzer()
        val audio = HashMap<String, PcmAudio>()
        val ana = LinkedHashMap<String, TrackAnalysis>()
        for (f in files) {
            val id = f.name.removeSuffix("_44100.wav")
            val a = load(f)
            audio[id] = a
            ana[id] = analyzer.analyze(id, a)
            val t = ana[id]!!
            println("%-28s bpm=%6.1f(%.2f) beats=%.2f downb=%.2f key=%s(%.2f) sections=%s".format(
                id, t.bpm?.value ?: 0f, t.bpm?.confidence ?: 0f, t.beatTimesMs?.confidence ?: 0f,
                t.downbeatBeatIndices?.confidence ?: 0f, t.key?.value?.camelot() ?: "-", t.key?.confidence ?: 0f,
                t.sections?.value?.size ?: 0))
        }
        val planner = DjTransitionPlanner()
        val settings = DjSettings(enabled = true)
        val kinds = HashMap<PlanKind, Int>()
        val reasons = HashMap<String, Int>()
        val matched = ArrayList<Triple<String, String, TransitionPlan>>()
        for ((ia, a) in ana) for ((ib, b) in ana) {
            if (ia == ib) continue
            val p = planner.plan(a, b, settings)
            kinds.merge(p.kind, 1, Int::plus)
            if (p.kind != PlanKind.BEAT_MATCHED) reasons.merge(p.reason.take(70), 1, Int::plus)
            else matched += Triple(ia, ib, p)
        }
        println("PLAN KINDS over ${ana.size * (ana.size - 1)} ordered pairs: $kinds")
        reasons.entries.sortedByDescending { it.value }.take(8).forEach { println("  ${it.value}x  ${it.key}") }

        // Grade a deterministic sample of beat-matched mixes against the plain-crossfade baseline.
        val sample = matched.filterIndexed { i, _ -> i % maxOf(1, matched.size / 10) == 0 }.take(10)
        var better = 0
        var worse = 0
        for ((ia, ib, plan) in sample) {
            val fallback = planner.plan(ana[ia], ana[ib], settings.copy(minConfidence = 1.1f))
            val g1 = grade(ia, ib, plan, audio, analyzer)
            val g2 = grade(ia, ib, fallback, audio, analyzer)
            val verdict = if (g1.cv < g2.cv) { better++; "better" } else { worse++; "worse" }
            println("%-22s -> %-22s DJ cv=%.3f conf=%.2f | xfade cv=%.3f conf=%.2f | %s | %s".format(ia.take(22), ib.take(22), g1.cv, g1.conf, g2.cv, g2.conf, verdict, plan.reason.take(60)))
            out?.let { d ->
                d.mkdirs()
                WavIo.write(File(d, "${ia}__${ib}__dj.wav"), WavIo.Wav(arrayOf(g1.l, g1.r), g1.sr))
                WavIo.write(File(d, "${ia}__${ib}__xfade.wav"), WavIo.Wav(arrayOf(g2.l, g2.r), g2.sr))
            }
        }
        println("beat-grid regularity in the overlap, DJ vs plain crossfade: better=$better worse=$worse of ${sample.size}")
    }

    private class Grade(val cv: Double, val conf: Float, val l: FloatArray, val r: FloatArray, val sr: Int)

    private fun grade(ia: String, ib: String, plan: TransitionPlan, audio: Map<String, PcmAudio>, analyzer: DspTrackAnalyzer): Grade {
        fun st(p: PcmAudio) = StereoPcm.fromMono(p.samples, p.sampleRate)
        val lead = maxOf(8000L, -plan.preRollMs)
        val win = OfflineMixRenderer.render(plan, AudioSegment(st(audio.getValue(ia))), AudioSegment(st(audio.getValue(ib))), OfflineMixRenderer.Options(leadMs = lead, tailMs = 8000L))
        val sr = win.audio.sampleRate
        val mono = FloatArray(win.frames) { (win.audio.left[it] + win.audio.right[it]) * 0.5f }
        // analyse only the overlap region (+ a little context) of the mix
        val from = ((lead) * sr / 1000).toInt().coerceIn(0, mono.size - 1)
        val to = ((lead + plan.overlapMs) * sr / 1000).toInt().coerceIn(from + 1, mono.size)
        val seg = mono.copyOfRange(from, to)
        val an = analyzer.analyze("mix", PcmAudio(seg, sr))
        val beats = an.beatTimesMs?.value ?: return Grade(9.0, 0f, win.audio.left, win.audio.right, sr)
        if (beats.size < 6) return Grade(9.0, an.beatTimesMs.confidence, win.audio.left, win.audio.right, sr)
        val ibi = beats.zipWithNext { a, b -> (b - a).toDouble() }
        val mean = ibi.average()
        val sd = sqrt(ibi.sumOf { (it - mean) * (it - mean) } / ibi.size)
        return Grade(sd / mean, an.beatTimesMs.confidence, win.audio.left, win.audio.right, sr)
    }
}
