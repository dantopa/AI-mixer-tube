package org.simpmusic.dj.render

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Streaming time-stretch / pitch-shift for one deck ("key lock" + tempo fader).
 *
 * Algorithm: phase vocoder with identity phase locking (Laroche & Dolson) around spectral peaks, phase reset of the
 * band above ~150 Hz on transients (so drum hits keep their attack), and stereo-linked phase (the phase decisions
 * are taken on the L+R sum and applied as one rotation to both channels, so the stereo image is preserved).
 * Pitch shifting is stretch + resample: the vocoder runs at tempo `rate / pitchRatio`, a cubic resampler then plays
 * its output `pitchRatio` times faster, so the net speed is `rate` and the pitch is `pitchRatio`.
 *
 * Why not WSOLA: WSOLA's waveform search moves audio by up to its tolerance relative to the ideal timeline
 * (a sawtooth of a few ms), which is exactly what breaks beat alignment between two decks. The vocoder places every
 * frame at its exact analysis position, so the source position is the integral of the rate lane to within one
 * sample; the price is a slightly softer sound at large stretches, which is irrelevant at the +-4..8% DJ range.
 *
 * Exactness: at `rate = 1` and `pitchRatio = 1` the vocoder is an identity (perfect reconstruction, Hann^2 overlap),
 * and rate changes only change the analysis hop, so the output is phase-continuous (no clicks) by construction.
 *
 * Streaming contract: sample `i` of the output (counted from [start]) plays source position `A(i)`, the integral of
 * the automation from [start]'s anchor. [rate] is queried per synthesis hop for the hop that is about to be built,
 * i.e. up to [latencyFrames] output frames ahead of the sample being delivered: a real-time consumer must have the
 * automation available that far ahead (it always does: lanes are known in advance) or accept that a lane change
 * takes effect [latencyFrames] late. No allocation happens after construction. Only `kotlin.math` is used.
 *
 * @param channels 1 or 2
 * @param fftSize power of two; 2048 for 44.1/48 kHz (46 ms window)
 * @param overlap synthesis overlap factor (4 = 75%)
 */
class DeckStretcher(
    val channels: Int,
    val sampleRate: Int,
    val fftSize: Int = 2048,
    val overlap: Int = 4,
) {
    /** Reads `count` frames starting at source frame [startFrame] into dst[c][0 until count]; zero outside the audio. */
    fun interface Source {
        fun read(startFrame: Long, count: Int, dst: Array<FloatArray>)
    }

    /** Lane values as a function of the OUTPUT frame index (counted from [start]). */
    interface Automation {
        /** Playback speed multiplier at output frame [outFrame] (pitch preserved). */
        fun rate(outFrame: Double): Double

        /** Pitch ratio (2^(semitones/12)) at output frame [outFrame]. */
        fun pitchRatio(outFrame: Double): Double
    }

    /** Output frames between a source position being analysed and it being audible. */
    val latencyFrames: Int get() = fftSize / 2

    private val n = fftSize
    private val half = n / 2
    private val hs = n / overlap
    private val fft = Fft(n)
    private val win = DoubleArray(n) { 0.5 - 0.5 * cos(2.0 * PI * it / n) }
    private val norm = 1.0 / (overlap * 3.0 / 8.0)
    private val ringSize = 4 * n
    private val ringMask = (ringSize - 1).toLong()
    private val ring = Array(channels) { FloatArray(ringSize) }
    private val srcBuf = Array(channels) { FloatArray(n) }
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)

    private val bins = half + 1
    private val aRe = DoubleArray(bins)
    private val aIm = DoubleArray(bins)
    private val bRe = DoubleArray(bins)
    private val bIm = DoubleArray(bins)
    private val mag = DoubleArray(bins)
    private val phi = DoubleArray(bins)
    private val prevPhi = DoubleArray(bins)
    private val psi = DoubleArray(bins)
    private val prop = DoubleArray(bins)
    private val omegaBin = DoubleArray(bins) { 2.0 * PI * it / n }
    private val peakOf = IntArray(bins)
    private val rotRe = DoubleArray(bins)
    private val rotIm = DoubleArray(bins)

    private val binHf = max(2, (600.0 * n / sampleRate).toInt())
    private val binReset = max(2, (150.0 * n / sampleRate).toInt())

    // ---- per-stream state ----
    private lateinit var source: Source
    private lateinit var automation: Automation
    private var anchor = 0.0
    private var rInit = 1.0
    private var frameIndex = 0L
    private var finalized = 0L
    private var lastA = 0.0
    private var prevStart = 0L
    private var havePrev = false
    private var prevHfE = 0.0
    private var resetCountdown = 0
    private var uPos = 0.0
    private var outIndex = 0L
    private var pitchNow = 1.0
    private var pitchRefreshAt = 0L
    private val aRing = DoubleArray(16)
    private var started = false
    private var identity = false

    /**
     * Start (or restart) the stream so that output frame 0 plays source frame [sourceFrame] (fractional allowed).
     * Frames before the first output sample are pre-rolled from the source so there is no fade-in artefact.
     */
    fun start(sourceFrame: Double, source: Source, automation: Automation) {
        this.source = source
        this.automation = automation
        anchor = sourceFrame
        java.util.Arrays.fill(prevPhi, 0.0)
        java.util.Arrays.fill(psi, 0.0)
        for (c in 0 until channels) java.util.Arrays.fill(ring[c], 0f)
        val p0 = clampPitch(automation.pitchRatio(0.0))
        pitchNow = p0
        pitchRefreshAt = PITCH_BLOCK.toLong()
        rInit = clampRate(automation.rate(0.0)) / p0
        frameIndex = (-(overlap / 2) + 1).toLong()
        finalized = frameIndex * hs - half
        havePrev = false
        prevHfE = 0.0
        resetCountdown = 0
        uPos = 0.0
        outIndex = 0
        lastA = anchor
        started = true
    }

    /** Source frame (fractional) that the NEXT output sample corresponds to. */
    fun sourceFrameOfNextOutput(): Double {
        check(started)
        while (floor(uPos).toLong() + 2 >= finalized) synthFrame()
        val j = Math.floorDiv(floor(uPos).toLong(), hs.toLong())
        val a0 = aOf(j)
        val a1 = aOf(j + 1)
        val f = (uPos - j * hs) / hs
        return a0 + (a1 - a0) * f
    }

    private fun aOf(frame: Long): Double = aRing[(frame and 15L).toInt()]

    /** Produce [count] output frames into out[c][offset until offset+count]. */
    fun render(out: Array<FloatArray>, offset: Int, count: Int) {
        check(started) { "call start() first" }
        for (i in 0 until count) {
            if (outIndex >= pitchRefreshAt) {
                pitchNow = clampPitch(automation.pitchRatio(outIndex.toDouble()))
                pitchRefreshAt = outIndex + PITCH_BLOCK
            }
            val idx0 = floor(uPos).toLong()
            while (idx0 + 2 >= finalized) synthFrame()
            val frac = uPos - idx0
            if (frac == 0.0) {
                val m = (idx0 and ringMask).toInt()
                for (c in 0 until channels) out[c][offset + i] = ring[c][m]
            } else {
                val m0 = ((idx0 - 1) and ringMask).toInt()
                val m1 = (idx0 and ringMask).toInt()
                val m2 = ((idx0 + 1) and ringMask).toInt()
                val m3 = ((idx0 + 2) and ringMask).toInt()
                val t = frac
                val t2 = t * t
                val t3 = t2 * t
                // Catmull-Rom
                val w0 = -0.5 * t3 + t2 - 0.5 * t
                val w1 = 1.5 * t3 - 2.5 * t2 + 1.0
                val w2 = -1.5 * t3 + 2.0 * t2 + 0.5 * t
                val w3 = 0.5 * t3 - 0.5 * t2
                for (c in 0 until channels) {
                    val r = ring[c]
                    out[c][offset + i] = (w0 * r[m0] + w1 * r[m1] + w2 * r[m2] + w3 * r[m3]).toFloat()
                }
            }
            uPos += pitchNow
            outIndex++
        }
    }

    // ---------------------------------------------------------------------------------------------

    private fun pvRateAtU(u: Double): Double {
        val i = outIndex + (u - uPos) / pitchNow
        val p = clampPitch(automation.pitchRatio(i))
        return clampRate(automation.rate(i)) / p
    }

    private fun synthFrame() {
        val k = frameIndex
        val a: Double
        if (k <= 0) {
            a = anchor + k.toDouble() * hs * rInit
            identity = abs(rInit - 1.0) < 1e-6 && pitchNow == 1.0
        } else {
            val u1 = k.toDouble() * hs
            val u0 = u1 - hs
            val r = (pvRateAtU(u0) + 4.0 * pvRateAtU((u0 + u1) * 0.5) + pvRateAtU(u1)) / 6.0
            a = lastA + hs * r
            identity = abs(r - 1.0) < 1e-6 && pitchNow == 1.0
        }
        lastA = a
        aRing[(k and 15L).toInt()] = a
        val sStart = floor(a + 0.5).toLong() - half
        val hopA = (sStart - prevStart).toInt()
        analyseAndSynthesise(sStart, if (havePrev) hopA else 0, k)
        prevStart = sStart
        havePrev = true
        frameIndex = k + 1
        finalized = (k + 1) * hs - half
    }

    private fun analyseAndSynthesise(sStart: Long, hopA: Int, k: Long) {
        source.read(sStart, n, srcBuf)
        val stereo = channels == 2
        if (stereo) {
            for (i in 0 until n) {
                val w = win[i]
                re[i] = w * srcBuf[0][i]
                im[i] = w * srcBuf[1][i]
            }
            fft.forward(re, im)
            for (b in 0 until bins) {
                val nb = (n - b) and (n - 1)
                val zr = re[b]; val zi = im[b]
                val cr = re[nb]; val ci = -im[nb] // conj(Z[N-b])
                aRe[b] = 0.5 * (zr + cr); aIm[b] = 0.5 * (zi + ci)
                // (Z - conj(Z[N-b])) / (2i)
                val dr = zr - cr; val di = zi - ci
                bRe[b] = 0.5 * di; bIm[b] = -0.5 * dr
            }
        } else {
            for (i in 0 until n) {
                re[i] = win[i] * srcBuf[0][i]
                im[i] = 0.0
            }
            fft.forward(re, im)
            for (b in 0 until bins) { aRe[b] = re[b]; aIm[b] = im[b] }
        }

        // reference spectrum = channel sum
        var hfE = 0.0
        for (b in 0 until bins) {
            val sr = if (stereo) aRe[b] + bRe[b] else aRe[b]
            val si = if (stereo) aIm[b] + bIm[b] else aIm[b]
            mag[b] = kotlin.math.sqrt(sr * sr + si * si)
            phi[b] = atan2(si, sr)
            if (b >= binHf) hfE += mag[b] * mag[b]
        }

        if (!havePrev || hopA <= 0) {
            for (b in 0 until bins) psi[b] = phi[b]
            resetCountdown = 0
        } else {
            if (hfE > HF_FLOOR && hfE > TRANSIENT_RATIO * (prevHfE + 1e-9)) resetCountdown = overlap
            val doReset = resetCountdown > 0
            if (resetCountdown > 0) resetCountdown--
            val hopA_d = hopA.toDouble()
            val hsD = hs.toDouble()
            for (b in 0 until bins) {
                var d = phi[b] - prevPhi[b] - omegaBin[b] * hopA_d
                d -= TWO_PI * floor(d / TWO_PI + 0.5)
                val omega = omegaBin[b] + d / hopA_d
                prop[b] = psi[b] + omega * hsD
                if (identity) {
                    // rate 1, pitch 1: pull the synthesis phase back onto the analysis phase, so the output converges
                    // to the source waveform (a hand-off to a live player at native rate is then seamless).
                    var e = phi[b] - prop[b]
                    e -= TWO_PI * floor(e / TWO_PI + 0.5)
                    prop[b] += RELOCK * e
                }
            }
            // peaks and regions of influence
            var lastPeak = -1
            var b = 0
            while (b < bins) {
                val m = mag[b]
                val isPeak = (b < 1 || m >= mag[b - 1]) && (b >= bins - 1 || m >= mag[b + 1]) &&
                    (b < 2 || m > mag[b - 2]) && (b >= bins - 2 || m > mag[b + 2])
                if (isPeak) {
                    if (lastPeak >= 0) {
                        val mid = (lastPeak + b) / 2
                        for (x in lastPeak + 1..mid) peakOf[x] = lastPeak
                        for (x in mid + 1 until b) peakOf[x] = b
                    } else {
                        for (x in 0 until b) peakOf[x] = b
                    }
                    peakOf[b] = b
                    lastPeak = b
                }
                b++
            }
            if (lastPeak < 0) {
                for (x in 0 until bins) peakOf[x] = x
            } else {
                for (x in lastPeak + 1 until bins) peakOf[x] = lastPeak
            }
            for (x in 0 until bins) {
                val p = peakOf[x]
                psi[x] = prop[p] + (phi[x] - phi[p])
            }
            if (doReset) for (x in binReset until bins) psi[x] = phi[x]
        }
        prevHfE = hfE
        for (b in 0 until bins) prevPhi[b] = phi[b]

        // rotate both channels by the same per-bin phase rotation exp(i (psi - phi))
        for (b in 0 until bins) {
            val d = psi[b] - phi[b]
            rotRe[b] = cos(d)
            rotIm[b] = sin(d)
        }
        val base = k * hs - half
        if (stereo) {
            for (b in 0 until bins) {
                val lr = aRe[b] * rotRe[b] - aIm[b] * rotIm[b]
                val li = aRe[b] * rotIm[b] + aIm[b] * rotRe[b]
                val rr = bRe[b] * rotRe[b] - bIm[b] * rotIm[b]
                val ri = bRe[b] * rotIm[b] + bIm[b] * rotRe[b]
                if (b == 0 || b == half) {
                    re[b] = lr; im[b] = rr
                } else {
                    // Z[b] = L[b] + i R[b];  Z[N-b] = conj(L[b]) + i conj(R[b])
                    re[b] = lr - ri; im[b] = li + rr
                    re[n - b] = lr + ri; im[n - b] = -li + rr
                }
            }
            fft.inverse(re, im)
            for (i in 0 until n) {
                val w = win[i] * norm
                val pos = ((base + i) and ringMask).toInt()
                val vl = (re[i] * w).toFloat()
                val vr = (im[i] * w).toFloat()
                if (i >= n - hs) { ring[0][pos] = vl; ring[1][pos] = vr } else { ring[0][pos] += vl; ring[1][pos] += vr }
            }
        } else {
            for (b in 0 until bins) {
                val lr = aRe[b] * rotRe[b] - aIm[b] * rotIm[b]
                val li = aRe[b] * rotIm[b] + aIm[b] * rotRe[b]
                if (b == 0 || b == half) {
                    re[b] = lr; im[b] = 0.0
                } else {
                    re[b] = lr; im[b] = li
                    re[n - b] = lr; im[n - b] = -li
                }
            }
            fft.inverse(re, im)
            for (i in 0 until n) {
                val pos = ((base + i) and ringMask).toInt()
                val v = (re[i] * win[i] * norm).toFloat()
                if (i >= n - hs) ring[0][pos] = v else ring[0][pos] += v
            }
        }
    }

    private fun clampRate(r: Double) = if (r.isNaN()) 1.0 else min(MAX_RATE, max(MIN_RATE, r))
    private fun clampPitch(p: Double) = if (p.isNaN()) 1.0 else min(2.0, max(0.5, p))

    companion object {
        const val MIN_RATE = 0.25
        const val MAX_RATE = 4.0
        private const val TWO_PI = 2.0 * PI
        private const val PITCH_BLOCK = 32
        private const val HF_FLOOR = 0.5
        private const val TRANSIENT_RATIO = 3.0
        private const val RELOCK = 0.3
    }
}
