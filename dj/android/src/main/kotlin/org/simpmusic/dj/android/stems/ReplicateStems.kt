package org.simpmusic.dj.android.stems

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.simpmusic.dj.android.log.DjLog
import org.simpmusic.dj.timeline.StemProfile
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Demucs on Replicate (`ryan5453/demucs`, htdemucs on an A100: a few seconds and about half a US cent per song, as listed
 * on its page): the track's WAV is uploaded through the Files API (`POST /v1/files`, multipart field `content`, the input
 * URL is `urls.get`), a prediction is created on the pinned version with `format = mp3`, polled until it succeeds, and the
 * four stems (`vocals`, `bass`, `drums`, `other`: file URLs, deleted by Replicate after an hour) are downloaded and decoded
 * HERE into the same levels map the owner's server answers ([StemProfile]). Only the map is kept.
 */
internal class ReplicateStems(private val token: String, private val cacheDir: File) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun separate(wav: ByteArray, id: String): StemProfile {
        val audioUrl = upload(wav, id)
        var p = createPrediction(audioUrl)
        val t0 = System.currentTimeMillis()
        while (p.status() !in TERMINAL) {
            if (System.currentTimeMillis() - t0 > MAX_WAIT_MS) error("Replicate took over ${MAX_WAIT_MS / 60_000} min")
            delay(POLL_MS)
            p = getJson(p["urls"]!!.jsonObject["get"]!!.jsonPrimitive.content)
        }
        if (p.status() != "succeeded") error("Replicate prediction ${p.status()}: ${p["error"]?.toString()?.take(160)}")
        val out = p["output"]?.jsonObject ?: error("Replicate answered no output")
        val stems = HashMap<String, List<Int>>()
        for (name in listOf("vocals", "bass", "drums", "other")) {
            val url = out[name]?.jsonPrimitive?.contentOrNull ?: error("no $name stem in the output")
            val f = File(cacheDir, "stem-$id-$name.mp3")
            try {
                download(url, f)
                stems[name] = levels(f, HOP_MS)
            } finally {
                f.delete()
            }
        }
        DjLog.i(TAG, "replicate $id: ${p["metrics"]?.toString()?.take(120)}")
        return StemProfile(HOP_MS, stems["vocals"]!!, stems["bass"]!!, stems["drums"]!!, stems["other"]!!, model = "htdemucs@replicate")
    }

    /** GET /v1/account: a cheap way to know the token works before uploading megabytes. null = OK, else why not. */
    fun check(): String? {
        val c = open("$API/account", "GET")
        return try {
            when (val code = c.responseCode) {
                in 200..299 -> null
                401 -> "Replicate refused the token (401)"
                else -> "Replicate account check answered $code"
            }
        } finally {
            c.disconnect()
        }
    }

    private fun JsonObject.status(): String = this["status"]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun upload(wav: ByteArray, id: String): String {
        val boundary = "----bagracho${System.nanoTime()}"
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"content\"; filename=\"$id.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val c = open("$API/files", "POST").apply {
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            doOutput = true
            setFixedLengthStreamingMode(head.size + wav.size + tail.size)
        }
        try {
            c.outputStream.use { it.write(head); it.write(wav); it.write(tail) }
            val o = read(c, "upload")
            return o["urls"]?.jsonObject?.get("get")?.jsonPrimitive?.contentOrNull ?: error("the upload answered no url")
        } finally {
            c.disconnect()
        }
    }

    private fun createPrediction(audioUrl: String): JsonObject {
        val body = """{"version":"$VERSION","input":{"audio":${kotlinx.serialization.json.JsonPrimitive(audioUrl)},"model":"htdemucs","format":"mp3"}}""".toByteArray()
        val c = open("$API/predictions", "POST").apply {
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Prefer", "wait=60")
            doOutput = true
            setFixedLengthStreamingMode(body.size)
        }
        try {
            c.outputStream.use { it.write(body) }
            return read(c, "prediction")
        } finally {
            c.disconnect()
        }
    }

    private fun getJson(url: String): JsonObject {
        val c = open(url, "GET")
        try {
            return read(c, "poll")
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String, to: File) {
        val c = open(url, "GET")
        try {
            if (c.responseCode !in 200..299) error("stem download answered ${c.responseCode}")
            c.inputStream.use { i -> to.outputStream().use { o -> i.copyTo(o) } }
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 120_000
            setRequestProperty("Authorization", "Bearer $token")
        }

    private fun read(c: HttpURLConnection, what: String): JsonObject {
        val code = c.responseCode
        if (code !in 200..299) {
            val err = runCatching { c.errorStream?.bufferedReader()?.readText()?.take(200) }.getOrNull().orEmpty()
            error(
                when (code) {
                    401 -> "Replicate refused the token (401)"
                    402 -> "Replicate needs billing set up (402)"
                    429 -> "Replicate rate limit (429)"
                    else -> "Replicate $what answered $code $err"
                },
            )
        }
        return json.parseToJsonElement(c.inputStream.bufferedReader().readText()).jsonObject
    }

    companion object {
        private const val TAG = "stems"
        private const val API = "https://api.replicate.com/v1"
        /** ryan5453/demucs, the version marked Latest on 2026-11-11 (htdemucs; output keys vocals/bass/drums/other). */
        const val VERSION = "ff5ad47d2685b27c0f820ee134c4394140804f08fa755cb20c846459faa4b337"
        private val TERMINAL = setOf("succeeded", "failed", "canceled")
        private const val POLL_MS = 2_000L
        private const val MAX_WAIT_MS = 10 * 60_000L
        const val HOP_MS = 250

        /** RMS level (dBFS, rounded, -90 floor) of [file] (any format MediaCodec decodes) per [hopMs], channels averaged. */
        fun levels(file: File, hopMs: Int): List<Int> {
            val ex = MediaExtractor()
            ex.setDataSource(file.path)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: error("no audio track in the stem")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            val out = ArrayList<Int>()
            var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var hop = max(1, sr * hopMs / 1000)
            var acc = 0.0
            var n = 0
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            try {
                while (!outDone) {
                    if (!inDone) {
                        val ix = codec.dequeueInputBuffer(10_000)
                        if (ix >= 0) {
                            val buf = codec.getInputBuffer(ix)!!
                            val size = ex.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(ix, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inDone = true
                            } else {
                                codec.queueInputBuffer(ix, 0, size, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val ox = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            hop = max(1, sr * hopMs / 1000)
                        }
                        ox >= 0 -> {
                            val b = codec.getOutputBuffer(ox)!!.order(ByteOrder.LITTLE_ENDIAN)
                            b.position(info.offset)
                            val shorts = b.asShortBuffer()
                            val frames = (info.size / 2) / ch
                            for (fr in 0 until frames) {
                                var s = 0.0
                                for (k in 0 until ch) s += shorts.get(fr * ch + k) / 32768.0
                                s /= ch
                                acc += s * s
                                if (++n == hop) {
                                    val r = sqrt(acc / n)
                                    out += if (r <= 1e-6) -90 else (20 * log10(r)).roundToInt().coerceAtLeast(-90)
                                    acc = 0.0
                                    n = 0
                                }
                            }
                            codec.releaseOutputBuffer(ox, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
                ex.release()
            }
            return out
        }
    }
}
