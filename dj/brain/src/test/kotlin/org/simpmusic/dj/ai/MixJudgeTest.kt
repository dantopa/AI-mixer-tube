package org.simpmusic.dj.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.simpmusic.dj.model.EqDeck
import org.simpmusic.dj.render.StereoPcm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MixJudgeTest {
    private val sr = 22_050
    private val beatMs = 500.0
    private val barMs = 2_000.0

    /** A four-on-the-floor kick (60 Hz bursts) from [startMs] to [endMs] of a window starting at -4 s, shifted by [offsetMs]. */
    private fun kicks(startMs: Double, endMs: Double, offsetMs: Double, lenMs: Double = 16_000.0): StereoPcm {
        val n = (lenMs * sr / 1000).toInt()
        val l = FloatArray(n)
        var t = startMs + offsetMs
        while (t < endMs) {
            val f0 = ((t + 4_000) * sr / 1000).toInt()
            for (i in 0 until sr / 12) {
                val k = f0 + i
                if (k in 0 until n) l[k] += (0.6 * sin(2 * PI * 60 * i / sr) * exp(-i / (sr * 0.03))).toFloat()
            }
            t += beatMs
        }
        return StereoPcm(l, l.copyOf(), sr)
    }

    private fun sum(a: StereoPcm, b: StereoPcm) = StereoPcm(FloatArray(a.frames) { a.left[it] + b.left[it] }, FloatArray(a.frames) { a.right[it] + b.right[it] }, sr)

    @Test
    fun alignedKicksAreTogetherAndAFlamIsMeasured() {
        val a = kicks(-4_000.0, 8_000.0, 0.0)
        val together = kicks(0.0, 12_000.0, 0.0)
        val v = MixJudge.judge(sum(a, together), a, together, -4_000.0, 8_000L, barMs)
        println(v.text)
        assertNotNull(v.kickLagMs)
        assertTrue(abs(v.kickLagMs!!) <= 4.0, "lag ${v.kickLagMs}")
        val late = kicks(0.0, 12_000.0, 30.0)
        val w = MixJudge.judge(sum(a, late), a, late, -4_000.0, 8_000L, barMs)
        println(w.text)
        assertTrue(abs(w.kickLagMs!! - 30.0) <= 4.0, "lag ${w.kickLagMs}")
        assertTrue(w.score < v.score, "a flam scores lower: ${w.score} vs ${v.score}")
        // two kicks at full level on every bar of the overlap: the bass clashes there
        assertTrue(v.bassClashBars.isNotEmpty())
    }

    @Test
    fun theEqAndKeyMovesAreParsed() {
        val o = Json.parseToJsonElement(
            """{"bass_swap_bar":-1,"fade":"smooth","filter":"none","echo_out":false,"loop_bars":0,"key_shift":3,
               "eq":[{"deck":"incoming","bar":0,"bass":"kill","highs":"on"},{"deck":"incoming","bar":4,"bass":"on","highs":"on"},{"deck":"outgoing","bar":4,"bass":"kill","highs":"kill"}]}""",
        ).jsonObject
        val m = MixBrief.movesOf(o)
        assertEquals(1, m.keyShift)
        assertEquals(3, m.eq.size)
        assertEquals(EqDeck.OUTGOING, m.eq.first().deck)
        assertTrue(m.describe().contains("eq") && m.describe().contains("key +1"), m.describe())
    }
}
