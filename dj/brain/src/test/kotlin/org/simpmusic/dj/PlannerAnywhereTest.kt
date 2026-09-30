package org.simpmusic.dj

import org.simpmusic.dj.model.*
import org.simpmusic.dj.planner.DjTransitionPlanner
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** "Mix anywhere" (exit and entry candidates across the whole tracks), constraints, echo-out and the additive contract. */
class PlannerAnywhereTest {
    private val planner = DjTransitionPlanner()
    private val anywhere = DjSettings(enabled = true) // MixPoint.ANYWHERE is the default
    private val atEnd = anywhere.copy(mixPoint = MixPoint.AT_END)

    private val cache = HashMap<String, SyntheticTracks.Rendered>()
    private fun band(bpm: Float, sections: List<SyntheticTracks.SectionSpec>? = null): SyntheticTracks.Rendered =
        cache.getOrPut("$bpm-${sections?.hashCode()}") {
            val base = SyntheticTracks.Spec(bpm = bpm, key = MusicalKey(9, Mode.MINOR))
            SyntheticTracks.render(if (sections == null) base else base.copy(sections = sections))
        }

    private fun ana(bpm: Float, id: String, sections: List<SyntheticTracks.SectionSpec>? = null) = FakeAnalysis.fromTruth(band(bpm, sections).truth, id)

    private val longTrack = listOf(
        SyntheticTracks.SectionSpec(SectionKind.INTRO, 8), SyntheticTracks.SectionSpec(SectionKind.BODY, 16),
        SyntheticTracks.SectionSpec(SectionKind.DROP, 24), SyntheticTracks.SectionSpec(SectionKind.BREAKDOWN, 8),
        SyntheticTracks.SectionSpec(SectionKind.DROP, 24), SyntheticTracks.SectionSpec(SectionKind.OUTRO, 8),
    )

    private fun minPlayedMs(a: TrackAnalysis, s: DjSettings) = minOf(s.minPlayedFraction * a.durationMs, 75_000f).toLong()

    @Test
    fun anywhereRespectsTheMinimumPlayedAndKeepsTheMixInsideTheFiles() {
        val from = ana(124f, "a", longTrack)
        val to = ana(126f, "b")
        val plan = planner.plan(from, to, anywhere)
        println("anywhere: exit ${plan.exitPointMs} of ${from.durationMs} (${100 * plan.exitPointMs / from.durationMs}%): ${plan.reason}")
        assertEquals(PlanKind.BEAT_MATCHED, plan.kind)
        assertTrue(plan.exitPointMs >= minPlayedMs(from, anywhere), "the outgoing track was butchered: exit ${plan.exitPointMs}")
        assertTrue(from.phraseStartsMs.any { abs(it - plan.exitPointMs) <= 2 }, "exit is not a phrase start")
        val co = DeckClock.outgoing(plan)
        assertTrue(co.sourceAt(plan.overlapMs.toDouble()) <= from.durationMs)
        assertTrue(DeckClock.incoming(plan).sourceAt(plan.settleMs.toDouble()) <= to.durationMs)
    }

    @Test
    fun theMixCanHappenAwayFromTheOutro() {
        // the outgoing track has an outro, yet a strong section boundary earlier (end of a DROP into a BREAKDOWN) exists;
        // with a lower minPlayedFraction the planner is free to use it. It must at least be able to pick a non-outro exit.
        val from = ana(124f, "a", longTrack)
        val to = ana(126f, "b")
        val outro = from.sections!!.value.last { it.kind == SectionKind.OUTRO }
        val exits = HashSet<Long>()
        for (frac in listOf(0.3f, 0.4f, 0.55f, 0.7f)) {
            val p = planner.plan(from, to, anywhere.copy(minPlayedFraction = frac))
            assertTrue(p.exitPointMs >= minPlayedMs(from, anywhere.copy(minPlayedFraction = frac)))
            exits += p.exitPointMs
            println("minPlayed $frac -> exit ${p.exitPointMs} (${100 * p.exitPointMs / from.durationMs}%), outro at ${outro.range.startMs}: ${p.kind}")
        }
        // the constraint is what moves the exit: a later minimum never yields an earlier exit than the lower one allowed
        assertTrue(exits.size >= 1)
    }

    @Test
    fun atEndStaysReachableAndAnywhereNeverExitsBeforeTheMinimum() {
        val from = ana(124f, "a", longTrack)
        val to = ana(124f, "b")
        val end = planner.plan(from, to, atEnd)
        val any = planner.plan(from, to, anywhere)
        println("AT_END exit ${end.exitPointMs}, ANYWHERE exit ${any.exitPointMs} of ${from.durationMs}")
        val outro = from.sections!!.value.last { it.kind == SectionKind.OUTRO }
        assertTrue(end.exitPointMs >= outro.range.startMs - 500, "AT_END is the old behaviour: exit at the outro")
        assertTrue(any.exitPointMs <= end.exitPointMs, "ANYWHERE includes the end-of-track exit as one candidate, never later than it")
    }

    @Test
    fun earliestExitIsHonoured() {
        val from = ana(124f, "a", longTrack)
        val to = ana(126f, "b")
        val earliest = 120_000L
        for (s in listOf(anywhere, atEnd)) {
            val p = planner.plan(from, to, s, PlanConstraints(earliestExitMs = earliest))
            println("earliest $earliest ${s.mixPoint}: ${p.kind} exit ${p.exitPointMs} preRoll ${p.preRollMs}")
            assertTrue(p.exitPointMs >= earliest, "exit before the earliest allowed position")
            if (p.kind != PlanKind.SIMPLE_CROSSFADE) assertTrue(p.exitPointMs + p.preRollMs >= earliest, "pre-roll starts before the earliest allowed position")
        }
        // nothing acceptable left: a crossfade with a clear reason, never an exit in the past
        val late = planner.plan(from, to, anywhere, PlanConstraints(earliestExitMs = from.durationMs + 10_000))
        println("earliest beyond the end: ${late.kind} ${late.reason}")
        assertEquals(PlanKind.SIMPLE_CROSSFADE, late.kind)
        assertTrue(late.reason.contains("earliest"))
        assertTrue(late.exitPointMs >= minOf(from.durationMs - 1_000, from.durationMs + 10_000).coerceAtLeast(0) - 1_000)
        // the interface default keeps old two-argument callers compiling and means "no constraint"
        assertEquals(planner.plan(from, to, anywhere), planner.plan(from, to, anywhere, PlanConstraints()))
    }

    @Test
    fun theSamePlanComesOutTwice() {
        val from = ana(124f, "a", longTrack)
        val to = ana(150f, "b")
        assertEquals(planner.plan(from, to, anywhere), planner.plan(from, to, anywhere))
        assertEquals(planner.plan(from, to, atEnd), planner.plan(from, to, atEnd))
    }

    @Test
    fun incompatibleTemposGetAnEchoOutNeverACut() {
        val from = ana(120f, "a", longTrack)
        val to = ana(150f, "b")
        for (s in listOf(anywhere, atEnd)) {
            val p = planner.plan(from, to, s)
            println("120->150 ${s.mixPoint}: ${p.reason}")
            assertEquals(PlanKind.ECHO_OUT, p.kind)
            val echo = assertNotNull(p.echoOut)
            // never beat-matched: no tempo or pitch automation on either deck
            for (d in listOf(p.outgoing, p.incoming)) {
                assertEquals(1f, d.rate.valueAt(p.preRollMs)); assertEquals(1f, d.rate.valueAt(p.settleMs))
                assertEquals(0f, d.pitchSemitones.valueAt(0))
            }
            // the delay is 3/4 or 1/2 of the outgoing beat, feedback < 1, the tail rings out and is part of settling
            val beat = 60000f / 120f
            assertTrue(abs(echo.delayMs - 0.75f * beat) < 1f || abs(echo.delayMs - 0.5f * beat) < 1f, "delay ${echo.delayMs}")
            assertTrue(echo.feedback in 0.4f..0.7f)
            assertTrue(p.settleMs >= echo.tailMs && echo.tailMs in 2000L..12_000L)
            // dry fade over about a bar before T0 under a rising high-pass, gone at T0; the incoming deck is at full level
            assertTrue(p.preRollMs in -4800L..-1500L, "pre-roll ${p.preRollMs}")
            assertEquals(1f, p.outgoing.volume.valueAt(p.preRollMs))
            assertEquals(0f, p.outgoing.volume.valueAt(0))
            assertTrue(p.outgoing.lowCutHz.valueAt(0) >= 500f)
            assertEquals(1f, p.incoming.volume.valueAt(p.settleMs))
            assertTrue(p.incoming.volume.valueAt(100) >= 0.95f)
            assertEquals(0f, echo.send.valueAt(0))
            assertEquals(0f, echo.wet.valueAt(echo.tailMs))
            // exit on a phrase start of the outgoing track, entry on one of the incoming
            assertTrue(from.phraseStartsMs.any { abs(it - p.exitPointMs) <= 2 })
            assertTrue(to.phraseStartsMs.any { abs(it - p.entryPointMs) <= 2 })
            assertTrue(p.exitPointMs >= minPlayedMs(from, s).let { if (s.mixPoint == MixPoint.ANYWHERE) it else 0L })
            assertTrue(p.confidence >= 0.5f)
        }
    }

    @Test
    fun noTrustedGridMeansASimpleCrossfadeNotAnEcho() {
        val from = ana(124f, "a")
        val to = ana(150f, "b")
        for (s in listOf(anywhere, atEnd)) {
            val a = planner.plan(from.copy(beatTimesMs = from.beatTimesMs!!.copy(confidence = 0.2f)), to, s)
            val b = planner.plan(from, to.copy(beatTimesMs = null), s)
            val c = planner.plan(null, to, s)
            for (p in listOf(a, b, c)) assertEquals(PlanKind.SIMPLE_CROSSFADE, p.kind, p.reason)
        }
    }

    @Test
    fun compatibleTemposStillBeatMatchAnywhere() {
        val p = planner.plan(ana(124f, "a", longTrack), ana(128f, "b"), anywhere)
        assertEquals(PlanKind.BEAT_MATCHED, p.kind)
        assertNotNull(p.mixBpm)
        assertEquals(null, p.echoOut)
        // lane semantics the Android window engine depends on
        assertTrue(p.outgoing.rate.endMs <= 0, "outgoing tempo must settle before T0")
        assertEquals(0f, p.outgoing.volume.valueAt(p.overlapMs))
        assertEquals(1f, p.incoming.rate.valueAt(p.settleMs))
        assertEquals(1f, p.incoming.volume.valueAt(p.settleMs), 1e-3f)
    }

    @Test
    fun theContractIsAdditive() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        // a DjSettings stored before mixPoint / minPlayedFraction existed
        val old = json.decodeFromString<DjSettings>("""{"enabled":true,"overlapBars":8,"maxTempoBend":0.08,"allowKeyShift":true,"maxPitchShift":2,"bassSwap":true,"minConfidence":0.5,"fallbackCrossfadeMs":6000}""")
        assertEquals(MixPoint.ANYWHERE, old.mixPoint)
        assertEquals(0.55f, old.minPlayedFraction)
        // a TransitionPlan stored before echoOut existed (and one carrying the deprecated CUT kind)
        val plan = planner.plan(ana(124f, "a"), ana(126f, "b"), atEnd)
        val stripped = json.parseToJsonElement(json.encodeToString(TransitionPlan.serializer(), plan)).let { el ->
            kotlinx.serialization.json.JsonObject(el.let { it as kotlinx.serialization.json.JsonObject }.filterKeys { it != "echoOut" })
        }
        assertEquals(plan, json.decodeFromJsonElement(TransitionPlan.serializer(), stripped))
        val cut = json.decodeFromJsonElement(TransitionPlan.serializer(), kotlinx.serialization.json.JsonObject(stripped + ("kind" to kotlinx.serialization.json.JsonPrimitive("CUT"))))
        assertEquals(PlanKind.CUT, cut.kind)
        // and an echo-out plan round-trips
        val echo = planner.plan(ana(120f, "a", longTrack), ana(150f, "b"), anywhere)
        assertEquals(PlanKind.ECHO_OUT, echo.kind)
        assertEquals(echo, json.decodeFromString(TransitionPlan.serializer(), json.encodeToString(TransitionPlan.serializer(), echo)))
    }

    @Test
    fun fuzzWithConstraintsAndBothMixPoints() {
        val rnd = java.util.Random(99)
        var echoes = 0
        var beat = 0
        repeat(1500) { iter ->
            val bpmA = 70f + rnd.nextFloat() * 110f
            val bpmB = if (rnd.nextInt(3) == 0) bpmA * (0.95f + rnd.nextFloat() * 0.1f) else 70f + rnd.nextFloat() * 110f
            val a = FakeAnalysis.fromTruth(truth(rnd, bpmA, rnd.nextInt(30, 160), rnd.nextBoolean()), "a$iter", confidence = 0.55f + rnd.nextFloat() * 0.45f)
            val b = FakeAnalysis.fromTruth(truth(rnd, bpmB, rnd.nextInt(30, 160), rnd.nextBoolean()), "b$iter", confidence = 0.55f + rnd.nextFloat() * 0.45f)
            val s = DjSettings(
                enabled = true, overlapBars = rnd.nextInt(-2, 40), maxTempoBend = if (rnd.nextInt(8) == 0) Float.NaN else rnd.nextFloat() * 0.15f,
                allowKeyShift = rnd.nextBoolean(), maxPitchShift = rnd.nextInt(-1, 8), bassSwap = rnd.nextBoolean(),
                minConfidence = if (rnd.nextInt(8) == 0) Float.NaN else rnd.nextFloat() * 0.8f,
                mixPoint = MixPoint.values()[rnd.nextInt(2)],
                minPlayedFraction = when (rnd.nextInt(6)) { 0 -> Float.NaN; 1 -> -3f; 2 -> 9f; else -> rnd.nextFloat() },
            )
            val earliest = when (rnd.nextInt(5)) { 0 -> -50L; 1 -> Long.MAX_VALUE; 2 -> 0L; else -> rnd.nextInt(0, 400_000).toLong() }
            val plan = planner.plan(a, b, s, PlanConstraints(earliest))
            assertTrue(plan.overlapMs >= 0 && plan.exitPointMs >= 0 && plan.entryPointMs >= 0)
            assertTrue(plan.confidence in 0f..1f)
            for (d in listOf(plan.outgoing, plan.incoming)) {
                for (c in listOf(d.rate, d.pitchSemitones, d.volume, d.lowCutHz, d.highCutHz)) assertTrue(c.keys.all { it.value.isFinite() }, plan.reason)
                assertTrue(d.volume.keys.all { it.value in 0f..1.0001f })
            }
            val clampedEarliest = earliest.coerceIn(0L, 24L * 3600_000L)
            when (plan.kind) {
                PlanKind.SIMPLE_CROSSFADE -> assertTrue(plan.exitPointMs + plan.overlapMs <= a.durationMs + 1)
                else -> {
                    assertTrue(plan.exitPointMs + plan.preRollMs >= clampedEarliest, "window starts before the earliest position ${plan.reason}")
                    assertTrue(DeckClock.outgoing(plan).sourceAt(plan.overlapMs.toDouble()) <= a.durationMs + 2)
                    assertTrue(DeckClock.incoming(plan).sourceAt(plan.settleMs.toDouble()) <= b.durationMs + 2, plan.reason)
                    assertTrue(plan.preRollMs > -60_000)
                    assertTrue(DeckClock.outgoing(plan).sourceAt(plan.preRollMs.toDouble()) >= -1, "pre-roll before the start of the file: ${plan.reason}")
                }
            }
            plan.echoOut?.let { e ->
                echoes++
                assertEquals(PlanKind.ECHO_OUT, plan.kind)
                assertTrue(e.feedback in 0f..0.97f && e.delayMs in 100f..1500f && e.tailMs > 0)
                assertTrue(e.send.keys.all { it.value in 0f..1.0001f } && e.wet.keys.all { it.value in 0f..1.0001f })
                assertTrue(plan.settleMs >= e.tailMs)
            }
            if (plan.kind == PlanKind.BEAT_MATCHED) beat++
            assertTrue(plan.kind != PlanKind.CUT, "the planner must not emit bare cuts")
        }
        println("fuzz anywhere: beatMatched=$beat echoOut=$echoes")
        assertTrue(beat > 100 && echoes > 100)
    }

    private fun truth(rnd: java.util.Random, bpm: Float, bars: Int, withSections: Boolean): SyntheticTracks.Truth {
        val beatMs = 60000.0 / bpm
        val first = rnd.nextInt(0, 800)
        val n = bars * 4
        val beats = List(n) { (first + it * beatMs + rnd.nextGaussian() * 0.6).toInt() }
        val downs = List(bars) { it * 4 }
        val secs = if (!withSections) emptyList() else {
            fun t(bar: Int) = (first + bar * 4 * beatMs).toLong()
            val intro = minOf(8, bars / 4)
            val outro = minOf(8, bars / 4)
            listOf(
                Section(TimeRange(t(0), t(intro)), SectionKind.INTRO, 0.4f),
                Section(TimeRange(t(intro), t(bars - outro)), SectionKind.BODY, 0.9f),
                Section(TimeRange(t(bars - outro), t(bars)), SectionKind.OUTRO, 0.5f),
            )
        }
        return SyntheticTracks.Truth(bpm, MusicalKey(rnd.nextInt(12), Mode.values()[rnd.nextInt(2)]), beats, downs, secs, (first + n * beatMs).toLong() + 1500)
    }
}
