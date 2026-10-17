package org.simpmusic.dj.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetBriefTest {
    private fun t(id: String) = SetBrief.Track(id, "Song $id - Band", null)

    private val brief = SetBrief.build(t("now"), listOf(t("a"), t("b"), t("c"), t("d")))!!

    @Test
    fun theBriefLabelsEveryUpcomingTrackAndKeepsThePlayingOneOut() {
        assertTrue(brief.user.contains("PLAYING NOW (stays first): Song now - Band"))
        assertTrue(brief.user.contains("T4: Song d - Band | not analysed yet"), brief.user)
        assertEquals(listOf("T1", "T2", "T3", "T4"), brief.labels)
        assertNull(SetBrief.build(t("now"), listOf(t("a"), t("b"))), "two upcoming tracks are not worth ordering")
    }

    @Test
    fun claudesOrderIsMappedBackToIds() {
        val o = SetBrief.parse(brief, """{"order":["T3","T1","T4","T2"],"why":"tempo up"}""").getOrThrow()
        assertEquals(listOf("c", "a", "d", "b"), o.ids)
        assertEquals("tempo up", o.why)
    }

    @Test
    fun unknownRepeatedOrMissingLabelsCannotLoseOrInventATrack() {
        val o = SetBrief.parse(brief, """{"order":["t2","T9","T2","T4"],"why":""}""").getOrThrow()
        // T2, T4 as asked; T1 and T3 (left out) after them in the radio's order; T9 does not exist
        assertEquals(listOf("b", "d", "a", "c"), o.ids)
        assertTrue(SetBrief.parse(brief, """{"order":["T1"],"why":""}""").isFailure, "an order naming one of four is not an order")
    }
}
