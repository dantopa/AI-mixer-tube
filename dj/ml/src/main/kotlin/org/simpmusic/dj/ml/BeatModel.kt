package org.simpmusic.dj.ml

/** Frame-wise logits (pre-sigmoid) for one span of frames, 50 frames per second. */
class FrameLogits(val beat: FloatArray, val downbeat: FloatArray) {
    init {
        require(beat.size == downbeat.size) { "beat/downbeat length mismatch" }
    }

    val frames: Int get() = beat.size
}

/**
 * The only seam to a neural runtime: a log-mel spectrogram chunk `[frames][128]` (row-major) in,
 * beat + downbeat logits `[frames]` out. Everything else in this module is plain Kotlin.
 * Implementations must accept any `frames` up to [MAX_FRAMES] (the ONNX model has a dynamic time axis).
 */
interface BeatModel : AutoCloseable {
    fun run(spect: FloatArray, frames: Int): FrameLogits

    override fun close() {}

    companion object {
        const val N_MELS = 128
        const val MAX_FRAMES = 1500
    }
}

/**
 * Whole-piece inference exactly as `beat_this.inference.split_predict_aggregate`: the piece is cut into
 * [chunkSize]-frame chunks that overlap by [border] frames (the model is not trained on its edge frames),
 * the first / last chunk are zero-padded by [border], the last chunk start is shifted left so it ends at the
 * end of the piece, and where chunks overlap the EARLIER chunk's prediction wins (`keep_first`).
 */
object ChunkedInference {
    const val CHUNK_SIZE = 1500
    const val BORDER = 6

    /** Chunk start offsets (frames, may be negative for the first chunk). Mirrors `split_piece(avoid_short_end=True)`. */
    fun starts(length: Int, chunkSize: Int = CHUNK_SIZE, border: Int = BORDER): IntArray {
        if (length <= 0) return IntArray(0)
        val step = chunkSize - 2 * border
        val n = (length + step - 1) / step // np.arange(-border, length - border, step).size = ceil(length / step)
        val s = IntArray(maxOf(n, 1)) { -border + it * step }
        if (length > step) s[s.size - 1] = length - (chunkSize - border)
        return s
    }

    fun run(
        model: BeatModel,
        spect: FloatArray,
        frames: Int,
        chunkSize: Int = CHUNK_SIZE,
        border: Int = BORDER,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): FrameLogits {
        val mels = BeatModel.N_MELS
        require(spect.size == frames * mels) { "spect size ${spect.size} != $frames x $mels" }
        val beat = FloatArray(frames) { -1000f }
        val down = FloatArray(frames) { -1000f }
        if (frames == 0) return FrameLogits(beat, down)
        val starts = starts(frames, chunkSize, border)
        val outs = ArrayList<FrameLogits>(starts.size)
        for ((ci, start) in starts.withIndex()) {
            val from = maxOf(start, 0)
            val to = minOf(start + chunkSize, frames)
            val left = maxOf(0, -start)
            val right = maxOf(0, minOf(border, start + chunkSize - frames))
            val len = left + (to - from) + right
            val chunk = FloatArray(len * mels) // zero padded
            System.arraycopy(spect, from * mels, chunk, left * mels, (to - from) * mels)
            outs.add(model.run(chunk, len))
            onProgress?.invoke(ci + 1, starts.size)
        }
        // keep_first: write later chunks first so earlier ones overwrite them.
        for (ci in starts.indices.reversed()) {
            val start = starts[ci]
            val o = outs[ci]
            // strip the border on both sides of the prediction
            val usable = o.frames - 2 * border
            for (i in 0 until usable) {
                val dst = start + border + i
                if (dst in 0 until frames) {
                    beat[dst] = o.beat[border + i]
                    down[dst] = o.downbeat[border + i]
                }
            }
        }
        return FrameLogits(beat, down)
    }
}
