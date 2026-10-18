package org.simpmusic.dj.ai

import kotlinx.serialization.json.Json
import org.simpmusic.dj.model.AiMixPoints
import org.simpmusic.dj.model.Confident
import org.simpmusic.dj.model.DjSettings
import org.simpmusic.dj.model.PlanConstraints
import org.simpmusic.dj.model.PlanKind
import org.simpmusic.dj.model.PlanSource
import org.simpmusic.dj.model.TimeRange
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.planner.DjTransitionPlanner
import org.simpmusic.dj.timeline.LyricLine
import org.simpmusic.dj.timeline.Timelines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "AI Mix" on the owner's real case 1 (Lo Dejaría Todo -> A Prueba de Balas, `OwnerCasesTest`): the brief Claude reads, an
 * answer Claude could give, and the plan the planner makes of it. No network: the answer is written here.
 */
class MixBriefTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val settings = DjSettings(enabled = true, allowEchoOut = false, allowKeyShift = false, maxTempoBend = 0.06f)

    private fun load(id: String): TrackAnalysis =
        json.decodeFromString(TrackAnalysis.serializer(), javaClass.getResource("/structure/$id.json")!!.readText())

    private fun TrackAnalysis.withVoices(vararg r: Pair<Long, Long>) = copy(vocals = Confident(r.map { TimeRange(it.first, it.second) }, 0.85f))

    private val out = load("cXkiDWOrRZE").withVoices(5_500L to 7_900L, 13_700L to 17_500L, 47_800L to 92_400L)
    private val inc = load("7GUt3ooPJ1k").withVoices(5_000L to 79_400L, 91_000L to 139_900L)

    private fun brief(earliest: Long = 30_000L) =
        MixBrief.build(
            MixBrief.Track("Lo Dejaría Todo", Timelines.of(out)),
            MixBrief.Track("A Prueba de Balas", Timelines.of(inc), lyrics = listOf(LyricLine(5_000L, text = "primera línea"), LyricLine(91_000L, text = "vuelve la voz"))),
            earliestExitMs = earliest,
            requestedBars = 8,
            positionMs = earliest - 20_000L,
        )!!

    @Test
    fun anEntryThatWouldLeaveNoRoomForTheNextMixIsNotOffered() {
        // the chain: entering the incoming late would leave it no room to mix into the track after it
        val free = brief()
        val b =
            MixBrief.build(
                MixBrief.Track("Lo Dejaría Todo", Timelines.of(out)),
                MixBrief.Track("A Prueba de Balas", Timelines.of(inc)),
                earliestExitMs = 30_000L,
                requestedBars = 8,
                positionMs = 10_000L,
                entryLatestMs = 40_000L,
            )!!
        assertTrue(b.entryTo < free.entryTo, "limited ${b.entryTo} vs free ${free.entryTo}")
        assertTrue(b.inBarsMs[b.entryTo] <= 40_000L && b.inBarsMs[b.entryTo + 1] > 40_000L)
        assertTrue(b.user.contains("every song joins the one before AND the one after"), b.user.takeLast(600))
        assertTrue(MixBrief.parse(b, """{"exit_bar":${b.exitFrom},"entry_bar":${b.entryTo + 1},"overlap_bars":4,"bass_swap_bar":-1,"fade":"smooth","filter":"none","echo_out":false,"loop_bars":0,"why":"x"}""").isFailure)
    }

    @Test
    fun aPairSolvedAheadSaysTheOutgoingIsNotPlayingYet() {
        // AI Auto solves the next pairs while an earlier track plays: the outgoing is heard from where the mix before hands
        // it over, and its exits start 30 s after that, whatever the playhead
        val b =
            MixBrief.build(
                MixBrief.Track("Lo Dejaría Todo", Timelines.of(out)),
                MixBrief.Track("A Prueba de Balas", Timelines.of(inc)),
                earliestExitMs = 42_000L,
                requestedBars = 8,
                positionMs = 12_000L,
                ahead = true,
            )!!
        assertTrue(b.user.startsWith("OUTGOING (not playing yet"), b.user.take(120))
        assertTrue(b.user.contains("plays on its own from 0:12"), b.user.take(160))
        assertTrue(b.outBarsMs[b.exitFrom] >= 42_000L)
        assertTrue(brief().user.startsWith("OUTGOING (playing now"), "a current pair is unchanged")
    }

    @Test
    fun theBriefIsOneRowPerBarWithTheAllowedRanges() {
        val b = brief()
        println(b.user.lines().take(12).joinToString("\n"))
        assertTrue(b.outBarsMs.size >= 38, "bars of the outgoing: ${b.outBarsMs.size}")
        assertTrue(b.outBarsMs[b.exitFrom] >= 30_000L && (b.exitFrom == 0 || b.outBarsMs[b.exitFrom - 1] < 30_000L), "first allowed exit is the first bar after the earliest")
        assertTrue(b.user.contains("vuelve la voz"), "the lyrics are in the brief")
        assertTrue(b.user.contains("voice from the vocal detector"), b.user.take(400))
        // every row of the outgoing table is there, numbered from 0
        assertTrue(b.user.lines().any { it.startsWith("${b.outBarsMs.size - 1} ") })
        // small enough for a cheap model: a few thousand tokens, not tens
        assertTrue(b.user.length < 20_000, "brief length ${b.user.length}")
    }

    @Test
    fun theBriefCarriesTheFingerprintOfBothTracks() {
        val b = brief()
        val fp = b.user.lines().filter { it.startsWith("fingerprint:") }
        println(b.user.lines().dropWhile { !it.startsWith("fingerprint:") }.take(10).joinToString("\n"))
        assertEquals(2, fp.size, "one fingerprint per track")
        // phrases numbered from 1, with a part letter on every row
        val rows = b.user.lines().dropWhile { !it.startsWith("phrase bars") }.drop(1).takeWhile { it.firstOrNull()?.isDigit() == true }
        assertTrue(rows.size >= 4, "phrases: ${rows.size}")
        assertTrue(rows.all { r -> r.split(' ')[3].let { it.first().isLetter() || it == "?" } }, rows.joinToString("\n"))
    }

    @Test
    fun aShortAnswerIsLengthenedToFourBars() {
        val b = brief()
        val exitBar = b.outBarsMs.indexOfFirst { it >= 93_000L }
        val pts = MixBrief.parse(b, """{"exit_bar": $exitBar, "entry_bar": 2, "overlap_bars": 1, "why": "x"}""").getOrThrow()
        assertEquals(MixBrief.MIN_OVERLAP_BARS, pts.overlapBars)
    }

    @Test
    fun anAnswerOutsideTheAllowedRangeIsRefused() {
        val b = brief()
        val r = MixBrief.parse(b, """{"exit_bar": ${b.exitFrom - 1}, "entry_bar": 3, "overlap_bars": 8, "why": "x"}""")
        assertTrue(r.isFailure)
        assertTrue(MixBrief.parse(b, "not json").isFailure)
    }

    @Test
    fun claudesPointsArePlannedExactly() {
        val b = brief()
        // the answer the owner would give: the first's instrumental (~1:33) over the second's (~1:20), 4 bars
        val exitBar = b.outBarsMs.indexOfFirst { it >= 93_000L }
        val entryBar = b.inBarsMs.indexOfFirst { it >= 80_500L }
        val pts = MixBrief.parse(b, """{"exit_bar": $exitBar, "entry_bar": $entryBar, "overlap_bars": 4, "why": "instrumental over instrumental"}""").getOrThrow()
        val p = DjTransitionPlanner().plan(out, inc, settings, PlanConstraints(earliestExitMs = 30_000L, aiPoints = pts))
        println("ai: exit=${p.exitPointMs} entry=${p.entryPointMs} overlap=${p.overlapMs} | ${p.reason}")
        assertEquals(PlanKind.BEAT_MATCHED, p.kind)
        assertEquals(PlanSource.AI_MIX, p.meta?.source)
        assertTrue(p.reason.startsWith(AiMixPoints.TAG), p.reason)
        assertTrue(p.exitPointMs in b.outBarsMs[exitBar] - 1_000..b.outBarsMs[exitBar] + 1_000, "exit ${p.exitPointMs}")
        assertTrue(p.entryPointMs in b.inBarsMs[entryBar] - 1_000..b.inBarsMs[entryBar] + 1_000, "entry ${p.entryPointMs}")
    }

    @Test
    fun pointsThePlannerCannotUseFallBackToTheRegularPlanAndSayWhy() {
        // an exit before the earliest allowed one (the answer came too late): the regular plan, with the reason
        val pts = AiMixPoints(exitMs = 20_000L, entryMs = 0L, overlapBars = 8, why = "too early")
        val p = DjTransitionPlanner().plan(out, inc, settings, PlanConstraints(earliestExitMs = 60_000L, aiPoints = pts))
        assertTrue(p.meta?.source != PlanSource.AI_MIX)
        assertTrue(p.reason.contains(AiMixPoints.NOT_USED.trim()), p.reason)
    }

    /** Owner, build ba: a 16-bar (49 s) AI overlap between two tracks whose voices nobody knew "fue raro". */
    @Test
    fun aLongAiOverlapIsCappedWhenTheVoicesAreUnknown() {
        val pts = AiMixPoints(exitMs = 93_600L, entryMs = 80_900L, overlapBars = 16, why = "long")
        val known = DjTransitionPlanner().plan(out, inc, settings, PlanConstraints(earliestExitMs = 30_000L, aiPoints = pts))
        val blind = DjTransitionPlanner().plan(out.copy(vocals = null), inc.copy(vocals = null), settings, PlanConstraints(earliestExitMs = 30_000L, aiPoints = pts))
        assertEquals(PlanSource.AI_MIX, blind.meta?.source, blind.reason)
        assertTrue(AiMixPoints.UNSURE_CAP in blind.reason, blind.reason)
        assertTrue(AiMixPoints.UNSURE_CAP !in known.reason, known.reason)
        val f = blind.meta!!.features!!
        val bars = if (f.unitIsBar) f.overlapUnits else f.overlapUnits / (out.beatsPerBar ?: 4)
        assertTrue(bars <= AiMixPoints.UNSURE_MAX_BARS, blind.reason)
    }

    @Test
    fun theOwnersOwnEditIsNotCappedAndIsTaggedAsTheirs() {
        val pts = AiMixPoints(exitMs = 93_600L, entryMs = 80_900L, overlapBars = 16, why = "owner edit", byOwner = true)
        val p = DjTransitionPlanner().plan(out.copy(vocals = null), inc.copy(vocals = null), settings, PlanConstraints(earliestExitMs = 30_000L, aiPoints = pts))
        // the planner executes it like Claude's points (the engine relabels the source OWNER_EDIT), without the safety cap
        assertEquals(PlanSource.AI_MIX, p.meta?.source, p.reason)
        assertTrue(p.reason.startsWith(AiMixPoints.OWNER_TAG), p.reason)
        assertTrue(AiMixPoints.UNSURE_CAP !in p.reason, p.reason)
    }
}
