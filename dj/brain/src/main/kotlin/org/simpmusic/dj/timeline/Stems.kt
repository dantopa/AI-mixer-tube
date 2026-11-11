package org.simpmusic.dj.timeline

import kotlinx.serialization.Serializable
import org.simpmusic.dj.model.TimeRange

/**
 * What a source-separation model (Demucs, run on the owner's own server: `dj/stems-server`) measured of a track: per
 * [hopMs] frame, the level (dBFS RMS, rounded, -90 = silence) of each stem. Not the audio itself: a map of where the
 * voice, the bass, the drums and the rest are, which is what the planner and Claude need and what the detectors on the
 * phone get wrong on cumbia (YAMNet hears accordions as voice).
 */
@Serializable
data class StemProfile(
    val hopMs: Int,
    val vocals: List<Int>,
    val bass: List<Int>,
    val drums: List<Int>,
    val other: List<Int>,
    /** The model that made it ("htdemucs"), for the log. */
    val model: String = "",
) {
    val frames: Int get() = minOf(vocals.size, bass.size, drums.size, other.size)

    /** Level of [stem] at source time [tMs] (dB), -90 outside. */
    fun at(stem: List<Int>, tMs: Long): Int = stem.getOrNull((tMs / hopMs).toInt()) ?: -90

    /**
     * Where someone sings: frames whose vocal stem is within [VOICE_RANGE_DB] of its own loud level (the 95th percentile)
     * and above [VOICE_FLOOR_DB], merged across gaps under [MERGE_MS], ranges under [MIN_RANGE_MS] dropped. A short shout
     * at band level counts as voice here: the planner's structure rules already tolerate islands under 2 s.
     */
    fun vocalRanges(): List<TimeRange> {
        if (frames == 0) return emptyList()
        val sorted = vocals.take(frames).sorted()
        val loud = sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)]
        val bar = maxOf(VOICE_FLOOR_DB, loud - VOICE_RANGE_DB)
        val raw = ArrayList<TimeRange>()
        var start = -1
        for (i in 0 until frames) {
            val on = vocals[i] >= bar
            if (on && start < 0) start = i
            if (!on && start >= 0) {
                raw += TimeRange(start.toLong() * hopMs, i.toLong() * hopMs)
                start = -1
            }
        }
        if (start >= 0) raw += TimeRange(start.toLong() * hopMs, frames.toLong() * hopMs)
        val merged = ArrayList<TimeRange>()
        for (r in raw) {
            val last = merged.lastOrNull()
            if (last != null && r.startMs - last.endMs < MERGE_MS) merged[merged.size - 1] = TimeRange(last.startMs, r.endMs) else merged += r
        }
        return merged.filter { it.endMs - it.startMs >= MIN_RANGE_MS }
    }

    companion object {
        const val VOICE_RANGE_DB = 20
        const val VOICE_FLOOR_DB = -50
        const val MERGE_MS = 1_200L
        const val MIN_RANGE_MS = 800L
    }
}
