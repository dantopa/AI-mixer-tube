package org.simpmusic.dj.android.analysis

import android.content.Context
import org.simpmusic.dj.analysis.DspTrackAnalyzer
import org.simpmusic.dj.ml.BeatGrid
import org.simpmusic.dj.ml.BeatProvider
import org.simpmusic.dj.ml.BeatThisTracker
import org.simpmusic.dj.ml.CompositeAnalyzer
import org.simpmusic.dj.ml.OnnxBeatModel
import org.simpmusic.dj.model.PcmAudio
import org.simpmusic.dj.model.TrackAnalyzer

/**
 * Builds the on-device analyzer: the pure-Kotlin DSP analyzer (key, energy, sections, timbre...) with the neural
 * beat/downbeat grid of Beat This! overlaid when the ONNX model is present as an asset. Beat This! grids are what
 * makes real-music beat-matching trustworthy (DSP-only grids rarely reach the planner's confidence bar outside steady
 * club music), so the model is used whenever it is installed and the app degrades to DSP-only otherwise.
 */
object DjAnalyzerFactory {
    /** File name of the quantised model (`beat_this_int8mm.onnx`) inside the app assets. */
    const val MODEL_ASSET = "beat_this_int8mm.onnx"

    fun create(context: Context, log: (String) -> Unit = {}): TrackAnalyzer {
        val dsp = DspTrackAnalyzer()
        val app = context.applicationContext
        val hasModel = try {
            app.assets.open(MODEL_ASSET).close()
            true
        } catch (_: Exception) {
            false
        }
        if (!hasModel) {
            log("Beat This! model not bundled: using the DSP analyzer only")
            return dsp
        }
        return CompositeAnalyzer(dsp, LazyBeatThis(app, log))
    }
}

/** Loads the ONNX model on first use (24 MB, a couple of seconds) and keeps it for the process lifetime. */
private class LazyBeatThis(private val context: Context, private val log: (String) -> Unit) : BeatProvider {
    override val id: String = "beat-this"

    @Volatile private var tracker: BeatThisTracker? = null

    private fun tracker(): BeatThisTracker =
        tracker ?: synchronized(this) {
            tracker ?: run {
                val model = OnnxBeatModel.fromStream(context.assets.open(DjAnalyzerFactory.MODEL_ASSET), threads = 2)
                log("Beat This! model loaded")
                BeatThisTracker(model).also { tracker = it }
            }
        }

    override fun grid(audio: PcmAudio): BeatGrid? = tracker().grid(audio)
}
