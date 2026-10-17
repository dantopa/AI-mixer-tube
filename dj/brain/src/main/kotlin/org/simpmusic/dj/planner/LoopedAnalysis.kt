package org.simpmusic.dj.planner

import org.simpmusic.dj.model.Confident
import org.simpmusic.dj.model.SourceLoop
import org.simpmusic.dj.model.TimeRange
import org.simpmusic.dj.model.TrackAnalysis
import kotlin.math.min

/**
 * The outgoing track as it will SOUND with a DJ loop ([SourceLoop]): the song up to the loop's end, then the loop
 * again and again, on one "virtual" timeline. The planner plans the mix on this analysis exactly as on a real track, so
 * what it measures over the overlap (the beat grid it locks to, the bass, the voices, the chroma) is the loop's, and the
 * plan's outgoing clock runs in virtual time; the renderer's [org.simpmusic.dj.render.LoopSource] plays it.
 *
 * Every timed field is remapped: per-beat lists (beats, downbeats, downbeat logits) and per-bar ones (bar margins,
 * phrase positions) repeat their loop part; hop-sampled curves (energy, bands, structure frames) read the real time
 * [SourceLoop.realMs] gives; sections stop at the loop's start (an "outro" must not clamp a loop); voice ranges repeat.
 * The vocal profile is dropped (it is only used to validate lyrics).
 */
internal object LoopedAnalysis {
    class Looped(val analysis: TrackAnalysis, val vocals: List<TimeRange>?)

    /** [a] (refined) looped from [loop] on, [repeats] times; [vocals] = the track's voice ranges (null = unknown). */
    fun of(a: TrackAnalysis, loop: SourceLoop, vocals: List<TimeRange>?, repeats: Int): Looped {
        val ls = loop.startMs
        val le = loop.endMs
        val len = loop.lengthMs
        val end = le + repeats * len

        // ---- per beat ----
        val beats = a.beatTimesMs?.value.orEmpty()
        val src = ArrayList<Int>() // original beat index of each new beat
        val times = ArrayList<Int>()
        beats.indices.filter { beats[it] < le }.forEach { src += it; times += beats[it] }
        val inLoop = beats.indices.filter { beats[it] >= ls && beats[it] < le }
        for (k in 1..repeats) inLoop.forEach { src += it; times += (beats[it] + k * len).toInt() }
        val down = a.downbeatBeatIndices?.value.orEmpty()
        val downSet = down.toHashSet()
        val newDown = src.indices.filter { src[it] in downSet }
        val barOfBeat = down.withIndex().associate { (bar, beat) -> beat to bar }
        val srcBar = newDown.map { barOfBeat.getValue(src[it]) } // original bar of each new bar
        fun <T> perBar(l: List<T>?): List<T>? = l?.takeIf { it.isNotEmpty() }?.let { orig -> srcBar.mapNotNull { orig.getOrNull(it) } }

        // ---- per time ----
        fun times(l: List<Long>): List<Long> =
            l.filter { it < le } + (1..repeats).flatMap { k -> l.filter { it >= ls && it < le }.map { it + k * len } }
        fun curve(c: List<Float>, hop: Int): List<Float> {
            if (c.isEmpty() || hop <= 0) return c
            val n = ((end + hop - 1) / hop).toInt()
            return List(n) { i -> c.getOrElse((loop.realMs(i.toDouble() * hop) / hop).toInt().coerceIn(0, c.size - 1)) { 0f } }
        }
        fun frames(f: List<Float>, hop: Int, dim: Int): List<Float> {
            if (f.isEmpty() || hop <= 0) return f
            val count = f.size / dim
            val n = ((end + hop - 1) / hop).toInt()
            val outList = ArrayList<Float>(n * dim)
            for (i in 0 until n) {
                val j = (loop.realMs(i.toDouble() * hop) / hop).toInt().coerceIn(0, count - 1)
                for (d in 0 until dim) outList += f[j * dim + d]
            }
            return outList
        }
        fun ranges(r: List<TimeRange>): List<TimeRange> {
            val outList = ArrayList<TimeRange>()
            r.forEach { x -> if (x.startMs < le) outList += TimeRange(x.startMs, min(x.endMs, le)) }
            for (k in 1..repeats) {
                r.forEach { x ->
                    val s = maxOf(x.startMs, ls)
                    val e = minOf(x.endMs, le)
                    if (e > s) outList += TimeRange(s + k * len, e + k * len)
                }
            }
            return outList.sortedBy { it.startMs }
        }

        val hop = a.energyHopMs
        val looped = a.copy(
            durationMs = end,
            beatTimesMs = a.beatTimesMs?.let { Confident(times, it.confidence) },
            downbeatBeatIndices = a.downbeatBeatIndices?.let { Confident(newDown, it.confidence) },
            beatDownbeatLogits = a.beatDownbeatLogits?.let { l -> src.map { l.getOrElse(it) { 0f } } },
            phraseStartsMs = times(a.phraseStartsMs),
            energy = curve(a.energy, hop),
            lowBandEnergy = curve(a.lowBandEnergy, hop),
            highBandEnergy = curve(a.highBandEnergy, hop),
            structureFrames = frames(a.structureFrames, a.structureHopMs, TrackAnalysis.STRUCTURE_DIM),
            sections = a.sections?.let { s -> Confident(s.value.filter { it.range.startMs < ls }.map { if (it.range.endMs > ls) it.copy(range = TimeRange(it.range.startMs, ls)) else it }, s.confidence) },
            vocals = a.vocals?.let { Confident(ranges(it.value), it.confidence) },
            vocalProfile = null,
            barPhase = a.barPhase?.let { bp -> bp.copy(barMarginLogits = perBar(bp.barMarginLogits) ?: emptyList()) },
            phrases = a.phrases?.let { p ->
                p.copy(
                    blockStartsMs = times(p.blockStartsMs),
                    phraseStartsMs = times(p.phraseStartsMs),
                    barPositions = perBar(p.barPositions) ?: emptyList(),
                )
            },
        )
        return Looped(looped, vocals?.let { ranges(it) })
    }
}
