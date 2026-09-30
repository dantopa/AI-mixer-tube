package org.simpmusic.dj

import org.simpmusic.dj.model.*
import org.simpmusic.dj.planner.DjTransitionPlanner
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlannerTest {
    private val planner = DjTransitionPlanner()
    private val settings = DjSettings(enabled = true)

    private val cache = HashMap<String, SyntheticTracks.Rendered>()
    private fun band(bpm: Float, key: MusicalKey = MusicalKey(9, Mode.MINOR), firstBeatMs: Int = 180): SyntheticTracks.Rendered =
        cache.getOrPut("$bpm-$key-$firstBeatMs") { SyntheticTracks.render(SyntheticTracks.Spec(bpm = bpm, key = key, firstBeatMs = firstBeatMs)) }

    private fun ana(bpm: Float, id: String, key: MusicalKey = MusicalKey(9, Mode.MINOR)) = FakeAnalysis.fromTruth(band(bpm, key).truth, id)

    /** Max |wall time of outgoing lattice beat - wall time of incoming lattice beat| over the overlap, in ms. */
    fun lockErrorMs(plan: TransitionPlan, from: TrackAnalysis, to: TrackAnalysis, strideOut: Int = 1, strideIn: Int = 1, minBeats: Int = 8): Double {
        val outBeats = from.beatTimesMs!!.value
        val inBeats = to.beatTimesMs!!.value
        val co = DeckClock.outgoing(plan)
        val ci = DeckClock.incoming(plan)
        val i0 = outBeats.indices.minBy { abs(outBeats[it] - plan.exitPointMs) }
        val j0 = inBeats.indices.minBy { abs(inBeats[it] - plan.entryPointMs) }
        var worst = 0.0
        var k = 0
        while (i0 + strideOut * k < outBeats.size && j0 + strideIn * k < inBeats.size) {
            val to1 = co.wallAt(outBeats[i0 + strideOut * k].toDouble())
            val ti = ci.wallAt(inBeats[j0 + strideIn * k].toDouble())
            if (to1 > plan.overlapMs) break
            worst = maxOf(worst, abs(to1 - ti))
            k++
        }
        assertTrue(k >= minBeats, "expected a few lattice beats inside the overlap, got $k")
        return worst
    }



    /** A hand-made grid: beat i at times(i); downbeats every 4th; phrases every 16 beats. */
    private fun customAnalysis(id: String, n: Int, bpmField: Float, downConf: Float = 0.9f, time: (Int) -> Double): TrackAnalysis {
        val beats = List(n) { time(it).toInt() }
        val downs = List(n / 4) { it * 4 }
        val dur = beats.last() + 1500L
        return TrackAnalysis(
            videoId = id, analyzerId = "custom", analyzedAtEpochMs = 0, durationMs = dur,
            bpm = Confident(bpmField, 0.9f), beatTimesMs = Confident(beats, 0.9f), downbeatBeatIndices = Confident(downs, downConf),
            beatsPerBar = 4, phraseStartsMs = downs.filter { it % 16 == 0 }.map { beats[it].toLong() }, key = null,
            energyHopMs = 100, energy = List((dur / 100).toInt() + 1) { 0.6f }, lowBandEnergy = List((dur / 100).toInt() + 1) { 0.5f },
            sections = null, vocals = null, loudnessDb = -14f,
        )
    }

    @Test
    fun tempoDecisionIsLocalNotGlobal() {
        // 120 bpm for the first half, 126 bpm (+5%) for the second; the bpm field is the misleading global 120
        val half = 100
        val a = customAnalysis("a", 200, 120f) { if (it < half) it * 500.0 else half * 500.0 + (it - half) * (60000.0 / 126) }
        val b = customAnalysis("b", 200, 126f) { it * (60000.0 / 126) + 200 }
        val plan = planner.plan(a, b, settings)
        println("local tempo: ${plan.reason}")
        assertEquals(PlanKind.BEAT_MATCHED, plan.kind)
        assertTrue(plan.exitPointMs > half * 500 + 3000, "exit should be in the 126 bpm half")
        // outgoing runs at 126 bpm where it is mixed, so no stretch is needed (a global 120 would demand x1.05)
        assertEquals(1f, plan.outgoing.rate.valueAt(plan.overlapMs), 0.01f)
        assertEquals(1f, plan.incoming.rate.valueAt(0), 0.01f)
        // and the reverse: the incoming track has the change, entered in its slow first half
        val plan2 = planner.plan(b, a, settings)
        assertEquals(PlanKind.BEAT_MATCHED, plan2.kind)
        // the incoming track is entered in its 120 bpm half: 126 -> 120 needs sqrt(120/126) = 0.976 on the outgoing deck
        assertEquals(Math.sqrt(120.0 / 126.0), plan2.outgoing.rate.valueAt(plan2.overlapMs).toDouble(), 0.004)
    }

    @Test
    fun aTempoRampInsideTheOverlapShortensItOrFallsBack() {
        val steady = customAnalysis("b", 200, 120f) { it * 500.0 + 200 }
        // outgoing slows down 12% over its last 64 beats: constant rates cannot follow that
        val drifting = customAnalysis("a", 200, 120f) { i -> if (i < 130) i * 500.0 else 130 * 500.0 + (i - 130) * 500.0 + 0.5 * 500.0 * 0.12 / 70.0 * (i - 130.0) * (i - 130.0) }
        val plan = planner.plan(drifting, steady, settings)
        println("drift: ${plan.kind} ${plan.reason}")
        val bar = 2000.0
        assertTrue(plan.kind == PlanKind.SIMPLE_CROSSFADE || plan.overlapMs / bar < 8.0)
        assertTrue(plan.reason.contains("drift") || plan.reason.contains("irregular") || plan.kind == PlanKind.BEAT_MATCHED)
        if (plan.kind == PlanKind.BEAT_MATCHED) {
            // whatever it kept must hold the 8 ms budget
            val co = DeckClock.outgoing(plan)
            val ci = DeckClock.incoming(plan)
            val ob = drifting.beatTimesMs!!.value
            val ib = steady.beatTimesMs!!.value
            val i0 = ob.indices.minBy { abs(ob[it] - plan.exitPointMs) }
            val j0 = ib.indices.minBy { abs(ib[it] - plan.entryPointMs) }
            var worst = 0.0
            var k = 0
            while (i0 + k < ob.size && j0 + k < ib.size) {
                val t = co.wallAt(ob[i0 + k].toDouble())
                if (t > plan.overlapMs) break
                worst = maxOf(worst, abs(t - ci.wallAt(ib[j0 + k].toDouble())))
                k++
            }
            println("kept overlap worst slide: $worst ms")
            assertTrue(worst < 10.0)
        }
        // a grid that wanders far more than that is refused with a clear reason
        val wild = customAnalysis("w", 200, 120f) { i -> i * 500.0 + 120.0 * kotlin.math.sin(i * 0.9) + (if (i % 3 == 0) 90.0 else 0.0) }
        val p2 = planner.plan(wild, steady, settings)
        println("irregular: ${p2.kind} ${p2.reason}")
        assertEquals(PlanKind.SIMPLE_CROSSFADE, p2.kind)
        assertTrue(p2.reason.contains("irregular") || p2.reason.contains("drift"))
    }

    @Test
    fun anIrregularLongOverlapWindowShrinksTheOverlapInsteadOfFallingBackToACrossfade() {
        // The owner's phone: 16 bars asked, "outgoing beat grid is irregular around the mix" and a plain crossfade.
        // A glitch every ~20 s (a drumless break, a wrong beat run) sits inside any 45 s window but not inside a short one.
        val glitchy = customAnalysis("g", 330, 85.7f) { i -> i * 700.0 + (i / 29) * 3000.0 }
        val steady = customAnalysis("s", 330, 85.7f) { i -> i * 700.0 }
        val asked = settings.copy(overlapBars = 16)
        val plan = planner.plan(glitchy, steady, asked)
        println("glitchy grid, 16 bars asked: ${plan.kind} overlap ${plan.overlapMs} ms | ${plan.reason}")
        assertEquals(PlanKind.BEAT_MATCHED, plan.kind)
        assertTrue(plan.overlapMs < 16 * 4 * 700, "overlap should have shrunk: ${plan.overlapMs}")
        assertTrue(plan.reason.contains("irregular over a longer overlap"))
    }

    @Test
    fun untrustedDownbeatsMeanBeatAlignmentAndAShorterOverlap() {
        val a = customAnalysis("a", 260, 120f, downConf = 0.05f) { it * 500.0 + 100 }
        val b = customAnalysis("b", 260, 122f, downConf = 0.05f) { it * (60000.0 / 122) + 100 }
        val plan = planner.plan(a, b, settings.copy(overlapBars = 8))
        println("no downbeats: ${plan.reason}")
        assertEquals(PlanKind.BEAT_MATCHED, plan.kind)
        assertTrue(plan.reason.contains("no bar alignment claimed"))
        assertTrue(plan.overlapMs <= 16.5 * 500.0, "overlap ${plan.overlapMs} should be capped at 4 bars")
        assertTrue(!plan.reason.contains("phrase"), "phrase starts must not be used when downbeats are untrusted")
        // and a CUT needs trusted downbeats
        val far = customAnalysis("c", 260, 160f, downConf = 0.05f) { it * 375.0 + 100 }
        assertEquals(PlanKind.SIMPLE_CROSSFADE, planner.plan(a, far, settings).kind)
    }

    /** Least-squares ideal grid (time = c + p * index) through ALL beats of an analysis: the fixture's own jitter averages out. */
    private fun idealGrid(a: TrackAnalysis): Pair<Double, Double> {
        val b = a.beatTimesMs!!.value
        val n = b.size
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (i in 0 until n) { sx += i; sy += b[i]; sxx += i.toDouble() * i; sxy += i.toDouble() * b[i] }
        val p = (n * sxy - sx * sy) / (n * sxx - sx * sx)
        return (sy - p * sx) / n to p
    }

    /** Phase-lock error measured on the ideal (unquantised) grids, so it isolates the planner from fixture noise. */
    fun lockErrorIdealMs(plan: TransitionPlan, from: TrackAnalysis, to: TrackAnalysis, strideOut: Int, strideIn: Int): Double {
        val (co, po) = idealGrid(from)
        val (ci, pi) = idealGrid(to)
        val clockO = DeckClock.outgoing(plan)
        val clockI = DeckClock.incoming(plan)
        val i0 = Math.round((plan.exitPointMs - co) / po).toInt()
        val j0 = Math.round((plan.entryPointMs - ci) / pi).toInt()
        var worst = 0.0
        var k = 0
        while (true) {
            val tOut = clockO.wallAt(co + po * (i0 + strideOut * k))
            if (tOut > plan.overlapMs) break
            val tIn = clockI.wallAt(ci + pi * (j0 + strideIn * k))
            worst = maxOf(worst, abs(tOut - tIn))
            k++
        }
        return worst
    }

    private fun assertWithinFiles(plan: TransitionPlan, from: TrackAnalysis, to: TrackAnalysis) {
        val co = DeckClock.outgoing(plan)
        val ci = DeckClock.incoming(plan)
        assertTrue(plan.exitPointMs >= 0 && plan.entryPointMs >= 0)
        assertTrue(co.sourceAt(plan.overlapMs.toDouble()) <= from.durationMs + 1, "outgoing runs past its file: ${co.sourceAt(plan.overlapMs.toDouble())} > ${from.durationMs}")
        assertTrue(ci.sourceAt(plan.settleMs.toDouble()) <= to.durationMs + 1, "incoming runs past its file")
        assertTrue(co.sourceAt(plan.preRollMs.toDouble()) >= -1, "pre-roll starts before the outgoing file")
    }

    @Test
    fun beatMatched124to128() {
        val from = ana(124f, "a")
        val to = ana(128f, "b", MusicalKey(9, Mode.MINOR))
        val plan = planner.plan(from, to, settings)
        println("124->128: ${plan.reason}")
        assertEquals(PlanKind.BEAT_MATCHED, plan.kind)
        // exit on a phrase start at the outro
        assertTrue(from.phraseStartsMs.any { abs(it - plan.exitPointMs) <= 2 }, "exit ${plan.exitPointMs} not a phrase start")
        val outro = from.sections!!.value.last { it.kind == SectionKind.OUTRO }
        assertTrue(plan.exitPointMs >= outro.range.startMs - 500, "exit before the outro")
        assertTrue(from.phraseStartsMs.any { abs(it - plan.entryPointMs) <= 2 } || abs(plan.entryPointMs - to.beatTimesMs!!.value[0]) <= 2)
        // geometric split: each deck stretched by about half the 3.2% difference, in opposite directions
        val ro = plan.outgoing.rate.valueAt(plan.overlapMs)
        val ri = plan.incoming.rate.valueAt(0)
        assertEquals(Math.sqrt(128.0 / 124.0), ro.toDouble(), 0.002)
        assertEquals(Math.sqrt(124.0 / 128.0), ri.toDouble(), 0.002)
        assertEquals(126f, plan.mixBpm!!, 0.3f)
        // 8 bars at the mix bpm, whole bars
        val bar = 4 * 60000.0 / plan.mixBpm!!
        assertEquals(8.0, plan.overlapMs / bar, 0.01)
        // tempo must be reached BEFORE T0 on the outgoing deck and be constant afterwards
        assertTrue(plan.preRollMs <= -8 * 60000L / 126)
        assertEquals(1f, plan.outgoing.rate.valueAt(plan.preRollMs))
        assertEquals(ro, plan.outgoing.rate.valueAt(0))
        assertEquals(ro, plan.outgoing.rate.valueAt(plan.overlapMs / 2))
        // incoming: constant during the overlap, back to native once alone
        assertEquals(ri, plan.incoming.rate.valueAt(plan.overlapMs / 2))
        assertEquals(1f, plan.incoming.rate.valueAt(plan.settleMs))
        // outgoing silent at the end, incoming at native level
        assertEquals(0f, plan.outgoing.volume.valueAt(plan.overlapMs))
        assertEquals(1f, plan.incoming.volume.valueAt(plan.overlapMs), 1e-4f)
        // bass swap present and in the middle
        assertEquals(20f, plan.outgoing.lowCutHz.valueAt(plan.overlapMs / 4))
        assertEquals(250f, plan.outgoing.lowCutHz.valueAt(plan.overlapMs * 3 / 4))
        assertEquals(250f, plan.incoming.lowCutHz.valueAt(plan.overlapMs / 4))
        assertEquals(20f, plan.incoming.lowCutHz.valueAt(plan.overlapMs * 3 / 4))
        assertTrue(plan.confidence >= 0.85f)
        assertWithinFiles(plan, from, to)
        val err = lockErrorMs(plan, from, to)
        println("phase lock error 124->128: $err ms")
        assertTrue(err < 1.5, "phase lock error $err ms")
    }

    @Test
    fun beatMatched128to124() {
        val from = ana(128f, "a")
        val to = ana(124f, "b")
        val plan = planner.plan(from, to, settings)
        println("128->124: ${plan.reason}")
        assertEquals(PlanKind.BEAT_MATCHED, plan.kind)
        assertTrue(plan.outgoing.rate.valueAt(plan.overlapMs) < 1f)
        assertTrue(plan.incoming.rate.valueAt(0) > 1f)
        assertWithinFiles(plan, from, to)
        assertTrue(lockErrorMs(plan, from, to) < 1.5)
    }

    @Test
    fun halfDoubleTime87to174() {
        val from = ana(87f, "a")
        val to = ana(174f, "b")
        val plan = planner.plan(from, to, settings)
        println("87->174: ${plan.reason}")
        assertEquals(PlanKind.BEAT_MATCHED, plan.kind)
        // no stretch needed: 174 is exactly double 87
        assertEquals(1f, plan.outgoing.rate.valueAt(plan.overlapMs), 0.002f)
        assertEquals(1f, plan.incoming.rate.valueAt(0), 0.002f)
        assertEquals(87f, plan.mixBpm!!, 0.5f)
        assertWithinFiles(plan, from, to)
        val err = lockErrorMs(plan, from, to, strideOut = 1, strideIn = 2)
        println("half/double lock error: $err ms")
        assertTrue(err < 2.0)
        // and the reverse
        val back = planner.plan(to, from, settings)
        assertEquals(PlanKind.BEAT_MATCHED, back.kind)
        assertTrue(lockErrorMs(back, to, from, strideOut = 2, strideIn = 1) < 2.0)
    }

    @Test
    fun halfDoubleWithBend() {
        // 90 vs 176: 176/2 = 88 -> 2.2% apart
        val plan = planner.plan(ana(90f, "a"), ana(176f, "b"), settings)
        println("90->176: ${plan.reason}")
        assertEquals(PlanKind.BEAT_MATCHED, plan.kind)
        assertTrue(plan.mixBpm!! in 88f..90f)
    }

    @Test
    fun beyondBendCutsOnDownbeatOrCrossfades() {
        val from = ana(120f, "a")
        val to = ana(150f, "b")
        val plan = planner.plan(from, to, settings)
        println("120->150: ${plan.kind} ${plan.reason}")
        assertEquals(PlanKind.CUT, plan.kind)
        assertEquals(0L, plan.overlapMs)
        assertTrue(from.downbeatBeatIndices!!.value.any { abs(from.beatTimesMs!!.value[it] - plan.exitPointMs) <= 1 })
        assertTrue(to.downbeatBeatIndices!!.value.any { abs(to.beatTimesMs!!.value[it] - plan.entryPointMs) <= 1 })
        assertEquals(1f, plan.outgoing.rate.valueAt(0))
        assertEquals(0f, plan.outgoing.volume.valueAt(0))
        assertEquals(1f, plan.incoming.volume.valueAt(plan.settleMs))
        assertWithinFiles(plan, from, to)

        // same, but downbeats are not trusted -> plain crossfade
        val weak = to.copy(downbeatBeatIndices = to.downbeatBeatIndices!!.copy(confidence = 0.2f))
        val p2 = planner.plan(from, weak, settings)
        println("120->150 weak downbeats: ${p2.kind} ${p2.reason}")
        assertEquals(PlanKind.SIMPLE_CROSSFADE, p2.kind)

        // 120 -> 130 is inside the split bend (+-4.1%) but not with a tighter setting
        val p3 = planner.plan(ana(120f, "a"), ana(130f, "c"), settings)
        assertEquals(PlanKind.BEAT_MATCHED, p3.kind)
        val p4 = planner.plan(ana(120f, "a"), ana(130f, "c"), settings.copy(maxTempoBend = 0.03f))
        assertEquals(PlanKind.CUT, p4.kind)
    }

    @Test
    fun lowConfidenceAndMissingFactsFallBack() {
        val from = ana(124f, "a")
        val to = ana(128f, "b")
        val lowBeats = to.copy(beatTimesMs = to.beatTimesMs!!.copy(confidence = 0.3f))
        val p = planner.plan(from, lowBeats, settings)
        assertEquals(PlanKind.SIMPLE_CROSSFADE, p.kind)
        assertTrue(p.reason.contains("beat grid"))
        // exit = end of file minus the fade, equal power
        assertTrue(abs(from.durationMs - settings.fallbackCrossfadeMs - p.exitPointMs) <= 200)
        assertEquals(settings.fallbackCrossfadeMs, p.overlapMs)
        assertEquals(0f, p.outgoing.volume.valueAt(p.overlapMs))
        assertEquals(1f, p.incoming.volume.valueAt(p.overlapMs))
        assertEquals(0.7071f, p.outgoing.volume.valueAt(p.overlapMs / 2), 0.01f)
        assertEquals(0.7071f, p.incoming.volume.valueAt(p.overlapMs / 2), 0.01f)

        assertEquals(PlanKind.SIMPLE_CROSSFADE, planner.plan(null, to, settings).kind)
        assertEquals(PlanKind.SIMPLE_CROSSFADE, planner.plan(from, null, settings).kind)
        assertEquals(PlanKind.SIMPLE_CROSSFADE, planner.plan(null, null, settings).kind)
        // the bpm field is informational: a grid alone is enough
        assertEquals(PlanKind.BEAT_MATCHED, planner.plan(from.copy(bpm = null), to, settings).kind)
        assertEquals(PlanKind.SIMPLE_CROSSFADE, planner.plan(from.copy(beatTimesMs = null), to, settings).kind)
        // confidence gate
        val mid = from.copy(beatTimesMs = from.beatTimesMs!!.copy(confidence = 0.6f))
        assertEquals(PlanKind.BEAT_MATCHED, planner.plan(mid, to, settings.copy(minConfidence = 0.5f)).kind)
        assertEquals(PlanKind.SIMPLE_CROSSFADE, planner.plan(mid, to, settings.copy(minConfidence = 0.8f)).kind)
    }

    @Test
    fun leadingSilenceIsSkippedAndEntryIsOnAGridPoint() {
        val r = band(128f, firstBeatMs = 1800)
        val to = FakeAnalysis.fromTruth(r.truth, "b", leadingSilenceMs = 1700)
        val plan = planner.plan(ana(124f, "a"), to, settings)
        assertEquals(PlanKind.BEAT_MATCHED, plan.kind)
        assertTrue(plan.entryPointMs >= 1700 - 200, "entry ${plan.entryPointMs} inside the silence")
        assertTrue(to.beatTimesMs!!.value.any { abs(it - plan.entryPointMs) <= 2 })
    }

    @Test
    fun trailingSilenceMovesTheExit() {
        val r = band(124f)
        val cut = r.truth.durationMs - 20_000
        val from = FakeAnalysis.fromTruth(r.truth, "a", trailingSilenceFromMs = cut, withSections = false)
        val plan = planner.plan(from, ana(126f, "b"), settings)
        assertEquals(PlanKind.BEAT_MATCHED, plan.kind)
        val co = DeckClock.outgoing(plan)
        assertTrue(co.sourceAt(plan.overlapMs.toDouble()) <= cut + 300, "overlap runs into the silent tail")
    }

    @Test
    fun overlapIsClampedByAShortOutro() {
        val r = SyntheticTracks.render(
            SyntheticTracks.Spec(
                bpm = 124f, key = MusicalKey(9, Mode.MINOR),
                sections = listOf(
                    SyntheticTracks.SectionSpec(SectionKind.INTRO, 8), SyntheticTracks.SectionSpec(SectionKind.BODY, 24),
                    SyntheticTracks.SectionSpec(SectionKind.OUTRO, 4),
                ),
            ),
        )
        val from = FakeAnalysis.fromTruth(r.truth, "a")
        val plan = planner.plan(from, ana(124f, "b"), settings)
        println("short outro: ${plan.reason}")
        assertEquals(PlanKind.BEAT_MATCHED, plan.kind)
        val bar = 4 * 60000.0 / plan.mixBpm!!
        assertEquals(4.0, plan.overlapMs / bar, 0.02)
    }

    @Test
    fun overlapIsClampedByAShortIntro() {
        val r = SyntheticTracks.render(
            SyntheticTracks.Spec(
                bpm = 124f, key = MusicalKey(9, Mode.MINOR),
                sections = listOf(
                    SyntheticTracks.SectionSpec(SectionKind.INTRO, 4), SyntheticTracks.SectionSpec(SectionKind.BODY, 24),
                    SyntheticTracks.SectionSpec(SectionKind.OUTRO, 8),
                ),
            ),
        )
        val to = FakeAnalysis.fromTruth(r.truth, "b")
        val plan = planner.plan(ana(124f, "a"), to, settings)
        val bar = 4 * 60000.0 / plan.mixBpm!!
        assertEquals(4.0, plan.overlapMs / bar, 0.02)
    }

    @Test
    fun keyClashIsFixedByAPitchShiftWithinLimits() {
        val from = ana(124f, "a", MusicalKey(9, Mode.MINOR)) // 8A
        val to = ana(124f, "b", MusicalKey(6, Mode.MINOR)) // F#m = 11A
        val plan = planner.plan(from, to, settings)
        println("key clash: ${plan.reason}")
        assertEquals(PlanKind.BEAT_MATCHED, plan.kind)
        val so = plan.outgoing.pitchSemitones.valueAt(0).toInt()
        val si = plan.incoming.pitchSemitones.valueAt(0).toInt()
        assertTrue(so != 0 || si != 0, "expected a pitch shift")
        assertTrue(abs(so) <= 2 && abs(si) <= 2)
        val ko = MusicalKey(((from.key!!.value.pitchClass + so) % 12 + 12) % 12, Mode.MINOR)
        val ki = MusicalKey(((to.key!!.value.pitchClass + si) % 12 + 12) % 12, Mode.MINOR)
        assertTrue(Camelot.distance(ko, ki) <= 1)
        // an incoming shift returns to 0 once alone; an outgoing one is reached before T0
        if (si != 0) assertEquals(0f, plan.incoming.pitchSemitones.valueAt(plan.settleMs))
        if (so != 0) assertEquals(0f, plan.outgoing.pitchSemitones.valueAt(plan.preRollMs))

        // no shift allowed: short EQ-heavy overlap and the clash is reported
        val p2 = planner.plan(from, to, settings.copy(allowKeyShift = false))
        println("key clash no shift: ${p2.reason}")
        assertTrue(p2.reason.contains("CLASH"))
        val bar = 4 * 60000.0 / p2.mixBpm!!
        assertTrue(p2.overlapMs / bar <= 4.01)
        assertEquals(0f, p2.outgoing.pitchSemitones.valueAt(0))
        assertTrue(p2.outgoing.highCutHz.valueAt(p2.overlapMs) < 5000f)
    }

    @Test
    fun compatibleKeysNeedNoShift() {
        val plan = planner.plan(ana(124f, "a", MusicalKey(9, Mode.MINOR)), ana(126f, "b", MusicalKey(0, Mode.MAJOR)), settings)
        assertEquals(0f, plan.outgoing.pitchSemitones.valueAt(0))
        assertEquals(0f, plan.incoming.pitchSemitones.valueAt(0))
        assertTrue(plan.reason.contains("compatible"))
    }

    @Test
    fun loudnessMatchAttenuatesTheLouderDeckOnly() {
        val a = ana(124f, "a").copy(loudnessDb = -8f)
        val b = ana(124f, "b").copy(loudnessDb = -14f)
        val plan = planner.plan(a, b, settings)
        // outgoing is 6 dB louder: dips to 0.5 by T0, incoming stays at unity
        assertEquals(0.501f, plan.outgoing.volume.valueAt(0), 0.01f)
        assertEquals(1f, plan.outgoing.volume.valueAt(plan.preRollMs))
        val peakIn = (0..plan.overlapMs step 50).maxOf { plan.incoming.volume.valueAt(it) }
        assertTrue(peakIn <= 1.0001f)
        val plan2 = planner.plan(b, a, settings)
        // incoming louder: attenuated at first, glides to 1.0
        assertTrue(plan2.incoming.volume.valueAt(plan2.overlapMs / 2) < 0.7f * 1.0f + 0.001f)
        assertEquals(1f, plan2.incoming.volume.valueAt(plan2.overlapMs), 1e-3f)
        for (p in listOf(plan, plan2)) for (d in listOf(p.outgoing, p.incoming)) assertTrue(d.volume.keys.all { it.value <= 1.0001f })
    }

    @Test
    fun bassSwapSkippedWhenThereIsNoBassAndWhenDisabled() {
        val from = ana(124f, "a")
        val to = ana(124f, "b")
        val noBass = to.copy(lowBandEnergy = to.lowBandEnergy.map { 0f })
        val p = planner.plan(from, noBass, settings)
        assertEquals(20f, p.outgoing.lowCutHz.valueAt(p.overlapMs))
        assertEquals(20f, p.incoming.lowCutHz.valueAt(0))
        val p2 = planner.plan(from, to, settings.copy(bassSwap = false))
        assertEquals(20f, p2.outgoing.lowCutHz.valueAt(p2.overlapMs))
    }

    @Test
    fun shortTracksNeverRunPastTheirFiles() {
        // outgoing is only 12 s long, incoming 9 s
        val r = SyntheticTracks.clickTrack(124f, 8, sampleRate = 8000)
        val short = FakeAnalysis.fromTruth(r.truth, "s", withSections = false)
        val plan = planner.plan(short, short.copy(videoId = "t"), settings)
        println("short tracks: ${plan.kind} ${plan.reason}")
        assertWithinFiles(plan, short, short.copy(videoId = "t"))
        val tiny = short.copy(durationMs = 2500)
        val p2 = planner.plan(tiny, tiny, settings)
        assertEquals(PlanKind.SIMPLE_CROSSFADE, p2.kind)
        assertTrue(p2.exitPointMs + p2.overlapMs <= 2500)
        assertTrue(p2.overlapMs <= 2500)
    }

    @Test
    fun beatsModeWhenDownbeatsAreUntrusted() {
        val from = ana(124f, "a")
        val to = ana(126f, "b")
        val weak = from.copy(downbeatBeatIndices = from.downbeatBeatIndices!!.copy(confidence = 0.2f))
        val plan = planner.plan(weak, to, settings)
        println("no downbeats: ${plan.reason}")
        assertEquals(PlanKind.BEAT_MATCHED, plan.kind)
        assertTrue(plan.reason.contains("downbeats untrusted"))
        assertTrue(lockErrorMs(plan, weak, to) < 1.5)
        // whole number of beats
        val beat = 60000.0 / plan.mixBpm!!
        val n = plan.overlapMs / beat
        assertEquals(Math.rint(n), n, 0.02)
    }

    @Test
    fun planRoundTripsThroughJson() {
        val plan = planner.plan(ana(124f, "a"), ana(128f, "b"), settings)
        val json = kotlinx.serialization.json.Json { prettyPrint = false }
        val back = json.decodeFromString<TransitionPlan>(json.encodeToString(TransitionPlan.serializer(), plan))
        assertEquals(plan, back)
    }

    @Test
    fun fuzzNeverThrowsAndStaysInsideTheFiles() {
        val rnd = java.util.Random(42)
        var beatMatched = 0
        var cuts = 0
        var simple = 0
        repeat(4000) { iter ->
            val a = randomAnalysis(rnd, "a$iter")
            val b = randomAnalysis(rnd, "b$iter")
            val s = DjSettings(
                enabled = true,
                overlapBars = rnd.nextInt(-3, 100),
                maxTempoBend = when (rnd.nextInt(5)) { 0 -> Float.NaN; 1 -> -1f; 2 -> 5f; else -> rnd.nextFloat() * 0.2f },
                allowKeyShift = rnd.nextBoolean(),
                maxPitchShift = rnd.nextInt(-2, 12),
                bassSwap = rnd.nextBoolean(),
                minConfidence = when (rnd.nextInt(4)) { 0 -> Float.NaN; 1 -> 2f; else -> rnd.nextFloat() },
                fallbackCrossfadeMs = if (rnd.nextInt(6) == 0) -5L else rnd.nextInt(0, 40_000).toLong(),
            )
            val fromArg = if (rnd.nextInt(20) == 0) null else a
            val toArg = if (rnd.nextInt(20) == 0) null else b
            val plan = planner.plan(fromArg, toArg, s)
            assertTrue(plan.overlapMs >= 0, "negative overlap")
            assertTrue(plan.exitPointMs >= 0 && plan.entryPointMs >= 0)
            assertTrue(plan.confidence in 0f..1f)
            for (d in listOf(plan.outgoing, plan.incoming)) {
                for (c in listOf(d.rate, d.pitchSemitones, d.volume, d.lowCutHz, d.highCutHz)) {
                    assertTrue(c.keys.all { it.value.isFinite() }, "non finite lane value: ${plan.reason}")
                }
                assertTrue(d.rate.keys.all { it.value in 0.5f..2f }, "absurd rate ${plan.reason}")
                assertTrue(d.volume.keys.all { it.value in 0f..1.0001f })
            }
            when (plan.kind) {
                PlanKind.BEAT_MATCHED -> beatMatched++
                PlanKind.CUT -> cuts++
                PlanKind.SIMPLE_CROSSFADE -> simple++
            }
            if (plan.kind != PlanKind.SIMPLE_CROSSFADE && fromArg != null && toArg != null) {
                val co = DeckClock.outgoing(plan)
                val ci = DeckClock.incoming(plan)
                val outDur = if (a.durationMs > 0) minOf(a.durationMs, 1_000_000_000L) else (a.beatTimesMs?.value?.maxOrNull()?.toLong()?.plus(1000) ?: 0)
                val inDur = if (b.durationMs > 0) minOf(b.durationMs, 1_000_000_000L) else (b.beatTimesMs?.value?.maxOrNull()?.toLong()?.plus(1000) ?: 0)
                assertTrue(co.sourceAt(plan.overlapMs.toDouble()) <= outDur + 2, "outgoing past file ${plan.reason}")
                assertTrue(ci.sourceAt(plan.overlapMs.toDouble()) <= inDur + 2, "incoming past file inDur=$inDur srcAt=${ci.sourceAt(plan.overlapMs.toDouble())} entry=${plan.entryPointMs} durRaw=${b.durationMs} lastBeat=${b.beatTimesMs?.value?.maxOrNull()} ${plan.reason}")
                assertTrue(plan.preRollMs > -60_000)
            } else if (fromArg != null && plan.kind == PlanKind.SIMPLE_CROSSFADE) {
                assertTrue(plan.exitPointMs + plan.overlapMs - 1 <= a.durationMs || a.durationMs <= 0, "simple exit past file")
            }
        }
        println("fuzz: beatMatched=$beatMatched cut=$cuts simple=$simple")
        assertTrue(beatMatched > 20, "fuzz never produced a beat-matched plan ($beatMatched)")
    }


    private fun gridTruth(rnd: java.util.Random, bpm: Float, bars: Int, withSections: Boolean): SyntheticTracks.Truth {
        val beatMs = 60000.0 / bpm
        val first = rnd.nextInt(0, 800)
        val n = bars * 4
        val beats = List(n) { (first + it * beatMs + rnd.nextGaussian() * 0.6).toInt() }
        val downs = List(bars) { it * 4 }
        val secs = if (!withSections) emptyList() else {
            val introBars = listOf(4, 8, 16)[rnd.nextInt(3)]
            val outroBars = listOf(4, 8, 16)[rnd.nextInt(3)]
            fun t(bar: Int) = (first + bar * 4 * beatMs).toLong()
            listOf(
                Section(TimeRange(t(0), t(introBars)), SectionKind.INTRO, 0.4f),
                Section(TimeRange(t(introBars), t(bars - outroBars)), SectionKind.BODY, 0.9f),
                Section(TimeRange(t(bars - outroBars), t(bars)), SectionKind.OUTRO, 0.5f),
            )
        }
        return SyntheticTracks.Truth(bpm, MusicalKey(rnd.nextInt(12), Mode.values()[rnd.nextInt(2)]), beats, downs, secs, (first + n * beatMs).toLong() + 1500)
    }

    @Test
    fun realisticFuzzKeepsPhaseLockedAndInsideTheFiles() {
        val rnd = java.util.Random(7)
        var matched = 0
        var worstLock = 0.0
        val kinds = HashMap<PlanKind, Int>()
        repeat(500) { iter ->
            val bpmA = 70f + rnd.nextFloat() * 110f
            val bpmB = when (rnd.nextInt(4)) { 0 -> bpmA * 2f * (0.97f + rnd.nextFloat() * 0.06f); 1 -> bpmA / 2f * (0.97f + rnd.nextFloat() * 0.06f); else -> bpmA * (0.9f + rnd.nextFloat() * 0.2f) }
            val ta = gridTruth(rnd, bpmA, rnd.nextInt(40, 140), rnd.nextBoolean())
            val tb = gridTruth(rnd, bpmB.coerceIn(60f, 200f), rnd.nextInt(40, 140), rnd.nextBoolean())
            val a = FakeAnalysis.fromTruth(ta, "a$iter", confidence = 0.6f + rnd.nextFloat() * 0.4f)
            val b = FakeAnalysis.fromTruth(tb, "b$iter", confidence = 0.6f + rnd.nextFloat() * 0.4f)
            val s = DjSettings(enabled = true, overlapBars = rnd.nextInt(1, 20), maxTempoBend = 0.03f + rnd.nextFloat() * 0.1f,
                allowKeyShift = rnd.nextBoolean(), maxPitchShift = rnd.nextInt(0, 4), bassSwap = rnd.nextBoolean(), minConfidence = 0.3f)
            val plan = planner.plan(a, b, s)
            kinds.merge(plan.kind, 1, Int::plus)
            assertWithinFiles(plan, a, b)
            if (plan.kind == PlanKind.BEAT_MATCHED) {
                matched++
                // bends within limits, tempo reached before T0 and constant over the overlap
                for (d in listOf(plan.outgoing, plan.incoming)) assertTrue(abs(d.rate.valueAt(plan.overlapMs / 2) - 1f) <= s.maxTempoBend + 1e-3f, plan.reason)
                assertEquals(plan.outgoing.rate.valueAt(0), plan.outgoing.rate.valueAt(plan.overlapMs), plan.reason)
                assertEquals(plan.incoming.rate.valueAt(0), plan.incoming.rate.valueAt(plan.overlapMs), plan.reason)
                assertTrue(plan.outgoing.rate.endMs <= 0, "outgoing tempo must settle before T0: ${plan.reason}")
                assertTrue(plan.incoming.rate.startMs >= 0)
                assertEquals(0f, plan.outgoing.volume.valueAt(plan.overlapMs))
                // phase lock, on whichever lattice the planner chose
                val err = listOf(1 to 1, 1 to 2, 2 to 1).minOf { (so, si) ->
                    lockErrorIdealMs(plan, a, b, so, si)
                }
                worstLock = maxOf(worstLock, err)
                assertTrue(err < 2.0, "phase lock $err ms: ${plan.reason}") // exit and entry are whole ms: up to 1 ms of that is rounding
            }
        }
        println("realistic fuzz: $kinds, worst phase-lock error ${"%.3f".format(worstLock)} ms over $matched beat-matched plans")
        assertTrue(matched > 250)
    }

    private fun randomAnalysis(rnd: java.util.Random, id: String): TrackAnalysis {
        val realistic = rnd.nextInt(3) != 0
        val bpm = if (realistic) 70f + rnd.nextFloat() * 110f else listOf(0f, -5f, Float.NaN, 1000f, 30f, 300f, 0.1f)[rnd.nextInt(7)]
        val beatMs = if (bpm.isFinite() && bpm > 1f) 60000.0 / bpm else 500.0
        val nBeats = if (rnd.nextInt(8) == 0) rnd.nextInt(0, 5) else rnd.nextInt(8, 700)
        val first = rnd.nextInt(-300, 3000)
        val beats = MutableList(nBeats) { (first + it * beatMs + (if (realistic) rnd.nextGaussian() * 4 else rnd.nextGaussian() * 400)).toInt() }
        if (rnd.nextInt(5) == 0) beats.shuffle(rnd)
        if (rnd.nextInt(8) == 0 && beats.isNotEmpty()) beats.add(beats[0])
        val downs = MutableList(nBeats / 4 + 1) { it * 4 }.let { l -> if (rnd.nextInt(6) == 0) l.map { it + rnd.nextInt(-3, 900) } else l }
        val dur = when (rnd.nextInt(8)) {
            0 -> 0L; 1 -> -100L; 2 -> Long.MAX_VALUE; 3 -> rnd.nextInt(0, 5000).toLong()
            else -> (first + nBeats * beatMs).toLong() + rnd.nextInt(-20_000, 5_000)
        }
        fun c() = if (rnd.nextInt(6) == 0) 0.05f else 0.5f + rnd.nextFloat() * 0.5f
        val hop = if (rnd.nextInt(10) == 0) 0 else 100
        val nE = rnd.nextInt(0, 400)
        val energy = List(nE) { if (rnd.nextInt(20) == 0) Float.NaN else rnd.nextFloat() * (if (rnd.nextInt(50) == 0) 5f else 1f) }
        val sections = if (rnd.nextInt(3) == 0) null else Confident(
            List(rnd.nextInt(0, 6)) {
                val s = rnd.nextInt(0, 200_000).toLong()
                Section(TimeRange(s, s + rnd.nextInt(0, 60_000)), SectionKind.values()[rnd.nextInt(6)], rnd.nextFloat())
            },
            c(),
        )
        return TrackAnalysis(
            videoId = id, analyzerId = "fuzz", analyzedAtEpochMs = 0, durationMs = dur,
            bpm = if (rnd.nextInt(8) == 0) null else Confident(bpm, c()),
            beatTimesMs = if (rnd.nextInt(8) == 0) null else Confident(beats, c()),
            downbeatBeatIndices = if (rnd.nextInt(6) == 0) null else Confident(downs, c()),
            beatsPerBar = listOf(null, 3, 4, 0, -2, 17)[rnd.nextInt(6)],
            phraseStartsMs = List(rnd.nextInt(0, 30)) { rnd.nextInt(-1000, 300_000).toLong() },
            key = if (rnd.nextInt(3) == 0) null else Confident(MusicalKey(rnd.nextInt(12), Mode.values()[rnd.nextInt(2)]), c()),
            energyHopMs = hop, energy = energy, lowBandEnergy = List(rnd.nextInt(0, 400)) { rnd.nextFloat() },
            sections = sections, vocals = null,
            loudnessDb = when (rnd.nextInt(8)) { 0 -> Float.NaN; 1 -> Float.NEGATIVE_INFINITY; 2 -> 12f; else -> -6f - rnd.nextFloat() * 20f },
        )
    }
}
