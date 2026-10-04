package org.simpmusic.dj.audio

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal RIFF/WAVE reader/writer (PCM16 and float32) used by tools, tests and the offline renderer. */
object WavIo {
    class Wav(val channels: Array<FloatArray>, val sampleRate: Int) {
        val frames: Int get() = channels[0].size
        fun mono(): FloatArray {
            if (channels.size == 1) return channels[0]
            val out = FloatArray(frames)
            for (c in channels) for (i in out.indices) out[i] += c[i]
            val g = 1f / channels.size
            for (i in out.indices) out[i] *= g
            return out
        }
    }

    fun write(file: File, wav: Wav) = file.writeBytes(encode(wav))

    fun encode(wav: Wav): ByteArray {
        val ch = wav.channels.size
        val n = wav.frames
        val data = ByteBuffer.allocate(n * ch * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) for (c in 0 until ch) {
            val v = (wav.channels[c][i].coerceIn(-1f, 1f) * 32767f).toInt()
            data.putShort(v.toShort())
        }
        val out = ByteArrayOutputStream()
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data.capacity()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(ch.toShort())
        h.putInt(wav.sampleRate).putInt(wav.sampleRate * ch * 2).putShort((ch * 2).toShort()).putShort(16)
        h.put("data".toByteArray()).putInt(data.capacity())
        out.write(h.array())
        out.write(data.array())
        return out.toByteArray()
    }

    fun read(file: File): Wav = decode(file.readBytes())

    fun decode(bytes: ByteArray): Wav {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size > 44 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV file" }
        var pos = 12
        var channels = 1
        var rate = 44100
        var bits = 16
        var format = 1
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = b.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    format = b.getShort(body).toInt() and 0xFFFF
                    channels = b.getShort(body + 2).toInt()
                    rate = b.getInt(body + 4)
                    bits = b.getShort(body + 14).toInt()
                }
                "data" -> {
                    val len = minOf(size.toLong() and 0xFFFFFFFFL, (bytes.size - body).toLong()).toInt()
                    val bytesPer = bits / 8
                    val frames = len / (bytesPer * channels)
                    val out = Array(channels) { FloatArray(frames) }
                    var p = body
                    for (i in 0 until frames) for (c in 0 until channels) {
                        out[c][i] = when {
                            format == 3 && bits == 32 -> b.getFloat(p)
                            bits == 16 -> b.getShort(p) / 32768f
                            bits == 24 -> ((b.get(p + 2).toInt() shl 16) or ((b.get(p + 1).toInt() and 0xFF) shl 8) or (b.get(p).toInt() and 0xFF)) / 8388608f
                            else -> error("unsupported WAV sample format: $format/$bits")
                        }
                        p += bytesPer
                    }
                    return Wav(out, rate)
                }
            }
            pos = body + size + (size and 1)
        }
        error("WAV has no data chunk")
    }
}
