package org.simpmusic.dj.android.window

import org.junit.Assert.assertEquals
import org.junit.Test

class CalibratorRestoreTest {
    @Test
    fun learnedValuesAreReportedAndRestored() {
        val a = LatencyCalibrator()
        var saved: Triple<Double, Double, Double>? = null
        a.onChanged = { saved = Triple(it.startLatencyMs, it.seekBiasMs, it.windowSeekBiasMs) }
        a.observeSeek(-100.0, SeekTarget.LIVE)
        a.observeSeek(40.0, SeekTarget.WINDOW)
        a.observeStart(30.0)
        val (s, l, w) = saved!!
        val b = LatencyCalibrator()
        b.restore(s, l, w)
        assertEquals(a.startLatencyMs, b.startLatencyMs, 1e-9)
        assertEquals(a.seekBiasMs, b.seekBiasMs, 1e-9)
        assertEquals(a.windowSeekBiasMs, b.windowSeekBiasMs, 1e-9)
    }

    @Test
    fun outOfRangeOrMissingValuesKeepTheDefaults() {
        val c = LatencyCalibrator()
        c.restore(Double.NaN, 5000.0, -900.0)
        assertEquals(120.0, c.startLatencyMs, 0.0)
        assertEquals(120.0, c.seekBiasMs, 0.0)
        assertEquals(120.0, c.windowSeekBiasMs, 0.0)
    }
}
