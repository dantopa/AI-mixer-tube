package org.simpmusic.dj.ml

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrontEndTest {
    @Test
    fun melMatchesTorchaudioReference() {
        val ref = Refs.floats("mel_ref.f32")
        val (frames, mels) = String(Refs.bytes("mel_ref.shape")).trim().split(" ").map { it.toInt() }
        assertEquals(128, mels)
        val front = MelFrontEnd()
        val sig = Refs.testSignal(44100)
        assertEquals(frames, front.frameCount(sig.size))
        val mel = front.compute(sig)
        assertEquals(ref.size, mel.size)
        var maxAbs = 0.0
        var at = 0
        for (i in ref.indices) {
            val d = abs((mel[i] - ref[i]).toDouble())
            if (d > maxAbs) { maxAbs = d; at = i }
        }
        // log1p(1000 x) values are O(1..10); float32 torch vs double Kotlin should agree to ~1e-4.
        assertTrue(maxAbs < 2e-3, "max abs diff to torchaudio log-mel = $maxAbs at frame ${at / 128} mel ${at % 128}: kt=${mel[at]} ref=${ref[at]}")
    }

    @Test
    fun fftMatchesNaiveDft() {
        val n = 64
        val re = DoubleArray(n) { sin(0.3 * it) + 0.2 * it % 3 }
        val im = DoubleArray(n)
        val expRe = DoubleArray(n)
        val expIm = DoubleArray(n)
        for (k in 0 until n) for (t in 0 until n) {
            val a = -2 * PI * k * t / n
            expRe[k] += re[t] * Math.cos(a)
            expIm[k] += re[t] * Math.sin(a)
        }
        Fft(n).transform(re, im)
        for (k in 0 until n) {
            assertEquals(expRe[k], re[k], 1e-9)
            assertEquals(expIm[k], im[k], 1e-9)
        }
    }

    @Test
    fun slaneyMelScaleRoundTrips() {
        for (hz in listOf(30.0, 500.0, 999.0, 1000.0, 4000.0, 11000.0)) {
            assertEquals(hz, MelFrontEnd.melToHz(MelFrontEnd.hzToMel(hz)), 1e-9)
        }
        assertEquals(15.0, MelFrontEnd.hzToMel(1000.0), 1e-9) // Slaney: 1 kHz = 15 mel
    }

    @Test
    fun resamplerKeepsInBandAndKillsAliasing() {
        val sr = 44100
        val n = sr * 2
        fun rms(x: FloatArray, from: Int, to: Int): Double = Math.sqrt((from until to).sumOf { x[it].toDouble() * x[it] } / (to - from))
        val tone = FloatArray(n) { sin(2 * PI * 1000.0 * it / sr).toFloat() }
        val down = Resampler.resample(tone, sr, 22050)
        assertEquals(n / 2, down.size)
        assertEquals(0.7071, rms(down, 2000, down.size - 2000), 0.01)
        val hi = FloatArray(n) { sin(2 * PI * 15000.0 * it / sr).toFloat() } // above the new Nyquist
        val dh = Resampler.resample(hi, sr, 22050)
        assertTrue(rms(dh, 2000, dh.size - 2000) < 0.01, "15 kHz must be filtered out")
        // non-integer ratio 48k -> 22.05k
        val t48 = FloatArray(48000) { sin(2 * PI * 440.0 * it / 48000).toFloat() }
        val d48 = Resampler.resample(t48, 48000, 22050)
        assertEquals(22050, d48.size)
        assertEquals(0.7071, rms(d48, 1500, d48.size - 1500), 0.01)
    }
}
