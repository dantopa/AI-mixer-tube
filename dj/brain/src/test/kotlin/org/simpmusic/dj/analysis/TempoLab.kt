package org.simpmusic.dj.analysis

import kotlinx.serialization.json.*
import org.simpmusic.dj.audio.WavIo
import org.simpmusic.dj.model.PcmAudio
import java.io.File
import kotlin.test.Test

/** Diagnostic: tempo candidates and octave evidence on the real corpus (skipped without DJ_CORPUS). */
class TempoLab {
    private val published = mapOf(
        "Cyborg_Ninja" to 160.0, "Disco_Medusae" to 115.0, "Volatile_Reaction" to 155.0, "Monkeys_Spinning_Monkeys" to 144.0,
        "Hitman" to 76.0, "Local_Forecast_-_Elevator" to 82.0, "Sneaky_Snitch" to 87.0, "Kool_Kats" to 113.0, "Funkorama" to 101.0,
    )

    @Test
    fun run() {
        val dir = System.getenv("DJ_CORPUS")?.let { File(it) } ?: return
        val refs = System.getenv("DJ_REFS")?.let { File(it) }
        val an = DspTrackAnalyzer()
        val truth = LinkedHashMap<String, Pair<File, Double>>()
        for ((n, b) in published) truth[n] = File(dir, "$n.wav") to b
        refs?.listFiles { f -> f.extension == "json" }?.sortedBy { it.name }?.forEach {
            val name = it.nameWithoutExtension
            val bpm = Json.parseToJsonElement(it.readText()).jsonObject["bpm"]!!.jsonPrimitive.double
            truth[name] = File(dir, "${name}_22050.wav") to bpm
        }
        for ((name, pair) in truth) {
            val (file, tb) = pair
            if (!file.exists()) continue
            val wav = WavIo.read(file)
            val x = an.prepare(PcmAudio(wav.mono(), wav.sampleRate))
            val on = OnsetFeatures.compute(x)
            val raw = OnsetEnvelope.combined(on)
            val det = OnsetEnvelope.detrend(raw)
            val detLow = OnsetEnvelope.detrend(on.fluxLow)
            val detTot = OnsetEnvelope.detrend(on.flux)
            val te = TempoEstimator.estimate(det, detLow)!!
            val acAll = Acf(det, 700); val acLow = Acf(detLow, 700); val acTot = Acf(detTot, 700)
            fun row(b: Double) = "%.1f[all %.2f low %.2f tot %.2f]".format(b, acAll.atBpm(b), acLow.atBpm(b), acTot.atBpm(b))
            println("$name truth=$tb est=${"%.1f".format(te.bpm)} ratio=${"%.2f".format(te.bpm / tb)} alts=${te.alternates.take(5).joinToString { "%.1f:%.2f".format(it.first, it.second) }}")
            println("     @truth ${row(tb)} @est ${row(te.bpm)} @est/2 ${row(te.bpm / 2)} @est*2 ${row(te.bpm * 2)}")
        }
    }
}
