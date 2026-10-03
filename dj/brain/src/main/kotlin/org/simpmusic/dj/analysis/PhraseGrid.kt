package org.simpmusic.dj.analysis

import org.simpmusic.dj.model.PhraseInfo
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.model.Trust
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Where the phrases start: the "1" of the 16-bar block, the moment a DJ lines two tracks up on.
 *
 * Before this the phrases were COUNTED (every 4 bars at >= 95 bpm, else 2, from the first downbeat), so a 2-bar pickup, a
 * stretched break or an intro of odd length put every phrase line of the track in the wrong place. Here they are found
 * from what changes at a phrase line in the stored envelopes ([TrackAnalysis.energy], [TrackAnalysis.lowBandEnergy],
 * [TrackAnalysis.highBandEnergy] and [TrackAnalysis.structureFrames] when the analysis has them): the bass coming back
 * after a fill, the energy stepping up or down, a crash on the line, the bars after it sounding unlike the bars before.
 *
 * Per bar of the decided lattice (after [BarPhase]) a boundary score is built from the change of loudness and of the
 * low band across the bar line (spans of 1, 2, 4 and 8 bars) plus the low-band step right at the line. A Viterbi pass
 * over "bar position in a 16-bar block" then picks the lattice those scores support best: each bar advances one position,
 * the 16-block line weighs most, the 8-bar half next, the 4-bar quarters a little. A block may end early on a 4, 8 or 12
 * bar boundary (cheap: songs do that) or anywhere (dear: a pickup bar, an odd intro), so one irregular spot does not
 * shift the rest of the track.
 *
 * How sure it is: the best lattice against the same lattice shifted by d bars, every d, compared with what the same
 * track scores with its bars in random order (see [detect]). A shift of 8 that scores almost as well means the 16-bar
 * line and its half are interchangeable ([PhraseInfo.blockMargin] small): the 8-bar phrases may still be right, the 16
 * is not known. Any other shift scoring as well means the phrases themselves are not known ([PhraseInfo.phraseMargin]
 * small). The owner can mark where a phrase starts ([anchors]); it is forced into the lattice as a 16-bar line.
 *
 * Measured on nothing labelled: no phrase annotations exist for the corpus or the owner's exports (`PhraseGridReport`).
 * What the margins say there: synthetic tracks with 16-bar sections are found and trusted in 60/60 runs, noise-only tracks
 * are trusted in 1/60. On real music the 8-bar phrases are trusted on about 1 track in 10 (stored envelopes only: 18/178
 * of the owner's exports; 3/13 of the electronic corpus with the structure frames), the 16-bar line on about 1 in 40:
 * most tracks change only a few times, and a few changes fit too many lattices to prove one. Hence [anchors].
 */
object PhraseGrid {
    const val BLOCK_BARS = 16
    const val PHRASE_BARS = 8

    /** Evidence weight of each position in the block (0 = the 16-bar line). */
    private val WEIGHT = DoubleArray(BLOCK_BARS).also {
        it[0] = 1.0
        it[8] = 0.6
        it[4] = 0.25
        it[12] = 0.25
    }

    /** Cost of a block ending after 8 bars (two 8-bar phrases are also how a 16 is heard, so this is cheap). */
    private const val EARLY_8_COST = 2.0

    /** Cost of a block ending after 4 or 12 bars. */
    private const val EARLY_4_COST = 4.0

    /** Cost of a block ending anywhere else (a pickup bar, a stretched break, an intro of odd length). */
    private const val IRREGULAR_COST = 8.0

    /** Z-scored boundary evidence is clipped to this range: one huge change must not decide the whole track. */
    private const val CLIP_LOW = -2.0
    private const val CLIP_HIGH = 4.0

    /** Margins (null sds, see [Result.phraseMargin]) from which the phrases / the 16-bar line are trusted. */
    const val PHRASE_TRUST = 3.0f
    const val BLOCK_TRUST = 2.5f

    /** Both margins of a result clear the bars ([PHRASE_TRUST], [BLOCK_TRUST]). */
    fun blocksSure(r: Result) = r.anchored || (r.phraseMargin >= PHRASE_TRUST && r.blockMargin >= BLOCK_TRUST)

    private const val MIN_BARS = 12

    /** Shuffles of the evidence that make a track's null (see [detect]); fixed seed, so a track always gets the same answer. */
    private const val NULL_RUNS = 24
    private const val NULL_SEED = 16

    /** Bars on each side of a bar that its local margins are measured over (two blocks in all). */
    private const val LOCAL_BARS = 16

    /** The owner's marked phrase start for a track (source ms); null when none. */
    @Volatile var anchors: (videoId: String) -> Long? = { null }

    @Volatile var enabled: Boolean = true

    @Volatile var debug: ((String) -> Unit)? = null

    class Result(
        /** Per bar: position in its 16-bar block (0 = the block's first bar). */
        val position: IntArray,
        /** Per bar: the boundary evidence used (z-scored, clipped). */
        val evidence: DoubleArray,
        /** Whole-track margins over the shifts, in standard deviations of the same evidence with its bars shuffled. */
        val phraseMargin: Double,
        val blockMargin: Double,
        /** Per bar: margin (noise sds) of the lattice around it over every shift that changes its 8-bar phase. */
        val phraseLoss: DoubleArray,
        /** Per bar: margin (noise sds) of the lattice around it over the half-block shift (its 16 becomes an 8). */
        val blockLoss: DoubleArray,
        val irregular: Int,
        val anchored: Boolean,
    )

    /**
     * [a] (after [BarPhase.apply]) with its phrases found. Returned unchanged when it has no decided bars or the envelopes
     * are missing.
     */
    fun apply(a: TrackAnalysis): TrackAnalysis {
        if (!enabled) return a
        // on the voted bars, or on the tracker's own downbeats when they are trusted (analyses without logits)
        if (a.barPhase == null && (a.downbeatBeatIndices?.confidence ?: 0f) < Trust.DOWNBEATS) return a
        val barsSure = a.barPhase?.trusted ?: true
        val beats = a.beatTimesMs?.value ?: return a
        val downs = a.downbeatBeatIndices?.value?.filter { it in beats.indices } ?: return a
        val barStarts = downs.map { beats[it].toLong() }
        val r = detect(barStarts, Envelopes.of(a), anchors(a.videoId)) ?: return a
        val phraseStarts = barStarts.indices.filter { r.position[it] % PHRASE_BARS == 0 }.map { barStarts[it] }
        val blockStarts = barStarts.indices.filter { r.position[it] == 0 }.map { barStarts[it] }
        val info =
            PhraseInfo(
                source = if (r.anchored) "anchored" else "detected",
                phraseMargin = r.phraseMargin.toFloat(),
                blockMargin = r.blockMargin.toFloat(),
                phrasesTrusted = barsSure && (r.anchored || r.phraseMargin >= PHRASE_TRUST),
                blocksTrusted = barsSure && blocksSure(r),
                irregular = r.irregular,
                blockStartsMs = blockStarts,
                barPositions = r.position.toList(),
            )
        // An unsure lattice is shown (the counter's "?") but does not replace the counted phrases the planner scores on.
        return a.copy(phraseStartsMs = if (info.phrasesTrusted) phraseStarts else a.phraseStartsMs, phrases = info)
    }

    /** What [detect] reads from an analysis: the 100 ms level envelopes and the optional structure frames. */
    class Envelopes(
        val hopMs: Int,
        val energy: List<Float>,
        val low: List<Float>,
        val high: List<Float> = emptyList(),
        val structureHopMs: Int = 0,
        val structure: List<Float> = emptyList(),
    ) {
        companion object {
            fun of(a: TrackAnalysis) = Envelopes(a.energyHopMs, a.energy, a.lowBandEnergy, a.highBandEnergy, a.structureHopMs, a.structureFrames)
        }
    }

    /** Per bar: levels (log), line steps, and the bar's mean chroma / timbre (z-scored per dimension over the bars). */
    private class BarFeatures(val e: DoubleArray, val l: DoubleArray, val h: DoubleArray?, val lowStep: DoubleArray, val highStep: DoubleArray?, val chroma: Array<DoubleArray>?, val timbre: Array<DoubleArray>?) {
        val size get() = e.size

        fun permuted(order: IntArray) =
            BarFeatures(
                DoubleArray(size) { e[order[it]] },
                DoubleArray(size) { l[order[it]] },
                h?.let { x -> DoubleArray(size) { x[order[it]] } },
                DoubleArray(size) { lowStep[order[it]] },
                highStep?.let { x -> DoubleArray(size) { x[order[it]] } },
                chroma?.let { x -> Array(size) { x[order[it]] } },
                timbre?.let { x -> Array(size) { x[order[it]] } },
            )
    }

    /** Phrase lattice over bars starting at [barStarts] (ms). */
    fun detect(barStarts: List<Long>, env: Envelopes, anchorMs: Long? = null): Result? {
        val k = barStarts.size
        val hopMs = env.hopMs
        if (k < MIN_BARS || hopMs <= 0 || env.energy.size < 4 || env.low.size < 4) return null
        val barLen = DoubleArray(k) { i -> if (i + 1 < k) (barStarts[i + 1] - barStarts[i]).toDouble() else (barStarts[i] - barStarts[i - 1]).toDouble() }
        fun mean(x: List<Float>, t0: Double, t1: Double): Double {
            val h0 = max(0, (t0 / hopMs).toInt())
            val h1 = min(x.size, max(h0 + 1, (t1 / hopMs).toInt()))
            if (h0 >= x.size) return ln(1e-3)
            var s = 0.0
            for (h in h0 until h1) s += ln(max(x[h].toDouble(), 1e-3))
            return s / (h1 - h0)
        }
        fun level(x: List<Float>) = DoubleArray(k) { mean(x, barStarts[it].toDouble(), barStarts[it] + barLen[it]) }
        // the band in the last quarter of the bar before the line, against the first quarter after it
        fun lineStep(x: List<Float>) = DoubleArray(k) { i ->
            if (i == 0) 0.0 else {
                val q = barLen[i - 1] / 4
                mean(x, barStarts[i].toDouble(), barStarts[i] + q) - mean(x, barStarts[i] - q, barStarts[i].toDouble())
            }
        }
        val hasHigh = env.high.size >= 4
        val dim = TrackAnalysis.STRUCTURE_DIM
        val frames = if (env.structureHopMs > 0) env.structure.size / dim else 0
        fun barVectors(from: Int, to: Int): Array<DoubleArray>? {
            if (frames < 4) return null
            val v = Array(k) { i ->
                val f0 = max(0, (barStarts[i] / env.structureHopMs).toInt())
                val f1 = min(frames, max(f0 + 1, ((barStarts[i] + barLen[i]) / env.structureHopMs).toInt()))
                DoubleArray(to - from) { d -> if (f0 >= frames) 0.0 else (f0 until f1).sumOf { env.structure[it * dim + from + d].toDouble() } / (f1 - f0) }
            }
            for (d in 0 until to - from) {
                val col = DoubleArray(k) { v[it][d] }
                val m = col.average()
                val sdv = sd(col)
                for (i in 0 until k) v[i][d] = (v[i][d] - m) / sdv
            }
            return v
        }
        val feats = BarFeatures(
            e = level(env.energy),
            l = level(env.low),
            h = if (hasHigh) level(env.high) else null,
            lowStep = lineStep(env.low),
            highStep = if (hasHigh) lineStep(env.high) else null,
            chroma = barVectors(0, 12),
            timbre = barVectors(12, dim),
        )
        val ev = evidence(feats)
        val anchorBar = anchorMs?.let { t -> barStarts.indices.minByOrNull { abs(barStarts[it] - t) }?.takeIf { abs(barStarts[it] - t) <= barLen[it] / 2 } }
        val lat = lattice(ev, anchorBar)
        val pos = lat.pos
        var irregular = 0
        for (i in 1 until k) if (pos[i] != (pos[i - 1] + 1) % BLOCK_BARS && !(pos[i] == 0 && (pos[i - 1] + 1) % 4 == 0)) irregular++
        // Per bar, over the bars around it: how much more evidence the lattice collects than the same lattice shifted by d
        // bars (no new resets), in noise standard deviations, the worst over the shifts that change the 8-bar phase
        // (phrase) and over the half-block shift (block). Max-marginals were tried first: forcing one bar elsewhere costs
        // about two resets whatever the evidence, so pure noise scored like a clear track.
        val phraseLoss = DoubleArray(k)
        val blockLoss = DoubleArray(k)
        for (i in 0 until k) {
            val j0 = max(0, i - LOCAL_BARS)
            val j1 = min(k, i + LOCAL_BARS + 1)
            var worst = Double.POSITIVE_INFINITY
            for (d in 1 until BLOCK_BARS) {
                val m = shiftMargin(ev, pos, d, j0, j1)
                if (d % PHRASE_BARS != 0) worst = min(worst, m)
            }
            phraseLoss[i] = worst
            blockLoss[i] = shiftMargin(ev, pos, PHRASE_BARS, j0, j1)
        }
        // The lattice is the best of many, so it always beats its own shifts by something, even on noise. What the track
        // scores is compared with the same track with its bars played in random order (structure gone, the levels and the
        // whole evidence pipeline kept: shuffling the evidence itself instead lost the neighbour correlations the
        // sharpening puts in, and pure noise then beat its null by 4 sds): margins in standard deviations of that null.
        val (gp, gb) = globalMargins(ev, pos)
        val rnd = kotlin.random.Random(NULL_SEED)
        val nullP = DoubleArray(NULL_RUNS)
        val nullB = DoubleArray(NULL_RUNS)
        for (r in 0 until NULL_RUNS) {
            val order = IntArray(k) { it }.also { a -> for (i in a.size - 1 downTo 1) { val j = rnd.nextInt(i + 1); val t = a[i]; a[i] = a[j]; a[j] = t } }
            val shuffled = evidence(feats.permuted(order))
            val (np, nb) = globalMargins(shuffled, lattice(shuffled, null).pos)
            nullP[r] = np
            nullB[r] = nb
        }
        val phraseMargin = (gp - nullP.average()) / sd(nullP)
        val blockMargin = (gb - nullB.average()) / sd(nullB)
        debug?.invoke("phrase global %.1f null %.1f+-%.1f -> %.1f | block %.1f null %.1f+-%.1f -> %.1f".format(gp, nullP.average(), sd(nullP), phraseMargin, gb, nullB.average(), sd(nullB), blockMargin))
        return Result(pos, ev, phraseMargin, blockMargin, phraseLoss, blockLoss, irregular, anchorBar != null)
    }

    private fun globalMargins(ev: DoubleArray, pos: IntArray): Pair<Double, Double> {
        var p = Double.POSITIVE_INFINITY
        for (d in 1 until BLOCK_BARS) if (d % PHRASE_BARS != 0) p = min(p, shiftMargin(ev, pos, d, 0, ev.size))
        return p to shiftMargin(ev, pos, PHRASE_BARS, 0, ev.size)
    }

    /** Per bar: how much the music changes at its line (z-scored, clipped). */
    private fun evidence(f: BarFeatures): DoubleArray {
        val k = f.size
        val e = f.e
        val l = f.l
        val se = sd(e)
        val sl = sd(l)
        val sh = f.h?.let { sd(it) } ?: 1.0
        val ss = sd(f.lowStep)
        val shs = f.highStep?.let { sd(it) } ?: 1.0
        fun dist(v: Array<DoubleArray>, a0: Int, a1: Int, b0: Int, b1: Int): Double {
            var d2 = 0.0
            for (d in v[0].indices) {
                var ma = 0.0
                var mb = 0.0
                for (q in a0 until a1) ma += v[q][d]
                for (q in b0 until b1) mb += v[q][d]
                val x = ma / (a1 - a0) - mb / (b1 - b0)
                d2 += x * x
            }
            return sqrt(d2 / v[0].size)
        }
        val raw = DoubleArray(k) { i ->
            var s = 0.0
            var w = 0.0
            for ((span, ws) in SPANS) {
                if (i - span < 0 || i + span > k) continue
                var c = abs(avg(e, i, i + span) - avg(e, i - span, i)) / se + abs(avg(l, i, i + span) - avg(l, i - span, i)) / sl
                f.h?.let { h -> c += W_HIGH * abs(avg(h, i, i + span) - avg(h, i - span, i)) / sh }
                f.chroma?.let { c += W_CHROMA * dist(it, i, i + span, i - span, i) }
                f.timbre?.let { c += W_TIMBRE * dist(it, i, i + span, i - span, i) }
                s += ws * c
                w += ws
            }
            // bars near either end have fewer spans; scale to the full set so they are not penalised for it
            var b = if (w > 0) s * SPAN_WEIGHT / w else 0.0
            // The line is where the bass COMES BACK, not where it leaves: a fill (the bass out for the last beat or bar
            // before the line) changes the level on both of its edges, and only the direction tells which edge is the line.
            val st = f.lowStep[i]
            b += if (st > 0) STEP_IN * st / ss else STEP_OUT * -st / ss
            // a crash or an opening hat pattern on the line
            f.highStep?.let { hs -> if (hs[i] > 0) b += W_HIGH_STEP * hs[i] / shs }
            if (i >= 2) {
                val dip = min(l[i - 2], l[i]) - l[i - 1]
                if (dip > 0) b += DIP * dip / sl
            }
            if (i >= 1 && i + 1 < k) {
                val dip = min(l[i - 1], l[i + 1]) - l[i]
                if (dip > 0) b -= DIP * dip / sl
            }
            b
        }
        // A change smears over the bars next to the line (the spans overlap it); keep the bar that stands out from its
        // neighbours, or a line one bar early scores almost as well as the real one.
        val sharp = DoubleArray(k) { i -> raw[i] - SHARPEN * max(if (i > 0) raw[i - 1] else 0.0, if (i + 1 < k) raw[i + 1] else 0.0) }
        return zscore(sharp).map { it.coerceIn(CLIP_LOW, CLIP_HIGH) }.toDoubleArray()
    }

    /** Evidence lattice [pos] collects over bars [from, to) beyond the same lattice shifted by [d], in noise sds. */
    private fun shiftMargin(ev: DoubleArray, pos: IntArray, d: Int, from: Int, to: Int): Double {
        var diff = 0.0
        var v = 0.0
        for (i in from until to) {
            val w = WEIGHT[pos[i]] - WEIGHT[(pos[i] + d) % BLOCK_BARS]
            diff += w * ev[i]
            v += w * w
        }
        return if (v <= 0) 0.0 else diff / sqrt(v)
    }

    /** Cost of going from position [p] to [q] on the next bar; null when not allowed. */
    private fun cost(p: Int, q: Int): Double? =
        when {
            q == (p + 1) % BLOCK_BARS -> 0.0
            q != 0 -> null
            p == 7 -> EARLY_8_COST
            p == 3 || p == 11 -> EARLY_4_COST
            else -> IRREGULAR_COST
        }

    private class Lattice(val pos: IntArray, val best: Double, val maxMarginal: Array<DoubleArray>)

    /**
     * Best lattice, and per bar and position the score of the best lattice that puts that bar at that position
     * (max-marginals, forward + backward): how much is lost by forcing a bar elsewhere, every other bar re-optimised.
     */
    private fun lattice(ev: DoubleArray, anchorBar: Int?): Lattice {
        val n = ev.size
        val b = BLOCK_BARS
        val neg = Double.NEGATIVE_INFINITY
        fun allowed(i: Int, q: Int) = anchorBar != i || q == 0
        val fwd = Array(n) { DoubleArray(b) { neg } }
        val back = Array(n) { IntArray(b) }
        for (q in 0 until b) if (allowed(0, q)) fwd[0][q] = WEIGHT[q] * ev[0]
        for (i in 1 until n) for (q in 0 until b) {
            if (!allowed(i, q)) continue
            var best = neg
            var arg = 0
            for (p in 0 until b) {
                if (fwd[i - 1][p] == neg) continue
                val c = fwd[i - 1][p] - (cost(p, q) ?: continue)
                if (c > best) { best = c; arg = p }
            }
            if (best != neg) fwd[i][q] = best + WEIGHT[q] * ev[i]
            back[i][q] = arg
        }
        val bwd = Array(n) { DoubleArray(b) { neg } }
        for (q in 0 until b) if (allowed(n - 1, q)) bwd[n - 1][q] = 0.0
        for (i in n - 2 downTo 0) for (p in 0 until b) {
            if (!allowed(i, p)) continue
            var best = neg
            for (q in 0 until b) {
                if (bwd[i + 1][q] == neg) continue
                val c = bwd[i + 1][q] - (cost(p, q) ?: continue) + WEIGHT[q] * ev[i + 1]
                if (c > best) best = c
            }
            bwd[i][p] = best
        }
        val pos = IntArray(n)
        pos[n - 1] = (0 until b).maxByOrNull { fwd[n - 1][it] }!!
        for (i in n - 1 downTo 1) pos[i - 1] = back[i][pos[i]]
        val mm = Array(n) { i -> DoubleArray(b) { q -> if (fwd[i][q] == neg || bwd[i][q] == neg) neg else fwd[i][q] + bwd[i][q] } }
        return Lattice(pos, fwd[n - 1][pos[n - 1]], mm)
    }

    /** One line for the log. */
    fun describe(a: TrackAnalysis): String {
        val p = a.phrases ?: return "phrases: counted (no decided bars)"
        return "phrases: ${p.source} margins phrase %.1f block %.1f %s irregular=${p.irregular} blocks@%s".format(
            p.phraseMargin,
            p.blockMargin,
            when {
                p.blocksTrusted -> "16-bar lines sure"
                p.phrasesTrusted -> "8-bar phrases sure, 16 unsure"
                else -> "phrases unsure"
            },
            p.blockStartsMs.take(6).joinToString(",") { "%.1fs".format(it / 1000.0) },
        )
    }

    /** Spans (bars) compared on each side of a bar line, with their weights. */
    private val SPANS = listOf(1 to 0.5, 2 to 1.0, 4 to 1.0, 8 to 0.7)
    private val SPAN_WEIGHT = SPANS.sumOf { it.second }

    /** Low-band step at the line: the bass coming in weighs much more than the bass leaving (see the fill note). */
    private const val STEP_IN = 1.0
    private const val STEP_OUT = 0.2

    /** A bar whose bass dips below both neighbours (a fill): evidence for a line right after it, against one on it. */
    private const val DIP = 1.0

    /** Share of the stronger neighbour's evidence taken off each bar (see the smear note in [detect]). */
    private const val SHARPEN = 0.5

    /** Weights of the high band, its step at the line, and the chroma / timbre change, beside loudness and bass (1 each). */
    private const val W_HIGH = 0.7
    private const val W_HIGH_STEP = 0.5
    private const val W_CHROMA = 0.8
    private const val W_TIMBRE = 0.8

    private fun median(x: DoubleArray): Double = if (x.isEmpty()) 0.0 else x.sorted()[x.size / 2]

    private fun avg(x: DoubleArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += x[i]
        return s / (to - from)
    }

    private fun sd(x: DoubleArray): Double {
        val m = x.average()
        var s = 0.0
        for (v in x) s += (v - m) * (v - m)
        return sqrt(s / x.size).coerceAtLeast(1e-3)
    }

    private fun zscore(x: DoubleArray): DoubleArray {
        val m = x.average()
        val s = sd(x)
        return DoubleArray(x.size) { (x[it] - m) / s }
    }
}
