package org.simpmusic.dj.ai

import org.simpmusic.dj.analysis.RealFft
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The closest Claude can get to hearing (the API takes text and images, not audio): a spectrogram of a stretch of audio as
 * a PNG. Time runs left to right, frequency bottom (40 Hz) to top (12 kHz) on a log scale, brightness is loudness (dB
 * against the picture's own peak). Thin vertical lines are bars (brighter every 4, white every 8), green and red full-
 * height lines mark where a mix starts and ends, dashed horizontal lines sit at 250 Hz (bass below) and 2 kHz (voice and
 * lead instruments live between them, cymbals above). Strips across the top carry per-time facts (a voice, a fader).
 * Pure Kotlin (FFT, colour map, PNG encoder), so it runs the same on the phone and in the tests.
 */
object Spectrogram {
    /** A vertical line at [tMs] (same clock as `startMs`). */
    class Mark(val tMs: Double, val kind: Kind)

    enum class Kind { BAR, BAR4, PHRASE, MIX_START, MIX_END }

    /** A strip [STRIP_H] px high across the top: [level] (0..1) at each time, drawn in [rgb]. */
    class Strip(val rgb: Int, val level: (Double) -> Float)

    const val LOW_HZ = 40.0
    const val HIGH_HZ = 12_000.0
    const val BASS_HZ = 250.0
    const val MID_HZ = 2_000.0
    private const val STRIP_H = 8
    private const val FFT = 4096
    private const val RANGE_DB = 70.0

    /**
     * [left]/[right] at [sampleRate], sample 0 at [startMs]. At most [maxWidth] columns (one column per hop) and [rows]
     * frequency rows.
     */
    fun png(
        left: FloatArray,
        right: FloatArray,
        sampleRate: Int,
        startMs: Double,
        marks: List<Mark> = emptyList(),
        strips: List<Strip> = emptyList(),
        maxWidth: Int = 1000,
        rows: Int = 220,
    ): ByteArray {
        val n = min(left.size, right.size)
        require(n > 0) { "no audio" }
        val hop = max(256, (n + maxWidth - 1) / maxWidth)
        val cols = max(1, n / hop)
        val fft = RealFft(FFT)
        val win = FloatArray(FFT) { (0.5 - 0.5 * cos(2 * PI * it / (FFT - 1))).toFloat() }
        val frame = FloatArray(FFT)
        val re = FloatArray(FFT / 2 + 1)
        val im = FloatArray(FFT / 2 + 1)
        // row -> FFT bin range on a log frequency axis
        val binHz = sampleRate.toDouble() / FFT
        val rowLo = IntArray(rows)
        val rowHi = IntArray(rows)
        for (r in 0 until rows) {
            val f0 = LOW_HZ * exp(ln(HIGH_HZ / LOW_HZ) * r / rows)
            val f1 = LOW_HZ * exp(ln(HIGH_HZ / LOW_HZ) * (r + 1) / rows)
            rowLo[r] = (f0 / binHz).toInt().coerceIn(1, FFT / 2)
            rowHi[r] = max(rowLo[r], (f1 / binHz).toInt().coerceIn(1, FFT / 2))
        }
        val db = Array(cols) { FloatArray(rows) }
        var peak = -200.0
        for (c in 0 until cols) {
            val center = c * hop + hop / 2
            val s0 = center - FFT / 2
            for (i in 0 until FFT) {
                val k = s0 + i
                frame[i] = if (k in 0 until n) (left[k] + right[k]) * 0.5f * win[i] else 0f
            }
            fft.forward(frame, re, im)
            for (r in 0 until rows) {
                var m = 0.0
                for (b in rowLo[r]..rowHi[r]) m = max(m, (re[b] * re[b] + im[b] * im[b]).toDouble())
                val v = 10 * log10(m + 1e-12)
                db[c][r] = v.toFloat()
                if (v > peak) peak = v
            }
        }
        val topH = strips.size * STRIP_H
        val h = rows + topH
        val px = IntArray(cols * h)
        for (c in 0 until cols) {
            for (r in 0 until rows) {
                val x = ((db[c][r] - (peak - RANGE_DB)) / RANGE_DB).toFloat().coerceIn(0f, 1f)
                px[(h - 1 - r) * cols + c] = colour(x)
            }
        }
        val msPerCol = hop * 1000.0 / sampleRate
        fun colOf(t: Double): Int = ((t - startMs) / msPerCol).roundToInt()
        // frequency guides (dashed)
        for (hz in listOf(BASS_HZ, MID_HZ)) {
            val r = (ln(hz / LOW_HZ) / ln(HIGH_HZ / LOW_HZ) * rows).roundToInt().coerceIn(0, rows - 1)
            val y = h - 1 - r
            for (c in 0 until cols) if ((c / 6) % 2 == 0) px[y * cols + c] = blend(px[y * cols + c], 0xFFFFFF, 0.55f)
        }
        // time marks
        for (m in marks) {
            val c = colOf(m.tMs)
            if (c !in 0 until cols) continue
            val (rgb, a, from) =
                when (m.kind) {
                    Kind.BAR -> Triple(0xFFFFFF, 0.25f, topH)
                    Kind.BAR4 -> Triple(0xFFFFFF, 0.5f, topH)
                    Kind.PHRASE -> Triple(0xFFFFFF, 0.9f, topH)
                    Kind.MIX_START -> Triple(0x30FF60, 1f, 0)
                    Kind.MIX_END -> Triple(0xFF3030, 1f, 0)
                }
            for (y in from until h) px[y * cols + c] = blend(px[y * cols + c], rgb, a)
        }
        // strips on top
        strips.forEachIndexed { i, s ->
            for (c in 0 until cols) {
                val v = s.level(startMs + (c + 0.5) * msPerCol).coerceIn(0f, 1f)
                val rgb = blend(0x101010, s.rgb, v)
                for (y in i * STRIP_H until (i + 1) * STRIP_H - 1) px[y * cols + c] = rgb
            }
        }
        return Png.rgb(cols, h, px)
    }

    /** Dark blue -> purple -> orange -> pale yellow, readable at a glance. */
    private fun colour(x: Float): Int {
        val stops = intArrayOf(0x05050F, 0x2A0A5E, 0x8A1F6E, 0xE0503A, 0xFBB43C, 0xFCFDBF)
        val p = x * (stops.size - 1)
        val i = p.toInt().coerceAtMost(stops.size - 2)
        return blend(stops[i], stops[i + 1], p - i)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - u) + ((b shr s) and 0xFF) * u).roundToInt().coerceIn(0, 255)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** RMS level in dBFS of [left]/[right] from frame [from] (inclusive) to [to] (exclusive); -120 for silence. */
    fun rmsDb(left: FloatArray, right: FloatArray, from: Int, to: Int): Double {
        val a = from.coerceIn(0, left.size)
        val b = to.coerceIn(a, left.size)
        if (b <= a) return -120.0
        var s = 0.0
        for (i in a until b) s += (left[i] * left[i] + right[i] * right[i]) / 2.0
        val r = sqrt(s / (b - a))
        return if (r <= 1e-6) -120.0 else 20 * log10(r)
    }
}

/** A minimal PNG writer (8-bit RGB, no interlace), enough for pictures sent to Claude. */
object Png {
    fun rgb(width: Int, height: Int, pixels: IntArray): ByteArray {
        require(pixels.size == width * height)
        val raw = ByteArray(height * (width * 3 + 1))
        var o = 0
        for (y in 0 until height) {
            raw[o++] = 0 // filter: none
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                raw[o++] = (p shr 16).toByte()
                raw[o++] = (p shr 8).toByte()
                raw[o++] = p.toByte()
            }
        }
        val deflater = Deflater(6)
        deflater.setInput(raw)
        deflater.finish()
        val z = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (!deflater.finished()) z.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArrayOutputStream().apply {
            int(width); int(height)
            write(8); write(2); write(0); write(0); write(0) // depth 8, colour type RGB
        }.toByteArray()
        chunk(out, "IHDR", ihdr)
        chunk(out, "IDAT", z.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.int(v: Int) {
        write(v ushr 24); write(v ushr 16); write(v ushr 8); write(v)
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        out.int(data.size)
        val t = type.toByteArray(Charsets.US_ASCII)
        out.write(t)
        out.write(data)
        val crc = CRC32()
        crc.update(t)
        crc.update(data)
        out.int(crc.value.toInt())
    }
}
