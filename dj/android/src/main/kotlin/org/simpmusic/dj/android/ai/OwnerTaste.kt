package org.simpmusic.dj.android.ai

import org.simpmusic.dj.ai.MixBrief
import org.simpmusic.dj.android.journal.MixJournal
import org.simpmusic.dj.android.journal.MixRecord
import org.simpmusic.dj.model.AiMixPoints
import org.simpmusic.dj.model.TrackAnalysis

/**
 * The owner's latest 👍 / 👎 from the mix journal (`mixes.jsonl`), as the examples Claude reads ([MixBrief.taste]): each
 * rating joined with the plan that was played (its features, its moves, Claude's why when it was an AI mix). Few-shot
 * memory, nothing trained. Reads files: call it off the main thread.
 */
object OwnerTaste {
    /**
     * The ratings Claude reads for a question about [fromId] -> [toId]: EVERY rating ever given to a mix of this same pair or
     * involving either track (never forgotten, so an error the owner marked is not repeated), then the latest others, up to
     * [max] in all.
     */
    fun recent(max: Int = 12, fromId: String? = null, toId: String? = null, analysisOf: ((String) -> TrackAnalysis?)? = null): List<MixBrief.RatedMix> {
        val ctx = contextOf(analysisOf)
        val all = rated()
        val ids = setOfNotNull(fromId, toId)
        fun aboutOf(s: MixRecord): String? =
            when {
                s.fromId == fromId && s.toId == toId -> "THIS SAME PAIR"
                s.fromId != null && s.fromId in ids -> "with the ${if (s.fromId == fromId) "outgoing" else "incoming"} track as outgoing"
                s.toId != null && s.toId in ids -> "with the ${if (s.toId == toId) "incoming" else "outgoing"} track as incoming"
                else -> null
            }
        val own = all.filter { (s, _) -> aboutOf(s) != null }.take(MAX_OWN).map { (s, liked) -> ctx(s, of(s, liked, aboutOf(s))) }
        val others = all.filter { (s, _) -> aboutOf(s) == null }.take((max - own.size).coerceAtLeast(MIN_GENERAL)).map { (s, liked) -> ctx(s, of(s, liked, null)) }
        return own + others
    }

    /** Every rated mix, newest first, with its started record (the owner's last word on each). */
    fun rated(): List<Pair<MixRecord, Boolean>> {
        val records = MixJournal.files()
            .flatMap { f -> runCatching { f.readLines() }.getOrDefault(emptyList()) }
            .mapNotNull { runCatching { MixJournal.decode(it) }.getOrNull() }
        val started = HashMap<String, MixRecord>()
        val rated = LinkedHashMap<String, MixRecord>()
        for (r in records) {
            when (r.type) {
                "started" -> started[r.mixId] = r
                // a mix rated twice keeps the owner's last word
                "rated" -> if (r.rating != null) { rated.remove(r.mixId); rated[r.mixId] = r }
            }
        }
        return rated.values.sortedByDescending { it.atEpochMs }
            .mapNotNull { r -> started[r.mixId]?.let { it to ((r.rating ?: 0) > 0) } }
    }

    /** Every rated mix as Claude reads it, for "learn from my ratings" ([OwnerLessons]); [titleOf] names the tracks. */
    fun allForLearning(titleOf: (String) -> String?, analysisOf: ((String) -> TrackAnalysis?)? = null): List<MixBrief.RatedMix> {
        val ctx = contextOf(analysisOf)
        return rated().map { (s, liked) ->
            ctx(s, of(s, liked, null)).copy(titles = listOfNotNull(s.fromId?.let { titleOf(it) ?: it }, s.toId?.let { titleOf(it) ?: it }).joinToString(" -> ").takeIf { it.isNotBlank() })
        }
    }

    /**
     * Adds what each track was doing at the mix's points ([MixBrief.pointContext]: sung or instrumental, voice soon after,
     * bass, phrase line), from the stored analyses; analyses are read once per track. Without [analysisOf], unchanged.
     */
    private fun contextOf(analysisOf: ((String) -> TrackAnalysis?)?): (MixRecord, MixBrief.RatedMix) -> MixBrief.RatedMix {
        if (analysisOf == null) return { _, m -> m }
        val memo = HashMap<String, org.simpmusic.dj.timeline.TrackTimeline?>()
        fun tl(id: String?) = id?.let { memo.getOrPut(it) { runCatching { analysisOf(it)?.let { a -> org.simpmusic.dj.timeline.Timelines.of(a) } }.getOrNull() } }
        return { s, m ->
            val overlap = s.overlapMs ?: 0L
            // a looped exit plays virtual time: its real span is not where the plan says, so no context is claimed
            val looped = s.reason?.contains("loop") == true && s.reason.contains("; moves: ")
            val outAt = if (looped) null else s.exitMs?.let { e -> tl(s.fromId)?.let { MixBrief.pointContext(it, e, e + overlap) } }
            val inAt = s.entryMs?.let { e -> tl(s.toId)?.let { MixBrief.pointContext(it, e, e + overlap) } }
            m.copy(outAt = outAt, inAt = inAt)
        }
    }

    private const val MAX_OWN = 10
    private const val MIN_GENERAL = 4

    private fun of(s: MixRecord, liked: Boolean, about: String?): MixBrief.RatedMix {
        val f = s.meta?.features
        val reason = s.reason.orEmpty()
        val ai = reason.startsWith(AiMixPoints.TAG)
        return MixBrief.RatedMix(
            liked = liked,
            source = s.meta?.source?.name,
            overlapS = s.overlapMs?.let { it / 1000.0 },
            exitFraction = f?.exitFraction,
            entryFraction = f?.entryFraction,
            phraseOnPhrase = f?.phraseOnPhrase,
            bassSwap = f?.bassSwap,
            voiceOverVoiceS = f?.voiceOverVoiceMs?.let { it / 1000.0 },
            harmonyExcess = f?.harmonyExcess,
            bpm = s.mixBpm,
            moves = reason.substringAfter("; moves: ", "").substringBefore(" | ").takeIf { it.isNotBlank() }?.take(160),
            why = if (ai) whyOf(reason) else null,
            about = about,
        )
    }

    /**
     * Claude's why inside an AI plan's reason, "AI MIX: exit .., entry .., N bars (WHY); beat-matched ...": the last
     * parenthesised group before "; beat-matched", found by matching parentheses backwards (the why may hold its own).
     */
    internal fun whyOf(reason: String): String? {
        val head = reason.substringAfter(AiMixPoints.TAG).substringBefore("; beat-matched", "")
        if (!head.endsWith(")")) return null
        var depth = 0
        for (i in head.indices.reversed()) {
            when (head[i]) {
                ')' -> depth++
                '(' -> if (--depth == 0) return head.substring(i + 1, head.length - 1).trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }
}
