package org.simpmusic.dj

import org.simpmusic.dj.model.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ContractTest {
    @Test
    fun camelotWheel() {
        assertEquals("8B", MusicalKey(0, Mode.MAJOR).camelot()) // C major
        assertEquals("8A", MusicalKey(9, Mode.MINOR).camelot()) // A minor
        assertEquals("9B", MusicalKey(7, Mode.MAJOR).camelot()) // G major
        assertEquals("1A", MusicalKey(8, Mode.MINOR).camelot()) // G# minor
        assertEquals(1, Camelot.distance(MusicalKey(0, Mode.MAJOR), MusicalKey(9, Mode.MINOR))) // relative
        assertEquals(1, Camelot.distance(MusicalKey(0, Mode.MAJOR), MusicalKey(7, Mode.MAJOR))) // fifth
        assertEquals(0, Camelot.distance(MusicalKey(0, Mode.MAJOR), MusicalKey(0, Mode.MAJOR)))
    }

    @Test
    fun paramCurveInterpolates() {
        val c = ParamCurve(listOf(Keyframe(-1000, 1f), Keyframe(0, 1f), Keyframe(1000, 0f, Ease.LINEAR)))
        assertEquals(1f, c.valueAt(-5000))
        assertEquals(0.5f, c.valueAt(500))
        assertEquals(0f, c.valueAt(9999))
        val e = ParamCurve(listOf(Keyframe(0, 100f), Keyframe(1000, 400f, Ease.EXPONENTIAL)))
        assertEquals(200f, e.valueAt(500), 0.5f)
    }
}
