package org.simpmusic.dj.ai

import kotlinx.serialization.json.Json
import org.simpmusic.dj.model.Confident
import org.simpmusic.dj.model.DjSettings
import org.simpmusic.dj.model.TimeRange
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.render.StereoPcm
import org.simpmusic.dj.timeline.Timelines
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Pro mode's tools on the owner's case 1 (real analyses, synthetic audio standing in for the decoder). No network. */
class MixToolsProTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val settings = DjSettings(enabled = true, allowEchoOut = false, allowKeyShift = false, maxTempoBend = 0.06f)

    private fun load(id: String): TrackAnalysis =
        json.decodeFromString(TrackAnalysis.serializer(), javaClass.getResource("/structure/$id.json")!!.readText())

    private fun TrackAnalysis.withVoices(vararg r: Pair<Long, Long>) = copy(vocals = Confident(r.map { TimeRange(it.first, it.second) }, 0.85f))

    private val out = load("cXkiDWOrRZE").withVoices(5_500L to 7_900L, 13_700L to 17_500L, 47_800L to 92_400L)
    private val inc = load("7GUt3ooPJ1k").withVoices(5_000L to 79_400L, 91_000L to 139_900L)
    private val brief =
        MixBrief.build(MixBrief.Track("Lo Dejaría Todo", Timelines.of(out)), MixBrief.Track("A Prueba de Balas", Timelines.of(inc)), 30_000L, 8, 10_000L)!!

    /** A 110 Hz tone with a 3 kHz one on top, enough to render and to look at. */
    private val ears =
        MixTools.MixEars { _, fromMs, toMs ->
            val sr = 22_050
            val n = ((toMs - fromMs) * sr / 1000).toInt()
            val l = FloatArray(n) { i -> (0.3 * sin(2 * PI * 110 * i / sr) + 0.1 * sin(2 * PI * 3000 * i / sr)).toFloat() }
            StereoPcm(l, l.copyOf(), sr)
        }
    private val tools = MixTools(brief, out, inc, null, null, settings, 30_000L, ears = ears, pro = true)

    private fun isPng(b: ByteArray) = b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()

    @Test
    fun proOffersItsToolsAndAMinimalModeDoesNot() {
        assertEquals(MixTools.SPECS.map { it.name } + listOf(MixTools.CANDIDATES, MixTools.LOOK, MixTools.LISTEN), tools.specs.map { it.name })
        assertEquals(MixTools.PRO_MAX_TURNS, tools.maxTurns)
        val plain = MixTools(brief, out, inc, null, null, settings, 30_000L)
        assertEquals(MixTools.SPECS.map { it.name }, plain.specs.map { it.name })
        assertEquals(MixTools.MAX_TURNS, plain.maxTurns)
        // Pro without audio still gets the map, not the pictures
        assertEquals(MixTools.SPECS.map { it.name } + MixTools.CANDIDATES, MixTools(brief, out, inc, null, null, settings, 30_000L, pro = true).specs.map { it.name })
        for (s in MixTools.PRO_SPECS) {
            assertEquals(false, s.schema["additionalProperties"], s.name)
            @Suppress("UNCHECKED_CAST")
            assertEquals((s.schema["properties"] as Map<String, Any>).keys.toList(), s.schema["required"], s.name)
        }
    }

    @Test
    fun lookReturnsASpectrogramOfTheBars() {
        val entryBar = brief.inBarsMs.indexOfFirst { it >= 80_500L }
        val r = tools.call(MixTools.LOOK, """{"track":"incoming","from_bar":$entryBar,"to_bar":${entryBar + 7}}""") as MixTools.Outcome.Result
        assertFalse(r.isError, r.text)
        assertNotNull(r.image)
        assertTrue(isPng(r.image!!))
        assertTrue(r.text.contains("INCOMING bars $entryBar-${entryBar + 7}"), r.text)
        assertTrue(r.text.contains("red strip"), "the voice strip is explained: ${r.text}")
    }

    @Test
    fun listenRendersTheMixAndMeasuresItsLevel() {
        val exitBar = brief.outBarsMs.indexOfFirst { it >= 93_000L }
        val entryBar = brief.inBarsMs.indexOfFirst { it >= 80_500L }
        val r = tools.call(MixTools.LISTEN, """{"exit_bar":$exitBar,"entry_bar":$entryBar,"overlap_bars":4,"bass_swap_bar":-1,"fade":"smooth","filter":"none","echo_out":false,"loop_bars":0}""") as MixTools.Outcome.Result
        println(r.text)
        assertFalse(r.isError, r.text)
        assertTrue(r.text.startsWith("OK"), r.text)
        assertTrue(r.text.contains("LISTENED") && r.text.contains("mix level per bar"), r.text)
        assertTrue(isPng(r.image!!))
    }

    @Test
    fun candidatesListsMeasuredMixesCleanestFirst() {
        val r = tools.call(MixTools.CANDIDATES, """{"overlap_bars":8}""") as MixTools.Outcome.Result
        println(r.text)
        assertFalse(r.isError, r.text)
        assertTrue(r.text.lines().count { it.startsWith("exit_bar") } in 1..MixTools.MAX_CANDIDATES, r.text)
        assertEquals(0, tools.tries, "the map is not counted as tries")
    }

    @Test
    fun thePngIsReadable() {
        val png = Png.rgb(3, 2, intArrayOf(0xFF0000, 0x00FF00, 0x0000FF, 0, 0xFFFFFF, 0x808080))
        val img = javax.imageio.ImageIO.read(png.inputStream())
        assertEquals(3, img.width)
        assertEquals(2, img.height)
        assertEquals(0xFF0000, img.getRGB(0, 0) and 0xFFFFFF)
        assertEquals(0x808080, img.getRGB(2, 1) and 0xFFFFFF)
    }
}
