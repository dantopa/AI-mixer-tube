package org.simpmusic.dj.timeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StemsTest {
    /** 20 s at 250 ms: a voice from 10 s to 16 s with a 0.5 s breath at 13 s, a short shout at 4 s. */
    private val profile =
        StemProfile(
            hopMs = 250,
            vocals = List(80) { i ->
                when {
                    i in 16..17 -> -20 // 0.5 s shout
                    i in 40..63 && i !in 52..53 -> -14
                    else -> -70
                }
            },
            bass = List(80) { -20 },
            drums = List(80) { -22 },
            other = List(80) { -30 },
            model = "htdemucs",
        )

    @Test
    fun theVocalStemGivesTheSungRangesWithBreathsMergedAndShoutsDropped() {
        val r = profile.vocalRanges()
        assertEquals(1, r.size, r.toString())
        assertEquals(10_000L, r[0].startMs)
        assertEquals(16_000L, r[0].endMs)
    }

    @Test
    fun stemsWinOverLyricsAndTheDetectorInTheTimeline() {
        val raw = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(
            org.simpmusic.dj.model.TrackAnalysis.serializer(),
            javaClass.getResource("/structure/cXkiDWOrRZE.json")!!.readText(),
        )
        val before = Annotations.installed
        try {
            Annotations.install(object : TrackAnnotations by TrackAnnotations.NONE {
                override fun stems(videoId: String) = profile
            })
            val t = Timelines.of(raw)
            assertEquals(Provenance.STEMS, t.vocals?.provenance)
            assertTrue(t.describe().contains("stems"), t.describe())
        } finally {
            Annotations.install(before)
        }
    }
}
