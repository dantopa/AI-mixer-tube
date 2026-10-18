package org.simpmusic.dj.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.simpmusic.dj.model.AiMixPoints
import org.simpmusic.dj.model.FadeMove
import org.simpmusic.dj.model.FilterMove
import org.simpmusic.dj.model.MixMoves
import org.simpmusic.dj.model.TimeRange
import org.simpmusic.dj.planner.MixMarks
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

    private val FADES = mapOf("smooth" to FadeMove.SMOOTH, "hold_then_drop" to FadeMove.HOLD_THEN_DROP, "early_handover" to FadeMove.EARLY_HANDOVER)
    private val FILTERS = mapOf("none" to FilterMove.NONE, "lowpass_out" to FilterMove.LOWPASS_OUT, "highpass_out" to FilterMove.HIGHPASS_OUT, "highpass_in" to FilterMove.HIGHPASS_IN)

    /**
     * The mixer moves Claude may make (see [MixMoves]), as JSON-schema properties shared by the one-answer schema and the
     * tools. All required (strict schemas): "-1", "smooth", "none", false and 0 mean "the engine's own mix".
     */
    val MOVES_PROPS: Map<String, Any> =
        mapOf(
            "bass_swap_bar" to mapOf("type" to "integer", "description" to "Bar of the overlap (0 = its first bar) where the basslines swap: before it only the outgoing's bass plays, after it only the incoming's. -1 = the middle."),
            "fade" to mapOf("type" to "string", "enum" to FADES.keys.toList(), "description" to "smooth = equal-power over the whole overlap; hold_then_drop = the outgoing stays full and drops over the last bars (the incoming is up by the middle); early_handover = the incoming is up within the first quarter and the outgoing is gone by three quarters."),
            "filter" to mapOf("type" to "string", "enum" to FILTERS.keys.toList(), "description" to "none; lowpass_out = the outgoing sinks under a low-pass; highpass_out = the outgoing washes out from the bass up; highpass_in = the incoming enters thin and opens up by the bass swap."),
            "echo_out" to mapOf("type" to "boolean", "description" to "Throw a beat-synced echo on the outgoing as its fader closes: its last beat rings out over the incoming."),
            "loop_bars" to mapOf("type" to "integer", "description" to "0, 1, 2 or 4. Loop this many bars of the outgoing, starting at exit_bar, for the whole overlap (0 = no loop). A loop of an instrumental bar with the bass makes a clean bed under the incoming when the outgoing has no long instrumental; at most ~9 s (halved when longer)."),
        )

    /** The moves of an answer or a tool call; missing fields are the engine's defaults. */
    fun movesOf(o: JsonObject): MixMoves {
        val bar = o["bass_swap_bar"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 }
        val fade = o["fade"]?.jsonPrimitive?.contentOrNull?.let { FADES[it] } ?: FadeMove.SMOOTH
        val filter = o["filter"]?.jsonPrimitive?.contentOrNull?.let { FILTERS[it] } ?: FilterMove.NONE
        val echo = o["echo_out"]?.jsonPrimitive?.booleanOrNull ?: false
        // (the largest DJ loop length not above what was asked: 3 -> 2, 8 -> 4)
        val loop = o["loop_bars"]?.jsonPrimitive?.intOrNull?.let { n -> MixMoves.LOOP_BARS.lastOrNull { it <= n } } ?: 0
        return MixMoves(bassSwapBar = bar, fade = fade, filter = filter, echoOut = echo, loopBars = loop)
    }

    /** The JSON schema of the answer (structured output): bar numbers and an overlap, the moves, one sentence of why. */
    val SCHEMA: Map<String, Any> by lazy {
        val props = mapOf(
            "exit_bar" to mapOf("type" to "integer", "description" to "Bar of the OUTGOING table where the mix starts (the incoming comes in on its downbeat)."),
            "entry_bar" to mapOf("type" to "integer", "description" to "Bar of the INCOMING table that plays at that moment."),
            "overlap_bars" to mapOf("type" to "integer", "description" to "How many bars both tracks play together before only the incoming is left."),
        ) + MOVES_PROPS + mapOf("why" to mapOf("type" to "string", "description" to "One short sentence: what overlaps what, in musical terms."))
        mapOf("type" to "object", "properties" to props, "required" to props.keys.toList(), "additionalProperties" to false)
    }

    val SYSTEM: String =
        """
        You are the mix brain of a DJ app that plays cumbia, Argentine cumbia, cumbia villera and other Latin music from a
        streaming radio. You choose WHERE two consecutive tracks mix; the engine does the rest: it beat-matches both decks
        (bending tempo a few percent), lines up the downbeats, swaps the basslines halfway through the overlap so only one
        bass plays at a time, and matches loudness. You never hear the audio: you read one table per track, one row per bar.

        Columns: bar = bar number; t = start of the bar (m:ss); loud, bass, high = loudness, low band and high band as 0-9 of
        that track's own range; voice = how much of the bar someone sings, 0-9 ("-" = unknown); line = "B" where a 16-bar block
        starts, "P" where an 8-bar phrase starts, "?" when those lines are only a guess. Lyrics, when given, are on the
        track's own clock, each line with the bar.beat it starts on (the tables' bar numbers), and every gap of 4 s or more
        written out as "no singing" with its bars: that is the most exact map of where a voice ends and the next line
        comes in, so use it to put the hand-off on the beat where one song's line would have come.

        Each track also has a fingerprint, one row per 8-bar phrase: its bars; part = a letter, the same letter where the
        music repeats (a chorus comes back as the same letter; "repeats" names the earlier phrase and how alike they are,
        0-1); local-key = the key that phrase's chords sound in ("Am", "C"; "?" = a weak fit); loud, bass and voice as 0-9.
        Two keys mix well when they are the same, relative (Am / C), or a fifth apart (Am / Em, C / G); farther apart, keep
        the overlap short or on drums and bass. The song map gives the sung and instrumental spans in seconds.
        An image may come with the tables: both tracks drawn bar by bar (loudness, bass, highs, voice, and a 12-row chroma
        strip, the notes being played), with the phrase lines and bar numbers; use it to see the shape of each song.

        THE GOAL, above every rule: the change from one song to the next must not be felt. It should sound like a live band's
        medley ("enganchado"): the band ends a phrase of one song and the next song comes in exactly where the ear expects
        the music to go on. The groove never stops, the beat keeps landing where it should, and the next sung line starts
        where a line would naturally come, so even the lyrics seem to continue. One song may drop out, but the other is
        already there at that very moment: the music never pauses and nothing feels forced. Every rule below serves this.
        Read where each track sings and where only the band plays, and place the points so the incoming's music (and its
        first sung line) arrives as the natural continuation of the outgoing. Between two clean options, take the one that
        sounds most like one continuous song.

        The owner's rules, most important first:
        1. Overlap the outgoing's INSTRUMENTAL with the incoming's INSTRUMENTAL, with the bass present in BOTH (bass >= 4
           on both sides through the overlap). That is where a mix is felt.
        2. Never two voices at once: no singing on both tracks in the same bars. If the incoming sings from its first bar,
           end the overlap right where its voice starts. When the voice column comes from the vocal detector, read it as a
           hint: on cumbia it often hears the accordion or the keyboards as a voice, so only clear contrasts (0-2 against
           7-9) mean something; synced lyrics are reliable.
        3. Phrase on phrase: start the mix on a phrase line ("P" or better "B") of both tracks when you can.
        4. The best moment to leave is right after a chorus ends, when the band plays an instrumental. The incoming's next
           phrase 1 (its first sung line, or the start of its next section) should arrive exactly when the outgoing's
           instrumental ends, so the incoming takes over at full volume.
        5. The mix can be ANYWHERE that makes musical sense: not necessarily at the end of the outgoing, nor at the very
           start of the incoming. The outgoing may leave in its middle (an instrumental break after a chorus, the second
           time a chorus ends); the incoming may enter at its intro, at an instrumental break of its own, or at the start of
           a later section, skipping what comes before. Pick the points that sound best, not the last exit and bar 0 by
           habit. Keep most of the incoming: enter in its first half unless a later point is clearly better.
        6. Do not cut a sung line in half; do not leave in the middle of a chorus.
        7. The overlap is at least 4 bars: a shorter one is not heard as a mix. Use the owner's usual length (given below)
           whenever both sides allow it; otherwise the longest that stays clean. When nothing clean exists, 4 bars on a
           phrase line beat a long messy overlap. The owner's ratings so far: the mixes they liked overlapped 11-23 s with
           the bass swap in the middle of the overlap; a 4-bar (~5 s) mix sounds to them like a plain fade, so prefer 8
           bars or more whenever the instrumentals allow it. The ones they disliked were short or clashed in key (a
           harmony excess above ~0.08 in try_mix): move the points, or keep such a clash short and on drums and bass.
        8. When a track's bar lines are only a guess ("?") or its voice is unknown ("-"), keep the overlap at 4-8 bars: a
           long overlap whose 1s may not land together, or with voices nobody can see, sounds wrong (the engine caps it
           at 8 bars then).
        9. The owner's marks and ratings, when given after the tables, are the owner's own ear: use the marked points, and
           lean towards the kind of mix they liked (length, where in the songs, the moves) and away from what they did
           not like.

        You also have the DJ's hands on the mixer (leave them at "-1 / smooth / none / false / 0" when the plain mix is
        right; use a move when it makes the transition sound better, not to show off):
        - bass_swap_bar: the bar of the overlap where the basslines swap. Put it where the incoming's bass should take
          over: on a phrase line, or right where the outgoing's bass or groove ends.
        - fade: hold_then_drop keeps the outgoing's energy up to the last bars (a groove that should carry the floor until
          the incoming's phrase 1); early_handover lets the incoming take over quickly (the outgoing is about to sing or
          thin out).
        - filter: lowpass_out sinks the outgoing behind the incoming (a busy or bright outgoing); highpass_out washes it
          out (a big ending); highpass_in brings the incoming in thin (its intro would crowd the outgoing).
        - echo_out: the outgoing's last beat repeats over the incoming, a classic ending; good when the outgoing would
          otherwise stop on a voice or a hit.
        - loop_bars: the outgoing repeats 1, 2 or 4 bars from exit_bar for the whole overlap. Use it when the outgoing has
          no instrumental long enough: loop an instrumental bar WITH the bass (no voice in it) and lay the incoming over
          it, a bridge between the two. Loop whole phrases' bars (2 or 4) when you can; the loop must start on a bar
          whose groove repeats well.

        THE SWEET SPOT (the owner's words): the incoming's voice starts EXACTLY where the outgoing's voice would have come
        back. Read both tracks' sections and lyrics: if the outgoing would sing again after N bars of instrumental, enter
        the incoming so that it also sings after N bars, on the same beat, phrase on phrase; the two instrumentals then
        lie on top of each other with the same length, and the vocal line continues as if it were one song. When the two
        songs have similar shapes (intro / verse / chorus of similar lengths) this is easy; when they differ, use a trick:
        enter the incoming a few bars earlier or later in its intro, shorten or lengthen the overlap by whole bars, or
        loop an instrumental bar of the outgoing until the incoming's voice is due. OWNER_EDIT mixes in the ratings were
        placed by the owner by hand: they are the best examples of their taste.

        Use only bar numbers from the tables and stay inside the allowed ranges given. Answer with the JSON only.
        """.trimIndent()

    /** Lyrics lines and table rows kept per track: a 5-minute song is ~110 bars at 90 bpm. */
    private const val MAX_BARS = 220
    private const val MAX_LYRIC_LINES = 90
    private const val MAX_LYRIC_CHARS = 60

    /** A gap between lyric lines at least this long is written out as "no singing". */
    private const val LYRIC_GAP_MS = 4_000L

    /** "34.2": bar 34 (the tables' numbering), beat 2 of it; "-" before the first bar. */
    internal fun barBeat(bars: List<Long>, endMs: Long, perBar: Int, t: Long): String {
        val i = bars.indexOfLast { it <= t }
        if (i < 0) return "-"
        val s = bars[i]
        val e = bars.getOrNull(i + 1) ?: (s + (if (i > 0) s - bars[i - 1] else endMs - s)).coerceAtLeast(s + 1)
        val beat = (((t - s).toDouble() / (e - s)) * perBar).toInt().coerceIn(0, perBar - 1) + 1
        return "$i.$beat"
    }

    /** Fingerprint: phrases are 8 bars; two phrases are the same part above this similarity; a key fit below this is unsure. */
    private const val PHRASE_BARS = 8
    private const val SAME_PART = 0.75
    private const val WEAK_KEY = 0.6

    /** The shortest overlap an answer may ask for (shorter answers are lengthened). */
    const val MIN_OVERLAP_BARS = 4

    /** An exit must leave this many bars of the outgoing for the overlap. */
    private const val EXIT_TAIL_BARS = 4

    /** An entry must leave this much of the incoming after it (a quarter of a shorter track). */
    private const val ENTRY_TAIL_MS = 30_000L

    /** The owner's marked entry is offered while this much of the track is left after it. */
    private const val MARK_TAIL_MS = 8_000L

    /**
     * The brief for [out] -> [inc], or null when a track has no beat grid (nothing to count bars on). Exits are allowed from
     * [earliestExitMs] (the playhead plus the time the answer and the cue take) to [EXIT_TAIL_BARS] before the end.
     */
    fun build(out: Track, inc: Track, earliestExitMs: Long, requestedBars: Int, positionMs: Long, taste: String? = null, ahead: Boolean = false, entryLatestMs: Long? = null): Brief? {
        val outBars = bars(out.timeline) ?: return null
        val inBars = bars(inc.timeline) ?: return null
        val exitFrom = outBars.indexOfFirst { it >= earliestExitMs }.takeIf { it >= 0 } ?: return null
        val exitTo = (outBars.size - 1 - EXIT_TAIL_BARS).coerceAtMost(MAX_BARS - 1)
        if (exitTo < exitFrom) return null
        val inDur = inc.timeline.refined.durationMs
        // a short track keeps a quarter of itself after the entry, not 30 s: a 1:14 cumbia (owner, build bc) allowed entries
        // only up to 0:44, and the owner's own entry mark at 0:57 was out of range
        val tail = minOf(ENTRY_TAIL_MS, inDur / 4)
        var entryTo = inBars.indexOfLast { it <= inDur - tail }.coerceAtLeast(0).coerceAtMost(MAX_BARS - 1)
        // the owner's marked entry is always offered (while a few bars of the track are left after it)
        runCatching { MixMarks.of(inc.timeline.refined.videoId)?.entryMs }.getOrNull()?.let { m ->
            val bar = inBars.indices.minByOrNull { kotlin.math.abs(inBars[it] - m) }
            if (bar != null && bar > entryTo && inBars[bar] <= inDur - MARK_TAIL_MS) entryTo = bar.coerceAtMost(MAX_BARS - 1)
        }
        // The incoming must still mix into the track AFTER it: entering late in a track (the owner: "it mixed in near the
        // end and the next mix had no time") leaves no room for its own exit. The engine gives the latest entry that keeps
        // the incoming's best exit into its next track playable; the owner's marked entry still wins.
        val chainLimit = entryLatestMs?.let { lim -> inBars.indexOfLast { it <= lim } }?.takeIf { it >= 0 }
        if (chainLimit != null && chainLimit < entryTo) entryTo = chainLimit
        val user =
            buildString {
                // ahead: this pair is solved before it plays (the DJ works a few mixes ahead); the outgoing is not playing yet
                if (ahead) {
                    append("OUTGOING (not playing yet: it comes after the mix into it, and plays on its own from ${clock(positionMs)})\n")
                } else {
                    append("OUTGOING (playing now, at ${clock(positionMs)})\n")
                }
                append(table(out, outBars))
                append("\nINCOMING (next)\n")
                append(table(inc, inBars))
                append("\nAllowed: exit_bar ").append(exitFrom).append("..").append(exitTo)
                append(" (earlier bars have already played or come too soon), entry_bar 0..").append(entryTo)
                append(", overlap_bars ").append(MIN_OVERLAP_BARS).append("..32. The owner's usual overlap is ").append(requestedBars).append(" bars.\n")
                if (chainLimit != null && chainLimit == entryTo) {
                    append("The incoming must still mix well into the track after it, at its best point with that one, after playing on its own")
                    append(" a while: so the entry goes no later than bar ").append(entryTo).append(" (").append(clock(inBars[entryTo])).append(").")
                    append(" The set is a chain: every song joins the one before AND the one after.\n")
                }
                marks(out, outBars, inc, inBars)?.let { append('\n').append(it) }
                taste?.takeIf { it.isNotBlank() }?.let { append('\n').append(it.trimEnd()).append('\n') }
            }
        return Brief(SYSTEM, user, outBars, inBars, exitFrom, exitTo, entryTo)
    }

    /**
     * The owner's own mix marks on these two tracks (marked by ear, see [MixMarks]): where the outgoing may start to
     * leave, where the incoming starts when mixed into, from where it plays alone. Claude is told to honour them.
     */
    private fun marks(out: Track, outBars: List<Long>, inc: Track, inBars: List<Long>): String? {
        fun bar(bars: List<Long>, t: Long) = bars.indices.minByOrNull { kotlin.math.abs(bars[it] - t) }
        val mo = runCatching { MixMarks.of(out.timeline.refined.videoId) }.getOrNull()
        val mi = runCatching { MixMarks.of(inc.timeline.refined.videoId) }.getOrNull()
        val lines = listOfNotNull(
            mo?.exitMs?.let { "the outgoing's exit at bar ${bar(outBars, it)} (${clock(it)})" },
            mi?.entryMs?.let { "the incoming's entry at bar ${bar(inBars, it)} (${clock(it)})" },
            mi?.soloMs?.let { "the incoming plays alone from bar ${bar(inBars, it)} (${clock(it)})" },
        )
        if (lines.isEmpty()) return null
        return "THE OWNER MARKED, by ear: " + lines.joinToString("; ") + ". Use these points unless they are outside the allowed ranges.\n"
    }

    /** One mix the owner rated (👍 / 👎), as Claude reads it in [taste]. Null fields are left out. */
    data class RatedMix(
        val liked: Boolean,
        /** Who placed it: AI_MIX, REGULAR, PERFECT, STRUCTURE, OWNER... */
        val source: String?,
        val overlapS: Double?,
        val exitFraction: Float?,
        val entryFraction: Float?,
        val phraseOnPhrase: Boolean?,
        val bassSwap: Boolean?,
        val voiceOverVoiceS: Double?,
        val harmonyExcess: Float?,
        val bpm: Float?,
        /** The moves and the AI's why, when it was an AI mix (from the plan's reason). */
        val moves: String? = null,
        val why: String? = null,
        val titles: String? = null,
        /** Set when the mix involved this question's tracks ("THIS SAME PAIR", "with the incoming track as outgoing"...). */
        val about: String? = null,
        /** What the outgoing was doing around its exit and the incoming around its entry ([pointContext]). */
        val outAt: String? = null,
        val inAt: String? = null,
    )

    /**
     * What a track is doing over [startMs]..[endMs] (the part of it in a mix) and just before / after: sung or instrumental
     * (and from what: lyrics or the detector), whether the voice comes back or starts soon after, the bass and loudness as
     * 0-9 of the track's own range, whether the span starts on a 16-bar / 8-bar line, and where in the track it is. So a
     * rating says not only "liked" but on which structure, which is what lets Claude learn which trick fits which shape.
     */
    fun pointContext(t: TrackTimeline, startMs: Long, endMs: Long): String {
        val a = t.refined
        val dur = a.durationMs.coerceAtLeast(1)
        val hop = a.energyHopMs.coerceAtLeast(1)
        val parts = ArrayList<String>()
        parts += "at %d%% of the track".format(java.util.Locale.ROOT, (100 * startMs / dur).toInt())
        val v = t.vocals?.value
        if (v == null) {
            parts += "voice unknown"
        } else {
            val share = sungShare(v, startMs, endMs)
            parts += when {
                share < 0.15 -> "instrumental"
                share > 0.7 -> "sung"
                else -> "partly sung"
            } + if (t.vocalsFromLyrics) " (lyrics)" else " (detector)"
            if (sungShare(v, startMs - 8_000, startMs) > 0.5) parts += "a voice just before"
            v.firstOrNull { it.startMs >= endMs - 500 && it.startMs < endMs + 8_000 }?.let { parts += "voice starts %.0f s after".format(java.util.Locale.ROOT, (it.startMs - endMs) / 1000.0) }
        }
        parts += "bass " + Levels(a.lowBandEnergy, hop).at(startMs, endMs) + "/9"
        parts += "loud " + Levels(a.energy, hop).at(startMs, endMs) + "/9"
        val near = { lines: List<Long>? -> lines?.any { kotlin.math.abs(it - startMs) < 400 } == true }
        parts += when {
            near(t.blocks?.value?.startsMs) -> "starts on a 16-bar line"
            near(t.phrases?.value?.startsMs) -> "starts on an 8-bar line"
            else -> "not on a phrase line"
        }
        return parts.joinToString(", ")
    }

    /**
     * The owner's taste as Claude reads it: their latest ratings, liked and disliked, as the numbers the planner kept
     * (overlap, where in each song, phrase on phrase, voices, harmony, moves). Few-shot memory: no training, just
     * examples in the prompt. Null when there are none.
     */
    fun taste(mixes: List<RatedMix>, max: Int = MAX_TASTE): String? {
        if (mixes.isEmpty()) return null
        return buildString {
            append("THE OWNER'S RATINGS, newest first (learn what they like, avoid what they did not). Ratings of THESE tracks come first ")
            append("and are never forgotten: never repeat a mix of them the owner DISLIKED, and when they LIKED one of this same pair, ")
            append("prefer those points again unless they are not allowed now:\n")
            for (m in mixes.take(max)) {
                append(if (m.liked) "LIKED" else "DISLIKED")
                m.about?.let { append(" [").append(it).append(']') }
                m.source?.let { append(' ').append(it) }
                m.titles?.let { append(" (").append(it.take(70)).append(')') }
                append(':')
                val parts = listOfNotNull(
                    m.overlapS?.let { "%.0f s overlap".format(java.util.Locale.ROOT, it) },
                    m.bpm?.let { "%.0f bpm".format(java.util.Locale.ROOT, it) },
                    m.exitFraction?.let { "exit at %d%% of the outgoing".format(java.util.Locale.ROOT, (it * 100).roundToInt()) },
                    m.entryFraction?.let { "entry at %d%% of the incoming".format(java.util.Locale.ROOT, (it * 100).roundToInt()) },
                    m.phraseOnPhrase?.let { if (it) "phrase on phrase" else "not phrase on phrase" },
                    m.bassSwap?.let { if (it) "bass swap" else "no bass swap" },
                    m.voiceOverVoiceS?.let { if (it < 0.5) "no voice over voice" else "%.1f s voice over voice".format(java.util.Locale.ROOT, it) },
                    m.harmonyExcess?.let { if (it < 0.025f) "consonant" else if (it < 0.07f) "tense harmony" else "harmony clash" },
                    m.moves?.let { "moves: $it" },
                    m.outAt?.let { "outgoing there: $it" },
                    m.inAt?.let { "incoming there: $it" },
                    m.why?.let { "why: \"${it.take(120)}\"" },
                )
                append(' ').append(parts.joinToString(", ")).append('\n')
            }
        }
    }

    private const val MAX_TASTE = 22

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
            val moves = movesOf(o)
            // (a loop makes its own room)
            val room = if (moves.loopBars > 0) 32 else (brief.outBarsMs.size - 1 - exit).coerceAtLeast(1)
            AiMixPoints(brief.outBarsMs[exit], brief.inBarsMs[entry], overlap.coerceIn(MIN_OVERLAP_BARS, 32).coerceAtMost(room), why.ifEmpty { "no reason given" }, moves)
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
            org.simpmusic.dj.analysis.SongStructure.describe(t)?.let { append("sections (from the voice and what repeats): ").append(it).append('\n') }
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
            append(fingerprint(track, bars))
            val lyrics = track.lyrics?.filter { it.text.isNotBlank() && it.text.trim() != "♫" }
            if (!lyrics.isNullOrEmpty()) {
                // Each line with the bar.beat it starts on (the tables' bar numbers), so the line that ends a section and
                // the one that starts the next can be put on the exact beat; a long gap is written out as instrumental.
                append("lyrics (time, bar.beat where the line starts, text):\n")
                val per = (t.beatsPerBar ?: 4).coerceIn(2, 7)
                val kept = lyrics.take(MAX_LYRIC_LINES)
                // the sung ranges as the planner reads them (a line sings until the next, a long one ends by its words)
                val sung = org.simpmusic.dj.timeline.LyricVocals.ranges(track.lyrics.orEmpty(), end)
                for ((i, l) in kept.withIndex()) {
                    append(clock(l.startMs)).append(' ').append(barBeat(bars, end, per, l.startMs)).append(' ')
                    append(l.text.trim().take(MAX_LYRIC_CHARS)).append('\n')
                    val next = kept.getOrNull(i + 1)?.startMs ?: continue
                    val sungTo = sung.lastOrNull { it.startMs <= l.startMs }?.endMs?.coerceIn(l.startMs, next) ?: next
                    if (next - sungTo >= LYRIC_GAP_MS) {
                        append("  - no singing ").append(clock(sungTo)).append('-').append(clock(next))
                        append(" (bars ").append(barBeat(bars, end, per, sungTo)).append(" to ").append(barBeat(bars, end, per, next)).append(")\n")
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The fingerprint ("huella", the owner's word, 2026-10-17): the song as a DJ reads it, one row per 8-bar phrase.

    /** One phrase of a track: its bars, which part it is (same letter = the same music), its local key and levels. */
    internal class Phrase(val fromBar: Int, val toBar: Int, val part: Char, val key: String?, val repeatOf: Int?, val similarity: Double)

    /** Bars of 8 from the first trusted phrase line (or bar 0), each labelled by similarity and keyed by its own chroma. */
    internal fun phrases(t: TrackTimeline, bars: List<Long>): List<Phrase> {
        if (bars.size < 4) return emptyList()
        val lines = t.phrases?.value?.takeIf { it.trusted }?.startsMs
        val first = lines?.firstOrNull()?.let { l -> bars.indexOfFirst { kotlin.math.abs(it - l) < 400 } }?.takeIf { it >= 0 } ?: 0
        val starts = ArrayList<Int>()
        if (first > 0) starts += 0
        var b = first
        while (b < bars.size) { starts += b; b += PHRASE_BARS }
        val a = t.refined
        val end = a.durationMs
        val feats = starts.indices.map { i ->
            val s = bars[starts[i]]
            val e = starts.getOrNull(i + 1)?.let { bars[it] } ?: end
            phraseVector(a, s, e)
        }
        val profile = org.simpmusic.dj.analysis.Harmony.profile(a)
        val labels = CharArray(starts.size) { '?' }
        val repeatOf = arrayOfNulls<Int>(starts.size)
        val sims = DoubleArray(starts.size)
        var next = 'A'
        for (i in starts.indices) {
            val v = feats[i] ?: continue
            var best = -1
            var bestSim = -1.0
            for (j in 0 until i) {
                val w = feats[j] ?: continue
                val c = cosine(v, w)
                if (c > bestSim) { bestSim = c; best = j }
            }
            if (best >= 0 && bestSim >= SAME_PART) {
                labels[i] = labels[best]
                repeatOf[i] = best + 1
            } else {
                labels[i] = next
                if (next < 'Z') next++
            }
            sims[i] = bestSim.coerceAtLeast(0.0)
        }
        return starts.indices.map { i ->
            val s = bars[starts[i]]
            val e = starts.getOrNull(i + 1)?.let { bars[it] } ?: end
            val chroma = profile?.window(s, e - s)
            Phrase(starts[i], (starts.getOrNull(i + 1) ?: bars.size) - 1, labels[i], chroma?.let { localKey(it) }, repeatOf[i], sims[i])
        }
    }

    private fun fingerprint(track: Track, bars: List<Long>): String {
        val t = track.timeline
        val a = t.refined
        val hop = a.energyHopMs.coerceAtLeast(1)
        val loud = Levels(a.energy, hop)
        val bass = Levels(a.lowBandEnergy, hop)
        val vocals = t.vocals?.value
        val ps = phrases(t, bars)
        if (ps.isEmpty()) return ""
        return buildString {
            append("fingerprint: key ")
            append(a.key?.let { k -> "${k.value} conf %.2f%s".format(java.util.Locale.ROOT, k.confidence, if (k.confidence < org.simpmusic.dj.model.Trust.KEY) " (unsure)" else "") } ?: "unknown")
            org.simpmusic.dj.analysis.SongMap.describe(a)?.let { append(" | song map (s): ").append(it) }
            append('\n')
            append("phrase bars t part local-key loud bass voice repeats\n")
            ps.forEachIndexed { i, p ->
                val s = bars[p.fromBar]
                val e = bars.getOrNull(p.toBar + 1) ?: a.durationMs
                append(i + 1).append(' ').append(p.fromBar).append('-').append(p.toBar).append(' ').append(clock(s)).append(' ')
                append(p.part).append(' ').append(p.key ?: "-").append(' ')
                append(loud.at(s, e)).append(' ').append(bass.at(s, e)).append(' ')
                append(vocals?.let { level(sungShare(it, s, e)) } ?: "-").append(' ')
                append(p.repeatOf?.let { "phrase $it (%.2f)".format(java.util.Locale.ROOT, p.similarity) } ?: "-")
                append('\n')
            }
        }
    }

    /** Mean z-scored structure frame (chroma + timbre) over [s, e), or null without frames. */
    private fun phraseVector(a: org.simpmusic.dj.model.TrackAnalysis, s: Long, e: Long): DoubleArray? {
        val dim = org.simpmusic.dj.model.TrackAnalysis.STRUCTURE_DIM
        val hop = a.structureHopMs
        val f = a.structureFrames
        if (hop <= 0 || f.size < dim * 8) return null
        val n = f.size / dim
        val mean = DoubleArray(dim)
        val sd = DoubleArray(dim)
        for (i in 0 until n) for (d in 0 until dim) mean[d] += f[i * dim + d]
        for (d in 0 until dim) mean[d] /= n
        for (i in 0 until n) for (d in 0 until dim) { val x = f[i * dim + d] - mean[d]; sd[d] += x * x }
        for (d in 0 until dim) sd[d] = kotlin.math.sqrt(sd[d] / n).coerceAtLeast(1e-6)
        val i0 = (s / hop).toInt().coerceIn(0, n)
        val i1 = (e / hop).toInt().coerceIn(i0, n)
        if (i1 - i0 < 2) return null
        val v = DoubleArray(dim)
        for (i in i0 until i1) for (d in 0 until dim) v[d] += (f[i * dim + d] - mean[d]) / sd[d]
        for (d in 0 until dim) v[d] /= (i1 - i0)
        return v
    }

    private fun cosine(a: DoubleArray, b: DoubleArray): Double {
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) { ab += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i] }
        return if (aa <= 1e-12 || bb <= 1e-12) 0.0 else ab / kotlin.math.sqrt(aa * bb)
    }

    private val KK_MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val KK_MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
    private val NOTES = arrayOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

    /** The key a chroma window sounds in (Krumhansl-Kessler), "Am" / "C"; "?" appended when the fit is weak. */
    internal fun localKey(chroma: DoubleArray): String {
        var best = 0.0
        var name = "?"
        for (pc in 0 until 12) for ((profile, minor) in listOf(KK_MAJOR to false, KK_MINOR to true)) {
            var mv = 0.0; var mp = 0.0
            for (i in 0 until 12) { mv += chroma[i]; mp += profile[i] }
            mv /= 12; mp /= 12
            var num = 0.0; var dv = 0.0; var dp = 0.0
            for (i in 0 until 12) {
                val x = chroma[(i + pc) % 12] - mv
                val y = profile[i] - mp
                num += x * y; dv += x * x; dp += y * y
            }
            val r = if (dv <= 1e-18) 0.0 else num / kotlin.math.sqrt(dv * dp)
            if (r > best) { best = r; name = NOTES[pc] + if (minor) "m" else "" }
        }
        return if (best < WEAK_KEY) "$name?" else name
    }

    /** A curve's 0-9 level over a span (for the tools): `levelsOf(curve, hop)(startMs, endMs)`. */
    fun levelsOf(curve: List<Float>, hopMs: Int): (Long, Long) -> String = Levels(curve, hopMs.coerceAtLeast(1))::at

    /** How much of a span is sung, 0-9. */
    fun voiceLevel(r: List<org.simpmusic.dj.model.TimeRange>, s: Long, e: Long): String = level(sungShare(r, s, e))

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
