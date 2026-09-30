package org.simpmusic.dj.ml

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Test helpers: reference tensors dumped from PyTorch (small, checked in) and the optional local corpus/model. */
object Refs {
    fun bytes(name: String): ByteArray =
        checkNotNull(Refs::class.java.getResourceAsStream("/beatthis/$name")) { "missing test resource $name" }.readBytes()

    fun floats(name: String): FloatArray {
        val b = ByteBuffer.wrap(bytes(name)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()).also { b.get(it) }
    }

    fun json(name: String): JsonObject = Json.parseToJsonElement(String(bytes(name))).jsonObject

    fun doubles(o: JsonObject, key: String): DoubleArray = o[key]!!.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()

    fun ints(o: JsonObject, key: String): IntArray = o[key]!!.jsonArray.map { it.jsonPrimitive.int }.toIntArray()

    /** The deterministic signal `dump_vectors.py` renders (2 s at 22.05 kHz). */
    fun testSignal(n: Int, sr: Int = 22050): FloatArray {
        var s = 12345L
        return FloatArray(n) { i ->
            s = (s * 1103515245L + 12345L) and 0x7FFFFFFFL
            val noise = (s.toDouble() / 0x7FFFFFFFL - 0.5) * 0.1
            var x = 0.5 * Math.sin(2 * Math.PI * 440.0 * i / sr) + 0.25 * Math.sin(2 * Math.PI * 3000.5 * i / sr)
            if (i % 11025 < 50) x += 0.3
            (x + noise).toFloat()
        }
    }

    /** ONNX model and corpus are NOT in git: env vars, else the sandbox scratchpad defaults. Null = skip the test. */
    fun modelFile(): File? = (System.getenv("BEAT_THIS_MODEL") ?: "$SCRATCH/model/beat_this_int8mm.onnx").let(::File).takeIf { it.isFile }

    fun corpusDir(): File? = (System.getenv("BEAT_THIS_CORPUS") ?: "$SCRATCH/corpus").let(::File).takeIf { File(it, "refs").isDirectory }

    private const val SCRATCH = "/tmp/claude-0/-home-user-AI-mixer-tube/e68acb2e-1315-5994-9988-a44dc49e8066/scratchpad"
}
