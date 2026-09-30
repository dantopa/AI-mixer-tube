package org.simpmusic.dj.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.simpmusic.dj.android.decode.ChannelMixer
import org.simpmusic.dj.android.decode.StreamingResampler
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class ResamplerTest {
    private fun sine(freq: Double, rate: Int, seconds: Double, channels: Int = 1): FloatArray {
        val n = (rate * seconds).toInt()
        return FloatArray(n * channels) { i -> sin(2 * PI * freq * (i / channels) / rate).toFloat() * 0.8f }
    }

    private fun resample(input: FloatArray, inRate: Int, outRate: Int, channels: Int, chunk: Int): FloatArray {
        val r = StreamingResampler(inRate, outRate, channels)
        val out = ArrayList<Float>()
        val collect: (FloatArray, Int) -> Unit = { b, n -> for (i in 0 until n * channels) out.add(b[i]) }
        var pos = 0
        val frames = input.size / channels
        while (pos < frames) {
            val n = minOf(chunk, frames - pos)
            r.process(input.copyOfRange(pos * channels, (pos + n) * channels), n, collect)
            pos += n
        }
        r.finish(collect)
        return out.toFloatArray()
    }

    private fun rms(a: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += a[i] * a[i]
        return sqrt(s / (to - from))
    }

    @Test
    fun sameRateIsAPassthrough() {
        val x = sine(440.0, 44_100, 0.2)
        assertArrayEquals(x, resample(x, 44_100, 44_100, 1, 1000), 0f)
    }

    @Test
    fun downsampleKeepsToneLevelAndLength() {
        val x = sine(1000.0, 48_000, 2.0)
        val y = resample(x, 48_000, 22_050, 1, 4096)
        assertEquals(2.0 * 22_050, y.size.toDouble(), 30.0)
        val expected = 0.8 / sqrt(2.0)
        assertEquals(expected, rms(y, 2000, y.size - 2000), 0.01)
        // and it is still a 1 kHz tone: zero crossings per second
        var crossings = 0
        for (i in 2001 until y.size - 2000) if (y[i - 1] < 0 && y[i] >= 0) crossings++
        val seconds = (y.size - 4000) / 22_050.0
        assertEquals(1000.0, crossings / seconds, 5.0)
    }

    @Test
    fun aliasingComponentsAboveTheNewNyquistAreRejected() {
        val x = sine(15_000.0, 48_000, 1.0) // above 11.025 kHz
        val y = resample(x, 48_000, 22_050, 1, 4096)
        assertTrue("alias residue ${rms(y, 2000, y.size - 2000)}", rms(y, 2000, y.size - 2000) < 0.8 / sqrt(2.0) * 0.02) // < -34 dB
    }

    @Test
    fun outputDoesNotDependOnHowTheInputIsChunked() {
        val x = sine(700.0, 44_100, 1.0) + FloatArray(0)
        val a = resample(x, 44_100, 48_000, 1, 44_100)
        val b = resample(x, 44_100, 48_000, 1, 333)
        assertEquals(a.size, b.size)
        for (i in a.indices) assertEquals("sample $i", a[i], b[i], 1e-5f)
    }

    @Test
    fun upsampleAlsoWorksAndChannelsStayIndependent() {
        val left = sine(500.0, 44_100, 0.5)
        val stereo = FloatArray(left.size * 2)
        for (i in left.indices) stereo[2 * i] = left[i] // right stays silent
        val y = resample(stereo, 44_100, 48_000, 2, 1024)
        var rightPeak = 0f
        for (i in 1 until y.size step 2) rightPeak = maxOf(rightPeak, abs(y[i]))
        assertEquals(0f, rightPeak, 1e-6f)
        val l = FloatArray(y.size / 2) { y[2 * it] }
        assertEquals(0.8 / sqrt(2.0), rms(l, 1000, l.size - 1000), 0.01)
    }

    @Test
    fun channelMixer() {
        val out = FloatArray(4)
        ChannelMixer.convert(floatArrayOf(1f, 0f, 0.5f, 0.5f), 2, 2, 1, out)
        assertArrayEquals(floatArrayOf(0.5f, 0.5f, 0f, 0f), out, 1e-6f)
        ChannelMixer.convert(floatArrayOf(0.25f, -0.5f), 2, 1, 2, out)
        assertArrayEquals(floatArrayOf(0.25f, 0.25f, -0.5f, -0.5f), out, 1e-6f)
        val six = floatArrayOf(0.1f, 0.2f, 9f, 9f, 9f, 9f)
        ChannelMixer.convert(six, 1, 6, 2, out)
        assertEquals(0.1f, out[0], 1e-6f)
        assertEquals(0.2f, out[1], 1e-6f)
    }
}
