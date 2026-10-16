package org.simpmusic.dj.timeline

import org.simpmusic.dj.analysis.VocalClash
import org.simpmusic.dj.model.Confident
import org.simpmusic.dj.model.DjSettings
import org.simpmusic.dj.model.PlanKind
import org.simpmusic.dj.model.TimeRange
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.model.VocalProfile
import org.simpmusic.dj.planner.DjTransitionPlanner
import org.simpmusic.dj.planner.MixMarks
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synced lyrics as the source of truth for where the voice is: the converter, the validation, the timeline, the planner. */
class LyricVocalsTest {
    @AfterTest
    fun uninstall() = Annotations.install(TrackAnnotations.NONE)

    /** LRC as LRCLIB / SimpMusic give it: one start per line, no end; "♫" for the gaps. */
    private fun lrc(vararg l: Pair<Double, String>) = l.map { (t, w) -> LyricLine(startMs = (t * 1000).toLong(), text = w) }

    @Test
    fun lrcLinesSingUntilTheNextLineAndTheBreaksStayEmpty() {
        val lines =
            lrc(
                12.0 to "Ya no quiero verte más",
                15.5 to "porque me hiciste llorar",
                19.2 to "y ahora vuelves a buscarme",
                23.0 to "♫",
                45.0 to "Ay, corazón",
                48.1 to "no me mientas otra vez",
                51.6 to "que ya sé lo que tú eres",
            )
        val r = LyricVocals.ranges(lines, durationMs = 180_000)
        // intro 0..12 empty; 12..23 sung (the "♫" marker ends it); break 23..45 empty; last line estimated, not to the end
        assertEquals(2, r.size, "$r")
        assertEquals(TimeRange(12_000, 23_000), r[0])
        assertEquals(45_000, r[1].startMs)
        assertTrue(r[1].endMs in 52_500..55_000, "the last line ends by its syllables, not at the end of the track: ${r[1]}")
    }

    @Test
    fun aLongLineWithoutWordsEndsByItsSyllables() {
        // a line 20 s before the next: the band plays most of it
        val r = LyricVocals.ranges(lrc(10.0 to "mi amor", 30.0 to "te quiero", 33.0 to "♫"))
        assertEquals(2, r.size, "$r")
        assertTrue(r[0].endMs - r[0].startMs in 1_500L..2_000L, "${r[0]}")
        assertEquals(TimeRange(30_000, 33_000), r[1])
    }

    @Test
    fun richSyncEndsALongLineAtItsLastWordAndKeepsShortGapsMerged() {
        val lines =
            listOf(
                LyricLine(5_000, null, listOf(5_000, 5_400, 5_900, 6_500), "Bailando contigo esta noche"),
                LyricLine(7_300, null, listOf(7_300, 7_800, 8_400), "hasta que salga"),
                // 25 s to the next line: ends at its last word + 0.6 s
                LyricLine(9_200, null, listOf(9_200, 9_700, 10_300), "el sol"),
                LyricLine(35_000, null, listOf(35_000, 35_500), "otra vez"),
            )
        val r = LyricVocals.ranges(lines)
        // 5.0 -> 7.3 -> 9.2 run together (gaps under 1.5 s), the long line ends at 10.3 + 0.6
        assertEquals(TimeRange(5_000, 10_900), r[0])
        assertEquals(35_000, r[1].startMs)
        assertEquals(36_100, r[1].endMs, "the last line: its last word + 0.6 s")
    }

    @Test
    fun ttmlDeclaredEndsAreUsedForLongLines() {
        val lines = listOf(LyricLine(5_000, 7_200, emptyList(), "hola"), LyricLine(40_000, 43_000, emptyList(), "adiós"), LyricLine(44_000, 46_000, emptyList(), "amor"))
        val r = LyricVocals.ranges(lines)
        assertEquals(listOf(TimeRange(5_000, 7_200), TimeRange(40_000, 46_000)), r)
    }

    @Test
    fun instrumentalMarkersAreNotVoices() {
        assertTrue(LyricVocals.isInstrumental("♫"))
        assertTrue(LyricVocals.isInstrumental("  "))
        assertTrue(LyricVocals.isInstrumental("..."))
        assertTrue(LyricVocals.isInstrumental("(Instrumental)"))
        assertFalse(LyricVocals.isInstrumental("Uh, ¡eso!"))
    }

    // ---- validation ----

    private val song = (0 until 30).map { i -> LyricLine(20_000L + i * 4_000L, text = "linea $i de la cancion") } + LyricLine(140_000, text = "♫")

    @Test
    fun lyricsOfAnotherRecordingAreRejected() {
        val b = LyricVocals.build(song, "lrclib", durationMs = 100_000)
        assertNull(b.vocals)
        assertTrue(b.rejected!!.contains("last line"), b.rejected)
        val d = LyricVocals.build(song, "lrclib", durationMs = 200_000, declaredDurationMs = 240_000)
        assertNull(d.vocals, "timed to 240 s, the track is 200 s")
        assertNull(LyricVocals.build(song.take(2), "lrclib", 200_000).vocals, "two lines are not lyrics")
    }

    @Test
    fun withoutDetectorEvidenceNoOffsetAndALowerConfidence() {
        val v = assertNotNull(LyricVocals.build(song, "lrclib", 200_000).vocals)
        assertEquals(0L, v.offsetMs)
        assertTrue(v.trusted, "still trusted: ${v.confidence}")
        assertTrue(v.confidence < 0.8f)
        assertEquals(30, v.lines)
        assertFalse(v.wordLevel)
    }

    /** A detector profile (480 ms frames) whose voice is the lyrics' ranges shifted by [shiftMs]; [band] lifts everything. */
    private fun profile(ranges: List<TimeRange>, shiftMs: Long, durMs: Long, band: Float = 0f, seed: Int = 3): VocalProfile {
        val r = Random(seed)
        val n = (durMs / 480).toInt()
        val score =
            List(n) { i ->
                val t = i * 480L + 240
                val voiced = ranges.any { t - shiftMs >= it.startMs && t - shiftMs < it.endMs }
                (if (voiced) -3.5f else -6f) + band + (r.nextFloat() - 0.5f) * 1.5f
            }
        return VocalProfile(480, score, List(n) { 0 })
    }

    private fun analysisWith(profile: VocalProfile?, durMs: Long) =
        TrackAnalysis(
            videoId = "t", analyzerId = "custom", analyzedAtEpochMs = 0, durationMs = durMs, bpm = null, beatTimesMs = null,
            downbeatBeatIndices = null, beatsPerBar = 4, phraseStartsMs = emptyList(), key = null, energyHopMs = 100,
            energy = emptyList(), lowBandEnergy = emptyList(), sections = null, vocals = null, loudnessDb = -14f, vocalProfile = profile,
        )

    @Test
    fun theOffsetSearchFindsAVideoWithALongerIntro() {
        // a breaky song: sung 20-60, 80-120, 140-170 on the album; the video plays 3.2 s more intro
        val lines =
            (listOf(20, 24, 28, 32, 36, 40, 44, 48, 52, 56) + listOf(80, 84, 88, 92, 96, 100, 104, 108, 112, 116) + listOf(140, 144, 148, 152, 156, 160, 164))
                .flatMap { s -> listOf(LyricLine(s * 1000L, text = "canto y canto"), LyricLine(s * 1000L + 3_600, text = "♫")) }
        val own = LyricVocals.ranges(lines)
        val dur = 200_000L
        val fit = LyricVocals.bestOffset(own, profile(own, 3_200, dur))
        println("offset fit: $fit")
        assertTrue(abs(fit.offsetMs - 3_200) <= 300, "found ${fit.offsetMs}")
        assertTrue(fit.best > fit.atZero + 0.1)
        val v = assertNotNull(LyricVocals.build(lines, "simpmusic", dur, analysisWith(profile(own, 3_200, dur), dur)).vocals)
        assertTrue(abs(v.offsetMs - 3_200) <= 300, v.describe())
        assertTrue(v.confidence >= 0.8f, v.describe())
        assertEquals(own.first().startMs + v.offsetMs, v.ranges.first().startMs)
        // the same evidence with the band heard as voice (+2.5 everywhere, the cumbia case): the shape still says where
        val band = assertNotNull(LyricVocals.build(lines, "simpmusic", dur, analysisWith(profile(own, 3_200, dur, band = 2.5f), dur)).vocals)
        assertTrue(abs(band.offsetMs - 3_200) <= 300, band.describe())
    }

    @Test
    fun evidenceThatDoesNotAgreeLowersTheConfidenceAndAppliesNoOffset() {
        val own = LyricVocals.ranges(song)
        val r = Random(9)
        val flat = VocalProfile(480, List(400) { -4f + (r.nextFloat() - 0.5f) * 1.5f }, List(400) { 0 })
        val v = assertNotNull(LyricVocals.build(song, "lrclib", 200_000, analysisWith(flat, 200_000)).vocals)
        println(v.describe())
        assertEquals(0L, v.offsetMs)
        assertTrue(v.confidence < 0.7f, v.describe())
        assertTrue(own.isNotEmpty())
    }

    @Test
    fun contradictingEvidenceRejectsTheLyricsUnlessTheDetectorIsTheOneNotBelieved() {
        // the detector's score is LOW where the lyrics sing and high in the gaps (an accordion break heard as a singer)
        val own = LyricVocals.ranges(song)
        val inverted = profile(own, 0, 200_000).let { p -> p.copy(score = p.score.map { -9.5f - it }) }
        val believed = analysisWith(inverted, 200_000).copy(vocals = Confident(listOf(TimeRange(0, 60_000)), 0.85f))
        val c = assertNotNull(LyricVocals.build(song, "lrclib", 200_000, believed).vocals)
        assertFalse(c.trusted, "a believable detection contradicts them: ${c.describe()}")
        val bandAsVoice = analysisWith(inverted, 200_000).copy(vocals = Confident(listOf(TimeRange(0, 195_000)), 0.85f))
        val b = assertNotNull(LyricVocals.build(song, "lrclib", 200_000, bandAsVoice).vocals)
        assertTrue(b.trusted, "the detector hears the band as voice (97 %): the lyrics still win: ${b.describe()}")
    }

    // ---- the timeline ----

    private class Lyrics(val map: Map<String, LyricVocals>) : TrackAnnotations {
        override fun downbeatAnchorMs(videoId: String): Long? = null

        override fun phraseAnchorMs(videoId: String): Long? = null

        override fun mixMarks(videoId: String): MixMarks? = null

        override fun lyricVocals(videoId: String): LyricVocals? = map[videoId]
    }

    @Test
    fun lyricsComeBeforeTheDetectorAndAreInTheCacheKey() {
        val a = analysis("tl", 300, listOf(TimeRange(10_000, 60_000)))
        val before = Timelines.of(a)
        assertEquals(Provenance.DETECTED, before.vocals!!.provenance)
        val lv = LyricVocals(listOf(TimeRange(20_000, 40_000)), "lrclib", false, 0, 0.8f, 10)
        Annotations.install(Lyrics(mapOf("tl" to lv)))
        val after = Timelines.of(a)
        assertNotSame(before, after, "new lyrics, new timeline")
        assertEquals(Provenance.LYRICS, after.vocals!!.provenance)
        assertEquals(lv.ranges, after.vocals?.value)
        assertTrue(after.vocalsFromLyrics)
        assertTrue("from lyrics lrclib" in after.describe(), after.describe())
        // untrusted lyrics leave the detector in charge, and the log says so
        Annotations.install(Lyrics(mapOf("tl" to lv.copy(confidence = 0.3f))))
        val weak = Timelines.of(a)
        assertEquals(Provenance.DETECTED, weak.vocals!!.provenance)
        assertTrue("lyrics ignored" in weak.describe(), weak.describe())
    }

    // ---- the planner ----

    /** As in StructureHandoffTest: 120 bpm, bars of 2 s, 150 s. */
    private fun analysis(id: String, n: Int, vocals: List<TimeRange>?): TrackAnalysis {
        val beats = List(n) { it * 500 }
        val downs = List(n / 4) { it * 4 }
        val dur = beats.last() + 1500L
        return TrackAnalysis(
            videoId = id, analyzerId = "custom", analyzedAtEpochMs = 0, durationMs = dur,
            bpm = Confident(120f, 0.9f), beatTimesMs = Confident(beats, 0.9f), downbeatBeatIndices = Confident(downs, 0.9f),
            beatsPerBar = 4, phraseStartsMs = downs.filter { it % 16 == 0 }.map { beats[it].toLong() }, key = null,
            energyHopMs = 100, energy = List((dur / 100).toInt() + 1) { 0.6f }, lowBandEnergy = List((dur / 100).toInt() + 1) { 0.5f },
            sections = null, vocals = vocals?.let { Confident(it, 0.85f) }, loudnessDb = -14f,
        )
    }

    /** LRC lines every 4 s over [from]..[to] s, with a "♫" marker where the section ends. */
    private fun section(from: Int, to: Int) = (from until to step 4).map { LyricLine(it * 1000L, text = "la cumbia sabrosa") } + LyricLine(to * 1000L, text = "♫")

    @Test
    fun lyricsFindTheInstrumentalHandoffTheDetectorsCoverageHid() {
        // A cumbia pair: YAMNet hears the band as voice, 0..143 s of 150 (95 %), so both detections are ignored and no
        // structure hand-off is possible. The lyrics say: outgoing sings 4..96 and 112..140 (an 8-bar instrumental after the
        // chorus), incoming has an 8-bar instrumental intro and sings from 16 s.
        val detector = listOf(TimeRange(0, 143_000))
        val out = analysis("cumbia-out", 300, detector)
        val inc = analysis("cumbia-in", 300, detector)
        val planner = DjTransitionPlanner()
        val settings = DjSettings(enabled = true)
        val blind = planner.plan(out, inc, settings)
        println("detector only: ${blind.reason}")
        assertFalse(blind.reason.startsWith("STRUCTURE"), blind.reason)

        val outLyrics = assertNotNull(LyricVocals.build(section(4, 96) + section(112, 140), "lrclib", out.durationMs, out).vocals)
        val inLyrics = assertNotNull(LyricVocals.build(section(16, 140), "betterlyrics", inc.durationMs, inc).vocals)
        assertEquals(TimeRange(4_000, 96_000), outLyrics.ranges.first())
        Annotations.install(Lyrics(mapOf("cumbia-out" to outLyrics, "cumbia-in" to inLyrics)))
        val plan = planner.plan(out, inc, settings)
        println("with lyrics: ${plan.reason}")
        assertEquals(PlanKind.BEAT_MATCHED, plan.kind)
        assertTrue(plan.reason.startsWith("STRUCTURE"), plan.reason)
        assertTrue("second tier" !in plan.reason, "instrumental over instrumental: ${plan.reason}")
        assertEquals(96_000, plan.exitPointMs, "the mix starts where the chorus ends")
        assertEquals(0, plan.entryPointMs, "the incoming plays its whole intro")
        assertEquals(16_000, plan.overlapMs)
        assertTrue("vocals: outgoing from lyrics lrclib" in plan.reason, plan.reason)
        val v = VocalClash.of(outLyrics.ranges, plan.exitPointMs, 1.0, inLyrics.ranges, plan.entryPointMs, 1.0, plan.overlapMs)!!
        assertEquals(0, v.clashMs)
    }

    @Test
    fun withoutLyricsThePlanIsTheDetectorsOwn() {
        // the same pair as StructureHandoffTest's first one, planned twice: once with no lyrics installed, once with lyrics
        // for an unrelated track; the plans are identical
        val out = analysis("plain-out", 300, listOf(TimeRange(4_000, 96_000), TimeRange(112_000, 140_000)))
        val inc = analysis("plain-in", 300, listOf(TimeRange(16_000, 140_000)))
        val planner = DjTransitionPlanner()
        val a = planner.plan(out, inc, DjSettings(enabled = true))
        Annotations.install(Lyrics(mapOf("someone-else" to LyricVocals(listOf(TimeRange(0, 1_000)), "lrclib", false, 0, 0.9f, 3))))
        val b = planner.plan(out, inc, DjSettings(enabled = true))
        assertEquals(a, b)
        assertTrue(a.reason.startsWith("STRUCTURE"), a.reason)
        assertFalse("from lyrics" in a.reason)
    }
}
