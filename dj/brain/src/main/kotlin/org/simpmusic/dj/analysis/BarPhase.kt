package org.simpmusic.dj.analysis

import org.simpmusic.dj.model.BarPhaseInfo
import org.simpmusic.dj.model.Confident
import org.simpmusic.dj.model.TrackAnalysis
import kotlin.math.abs

/**
 * Which beat of the bar is the "1".
 *
 * Beat This! is excellent at WHERE the beats are and unreliable at which one is the 1 on half-bar-periodic music (cumbia,
 * dembow): its minimal peak picker keeps a downbeat only where the downbeat logit clears 0, and on those tracks the logit
 * hovers around 0, so the picked 1 jumps between beat 1 and beat 3 (measured: a stable bar phase on 4 of 51 cumbia tracks,
 * against 15 of 23 electronic ones). Kick, snare and hat energy cannot settle it either: they hit beats 1 and 3 alike.
 *
 * What does settle it is the network's own evidence, summed: the per-beat downbeat log-odds the tracker now stores
 * ([TrackAnalysis.beatDownbeatLogits]). A Viterbi pass over "position in the bar" picks the lattice that best explains
 * them, where every beat advances the position by one and a skipped or repeated beat (a real 2/4 bar, or a grid error)
 * costs [SLIP_PENALTY]. On the 13-track electronic corpus (fp32 logits) the decided 1s agree with the reference
 * downbeats on 83-100 % of the bars, 100 % on the two tracks whose picked downbeats were consistent only 51-53 % of the
 * time.
 *
 * How sure it is: for every bar, the logit at the chosen 1 minus the logit at each other beat of the bar, averaged over
 * [WINDOW_BARS] bars on each side; the worst of the other beats is the bar's margin. A half-bar ambiguity (1 and 3 alike)
 * shows as a margin near 0 against beat 3. The owner can also tap the 1 ([anchors]), which wins over the evidence.
 *
 * Runs on read, after [GridRepair], through [AnalysisRefiner]; analyses stored before the logits existed are returned as
 * they were (the tracker's own downbeats, no [BarPhaseInfo]).
 */
object BarPhase {
    /** Log-odds lost for each skipped or repeated beat in the lattice. */
    const val SLIP_PENALTY = 12.0

    /**
     * With the owner's tapped 1 the lattice never slips: the tap says the evidence is wrong about the phase, and a finite
     * penalty let confident (wrong) evidence pull the lattice back after a few bars (a 40 log-odds penalty slipped 4 times
     * on a synthetic track whose network evidence sat on beat 3). A real 5-beat bar after the tap is the owner's to re-tap.
     */
    const val ANCHORED_SLIP_PENALTY = Double.POSITIVE_INFINITY

    /** Logits are clipped to +-this: one wildly sure beat must not outvote a bar of evidence. */
    const val EVIDENCE_CLIP = 8.0

    /** Bars on each side averaged into a bar's margin. */
    const val WINDOW_BARS = 8

    /** Median margin (log-odds per bar) from which the bars are trusted for an ordinary beat-matched mix. */
    const val TRUST_MARGIN = 2.0f

    /** Local margin both mix points need for a "Perfect" mix (see [TrackAnalysis.barPhase]). */
    const val PERFECT_MARGIN = 4.0f

    /**
     * At most one slip per this many bars, or the lattice is not trusted. Real cumbia has them (a 5-beat bar where the band
     * stretches a break, a 2-beat pickup): the first device phasegrams showed clean column-1 stripes with 4-7 slips over
     * 32-71 bars, which a 1-per-16 rule refused.
     */
    const val BARS_PER_SLIP = 8

    /** A repaired beat takes the evidence of an original beat within this distance. */
    private const val MATCH_MS = 45

    /** Share of beats that must carry evidence. */
    private const val MIN_EVIDENCE_SHARE = 0.5

    private const val MIN_BEATS = 16

    /** Confidence given to trusted / untrusted voted downbeats (the planner's gate is `Trust.DOWNBEATS` = 0.45). */
    private const val TRUSTED_CONFIDENCE = 0.85f
    private const val UNTRUSTED_CONFIDENCE = 0.35f

    /** The owner's tapped 1 for a track (source ms), set by the app; null when none. */
    @Volatile var anchors: (videoId: String) -> Long? = { null }

    @Volatile var enabled: Boolean = true

    class Decision(
        /** Indices (into the beats) of the decided 1s. */
        val downbeats: IntArray,
        val slips: Int,
        /** Per decided bar: its margin (log-odds per bar). */
        val barMargins: FloatArray,
        val anchored: Boolean,
    ) {
        val margin: Float get() = median(barMargins)
    }

    /**
     * Decides the lattice from per-beat [evidence] (downbeat log-odds; 0 = no evidence) for [beatsPerBar] beats per bar,
     * optionally forcing beat [anchorIndex] to be a 1.
     */
    fun decide(evidence: FloatArray, beatsPerBar: Int, anchorIndex: Int? = null, slipPenalty: Double = if (anchorIndex != null) ANCHORED_SLIP_PENALTY else SLIP_PENALTY): Decision {
        val n = evidence.size
        val b = beatsPerBar
        val e = DoubleArray(n) { evidence[it].toDouble().coerceIn(-EVIDENCE_CLIP, EVIDENCE_CLIP) }
        val neg = Double.NEGATIVE_INFINITY
        var score = DoubleArray(b) { s -> if (s == 0) e[0] else 0.0 }
        if (anchorIndex == 0) for (s in 1 until b) score[s] = neg
        val back = Array(n) { IntArray(b) }
        for (i in 1 until n) {
            val next = DoubleArray(b) { neg }
            for (s in 0 until b) {
                var best = neg
                var arg = 0
                for (p in 0 until b) {
                    if (score[p] == neg) continue
                    val step =
                        when (s) {
                            (p + 1) % b -> 0.0
                            p, (p + 2) % b -> -slipPenalty
                            else -> continue
                        }
                    val c = score[p] + step
                    if (c > best) {
                        best = c
                        arg = p
                    }
                }
                next[s] = if (best == neg) neg else best + if (s == 0) e[i] else 0.0
                back[i][s] = arg
            }
            if (anchorIndex == i) for (s in 1 until b) next[s] = neg
            score = next
        }
        val pos = IntArray(n)
        pos[n - 1] = score.indices.maxByOrNull { score[it] }!!
        for (i in n - 1 downTo 1) pos[i - 1] = back[i][pos[i]]
        val downs = (0 until n).filter { pos[it] == 0 }.toIntArray()
        var slips = 0
        for (i in 1 until n) if (pos[i] != (pos[i - 1] + 1) % b) slips++
        return Decision(downs, slips, margins(e, downs, b, n), anchorIndex != null)
    }

    /** Per bar: min over the other beats of the bar of the windowed mean of (logit at the 1 - logit at that beat). */
    private fun margins(e: DoubleArray, downs: IntArray, b: Int, n: Int): FloatArray {
        val k = downs.size
        if (k == 0) return FloatArray(0)
        // diff[bar][t-1] = e[1] - e[1 + t], NaN when that beat is not in the bar
        val diff = Array(k) { bar ->
            val d = downs[bar]
            val end = if (bar + 1 < k) downs[bar + 1] else minOf(n, d + b)
            DoubleArray(b - 1) { t -> if (d + t + 1 < end) e[d] - e[d + t + 1] else Double.NaN }
        }
        return FloatArray(k) { bar ->
            var worst = Double.POSITIVE_INFINITY
            for (t in 0 until b - 1) {
                var sum = 0.0
                var cnt = 0
                for (j in maxOf(0, bar - WINDOW_BARS)..minOf(k - 1, bar + WINDOW_BARS)) {
                    val v = diff[j][t]
                    if (!v.isNaN()) {
                        sum += v
                        cnt++
                    }
                }
                if (cnt > 0) worst = minOf(worst, sum / cnt)
            }
            if (worst.isInfinite()) 0f else worst.toFloat()
        }
    }

    /**
     * [repaired] (the grid the planner uses) with its downbeats replaced by the voted lattice, using the evidence of [raw]
     * (the stored analysis, whose logits follow ITS beats). Returned unchanged when there is no evidence.
     */
    fun apply(repaired: TrackAnalysis, raw: TrackAnalysis): TrackAnalysis {
        if (!enabled) return repaired
        val beats = repaired.beatTimesMs?.value ?: return repaired
        val rawBeats = raw.beatTimesMs?.value
        val rawLogits = raw.beatDownbeatLogits
        if (rawBeats == null || rawLogits == null || rawLogits.size != rawBeats.size || beats.size < MIN_BEATS) {
            return repaired.copy(beatDownbeatLogits = null)
        }
        val evidence = FloatArray(beats.size)
        var matched = 0
        var j = 0
        for (i in beats.indices) {
            while (j + 1 < rawBeats.size && abs(rawBeats[j + 1] - beats[i]) <= abs(rawBeats[j] - beats[i])) j++
            if (abs(rawBeats[j] - beats[i]) <= MATCH_MS) {
                evidence[i] = rawLogits[j]
                matched++
            }
        }
        if (matched < beats.size * MIN_EVIDENCE_SHARE) return repaired.copy(beatDownbeatLogits = null)
        val bpb = repaired.beatsPerBar?.takeIf { it == 3 || it == 4 } ?: 4
        val anchorMs = anchors(repaired.videoId)
        val anchorIndex = anchorMs?.let { t -> nearestWithin(beats, t, medianInterval(beats) / 2) }
        val d = decide(evidence, bpb, anchorIndex)
        val bars = d.downbeats.size
        val margin = d.margin
        val tooManySlips = d.slips > maxOf(1, bars / BARS_PER_SLIP)
        val trusted = d.anchored || (margin >= TRUST_MARGIN && !tooManySlips)
        val old = repaired.downbeatBeatIndices?.value?.toHashSet() ?: emptySet<Int>()
        val changed = d.downbeats.count { it !in old }
        val info =
            BarPhaseInfo(
                source = if (d.anchored) "anchored" else "voted",
                margin = margin,
                // the track may host a Perfect mix; each mix point must still clear PERFECT_MARGIN locally (see the planner)
                trusted = trusted,
                slips = d.slips,
                changedBars = changed,
                barMarginLogits = d.barMargins.toList(),
            )
        val conf = if (trusted) TRUSTED_CONFIDENCE else UNTRUSTED_CONFIDENCE
        return repaired.copy(
            beatsPerBar = bpb,
            downbeatBeatIndices = Confident(d.downbeats.toList(), conf),
            phraseStartsMs = phraseStarts(beats, d.downbeats, bpb, repaired.bpm?.value),
            beatDownbeatLogits = evidence.toList(),
            barPhase = info,
        )
    }

    /** Same convention as the tracker's phrases (4 bars at >= 95 bpm, else 2), counted on the decided lattice. */
    private fun phraseStarts(beats: List<Int>, downs: IntArray, bpb: Int, bpm: Float?): List<Long> {
        val fast = (bpm ?: (60000f / medianInterval(beats).coerceAtLeast(1.0).toFloat())) >= 95f
        val bars = if (fast) 4 else 2
        return downs.filterIndexed { k, _ -> k % bars == 0 }.map { beats[it].toLong() }
    }

    /** The margin of the bar containing [timeMs] in an analysis returned by [apply]; null without a decision. */
    fun marginAt(a: TrackAnalysis, timeMs: Long): Float? {
        val info = a.barPhase ?: return null
        if (info.source == "anchored") return Float.POSITIVE_INFINITY
        val beats = a.beatTimesMs?.value ?: return null
        val downs = a.downbeatBeatIndices?.value ?: return null
        if (downs.isEmpty() || info.barMarginLogits.size != downs.size) return null
        var bar = 0
        for (k in downs.indices) if (beats[downs[k]] <= timeMs) bar = k else break
        return info.barMarginLogits[bar]
    }

    /** One line for the log. */
    fun describe(a: TrackAnalysis): String {
        val i = a.barPhase ?: return "bar-phase: no evidence (tracker downbeats kept)"
        return "bar-phase: ${i.source} margin %.1f %s slips=${i.slips} moved=${i.changedBars}/${a.downbeatBeatIndices?.value?.size ?: 0} bars".format(
            i.margin,
            when {
                i.trusted && (i.source == "anchored" || i.margin >= PERFECT_MARGIN) -> "PERFECT-ready"
                i.trusted -> "bars ok (Perfect only where the local margin >= $PERFECT_MARGIN)"
                else -> "bars unsure"
            },
        )
    }

    private fun nearestWithin(beats: List<Int>, t: Long, maxMs: Double): Int? {
        var best = -1
        var bd = Long.MAX_VALUE
        for (i in beats.indices) {
            val dd = abs(beats[i] - t)
            if (dd < bd) {
                bd = dd
                best = i
            }
        }
        return if (best >= 0 && bd <= maxMs) best else null
    }

    private fun medianInterval(beats: List<Int>): Double {
        if (beats.size < 2) return 500.0
        val iv = (1 until beats.size).map { beats[it] - beats[it - 1] }.sorted()
        return iv[iv.size / 2].toDouble()
    }

    private fun median(a: FloatArray): Float {
        if (a.isEmpty()) return 0f
        val s = a.sorted()
        return s[s.size / 2]
    }

    private fun abs(x: Int) = if (x < 0) -x else x
    private fun abs(x: Long) = if (x < 0) -x else x
}

/**
 * The grid every consumer reads: [GridRepair] (fix the beats), then [BarPhase] (decide the 1). Cached like the repair,
 * keyed by the stored analysis and the owner's tapped 1.
 */
object AnalysisRefiner {
    private val cache =
        object : LinkedHashMap<Pair<TrackAnalysis, Long?>, TrackAnalysis>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<TrackAnalysis, Long?>, TrackAnalysis>?) = size > 256
        }

    fun cached(raw: TrackAnalysis): TrackAnalysis {
        val key = raw to BarPhase.anchors(raw.videoId)
        synchronized(cache) { cache[key]?.let { return it } }
        val r = BarPhase.apply(GridRepair.cached(raw), raw)
        synchronized(cache) { cache[key] = r }
        return r
    }
}
