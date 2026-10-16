package org.simpmusic.dj

import kotlinx.serialization.json.Json
import org.simpmusic.dj.analysis.Harmony
import org.simpmusic.dj.analysis.VocalClash
import org.simpmusic.dj.model.*
import org.simpmusic.dj.planner.DjTransitionPlanner
import org.simpmusic.dj.planner.MixMarks
import org.simpmusic.dj.planner.PlanTags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The planner's structured result (`TransitionPlan.meta`) says what its reason text says, and what the code used to parse. */
class PlanMetaTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val planner = DjTransitionPlanner()
    private val ownerSettings = DjSettings(enabled = true, allowEchoOut = false, allowKeyShift = false, maxTempoBend = 0.06f)

    private fun load(id: String): TrackAnalysis =
        json.decodeFromString(TrackAnalysis.serializer(), javaClass.getResource("/structure/$id.json")!!.readText())

    private fun withMarks(m: Map<String, MixMarks>, block: () -> Unit) {
        val before = MixMarks.provider
        MixMarks.provider = { m[it] }
        try { block() } finally { MixMarks.provider = before }
    }

    // ---- owner marks

    @Test
    fun bothMarksAreOwnerMarksAndBest() {
        withMarks(mapOf("cXkiDWOrRZE" to MixMarks(exitMs = 93_000), "7GUt3ooPJ1k" to MixMarks(entryMs = 80_000, soloMs = 91_000))) {
            val p = planner.plan(load("cXkiDWOrRZE"), load("7GUt3ooPJ1k"), ownerSettings, PlanConstraints(preferPerfect = true, earliestExitMs = 30_000))
            val m = assertNotNull(p.meta, p.reason)
            assertEquals(PlanSource.OWNER_MARKS, m.source, p.reason)
            assertEquals(MixQuality.BEST, m.quality)
            assertFeaturesMatch(p)
        }
    }

    @Test
    fun aMarkedExitAloneIsOwnerPartialAndBest() {
        withMarks(mapOf("cXkiDWOrRZE" to MixMarks(exitMs = 93_000))) {
            val p = planner.plan(load("cXkiDWOrRZE"), load("7GUt3ooPJ1k"), ownerSettings, PlanConstraints(preferPerfect = true, earliestExitMs = 30_000))
            val m = assertNotNull(p.meta)
            assertEquals(PlanSource.OWNER_PARTIAL, m.source, p.reason)
            assertEquals(MixQuality.BEST, m.quality)
        }
    }

    @Test
    fun theExportPairsFromTheOwnersMarks() {
        val marks = mapOf(
            "lb5KOCVZB_E" to MixMarks(exitMs = 89_000, entryMs = 10_800, soloMs = 35_060),
            "OZGBG-GUDSo" to MixMarks(exitMs = 60_260, entryMs = 12_280, soloMs = 36_280),
        )
        withMarks(marks) {
            val both = planner.plan(load("lb5KOCVZB_E"), load("OZGBG-GUDSo"), ownerSettings, PlanConstraints(preferPerfect = true, earliestExitMs = 30_000))
            assertEquals(PlanSource.OWNER_MARKS, both.meta?.source, both.reason)
            assertEquals(MixQuality.BEST, both.meta?.quality)
            // Carita's exit is marked, "Ahora Estoy Solo" has no entry mark (its own exit/entry marks are not set here)
            val exitOnly = planner.plan(load("OZGBG-GUDSo"), load("Lw2aYkmrQcM"), ownerSettings, PlanConstraints(preferPerfect = true, earliestExitMs = 30_000))
            assertEquals(PlanSource.OWNER_PARTIAL, exitOnly.meta?.source, exitOnly.reason)
            assertEquals(MixQuality.BEST, exitOnly.meta?.quality)
        }
    }

    // ---- structure hand-offs

    @Test
    fun aStructureHandoffIsStructureAndBestWithFeaturesOfThePlan() {
        val out = analysis("out", 300, listOf(TimeRange(4_000, 96_000), TimeRange(112_000, 140_000)))
        val inc = analysis("in", 300, listOf(TimeRange(16_000, 140_000)))
        val p = planner.plan(out, inc, DjSettings(enabled = true))
        val m = assertNotNull(p.meta)
        assertEquals(PlanSource.STRUCTURE, m.source, p.reason)
        assertEquals(MixQuality.BEST, m.quality)
        val f = assertNotNull(m.features)
        assertEquals(p.overlapMs, f.overlapMs)
        assertEquals(8, f.overlapUnits)
        assertTrue(f.unitIsBar)
        assertTrue(f.vocalsKnownOut && f.vocalsKnownIn)
        val v = VocalClash.of(VocalClash.ranges(out), p.exitPointMs, f.rateOut.toDouble(), VocalClash.ranges(inc), p.entryPointMs, f.rateIn.toDouble(), p.overlapMs)!!
        assertEquals(v.clashMs, f.voiceOverVoiceMs)
        assertEquals(false, f.voiceClash)
        assertEquals(p.exitPointMs.toDouble() / out.durationMs, f.exitFraction.toDouble(), 1e-4)
        assertNull(m.failCode)
    }

    @Test
    fun anIncomingThatSingsAtOnceIsTheSecondTier() {
        val out = analysis("out", 300, listOf(TimeRange(4_000, 96_000), TimeRange(112_000, 140_000)))
        val inc = analysis("in", 300, listOf(TimeRange(500, 130_000)))
        val p = planner.plan(out, inc, DjSettings(enabled = true))
        assertEquals(PlanSource.STRUCTURE_SECOND_TIER, p.meta?.source, p.reason)
        assertEquals(MixQuality.TAIL, p.meta?.quality)
    }

    @Test
    fun withoutVocalsTheRegularSearchIsCleanAndCarriesItsPairScore() {
        val p = planner.plan(analysis("out", 300, null), analysis("in", 300, null), DjSettings(enabled = true))
        assertEquals(PlanKind.BEAT_MATCHED, p.kind, p.reason)
        val m = assertNotNull(p.meta)
        assertEquals(PlanSource.REGULAR, m.source, p.reason)
        assertEquals(MixQuality.CLEAN, m.quality)
        val f = assertNotNull(m.features)
        assertNotNull(f.pairScore)
        assertEquals(false, f.vocalsKnownOut)
        assertNull(f.voiceOverVoiceMs)
    }

    @Test
    fun atTheEndIsEndOfTrack() {
        val p = planner.plan(analysis("out", 300, null), analysis("in", 300, null), DjSettings(enabled = true, mixPoint = MixPoint.AT_END))
        assertEquals(PlanKind.BEAT_MATCHED, p.kind, p.reason)
        assertEquals(PlanSource.END_OF_TRACK, p.meta?.source)
        assertEquals(p.overlapMs, p.meta?.features?.overlapMs)
    }

    // ---- fallbacks

    @Test
    fun aSimpleCrossfadeCarriesAFailCode() {
        val inc = analysis("in", 300, null)
        val missing = planner.plan(null, inc, DjSettings(enabled = true))
        assertEquals(PlanKind.SIMPLE_CROSSFADE, missing.kind)
        assertEquals(PlanSource.CROSSFADE, missing.meta?.source)
        assertEquals(MixQuality.OTHER, missing.meta?.quality)
        assertEquals(PlanFailCode.NO_ANALYSIS, missing.meta?.failCode)

        val weak = analysis("out", 300, null).let { it.copy(beatTimesMs = it.beatTimesMs!!.copy(confidence = 0.1f)) }
        val untrusted = planner.plan(weak, inc, DjSettings(enabled = true))
        assertEquals(PlanKind.SIMPLE_CROSSFADE, untrusted.kind)
        assertEquals(PlanFailCode.GRID_UNTRUSTED, untrusted.meta?.failCode, untrusted.reason)
    }

    @Test
    fun tempoIncompatibleTracksSayWhy() {
        val out = analysis("out", 300, null)
        val fast = analysis("in", 300, null, beatMs = 375) // 160 bpm against 120: beyond any bend, also at half/double time
        val echo = planner.plan(out, fast, DjSettings(enabled = true))
        assertEquals(PlanKind.ECHO_OUT, echo.kind, echo.reason)
        assertEquals(PlanSource.ECHO_OUT, echo.meta?.source)
        assertEquals(MixQuality.OTHER, echo.meta?.quality)
        assertEquals(PlanFailCode.TEMPO_BEYOND_BEND, echo.meta?.failCode)
        val plain = planner.plan(out, fast, DjSettings(enabled = true, allowEchoOut = false))
        assertEquals(PlanKind.SIMPLE_CROSSFADE, plain.kind, plain.reason)
        assertEquals(PlanFailCode.TEMPO_BEYOND_BEND, plain.meta?.failCode, plain.reason)
    }

    @Test
    fun perfectRefusalsCarryAFailCode() {
        val p = planner.plan(analysis("out", 300, null), analysis("in", 300, null), DjSettings(enabled = true), PlanConstraints(perfect = true))
        assertTrue(p.reason.startsWith(PlanConstraints.PERFECT_REFUSED), p.reason)
        assertEquals(false, p.meta?.perfect)
        assertEquals(PlanFailCode.GRID_UNTRUSTED, p.meta?.failCode, p.reason) // no bar-phase evidence on these analyses
    }

    @Test
    fun metaSurvivesJson() {
        val p = planner.plan(analysis("out", 300, null), analysis("in", 300, null), DjSettings(enabled = true))
        val back = Json.decodeFromString(TransitionPlan.serializer(), Json.encodeToString(TransitionPlan.serializer(), p))
        assertEquals(p.meta, back.meta)
        // and a plan stored before the field existed still reads
        val old = Json.encodeToString(TransitionPlan.serializer(), p.copy(meta = null))
        assertNull(Json.decodeFromString(TransitionPlan.serializer(), old).meta)
    }

    /**
     * Every ordered pair of the owner's stored analyses, planned three ways (and with their export marks): the structured
     * meta must agree with what the reason says, i.e. with every text check the planner and the app used to make.
     */
    @Test
    fun metaAgreesWithTheReasonOnTheOwnersTracks() {
        val ids = listOf("7GUt3ooPJ1k", "Lw2aYkmrQcM", "OZGBG-GUDSo", "cXkiDWOrRZE", "lb5KOCVZB_E")
        val tracks = ids.associateWith { load(it) }
        val marks = mapOf(
            "lb5KOCVZB_E" to MixMarks(exitMs = 89_000, entryMs = 10_800, soloMs = 35_060),
            "cXkiDWOrRZE" to MixMarks(exitMs = 93_580, entryMs = 2_146, soloMs = 25_009),
            "OZGBG-GUDSo" to MixMarks(exitMs = 60_260, entryMs = 12_280, soloMs = 36_280),
        )
        val configs = listOf(
            DjSettings(enabled = true) to PlanConstraints(preferPerfect = true, earliestExitMs = 30_000),
            DjSettings(enabled = true) to PlanConstraints(earliestExitMs = 30_000),
            DjSettings(enabled = true, mixPoint = MixPoint.AT_END) to PlanConstraints(),
            ownerSettings to PlanConstraints(perfect = true, earliestExitMs = 30_000),
        )
        var n = 0
        val sources = HashMap<PlanSource, Int>()
        for (withOwner in listOf(false, true)) {
            withMarks(if (withOwner) marks else emptyMap()) {
                for (a in ids) for (b in ids) {
                    if (a == b) continue
                    for ((s, k) in configs) {
                        val p = planner.plan(tracks.getValue(a), tracks.getValue(b), s, k)
                        assertConsistent(p)
                        sources.merge(p.meta!!.source, 1, Int::plus)
                        n++
                    }
                }
            }
        }
        println("plan meta sweep: $n plans, sources $sources")
    }

    /** The structure scenarios of `StructureHandoffTest` (plus a tempo-incompatible pair), the same agreement. */
    @Test
    fun metaAgreesWithTheReasonOnSyntheticStructures() {
        val sung = listOf(TimeRange(4_000, 96_000), TimeRange(112_000, 140_000))
        val incomings = listOf(
            listOf(TimeRange(16_000, 140_000)), listOf(TimeRange(8_000, 140_000)), listOf(TimeRange(4_000, 5_500), TimeRange(16_000, 140_000)),
            listOf(TimeRange(500, 30_000), TimeRange(38_000, 140_000)), listOf(TimeRange(500, 130_000)), null,
        )
        val sources = HashMap<PlanSource, Int>()
        for (v in incomings) for (beatMs in listOf(500, 375)) {
            for (k in listOf(PlanConstraints(), PlanConstraints(preferPerfect = true), PlanConstraints(perfect = true))) {
                val p = planner.plan(analysis("out", 300, sung), analysis("in", 300, v, beatMs = beatMs), DjSettings(enabled = true), k)
                assertConsistent(p)
                sources.merge(p.meta!!.source, 1, Int::plus)
            }
        }
        println("synthetic plan meta sweep: sources $sources")
        assertTrue((sources[PlanSource.STRUCTURE] ?: 0) > 0 && (sources[PlanSource.STRUCTURE_SECOND_TIER] ?: 0) > 0, "$sources")
    }

    // ---------------------------------------------------------------------------------------------

    /** The look-ahead's ranking from the reason text, as it was before the meta existed. */
    private fun legacyQuality(p: TransitionPlan): MixQuality {
        val own = p.reason.substringBefore(" | ")
        return when {
            p.kind == PlanKind.BEAT_MATCHED && own.startsWith(MixMarks.TAG) -> MixQuality.BEST
            p.kind != PlanKind.BEAT_MATCHED || Harmony.CLASH_TAG in own || VocalClash.CLASH_TAG in own -> MixQuality.OTHER
            PlanTags.STRUCTURE in own -> if (PlanTags.SECOND_TIER in own) MixQuality.TAIL else MixQuality.BEST
            else -> MixQuality.CLEAN
        }
    }

    private fun assertConsistent(p: TransitionPlan) {
        val m = assertNotNull(p.meta, "no meta: ${p.reason}")
        val own = p.reason.substringBefore(" | ")
        assertEquals(legacyQuality(p), m.quality, p.reason)
        assertEquals(p.reason.startsWith(MixMarks.TAG), m.source == PlanSource.OWNER_MARKS || m.source == PlanSource.OWNER_PARTIAL, "${m.source}: ${p.reason}")
        assertEquals(p.reason.startsWith(PlanConstraints.PERFECT_OK), m.perfect, p.reason)
        assertEquals(PlanTags.STRUCTURE in own, m.source == PlanSource.STRUCTURE || m.source == PlanSource.STRUCTURE_SECOND_TIER, "${m.source}: ${p.reason}")
        when (p.kind) {
            PlanKind.SIMPLE_CROSSFADE -> {
                assertEquals(PlanSource.CROSSFADE, m.source)
                assertNotNull(m.failCode, p.reason)
            }
            PlanKind.ECHO_OUT -> {
                assertEquals(PlanSource.ECHO_OUT, m.source)
                assertNotNull(m.failCode, p.reason)
            }
            PlanKind.BEAT_MATCHED -> assertFeaturesMatch(p)
            PlanKind.CUT -> error("the planner no longer cuts")
        }
    }

    private fun assertFeaturesMatch(p: TransitionPlan) {
        val f = assertNotNull(p.meta?.features, p.reason)
        assertEquals(p.overlapMs, f.overlapMs, p.reason)
        assertEquals(VocalClash.CLASH_TAG in p.reason.substringBefore(" | "), f.voiceClash, p.reason)
        assertEquals(Harmony.CLASH_TAG in p.reason.substringBefore(" | "), f.harmonyClash, p.reason)
        assertTrue(f.overlapUnits >= 1)
        assertTrue(f.exitFraction in 0f..1f && f.entryFraction in 0f..1f)
    }

    private fun analysis(id: String, n: Int, vocals: List<TimeRange>?, beatMs: Int = 500): TrackAnalysis {
        val beats = List(n) { it * beatMs }
        val downs = List(n / 4) { it * 4 }
        val dur = beats.last() + 3L * beatMs
        return TrackAnalysis(
            videoId = id, analyzerId = "custom", analyzedAtEpochMs = 0, durationMs = dur,
            bpm = Confident(60_000f / beatMs, 0.9f), beatTimesMs = Confident(beats, 0.9f), downbeatBeatIndices = Confident(downs, 0.9f),
            beatsPerBar = 4, phraseStartsMs = downs.filter { it % 16 == 0 }.map { beats[it].toLong() }, key = null,
            energyHopMs = 100, energy = List((dur / 100).toInt() + 1) { 0.6f }, lowBandEnergy = List((dur / 100).toInt() + 1) { 0.5f },
            sections = null, vocals = vocals?.let { Confident(it, 0.85f) }, loudnessDb = -14f,
        )
    }
}
