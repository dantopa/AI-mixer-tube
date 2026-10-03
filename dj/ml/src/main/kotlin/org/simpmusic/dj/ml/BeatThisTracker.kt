package org.simpmusic.dj.ml

import org.simpmusic.dj.model.PcmAudio
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Beat grid in the vocabulary of `TrackAnalysis`: times in ms on the source timeline, downbeats as indices
 * into the beat list, and how much each part is trusted.
 */
class BeatGrid(
    val beatTimesMs: List<Int>,
    val downbeatBeatIndices: List<Int>,
    val beatsPerBar: Int?,
    val bpm: Float?,
    val beatConfidence: Float,
    val bpmConfidence: Float,
    val downbeatConfidence: Float,
    val phraseStartsMs: List<Long>,
    /** Per beat (same order as [beatTimesMs]): the network's downbeat logit, or null when the source has none. */
    val downbeatLogits: List<Float>? = null,
)

/** Where a [BeatGrid] comes from; lets [CompositeAnalyzer] overlay any neural (or other) beat source. */
interface BeatProvider {
    val id: String
    fun grid(audio: PcmAudio): BeatGrid?
}

/**
 * Beat This! (Foscarin, Schlueter, Widmer, ISMIR 2024) on-device: resample -> log-mel -> chunked ONNX model ->
 * peak picking -> beat grid. Model runtime is behind [BeatModel]; the rest is pure Kotlin.
 */
class BeatThisTracker(
    private val model: BeatModel,
    private val front: MelFrontEnd = MelFrontEnd(),
    private val onProgress: ((done: Int, total: Int) -> Unit)? = null,
) : BeatProvider {
    override val id: String = "beat-this"

    /** Raw picks (seconds) straight from the paper's minimal post-processor, plus the frame logits. */
    fun track(audio: PcmAudio): BeatPicks {
        val mono = Resampler.resample(audio.samples, audio.sampleRate, MelFrontEnd.SAMPLE_RATE)
        val spect = front.compute(mono)
        val frames = front.frameCount(mono.size)
        val logits = ChunkedInference.run(model, spect, frames, onProgress = onProgress)
        return BeatPostProcessor.pick(logits)
    }

    override fun grid(audio: PcmAudio): BeatGrid? {
        if (audio.samples.size < audio.sampleRate) return null // under a second: nothing to track
        return BeatGridBuilder.build(track(audio))
    }
}

/** Turns raw picks into a [BeatGrid]: bpm, bar structure, phrase starts and honest confidences. */
object BeatGridBuilder {
    fun build(picks: BeatPicks): BeatGrid? {
        val n = picks.beatsSec.size
        if (n < 4) return null
        val beatsMs = IntArray(n) { Math.round(picks.beatsSec[it] * 1000.0).toInt() }
        // beats can collide after rounding only if two picks were < 1 ms apart, which the +/-70 ms pooling forbids
        val ibi = DoubleArray(n - 1) { picks.beatsSec[it + 1] - picks.beatsSec[it] }

        // Global tempo: slope of beat index vs time (robust against per-beat jitter/swing), like the reference refs.
        val bpm = (60.0 / slope(picks.beatsSec)).toFloat()

        // Regularity: share of intervals within 12 % of the median of their neighbourhood (tolerates drift).
        var regular = 0
        for (i in ibi.indices) {
            val lo = max(0, i - 4)
            val hi = min(ibi.size - 1, i + 4)
            val local = ibi.copyOfRange(lo, hi + 1).sorted()[(hi - lo + 1) / 2]
            if (abs(ibi[i] - local) <= 0.12 * local) regular++
        }
        val regularity = regular.toFloat() / ibi.size
        val meanBeatProb = picks.beatProb.average().toFloat()
        val beatConf = (0.5f * meanBeatProb + 0.5f * regularity).coerceIn(0f, 1f)
        val bpmConf = (regularity * (if (bpm in 40f..220f) 1f else 0.5f)).coerceIn(0f, 1f)

        // Downbeats as indices into the beat list.
        val dIdx = ArrayList<Int>()
        for (d in picks.downbeatsSec) {
            val i = nearestIndex(picks.beatsSec, d)
            if (dIdx.isEmpty() || dIdx.last() != i) dIdx.add(i)
        }
        var barLen: Int? = null
        var downConf = 0f
        if (dIdx.size >= 2) {
            val lens = (1 until dIdx.size).map { dIdx[it] - dIdx[it - 1] }
            val modal = lens.groupingBy { it }.eachCount().maxByOrNull { it.value }!!
            barLen = modal.key
            val consistency = modal.value.toFloat() / lens.size
            downConf = (0.5f * picks.downbeatProb.average().toFloat() + 0.5f * consistency).coerceIn(0f, 1f)
            // A bar length other than 3 or 4 is a sign the network is unsure about the metre: do not trust the bars.
            if (barLen != 3 && barLen != 4) downConf = min(downConf, 0.3f)
        }
        val beatsPerBar = barLen?.takeIf { it == 3 || it == 4 }

        val phrases = phraseStarts(beatsMs, dIdx, beatsPerBar, bpm)
        val logits = picks.beatDownbeatLogit.takeIf { it.size == n }?.map { if (it.isFinite()) it else -20f }
        return BeatGrid(beatsMs.toList(), dIdx, beatsPerBar, bpm, beatConf, bpmConf, downConf, phrases, logits)
    }

    /**
     * Phrase = 16 beats (4 bars of 4/4) at >= 95 bpm, else 8 beats; 12 / 6 beats for 3/4. Counted in whole bars
     * from the first downbeat, so every start is a downbeat. Empty when the bars are unknown.
     */
    fun phraseStarts(beatsMs: IntArray, downbeatIdx: List<Int>, beatsPerBar: Int?, bpm: Float): List<Long> {
        if (beatsPerBar == null || downbeatIdx.isEmpty()) return emptyList()
        val phraseBeats = if (beatsPerBar == 4) (if (bpm >= 95f) 16 else 8) else (if (bpm >= 95f) 12 else 6)
        val bars = phraseBeats / beatsPerBar
        return downbeatIdx.filterIndexed { k, _ -> k % bars == 0 }.map { beatsMs[it].toLong() }
    }

    private fun slope(t: DoubleArray): Double {
        val n = t.size
        val mx = (n - 1) / 2.0
        val my = t.average()
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            num += (i - mx) * (t[i] - my)
            den += (i - mx) * (i - mx)
        }
        return num / den
    }

    private fun nearestIndex(sorted: DoubleArray, x: Double): Int {
        var best = 0
        var bd = abs(sorted[0] - x)
        for (i in 1 until sorted.size) {
            val d = abs(sorted[i] - x)
            if (d < bd) { bd = d; best = i } else if (sorted[i] > x) break
        }
        return best
    }
}
