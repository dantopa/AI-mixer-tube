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
import org.simpmusic.dj.model.settleMs
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
    /** The tracks' audio, for [LOOK] and [LISTEN] (Pro mode); null = those tools are not offered. */
    private val ears: MixEars? = null,
    /** Pro mode: the extra tools ([LOOK], [LISTEN], [CANDIDATES]) and a longer conversation ([PRO_MAX_TURNS]). */
    val pro: Boolean = false,
    /**
     * Other upcoming tracks of the queue (analysed), any of which Claude may play next instead of [to] ([SWAP]); empty =
     * the queue cannot be reordered now (shuffle, a playlist, a pair solved ahead) and the queue tools are not offered.
     */
    private val alternatives: List<Alternative> = emptyList(),
    /** The title of the queued next ([to]), for [QUEUE]. */
    private val nextTitle: String = "the queued next",
) {
    /** An upcoming track Claude may move into the next slot. */
    class Alternative(val id: String, val title: String, val analysis: TrackAnalysis)

    /** True when the queue tools ([QUEUE], [SWAP]) are offered. */
    val canReorder: Boolean get() = alternatives.isNotEmpty()

    /** Decoded audio of a stretch of one of the two tracks (source time), or null when it cannot be had. Blocking. */
    fun interface MixEars {
        fun audio(incoming: Boolean, fromMs: Long, toMs: Long): org.simpmusic.dj.render.StereoPcm?
    }

    /** The tools offered in this conversation. */
    val specs: List<Spec>
        get() = (if (!pro) SPECS else SPECS + PRO_SPECS.filter { ears != null || it.name == CANDIDATES }) + (if (canReorder) QUEUE_SPECS else emptyList())

    /** Tool turns before the conversation is cut. */
    val maxTurns: Int get() = if (pro) PRO_MAX_TURNS else MAX_TURNS
    /** One tool as the API declares it: a name, what it does, and a strict JSON schema of its input. */
    class Spec(val name: String, val description: String, val schema: Map<String, Any>)

    /** What a call gave: text for Claude, or the committed points (the end of the conversation). */
    sealed class Outcome {
        class Result(val text: String, val isError: Boolean = false, val image: ByteArray? = null) : Outcome()
        /** [runnerUp]: the best other candidate Claude tried or listened to, for the owner's A/B (null = none). */
        class Committed(val points: AiMixPoints, val runnerUp: AiMixPoints? = null) : Outcome()
        /** Claude chose to play the upcoming track [id] next instead (the conversation ends; the new pair is asked again). */
        class Swap(val id: String, val why: String) : Outcome()
    }

    private val outT: TrackTimeline = Timelines.of(from)
    private val inT: TrackTimeline = Timelines.of(to)

    /** Every candidate tried or listened to, with the judge's score when it was listened to (else a neutral 5). */
    private val scored = LinkedHashMap<String, Pair<AiMixPoints, Double>>()

    private fun keyOf(p: AiMixPoints) = "${p.exitMs}/${p.entryMs}/${p.overlapBars}/${p.moves}"

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
                LOOK -> look(o)
                LISTEN -> listen(o)
                CANDIDATES -> Outcome.Result(candidates(o))
                QUEUE -> Outcome.Result(queueOptions())
                SWAP -> swap(o)
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
        if (p.meta?.source == PlanSource.AI_MIX && p.kind == PlanKind.BEAT_MATCHED) scored.putIfAbsent(keyOf(pts), pts to 5.0)
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

    // ---------------------------------------------------------------- Pro mode

    /** [LOOK]: a spectrogram of up to [MAX_LOOK_BARS] bars of one track, with its bar lines and where it sings. */
    private fun look(o: JsonObject): Outcome {
        val e = ears ?: return Outcome.Result("look is not available in this conversation", isError = true)
        val incoming = o["track"]?.jsonPrimitive?.contentOrNull == "incoming"
        val t = if (incoming) inT else outT
        val bars = if (incoming) brief.inBarsMs else brief.outBarsMs
        val a = o.int("from_bar").coerceIn(0, bars.size - 1)
        val b = o.int("to_bar").coerceIn(a, (a + MAX_LOOK_BARS - 1).coerceAtMost(bars.size - 1))
        val fromMs = bars[a]
        val toMs = bars.getOrNull(b + 1) ?: t.refined.durationMs
        val pcm = e.audio(incoming, fromMs, toMs) ?: return Outcome.Result("the audio of the ${if (incoming) "incoming" else "outgoing"} track could not be decoded here", isError = true)
        val marks = (a..b).map { bar -> Spectrogram.Mark(bars[bar].toDouble(), if (bar % 8 == 0) Spectrogram.Kind.PHRASE else if (bar % 4 == 0) Spectrogram.Kind.BAR4 else Spectrogram.Kind.BAR) }
        val vocals = t.vocals?.value
        val strips = listOfNotNull(vocals?.let { v -> Spectrogram.Strip(0xFF4040) { ms -> if (v.any { ms.toLong() in it.startMs until it.endMs }) 1f else 0f } })
        val png = Spectrogram.png(pcm.left, pcm.right, pcm.sampleRate, fromMs.toDouble(), marks, strips)
        val text =
            "${if (incoming) "INCOMING" else "OUTGOING"} bars $a-$b (${clock(fromMs)}-${clock(toMs)}), spectrogram: time left to right, " +
                "40 Hz at the bottom to 12 kHz at the top (log), brightness = loudness. A thin vertical line on every bar line, " +
                "brighter on bars divisible by 4, white on bars divisible by 8; the first line is bar $a. Dashed lines at 250 Hz " +
                "(the bass and kick live below) and 2 kHz (voice, accordion, keys and guitars between; cymbals and güiro above). " +
                (if (vocals != null) "The red strip on top is where someone sings (synced lyrics, else a trusted detection). " else "Where it sings is not known for this track. ") +
                "A sung line shows as bright, wavy horizontal harmonics between the dashed lines; a drum-only stretch is vertical streaks."
        return Outcome.Result(text, image = png)
    }

    /**
     * [LISTEN]: the mix at these points rendered by the same renderer the preview uses, as a spectrogram with the faders on
     * top, plus what try_mix measures and the mix's level bar by bar (a dip or a jump in level is what an ear notices first).
     */
    private fun listen(o: JsonObject): Outcome {
        val e = ears ?: return Outcome.Result("listen is not available in this conversation", isError = true)
        val measured = tryMix(o)
        val pts = points(o, "listen")
        val p = planner.plan(from, to, settings, PlanConstraints(earliestExitMs = earliestExitMs, aiPoints = pts))
        if (p.meta?.source != PlanSource.AI_MIX || p.kind != PlanKind.BEAT_MATCHED) return Outcome.Result(measured)
        val lead = LISTEN_LEAD_MS
        val tail = LISTEN_TAIL_MS
        val settle = p.settleMs
        val outFrom = (p.exitPointMs - (lead * 1.15).toLong() - 1_500).coerceAtLeast(0)
        val outTo = (p.outLoop?.let { it.endMs + 1_000 } ?: (p.exitPointMs + (p.overlapMs * 1.15).toLong())) + 1_500
        val inFrom = (p.entryPointMs - 1_500).coerceAtLeast(0)
        val inTo = p.entryPointMs + ((settle + tail) * 1.15).toLong() + 1_500
        val outPcm = e.audio(false, outFrom, outTo) ?: return Outcome.Result("$measured\n(the outgoing's audio could not be decoded, so the mix was not rendered)")
        val inPcm = e.audio(true, inFrom, inTo) ?: return Outcome.Result("$measured\n(the incoming's audio could not be decoded, so the mix was not rendered)")
        if (outPcm.sampleRate != inPcm.sampleRate) return Outcome.Result("$measured\n(the two tracks decode at different rates, so the mix was not rendered)")
        val w =
            org.simpmusic.dj.render.OfflineMixRenderer.render(
                p,
                org.simpmusic.dj.render.AudioSegment(outPcm, outFrom),
                org.simpmusic.dj.render.AudioSegment(inPcm, inFrom),
                org.simpmusic.dj.render.OfflineMixRenderer.Options(leadMs = lead, tailMs = tail),
            )
        val mix = w.audio
        val sr = mix.sampleRate
        val beatMs = 60_000.0 / (p.mixBpm ?: 120f)
        val barMs = beatMs * (from.beatsPerBar ?: 4).coerceAtLeast(1)
        val endMs = w.startMs + mix.frames * 1000.0 / sr
        val marks = ArrayList<Spectrogram.Mark>()
        var k = kotlin.math.floor(w.startMs / barMs).toInt()
        while (k * barMs < endMs) {
            if (k * barMs >= w.startMs) marks += Spectrogram.Mark(k * barMs, if (k % 4 == 0) Spectrogram.Kind.BAR4 else Spectrogram.Kind.BAR)
            k++
        }
        marks += Spectrogram.Mark(0.0, Spectrogram.Kind.MIX_START)
        marks += Spectrogram.Mark(p.overlapMs.toDouble(), Spectrogram.Kind.MIX_END)
        val strips =
            listOf(
                Spectrogram.Strip(0xFF9930) { t -> p.outgoing.volume.valueAt(t) },
                Spectrogram.Strip(0x30C0FF) { t -> p.incoming.volume.valueAt(t) },
            )
        val png = Spectrogram.png(mix.left, mix.right, sr, w.startMs, marks, strips)
        val levels =
            buildString {
                var bar = kotlin.math.floor(w.startMs / barMs).toInt()
                while (bar * barMs < endMs) {
                    val f0 = ((bar * barMs - w.startMs) * sr / 1000).toInt()
                    val f1 = (((bar + 1) * barMs - w.startMs) * sr / 1000).toInt()
                    if (f1 > 0) append(if (bar < 0) "$bar" else "+$bar").append(':').append("%.0f".format(java.util.Locale.ROOT, Spectrogram.rmsDb(mix.left, mix.right, f0, f1))).append(' ')
                    bar++
                }
            }.trim()
        var peak = 0f
        for (i in 0 until mix.frames) peak = maxOf(peak, abs(mix.left[i]), abs(mix.right[i]))
        val verdict =
            runCatching {
                MixJudge.judge(p, org.simpmusic.dj.render.AudioSegment(outPcm, outFrom), org.simpmusic.dj.render.AudioSegment(inPcm, inFrom), barMs, lead, tail)
            }.getOrNull()
        scored[keyOf(pts)] = pts to (verdict?.score ?: 5.0)
        val text =
            measured.trimEnd() + "\n" +
                (verdict?.let { it.text + "\n" } ?: "") +
                "LISTENED: the mix rendered from %.1f s before T0 to %.1f s after it (the same renderer and lanes the engine plays). ".format(java.util.Locale.ROOT, -w.startMs / 1000.0, endMs / 1000.0) +
                "Picture: green line = T0 (the incoming starts), red line = the outgoing is silent; thin lines on the mix's bars " +
                "(brighter every 4 from T0); top strips = the outgoing's fader (orange) and the incoming's (blue). Dashed lines at 250 Hz and 2 kHz.\n" +
                "mix level per bar from T0 (dBFS RMS; a drop of 3+ dB is a hole the ear hears, a rise of 3+ dB is a bump): $levels\n" +
                "peak %.1f dBFS".format(java.util.Locale.ROOT, 20 * kotlin.math.log10(peak.coerceAtLeast(1e-6f).toDouble()))
        return Outcome.Result(text, image = png)
    }

    /**
     * [CANDIDATES]: exits and entries spread over the allowed ranges (every 4 bars, plus the incoming's bar 0), each planned
     * and measured by the engine, the cleanest first. A map to start from, not a decision.
     */
    private fun candidates(o: JsonObject): String {
        val bars = o["overlap_bars"]?.jsonPrimitive?.intOrNull?.coerceIn(MixBrief.MIN_OVERLAP_BARS, 32) ?: 8
        val exits = (brief.exitFrom..brief.exitTo).filter { (it - brief.exitFrom) % 4 == 0 }.let { if (it.size > 8) it.filterIndexed { i, _ -> i % ((it.size + 7) / 8) == 0 } else it }
        val entries = (listOf(0) + (4..brief.entryTo step 4)).distinct().let { if (it.size > 6) it.filterIndexed { i, _ -> i % ((it.size + 5) / 6) == 0 } else it }
        class C(val exit: Int, val entry: Int, val score: Double, val line: String)
        val found = ArrayList<C>()
        for (x in exits) for (n in entries) {
            val pts = AiMixPoints(brief.outBarsMs[x], brief.inBarsMs[n], bars.coerceAtMost((brief.outBarsMs.size - 1 - x).coerceAtLeast(1)), "candidate", org.simpmusic.dj.model.MixMoves())
            val p = runCatching { planner.plan(from, to, settings, PlanConstraints(earliestExitMs = earliestExitMs, aiPoints = pts)) }.getOrNull() ?: continue
            if (p.meta?.source != PlanSource.AI_MIX || p.kind != PlanKind.BEAT_MATCHED) continue
            val f = p.meta?.features
            val vov = voiceOverVoice(p.exitPointMs, p.entryPointMs, p.overlapMs, p.outLoop)
            val cont = voiceContinuity(p.exitPointMs, p.entryPointMs, f?.rateOut ?: 1f, f?.rateIn ?: 1f, p.mixBpm, p.outLoop)
            val h = f?.harmonyExcess
            val harmony = when {
                h == null -> "harmony ?"
                f.harmonyClash -> "CLASH"
                h < 0.025 -> "consonant"
                else -> "tense"
            }
            val gotBars = f?.let { if (it.unitIsBar) it.overlapUnits else (it.overlapUnits + 2) / 4 } ?: bars
            var score = 0.0
            score += when (vov) { "none" -> 3.0; else -> if (vov.startsWith("unknown")) 1.0 else -2.0 }
            score += when (harmony) { "consonant" -> 2.0; "tense" -> 0.5; "CLASH" -> -2.0; else -> 0.0 }
            if (f?.phraseOnPhrase == true) score += 1.0
            if (cont?.contains("SEAMLESS") == true) score += 2.0
            score += 1.5 * (gotBars.toDouble() / bars).coerceAtMost(1.0)
            score -= 5 * maxOf(abs((f?.rateOut ?: 1f) - 1f), abs((f?.rateIn ?: 1f) - 1f))
            found += C(
                x, n, score,
                "exit_bar $x entry_bar $n: %d bars (%.1f s), %s, voice over voice %s, phrase on phrase %s, bend %.1f%%%s".format(
                    java.util.Locale.ROOT, gotBars, p.overlapMs / 1000.0, harmony, vov, if (f?.phraseOnPhrase == true) "yes" else "no",
                    100 * maxOf(abs((f?.rateOut ?: 1f) - 1f), abs((f?.rateIn ?: 1f) - 1f)),
                    cont?.let { c -> if ("SEAMLESS" in c) ", SEAMLESS" else if ("EARLIER" in c) ", incoming sings early" else if ("LATER" in c) ", incoming sings late" else "" } ?: "",
                ),
            )
        }
        if (found.isEmpty()) return "no candidate could be beat-matched in the allowed ranges (exits ${brief.exitFrom}-${brief.exitTo}, entries 0-${brief.entryTo}) at $bars bars"
        return buildString {
            append("${found.size} candidates planned at $bars bars (exits every 4 bars from ${brief.exitFrom}, entries at 0 and every 4 bars), the cleanest first. ")
            append("Plain mixes, no moves; only the engine's measurements, not a decision:\n")
            found.sortedByDescending { it.score }.take(MAX_CANDIDATES).forEach { append(it.line).append('\n') }
        }
    }

    /** One line: how [from] mixes into [a] with the DJ's own planner (where it would mix, how cleanly). */
    private fun pairLine(label: String, title: String, a: TrackAnalysis): String {
        val tFrom = Timelines.of(from).bpm?.value
        val tTo = Timelines.of(a).bpm?.value
        val gap = if (tFrom != null && tTo != null) listOf(tTo / tFrom, 2 * tTo / tFrom, tTo / (2 * tFrom)).minOf { abs(it - 1f) } else null
        val p = runCatching { planner.plan(from, a, settings, PlanConstraints(earliestExitMs = earliestExitMs, preferPerfect = true)) }.getOrNull()
            ?: return "$label \"$title\": could not be planned"
        val f = p.meta?.features
        val h = f?.harmonyExcess
        val harmony = when { h == null -> "harmony ?"; f.harmonyClash -> "harmony CLASH"; h < 0.025 -> "consonant"; else -> "tense" }
        val vov = voiceOverVoiceWith(a, p.exitPointMs, p.entryPointMs, p.overlapMs)
        return "%s \"%s\": %s bpm -> %s bpm (gap %s), %s, quality %s, %.1f s overlap, %s, voice over voice %s".format(
            java.util.Locale.ROOT, label, title, tFrom?.let { "%.0f".format(java.util.Locale.ROOT, it) } ?: "?", tTo?.let { "%.0f".format(java.util.Locale.ROOT, it) } ?: "?",
            gap?.let { "%.0f%%".format(java.util.Locale.ROOT, 100 * it) } ?: "?", p.kind, p.meta?.quality ?: "?", p.overlapMs / 1000.0, harmony, vov,
        )
    }

    private fun voiceOverVoiceWith(a: TrackAnalysis, exitMs: Long, entryMs: Long, overlapMs: Long): String {
        val vo = outT.vocals?.value ?: return "unknown"
        val vi = Timelines.of(a).vocals?.value ?: return "unknown"
        var both = 0L
        var t = 0L
        while (t < overlapMs) {
            if (vo.any { exitMs + t in it.startMs until it.endMs } && vi.any { entryMs + t in it.startMs until it.endMs }) both += STEP_MS
            t += STEP_MS
        }
        return if (both == 0L) "none" else "%.1f s".format(java.util.Locale.ROOT, both / 1000.0)
    }

    /** [QUEUE]: the queued next and the other upcoming tracks, each planned from the playing track by the DJ's planner. */
    private fun queueOptions(): String = buildString {
        append("How the playing track would mix into each upcoming track (the DJ's own plan, before your choices):\n")
        append(pairLine("NOW NEXT", nextTitle, to)).append('\n')
        alternatives.forEach { append(pairLine("id ${it.id}", it.title, it.analysis)).append('\n') }
        append("swap_next moves one of them into the next slot (the radio's other tracks keep their order); you are then asked again for that pair.")
    }

    private fun swap(o: JsonObject): Outcome {
        val id = o["track_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val alt = alternatives.firstOrNull { it.id == id } ?: return Outcome.Result("unknown track_id \"$id\": use an id from queue_options (${alternatives.joinToString { it.id }})", isError = true)
        return Outcome.Swap(alt.id, o["why"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().ifEmpty { "no reason given" })
    }

    private fun commit(o: JsonObject): Outcome {
        val why = o["why"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().ifEmpty { "no reason given" }
        val pts = points(o, why)
        val chosen = keyOf(pts)
        val runnerUp =
            scored.entries.filter { it.key != chosen && (it.value.first.exitMs != pts.exitMs || it.value.first.entryMs != pts.entryMs || it.value.first.overlapBars != pts.overlapBars || it.value.first.moves != pts.moves) }
                .maxByOrNull { it.value.second }?.value?.first
        return Outcome.Committed(pts, runnerUp)
    }

    companion object {
        const val ZOOM = "zoom"
        const val TRY = "try_mix"
        const val COMMIT = "commit_mix"
        const val LOOK = "look"
        const val LISTEN = "listen"
        const val CANDIDATES = "candidates"
        const val QUEUE = "queue_options"
        const val SWAP = "swap_next"

        /** Pro mode: tool turns before the conversation is cut. */
        const val PRO_MAX_TURNS = 20

        /** At most this many bars per look (a spectrogram over more gets too coarse to read a beat). */
        const val MAX_LOOK_BARS = 16

        /** How much of the mix [LISTEN] renders before T0 and after the outgoing is silent. */
        const val LISTEN_LEAD_MS = 4_000L
        const val LISTEN_TAIL_MS = 3_000L

        /** Candidates listed by [CANDIDATES]. */
        const val MAX_CANDIDATES = 10

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

        /** The extra tools of Pro mode. */
        val PRO_SPECS: List<Spec> =
            listOf(
                Spec(
                    CANDIDATES,
                    "Ask the engine for a map first: it plans exits every 4 bars across the allowed range against entries at bar 0 " +
                        "and every 4 bars of the incoming, measures each (harmony, voice over voice, phrase on phrase, voice " +
                        "continuity, tempo bend) and lists the cleanest $MAX_CANDIDATES. Plain mixes, no moves.",
                    obj(mapOf("overlap_bars" to mapOf("type" to "integer", "description" to "Overlap to plan each candidate with."))),
                ),
                Spec(
                    LOOK,
                    "See up to $MAX_LOOK_BARS bars of one track as a spectrogram image (the audio itself, not the detector's numbers): " +
                        "where the voice really is, where the bass and the kick drop out or come back, a fill, a break, a coro. " +
                        "Use it where the tables or the lyrics leave you unsure.",
                    obj(
                        mapOf(
                            "track" to mapOf("type" to "string", "enum" to listOf("outgoing", "incoming")),
                            "from_bar" to mapOf("type" to "integer"),
                            "to_bar" to mapOf("type" to "integer"),
                        ),
                    ),
                ),
                Spec(
                    LISTEN,
                    "Render this mix exactly as the engine would play it and see the result: what try_mix measures, the mix as a " +
                        "spectrogram with both faders on top, and the level of the mix bar by bar (holes and bumps). Listen to your " +
                        "best one or two candidates before committing; if the result shows two voices, a hole or a bump, fix it.",
                    obj(POINTS),
                ),
            )

        /** The queue tools, offered when the queue can be reordered. */
        val QUEUE_SPECS: List<Spec> =
            listOf(
                Spec(
                    QUEUE,
                    "See the upcoming tracks of the radio: how the playing track would mix into the queued next and into each " +
                        "of the next few tracks (tempo gap, the DJ's plan kind and quality, harmony, voice over voice). Use it " +
                        "when the queued next does not hook well (tempos far apart, a clash, nowhere clean to mix).",
                    obj(emptyMap()),
                ),
                Spec(
                    SWAP,
                    "Play another upcoming track next instead of the queued one, because it mixes better from the playing " +
                        "track. Ends this conversation; you are then asked for the new pair. Use it at most once, only when an " +
                        "option is clearly better (same style, closer tempo, a clean instrumental hand-over).",
                    obj(mapOf("track_id" to mapOf("type" to "string", "description" to "An id from queue_options."), "why" to mapOf("type" to "string"))),
                ),
            )

        /** Added to the system prompt when the queue tools are offered. */
        val REORDER_RULES: String =
            """

            You may also reorder the radio. If the queued next does not hook well from the playing track (tempos too far
            apart to beat-match, a harmonic clash, no clean instrumental to hand over on), call queue_options and, when
            another upcoming track clearly mixes better and fits the same style, swap_next to play it next instead. Do not
            swap for a small gain: the radio's order was chosen for its style. When the queued next is fine, mix it.
            """.trimIndent()

        /** Added to the system prompt in Pro mode, after [TOOL_RULES]. */
        val PRO_RULES: String =
            """

            PRO MODE. Take your time: up to $PRO_MAX_TURNS turns, and cost is not a concern. You also have candidates (a
            measured map of the whole allowed range), look (a spectrogram of the real audio of any stretch: the closest you
            can get to hearing it) and listen (the mix rendered by the engine, as a picture with the faders, plus its level
            bar by bar and a JUDGE line measured on the audio: whether the kicks land together or flam, bars where both
            basslines or both leads are loud, holes and bumps, and a score out of 10; aim for 8 or more). The moves also
            take the EQ knobs (eq: kill or open each deck's bass and highs from any bar of the overlap) and key_shift (one
            semitone on the incoming, only for a real clash). The owner compares your commit with your best other candidate
            by ear (A/B), so listen to at least two different ones. A good order: candidates; look at the outgoing's exit zone and the incoming's landing zone of the
            two or three best (the detector's voice column is unreliable on cumbia, the picture is the audio); try_mix with
            the moves you think fit; listen to the best one or two and fix what the picture shows (two voices at once, a
            hole or a bump in level, the incoming's bass arriving before the outgoing's leaves); then commit. When a song's
            structure is unclear and you can search the web, its lyrics with section labels ([Coro], [Verso]) or its real
            tempo and key may settle it.
            """.trimIndent()

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
            Nobody is watching this conversation: a message with no tool call ends it and no mix is committed. Do not end a
            turn with a summary that announces the next step, an offer to continue, or questions for the owner; put any note
            in the same message as your next tool call. The conversation ends only with commit_mix.
            """.trimIndent()

        private fun clock(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return "%d:%02d".format(java.util.Locale.ROOT, s / 60, s % 60)
        }
    }
}
