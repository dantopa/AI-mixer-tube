package org.simpmusic.dj.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.simpmusic.dj.model.AiMixPoints
import org.simpmusic.dj.model.TimeRange
import org.simpmusic.dj.timeline.LyricLine
import org.simpmusic.dj.timeline.TrackTimeline
import kotlin.math.roundToInt

/**
 * "AI Mix" (2026-10-16): what Claude is told about a pair, and how its answer becomes [AiMixPoints]. No audio leaves the
 * phone: each track is a table with one row per bar (loudness, bass, highs and voice as 0-9 levels of the track's own
 * range, and where the phrase lines are) plus its synced lyrics, if any. Claude answers with a bar of each table and an
 * overlap length; the planner snaps those to the grid and beat-matches them, as it does the owner's mix marks.
 *
 * The rules in [SYSTEM] are the owner's, in the order they asked for them (CLAUDE.md, builds al-ar): instrumental over
 * instrumental with the bass in both, never two voices at once, phrase on phrase, and the incoming's phrase 1 landing
 * where the outgoing would have started over.
 */
object MixBrief {
    /** One track of the pair: its title, its timeline (refined grid, vocals, phrase lines) and its lyrics on its own time. */
    class Track(val title: String, val timeline: TrackTimeline, val lyrics: List<LyricLine>? = null)

    /** What is sent, and what is needed to read the answer back. */
    class Brief(
        val system: String,
        val user: String,
        /** Start of each bar (ms) of the outgoing / incoming tables, by the bar numbers Claude answers with. */
        val outBarsMs: List<Long>,
        val inBarsMs: List<Long>,
        /** Bars of the outgoing table an exit may use (inclusive). */
        val exitFrom: Int,
        val exitTo: Int,
        /** Bars of the incoming table an entry may use (inclusive). */
        val entryTo: Int,
    )

    /** The JSON schema of the answer (structured output): bar numbers and an overlap, plus one sentence of why. */
    val SCHEMA: Map<String, Any> =
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "exit_bar" to mapOf("type" to "integer", "description" to "Bar of the OUTGOING table where the mix starts (the incoming comes in on its downbeat)."),
                "entry_bar" to mapOf("type" to "integer", "description" to "Bar of the INCOMING table that plays at that moment."),
                "overlap_bars" to mapOf("type" to "integer", "description" to "How many bars both tracks play together before only the incoming is left."),
                "why" to mapOf("type" to "string", "description" to "One short sentence: what overlaps what, in musical terms."),
            ),
            "required" to listOf("exit_bar", "entry_bar", "overlap_bars", "why"),
            "additionalProperties" to false,
        )

    val SYSTEM: String =
        """
        You are the mix brain of a DJ app that plays cumbia, Argentine cumbia, cumbia villera and other Latin music from a
        streaming radio. You choose WHERE two consecutive tracks mix; the engine does the rest: it beat-matches both decks
        (bending tempo a few percent), lines up the downbeats, swaps the basslines halfway through the overlap so only one
        bass plays at a time, and matches loudness. You never hear the audio: you read one table per track, one row per bar.

        Columns: bar = bar number; t = start of the bar (m:ss); loud, bass, high = loudness, low band and high band as 0-9 of
        that track's own range; voice = how much of the bar someone sings, 0-9 ("-" = unknown); line = "B" where a 16-bar block
        starts, "P" where an 8-bar phrase starts, "?" when those lines are only a guess. Lyrics, when given, are on the
        track's own clock: a gap between lyric lines is an instrumental.

        The owner's rules, most important first:
        1. Overlap the outgoing's INSTRUMENTAL with the incoming's INSTRUMENTAL, with the bass present in BOTH (bass >= 4
           on both sides through the overlap). That is where a mix is felt.
        2. Never two voices at once: no singing on both tracks in the same bars. If the incoming sings from its first bar,
           end the overlap right where its voice starts. When the voice column comes from the vocal detector, read it as a
           hint: on cumbia it often hears the accordion or the keyboards as a voice, so only clear contrasts (0-2 against
           7-9) mean something; synced lyrics are reliable.
        3. Phrase on phrase: start the mix on a phrase line ("P" or better "B") of both tracks when you can.
        4. The best moment to leave is right after a chorus ends, when the band plays an instrumental and the song would
           start over. The incoming's phrase 1 (its first sung line, or the start of its next section) should arrive
           exactly when the outgoing's instrumental ends, so the incoming takes over at full volume.
        5. Do not cut a sung line in half; do not leave in the middle of a chorus.
        6. The overlap is at least 4 bars: a shorter one is not heard as a mix. Use the owner's usual length (given below)
           whenever both sides allow it; otherwise the longest that stays clean. When nothing clean exists, 4 bars on a
           phrase line beat a long messy overlap.

        Use only bar numbers from the tables and stay inside the allowed ranges given. Answer with the JSON only.
        """.trimIndent()

    /** Lyrics lines and table rows kept per track: a 5-minute song is ~110 bars at 90 bpm. */
    private const val MAX_BARS = 220
    private const val MAX_LYRIC_LINES = 90
    private const val MAX_LYRIC_CHARS = 60

    /** The shortest overlap an answer may ask for (shorter answers are lengthened). */
    const val MIN_OVERLAP_BARS = 4

    /** An exit must leave this many bars of the outgoing for the overlap. */
    private const val EXIT_TAIL_BARS = 4

    /** An entry must leave this much of the incoming after it. */
    private const val ENTRY_TAIL_MS = 30_000L

    /**
     * The brief for [out] -> [inc], or null when a track has no beat grid (nothing to count bars on). Exits are allowed from
     * [earliestExitMs] (the playhead plus the time the answer and the cue take) to [EXIT_TAIL_BARS] before the end.
     */
    fun build(out: Track, inc: Track, earliestExitMs: Long, requestedBars: Int, positionMs: Long): Brief? {
        val outBars = bars(out.timeline) ?: return null
        val inBars = bars(inc.timeline) ?: return null
        val exitFrom = outBars.indexOfFirst { it >= earliestExitMs }.takeIf { it >= 0 } ?: return null
        val exitTo = (outBars.size - 1 - EXIT_TAIL_BARS).coerceAtMost(MAX_BARS - 1)
        if (exitTo < exitFrom) return null
        val inDur = inc.timeline.refined.durationMs
        val entryTo = inBars.indexOfLast { it <= inDur - ENTRY_TAIL_MS }.coerceAtLeast(0).coerceAtMost(MAX_BARS - 1)
        val user =
            buildString {
                append("OUTGOING (playing now, at ${clock(positionMs)})\n")
                append(table(out, outBars))
                append("\nINCOMING (next)\n")
                append(table(inc, inBars))
                append("\nAllowed: exit_bar ").append(exitFrom).append("..").append(exitTo)
                append(" (earlier bars have already played or come too soon), entry_bar 0..").append(entryTo)
                append(", overlap_bars ").append(MIN_OVERLAP_BARS).append("..32. The owner's usual overlap is ").append(requestedBars).append(" bars.\n")
            }
        return Brief(SYSTEM, user, outBars, inBars, exitFrom, exitTo, entryTo)
    }

    /** Claude's JSON answer as points on both tracks' clocks, or why it cannot be used. */
    fun parse(brief: Brief, json: String): Result<AiMixPoints> =
        runCatching {
            val o: JsonObject = Json.parseToJsonElement(json).jsonObject
            fun int(name: String) = o[name]?.jsonPrimitive?.intOrNull ?: error("answer has no $name")
            val exit = int("exit_bar")
            val entry = int("entry_bar")
            val overlap = int("overlap_bars")
            val why = o["why"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            require(exit in brief.exitFrom..brief.exitTo) { "exit_bar $exit is outside ${brief.exitFrom}..${brief.exitTo}" }
            require(entry in 0..brief.entryTo) { "entry_bar $entry is outside 0..${brief.entryTo}" }
            require(overlap >= 1) { "overlap_bars $overlap" }
            // at least MIN_OVERLAP_BARS (a model asked for 1-bar mixes on the owner's first session: ~3 s, not heard as a
            // mix), never more than the outgoing has bars left
            val room = (brief.outBarsMs.size - 1 - exit).coerceAtLeast(1)
            AiMixPoints(brief.outBarsMs[exit], brief.inBarsMs[entry], overlap.coerceIn(MIN_OVERLAP_BARS, 32).coerceAtMost(room), why.ifEmpty { "no reason given" })
        }

    // ---------------------------------------------------------------------------------------------

    /** Start of each bar: the refined downbeats, else every beatsPerBar beats; null without a usable grid. */
    internal fun bars(t: TrackTimeline): List<Long>? {
        t.downbeatsMs.takeIf { it.size >= 8 }?.let { return it.take(MAX_BARS) }
        val beats = t.beatsMs
        if (beats.size < 16) return null
        val per = (t.beatsPerBar ?: 4).coerceIn(2, 7)
        return beats.indices.step(per).map { beats[it].toLong() }.take(MAX_BARS)
    }

    private fun table(track: Track, bars: List<Long>): String {
        val t = track.timeline
        val a = t.refined
        val hop = a.energyHopMs.coerceAtLeast(1)
        val end = a.durationMs
        val loud = Levels(a.energy, hop)
        val bass = Levels(a.lowBandEnergy, hop)
        val high = Levels(a.highBandEnergy, hop)
        val vocals = t.vocals?.value
        val blocks = t.blocks?.value
        val phrases = t.phrases?.value
        val lineGuess = (blocks?.trusted != true) && (phrases?.trusted != true)
        return buildString {
            append("title: ").append(track.title).append('\n')
            append("bpm: ").append(t.bpm?.value?.let { "%.1f".format(java.util.Locale.ROOT, it) } ?: "?")
            append(", beats per bar: ").append(t.beatsPerBar ?: 4)
            append(", bars ").append(if (t.bars.value) "trusted" else "unsure")
            append(", length ").append(clock(end))
            append(", voice from ").append(
                when {
                    vocals == null -> "nowhere (unknown)"
                    t.vocalsFromLyrics -> "the synced lyrics"
                    else -> "the vocal detector"
                },
            ).append('\n')
            append("bar t loud bass high voice line\n")
            for (i in bars.indices) {
                val s = bars[i]
                val e = bars.getOrNull(i + 1) ?: end
                val line =
                    when {
                        blocks != null && s in blocks.startsMs -> "B"
                        phrases != null && s in phrases.startsMs -> "P"
                        else -> ""
                    }.let { if (it.isNotEmpty() && lineGuess) "$it?" else it }
                append(i).append(' ').append(clock(s)).append(' ')
                append(loud.at(s, e)).append(' ').append(bass.at(s, e)).append(' ').append(high.at(s, e)).append(' ')
                append(vocals?.let { level(sungShare(it, s, e)) } ?: "-")
                if (line.isNotEmpty()) append(' ').append(line)
                append('\n')
            }
            val lyrics = track.lyrics?.filter { it.text.isNotBlank() && it.text.trim() != "♫" }
            if (!lyrics.isNullOrEmpty()) {
                append("lyrics:\n")
                for (l in lyrics.take(MAX_LYRIC_LINES)) append(clock(l.startMs)).append(' ').append(l.text.trim().take(MAX_LYRIC_CHARS)).append('\n')
            }
        }
    }

    /** A curve as 0-9 levels of its own 5th-95th percentile range; "-" when the curve is missing. */
    private class Levels(private val v: List<Float>, private val hopMs: Int) {
        private val lo: Float
        private val hi: Float

        init {
            val s = v.sorted()
            lo = s.getOrNull((s.size * 0.05).toInt()) ?: 0f
            hi = s.getOrNull((s.size * 0.95).toInt().coerceAtMost(s.size - 1)) ?: 1f
        }

        fun at(startMs: Long, endMs: Long): String {
            if (v.isEmpty()) return "-"
            val a = (startMs / hopMs).toInt().coerceIn(0, v.size - 1)
            val b = (endMs / hopMs).toInt().coerceIn(a + 1, v.size)
            val mean = (a until b).sumOf { v[it].toDouble() } / (b - a)
            val x = if (hi > lo) (mean - lo) / (hi - lo) else 0.0
            return level(x)
        }
    }

    private fun level(x: Double): String = (x * 9).roundToInt().coerceIn(0, 9).toString()

    private fun sungShare(r: List<TimeRange>, s: Long, e: Long): Double {
        if (e <= s) return 0.0
        val sung = r.sumOf { (minOf(it.endMs, e) - maxOf(it.startMs, s)).coerceAtLeast(0L) }
        return sung.toDouble() / (e - s)
    }

    private fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(java.util.Locale.ROOT, s / 60, s % 60)
    }
}
