package org.simpmusic.dj.android.analysis

import android.content.Context
import org.simpmusic.dj.analysis.DspTrackAnalyzer
import org.simpmusic.dj.android.log.DjLog
import org.simpmusic.dj.ml.BeatGrid
import org.simpmusic.dj.ml.BeatProvider
import org.simpmusic.dj.ml.BeatThisTracker
import org.simpmusic.dj.ml.CompositeAnalyzer
import org.simpmusic.dj.ml.OnnxBeatModel
import org.simpmusic.dj.ml.OnnxVocalModel
import org.simpmusic.dj.ml.VocalDetector
import org.simpmusic.dj.ml.VocalProvider
import org.simpmusic.dj.model.Confident
import org.simpmusic.dj.model.TimeRange
import org.simpmusic.dj.model.PcmAudio
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.model.TrackAnalyzer

/**
 * Builds the on-device analyzer: the pure-Kotlin DSP analyzer (key, energy, sections, timbre...) with the neural
 * beat/downbeat grid of Beat This! overlaid when the ONNX model is present as an asset. Beat This! grids are what
 * makes real-music beat-matching trustworthy (DSP-only grids rarely reach the planner's confidence bar outside steady
 * club music), so the model is used whenever it is installed and the app degrades to DSP-only otherwise.
 *
 * Everything that can go wrong here is logged under the tags `analyzer` (asset, model, timings, result) and `ort`.
 */
object DjAnalyzerFactory {
    /** File name of the quantised model (`beat_this_int8mm.onnx`) inside the app assets. */
    const val MODEL_ASSET = "beat_this_int8mm.onnx"

    /** File name of the YAMNet vocal-activity model (`dj/ml/tools/vocals_model.py`) inside the app assets. */
    const val VOCALS_ASSET = "yamnet_fp16w.onnx"

    /** Size in bytes of the bundled model, or -1 when the asset is not in the APK. */
    fun modelAssetSize(context: Context, asset: String = MODEL_ASSET): Long =
        try {
            val am = context.applicationContext.assets
            try {
                am.openFd(asset).use { it.length }
            } catch (_: Exception) {
                // compressed asset: no file descriptor, count the stream
                am.open(asset).use { s ->
                    var total = 0L
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = s.read(buf)
                        if (n < 0) break
                        total += n
                    }
                    total
                }
            }
        } catch (_: Exception) {
            -1L
        }

    fun create(context: Context): TrackAnalyzer {
        val dsp = TimedDsp(DspTrackAnalyzer())
        val app = context.applicationContext
        val size = modelAssetSize(app)
        if (size < 0) {
            DjLog.w(TAG, "Beat This! model asset '$MODEL_ASSET' is NOT in the APK: DSP-only analysis (beat-matching will rarely reach the confidence bar)")
            return dsp
        }
        DjLog.i(TAG, "Beat This! model asset '$MODEL_ASSET' present: $size bytes; analyzer = DSP + Beat This! overlay")
        return CompositeAnalyzer(dsp, LazyBeatThis(app), vocals = vocals(app))
    }

    @Volatile private var vocalProvider: VocalProvider? = null

    /** The vocal detector (one per process), or null when its model is not in the APK: mixes then ignore vocals. */
    fun vocals(context: Context): VocalProvider? {
        vocalProvider?.let { return it }
        val size = modelAssetSize(context.applicationContext, VOCALS_ASSET)
        if (size < 0) {
            DjLog.w(TAG, "vocal model asset '$VOCALS_ASSET' is NOT in the APK: the DJ cannot avoid voice over voice")
            return null
        }
        DjLog.i(TAG, "vocal model asset '$VOCALS_ASSET' present: $size bytes")
        return synchronized(this) { vocalProvider ?: LazyVocals(context.applicationContext).also { vocalProvider = it } }
    }

    private const val TAG = "analyzer"
}

/** The DSP analyzer with its per-stage timings logged: which stage is slow on a real phone. */
internal class TimedDsp(private val dsp: DspTrackAnalyzer) : TrackAnalyzer {
    override val id: String get() = dsp.id

    override fun analyze(videoId: String, audio: PcmAudio): TrackAnalysis {
        val t0 = System.nanoTime()
        val (analysis, timings) = dsp.analyzeTimed(videoId, audio)
        DjLog.i(
            "analyzer",
            "DSP $videoId in ${(System.nanoTime() - t0) / 1_000_000} ms (audio ${audio.durationMs} ms): " +
                timings.stages.entries.joinToString { "${it.key}=${it.value}ms" },
        )
        return analysis
    }
}

/**
 * Loads the ONNX model on first use (24 MB, a couple of seconds) and keeps it for the process lifetime.
 *
 * A load or inference failure (an `Error` such as a missing native library included: the worker must survive it) is
 * logged with its stack and answered with `null`, so the composite keeps the DSP result. A failed LOAD is remembered for
 * [RETRY_LOAD_MS] so every track does not pay for it again, then tried again.
 */
private class LazyBeatThis(private val context: Context) : BeatProvider {
    override val id: String = "beat-this"

    @Volatile private var tracker: BeatThisTracker? = null

    @Volatile private var loadFailedAtMs = 0L

    private fun tracker(): BeatThisTracker? {
        tracker?.let { return it }
        return synchronized(this) {
            tracker ?: run {
                val failedAgo = System.currentTimeMillis() - loadFailedAtMs
                if (loadFailedAtMs != 0L && failedAgo < RETRY_LOAD_MS) {
                    DjLog.w(TAG_ORT, "model load failed ${failedAgo / 1000} s ago, not retrying yet: DSP-only for this track")
                    return@run null
                }
                val t0 = System.nanoTime()
                try {
                    DjLog.i(TAG_ORT, "loading model ${DjAnalyzerFactory.MODEL_ASSET} (creating the ONNX Runtime session)...")
                    val model = OnnxBeatModel.fromStream(context.assets.open(DjAnalyzerFactory.MODEL_ASSET), threads = 2)
                    DjLog.i(TAG_ORT, "session created in ${(System.nanoTime() - t0) / 1_000_000} ms")
                    BeatThisTracker(model).also { tracker = it }
                } catch (e: Throwable) {
                    loadFailedAtMs = System.currentTimeMillis()
                    DjLog.e(TAG_ORT, "session creation FAILED after ${(System.nanoTime() - t0) / 1_000_000} ms: DSP-only analysis until it loads", e)
                    null
                }
            }
        }
    }

    override fun grid(audio: PcmAudio): BeatGrid? {
        val t = tracker() ?: return null
        val t0 = System.nanoTime()
        return try {
            val grid = t.grid(audio)
            DjLog.i(
                TAG_ORT,
                "Beat This! inference ${(System.nanoTime() - t0) / 1_000_000} ms for ${audio.durationMs} ms of audio: " +
                    (grid?.let { "beats=${it.beatTimesMs.size} downbeats=${it.downbeatBeatIndices.size} bpm=${it.bpm?.let { b -> "%.1f".format(b) }} beatsPerBar=${it.beatsPerBar} conf(beat=%.2f bpm=%.2f down=%.2f)".format(it.beatConfidence, it.bpmConfidence, it.downbeatConfidence) } ?: "no usable grid (fewer than 4 beats or audio too short)"),
            )
            grid
        } catch (e: Throwable) {
            DjLog.e(TAG_ORT, "Beat This! inference FAILED after ${(System.nanoTime() - t0) / 1_000_000} ms: keeping the DSP grid", e)
            null
        }
    }

    private companion object {
        const val TAG_ORT = "ort"
        const val RETRY_LOAD_MS = 5 * 60_000L
    }
}

/** Loads the YAMNet session on first use and keeps it; any failure is logged and answered with null (no vocals). */
private class LazyVocals(private val context: Context) : VocalProvider {
    override val id: String = "yamnet-vocals-1"

    @Volatile private var detector: VocalDetector? = null

    @Volatile private var loadFailedAtMs = 0L

    private fun detector(): VocalDetector? {
        detector?.let { return it }
        return synchronized(this) {
            detector ?: run {
                if (loadFailedAtMs != 0L && System.currentTimeMillis() - loadFailedAtMs < 5 * 60_000L) return@run null
                val t0 = System.nanoTime()
                try {
                    // one thread: it runs beside Beat This! on the same worker, and heat matters more than seconds here
                    VocalDetector(OnnxVocalModel.fromStream(context.assets.open(DjAnalyzerFactory.VOCALS_ASSET), threads = 1)).also {
                        detector = it
                        DjLog.i("ort", "vocal model session created in ${(System.nanoTime() - t0) / 1_000_000} ms")
                    }
                } catch (e: Throwable) {
                    loadFailedAtMs = System.currentTimeMillis()
                    DjLog.e("ort", "vocal model session creation FAILED: no vocal detection until it loads", e)
                    null
                }
            }
        }
    }

    override fun vocals(audio: PcmAudio): Confident<List<TimeRange>>? = detect(audio)?.ranges

    override fun detect(audio: PcmAudio): org.simpmusic.dj.ml.VocalDetection? {
        val d = detector() ?: return null
        val t0 = System.nanoTime()
        return try {
            d.detect(audio).also { v ->
                DjLog.i(
                    "ort",
                    "vocals ${(System.nanoTime() - t0) / 1_000_000} ms for ${audio.durationMs} ms of audio: " +
                        (v?.ranges?.value?.let { r -> "${r.size} ranges, ${r.sumOf { it.durationMs } / 1000} s of voice" } ?: "too short"),
                )
            }
        } catch (e: Throwable) {
            DjLog.e("ort", "vocal detection FAILED after ${(System.nanoTime() - t0) / 1_000_000} ms: no vocals for this track", e)
            null
        }
    }
}
