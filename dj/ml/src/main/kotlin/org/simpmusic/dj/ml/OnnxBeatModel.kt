package org.simpmusic.dj.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.InputStream
import java.nio.FloatBuffer

/**
 * [BeatModel] over ONNX Runtime. The only class that imports `ai.onnxruntime`; the same source compiles against
 * `onnxruntime-android` (identical package) when the module is used from the app.
 *
 * The model file is injected (path, bytes or stream) and never bundled in git: see `dj/docs/ml.md`.
 */
class OnnxBeatModel private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : BeatModel {
    private val inputName = session.inputNames.first()

    override fun run(spect: FloatArray, frames: Int): FrameLogits {
        require(spect.size == frames * BeatModel.N_MELS)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(spect), longArrayOf(1, frames.toLong(), BeatModel.N_MELS.toLong())).use { input ->
            session.run(mapOf(inputName to input)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val beat = (out.get("beat").get().value as Array<FloatArray>)[0]
                @Suppress("UNCHECKED_CAST")
                val down = (out.get("downbeat").get().value as Array<FloatArray>)[0]
                return FrameLogits(beat, down)
            }
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        /** [threads] = intra-op threads (0 = ORT default). Analysis is background work: 2 is a good phone default. */
        fun fromFile(file: File, threads: Int = 2): OnnxBeatModel = fromBytes(file.readBytes(), threads)

        fun fromStream(stream: InputStream, threads: Int = 2): OnnxBeatModel = stream.use { fromBytes(it.readBytes(), threads) }

        fun fromBytes(bytes: ByteArray, threads: Int = 2): OnnxBeatModel {
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                if (threads > 0) setIntraOpNumThreads(threads)
                setInterOpNumThreads(1)
            }
            return OnnxBeatModel(env, env.createSession(bytes, opts))
        }
    }
}
