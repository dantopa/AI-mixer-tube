package org.simpmusic.dj.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.simpmusic.dj.android.settings.DjSettingsRepository
import org.simpmusic.dj.android.settings.simplified
import org.simpmusic.dj.model.DjSettings

class SimpleModeTest {
    @Test
    fun simpleModeKeepsOnlyAModestBeatMatchOrACrossfade() {
        val raw = DjSettings(enabled = true, allowKeyShift = true, bassSwap = true, maxTempoBend = 0.10f, overlapBars = 32)
        val s = raw.simplified()
        assertFalse(s.allowEchoOut)
        assertFalse(s.allowKeyShift)
        assertFalse(s.bassSwap)
        assertEquals(DjSettingsRepository.SIMPLE_MODE_MAX_BEND, s.maxTempoBend, 0f)
        // what the user chose for the rest is left alone
        assertEquals(32, s.overlapBars)
        assertTrue(s.enabled)
    }

    @Test
    fun aSmallerBendIsNotRaised() {
        assertEquals(0.03f, DjSettings(maxTempoBend = 0.03f).simplified().maxTempoBend, 0f)
    }
}
