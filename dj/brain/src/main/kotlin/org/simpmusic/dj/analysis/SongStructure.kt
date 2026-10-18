package org.simpmusic.dj.analysis

import org.simpmusic.dj.model.TimeRange
import org.simpmusic.dj.timeline.TrackTimeline

/**
 * The song's sections as a DJ names them: Intro, Verse n, Chorus n, Instrumental n, Outro (owner, 2026-10-18: "show the
 * detected structures on the mix picture: voice 1, instrumental 1, chorus, intro").
 *
 * Built from where the voice is (the timeline's vocals: trusted synced lyrics, else a trusted detection) and from
 * repetition ([SongMap.repeatScore], chroma + timbre of the stored structure frames):
 * - sung ranges closer than [JOIN_GAP_MS] are one sung section (a breath between lines is not a break);
 * - a gap of at least [MIN_INSTRUMENTAL_MS] is instrumental: the one before the first voice is the Intro, the one that
 *   runs to the end is the Outro, the others are Instrumental n;
 * - a sung section whose end repeats elsewhere in the song (score >= [SongMap.CHORUS]) is a Chorus, else a Verse.
 *
 * With no voice information the track gets no sections (unknown, never guessed). Not checked against labelled songs: the
 * chorus/verse call is only as good as the repetition score, which was never measured against labels.
 */
object SongStructure {
    enum class Kind { INTRO, VERSE, CHORUS, INSTRUMENTAL, OUTRO }

    data class Section(val startMs: Long, val endMs: Long, val kind: Kind, val number: Int, val repeat: Double? = null) {
        /** "Intro", "Verse 2", "Chorus 1", "Instrumental 3", "Outro". */
        val label: String
            get() = when (kind) {
                Kind.INTRO -> "Intro"
                Kind.OUTRO -> "Outro"
                Kind.VERSE -> "Verse $number"
                Kind.CHORUS -> "Chorus $number"
                Kind.INSTRUMENTAL -> "Instrumental $number"
            }
    }

    const val JOIN_GAP_MS = 4_000L
    const val MIN_INSTRUMENTAL_MS = 4_000L

    /** Keyed by the timeline's identity (its equals would compare whole analyses). */
    private class Key(val t: TrackTimeline) {
        override fun equals(other: Any?) = other is Key && other.t === t
        override fun hashCode() = System.identityHashCode(t)
    }

    private val cache = object : LinkedHashMap<Key, List<Section>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, List<Section>>?) = size > 32
    }

    fun of(t: TrackTimeline): List<Section> = synchronized(cache) { cache[Key(t)] } ?: compute(t).also { synchronized(cache) { cache[Key(t)] = it } }

    private fun compute(t: TrackTimeline): List<Section> {
        val v = t.vocals?.value ?: return emptyList()
        val a = t.refined
        val dur = a.durationMs
        if (dur <= 0) return emptyList()
        val sung = joined(SongMap.sung(v).filter { it.endMs > 0 && it.startMs < dur })
        val out = ArrayList<Section>()
        var verses = 0
        var choruses = 0
        var breaks = 0
        var prev = 0L
        for ((i, r) in sung.withIndex()) {
            val gap = r.startMs - prev
            if (gap >= MIN_INSTRUMENTAL_MS || (i == 0 && gap >= 1_000)) {
                out += if (i == 0) Section(0, r.startMs, Kind.INTRO, 1) else Section(prev, r.startMs, Kind.INSTRUMENTAL, ++breaks)
            } else if (out.isNotEmpty() && gap > 0) {
                // a short breath: the sung section before it simply runs up to this one
                out[out.size - 1] = out.last().copy(endMs = r.startMs)
            }
            val rep = SongMap.repeatScore(a, r.endMs, minOf(r.durationMs, 16_000L))
            // a voice from the very first second: the sung section starts the song
            val from = if (out.isEmpty()) 0L else r.startMs
            out += if (rep != null && rep >= SongMap.CHORUS) Section(from, minOf(r.endMs, dur), Kind.CHORUS, ++choruses, rep)
            else Section(from, minOf(r.endMs, dur), Kind.VERSE, ++verses, rep)
            prev = minOf(r.endMs, dur)
        }
        if (sung.isEmpty()) return listOf(Section(0, dur, Kind.INSTRUMENTAL, 1))
        if (dur - prev >= MIN_INSTRUMENTAL_MS) out += Section(prev, dur, Kind.OUTRO, 1)
        else if (dur > prev) out[out.size - 1] = out.last().copy(endMs = dur)
        return out
    }

    private fun joined(r: List<TimeRange>): List<TimeRange> {
        val out = ArrayList<TimeRange>()
        for (x in r.sortedBy { it.startMs }) {
            val last = out.lastOrNull()
            if (last != null && x.startMs - last.endMs < JOIN_GAP_MS) out[out.size - 1] = TimeRange(last.startMs, maxOf(last.endMs, x.endMs))
            else out += x
        }
        return out
    }

    /** The section playing at [ms], or null. */
    fun at(sections: List<Section>, ms: Long): Section? = sections.firstOrNull { ms >= it.startMs && ms < it.endMs }

    /** One line: "Intro 0:00-0:13 | Verse 1 0:13-0:41 | Chorus 1 0:41-1:05 (rep .71) | ...", or null when unknown. */
    fun describe(t: TrackTimeline): String? {
        val s = of(t).takeIf { it.isNotEmpty() } ?: return null
        fun c(ms: Long) = "%d:%02d".format(java.util.Locale.ROOT, ms / 60_000, ms / 1000 % 60)
        return s.joinToString(" | ") { x -> "${x.label} ${c(x.startMs)}-${c(x.endMs)}" + (x.repeat?.let { r -> " (rep %.2f)".format(java.util.Locale.ROOT, r) } ?: "") }
    }
}
