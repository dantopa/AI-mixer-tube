package org.simpmusic.dj

import org.simpmusic.dj.audio.WavIo
import org.simpmusic.dj.model.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyntheticTracksTest {
    @Test
    fun groundTruthIsConsistent() {
        val r = SyntheticTracks.render(SyntheticTracks.Spec(bpm = 124f, key = MusicalKey(9, Mode.MINOR)))
        val t = r.truth
        assertEquals(56 * 4, t.beatTimesMs.size)
        assertEquals(56, t.downbeatBeatIndices.size)
        // beats are 60000/124 ms apart, within rounding
        val gap = (t.beatTimesMs[64] - t.beatTimesMs[0]) / 64.0
        assertEquals(60000.0 / 124, gap, 0.6)
        assertTrue(r.audio.samples.any { it != 0f })
        assertTrue(r.audio.samples.all { kotlin.math.abs(it) <= 1f })
    }

    @Test
    fun wavRoundTrip() {
        val r = SyntheticTracks.clickTrack(120f, 2, sampleRate = 22050)
        val f = File.createTempFile("click", ".wav")
        WavIo.write(f, WavIo.Wav(arrayOf(r.audio.samples), r.audio.sampleRate))
        val back = WavIo.read(f)
        assertEquals(r.audio.sampleRate, back.sampleRate)
        assertEquals(r.audio.samples.size, back.frames)
        f.delete()
    }
}
