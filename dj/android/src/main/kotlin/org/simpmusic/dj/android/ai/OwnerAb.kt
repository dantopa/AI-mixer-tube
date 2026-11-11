package org.simpmusic.dj.android.ai

import org.simpmusic.dj.android.journal.MixJournal

/**
 * The owner's A/B choices (the mix sheet: Claude's commit against its best other candidate, heard one after the other),
 * as lines for Claude's brief. A choice between two mixes of the SAME pair says more than a thumb: same songs, only the
 * mix differs. Those of the pair being asked come first.
 */
object OwnerAb {
    const val MAX_LINES = 10

    fun block(fromId: String, toId: String): String? {
        val all = runCatching { MixJournal.records() }.getOrDefault(emptyList()).filter { it.type == "ab" && it.reason != null }
        if (all.isEmpty()) return null
        val (same, others) = all.asReversed().partition { (it.fromId == fromId && it.toId == toId) }
        val lines = (same.map { "[THIS SAME PAIR] " + text(it.reason!!) } + others.map { text(it.reason!!) }).take(MAX_LINES)
        return "THE OWNER'S A/B CHOICES (two of your candidates for the same pair, heard one after the other; the kept one sounded better to them):\n" +
            lines.joinToString("\n") { "- $it" }
    }

    private fun text(r: String): String = r.replace("kept: ", "KEPT ").replace(" || over: ", "  OVER  ")
}
