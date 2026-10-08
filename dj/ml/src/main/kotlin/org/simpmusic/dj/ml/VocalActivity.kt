package org.simpmusic.dj.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.simpmusic.dj.model.Confident
import org.simpmusic.dj.model.PcmAudio
import org.simpmusic.dj.model.TimeRange
import org.simpmusic.dj.model.VocalProfile
import java.io.InputStream
import java.nio.FloatBuffer
import kotlin.math.ln

/** Where a voice sings or raps in a track, for `TrackAnalysis.vocals`. */
interface VocalProvider {
    val id: String

    /** Null when nothing can be said (model missing, audio too short); an empty list means "no vocals found". */
    fun vocals(audio: PcmAudio): Confident<List<TimeRange>>?

    /** The ranges plus the frame-by-frame evidence they were cut from, when the provider has it. */
    fun detect(audio: PcmAudio): VocalDetection? = vocals(audio)?.let { VocalDetection(it, null) }
}

class VocalDetection(val ranges: Confident<List<TimeRange>>, val profile: VocalProfile?)

/** An AudioSet tagger over 16 kHz mono: one row of [VocalDetector.CLASSES] scores per 0.48 s frame (0.96 s window). */
interface VocalModel : AutoCloseable {
    fun classScores(wave16k: FloatArray): Array<FloatArray>
}

/**
 * Vocal activity from YAMNet (Google, Apache-2.0; `dj/ml/tools/vocals_model.py` builds the asset). YAMNet tags 521
 * AudioSet classes per 0.48 s; the vocal score of a frame is the sum of its voice classes (singing, rapping, choir, speech,
 * vocal music...), in log, averaged with its two neighbours, and a frame is vocal above [THRESHOLD].
 *
 * Measured against JamendoLyrics (79 songs in English, French, German and Spanish, vocal truth from the word timings; local
 * evaluation only, nothing of it is in the repo): a 16 s window is called vocal or not correctly 88 % of the time, and
 * "voice over voice" between two windows of different songs 86 % (precision 87 %, recall 93 %). The 13 instrumental tracks
 * of the test corpus are marked vocal 1.8 % of the time. Not measured on cumbia or reggaeton specifically.
 */
class VocalDetector(private val model: VocalModel) : VocalProvider {
    override val id: String = "yamnet-vocals-1"

    override fun vocals(audio: PcmAudio): Confident<List<TimeRange>>? = detect(audio)?.ranges

    override fun detect(audio: PcmAudio): VocalDetection? {
        val wave = Resampler.resample(audio.samples, audio.sampleRate, SAMPLE_RATE)
        val frames = wave.size / HOP
        if (frames < 3) return null
        val score = DoubleArray(frames)
        val top = IntArray(frames)
        var start = 0
        while (start < frames) {
            // YAMNet pads the end of what it is given: two extra hops make every kept frame see real audio (measured: exact)
            val from = start * HOP
            val to = minOf(wave.size, (start + CHUNK_HOPS + 2) * HOP)
            val rows = model.classScores(wave.copyOfRange(from, to))
            for (k in 0 until minOf(CHUNK_HOPS, rows.size, frames - start)) {
                var s = 0.0
                for (c in CLASSES) s += rows[k][c]
                score[start + k] = ln(s + 1e-4)
                var best = 0
                for (c in rows[k].indices) if (rows[k][c] > rows[k][best]) best = c
                top[start + k] = best
            }
            start += CHUNK_HOPS
        }
        val profile = VocalProfile(HOP_MS.toInt(), score.map { Math.round(it * 10) / 10f }, top.toList())
        return VocalDetection(Confident(ranges(score), CONFIDENCE), profile)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val HOP = 7_680
        const val HOP_MS = 480L

        /** 60 s of audio per inference: the whole track at once needs ~100 MB of activations on a phone. */
        const val CHUNK_HOPS = 125

        /** Speech, Singing, Choir, Chant, Child singing, Synthetic singing, Rapping, Humming, Vocal music, A capella. */
        val CLASSES = intArrayOf(0, 24, 25, 27, 29, 30, 31, 32, 249, 250)

        /** ln(sum of the voice classes), on the frame and its neighbours: the best balanced accuracy on JamendoLyrics. */
        const val THRESHOLD = -5.0
        const val MERGE_MS = 1_000L
        const val MIN_MS = 1_000L
        const val CONFIDENCE = 0.85f

        /** Frame scores -> vocal ranges: smoothing over 3 frames, threshold, gaps under [MERGE_MS] closed, bits under [MIN_MS] dropped. */
        fun ranges(score: DoubleArray): List<TimeRange> {
            val out = ArrayList<LongArray>()
            for (i in score.indices) {
                var s = 0.0
                var n = 0
                for (j in maxOf(0, i - 1)..minOf(score.size - 1, i + 1)) { s += score[j]; n++ }
                if (s / n < THRESHOLD) continue
                val a = i * HOP_MS + HOP_MS / 2
                val b = i * HOP_MS + 3 * HOP_MS / 2
                val last = out.lastOrNull()
                if (last != null && a - last[1] < MERGE_MS) last[1] = b else out += longArrayOf(a, b)
            }
            return out.filter { it[1] - it[0] >= MIN_MS }.map { TimeRange(it[0], it[1]) }
        }
    }
}

/** [VocalModel] over ONNX Runtime (the same source compiles against `onnxruntime-android`). */
class OnnxVocalModel private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : VocalModel {
    private val inputName = session.inputNames.first()

    override fun classScores(wave16k: FloatArray): Array<FloatArray> =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(wave16k), longArrayOf(wave16k.size.toLong())).use { input ->
            session.run(mapOf(inputName to input), setOf("output_0")).use { out ->
                @Suppress("UNCHECKED_CAST")
                out.get(0).value as Array<FloatArray>
            }
        }

    override fun close() {
        session.close()
    }

    companion object {
        fun fromStream(stream: InputStream, threads: Int = 1): OnnxVocalModel = stream.use { fromBytes(it.readBytes(), threads) }

        fun fromBytes(bytes: ByteArray, threads: Int = 1): OnnxVocalModel {
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                if (threads > 0) setIntraOpNumThreads(threads)
                setInterOpNumThreads(1)
                addConfigEntry("session.intra_op.allow_spinning", "0")
                addConfigEntry("session.inter_op.allow_spinning", "0")
            }
            return OnnxVocalModel(env, env.createSession(bytes, opts))
        }
    }
}
