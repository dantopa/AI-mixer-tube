package org.simpmusic.dj.timeline

import kotlinx.serialization.json.Json
import org.simpmusic.dj.FakeAnalysis
import org.simpmusic.dj.SyntheticTracks
import org.simpmusic.dj.analysis.AnalysisRefiner
import org.simpmusic.dj.analysis.BarPhase
import org.simpmusic.dj.analysis.PhraseGrid
import org.simpmusic.dj.analysis.VocalClash
import org.simpmusic.dj.model.BarPhaseInfo
import org.simpmusic.dj.model.Confident
import org.simpmusic.dj.model.Mode
import org.simpmusic.dj.model.MusicalKey
import org.simpmusic.dj.model.PhraseInfo
import org.simpmusic.dj.model.TimeRange
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.planner.MixMarks
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** One truth per track: provenance, the owner's annotations in the cache key, the vocal trust rule, bar positions. */
class TrackTimelineTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** Annotations set per test (videoId -> value). */
    private val taps = HashMap<String, Long>()
    private val phraseMarks = HashMap<String, Long>()
    private val marks = HashMap<String, MixMarks>()

    private val annotations =
        object : TrackAnnotations {
            override fun downbeatAnchorMs(videoId: String): Long? = taps[videoId]

            override fun phraseAnchorMs(videoId: String): Long? = phraseMarks[videoId]

            override fun mixMarks(videoId: String): MixMarks? = marks[videoId]
        }

    @BeforeTest
    fun install() = Annotations.install(annotations)

    @AfterTest
    fun uninstall() = Annotations.install(TrackAnnotations.NONE)

    private fun load(id: String): TrackAnalysis =
        json.decodeFromString(TrackAnalysis.serializer(), javaClass.getResource("/structure/$id.json")!!.readText())

    private fun track(id: String, bpm: Float = 124f): TrackAnalysis {
        val spec = SyntheticTracks.Spec(bpm = bpm, key = MusicalKey(9, Mode.MINOR))
        return FakeAnalysis.fromTruth(SyntheticTracks.render(spec).truth, id)
    }

    /** Clear logits on the true 1s (as in BarPhaseTest): the bars get voted. */
    private fun withLogits(a: TrackAnalysis, seed: Int = 1): TrackAnalysis {
        val r = Random(seed)
        val downs = a.downbeatBeatIndices!!.value.toHashSet()
        val n = a.beatTimesMs!!.value.size
        return a.copy(beatDownbeatLogits = List(n) { i -> (if (i in downs) 3f else -3f) + (r.nextFloat() * 2 - 1) })
    }

    @Test
    fun caritaTristesTempoIsTheRefinedOneAndSaysSo() {
        val raw = load("OZGBG-GUDSo")
        val t = Timelines.of(raw)
        println(t.describe())
        assertEquals(133.9f, raw.bpm!!.value, 0.1f)
        val bpm = assertNotNull(t.bpm)
        assertTrue(bpm.value in 70f..100f, "refined ${bpm.value}")
        assertEquals(Provenance.REFINED, bpm.provenance)
        // exactly the grid the planner reads
        assertSame(AnalysisRefiner.cached(raw), t.refined)
        assertEquals(bpm.value, AnalysisRefiner.cached(raw).bpm!!.value)
        assertSame(t, Timelines.of(raw), "cached")
    }

    @Test
    fun aGridLeftAloneIsDetected() {
        val raw = track("plain")
        val t = Timelines.of(raw)
        assertEquals(Provenance.DETECTED, t.bpm!!.provenance)
        assertEquals(Provenance.DETECTED, t.bars.provenance)
        assertNull(t.mixMarks)
    }

    @Test
    fun aTappedOneIsTheOwnersAndGivesANewTimeline() {
        val raw = withLogits(track("tl-tap", bpm = 92f))
        val before = Timelines.of(raw)
        assertEquals(Provenance.REFINED, before.bars.provenance)
        taps["tl-tap"] = raw.beatTimesMs!!.value[raw.downbeatBeatIndices!!.value[5]].toLong()
        val after = Timelines.of(raw)
        assertNotSame(before, after)
        assertEquals(Provenance.OWNER, after.bars.provenance)
        assertTrue(after.bars.value)
        assertEquals(taps["tl-tap"], after.downbeatAnchorMs)
        assertSame(AnalysisRefiner.cached(raw), after.refined, "the refiner sees the same tap through the hook")
    }

    @Test
    fun aMarkedPhraseIsTheOwnersAndGivesANewTimeline() {
        val raw = withLogits(track("tl-phrase"))
        val before = Timelines.of(raw)
        val mark = raw.beatTimesMs!!.value[raw.downbeatBeatIndices!!.value[3]].toLong()
        phraseMarks["tl-phrase"] = mark
        val after = Timelines.of(raw)
        println(after.describe())
        assertNotSame(before, after)
        val blocks = assertNotNull(after.blocks)
        assertEquals(Provenance.OWNER, blocks.provenance)
        assertEquals(Provenance.OWNER, after.phrases!!.provenance)
        assertTrue(mark in blocks.value.startsMs)
        assertTrue(after.barPosition(mark + 10)!!.phraseMarked)
        assertEquals(0, after.barPosition(mark + 10)!!.barInBlock)
    }

    @Test
    fun mixMarksAreInTheTimelineAndInTheKey() {
        val raw = track("tl-marks")
        val before = Timelines.of(raw)
        marks["tl-marks"] = MixMarks(exitMs = 60_000L)
        val after = Timelines.of(raw)
        assertNotSame(before, after)
        assertEquals(MixMarks(exitMs = 60_000L), after.mixMarks)
        assertEquals(MixMarks(exitMs = 60_000L), MixMarks.of("tl-marks"), "the planner's hook reads the same install")
        assertTrue("exit 1:00" in after.describe(), after.describe())
        marks["tl-marks"] = MixMarks(exitMs = 61_000L)
        assertNotSame(after, Timelines.of(raw))
    }

    @Test
    fun vocalsOverNinetyPercentOfTheTrackAreUnknown() {
        val base = track("tl-vox")
        val dur = base.durationMs
        val most = base.copy(vocals = Confident(listOf(TimeRange(0, dur * 95 / 100)), 0.85f))
        val t = Timelines.of(most)
        assertNull(t.vocals)
        assertEquals(0.95f, t.vocalCoverage!!, 0.01f)
        assertNull(VocalClash.ranges(most))
        assertTrue("unknown (untrusted)" in t.describe(), t.describe())

        val half = base.copy(vocals = Confident(listOf(TimeRange(dur / 2, dur), TimeRange(0, dur / 4)), 0.85f))
        val h = assertNotNull(Timelines.of(half).vocals)
        assertEquals(Provenance.DETECTED, h.provenance)
        assertEquals(listOf(TimeRange(0, dur / 4), TimeRange(dur / 2, dur)), h.value, "sorted")
        assertEquals(h.value, VocalClash.ranges(half))

        val none = Timelines.of(base.copy(vocals = null))
        assertNull(none.vocals)
        assertNull(none.vocalCoverage)
    }

    @Test
    fun barPositionOnASyntheticGrid() {
        // beats every 500 ms from 1000 ms; a 1-beat pickup, then bars of 4 (downbeats at beat 1, 5, 9...)
        val beats = (0 until 40).map { 1000 + it * 500 }
        val downs = (1 until 40 step 4).toList()
        val raw =
            TrackAnalysis(
                videoId = "tl-grid", analyzerId = "test", analyzedAtEpochMs = 0, durationMs = 22_000,
                bpm = Confident(120f, 0.9f), beatTimesMs = Confident(beats, 0.9f), downbeatBeatIndices = Confident(downs, 0.9f),
                beatsPerBar = 4, key = null, energyHopMs = 100, energy = emptyList(), lowBandEnergy = emptyList(),
                sections = null, vocals = null, loudnessDb = -10f,
            )
        val refined =
            raw.copy(
                barPhase = BarPhaseInfo(source = "anchored", margin = 5f, trusted = true, slips = 0, changedBars = 0),
                phrases = PhraseInfo("detected", 4f, 1f, phrasesTrusted = true, blocksTrusted = false, irregular = 0,
                    blockStartsMs = emptyList(), barPositions = downs.indices.map { (it + 14) % 16 }),
            )
        val t = TrackTimeline(raw, refined, downbeatAnchorMs = 1500L, phraseAnchorMs = null, mixMarks = null)

        assertNull(t.barPosition(900), "before the first beat")
        assertNull(t.barPosition(1200), "in the pickup, before the first 1")
        val one = assertNotNull(t.barPosition(1500))
        assertEquals(BarPosition(beatIndex = 1, barIndex = 0, beatInBar = 0, beatsPerBar = 4, sure = true, anchored = true,
            barInBlock = 14, phraseSure = true, blockSure = false, phraseMarked = false), one)
        val p = assertNotNull(t.barPosition(4499)) // beat 6 (4000 ms): bar 1 (starts at beat 5), its second beat
        assertEquals(6, p.beatIndex)
        assertEquals(1, p.barIndex)
        assertEquals(1, p.beatInBar)
        assertEquals(15, p.barInBlock)
        assertEquals(0, t.barPosition(5600)!!.barInBlock, "bar 2 starts a block")

        assertEquals(3500L, t.nearestBarLineMs(3200))
        assertEquals(5500L, t.nearestBarLineMs(4600))
        assertEquals(0, t.nearestBeatIndex(10))
        assertEquals(3, t.nearestBeatIndex(2600))
        assertEquals(4000L, t.shiftedOneMs(4499), "the beat after the 1 of the bar")
        assertEquals(2000L, t.shiftedOneMs(1200), "in the pickup: the beat after the first 1")
        assertNull(t.shiftedOneMs(900))
        assertEquals((1 until 40 step 4).map { 1000L + it * 500 }, t.downbeatsMs)
    }

    @Test
    fun withoutBeatsThereIsNoPosition() {
        val t = Timelines.of(track("tl-empty").copy(beatTimesMs = null, downbeatBeatIndices = null))
        assertNull(t.barPosition(10_000))
        assertNull(t.nearestBarLineMs(10_000))
        assertNull(t.nearestBeatIndex(10_000))
        assertNull(t.shiftedOneMs(10_000))
    }

    @Test
    fun installRewiresTheHooksTestsReplaced() {
        BarPhase.anchors = { 1L }
        PhraseGrid.anchors = { 2L }
        MixMarks.provider = { MixMarks(soloMs = 3L) }
        Annotations.install(annotations)
        taps["x"] = 7L
        assertEquals(7L, BarPhase.anchors("x"))
        assertNull(PhraseGrid.anchors("x"))
        assertNull(MixMarks.of("x"))
    }
}
