package org.simpmusic.dj.ml

import kotlinx.serialization.json.Json
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
 * Manual, real music. Does the beat grid actually put the two decks' kicks on top of each other?
 * Each deck is rendered SOLO from the same plan (tempo lanes applied, mixer bypassed); low-band onsets of both are
 * detected in the overlap and every onset of one deck is matched to the nearest of the other. If the alignment is real
 * most match within a few ms; the baseline shifts one deck by a third of a beat, which is what a wrong grid looks like.
 *
 * Needs DJ_CORPUS-style env: BEAT_THIS_MODEL, BEAT_THIS_CORPUS (and optionally DJ_CACHE to keep analyses between runs).
 */
class RealMixQuality {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun lowBandOnsets(x: FloatArray, sr: Int, fromFrame: Int, toFrame: Int): List<Double> {
        // 2-pole low-pass at ~150 Hz, 5 ms energy hops, half-wave flux, adaptive threshold, 120 ms refractory.
        val a = Math.exp(-2 * Math.PI * 150.0 / sr)
        var y1 = 0.0
        var y2 = 0.0
        val hop = sr / 200
        val n = (toFrame - fromFrame) / hop
        val env = DoubleArray(n)
        for (i in 0 until n) {
            var e = 0.0
            for (k in 0 until hop) {
                val s = x[fromFrame + i * hop + k].toDouble()
                y1 = a * y1 + (1 - a) * s
                y2 = a * y2 + (1 - a) * y1
                e += y2 * y2
            }
            env[i] = sqrt(e / hop)
        }
        val flux = DoubleArray(n) { if (it == 0) 0.0 else maxOf(0.0, env[it] - env[it - 1]) }
        val mean = flux.average()
        val sd = sqrt(flux.sumOf { (it - mean) * (it - mean) } / n.coerceAtLeast(1))
        val thr = mean + 1.5 * sd
        val out = ArrayList<Double>()
        var last = -1000.0
        for (i in 1 until n - 1) {
            if (flux[i] > thr && flux[i] >= flux[i - 1] && flux[i] >= flux[i + 1]) {
                val t = i * 5.0
                if (t - last >= 120.0) { out += t; last = t }
            }
        }
        return out
    }

    private fun matchRate(a: List<Double>, b: List<Double>, shift: Double, tol: Double): Pair<Double, Double> {
        if (a.isEmpty() || b.isEmpty()) return 0.0 to 0.0
        val d = a.map { t -> b.minOf { abs(it + shift - t) } }
        return d.count { it <= tol }.toDouble() / d.size to d.sorted()[d.size / 2]
    }

    @Test
    fun run() {
        val model = System.getenv("BEAT_THIS_MODEL")?.let(::File)?.takeIf { it.exists() } ?: return println("skipped: no model")
        val corpus = System.getenv("BEAT_THIS_CORPUS")?.let(::File) ?: return println("skipped: no corpus")
        val cache = System.getenv("DJ_CACHE")?.let(::File)?.also { it.mkdirs() }
        val files = corpus.resolve("audio").listFiles { f -> f.name.endsWith("_44100.wav") }!!.sortedBy { it.name }
        val dsp = DspTrackAnalyzer()
        val audio = HashMap<String, StereoPcm>()
        val ana = LinkedHashMap<String, TrackAnalysis>()
        OnnxBeatModel.fromFile(model, threads = 2).use { onnx ->
            val composite = CompositeAnalyzer(dsp, BeatThisTracker(onnx))
            for (f in files) {
                val id = f.name.removeSuffix("_44100.wav")
                val w = WavIo.read(f)
                audio[id] = StereoPcm.fromMono(w.mono(), w.sampleRate)
                val c = cache?.resolve("$id.json")
                ana[id] = if (c != null && c.exists()) json.decodeFromString(c.readText())
                else composite.analyze(id, PcmAudio(w.mono(), w.sampleRate)).also { c?.writeText(json.encodeToString(it)) }
            }
        }
        val planner = DjTransitionPlanner()
        val settings = DjSettings(enabled = true)
        var pairs = 0
        var skipped = 0
        val aligned = ArrayList<Double>()
        val shifted = ArrayList<Double>()
        val med = ArrayList<Double>()
        val worst = ArrayList<Triple<String, String, Double>>()
        for ((ia, a) in ana) for ((ib, b) in ana) {
            if (ia == ib) continue
            val plan = planner.plan(a, b, settings)
            if (plan.kind != PlanKind.BEAT_MATCHED) continue
            val lead = maxOf(4000L, -plan.preRollMs)
            fun solo(s: OfflineMixRenderer.Solo) = OfflineMixRenderer.render(
                plan, AudioSegment(audio.getValue(ia)), AudioSegment(audio.getValue(ib)),
                OfflineMixRenderer.Options(leadMs = lead, tailMs = 2000L, solo = s, bypassMixer = true),
            ).audio
            val o = solo(OfflineMixRenderer.Solo.OUTGOING)
            val i = solo(OfflineMixRenderer.Solo.INCOMING)
            val sr = o.sampleRate
            val from = (lead * sr / 1000).toInt()
            val to = (from + (plan.overlapMs * sr / 1000)).toInt().coerceAtMost(minOf(o.frames, i.frames) - 1)
            if (to - from < sr * 4) { skipped++; continue }
            val ko = lowBandOnsets(o.left, sr, from, to)
            val ki = lowBandOnsets(i.left, sr, from, to)
            if (ko.size < 6 || ki.size < 6) { skipped++; continue }
            val beatMs = (plan.mixBpm ?: 120f).let { 60000.0 / it }
            val (r0, m0) = matchRate(ko, ki, 0.0, 25.0)
            val (r1, _) = matchRate(ko, ki, beatMs / 3, 25.0)
            pairs++
            aligned += r0; shifted += r1; med += m0
            worst += Triple(ia, ib, r0)
        }
        println("pairs graded: $pairs (skipped, too few kicks: $skipped)")
        println("kick coincidence within 25 ms:  DJ plan ${"%.0f".format(aligned.average() * 100)}%   vs 1/3-beat offset ${"%.0f".format(shifted.average() * 100)}%")
        println("median kick offset: ${"%.1f".format(med.sorted()[med.size / 2])} ms")
        worst.sortedBy { it.third }.take(5).forEach { println("  weakest: ${it.first} -> ${it.second}: ${"%.0f".format(it.third * 100)}%") }
    }
}
