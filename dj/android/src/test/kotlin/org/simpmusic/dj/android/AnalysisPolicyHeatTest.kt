package org.simpmusic.dj.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.simpmusic.dj.android.scheduler.AnalysisPolicy
import org.simpmusic.dj.android.scheduler.BlockReason
import org.simpmusic.dj.android.scheduler.DeviceConditions
import org.simpmusic.dj.model.AnalysisPriority

class AnalysisPolicyHeatTest {
    private class C(
        override var isBatterySaver: Boolean = false,
        override var isCharging: Boolean = false,
        override var batteryPercent: Int = 90,
        override var isMetered: Boolean = false,
        override var thermalStatus: Int = 0,
    ) : DeviceConditions

    @Test
    fun backgroundWorkNeedsTheChargerAndACoolPhone() {
        val c = C()
        val p = AnalysisPolicy(c) { true }
        assertEquals(BlockReason.NOT_CHARGING, p.blockReason(AnalysisPriority.BACKGROUND))
        c.isCharging = true
        assertNull(p.blockReason(AnalysisPriority.BACKGROUND))
        c.thermalStatus = 1
        assertEquals(BlockReason.HOT, p.blockReason(AnalysisPriority.BACKGROUND))
    }

    @Test
    fun thePlayingAndNextTrackStopOnlyWhenThePhoneIsSeverelyHot() {
        val c = C(thermalStatus = 2)
        val p = AnalysisPolicy(c) { true }
        assertNull(p.blockReason(AnalysisPriority.NEXT_UP))
        c.thermalStatus = 3
        assertEquals(BlockReason.HOT, p.blockReason(AnalysisPriority.NEXT_UP))
        c.isCharging = true
        assertEquals("heat wins over the charger", BlockReason.HOT, p.blockReason(AnalysisPriority.NOW_PLAYING))
    }
}
