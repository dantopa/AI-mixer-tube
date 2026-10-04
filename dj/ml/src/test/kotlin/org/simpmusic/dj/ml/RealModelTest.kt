package org.simpmusic.dj.ml

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.simpmusic.dj.audio.WavIo
import org.simpmusic.dj.model.PcmAudio
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end check of the Kotlin pipeline against the PyTorch references. Needs the ONNX model and the corpus,
 * which are never in git (see dj/docs/ml.md); without them the tests return early.
 */
class RealModelTest {
    private val model = Refs.modelFile()
    private val corpus = Refs.corpusDir()

    /** mir_eval-style beat F-measure with a +/-70 ms tolerance window (greedy one-to-one matching). */
    private fun fMeasure(ref: DoubleArray, est: DoubleArray, tol: Double = 0.07): Double {
        if (ref.isEmpty() || est.isEmpty()) return 0.0
        var hits = 0
        val used = BooleanArray(est.size)
        for (r in ref) {
            var best = -1
            var bd = tol
            for (i in est.indices) if (!used[i] && abs(est[i] - r) <= bd) { bd = abs(est[i] - r); best = i }
            if (best >= 0) { used[best] = true; hits++ }
        }
        val p = hits.toDouble() / est.size
        val rc = hits.toDouble() / ref.size
        return if (p + rc == 0.0) 0.0 else 2 * p * rc / (p + rc)
    }

    private fun run(name: String, wavName: String, minBeatF: Double, minDownF: Double) {
        val m = model ?: return println("skipped: no ONNX model (set BEAT_THIS_MODEL)")
        val c = corpus ?: return println("skipped: no corpus (set BEAT_THIS_CORPUS)")
        val ref = Json.parseToJsonElement(File(c, "refs/$name.json").readText()).jsonObject
        val wav = WavIo.read(File(c, "audio/$wavName"))
        OnnxBeatModel.fromFile(m, threads = 2).use { onnx ->
            val t0 = System.nanoTime()
            val picks = BeatThisTracker(onnx).track(PcmAudio(wav.mono(), wav.sampleRate))
            val secs = (System.nanoTime() - t0) / 1e9
            val fb = fMeasure(Refs.doubles(ref, "beatsSec"), picks.beatsSec)
            val fd = fMeasure(Refs.doubles(ref, "downbeatsSec"), picks.downbeatsSec)
            println("real-model $name ($wavName): beatF=%.3f downF=%.3f in %.1fs for %.0fs of audio".format(fb, fd, secs, wav.frames.toDouble() / wav.sampleRate))
            assertTrue(fb >= minBeatF, "beat F $fb < $minBeatF")
            assertTrue(fd >= minDownF, "downbeat F $fd < $minDownF")
        }
    }

    // 22.05 kHz input skips the resampler; the int8 model is allowed to differ slightly from the fp32 references.
    @Test fun clubDiver22k() = run("club_diver", "club_diver_22050.wav", 0.97, 0.90)

    // 44.1 kHz input goes through our resampler instead of soxr.
    @Test fun funkorama44k() = run("funkorama", "funkorama_44100.wav", 0.95, 0.85)
}
