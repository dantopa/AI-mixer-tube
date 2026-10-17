package org.simpmusic.dj.ai

import kotlinx.serialization.json.Json
import org.simpmusic.dj.model.AiMixPoints
import org.simpmusic.dj.model.Confident
import org.simpmusic.dj.model.DjSettings
import org.simpmusic.dj.model.FadeMove
import org.simpmusic.dj.model.FilterMove
import org.simpmusic.dj.model.MixMoves
import org.simpmusic.dj.model.PlanConstraints
import org.simpmusic.dj.model.PlanKind
import org.simpmusic.dj.model.PlanSource
import org.simpmusic.dj.model.SourceLoop
import org.simpmusic.dj.model.TimeRange
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.model.silentFromMs
import org.simpmusic.dj.planner.DjTransitionPlanner
import org.simpmusic.dj.render.DeckVoice
import org.simpmusic.dj.render.LoopSource
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Claude's hands on the mixer (build bc): moves, the DJ loop, and the owner's taste in the brief. */
class MixMovesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val settings = DjSettings(enabled = true, allowEchoOut = false, allowKeyShift = false, maxTempoBend = 0.06f)

    private fun load(id: String): TrackAnalysis =
        json.decodeFromString(TrackAnalysis.serializer(), javaClass.getResource("/structure/$id.json")!!.readText())

    private fun TrackAnalysis.withVoices(vararg r: Pair<Long, Long>) = copy(vocals = Confident(r.map { TimeRange(it.first, it.second) }, 0.85f))

    // the owner's case 1 (Lo Dejaría Todo -> A Prueba de Balas), voices known on both sides
    private val out = load("cXkiDWOrRZE").withVoices(5_500L to 7_900L, 13_700L to 17_500L, 47_800L to 92_400L)
    private val inc = load("7GUt3ooPJ1k").withVoices(5_000L to 79_400L, 91_000L to 139_900L)

    private fun plan(moves: MixMoves) =
        DjTransitionPlanner().plan(out, inc, settings, PlanConstraints(earliestExitMs = 30_000L, aiPoints = AiMixPoints(93_600L, 80_900L, 4, "instrumental over instrumental", moves)))

    @Test
    fun theLoopSourceRepeatsTheLoopWithAQuietSeam() {
        val sr = 1_000
        // the source's value is its frame index: what is read says where it came from
        val ramp = object : DeckVoice.Source {
            override fun read(startFrame: Long, count: Int, dst: Array<FloatArray>) {
                for (i in 0 until count) { dst[0][i] = (startFrame + i).toFloat(); dst[1][i] = -(startFrame + i).toFloat() }
            }
        }
        val loop = SourceLoop(1_000L, 1_400L) // frames 1000..1399
        val src = LoopSource(ramp, loop, sr)
        val n = 2_000
        val dst = arrayOf(FloatArray(n), FloatArray(n))
        src.read(500L, n, dst)
        for (i in 0 until n) {
            val v = 500L + i
            val k = Math.floorMod(v - 1_000L, 400L)
            val expect = when {
                v < 1_400L -> v.toFloat()
                k >= 8 -> (1_000L + k).toFloat() // past the 8 ms seam: the loop itself
                else -> Float.NaN
            }
            if (!expect.isNaN()) assertEquals(expect, dst[0][i], 1e-3f, "frame $v")
            if (!expect.isNaN()) assertEquals(-expect, dst[1][i], 1e-3f, "frame $v (right)")
        }
        // inside a seam the two sides are blended with equal power: the loop start fades in from the continuation
        val seamStart = (1_400 - 500)
        for (j in 0 until 8) {
            val fi = kotlin.math.sin((j + 0.5) / 8 * Math.PI / 2)
            val fo = kotlin.math.cos((j + 0.5) / 8 * Math.PI / 2)
            assertEquals((1_000 + j) * fi + (1_400 + j) * fo, dst[0][seamStart + j].toDouble(), 1e-2, "seam frame $j")
        }
        // reads in pieces give the same as one read
        val dst2 = arrayOf(FloatArray(n), FloatArray(n))
        var at = 0
        while (at < n) { val m = minOf(37, n - at); val tmp = arrayOf(FloatArray(m), FloatArray(m)); src.read(500L + at, m, tmp); System.arraycopy(tmp[0], 0, dst2[0], at, m); at += m }
        for (i in 0 until n) assertEquals(dst[0][i], dst2[0][i], 0f, "piecewise read at $i")
        assertEquals(1_000L + 5, loop.realFrame(1_405L, sr))
        assertEquals(1_399L, loop.realFrame(1_399L, sr))
    }

    @Test
    fun movesReshapeTheLanesAndSayWhatTheyDid() {
        val plain = plan(MixMoves())
        assertEquals(PlanSource.AI_MIX, plain.meta?.source, plain.reason)
        assertNull(plain.echoOut)
        val m = plan(MixMoves(bassSwapBar = 1, fade = FadeMove.HOLD_THEN_DROP, filter = FilterMove.LOWPASS_OUT, echoOut = true))
        println("moves: ${m.reason}")
        assertEquals(PlanSource.AI_MIX, m.meta?.source, m.reason)
        assertEquals(PlanKind.BEAT_MATCHED, m.kind)
        // same points, same overlap: the moves only touch the lanes
        assertEquals(plain.exitPointMs, m.exitPointMs)
        assertEquals(plain.entryPointMs, m.entryPointMs)
        assertEquals(plain.overlapMs, m.overlapMs)
        assertTrue(m.reason.contains("moves: "), m.reason)
        val o = m.overlapMs
        // hold then drop: the outgoing is still at its level past the middle
        assertTrue(m.outgoing.volume.valueAt(o * 0.6) > 0.9f * m.outgoing.volume.valueAt(0.0), "held")
        assertTrue(plain.outgoing.volume.valueAt(o * 0.6) < 0.9f * plain.outgoing.volume.valueAt(0.0))
        assertEquals(0f, m.outgoing.volume.valueAt(o.toDouble()), 1e-6f)
        // the low-pass sinks the outgoing
        assertEquals(700f, m.outgoing.highCutHz.valueAt(o.toDouble()), 1f)
        // the echo rings past the fade, under the incoming
        val e = assertNotNull(m.echoOut)
        val silent = m.outgoing.volume.silentFromMs()!!
        assertTrue(e.tailMs > silent + 3_000, "tail ${e.tailMs} after silent $silent")
        assertTrue(e.send.valueAt(silent - 200.0) > 0.5f, "send open before the fader closes")
        // the bass swap moved to bar 1 of the overlap (when the plan swaps at all)
        if (plain.meta?.features?.bassSwap == true) {
            val bar = 4 * 60_000.0 / plain.mixBpm!!
            val k = m.incoming.lowCutHz.keys
            val center = (k.first().tMs + k.last().tMs) / 2.0
            assertTrue(abs(center - bar) < bar / 2 + 1, "swap center $center, bar $bar")
        }
    }

    @Test
    fun aLoopRepeatsTheOutgoingsBarsUnderTheWholeOverlap() {
        val p = plan(MixMoves(loopBars = 2))
        println("loop: ${p.reason}")
        assertEquals(PlanSource.AI_MIX, p.meta?.source, p.reason)
        val loop = assertNotNull(p.outLoop, p.reason)
        assertTrue(p.reason.contains("loop 2 bars"), p.reason)
        assertTrue(abs(loop.startMs - p.exitPointMs) <= 1, "the loop starts on the exit (${loop.startMs} vs ${p.exitPointMs})")
        assertTrue(loop.lengthMs in 3_000..9_000, "2 bars of cumbia: ${loop.lengthMs} ms")
        assertTrue(p.overlapMs >= loop.lengthMs, "the overlap plays the loop at least once")
        // a loop too long for the deck's buffer is halved, then dropped
        val four = plan(MixMoves(loopBars = 4))
        assertNotNull(four.outLoop, four.reason)
        assertTrue(four.outLoop!!.lengthMs <= 9_000, four.reason)
    }

    @Test
    fun movesTravelThroughTheAnswersJson() {
        val b = MixBrief.build(MixBrief.Track("a", org.simpmusic.dj.timeline.Timelines.of(out)), MixBrief.Track("b", org.simpmusic.dj.timeline.Timelines.of(inc)), 30_000L, 8, 0L)!!
        val exitBar = b.outBarsMs.indexOfFirst { it >= 93_000L }
        val pts = MixBrief.parse(
            b,
            """{"exit_bar":$exitBar,"entry_bar":3,"overlap_bars":8,"bass_swap_bar":4,"fade":"early_handover","filter":"highpass_in","echo_out":true,"loop_bars":2,"why":"x"}""",
        ).getOrThrow()
        assertEquals(MixMoves(bassSwapBar = 4, fade = FadeMove.EARLY_HANDOVER, filter = FilterMove.HIGHPASS_IN, echoOut = true, loopBars = 2), pts.moves)
        val plainPts = MixBrief.parse(b, """{"exit_bar":$exitBar,"entry_bar":3,"overlap_bars":8,"bass_swap_bar":-1,"fade":"smooth","filter":"none","echo_out":false,"loop_bars":0,"why":"x"}""").getOrThrow()
        assertTrue(plainPts.moves.isDefault)
        @Suppress("UNCHECKED_CAST")
        val required = MixBrief.SCHEMA["required"] as List<String>
        assertTrue(required.containsAll(listOf("bass_swap_bar", "fade", "filter", "echo_out", "loop_bars")))
    }

    @Test
    fun theOwnersTasteAndMarksReachTheBrief() {
        val taste = MixBrief.taste(
            listOf(
                MixBrief.RatedMix(true, "AI_MIX", 24.6, 0.62f, 0.07f, true, true, 0.0, 0.01f, 78f, moves = "bass swap on bar 4", why = "instrumental over instrumental"),
                MixBrief.RatedMix(false, "REGULAR", 3.0, 0.95f, 0.0f, false, false, 2.4, 0.09f, 85f),
            ),
        )!!
        println(taste)
        assertTrue(taste.contains("LIKED AI_MIX"), taste)
        assertTrue(taste.contains("DISLIKED REGULAR"), taste)
        assertTrue(taste.contains("2.4 s voice over voice"), taste)
        assertNull(MixBrief.taste(emptyList()))
        val prev = org.simpmusic.dj.planner.MixMarks.provider
        try {
            org.simpmusic.dj.planner.MixMarks.provider = { id -> if (id == out.videoId) org.simpmusic.dj.planner.MixMarks(exitMs = 93_600L) else null }
            val b = MixBrief.build(MixBrief.Track("a", org.simpmusic.dj.timeline.Timelines.of(out)), MixBrief.Track("b", org.simpmusic.dj.timeline.Timelines.of(inc)), 30_000L, 8, 0L, taste)!!
            assertTrue(b.user.contains("THE OWNER MARKED"), b.user.takeLast(800))
            assertTrue(b.user.contains("THE OWNER'S RATINGS"), b.user.takeLast(800))
        } finally {
            org.simpmusic.dj.planner.MixMarks.provider = prev
        }
    }
}
