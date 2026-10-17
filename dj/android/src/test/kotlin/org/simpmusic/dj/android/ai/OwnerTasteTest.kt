package org.simpmusic.dj.android.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OwnerTasteTest {
    @Test
    fun claudesWhyIsReadBackFromThePlansReason() {
        val r = "AI MIX: loop 2 bars 93.6-99.3 s under the overlap; exit 93.6 s, entry 80.9 s, 64 beats (bars untrusted) (capped to stay safe: 16 -> 8 bars: voice unknown) " +
            "(the intro (bar 2) under the last block); beat-matched 84.0 -> 85.7 bpm at 85.0"
        assertEquals("the intro (bar 2) under the last block", OwnerTaste.whyOf(r))
        assertNull(OwnerTaste.whyOf("beat-matched 84.0 -> 85.7 bpm"))
    }
}
