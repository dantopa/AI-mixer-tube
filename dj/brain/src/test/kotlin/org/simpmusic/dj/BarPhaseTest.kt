package org.simpmusic.dj

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.simpmusic.dj.analysis.AnalysisRefiner
import org.simpmusic.dj.analysis.BarPhase
import org.simpmusic.dj.model.*
import org.simpmusic.dj.planner.DjTransitionPlanner
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which beat is the "1": the bar-phase vote over the tracker's per-beat downbeat logits. */
class BarPhaseTest {
    @AfterTest
    fun noAnchors() {
        BarPhase.anchors = { null }
    }

    /**
     * Beat This! fp32 per-beat downbeat logits of the 13-track electronic/pop corpus (Kevin MacLeod, CC-BY; see
     * corpus/SOURCES.md), with the reference downbeats the same model picks. Fixture: test resources barphase/km_logits.json.
     */
    @Test
    fun theVoteAgreesWithTheReferenceDownbeatsOnTheRealCorpus() {
        val text = javaClass.getResource("/barphase/km_logits.json")!!.readText()
        val root = Json.parseToJsonElement(text).jsonObject
        var bars = 0
        var agree = 0
        for ((name, v) in root) {
            val o = v.jsonObject
            val bpb = o["beatsPerBar"]!!.jsonPrimitive.int
            if (bpb != 4) continue // 3/4 and 2/4 references are folk-dance and canon: not what a DJ mixes
            val logits = o["logits"]!!.jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
            val ref = o["refDownbeatIdx"]!!.jsonArray.map { it.jsonPrimitive.int }.toHashSet()
            val d = BarPhase.decide(logits, bpb)
            val a = d.downbeats.count { it in ref }
            println("$name: ${a}/${d.downbeats.size} decided 1s on reference downbeats, slips ${d.slips}, margin %.1f".format(d.margin))
            assertTrue(a >= 0.85 * d.downbeats.size, "$name: only $a of ${d.downbeats.size}")
            assertTrue(d.margin >= BarPhase.PERFECT_MARGIN, "$name margin ${d.margin}")
            bars += d.downbeats.size
            agree += a
        }
        println("corpus: $agree / $bars bars")
        assertTrue(agree >= 0.95 * bars)
    }

    /** Logits like the tracker's: the true 1 at [one], beat 3 at [three], other beats at -4, plus noise. */
    private fun logits(a: TrackAnalysis, one: Float, three: Float, noise: Float, seed: Int = 1): List<Float> {
        val r = Random(seed)
        val downs = a.downbeatBeatIndices!!.value.toHashSet()
        val n = a.beatTimesMs!!.value.size
        return List(n) { i ->
            val base =
                when {
                    i in downs -> one
                    (i - 2) in downs -> three
                    else -> -4f
                }
            base + (r.nextFloat() * 2 - 1) * noise
        }
    }

    private fun track(id: String, bpm: Float = 124f): TrackAnalysis {
        val spec = SyntheticTracks.Spec(bpm = bpm, key = MusicalKey(9, Mode.MINOR))
        return FakeAnalysis.fromTruth(SyntheticTracks.render(spec).truth, id)
    }

    @Test
    fun aClearOneIsTrustedAndKept() {
        val t = track("clear")
        val a = t.copy(beatDownbeatLogits = logits(t, one = 3f, three = -3f, noise = 1f))
        val r = AnalysisRefiner.cached(a)
        val info = assertNotNull(r.barPhase)
        println(BarPhase.describe(r))
        assertEquals("voted", info.source)
        assertTrue(info.trusted)
        assertEquals(t.downbeatBeatIndices!!.value, r.downbeatBeatIndices!!.value)
        assertTrue(r.downbeatBeatIndices!!.confidence >= Trust.DOWNBEATS)
    }

    @Test
    fun theTrackersOwnWrongOneIsCorrectedByTheEvidence() {
        val t = track("shifted")
        // the stored downbeats sit on beat 3 (the half-bar error), the evidence says beat 1
        val wrong = t.downbeatBeatIndices!!.value.map { it + 2 }.filter { it < t.beatTimesMs!!.value.size }
        val a = t.copy(downbeatBeatIndices = Confident(wrong, 0.6f), beatDownbeatLogits = logits(t, one = 2.5f, three = -1f, noise = 1.5f))
        val r = AnalysisRefiner.cached(a)
        println(BarPhase.describe(r))
        assertEquals(t.downbeatBeatIndices!!.value, r.downbeatBeatIndices!!.value)
        assertTrue(r.barPhase!!.changedBars > 0)
    }

    @Test
    fun aDembowLikeHalfBarAmbiguityIsNotTrusted() {
        val t = track("dembow", bpm = 92f)
        val a = t.copy(beatDownbeatLogits = logits(t, one = 0.3f, three = 0.1f, noise = 1.2f))
        val r = AnalysisRefiner.cached(a)
        println(BarPhase.describe(r))
        assertFalse(r.barPhase!!.trusted)
        assertTrue(r.downbeatBeatIndices!!.confidence < Trust.DOWNBEATS, "an unsure 1 must not claim bars")
    }

    @Test
    fun theOwnersTappedOneWins() {
        val t = track("tapped", bpm = 92f)
        val a = t.copy(beatDownbeatLogits = logits(t, one = 0.3f, three = 0.1f, noise = 1.2f))
        // the owner hears the 1 on what the analysis calls beat 3 of bar 10
        val beats = t.beatTimesMs!!.value
        val tapped = t.downbeatBeatIndices!!.value[10] + 2
        BarPhase.anchors = { if (it == "tapped") beats[tapped].toLong() + 30 else null }
        val r = AnalysisRefiner.cached(a)
        println(BarPhase.describe(r))
        assertEquals("anchored", r.barPhase!!.source)
        assertTrue(r.barPhase!!.trusted)
        assertTrue(tapped in r.downbeatBeatIndices!!.value)
        assertTrue(r.downbeatBeatIndices!!.value.all { (it - tapped) % 4 == 0 })
    }

    @Test
    fun anInsertedTwoBeatBarIsFollowed() {
        val t = track("slip")
        val n = t.beatTimesMs!!.value.size
        val downs = t.downbeatBeatIndices!!.value
        // a 2/4 bar at bar 20: from there the true 1s are two beats later than a strict 4-beat count
        val shiftAt = downs[20]
        val truth = downs.filter { it < shiftAt } + (shiftAt until n step 4).map { it + 2 }.filter { it < n }
        val set = truth.toHashSet()
        val r = Random(3)
        val ev = List(n) { i -> (if (i in set) 3f else -4f) + (r.nextFloat() * 2 - 1) }
        val d = BarPhase.decide(ev.toFloatArray(), 4)
        val hit = d.downbeats.count { it in set }
        println("slip: ${d.slips} slips, $hit / ${d.downbeats.size} on the true 1s")
        assertTrue(d.slips in 1..2)
        assertTrue(hit >= d.downbeats.size - 2)
    }

    @Test
    fun analysesWithoutEvidenceAreLeftAsTheyWere() {
        val t = track("old")
        val r = AnalysisRefiner.cached(t)
        assertNull(r.barPhase)
        assertEquals(t.downbeatBeatIndices, r.downbeatBeatIndices)
    }

    @Test
    fun perfectPlansOnlyOnTrustedOnesAndRefusesOtherwise() {
        val planner = DjTransitionPlanner()
        val s = DjSettings(enabled = true, minPlayedFraction = 0f)
        val a0 = track("pa")
        val b0 = track("pb", bpm = 126f)
        val a = a0.copy(beatDownbeatLogits = logits(a0, 3f, -3f, 1f))
        val b = b0.copy(beatDownbeatLogits = logits(b0, 3f, -3f, 1f, seed = 2))
        val c = PlanConstraints(earliestExitMs = 40_000L, latestExitMs = 160_000L, perfect = true)
        val p = planner.plan(a, b, s, c)
        println("perfect: ${p.kind} exit ${p.exitPointMs} entry ${p.entryPointMs}: ${p.reason}")
        assertEquals(PlanKind.BEAT_MATCHED, p.kind)
        assertTrue(p.reason.startsWith(PlanConstraints.PERFECT_OK))
        val ra = AnalysisRefiner.cached(a)
        val rb = AnalysisRefiner.cached(b)
        val outDowns = ra.downbeatBeatIndices!!.value.map { ra.beatTimesMs!!.value[it].toLong() }.toHashSet()
        val inDowns = rb.downbeatBeatIndices!!.value.map { rb.beatTimesMs!!.value[it].toLong() }.toHashSet()
        assertTrue(p.exitPointMs in outDowns, "exit ${p.exitPointMs} is not a 1")
        assertTrue(p.entryPointMs in inDowns, "entry ${p.entryPointMs} is not a 1")

        val unsure = b0.copy(beatDownbeatLogits = logits(b0, 0.3f, 0.1f, 1.2f, seed = 2))
        val q = planner.plan(a, unsure, s, c)
        println("refused: ${q.kind}: ${q.reason}")
        assertEquals(PlanKind.SIMPLE_CROSSFADE, q.kind)
        assertTrue(q.reason.startsWith(PlanConstraints.PERFECT_REFUSED))
        // without evidence at all
        val r = planner.plan(a0, b0, s, c)
        assertTrue(r.reason.startsWith(PlanConstraints.PERFECT_REFUSED) && "no bar-phase evidence" in r.reason, r.reason)
    }
}
