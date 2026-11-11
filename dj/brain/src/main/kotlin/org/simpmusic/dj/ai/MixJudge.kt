package org.simpmusic.dj.ai

import org.simpmusic.dj.model.TransitionPlan
import org.simpmusic.dj.render.AudioSegment
import org.simpmusic.dj.render.OfflineMixRenderer
import org.simpmusic.dj.render.StereoPcm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A judge with numbers for a rendered mix: renders the plan three times (the mix, each deck alone, with the same lanes the
 * engine plays) and measures, over the overlap, what the ear punishes:
 *  - **kicks**: whether the two decks' kicks land together (the lag of the best cross-correlation of their low-band onsets);
 *  - **bass clash**: bars where BOTH decks carry their bass (below ~150 Hz) near full level;
 *  - **lead clash**: bars where both decks are loud in the voice / lead range (300 Hz - 3 kHz) at once;
 *  - **level**: the deepest hole and the biggest bump of the mix, bar by bar, against the outgoing alone before T0.
 * One score 0..10 sums them up. Pure Kotlin, the same on the phone and in the tests.
 */
object MixJudge {
    class Verdict(
        val score: Double,
        /** Lag of the incoming's kicks against the outgoing's (ms, + = late), and how alike the two pulses are (0..1). */
        val kickLagMs: Double?,
        val kickCorr: Double?,
        val bassClashBars: List<Int>,
        val leadClashBars: List<Int>,
        val maxDipDb: Double,
        val maxBumpDb: Double,
        val text: String,
    )

    /**
     * @param barMs one bar of the mix (wall clock)
     * @param leadMs / tailMs how much is rendered before T0 and after the plan settles
     */
    fun judge(plan: TransitionPlan, out: AudioSegment, inc: AudioSegment, barMs: Double, leadMs: Long = 4_000L, tailMs: Long = 3_000L): Verdict {
        val o = OfflineMixRenderer.Options(leadMs = leadMs, tailMs = tailMs)
        val mix = OfflineMixRenderer.render(plan, out, inc, o)
        val soloOut = OfflineMixRenderer.render(plan, out, inc, o.copy(solo = OfflineMixRenderer.Solo.OUTGOING, masterLimiter = false))
        val soloIn = OfflineMixRenderer.render(plan, out, inc, o.copy(solo = OfflineMixRenderer.Solo.INCOMING, masterLimiter = false))
        return judge(mix.audio, soloOut.audio, soloIn.audio, mix.startMs, plan.overlapMs, barMs)
    }

    /** The same on already rendered audio: [mix], [a] (outgoing alone) and [b] (incoming alone), frame 0 at [startMs]. */
    fun judge(mix: StereoPcm, a: StereoPcm, b: StereoPcm, startMs: Double, overlapMs: Long, barMs: Double): Verdict {
        val sr = mix.sampleRate
        val n = min(mix.frames, min(a.frames, b.frames))
        fun frameOf(t: Double) = ((t - startMs) * sr / 1000.0).toInt().coerceIn(0, n)
        val ma = mono(a, n)
        val mb = mono(b, n)
        val mm = mono(mix, n)
        val lowA = lowpass(lowpass(ma, sr, BASS_HZ), sr, BASS_HZ)
        val lowB = lowpass(lowpass(mb, sr, BASS_HZ), sr, BASS_HZ)
        val midA = lowpass(highpass(ma, sr, LEAD_LOW_HZ), sr, LEAD_HIGH_HZ)
        val midB = lowpass(highpass(mb, sr, LEAD_LOW_HZ), sr, LEAD_HIGH_HZ)

        // ---- kicks: low-band onsets of each deck, cross-correlated over the overlap ----
        val hop = max(1, sr / 500) // 2 ms
        val f0 = frameOf(0.0)
        val f1 = frameOf(overlapMs.toDouble())
        val ea = onsets(lowA, f0, f1, hop)
        val eb = onsets(lowB, f0, f1, hop)
        var bestLag = 0
        var bestCorr = -1.0
        val maxLag = (MAX_LAG_MS / 2).toInt()
        val na = norm(ea)
        val nb = norm(eb)
        if (na > 0 && nb > 0) {
            for (lag in -maxLag..maxLag) {
                var s = 0.0
                for (i in ea.indices) {
                    val j = i + lag
                    if (j in eb.indices) s += ea[i] * eb[j]
                }
                val c = s / (na * nb)
                if (c > bestCorr) {
                    bestCorr = c
                    bestLag = lag
                }
            }
        }
        val kickLag = if (bestCorr >= MIN_PULSE_CORR) bestLag * hop * 1000.0 / sr else null
        val kickCorr = if (na > 0 && nb > 0) bestCorr.coerceAtLeast(0.0) else null

        // ---- per bar: bass and lead of each deck, level of the mix ----
        val firstBar = floor(startMs / barMs).toInt()
        val endMs = startMs + n * 1000.0 / sr
        val overlapBars = max(1, kotlin.math.ceil(overlapMs / barMs).toInt())
        class Bar(val i: Int, val bassA: Double, val bassB: Double, val leadA: Double, val leadB: Double, val mix: Double)
        val bars = ArrayList<Bar>()
        var k = firstBar
        while (k * barMs < endMs) {
            val s = frameOf(k * barMs)
            val e = frameOf((k + 1) * barMs)
            if (e - s > sr / 10) bars += Bar(k, rmsDb(lowA, s, e), rmsDb(lowB, s, e), rmsDb(midA, s, e), rmsDb(midB, s, e), rmsDb(mm, s, e))
            k++
        }
        val inOverlap = bars.filter { it.i in 0 until overlapBars }
        val bassMaxA = bars.maxOfOrNull { it.bassA } ?: -120.0
        val bassMaxB = bars.maxOfOrNull { it.bassB } ?: -120.0
        val leadMaxA = bars.maxOfOrNull { it.leadA } ?: -120.0
        val leadMaxB = bars.maxOfOrNull { it.leadB } ?: -120.0
        val bassClash = inOverlap.filter { it.bassA > bassMaxA - BASS_NEAR_DB && it.bassB > bassMaxB - BASS_NEAR_DB && it.bassA > FLOOR_DB && it.bassB > FLOOR_DB }.map { it.i }
        val leadClash = inOverlap.filter { it.leadA > leadMaxA - LEAD_NEAR_DB && it.leadB > leadMaxB - LEAD_NEAR_DB && it.leadA > FLOOR_DB && it.leadB > FLOOR_DB }.map { it.i }
        val before = bars.filter { it.i < 0 }.map { it.mix }.filter { it > FLOOR_DB }
        val ref = if (before.isNotEmpty()) before.sorted()[before.size / 2] else bars.map { it.mix }.filter { it > FLOOR_DB }.maxOrNull() ?: -20.0
        val judged = bars.filter { it.i >= 0 && it.i < overlapBars + 2 }
        val dip = judged.maxOfOrNull { ref - it.mix }?.coerceAtLeast(0.0) ?: 0.0
        val bump = judged.maxOfOrNull { it.mix - ref }?.coerceAtLeast(0.0) ?: 0.0

        var score = 10.0
        if (kickLag != null) score -= (max(0.0, abs(kickLag) - KICK_OK_MS) / 6.0).coerceAtMost(4.0)
        score -= 1.0 * bassClash.size.coerceAtMost(4)
        score -= 0.5 * leadClash.size.coerceAtMost(6)
        score -= 0.7 * max(0.0, dip - 2.0)
        score -= 0.7 * max(0.0, bump - 2.0)
        score = score.coerceIn(0.0, 10.0)

        val text =
            buildString {
                append("JUDGE (measured on the rendered audio, each deck alone and the mix): score %.1f/10. ".format(java.util.Locale.ROOT, score))
                append(
                    when {
                        kickLag == null -> "kicks: no common pulse in the overlap (one deck has no steady kick there), so nothing to flam. "
                        abs(kickLag) <= KICK_OK_MS -> "kicks: together (%+.0f ms, pulse match %.2f). ".format(java.util.Locale.ROOT, kickLag, kickCorr ?: 0.0)
                        else -> "kicks: the incoming's land %+.0f ms off the outgoing's (pulse match %.2f): a flam the ear hears. ".format(java.util.Locale.ROOT, kickLag, kickCorr ?: 0.0)
                    },
                )
                append(if (bassClash.isEmpty()) "bass: one at a time. " else "bass: BOTH basslines loud on overlap bars ${bassClash.joinToString(",")} (muddy; swap the bass earlier or kill one). ")
                append(if (leadClash.isEmpty()) "leads/voices: no clash. " else "leads/voices: both loud in 300 Hz-3 kHz on bars ${leadClash.joinToString(",")}. ")
                append("level: deepest hole %.1f dB, biggest bump %.1f dB against the outgoing before T0 (3+ dB is heard).".format(java.util.Locale.ROOT, dip, bump))
            }
        return Verdict(score, kickLag, kickCorr, bassClash, leadClash, dip, bump, text)
    }

    private fun mono(p: StereoPcm, n: Int) = FloatArray(n) { (p.left[it] + p.right[it]) * 0.5f }

    /** RBJ biquad, applied in place on a copy. */
    private fun biquad(x: FloatArray, b0: Double, b1: Double, b2: Double, a1: Double, a2: Double): FloatArray {
        val y = FloatArray(x.size)
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (i in x.indices) {
            val v = x[i].toDouble()
            val r = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = v; y2 = y1; y1 = r
            y[i] = r.toFloat()
        }
        return y
    }

    private fun lowpass(x: FloatArray, sr: Int, hz: Double): FloatArray {
        val w = 2 * PI * hz / sr
        val alpha = sin(w) / (2 * 0.7071)
        val c = cos(w)
        val a0 = 1 + alpha
        return biquad(x, (1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
    }

    private fun highpass(x: FloatArray, sr: Int, hz: Double): FloatArray {
        val w = 2 * PI * hz / sr
        val alpha = sin(w) / (2 * 0.7071)
        val c = cos(w)
        val a0 = 1 + alpha
        return biquad(x, (1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
    }

    /** Positive change of the RMS envelope per [hop] frames, from [from] to [to]: where the kicks hit. */
    private fun onsets(x: FloatArray, from: Int, to: Int, hop: Int): DoubleArray {
        val count = max(0, (to - from) / hop)
        val env = DoubleArray(count)
        for (h in 0 until count) {
            var s = 0.0
            val a = from + h * hop
            for (i in a until min(x.size, a + hop)) s += x[i] * x[i]
            env[h] = sqrt(s / hop)
        }
        val d = DoubleArray(count)
        for (h in 1 until count) d[h] = max(0.0, env[h] - env[h - 1])
        return d
    }

    private fun norm(x: DoubleArray): Double = sqrt(x.sumOf { it * it })

    private fun rmsDb(x: FloatArray, from: Int, to: Int): Double {
        if (to <= from) return -120.0
        var s = 0.0
        for (i in from until min(to, x.size)) s += x[i] * x[i]
        val r = sqrt(s / (to - from))
        return if (r <= 1e-6) -120.0 else 20 * log10(r)
    }

    private const val BASS_HZ = 150.0
    private const val LEAD_LOW_HZ = 300.0
    private const val LEAD_HIGH_HZ = 3_000.0
    private const val MAX_LAG_MS = 80.0
    private const val MIN_PULSE_CORR = 0.25
    /** Kicks within this many ms read as together (a flam starts around 15-20 ms). */
    const val KICK_OK_MS = 10.0
    private const val BASS_NEAR_DB = 6.0
    private const val LEAD_NEAR_DB = 4.0
    private const val FLOOR_DB = -45.0
}
