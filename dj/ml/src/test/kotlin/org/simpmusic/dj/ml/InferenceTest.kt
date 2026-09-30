package org.simpmusic.dj.ml

import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InferenceTest {
    @Test
    fun chunkStartsMatchPython() {
        val cases = Refs.json("chunks_ref.json")
        for ((len, v) in cases) {
            val expected = Refs.ints(v.jsonObject, "starts")
            assertContentEquals(expected, ChunkedInference.starts(len.toInt()), "starts for length $len")
        }
    }

    /** A model that echoes the absolute frame index it was given; aggregation must rebuild the identity ramp. */
    @Test
    fun chunkAggregationRebuildsWholePiece() {
        val cases = Refs.json("chunks_ref.json")
        for ((lenStr, v) in cases) {
            val len = lenStr.toInt()
            val sizes = Refs.ints(v.jsonObject, "sizes")
            var call = 0
            val model = object : BeatModel {
                override fun run(spect: FloatArray, frames: Int): FrameLogits {
                    assertEquals(sizes[call++], frames, "chunk size for length $len")
                    // first mel channel carries the frame index + 1 (zero == padding)
                    val out = FloatArray(frames) { spect[it * BeatModel.N_MELS] }
                    return FrameLogits(out, out.copyOf())
                }
            }
            val spect = FloatArray(len * BeatModel.N_MELS)
            for (f in 0 until len) spect[f * BeatModel.N_MELS] = (f + 1).toFloat()
            val out = ChunkedInference.run(model, spect, len)
            assertEquals(sizes.size, call)
            for (f in 0 until len) assertEquals((f + 1).toFloat(), out.beat[f], "frame $f of $len")
        }
    }

    @Test
    fun peakPickingMatchesPython() {
        val all = Refs.floats("logits_ref.f32")
        val ref = Refs.json("post_ref.json")
        val frames = all.size / 2
        val picks = BeatPostProcessor.pick(FrameLogits(all.copyOfRange(0, frames), all.copyOfRange(frames, 2 * frames)))
        val beats = Refs.doubles(ref, "beatsSec")
        val downs = Refs.doubles(ref, "downbeatsSec")
        assertEquals(beats.size, picks.beatsSec.size)
        assertEquals(downs.size, picks.downbeatsSec.size)
        for (i in beats.indices) assertEquals(beats[i], picks.beatsSec[i], 1e-6)
        for (i in downs.indices) assertEquals(downs[i], picks.downbeatsSec[i], 1e-6)
        assertTrue(picks.beatProb.all { it > 0.5 })
    }

    @Test
    fun dedupeMergesAdjacentPeaks() {
        assertContentEquals(doubleArrayOf(10.5, 20.0), BeatPostProcessor.dedupe(intArrayOf(10, 11, 20)))
        assertContentEquals(doubleArrayOf(), BeatPostProcessor.dedupe(intArrayOf()))
    }
}
