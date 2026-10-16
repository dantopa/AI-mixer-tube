package org.simpmusic.dj.timeline

import org.simpmusic.dj.analysis.AnalysisRefiner
import org.simpmusic.dj.analysis.BarPhase
import org.simpmusic.dj.analysis.PhraseGrid
import org.simpmusic.dj.model.TimeRange
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.model.Trust
import org.simpmusic.dj.planner.MixMarks

/** Where a position falls in the bars of a track (see [TrackTimeline.barPosition]). */
data class BarPosition(
    /** Index of the beat at or before the position (into [TrackTimeline.beatsMs]). */
    val beatIndex: Int,
    /** Index of the bar it is in (into [TrackTimeline.downbeatsMs]). */
    val barIndex: Int,
    /** 0 = the 1. */
    val beatInBar: Int,
    val beatsPerBar: Int,
    /** The bars are trusted. */
    val sure: Boolean,
    /** The owner set the 1. */
    val anchored: Boolean,
    /** Bar of the 16-bar block the beat is in (0..15); null without a phrase lattice. */
    val barInBlock: Int? = null,
    /** The 8-bar phrases / the 16-bar lines are found or marked (not just a guess). */
    val phraseSure: Boolean = false,
    val blockSure: Boolean = false,
    /** The owner marked where a phrase starts on this track. */
    val phraseMarked: Boolean = false,
)

/** Phrase lines of a track (8-bar phrases or 16-bar blocks) and whether they are trusted. */
data class PhraseLines(val startsMs: List<Long>, val trusted: Boolean)

/**
 * Everything the DJ believes about one track, in one place: the stored analysis ([raw]), the grid every consumer must use
 * ([refined] = `AnalysisRefiner.cached`, exactly what the planner reads), the owner's annotations, and each fact with its
 * [Provenance]. Before it every screen picked its own view (the "134 BPM" chip read the raw DSP field while the planner read
 * the repaired 80). Immutable; get one from [Timelines.of], which rebuilds it when an annotation changes.
 */
class TrackTimeline(
    val raw: TrackAnalysis,
    val refined: TrackAnalysis,
    /** The owner's tapped 1 (source ms) this timeline was built with; null when none. */
    val downbeatAnchorMs: Long?,
    /** The owner's phrase mark (source ms) this timeline was built with; null when none. */
    val phraseAnchorMs: Long?,
    /** The owner's mix marks (always [Provenance.OWNER]); null when none. */
    val mixMarks: MixMarks?,
) {
    val videoId: String get() = raw.videoId

    /** Tempo of the grid the planner uses: REFINED when the grid was repaired or the bars voted, DETECTED otherwise. */
    val bpm: Sourced<Float>? =
        refined.bpm?.let { b ->
            val refinedGrid = refined.bpm != raw.bpm || refined.beatTimesMs != raw.beatTimesMs || refined.barPhase != null
            Sourced(b.value, if (refinedGrid) Provenance.REFINED else Provenance.DETECTED, b.confidence)
        }

    val beatsMs: List<Int> = refined.beatTimesMs?.value ?: emptyList()

    /** Indices into [beatsMs] of the bar starts, as the refined analysis holds them. */
    val downbeatIndices: List<Int> = refined.downbeatBeatIndices?.value ?: emptyList()

    val downbeatsMs: List<Long> = downbeatIndices.filter { it in beatsMs.indices }.map { beatsMs[it].toLong() }

    /** Beats per bar; null when unknown (callers that need a number use 4, as the planner does). */
    val beatsPerBar: Int? = refined.beatsPerBar

    /** Whether the bars (which beat is the 1) are trusted: OWNER when the 1 is tapped, REFINED when voted. */
    val bars: Sourced<Boolean> =
        run {
            val info = refined.barPhase
            val conf = refined.downbeatBeatIndices?.confidence ?: 0f
            when {
                info == null -> Sourced(conf >= Trust.DOWNBEATS, Provenance.DETECTED, conf)
                info.source == "anchored" -> Sourced(info.trusted, Provenance.OWNER, conf)
                else -> Sourced(info.trusted, Provenance.REFINED, conf)
            }
        }

    /** Phrases COUNTED from the bars (4 bars at >= 95 bpm, else 2): what the ordinary planner scores on. */
    val countedPhraseStartsMs: List<Long> = refined.phraseStartsMs

    /** The found (or marked) 8-bar phrase lines; null without a phrase lattice. OWNER when the owner marked a phrase. */
    val phrases: Sourced<PhraseLines>? =
        refined.phrases?.let { p -> lines(p.phraseStartsMs, p.phrasesTrusted, p.source == "anchored") }

    /** The found (or marked) 16-bar block lines; null without a phrase lattice. */
    val blocks: Sourced<PhraseLines>? =
        refined.phrases?.let { p -> lines(p.blockStartsMs, p.blocksTrusted, p.source == "anchored") }

    /** Where a voice is, null = unknown (no detection, or one that is not trusted: see [trustedVocals]). */
    val vocals: Sourced<List<TimeRange>>? =
        trustedVocals(refined)?.let { Sourced(it, Provenance.DETECTED, refined.vocals?.confidence ?: 0f) }

    /** Share of the track the detector called voice; null without a detection. */
    val vocalCoverage: Float? = coverage(refined)

    /** Where [positionMs] falls in the bars; null before the first bar or without beats / bars. Same as the chip's counter. */
    fun barPosition(positionMs: Long): BarPosition? {
        val bi = lastAtOrBefore(beatsMs, positionMs)
        if (bi < 0 || downbeatIndices.isEmpty()) return null
        val k = barOf(bi)
        if (k < 0) return null
        val info = refined.barPhase
        val ph = refined.phrases
        return BarPosition(
            beatIndex = bi,
            barIndex = k,
            beatInBar = bi - downbeatIndices[k],
            beatsPerBar = beatsPerBar ?: 4,
            sure = info?.trusted == true,
            anchored = info?.source == "anchored",
            barInBlock = ph?.barPositions?.getOrNull(k),
            phraseSure = ph?.phrasesTrusted == true,
            blockSure = ph?.blocksTrusted == true,
            phraseMarked = ph?.source == "anchored",
        )
    }

    /** The bar line nearest [timeMs]; null without beats or bars (the caller keeps its own time then). */
    fun nearestBarLineMs(timeMs: Long): Long? = downbeatsMs.minByOrNull { kotlin.math.abs(it - timeMs) }

    /** Index of the beat nearest [timeMs] (0 before the first beat); null without beats. */
    fun nearestBeatIndex(timeMs: Long): Int? {
        if (beatsMs.isEmpty()) return null
        val i = lastAtOrBefore(beatsMs, timeMs)
        if (i < 0) return 0
        if (i + 1 < beatsMs.size && beatsMs[i + 1] - timeMs < timeMs - beatsMs[i]) return i + 1
        return i
    }

    /** "The 1 is one beat early": the beat after the 1 of the bar at [positionMs]; null when there is none. */
    fun shiftedOneMs(positionMs: Long): Long? {
        val bi = lastAtOrBefore(beatsMs, positionMs)
        if (bi < 0 || downbeatIndices.isEmpty()) return null
        val next = downbeatIndices[barOf(bi).coerceAtLeast(0)] + 1
        if (next >= beatsMs.size) return null
        return beatsMs[next].toLong()
    }

    /** One log line: tempo and its source, bars, phrases, vocals, marks. */
    fun describe(): String =
        buildString {
            append("timeline $videoId: ")
            append(bpm?.let { "%.1f bpm (%s, c%.2f)".format(it.value, it.provenance.label(), it.confidence) } ?: "bpm unknown")
            if (bpm?.provenance == Provenance.REFINED && raw.bpm != null) append(" raw %.1f".format(raw.bpm.value))
            append(" | bars ${if (bars.value) "trusted" else "unsure"} (${bars.provenance.label()}) ${beatsPerBar ?: "?"}/bar ${downbeatsMs.size} bars")
            append(" | phrases ")
            append(
                when {
                    phrases == null -> "counted"
                    blocks?.value?.trusted == true -> "16-bar lines sure (${blocks.provenance.label()})"
                    phrases.value.trusted -> "8-bar sure, 16 unsure (${phrases.provenance.label()})"
                    else -> "unsure (${phrases.provenance.label()})"
                },
            )
            append(" | vocals ")
            append(
                when {
                    vocals != null -> "known, ${vocals.value.size} ranges"
                    vocalCoverage != null -> "unknown (untrusted)"
                    else -> "unknown (not detected)"
                },
            )
            vocalCoverage?.let { append(", coverage %.0f%%".format(it * 100)) }
            append(" | marks ")
            append(mixMarks?.let { m -> "exit ${clock(m.exitMs)} entry ${clock(m.entryMs)} solo ${clock(m.soloMs)}" } ?: "none")
        }

    private fun barOf(beatIndex: Int): Int = downbeatIndices.binarySearch(beatIndex).let { if (it >= 0) it else -it - 2 }

    companion object {
        /** Detected voice over this share of a track means the detector cannot tell the voice from the band there. */
        const val UNTRUSTED_VOCAL_COVERAGE = 0.90

        private const val TRUSTED_LINES = 0.85f
        private const val UNSURE_LINES = 0.35f

        /**
         * The detected vocal ranges, sorted, or null (= unknown) when the analysis has no detection or the detector calls
         * [UNTRUSTED_VOCAL_COVERAGE] or more of the track voice: on the owner's cumbias YAMNet hears the band as voice
         * (detected coverage 65-98 %, median 89 %, across 33 tracks of the build-aq export), and acting on that cut nearly
         * every overlap to one bar.
         */
        fun trustedVocals(a: TrackAnalysis): List<TimeRange>? {
            val r = a.vocals?.value?.sortedBy { it.startMs } ?: return null
            val dur = a.durationMs.takeIf { it > 0 } ?: return r
            return r.takeIf { sung(r, dur) < UNTRUSTED_VOCAL_COVERAGE * dur }
        }

        private fun coverage(a: TrackAnalysis): Float? {
            val r = a.vocals?.value ?: return null
            val dur = a.durationMs.takeIf { it > 0 } ?: return null
            return (sung(r, dur).toDouble() / dur).toFloat()
        }

        private fun sung(r: List<TimeRange>, dur: Long): Long = r.sumOf { (minOf(it.endMs, dur) - maxOf(it.startMs, 0L)).coerceAtLeast(0L) }

        private fun lines(starts: List<Long>, trusted: Boolean, owner: Boolean): Sourced<PhraseLines> =
            Sourced(
                PhraseLines(starts, trusted),
                if (owner) Provenance.OWNER else Provenance.REFINED,
                if (trusted) TRUSTED_LINES else UNSURE_LINES,
            )

        internal fun lastAtOrBefore(beats: List<Int>, t: Long): Int {
            var lo = 0
            var hi = beats.size - 1
            var ans = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (beats[mid] <= t) {
                    ans = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            return ans
        }

        private fun Provenance.label(): String = name.lowercase()

        private fun clock(ms: Long?): String = ms?.let { "%d:%02d".format(it / 60_000, it / 1000 % 60) } ?: "-"
    }
}

/**
 * The cached [TrackTimeline] of a stored analysis. Keyed by the analysis AND the owner's annotations that change it (tapped
 * 1, phrase mark, mix marks, read through `BarPhase.anchors`, `PhraseGrid.anchors` and `MixMarks.of`), so a new mark gives
 * a new timeline. LRU of [CAPACITY].
 */
object Timelines {
    const val CAPACITY = 256

    private data class Key(val raw: TrackAnalysis, val downbeatAnchorMs: Long?, val phraseAnchorMs: Long?, val marks: MixMarks?)

    private val cache =
        object : LinkedHashMap<Key, TrackTimeline>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, TrackTimeline>?) = size > CAPACITY
        }

    fun of(raw: TrackAnalysis): TrackTimeline {
        val id = raw.videoId
        val key = Key(raw, BarPhase.anchors(id), PhraseGrid.anchors(id), MixMarks.of(id))
        synchronized(cache) { cache[key]?.let { return it } }
        val t = TrackTimeline(raw, AnalysisRefiner.cached(raw, key.downbeatAnchorMs, key.phraseAnchorMs), key.downbeatAnchorMs, key.phraseAnchorMs, key.marks)
        synchronized(cache) { cache[key] = t }
        return t
    }
}
