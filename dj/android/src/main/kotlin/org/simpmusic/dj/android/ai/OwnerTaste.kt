package org.simpmusic.dj.android.ai

import org.simpmusic.dj.ai.MixBrief
import org.simpmusic.dj.android.journal.MixJournal
import org.simpmusic.dj.android.journal.MixRecord
import org.simpmusic.dj.model.AiMixPoints

/**
 * The owner's latest 👍 / 👎 from the mix journal (`mixes.jsonl`), as the examples Claude reads ([MixBrief.taste]): each
 * rating joined with the plan that was played (its features, its moves, Claude's why when it was an AI mix). Few-shot
 * memory, nothing trained. Reads files: call it off the main thread.
 */
object OwnerTaste {
    fun recent(max: Int = 12): List<MixBrief.RatedMix> {
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
            .mapNotNull { r -> started[r.mixId]?.let { of(it, (r.rating ?: 0) > 0) } }
            .take(max)
    }

    private fun of(s: MixRecord, liked: Boolean): MixBrief.RatedMix {
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
