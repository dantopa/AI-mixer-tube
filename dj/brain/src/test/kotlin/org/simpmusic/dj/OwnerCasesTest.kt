package org.simpmusic.dj

import kotlinx.serialization.json.Json
import org.simpmusic.dj.model.*
import org.simpmusic.dj.planner.DjTransitionPlanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The owner's own mixes, one per case, on the real stored analyses of their tracks (no audio): where they hear the
 * instrumentals and the voices, and where the mix must go. The vocal ranges are the OWNER'S, not the detector's (which
 * hears the instrumentals of these cumbias as voice), so these tests pin the planner; the detector is judged elsewhere.
 */
class OwnerCasesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val planner = DjTransitionPlanner()
    private val settings = DjSettings(enabled = true, allowEchoOut = false, allowKeyShift = false, maxTempoBend = 0.06f)

    private fun load(id: String): TrackAnalysis =
        json.decodeFromString(TrackAnalysis.serializer(), javaClass.getResource("/structure/$id.json")!!.readText())

    private fun TrackAnalysis.withVoices(vararg r: Pair<Long, Long>) = copy(vocals = Confident(r.map { TimeRange(it.first, it.second) }, 0.85f))

    /**
     * Case 1 (owner, 2026-10-08): "Lo Dejaría Todo" -> "A Prueba de Balas". The first's instrumental after the chorus starts at
     * ~1:33 and runs to its end; the second's instrumental starts at ~1:20 and its voice comes back at ~1:31. "Just crossfade
     * the two instrumentals": start when the first's instrumental starts, enter the second at its instrumental, so that the
     * first is gone when the second's voice comes in.
     */
    @Test
    fun loDejariaTodoIntoAPruebaDeBalas() {
        val out = load("cXkiDWOrRZE").withVoices(5_500L to 7_900L, 13_700L to 17_500L, 19_900L to 23_300L, 24_700L to 28_600L, 30_500L to 39_600L, 41_000L to 45_400L, 47_800L to 92_400L)
        val inc = load("7GUt3ooPJ1k").withVoices(5_000L to 79_400L, 91_000L to 139_900L, 141_400L to 146_200L, 149_000L to 153_400L, 155_300L to 159_100L)
        val p = planner.plan(out, inc, settings, PlanConstraints(preferPerfect = true, earliestExitMs = 30_000))
        println("case 1: exit=${p.exitPointMs} entry=${p.entryPointMs} overlap=${p.overlapMs} | ${p.reason}")
        assertEquals(PlanKind.BEAT_MATCHED, p.kind)
        assertTrue(p.reason.contains("STRUCTURE"), p.reason)
        assertTrue(p.exitPointMs in 92_500..95_000, "starts as the first's instrumental starts (~1:33): ${p.exitPointMs}")
        assertTrue(p.entryPointMs in 79_000..82_000, "enters at the second's instrumental (~1:20): ${p.entryPointMs}")
        val voiceIn = p.entryPointMs + p.overlapMs // rates within 2 % of 1: close enough for a second of tolerance
        assertTrue(voiceIn in 90_000..93_500, "the first is gone when the second's voice comes in (~1:31): $voiceIn")
        assertTrue(p.reason.contains("vocals: one at a time") || p.reason.contains("none in the overlap"), p.reason)
    }

    /** Manual: the same pair with the vocals the phone's detector stored (prints, asserts nothing). */
    @Test
    fun case1WithTheDetectorsOwnVocals() {
        val p = planner.plan(load("cXkiDWOrRZE"), load("7GUt3ooPJ1k"), settings, PlanConstraints(preferPerfect = true, earliestExitMs = 30_000))
        println("case 1 (detector): exit=${p.exitPointMs} entry=${p.entryPointMs} overlap=${p.overlapMs} | ${p.reason.take(300)}")
    }
}
