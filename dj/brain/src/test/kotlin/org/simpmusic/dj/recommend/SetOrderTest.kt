package org.simpmusic.dj.recommend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SetOrderTest {
    private fun t(id: String, bpm: Double) = SetOrder.Track(id, bpm)

    /** The owner's build-ba radio: cumbia at 97 with tracks at 125, 122 and 108 mixed in. */
    @Test
    fun offTempoTracksAreGroupedInsteadOfBouncing() {
        val window = listOf(t("ocv", 125.0), t("ixo", 96.9), t("4eu", 122.0), t("1ez", 107.9), t("a7f", 102.0))
        val radio = SetOrder.jumpsInOrder(96.9, window, 0.06)
        val p = SetOrder.best(96.9, window, 0.06)!!
        println("radio order: $radio jumps; best: $p")
        assertEquals("ixo", p.ids.first(), "a 97 bpm track goes next, not the 125")
        assertTrue(p.jumps < radio, "fewer jumps than the radio's order ($radio)")
        // 125 and 122 end up next to each other
        val i = p.ids.indexOf("ocv")
        val j = p.ids.indexOf("4eu")
        assertEquals(1, kotlin.math.abs(i - j), p.toString())
    }

    @Test
    fun equalTemposKeepTheRadiosOrder() {
        val window = listOf(t("a", 85.0), t("b", 86.0), t("c", 84.5), t("d", 85.5))
        assertEquals(listOf("a", "b", "c", "d"), SetOrder.best(85.0, window, 0.06)!!.ids)
    }

    @Test
    fun halfAndDoubleTimeMix() {
        assertTrue(SetOrder.gap(85.0, 170.0) < 0.001)
        assertTrue(!SetOrder.isJump(84.0, 172.0, 0.06))
        assertTrue(SetOrder.isJump(97.0, 125.0, 0.06))
    }

    @Test
    fun forcingTheFirstSlotComparesCandidates() {
        val window = listOf(t("ocv", 125.0), t("ixo", 96.9), t("4eu", 122.0))
        val bad = SetOrder.best(96.9, window, 0.06, first = "4eu")!!
        val good = SetOrder.best(96.9, window, 0.06, first = "ixo")!!
        assertEquals("4eu", bad.ids.first())
        assertTrue(good.cost < bad.cost)
    }
}
