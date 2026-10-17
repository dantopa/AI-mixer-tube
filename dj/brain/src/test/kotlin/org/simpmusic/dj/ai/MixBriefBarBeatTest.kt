package org.simpmusic.dj.ai

import kotlin.test.Test
import kotlin.test.assertEquals

class MixBriefBarBeatTest {
    private val bars = listOf(1000L, 3000L, 5000L, 7000L)

    @Test
    fun aTimeIsNamedByItsBarAndBeat() {
        assertEquals("-", MixBrief.barBeat(bars, 9000, 4, 500))
        assertEquals("0.1", MixBrief.barBeat(bars, 9000, 4, 1000))
        assertEquals("0.3", MixBrief.barBeat(bars, 9000, 4, 2100))
        assertEquals("2.4", MixBrief.barBeat(bars, 9000, 4, 6600))
        assertEquals("3.2", MixBrief.barBeat(bars, 9000, 4, 7600))
    }
}
