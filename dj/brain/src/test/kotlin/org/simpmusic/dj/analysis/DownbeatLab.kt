package org.simpmusic.dj.analysis

import kotlinx.serialization.json.*
import org.simpmusic.dj.audio.WavIo
import org.simpmusic.dj.model.PcmAudio
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test

/** Diagnostic: which downbeat features vote for the reference phase (skipped without DJ_CORPUS + DJ_REFS). */
class DownbeatLab {
    @Test
    fun run() {
        val dir = System.getenv("DJ_CORPUS")?.let { File(it) } ?: return
        val refs = System.getenv("DJ_REFS")?.let { File(it) } ?: return
        val an = DspTrackAnalyzer()
        val perFeature = LinkedHashMap<String, IntArray>() // feature -> [correct, total]
        for (rf in refs.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }) {
            val name = rf.nameWithoutExtension
            val f = File(dir, "${name}_22050.wav")
            if (!f.exists()) continue
            val j = Json.parseToJsonElement(rf.readText()).jsonObject
            val refDown = j["downbeatsSec"]!!.jsonArray.map { it.jsonPrimitive.double }
            val refBeats = j["beatsSec"]!!.jsonArray.map { it.jsonPrimitive.double }
            val wav = WavIo.read(f)
            val x = an.prepare(PcmAudio(wav.mono(), wav.sampleRate))
            val on = OnsetFeatures.compute(x)
            val sp = SpectralFeatures.compute(x)
            val raw = OnsetEnvelope.combined(on)
            val det = OnsetEnvelope.detrend(raw)
            val te = TempoEstimator.estimate(det, OnsetEnvelope.detrend(on.fluxLow)) ?: continue
            val br = BeatTracker.track(raw, det, te.bpm) ?: continue
            val beats = br.timesSec
            // only meaningful when our beats coincide with the reference beats
            var matched = 0
            for (b in beats) if (refBeats.any { abs(it - b) < 0.07 }) matched++
            if (matched < 0.8 * beats.size) { println("$name: beats do not match the reference (${matched}/${beats.size}), skipped"); continue }
            val bpb = 4
            val votes = IntArray(bpb)
            for (d in refDown) {
                val i = beats.indices.minByOrNull { abs(beats[it] - d) }!!
                if (abs(beats[i] - d) < 0.07) votes[i % bpb]++
            }
            val refPhase = votes.indices.maxByOrNull { votes[it] }!!
            val feat = DownbeatAnalyzer.beatFeatures(beats, on, sp)
            val res = DownbeatAnalyzer.analyze(beats, on, sp)!!
            val feats = linkedMapOf("chroma" to feat.chroma, "low" to feat.low, "total" to feat.total, "high" to feat.high, "lowStep" to feat.lowStep, "struct" to feat.struct, "accent" to res.accent)
            val sb = StringBuilder("$name refPhase=$refPhase (votes ${votes.toList()}) est=${res.phase} z=${"%.1f".format(res.phaseZ)} meter=${"%.2f".format(res.meterConfidence)} | ")
            for ((fn, arr) in feats) {
                val sums = DoubleArray(bpb); val cnt = IntArray(bpb)
                for (i in arr.indices) { sums[i % bpb] += arr[i]; cnt[i % bpb]++ }
                val means = DoubleArray(bpb) { sums[it] / cnt[it] }
                val best = means.indices.maxByOrNull { means[it] }!!
                val ok = best == refPhase
                sb.append("$fn:${if (ok) "OK" else "x$best"} ")
                val pf = perFeature.getOrPut(fn) { IntArray(2) }
                if (ok) pf[0]++
                pf[1]++
            }
            println(sb)
        }
        println(perFeature.entries.joinToString("  ") { "${it.key}=${it.value[0]}/${it.value[1]}" })
    }
}
