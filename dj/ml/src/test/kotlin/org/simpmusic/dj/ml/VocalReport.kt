package org.simpmusic.dj.ml

import org.simpmusic.dj.audio.WavIo
import org.simpmusic.dj.model.PcmAudio
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Manual: the Kotlin [VocalDetector] on songs with known vocal timings. VOX_MODEL = the asset built by
 * `dj/ml/tools/vocals_model.py`; VOX_DIR = a folder with `wav/<name>.wav` and `annotations/words/<name>.csv`
 * (JamendoLyrics layout: word_start,word_end,...). Truth: words closer than 0.6 s merged into one sung span.
 * Prints how often a 16 s window is called vocal or not correctly. Nothing of the audio or the labels is in the repo.
 */
class VocalReport {
    @Test
    fun run() {
        val model = System.getenv("VOX_MODEL")?.let(::File)?.takeIf { it.isFile } ?: return println("skipped: no VOX_MODEL")
        val dir = System.getenv("VOX_DIR")?.let(::File)?.takeIf { it.isDirectory } ?: return println("skipped: no VOX_DIR")
        val detector = VocalDetector(OnnxVocalModel.fromStream(model.inputStream(), threads = 2))
        var right = 0
        var total = 0
        var audioMs = 0L
        var spentMs = 0L
        for (wavFile in (File(dir, "wav").listFiles() ?: emptyArray()).sortedBy { it.name }) {
            val words = File(dir, "annotations/words/${wavFile.nameWithoutExtension}.csv").readLines().drop(1).map { l ->
                val p = l.split(','); p[0].toDouble() * 1000 to p[1].toDouble() * 1000
            }.sortedBy { it.first }
            val sung = ArrayList<DoubleArray>()
            for ((a, b) in words) if (sung.isNotEmpty() && a - sung.last()[1] < 600) sung.last()[1] = maxOf(sung.last()[1], b) else sung += doubleArrayOf(a, b)
            val wav = WavIo.read(wavFile)
            val audio = PcmAudio(wav.mono(), wav.sampleRate)
            val t0 = System.nanoTime()
            val found = detector.vocals(audio)!!.value
            spentMs += (System.nanoTime() - t0) / 1_000_000
            audioMs += audio.durationMs
            var r = 0
            var n = 0
            var w = 0L
            while (w + 16_000 <= audio.durationMs) {
                fun share(spans: List<Pair<Double, Double>>): Double {
                    var s = 0.0
                    for ((a, b) in spans) s += (minOf(b, w + 16_000.0) - maxOf(a, w.toDouble())).coerceAtLeast(0.0)
                    return s / 16_000
                }
                val truth = share(sung.map { it[0] to it[1] }) >= 0.5
                val est = share(found.map { it.startMs.toDouble() to it.endMs.toDouble() }) >= 0.5
                if (truth == est) r++
                n++
                w += 4_000
            }
            println("%-45s windows right %3d/%3d, vocal ranges %d".format(wavFile.nameWithoutExtension.take(45), r, n, found.size))
            right += r
            total += n
        }
        println("16 s windows called right: $right/$total = %.3f; inference %d ms for %d s of audio".format(right.toDouble() / total, spentMs, audioMs / 1000))
        assertTrue(total == 0 || right.toDouble() / total > 0.8)
    }
}
