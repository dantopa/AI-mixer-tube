package org.simpmusic.dj.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.simpmusic.dj.android.log.DjLog
import org.simpmusic.dj.android.log.RepeatFilter

/** The log diet: unchanged repeats are dropped, and the mix summary keeps what a failed mix needs (build-av device log). */
class LogDietTest {
    @Test
    fun anUnchangedLineIsLoggedOncePerIntervalAndAChangeAtOnce() {
        var now = 0L
        val f = RepeatFilter(everyMs = 60_000L) { now }
        assertTrue(f.shouldLog("a/NEXT_UP", "fresh"))
        now = 5_000L
        assertFalse(f.shouldLog("a/NEXT_UP", "fresh"))
        assertTrue(f.shouldLog("a/NEXT_UP", "queued")) // the answer changed
        assertTrue(f.shouldLog("b/NEXT_UP", "fresh")) // another track
        now = 70_000L
        assertTrue(f.shouldLog("a/NEXT_UP", "queued")) // a minute later, once
    }

    @Test
    fun aPollThatOnlyMovedItsNumbersSaysTheSame() {
        val a = "current=x next=y pos=512 dur=133881 -> no prepared window yet"
        val b = "current=x next=y pos=4900 dur=133881 -> no prepared window yet"
        assertEquals(RepeatFilter.withoutNumbers(a), RepeatFilter.withoutNumbers(b))
    }

    @Test
    fun theMixSummaryKeepsTheMixAndDropsTheChatter() {
        val keep = listOf(
            "10-16 15:20:01.000 +1ms [main] xition    I LIVE a -> b BEAT_MATCHED: plan 1 armed",
            "10-16 15:20:01.000 +1ms [dj-bus] bus       D deck 3: stream starts at song 102000 ms",
            "10-16 15:19:42.943 +1ms [dj-render] planner   I plan a->b = BEAT_MATCHED",
            "10-16 15:19:42.943 +1ms [main] lyrics    W lyrics b: timeout",
        )
        val drop = listOf(
            "10-16 15:19:16.003 +1ms [main] sched     D request a NOW_PLAYING: already analysed (fresh in store)",
            "10-16 15:19:19.144 +1ms [DefaultDispatcher-worker-10] planner   I plan c->d = BEAT_MATCHED",
            "10-16 15:19:19.837 +1ms [main] poll      D current=a next=b pos=512",
        )
        keep.forEach { assertTrue(it, DjLog.isMixLine(it)) }
        drop.forEach { assertFalse(it, DjLog.isMixLine(it)) }
    }
}
