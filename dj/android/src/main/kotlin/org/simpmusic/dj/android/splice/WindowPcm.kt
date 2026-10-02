package org.simpmusic.dj.android.splice

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Read-only stereo PCM16 frames of a rendered window. Reads must be safe from several threads at once. */
interface WindowSamples {
    val sampleRate: Int
    val frames: Long

    /** Sample of channel [ch] (0 = left, 1 = right) at frame [frame], as -32768..32767. 0 outside the window. */
    fun sample(frame: Long, ch: Int): Int

    /** Ms of window per frame. */
    val msPerFrame: Double get() = 1000.0 / sampleRate
}

/**
 * Value at a FRACTIONAL frame position [pos] of channel [ch], by 4-point cubic (Catmull-Rom) interpolation. An integer
 * position returns the sample itself, so an unglided splice is bit-exact.
 */
fun WindowSamples.interpolate(pos: Double, ch: Int): Float {
    val i = kotlin.math.floor(pos).toLong()
    val f = pos - i
    if (f < 1e-9) return sample(i, ch).toFloat()
    val y0 = sample(i - 1, ch).toFloat()
    val y1 = sample(i, ch).toFloat()
    val y2 = sample(i + 1, ch).toFloat()
    val y3 = sample(i + 2, ch).toFloat()
    val t = f.toFloat()
    val a = -0.5f * y0 + 1.5f * y1 - 1.5f * y2 + 0.5f * y3
    val b = y0 - 2.5f * y1 + 2f * y2 - 0.5f * y3
    val c = -0.5f * y0 + 0.5f * y2
    return ((a * t + b) * t + c) * t + y1
}

/** In-memory window (tests, or a short window). */
class ArrayWindowSamples(
    override val sampleRate: Int,
    /** Interleaved stereo. */
    private val pcm: ShortArray,
) : WindowSamples {
    override val frames: Long get() = (pcm.size / 2).toLong()

    override fun sample(frame: Long, ch: Int): Int = if (frame < 0 || frame >= frames) 0 else pcm[(frame * 2 + ch).toInt()].toInt()
}

/**
 * The rendered window WAV, memory-mapped: no heap for a 15 MB mix, and the pages stay valid even if the engine deletes
 * the file while a deck still plays it (the mapping keeps the inode alive). Only stereo PCM16, which is what the
 * renderer writes.
 */
class MappedWindowPcm private constructor(
    override val sampleRate: Int,
    private val data: ByteBuffer,
) : WindowSamples {
    override val frames: Long = (data.capacity() / 4).toLong()

    override fun sample(frame: Long, ch: Int): Int {
        if (frame < 0 || frame >= frames) return 0
        return data.getShort((frame * 4 + ch * 2).toInt()).toInt()
    }

    companion object {
        /** Opens [file]; null when it is not a stereo PCM16 WAV. */
        fun open(file: File): MappedWindowPcm? {
            RandomAccessFile(file, "r").use { raf ->
                val ch = raf.channel
                val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                ch.read(header, 0)
                header.flip()
                if (header.remaining() < 12) return null
                val riff = ByteArray(4).also { header.get(it) }
                header.getInt()
                val wave = ByteArray(4).also { header.get(it) }
                if (String(riff) != "RIFF" || String(wave) != "WAVE") return null
                var pos = 12L
                var rate = 0
                var channels = 0
                var bits = 0
                var format = 0
                val chunk = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                while (pos + 8 <= ch.size()) {
                    chunk.clear()
                    ch.read(chunk, pos)
                    chunk.flip()
                    val id = ByteArray(4).also { chunk.get(it) }.let { String(it) }
                    val len = chunk.getInt().toLong() and 0xffffffffL
                    val body = pos + 8
                    when (id) {
                        "fmt " -> {
                            val fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                            ch.read(fmt, body)
                            fmt.flip()
                            format = fmt.getShort().toInt()
                            channels = fmt.getShort().toInt()
                            rate = fmt.getInt()
                            fmt.getInt()
                            fmt.getShort()
                            bits = fmt.getShort().toInt()
                        }
                        "data" -> {
                            if (format != 1 || channels != 2 || bits != 16 || rate <= 0) return null
                            val size = minOf(len, ch.size() - body)
                            val map = ch.map(FileChannel.MapMode.READ_ONLY, body, size).order(ByteOrder.LITTLE_ENDIAN)
                            return MappedWindowPcm(rate, map)
                        }
                    }
                    pos = body + len + (len and 1L)
                }
                return null
            }
        }
    }
}
