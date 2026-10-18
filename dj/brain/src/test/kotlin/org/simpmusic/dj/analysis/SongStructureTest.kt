package org.simpmusic.dj.analysis

import org.simpmusic.dj.model.Confident
import org.simpmusic.dj.model.TimeRange
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.timeline.Timelines
import kotlin.test.Test
import kotlin.test.assertEquals

class SongStructureTest {
    private val base: TrackAnalysis =
        kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(TrackAnalysis.serializer(), javaClass.getResource("/structure/7GUt3ooPJ1k.json")!!.readText())

    private fun track(vocals: List<TimeRange>?, durationMs: Long = 200_000): TrackAnalysis =
        base.copy(durationMs = durationMs, vocals = vocals?.let { Confident(it, 0.85f) }, vocalProfile = null)

    private fun sungOr(x: SongStructure.Section) = if (x.kind == SongStructure.Kind.VERSE || x.kind == SongStructure.Kind.CHORUS) "sung" else x.label

    @Test
    fun introVersesBreaksAndOutroAreNamed() {
        val a = track(listOf(TimeRange(12_000, 40_000), TimeRange(41_000, 60_000), TimeRange(80_000, 120_000), TimeRange(150_000, 180_000)))
        val s = SongStructure.of(Timelines.of(a))
        assertEquals(
            // verse or chorus depends on the real track's repetition (the fixture's frames): only "sung" is asserted here
            listOf("Intro", "sung", "Instrumental 1", "sung", "Instrumental 2", "sung", "Outro"),
            s.map { sungOr(it) },
        )
        assertEquals(0L, s.first().startMs)
        assertEquals(12_000L, s[1].startMs)
        assertEquals(60_000L, s[1].endMs) // the 1 s breath at 40 s does not split the verse
        assertEquals(200_000L, s.last().endMs)
    }

    @Test
    fun noVoiceInformationGivesNoSections() {
        assertEquals(emptyList(), SongStructure.of(Timelines.of(track(null))))
    }

    @Test
    fun aSongThatSingsAtOnceHasNoIntro() {
        val s = SongStructure.of(Timelines.of(track(listOf(TimeRange(300, 50_000)), 60_000)))
        assertEquals(listOf("sung", "Outro"), s.map { sungOr(it) })
        assertEquals(0L, s.first().startMs)
    }
}
