package org.simpmusic.dj.ml

import org.simpmusic.dj.model.Confident
import kotlin.test.Test
import kotlin.test.assertEquals

class TransientSnapTest {
    private val neural = List(64) { 300 + it * 500 }

    @Test
    fun trustedAgreeingDspGridSetsTheTimes() {
        val dsp = Confident(neural.map { it - 14 }, 0.9f) // physical transients 14 ms before the annotation convention
        val r = TransientSnap.refine(neural, listOf(300L, 4300L), dsp)
        assertEquals(neural.map { it - 14 }, r.beatTimesMs)
        assertEquals(listOf(286L, 4286L), r.phraseStartsMs)
    }

    @Test
    fun untrustedOrDisagreeingDspLeavesTheNeuralGridAlone() {
        val low = Confident(neural.map { it - 14 }, 0.3f)
        assertEquals(neural, TransientSnap.refine(neural, emptyList(), low).beatTimesMs)
        // a DSP grid a third of a beat away (half-time / wrong phase) must not be snapped to
        val wrong = Confident(neural.map { it + 170 }, 0.95f)
        assertEquals(neural, TransientSnap.refine(neural, emptyList(), wrong).beatTimesMs)
        assertEquals(neural, TransientSnap.refine(neural, emptyList(), null).beatTimesMs)
    }

    @Test
    fun partialAgreementBelowThresholdKeepsNeuralTimes() {
        val dsp = Confident(neural.mapIndexed { i, t -> if (i % 2 == 0) t - 10 else t + 200 }, 0.9f)
        assertEquals(neural, TransientSnap.refine(neural, emptyList(), dsp).beatTimesMs)
    }
}
