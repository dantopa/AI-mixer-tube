package org.simpmusic.dj.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.simpmusic.dj.model.AiMixPoints
import org.simpmusic.dj.model.DjSettings
import org.simpmusic.dj.model.PlanConstraints
import org.simpmusic.dj.model.PlanKind
import org.simpmusic.dj.model.PlanSource
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.planner.DjTransitionPlanner
import org.simpmusic.dj.model.TransitionPlanner
import org.simpmusic.dj.timeline.LyricLine
import org.simpmusic.dj.timeline.Timelines
import org.simpmusic.dj.timeline.TrackTimeline
import kotlin.math.abs

/**
 * "AI Mix with tools" (2026-10-17, the owner's experiment: "give the Bagracho the tools so it starts working like a real DJ").
 * Instead of one answer from one picture, Claude looks closer ([ZOOM]), tries a mix on the real planner and reads what it
 * measured ([TRY]: tempo bend, the harmony of that overlap, voice over voice, the bass on both sides, phrase on phrase),
 * corrects itself, and commits ([COMMIT]). The planner is the same one that will play the mix, so what Claude reads is
 * what will happen. Pure Kotlin: the Android client only carries the calls.
 */
class MixTools(
    private val brief: MixBrief.Brief,
    private val from: TrackAnalysis,
    private val to: TrackAnalysis,
    private val outLyrics: List<LyricLine>?,
    private val inLyrics: List<LyricLine>?,
    private val settings: DjSettings,
    private val earliestExitMs: Long,
    private val planner: TransitionPlanner = DjTransitionPlanner(),
) {
    /** One tool as the API declares it: a name, what it does, and a strict JSON schema of its input. */
    class Spec(val name: String, val description: String, val schema: Map<String, Any>)

    /** What a call gave: text for Claude, or the committed points (the end of the conversation). */
    sealed class Outcome {
        class Result(val text: String, val isError: Boolean = false) : Outcome()
        class Committed(val points: AiMixPoints) : Outcome()
    }

    private val outT: TrackTimeline = Timelines.of(from)
    private val inT: TrackTimeline = Timelines.of(to)

    /** How many mixes Claude tried (for the log). */
    var tries = 0
        private set

    fun call(name: String, inputJson: String): Outcome =
        try {
            val o = Json.parseToJsonElement(inputJson).jsonObject
            when (name) {
                ZOOM -> Outcome.Result(zoom(o))
                TRY -> Outcome.Result(tryMix(o))
                COMMIT -> commit(o)
                else -> Outcome.Result("unknown tool $name", isError = true)
            }
        } catch (e: Exception) {
            Outcome.Result("error: ${e.message}", isError = true)
        }

    private fun JsonObject.int(n: String): Int = this[n]?.jsonPrimitive?.intOrNull ?: error("missing $n")

    private fun zoom(o: JsonObject): String {
        val incoming = o["track"]?.jsonPrimitive?.contentOrNull == "incoming"
        val t = if (incoming) inT else outT
        val bars = if (incoming) brief.inBarsMs else brief.outBarsMs
        val lyrics = (if (incoming) inLyrics else outLyrics)?.filter { it.text.isNotBlank() && it.text.trim() != "♫" }
        val a = o.int("from_bar").coerceIn(0, bars.size - 1)
        val b = o.int("to_bar").coerceIn(a, (a + MAX_ZOOM_BARS - 1).coerceAtMost(bars.size - 1))
        val beats = t.beatsMs.map { it.toLong() }
        val r = t.refined
        val hop = r.energyHopMs.coerceAtLeast(1)
        val levels = listOf(r.energy, r.lowBandEnergy, r.highBandEnergy).map { MixBrief.levelsOf(it, hop) }
        val vocals = t.vocals?.value
        val end = bars.getOrNull(b + 1) ?: r.durationMs
        return buildString {
            append(if (incoming) "INCOMING" else "OUTGOING").append(" bars ").append(a).append('-').append(b).append(", one row per beat\n")
            append("bar.beat t loud bass high voice lyric\n")
            for (bar in a..b) {
                val s = bars[bar]
                val e = bars.getOrNull(bar + 1) ?: r.durationMs
                val inBar = beats.filter { it >= s && it < e }
                inBar.forEachIndexed { k, bt ->
                    val bn = inBar.getOrNull(k + 1) ?: e
                    append(bar).append('.').append(k + 1).append(' ').append("%.1f".format(java.util.Locale.ROOT, bt / 1000.0)).append(' ')
                    levels.forEach { append(it(bt, bn)).append(' ') }
                    append(vocals?.let { MixBrief.voiceLevel(it, bt, bn) } ?: "-")
                    lyrics?.firstOrNull { it.startMs >= bt - 50 && it.startMs < bn - 50 }?.let { append(' ').append('"').append(it.text.trim().take(48)).append('"') }
                    append('\n')
                }
            }
            if (end - bars[a] > 0 && beats.none { it >= bars[a] && it < end }) append("(no beats marked here)\n")
        }
    }

    /**
     * The overlap as the engine built it. Its units are beats when a pair's bars are not trusted (the plan counts beats
     * then), so it is said in bars either way; when it came out shorter than [askedBars], what cut it ([MixFeatures.cutBy]).
     */
    private fun overlapLine(f: org.simpmusic.dj.model.MixFeatures?, askedBars: Int, overlapS: Double, capReason: String?): String {
        val perBar = (from.beatsPerBar ?: 4).coerceAtLeast(1)
        val bars =
            when {
                f == null -> askedBars
                f.unitIsBar -> f.overlapUnits
                else -> (f.overlapUnits + perBar / 2) / perBar
            }
        val sb = StringBuilder("overlap %d bars = %.1f s".format(java.util.Locale.ROOT, bars, overlapS))
        if (f != null && !f.unitIsBar) sb.append(" (counted in beats: ${f.overlapUnits}; the bar lines of this pair are not trusted)")
        if (bars < askedBars) {
            sb.append(", shorter than the $askedBars bars asked: ").append(
                when {
                    capReason != null -> "capped at ${AiMixPoints.UNSURE_MAX_BARS} bars because $capReason (a long overlap is only safe when both tracks' 1s and voices are known)"
                    else -> cutReason(f?.cutBy)
                },
            )
        }
        return sb.append('\n').toString()
    }

    private fun cutReason(cutBy: String?): String =
                when (cutBy) {
                    "room" -> "one of the tracks ends too soon after these points"
                    "outro" -> "the outgoing's outro runs out there"
                    "intro" -> "the incoming's intro ends there (an entry later in the incoming, or a longer intro, gives more)"
                    "voice" -> "the second voice comes in"
                    "harmony" -> "the two clash harmonically, so the overlap is kept short"
                    "drift" -> "the local tempos drift apart over a longer overlap"
                    else -> "the engine kept what stayed in time"
                }

    private fun points(o: JsonObject, why: String): AiMixPoints {
        val exit = o.int("exit_bar")
        val entry = o.int("entry_bar")
        val overlap = o.int("overlap_bars")
        require(exit in brief.exitFrom..brief.exitTo) { "exit_bar $exit is outside ${brief.exitFrom}..${brief.exitTo}" }
        require(entry in 0..brief.entryTo) { "entry_bar $entry is outside 0..${brief.entryTo}" }
        val moves = MixBrief.movesOf(o)
        val room = if (moves.loopBars > 0) 32 else (brief.outBarsMs.size - 1 - exit).coerceAtLeast(1)
        return AiMixPoints(brief.outBarsMs[exit], brief.inBarsMs[entry], overlap.coerceIn(MixBrief.MIN_OVERLAP_BARS, 32).coerceAtMost(room), why, moves)
    }

    private fun tryMix(o: JsonObject): String {
        tries++
        val pts = points(o, "try")
        val p = planner.plan(from, to, settings, PlanConstraints(earliestExitMs = earliestExitMs, aiPoints = pts))
        val f = p.meta?.features
        return buildString {
            if (p.meta?.source != PlanSource.AI_MIX || p.kind != PlanKind.BEAT_MATCHED) {
                append("NOT POSSIBLE at these points: ").append(p.reason.substringAfter(AiMixPoints.NOT_USED, p.reason).take(240)).append('\n')
                return@buildString
            }
            val overlapS = p.overlapMs / 1000.0
            append("OK: beat-matched at %.1f bpm, ".format(java.util.Locale.ROOT, p.mixBpm ?: 0f))
            append("outgoing x%.3f incoming x%.3f (tempo bend %.1f%%), ".format(java.util.Locale.ROOT, f?.rateOut ?: 1f, f?.rateIn ?: 1f, 100 * maxOf(abs((f?.rateOut ?: 1f) - 1f), abs((f?.rateIn ?: 1f) - 1f))))
            append(overlapLine(f, pts.overlapBars, overlapS, p.reason.substringAfter(AiMixPoints.UNSURE_CAP, "").substringAfter(": ", "").substringBefore(')').takeIf { it.isNotEmpty() }))
            append("exit at %s (%s), entry at %s (%s), phrase on phrase: %s\n".format(java.util.Locale.ROOT, clock(p.exitPointMs), f?.exitLevel ?: "?", clock(p.entryPointMs), f?.entryLevel ?: "?", if (f?.phraseOnPhrase == true) "yes" else "no"))
            val h = f?.harmonyExcess
            append("harmony of the overlap: ").append(
                when {
                    h == null -> "unknown"
                    f.harmonyClash -> "CLASH (excess %.3f): the two sound out of key together".format(java.util.Locale.ROOT, h)
                    h < 0.025 -> "consonant (excess %.3f)".format(java.util.Locale.ROOT, h)
                    else -> "tense (excess %.3f)".format(java.util.Locale.ROOT, h)
                },
            ).append('\n')
            val loop = p.outLoop
            append("voice over voice: ").append(voiceOverVoice(p.exitPointMs, p.entryPointMs, p.overlapMs, loop)).append('\n')
            voiceContinuity(p.exitPointMs, p.entryPointMs, f?.rateOut ?: 1f, f?.rateIn ?: 1f, p.mixBpm, loop)?.let { append(it).append('\n') }
            // the bass of each track over its own part of the overlap, 0-9 of that track's range (a loop's: the loop itself)
            val outEnd = p.exitPointMs + p.overlapMs
            val inEnd = p.entryPointMs + p.overlapMs
            append("bass through the overlap: outgoing ").append(MixBrief.levelsOf(outT.refined.lowBandEnergy, outT.refined.energyHopMs)(loop?.startMs ?: p.exitPointMs, loop?.endMs ?: outEnd))
            append(", incoming ").append(MixBrief.levelsOf(inT.refined.lowBandEnergy, inT.refined.energyHopMs)(p.entryPointMs, inEnd)).append('\n')
            if (loop != null) {
                append("the outgoing loops %s-%s (%.1f s) under the whole overlap; the incoming plays alone from its %s\n".format(java.util.Locale.ROOT, clock(loop.startMs), clock(loop.endMs), loop.lengthMs / 1000.0, clock(inEnd)))
            } else {
                append("the incoming plays alone from its %s; the outgoing would have continued at %s\n".format(java.util.Locale.ROOT, clock(inEnd), clock(outEnd)))
            }
            p.reason.substringAfter("; moves: ", "").substringBefore(" | ").takeIf { it.isNotBlank() }?.let { append("moves: ").append(it).append('\n') }
            p.reason.substringAfter(DjTransitionPlanner.LOOP_NOT_USED, "").takeIf { it.isNotBlank() }?.let { append("loop NOT used: ").append(it.take(200)).append('\n') }
        }
    }

    /**
     * Seconds both tracks sing at once over the overlap, from each track's voice ranges (synced lyrics when present, else a
     * trusted detection). The plan's own figure is not used: the AI path ignores the detector, as the owner's marks do.
     */
    private fun voiceOverVoice(exitMs: Long, entryMs: Long, overlapMs: Long, loop: org.simpmusic.dj.model.SourceLoop?): String {
        fun outAt(t: Long): Long = loop?.realMs((exitMs + t).toDouble())?.toLong() ?: (exitMs + t)
        val vo = outT.vocals?.value
        val vi = inT.vocals?.value
        if (vo == null && vi == null) return "unknown (no trusted voice on either track)"
        if (vo == null || vi == null) return "unknown on the ${if (vo == null) "outgoing" else "incoming"} (the other sings %.1f s of it)".format(
            java.util.Locale.ROOT,
            (vo ?: vi!!).sumOf { r -> (minOf(r.endMs, (if (vo != null) exitMs else entryMs) + overlapMs) - maxOf(r.startMs, if (vo != null) exitMs else entryMs)).coerceAtLeast(0L) } / 1000.0,
        )
        var both = 0L
        var t = 0L
        while (t < overlapMs) {
            if (vo.any { outAt(t) in it.startMs until it.endMs } && vi.any { entryMs + t in it.startMs until it.endMs }) both += STEP_MS
            t += STEP_MS
        }
        return if (both == 0L) "none" else "%.1f s".format(java.util.Locale.ROOT, both / 1000.0)
    }

    /**
     * The owner's sweet spot: the incoming's voice starts exactly where the outgoing's voice would have come back, so the
     * song seems to go on (and the two instrumentals have the same length). Measured in wall time from T0: the outgoing's
     * next sung line after its exit (what it would have done) against the incoming's first sung line after its entry.
     */
    private fun voiceContinuity(exitMs: Long, entryMs: Long, rateOut: Float, rateIn: Float, mixBpm: Float?, loop: org.simpmusic.dj.model.SourceLoop?): String? {
        val vo = outT.vocals?.value ?: return null
        val vi = inT.vocals?.value ?: return null
        val nextOut = org.simpmusic.dj.analysis.SongMap.sung(vo).firstOrNull { it.startMs >= exitMs + 500 }?.startMs
        val nextIn = org.simpmusic.dj.analysis.SongMap.sung(vi).firstOrNull { it.startMs >= entryMs - 500 }?.startMs
        if (nextIn == null) return "voice continuity: the incoming does not sing after its entry"
        val inWall = (nextIn - entryMs) / rateIn.coerceAtLeast(0.5f)
        if (nextOut == null || loop != null) return "voice continuity: the incoming starts singing %.1f s after T0 (the outgoing has no voice to come back to%s)".format(java.util.Locale.ROOT, inWall / 1000.0, if (loop != null) ", it loops" else "")
        val outWall = (nextOut - exitMs) / rateOut.coerceAtLeast(0.5f)
        val diff = inWall - outWall
        val beat = mixBpm?.takeIf { it > 0f }?.let { 60_000.0 / it }
        val beats = beat?.let { " = %+.1f beats".format(java.util.Locale.ROOT, diff / it) } ?: ""
        val verdict = when {
            abs(diff) <= (beat ?: 500.0) -> "SEAMLESS: the incoming sings where the outgoing would have"
            diff < 0 -> "the incoming sings EARLIER than the outgoing would have"
            else -> "the incoming sings LATER than the outgoing would have (a longer instrumental)"
        }
        return "voice continuity: the outgoing's voice would come back %.1f s after T0, the incoming sings %.1f s after T0 (%+.1f s%s): %s".format(
            java.util.Locale.ROOT, outWall / 1000.0, inWall / 1000.0, diff / 1000.0, beats, verdict,
        )
    }

    private fun commit(o: JsonObject): Outcome {
        val why = o["why"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().ifEmpty { "no reason given" }
        return Outcome.Committed(points(o, why))
    }

    companion object {
        const val ZOOM = "zoom"
        const val TRY = "try_mix"
        const val COMMIT = "commit_mix"

        /** At most this many bars per zoom (a beat per row). */
        const val MAX_ZOOM_BARS = 16

        /** Tool turns before the conversation is cut (and the DJ's own plan kept). */
        const val MAX_TURNS = 8

        private const val STEP_MS = 100L

        private val POINTS: Map<String, Any> =
            mapOf(
                "exit_bar" to mapOf("type" to "integer", "description" to "Bar of the OUTGOING table where the mix starts."),
                "entry_bar" to mapOf("type" to "integer", "description" to "Bar of the INCOMING table that plays at that moment."),
                "overlap_bars" to mapOf("type" to "integer", "description" to "Bars both tracks play together (at least ${MixBrief.MIN_OVERLAP_BARS})."),
            ) + MixBrief.MOVES_PROPS

        private fun obj(props: Map<String, Any>) =
            mapOf("type" to "object", "properties" to props, "required" to props.keys.toList(), "additionalProperties" to false)

        val SPECS: List<Spec> =
            listOf(
                Spec(
                    ZOOM,
                    "Look closer at one track: one row per beat for up to $MAX_ZOOM_BARS bars, with loudness, bass, highs and voice " +
                        "(0-9) and the lyric line that starts on that beat. Use it to see exactly where a voice or the bass starts.",
                    obj(
                        mapOf(
                            "track" to mapOf("type" to "string", "enum" to listOf("outgoing", "incoming")),
                            "from_bar" to mapOf("type" to "integer"),
                            "to_bar" to mapOf("type" to "integer"),
                        ),
                    ),
                ),
                Spec(
                    TRY,
                    "Try a mix on the engine that will play it, without committing. It says whether it can be beat-matched there and " +
                        "measures it: tempo bend, the harmony of that exact overlap, seconds of voice over voice, the bass on both " +
                        "sides, whether it is phrase on phrase. Try a few candidates, compare, then commit the best.",
                    obj(POINTS),
                ),
                Spec(
                    COMMIT,
                    "Commit the mix to play. Call it once, at the end, with the points you chose and one sentence of why.",
                    obj(POINTS + ("why" to mapOf("type" to "string"))),
                ),
            )

        /** Added to the system prompt in tool mode. */
        val TOOL_RULES: String =
            """

            You have tools. Work like a DJ preparing the transition: read the tables and the fingerprint, zoom where you are
            unsure (where exactly the voice and the bass start), try two to four candidate mixes with try_mix and compare what
            the engine measures, then call commit_mix once with the best. Spread the candidates over the songs: at least one
            should not be at the last exit of the outgoing or at bar 0 of the incoming (a break in the middle, a later
            section of the incoming). When try_mix says the overlap came out shorter than asked and why, move the points
            (an entry past the incoming's intro, an exit with more song after it) if that gives a longer clean overlap. Prefer the candidate with no voice over voice, the
            bass on both sides, a consonant overlap and phrase on phrase, at the owner's usual length when it stays clean.
            try_mix also reports "voice continuity": aim for SEAMLESS (the incoming's voice starts on the beat where the
            outgoing's voice would have come back, the two instrumentals the same length); when the shapes differ, move the
            entry or the exit by whole bars, or use a loop or a longer/shorter overlap, until it is.
            try_mix takes the moves too and says what they did (and when a loop could not be used, why): try a candidate with
            the plain mix and one with the moves you think fit, and keep what the engine measures as cleaner. A loop changes
            what the outgoing plays under the overlap, so try_mix measures the loop's own bass and voice.
            Do not explain at length between calls; the commit's why is enough.
            """.trimIndent()

        private fun clock(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return "%d:%02d".format(java.util.Locale.ROOT, s / 60, s % 60)
        }
    }
}
