package org.simpmusic.dj.render

import org.simpmusic.dj.model.SourceLoop
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A deck source with a DJ loop ([SourceLoop]): virtual frames before the loop's end are the song itself; from there on
 * they repeat the loop. Each seam is an equal-power cross-fade of [SEAM_MS] from the song's own continuation (what the
 * previous pass would have played next) into the loop's start, so a loop cut on a beat never clicks.
 *
 * Reads go through fixed scratch buffers (no allocation per read); a request longer than them is served in chunks.
 */
class LoopSource(private val inner: DeckVoice.Source, loop: SourceLoop, sampleRate: Int) : DeckVoice.Source {
    private val start = loop.startFrame(sampleRate)
    private val len = loop.lengthFrames(sampleRate)
    private val end = start + len
    private val seam = min(len / 4, (sampleRate * SEAM_MS / 1000).toLong()).toInt().coerceAtLeast(1)
    private val a = arrayOf(FloatArray(CHUNK), FloatArray(CHUNK))
    private val b = arrayOf(FloatArray(CHUNK), FloatArray(CHUNK))
    private val fadeIn = FloatArray(seam) { sin((it + 0.5) / seam * PI / 2).toFloat() }
    private val fadeOut = FloatArray(seam) { cos((it + 0.5) / seam * PI / 2).toFloat() }

    override fun read(startFrame: Long, count: Int, dst: Array<FloatArray>) {
        var done = 0
        while (done < count) {
            val v = startFrame + done
            if (v < end) {
                // the song itself, up to the loop's end
                val n = min(count - done, min(CHUNK.toLong(), end - v).toInt())
                inner.read(v, n, a)
                copy(a, 0, dst, done, n)
                done += n
                continue
            }
            val k = Math.floorMod(v - start, len) // offset inside the loop
            if (k < seam) {
                // a seam: the continuation after the loop end fades out, the loop start fades in
                val n = min(count - done, min(CHUNK, (seam - k).toInt()))
                inner.read(start + k, n, a)
                inner.read(end + k, n, b)
                for (i in 0 until n) {
                    val j = (k + i).toInt()
                    dst[0][done + i] = a[0][i] * fadeIn[j] + b[0][i] * fadeOut[j]
                    dst[1][done + i] = a[1][i] * fadeIn[j] + b[1][i] * fadeOut[j]
                }
                done += n
            } else {
                val n = min(count - done, min(CHUNK.toLong(), len - k).toInt())
                inner.read(start + k, n, a)
                copy(a, 0, dst, done, n)
                done += n
            }
        }
    }

    private fun copy(src: Array<FloatArray>, from: Int, dst: Array<FloatArray>, at: Int, n: Int) {
        System.arraycopy(src[0], from, dst[0], at, n)
        System.arraycopy(src[1], from, dst[1], at, n)
    }

    companion object {
        /** Cross-fade at each seam (ms): short enough to keep the kick's attack, long enough not to click. */
        const val SEAM_MS = 8

        private const val CHUNK = 4096
    }
}
