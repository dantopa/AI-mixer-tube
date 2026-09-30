package org.simpmusic.dj.android.diag

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaExtractor
import org.simpmusic.dj.analysis.DspTrackAnalyzer
import org.simpmusic.dj.android.analysis.DjAnalyzerFactory
import org.simpmusic.dj.android.decode.AudioInput
import org.simpmusic.dj.android.decode.CancelSignal
import org.simpmusic.dj.android.decode.DecodeRequest
import org.simpmusic.dj.android.decode.MediaCodecPcmDecoder
import org.simpmusic.dj.android.log.DjLog
import org.simpmusic.dj.model.PcmAudio
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

data class SelfCheckResult(val name: String, val ok: Boolean, val detail: String)

/**
 * The startup self-check: every step the analysis needs, tried in isolation, one PASS/FAIL line each in the DJ log
 * (tag `selfcheck`). Cheap (about a second, the ONNX session excluded) and side-effect free.
 *
 *  1. the Beat This! model asset is in the APK,
 *  2. ONNX Runtime's native library loads (and reports its version and providers),
 *  3. the platform decoder opens and decodes one second of generated audio (a PCM16 WAV built in memory),
 *  4. the DSP analyzer runs over that audio,
 *  5. the analysis store directory is writable.
 */
class DjSelfCheck(
    private val context: Context,
    private val storeDir: File,
    private val codec: MediaCodecPcmDecoder = MediaCodecPcmDecoder(),
) {
    fun run(): List<SelfCheckResult> {
        DjLog.i(TAG, "self-check starting")
        val results =
            listOf(
                check("model-asset") {
                    val size = DjAnalyzerFactory.modelAssetSize(context)
                    if (size < 0) throw IllegalStateException("'${DjAnalyzerFactory.MODEL_ASSET}' is not in the APK: DSP-only analysis") else "${DjAnalyzerFactory.MODEL_ASSET} $size bytes"
                },
                check("onnx-runtime") {
                    val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
                    "loaded, providers=${ai.onnxruntime.OrtEnvironment.getAvailableProviders()} env=${env.javaClass.simpleName}"
                },
                check("decoder") {
                    val pcm = generateSineWav(1000)
                    val sink = FloatCollector()
                    codec.decode(BytesAudioInput(pcm), DecodeRequest(outSampleRate = 22_050, outChannels = 1), { buf, n -> sink.add(buf, n) }, CancelSignal.NEVER)
                    if (sink.size < 15_000) throw IllegalStateException("decoded only ${sink.size} samples of a 1 s sine (expected about 22050)")
                    "decoded ${sink.size} samples of a generated 1 s WAV"
                },
                check("dsp-analyzer") {
                    val sr = 22_050
                    val samples = FloatArray(sr * 6) { (0.4 * sin(2 * PI * 440.0 * it / sr)).toFloat() }
                    val t0 = System.nanoTime()
                    val a = DspTrackAnalyzer().analyze("self-check", PcmAudio(samples, sr))
                    "analysed 6 s of audio in ${(System.nanoTime() - t0) / 1_000_000} ms (loudness ${"%.1f".format(a.loudnessDb)} dB, key=${a.key?.value?.camelot()})"
                },
                check("store-dir") {
                    storeDir.mkdirs()
                    val f = File(storeDir, ".selfcheck")
                    f.writeText("ok")
                    f.delete()
                    "${storeDir.path} writable, ${storeDir.listFiles()?.count { it.name.endsWith(".json") } ?: 0} analyses stored"
                },
            )
        DjLog.i(TAG, "self-check finished: ${results.count { it.ok }}/${results.size} passed")
        return results
    }

    private fun check(name: String, block: () -> String): SelfCheckResult {
        val t0 = System.nanoTime()
        return try {
            val detail = block()
            DjLog.i(TAG, "$name PASS (${(System.nanoTime() - t0) / 1_000_000} ms): $detail")
            SelfCheckResult(name, true, detail)
        } catch (e: Throwable) {
            DjLog.e(TAG, "$name FAIL (${(System.nanoTime() - t0) / 1_000_000} ms): ${e.message}", e)
            SelfCheckResult(name, false, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private class FloatCollector {
        var size = 0
            private set

        fun add(@Suppress("UNUSED_PARAMETER") buf: FloatArray, n: Int) {
            size += n
        }
    }

    companion object {
        private const val TAG = "selfcheck"

        /** A mono PCM16 WAV of a 440 Hz sine, [ms] long, built in memory. */
        fun generateSineWav(ms: Int, sampleRate: Int = 44_100): ByteArray {
            val frames = sampleRate * ms / 1000
            val data = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frames) data.putShort((0.5 * Short.MAX_VALUE * sin(2 * PI * 440.0 * i / sampleRate)).toInt().toShort())
            val out = ByteBuffer.allocate(44 + data.capacity()).order(ByteOrder.LITTLE_ENDIAN)
            out.put("RIFF".toByteArray()).putInt(36 + data.capacity()).put("WAVE".toByteArray())
            out.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
            out.put("data".toByteArray()).putInt(data.capacity()).put(data.array())
            return out.array()
        }
    }
}

/** An [AudioInput] over bytes held in memory. */
class BytesAudioInput(private val bytes: ByteArray) : AudioInput {
    override fun applyTo(extractor: MediaExtractor) {
        extractor.setDataSource(
            object : MediaDataSource() {
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position >= bytes.size) return -1
                    val n = minOf(size, bytes.size - position.toInt())
                    System.arraycopy(bytes, position.toInt(), buffer, offset, n)
                    return n
                }

                override fun getSize(): Long = bytes.size.toLong()

                override fun close() {}
            },
        )
    }

    override fun describe(): String = "memory:${bytes.size}B"
}
