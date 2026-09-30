package org.simpmusic.dj.ml

import kotlinx.serialization.json.Json
import org.simpmusic.dj.audio.WavIo
import org.simpmusic.dj.model.TrackAnalysis
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test

/** Manual: how far is each track's Beat This! grid from the physical kick/bass transient? (needs DJ_CACHE + corpus) */
class BeatVsKickOffset {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun run() {
        val corpus = System.getenv("BEAT_THIS_CORPUS")?.let(::File) ?: return println("skipped")
        val cache = System.getenv("DJ_CACHE")?.let(::File) ?: return println("skipped")
        for (f in corpus.resolve("audio").listFiles { x -> x.name.endsWith("_44100.wav") }!!.sortedBy { it.name }) {
            val id = f.name.removeSuffix("_44100.wav")
            val c = cache.resolve("$id.json").takeIf { it.exists() } ?: continue
            val a = json.decodeFromString<TrackAnalysis>(c.readText())
            val beats = a.beatTimesMs?.value ?: continue
            val w = WavIo.read(f)
            val x = w.mono()
            val sr = w.sampleRate
            // 2-pole LP 150 Hz -> 2 ms energy hop -> half-wave flux
            val alpha = Math.exp(-2 * Math.PI * 150.0 / sr)
            var y1 = 0.0
            var y2 = 0.0
            val hop = sr / 500
            val n = x.size / hop
            val env = DoubleArray(n)
            for (i in 0 until n) {
                var e = 0.0
                for (k in 0 until hop) { y1 = alpha * y1 + (1 - alpha) * x[i * hop + k]; y2 = alpha * y2 + (1 - alpha) * y1; e += y2 * y2 }
                env[i] = sqrt(e / hop)
            }
            val flux = DoubleArray(n) { if (it == 0) 0.0 else maxOf(0.0, env[it] - env[it - 1]) }
            val offs = ArrayList<Double>()
            for (b in beats) {
                val c0 = (b / 2.0).toInt()
                val lo = (c0 - 25).coerceAtLeast(1)
                val hi = (c0 + 25).coerceAtMost(n - 2)
                if (hi <= lo) continue
                var best = lo
                for (i in lo..hi) if (flux[i] > flux[best]) best = i
                if (flux[best] > 0) offs += (best - c0) * 2.0
            }
            if (offs.size < 8) { println("%-26s too few".format(id)); continue }
            val s = offs.sorted()
            val med = s[s.size / 2]
            val mad = offs.map { abs(it - med) }.sorted().let { it[it.size / 2] }
            println("%-26s beats=%4d  kick-vs-grid median %+6.1f ms   MAD %5.1f ms   within 20ms of median: %.0f%%".format(id, beats.size, med, mad, 100.0 * offs.count { abs(it - med) <= 20 } / offs.size))
        }
    }
}
