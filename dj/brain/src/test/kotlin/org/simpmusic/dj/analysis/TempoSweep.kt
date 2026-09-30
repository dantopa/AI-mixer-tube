package org.simpmusic.dj.analysis

import kotlinx.serialization.json.*
import org.simpmusic.dj.SyntheticTracks
import org.simpmusic.dj.audio.WavIo
import org.simpmusic.dj.model.*
import java.io.File
import kotlin.math.abs
import kotlin.test.Test

/** Parameter sweep of the tempo decision over the real corpus + synthetic tracks (skipped without DJ_CORPUS). */
class TempoSweep {
    private class Item(val name: String, val det: FloatArray, val low: FloatArray, val truth: Double, val synthetic: Boolean)

    private fun items(): List<Item> {
        val dir = System.getenv("DJ_CORPUS")?.let { File(it) } ?: return emptyList()
        val refs = System.getenv("DJ_REFS")?.let { File(it) }
        val an = DspTrackAnalyzer()
        val out = ArrayList<Item>()
        val published = mapOf(
            "Cyborg_Ninja" to 160.0, "Disco_Medusae" to 115.0, "Volatile_Reaction" to 155.0, "Monkeys_Spinning_Monkeys" to 144.0,
            "Hitman" to 76.0, "Local_Forecast_-_Elevator" to 82.0, "Sneaky_Snitch" to 87.0, "Kool_Kats" to 113.0, "Funkorama" to 101.0,
        )
        val files = ArrayList<Triple<String, File, Double>>()
        for ((n, b) in published) files.add(Triple(n, File(dir, "$n.wav"), b))
        refs?.listFiles { f -> f.extension == "json" }?.sortedBy { it.name }?.forEach {
            val name = it.nameWithoutExtension
            if (name == "canon_in_d" || name.startsWith("waltz") || name == "funkorama" || name == "disco_con_tutti") return@forEach
            files.add(Triple(name, File(dir, "${name}_22050.wav"), Json.parseToJsonElement(it.readText()).jsonObject["bpm"]!!.jsonPrimitive.double))
        }
        for ((n, f, b) in files) {
            if (!f.exists()) continue
            val wav = WavIo.read(f)
            val x = an.prepare(PcmAudio(wav.mono(), wav.sampleRate))
            val on = OnsetFeatures.compute(x)
            out.add(Item(n, OnsetEnvelope.detrend(OnsetEnvelope.combined(on)), OnsetEnvelope.detrend(on.fluxLow), b, false))
        }
        for (bpm in listOf(70f, 90f, 100f, 118f, 124f, 128f, 140f, 150f, 174f)) {
            val r = SyntheticTracks.render(SyntheticTracks.Spec(bpm = bpm, key = MusicalKey(0, Mode.MAJOR)))
            val x = an.prepare(r.audio)
            val on = OnsetFeatures.compute(x)
            out.add(Item("syn$bpm", OnsetEnvelope.detrend(OnsetEnvelope.combined(on)), OnsetEnvelope.detrend(on.fluxLow), bpm.toDouble(), true))
        }
        return out
    }

    private fun ok(est: Double, t: Double) = abs(est / t - 1) < 0.04

    @Test
    fun sweep() {
        val its = items()
        if (its.isEmpty()) return
        data class Res(val label: String, val strict: Int, val lenient: Int, val synStrict: Int, val detail: String)
        val results = ArrayList<Res>()
        for (center in listOf(110.0, 120.0, 130.0)) for (sigma in listOf(0.7, 1.0, 1.5)) for (gamma in listOf(0.0, 1.0)) for (maxK in listOf(4, 8)) for (dp in listOf(0.0, 0.5, 1.0, 2.0)) for (low in listOf(true)) {
            val prm = TempoParams(priorCenter = center, priorSigmaOct = sigma, gamma = gamma, maxMultiples = maxK, useLowBand = low, directPower = dp)
            var strict = 0; var len = 0; var synS = 0
            val bad = ArrayList<String>()
            for (it in its) {
                val e = TempoEstimator.estimate(it.det, it.low, prm)?.bpm ?: 0.0
                val s = ok(e, it.truth)
                val l = s || ok(e, it.truth * 2) || ok(e, it.truth / 2)
                if (s) { strict++; if (it.synthetic) synS++ } else bad.add("${it.name}:${"%.0f".format(e)}/${"%.0f".format(it.truth)}")
                if (l) len++
            }
            results.add(Res("c=$center s=$sigma g=$gamma K=$maxK dp=$dp", strict, len, synS, bad.joinToString(" ")))
        }
        results.sortWith(compareByDescending<Res> { it.strict }.thenByDescending { it.synStrict })
        println("items=${its.size} (9 synthetic)")
        for (r in results.take(14)) println("${r.label}: strict=${r.strict} lenient=${r.lenient} syn=${r.synStrict} bad=${r.detail}")
        val cur = results.firstOrNull { it.label == "c=120.0 s=1.0 g=0.0 K=8 dp=0.0" }
        println("CURRENT ${cur?.label}: strict=${cur?.strict} lenient=${cur?.lenient} syn=${cur?.synStrict} bad=${cur?.detail}")
    }
}
