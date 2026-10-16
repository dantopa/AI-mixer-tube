package org.simpmusic.dj.timeline

import kotlinx.serialization.Serializable
import org.simpmusic.dj.model.TimeRange
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.model.VocalProfile
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One time-synced lyric line as a provider gives it (LRCLIB / SimpMusic LRC, BetterLyrics TTML, Spotify), on the timeline
 * the lyrics were timed to (usually the album audio).
 */
@Serializable
data class LyricLine(
    /** Where the line starts (ms). */
    val startMs: Long,
    /** Where the provider says it ends (TTML); null when it does not say (LRC). */
    val endMs: Long? = null,
    /** Start of each word (rich sync / word-level TTML), ascending; empty for line-level lyrics. */
    val wordStartsMs: List<Long> = emptyList(),
    /** The words, without timing markup ("♫" or blank = an instrumental marker, not a voice). */
    val text: String = "",
)

/**
 * Where a voice is in a track, read from its synced lyrics: the line timestamps say where someone sings and the long gaps
 * between them are the instrumentals. On the owner's cumbias the vocal detector hears the band as voice (65-98 % of a track
 * called voice), while the lyrics are precise and free; when present and [trusted] they are the DJ's truth for the vocals
 * ([TrackTimeline.vocals], [Provenance.LYRICS]). Ranges are on the TRACK's source timeline ([offsetMs] already applied).
 */
@Serializable
data class LyricVocals(
    /** Sung ranges, sorted, disjoint, inside the track. */
    val ranges: List<TimeRange>,
    /** Where the lyrics come from ("lrclib", "betterlyrics", "simpmusic", "cache"...). */
    val provider: String,
    /** The lyrics carry word timing (rich sync), so line ends are known rather than estimated. */
    val wordLevel: Boolean,
    /** Constant shift applied to the lyrics' times to fit this track's audio (ms; + = the voice comes later here). */
    val offsetMs: Long,
    /** How much these ranges are trusted (0..1); used when >= [MIN_CONFIDENCE]. */
    val confidence: Float,
    /** Sung lines the ranges were built from. */
    val lines: Int,
    /** How the offset / confidence were decided (for the log). */
    val note: String = "",
) {
    val trusted: Boolean get() = confidence >= MIN_CONFIDENCE && ranges.isNotEmpty()

    /** Share of [durationMs] the lyrics sing. */
    fun coverage(durationMs: Long): Float =
        if (durationMs <= 0) 0f else (ranges.sumOf { (minOf(it.endMs, durationMs) - maxOf(it.startMs, 0L)).coerceAtLeast(0L) }.toDouble() / durationMs).toFloat()

    /** One log fragment: provider, lines, word level, offset, confidence. */
    fun describe(): String =
        "lyrics $provider ${if (wordLevel) "word-level" else "line-level"}, $lines lines, offset ${if (offsetMs >= 0) "+" else ""}%.1f s, c%.2f%s".format(
            offsetMs / 1000.0, confidence, if (note.isNotEmpty()) " ($note)" else "",
        )

    /** Result of [build]: the vocals, or why the lyrics were rejected for this track. */
    class Built(val vocals: LyricVocals?, val rejected: String?)

    /** Result of [bestOffset]: the best constant offset and the correlation there and at 0. */
    data class OffsetFit(val offsetMs: Long, val best: Double, val atZero: Double)

    companion object {
        /** Below this the ranges are not used (the detector, or unknown, decides). */
        const val MIN_CONFIDENCE = 0.5f

        /** A line that runs longer than this to the next one is not sung all the way: its end is estimated. */
        const val MAX_LINE_MS = 8_000L

        /** A sung word lasts about this long after it starts (the last word of a line ends there). */
        const val WORD_TAIL_MS = 600L

        /** Estimated singing time per syllable of a line without word timing (sung, not spoken: ~3 syllables a second). */
        const val MS_PER_SYLLABLE = 300L

        /** Silence shorter than this between two lines is a breath, not an instrumental. */
        const val MERGE_GAP_MS = 1_500L

        /** The offset search: +- this much, in [OFFSET_STEP_MS] steps. */
        const val MAX_OFFSET_MS = 15_000L
        const val OFFSET_STEP_MS = 100L

        /** Agreement (correlation with the detector's evidence) above which the lyrics are confirmed by the audio. */
        const val GOOD_AGREEMENT = 0.25

        /** Under this the best offset is not distinguishable from noise: no offset is applied. */
        const val WEAK_AGREEMENT = 0.10

        /** The best offset must beat no offset by this much to be applied (a flat curve is not evidence). */
        const val MIN_GAIN = 0.03

        /** Lyrics whose last line is this far past the track's end are of another recording. */
        const val LATE_LAST_LINE_MS = MAX_OFFSET_MS

        /** A provider's declared duration this far from the track's is another recording. */
        const val DURATION_MISMATCH_MS = 20_000L

        /** Fewer sung lines than this are not lyrics to plan on (a title card, a single "instrumental" line). */
        const val MIN_LINES = 3

        private const val CONF_WORDS = 0.90f
        private const val CONF_LINES = 0.80f

        /**
         * Pure converter: sung ranges of [lines] on the lyrics' own timeline. A line sings from its start until the next
         * line's start; a line running longer than [MAX_LINE_MS] ends at its declared end (TTML), at its last word start +
         * [WORD_TAIL_MS] (word timing), or at start + a per-syllable estimate. Gaps under [MERGE_GAP_MS] are merged; the
         * instrumental intro, outro and breaks (and "♫" marker lines) stay empty. [durationMs] (> 0) caps the last line.
         */
        fun ranges(lines: List<LyricLine>, durationMs: Long = 0L): List<TimeRange> {
            val sorted = lines.filter { it.startMs >= 0 }.sortedBy { it.startMs }
            val raw = ArrayList<TimeRange>()
            for ((i, line) in sorted.withIndex()) {
                if (isInstrumental(line.text)) continue
                val start = line.startMs
                val next = sorted.getOrNull(i + 1)?.startMs?.takeIf { it > start }
                val span = next?.let { it - start }
                val words = line.wordStartsMs.filter { it >= start }
                val declared = line.endMs?.takeIf { it > start }
                var end =
                    when {
                        span != null && span <= MAX_LINE_MS -> next!!
                        declared != null -> declared
                        words.isNotEmpty() -> words.max() + WORD_TAIL_MS
                        else -> start + (syllables(line.text) * MS_PER_SYLLABLE).coerceIn(MERGE_GAP_MS, MAX_LINE_MS)
                    }
                if (next != null) end = minOf(end, next)
                if (durationMs > 0) end = minOf(end, durationMs)
                if (end > start) raw += TimeRange(start, end)
            }
            return merge(raw)
        }

        /** Sung lines of [lines] (instrumental markers excluded). */
        fun sungLines(lines: List<LyricLine>): Int = lines.count { !isInstrumental(it.text) }

        /**
         * The lyrics of a track turned into its vocals, validated against the track: rejected when they do not fit its
         * duration ([durationMs] of the audio, [declaredDurationMs] the provider's own, when it gives one); then the
         * constant offset that best agrees with the detector's per-frame evidence ([analysis]'s `vocalProfile`) is applied,
         * and the confidence lowered when that agreement is weak. Without a profile no offset is applied, at a lower
         * confidence. Never throws.
         */
        fun build(
            lines: List<LyricLine>,
            provider: String,
            durationMs: Long,
            analysis: TrackAnalysis? = null,
            declaredDurationMs: Long? = null,
        ): Built {
            val sung = lines.filter { !isInstrumental(it.text) && it.startMs >= 0 }
            if (sung.size < MIN_LINES) return Built(null, "only ${sung.size} sung lines")
            // unsynced text labelled as synced (every line at 0) is not timing
            if (sung.map { it.startMs }.distinct().size < MIN_LINES) return Built(null, "lines carry no timing")
            val dur = durationMs.takeIf { it > 0 } ?: analysis?.durationMs?.takeIf { it > 0 } ?: 0L
            val last = sung.maxOf { it.startMs }
            if (dur > 0 && last > dur + LATE_LAST_LINE_MS) {
                return Built(null, "last line at %.1f s, the track is %.1f s".format(last / 1000.0, dur / 1000.0))
            }
            if (dur > 0 && declaredDurationMs != null && declaredDurationMs > 0 && abs(declaredDurationMs - dur) > DURATION_MISMATCH_MS) {
                return Built(null, "lyrics timed to %.1f s, the track is %.1f s".format(declaredDurationMs / 1000.0, dur / 1000.0))
            }
            val wordLevel = sung.any { it.wordStartsMs.isNotEmpty() }
            val base = if (wordLevel) CONF_WORDS else CONF_LINES
            val own = ranges(lines) // on the lyrics' timeline; shifted and clipped below
            val profile = analysis?.vocalProfile?.takeIf { it.hopMs > 0 && it.score.size >= 20 }
            var offset = 0L
            var conf: Float
            val note: String
            if (profile == null) {
                conf = base - 0.20f
                note = "no detector evidence to align against"
            } else {
                val fit = bestOffset(own, profile)
                when {
                    fit.best >= GOOD_AGREEMENT -> {
                        if (fit.best - fit.atZero >= MIN_GAIN) offset = fit.offsetMs
                        conf = base
                    }
                    fit.best >= WEAK_AGREEMENT -> {
                        if (fit.best - fit.atZero >= MIN_GAIN) offset = fit.offsetMs
                        conf = base - 0.25f
                    }
                    fit.best > 0.0 -> conf = base - 0.30f
                    // the audio contradicts the lyrics everywhere: not this recording's timing, unless the detector is the one
                    // that cannot be believed (it hears the band as voice; an accordion break can score above the singer)
                    analysis != null && detectorUntrusted(analysis) -> conf = base - 0.30f
                    else -> conf = 0.30f
                }
                note = "agreement %.2f at the offset, %.2f at 0".format(fit.best, fit.atZero)
            }
            val shifted = clip(own.map { TimeRange(it.startMs + offset, it.endMs + offset) }, dur)
            if (shifted.isEmpty()) return Built(null, "no sung range inside the track")
            return Built(LyricVocals(shifted, provider, wordLevel, offset, conf.coerceIn(0f, 1f), sung.size, note), null)
        }

        /**
         * Searches -[MAX_OFFSET_MS]..+[MAX_OFFSET_MS] in [OFFSET_STEP_MS] steps for the shift that best agrees with the
         * detector: the Pearson correlation of the lyric-vocal mask (per profile frame) with the frame's score above its own
         * median (the band floor differs per track, so only what stands out of it counts). Ties keep the smaller shift.
         */
        fun bestOffset(ranges: List<TimeRange>, profile: VocalProfile): OffsetFit {
            val n = profile.score.size
            val hop = profile.hopMs.toLong()
            val med = profile.score.sorted()[n / 2]
            val e = DoubleArray(n) { maxOf(0.0, (profile.score[it] - med).toDouble()) }
            var bestOff = 0L
            var best = Double.NEGATIVE_INFINITY
            var zero = 0.0
            val steps = (MAX_OFFSET_MS / OFFSET_STEP_MS).toInt()
            val mask = DoubleArray(n)
            for (k in 0..2 * steps) {
                // search from 0 outwards so that a tie keeps the smallest shift
                val s = if (k == 0) 0 else if (k % 2 == 1) (k + 1) / 2 else -(k / 2)
                val off = s * OFFSET_STEP_MS
                for (i in 0 until n) mask[i] = if (inside(ranges, i * hop + hop / 2 - off)) 1.0 else 0.0
                val r = pearson(mask, e)
                if (k == 0) zero = r
                if (r > best + 1e-9) {
                    best = r
                    bestOff = off
                }
            }
            return OffsetFit(bestOff, if (best.isFinite()) best else 0.0, zero)
        }

        /** The analysis has a vocal detection that the timeline does not trust ([TrackTimeline.trustedVocals]). */
        private fun detectorUntrusted(a: TrackAnalysis): Boolean = a.vocals != null && TrackTimeline.trustedVocals(a) == null

        /** "♫", "♪", "...", "(instrumental)" or blank: a marker the provider puts in a gap, no one sings it. */
        fun isInstrumental(text: String): Boolean {
            val t = text.trim().lowercase()
            if (t.isEmpty()) return true
            if (t.none { it.isLetterOrDigit() }) return true
            val bare = t.trim('(', ')', '[', ']', '*', ' ')
            return bare == "instrumental" || bare == "music" || bare == "música" || bare == "musica"
        }

        /** Vowel groups, at least one per word: a rough syllable count for Spanish / English / Portuguese lines. */
        internal fun syllables(text: String): Int {
            var n = 0
            for (word in text.lowercase().split(Regex("\\s+"))) {
                if (word.none { it.isLetter() }) continue
                var groups = 0
                var inVowel = false
                for (c in word) {
                    val v = c in VOWELS
                    if (v && !inVowel) groups++
                    inVowel = v
                }
                n += groups.coerceAtLeast(1)
            }
            return n.coerceAtLeast(1)
        }

        private const val VOWELS = "aeiouyáéíóúüàèìòùâêîôûãõäëïöåæœ"

        private fun merge(r: List<TimeRange>): List<TimeRange> {
            val out = ArrayList<TimeRange>()
            for (x in r.sortedBy { it.startMs }) {
                val last = out.lastOrNull()
                if (last != null && x.startMs - last.endMs < MERGE_GAP_MS) {
                    out[out.size - 1] = TimeRange(last.startMs, maxOf(last.endMs, x.endMs))
                } else {
                    out += x
                }
            }
            return out
        }

        private fun clip(r: List<TimeRange>, dur: Long): List<TimeRange> =
            r.mapNotNull { x ->
                val s = x.startMs.coerceAtLeast(0L)
                val e = if (dur > 0) minOf(x.endMs, dur) else x.endMs
                if (e > s) TimeRange(s, e) else null
            }

        private fun inside(r: List<TimeRange>, t: Long): Boolean {
            var lo = 0
            var hi = r.size - 1
            while (lo <= hi) {
                val m = (lo + hi) ushr 1
                when {
                    t < r[m].startMs -> hi = m - 1
                    t >= r[m].endMs -> lo = m + 1
                    else -> return true
                }
            }
            return false
        }

        private fun pearson(a: DoubleArray, b: DoubleArray): Double {
            val n = a.size
            if (n == 0) return 0.0
            var ma = 0.0
            var mb = 0.0
            for (i in 0 until n) {
                ma += a[i]
                mb += b[i]
            }
            ma /= n
            mb /= n
            var sab = 0.0
            var saa = 0.0
            var sbb = 0.0
            for (i in 0 until n) {
                val da = a[i] - ma
                val db = b[i] - mb
                sab += da * db
                saa += da * da
                sbb += db * db
            }
            if (saa <= 0.0 || sbb <= 0.0) return 0.0
            return sab / sqrt(saa * sbb)
        }
    }
}
