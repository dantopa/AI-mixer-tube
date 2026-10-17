package org.simpmusic.dj.ai

import kotlinx.serialization.json.Json
import org.simpmusic.dj.model.Confident
import org.simpmusic.dj.model.DjSettings
import org.simpmusic.dj.model.TimeRange
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.timeline.Timelines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "AI Mix with tools" on the owner's case 1: a conversation Claude could have, played by hand. No network. */
class MixToolsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val settings = DjSettings(enabled = true, allowEchoOut = false, allowKeyShift = false, maxTempoBend = 0.06f)

    private fun load(id: String): TrackAnalysis =
        json.decodeFromString(TrackAnalysis.serializer(), javaClass.getResource("/structure/$id.json")!!.readText())

    private fun TrackAnalysis.withVoices(vararg r: Pair<Long, Long>) = copy(vocals = Confident(r.map { TimeRange(it.first, it.second) }, 0.85f))

    private val out = load("cXkiDWOrRZE").withVoices(5_500L to 7_900L, 13_700L to 17_500L, 47_800L to 92_400L)
    private val inc = load("7GUt3ooPJ1k").withVoices(5_000L to 79_400L, 91_000L to 139_900L)
    private val brief =
        MixBrief.build(MixBrief.Track("Lo Dejaría Todo", Timelines.of(out)), MixBrief.Track("A Prueba de Balas", Timelines.of(inc)), 30_000L, 8, 10_000L)!!
    private val tools = MixTools(brief, out, inc, null, null, settings, 30_000L)

    private fun text(o: MixTools.Outcome) = (o as MixTools.Outcome.Result).text

    @Test
    fun zoomTryCommit() {
        val exitBar = brief.outBarsMs.indexOfFirst { it >= 93_000L }
        val entryBar = brief.inBarsMs.indexOfFirst { it >= 80_500L }
        val z = text(tools.call(MixTools.ZOOM, """{"track":"incoming","from_bar":$entryBar,"to_bar":${entryBar + 5}}"""))
        println(z.lines().take(8).joinToString("\n"))
        assertTrue(z.lines().size > 10, "a row per beat")
        val t = text(tools.call(MixTools.TRY, """{"exit_bar":$exitBar,"entry_bar":$entryBar,"overlap_bars":4}"""))
        println(t)
        assertTrue(t.startsWith("OK"), t)
        assertTrue(t.contains("voice over voice") && t.contains("harmony of the overlap") && t.contains("bass through the overlap"), t)
        val bad = text(tools.call(MixTools.TRY, """{"exit_bar":${brief.exitFrom - 1},"entry_bar":0,"overlap_bars":4}"""))
        assertTrue(bad.startsWith("error"), bad)
        val c = tools.call(MixTools.COMMIT, """{"exit_bar":$exitBar,"entry_bar":$entryBar,"overlap_bars":4,"why":"inst over inst"}""")
        assertTrue(c is MixTools.Outcome.Committed)
        assertEquals(brief.outBarsMs[exitBar], c.points.exitMs)
        assertEquals(2, tools.tries)
    }

    @Test
    fun theToolSchemasAreStrict() {
        for (s in MixTools.SPECS) {
            assertEquals(false, s.schema["additionalProperties"], s.name)
            @Suppress("UNCHECKED_CAST")
            assertEquals((s.schema["properties"] as Map<String, Any>).keys.toList(), s.schema["required"], s.name)
        }
    }

    @Test
    fun tryMixTakesTheMovesAndMeasuresALoop() {
        val exitBar = brief.outBarsMs.indexOfFirst { it >= 93_000L }
        val entryBar = brief.inBarsMs.indexOfFirst { it >= 80_500L }
        val t = text(
            tools.call(
                MixTools.TRY,
                """{"exit_bar":$exitBar,"entry_bar":$entryBar,"overlap_bars":8,"bass_swap_bar":4,"fade":"hold_then_drop","filter":"lowpass_out","echo_out":true,"loop_bars":2}""",
            ),
        )
        println(t)
        assertTrue(t.startsWith("OK"), t)
        assertTrue(t.contains("the outgoing loops"), t)
        assertTrue(t.contains("moves: ") && t.contains("echo out"), t)
    }
}
