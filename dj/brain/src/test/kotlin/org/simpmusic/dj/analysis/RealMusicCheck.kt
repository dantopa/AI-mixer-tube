package org.simpmusic.dj.analysis

import org.simpmusic.dj.audio.WavIo
import org.simpmusic.dj.model.PcmAudio
import org.simpmusic.dj.model.TrackAnalysis
import java.io.File
import kotlinx.serialization.json.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test

/**
 * Manual real-music validation (skipped unless DJ_CORPUS points at a directory of WAV files; audio is never committed).
 * Prints the analysis, checks the beat grid against an independent broadband energy-rise detector, renders
 * "click over track" WAVs into DJ_OUT and, when DJ_REFS holds Beat This! JSON annotations, scores against them.
 */
class RealMusicCheck {
    @Test
    fun run() {
        val dir = System.getenv("DJ_CORPUS")?.let { File(it) } ?: return
        val out = System.getenv("DJ_OUT")?.let { File(it).apply { mkdirs() } }
        val only = System.getenv("DJ_ONLY")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val analyzer = DspTrackAnalyzer()
        val files = dir.listFiles { f -> f.extension == "wav" }!!.sortedBy { it.name }
            .filter { only == null || only.any { o -> it.name.contains(o) } }
        for (f in files) {
            val wav = WavIo.read(f)
            val skip = System.getenv("DJ_SKIP")?.toDoubleOrNull() ?: 0.0
            val audio = PcmAudio(wav.mono().let { m -> if (skip > 0) m.copyOfRange((skip * wav.sampleRate).toInt(), m.size) else m }, wav.sampleRate)
            val t0 = System.nanoTime()
            val a = analyzer.analyze(f.nameWithoutExtension, audio)
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("== ${f.name} (${audio.durationMs / 1000}s @${wav.sampleRate}) analysed in ${ms} ms")
            println("   " + describe(a))
            val refFile = System.getenv("DJ_REFS")?.let { File(it, f.nameWithoutExtension.replace(Regex("_(22050|44100)$"), "") + ".json") }
            if (refFile != null && refFile.exists()) println("   " + scoreAgainstRef(a, refFile, skip))
            val beats = a.beatTimesMs?.value
            if (beats != null && beats.size > 8) {
                val chk = gridVsOnsets(audio, beats)
                println("   grid-vs-onset: median offset ${"%.1f".format(chk.first)} ms, within 25 ms: ${"%.0f".format(chk.second * 100)} %")
                if (out != null) writeClicks(File(out, f.nameWithoutExtension + "_clicks.wav"), audio, a)
            }
        }
    }

    private fun fMeasure(est: List<Double>, ref: List<Double>, tol: Double): Triple<Double, Double, Double> {
        if (est.isEmpty() || ref.isEmpty()) return Triple(0.0, 0.0, 0.0)
        val used = BooleanArray(est.size)
        var tp = 0
        for (r in ref) {
            var bi = -1; var bd = tol
            for (i in est.indices) if (!used[i] && abs(est[i] - r) <= bd) { bd = abs(est[i] - r); bi = i }
            if (bi >= 0) { used[bi] = true; tp++ }
        }
        val p = tp.toDouble() / est.size; val rc = tp.toDouble() / ref.size
        return Triple(if (p + rc > 0) 2 * p * rc / (p + rc) else 0.0, p, rc)
    }

    private fun scoreAgainstRef(a: TrackAnalysis, file: File, skip: Double = 0.0): String {
        val j = Json.parseToJsonElement(file.readText()).jsonObject
        val ref = j["beatsSec"]!!.jsonArray.map { it.jsonPrimitive.double - skip }.filter { it >= 0 }
        val refDown = j["downbeatsSec"]!!.jsonArray.map { it.jsonPrimitive.double - skip }.filter { it >= 0 }
        val refBpm = j["bpm"]!!.jsonPrimitive.double
        val refBpb = j["beatsPerBar"]?.jsonPrimitive?.intOrNull
        val key = j["key"]?.jsonObject
        val est = a.beatTimesMs?.value?.map { it / 1000.0 } ?: emptyList()
        val estDown = a.downbeatBeatIndices?.value?.map { est[it] } ?: emptyList()
        val f70 = fMeasure(est, ref, 0.07); val f40 = fMeasure(est, ref, 0.04)
        val d70 = fMeasure(estDown, refDown, 0.07)
        // signed offset of matched beats
        val offs = ArrayList<Double>()
        for (r in ref) { val e = est.minByOrNull { abs(it - r) } ?: continue; if (abs(e - r) < 0.07) offs.add((e - r) * 1000) }
        offs.sort()
        val med = if (offs.isEmpty()) Double.NaN else offs[offs.size / 2]
        // double-tempo tolerant beat F (est at half/double density)
        val ratio = (a.bpm?.value ?: 0f) / refBpm
        val keyStr = key?.let { "${it["name"]?.jsonPrimitive?.content}(r=${"%.2f".format(it["corr"]!!.jsonPrimitive.double)})" }
        return "REF bpm=${"%.2f".format(refBpm)} ratio=${"%.3f".format(ratio)} beatF@70=${"%.2f".format(f70.first)} F@40=${"%.2f".format(f40.first)} (P=${"%.2f".format(f70.second)} R=${"%.2f".format(f70.third)}) medOffset=${"%.1f".format(med)}ms " +
            "downF@70=${"%.2f".format(d70.first)} bpb ref=$refBpb est=${a.beatsPerBar} refKey=$keyStr"
    }

    private fun describe(a: TrackAnalysis): String {
        val secs = a.sections?.value?.joinToString(" ") { "${it.kind.name.take(3)}@${it.range.startMs / 1000}" }
        return "bpm=${a.bpm?.value?.let { "%.2f".format(it) }}(${a.bpm?.confidence?.let { "%.2f".format(it) }}) beats=${a.beatTimesMs?.value?.size}(${a.beatTimesMs?.confidence?.let { "%.2f".format(it) }}) " +
            "bpb=${a.beatsPerBar} down(${a.downbeatBeatIndices?.confidence?.let { "%.2f".format(it) }}) key=${a.key?.value}(${a.key?.confidence?.let { "%.2f".format(it) }}) " +
            "loud=${"%.1f".format(a.loudnessDb)}dB sections=${a.sections?.confidence?.let { "%.2f".format(it) }} [$secs] phrases=${a.phraseStartsMs.size}"
    }

    /** Median (t_onset - t_beat) in ms and share of beats with an energy rise within 25 ms, on an independent detector. */
    private fun gridVsOnsets(audio: PcmAudio, beatsMs: List<Int>): Pair<Double, Double> {
        val sr = audio.sampleRate
        val x = audio.samples
        val hop = max(1, sr / 1000) // 1 ms
        val win = max(1, (0.005 * sr).toInt())
        val n = x.size / hop
        val env = DoubleArray(n)
        // 5 ms RMS envelope
        for (i in 0 until n) {
            var s = 0.0
            val a = i * hop; val b = min(x.size, a + win)
            for (k in a until b) s += x[k] * x[k]
            env[i] = s / max(1, b - a)
        }
        val rise = DoubleArray(n)
        for (i in 4 until n) rise[i] = max(0.0, Math.sqrt(env[i]) - Math.sqrt(env[i - 4]))
        val offs = ArrayList<Double>()
        for (b in beatsMs) {
            var best = -1; var bv = 0.0
            for (d in -60..60) { val i = b + d; if (i in 0 until n && rise[i] > bv) { bv = rise[i]; best = d } }
            if (best != Int.MIN_VALUE && bv > 0) offs.add(best.toDouble())
        }
        if (offs.isEmpty()) return 0.0 to 0.0
        offs.sort()
        return offs[offs.size / 2] to offs.count { abs(it) <= 25 }.toDouble() / beatsMs.size
    }

    private fun writeClicks(file: File, audio: PcmAudio, a: TrackAnalysis) {
        val beats = a.beatTimesMs!!.value
        val down = a.downbeatBeatIndices?.value?.toSet() ?: emptySet()
        val sr = audio.sampleRate
        val y = audio.samples.copyOf()
        for ((i, b) in beats.withIndex()) {
            val f = if (i in down) 1800.0 else 1000.0
            val s = (b.toLong() * sr / 1000).toInt()
            val len = (0.03 * sr).toInt()
            for (k in 0 until len) {
                val idx = s + k
                if (idx !in y.indices) break
                y[idx] += (sin(2 * PI * f * k / sr) * exp(-k / (0.006 * sr)) * (if (i in down) 0.8 else 0.5)).toFloat()
            }
        }
        WavIo.write(file, WavIo.Wav(arrayOf(y), sr))
    }
}
