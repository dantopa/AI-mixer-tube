package org.simpmusic.dj.android.stems

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import org.simpmusic.dj.android.decode.TrackDecoder
import org.simpmusic.dj.android.log.DjLog
import org.simpmusic.dj.timeline.StemProfile
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** The stems of every track measured so far (memory over `filesDir/dj/stems/<id>.json`). Read by `UserAnnotations`. */
object StemsCache {
    private val json = Json { ignoreUnknownKeys = true }
    private val mem = ConcurrentHashMap<String, StemProfile>()

    @Volatile private var dir: File? = null

    fun install(d: File) {
        dir = d.also { it.mkdirs() }
    }

    private fun fileOf(id: String): File? = dir?.let { File(it, id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json") }

    fun get(id: String): StemProfile? =
        mem[id] ?: fileOf(id)?.takeIf { it.isFile }?.let { f -> runCatching { json.decodeFromString(StemProfile.serializer(), f.readText()) }.getOrNull() }?.also { mem[id] = it }

    fun has(id: String): Boolean = mem.containsKey(id) || fileOf(id)?.isFile == true

    fun put(id: String, p: StemProfile) {
        mem[id] = p
        fileOf(id)?.let { f -> runCatching { f.writeText(json.encodeToString(StemProfile.serializer(), p)) } }
    }

    fun count(): Int = dir?.listFiles()?.count { it.name.endsWith(".json") } ?: 0
}

/**
 * Sends a track's audio to the owner's stems server (Demucs, `dj/stems-server`) and keeps the levels it answers
 * ([StemProfile]), so the DJ and Claude know where the voice, the bass and the drums really are. Asked for the playing and
 * the next track, at most once per track per process, one upload at a time, only from the decoder's cache (no extra
 * download), and by default only on an unmetered network. The upload is the analysis PCM as a 16-bit mono WAV
 * (22.05 kHz, ~10 MB for 4 min); nothing leaves the phone while the setting is off.
 */
class DjStemsSource(
    private val context: Context,
    private val decoder: TrackDecoder,
    private val scope: CoroutineScope,
) {
    /** Called on a background thread when a track's stems landed. */
    @Volatile var onChanged: ((String) -> Unit)? = null

    private val asked = ConcurrentHashMap.newKeySet<String>()
    private val one = Semaphore(1)
    private val json = Json { ignoreUnknownKeys = true }

    fun request(id: String) {
        val s = StemsSettings.state.value
        if (!s.usable || StemsCache.has(id) || !asked.add(id)) return
        scope.launch {
            one.withPermit {
                try {
                    run(id)
                } catch (e: Throwable) {
                    DjLog.w(TAG, "stems of $id failed: ${e.javaClass.simpleName}: ${e.message?.take(160)}")
                }
            }
        }
    }

    private suspend fun run(id: String) {
        val s = StemsSettings.state.value
        if (!s.usable) return
        if (s.unmeteredOnly && metered()) {
            asked.remove(id) // asked again later, on Wi-Fi
            DjLog.i(TAG, "stems of $id: waiting for an unmetered network")
            return
        }
        val pcm = runCatching { decoder.decodeForAnalysis(id, allowNetwork = false) }.getOrElse {
            asked.remove(id)
            DjLog.i(TAG, "stems of $id: not in the cache yet (${it.javaClass.simpleName})")
            return
        }
        val wav = wav16(pcm.samples, pcm.sampleRate)
        val t0 = System.nanoTime()
        if (s.backend == StemsSettings.Backend.REPLICATE) {
            val tok = StemsSettings.replicateToken() ?: return
            DjLog.i(TAG, "stems of $id: sending ${wav.size / 1024} kB to Replicate (Demucs)")
            val p = ReplicateStems(tok, context.cacheDir).separate(wav, id)
            StemsCache.put(id, p)
            DjLog.i(TAG, "stems of $id: ${p.frames} frames (${p.model}) in ${(System.nanoTime() - t0) / 1_000_000} ms, ${p.vocalRanges().size} sung ranges")
            onChanged?.invoke(id)
            return
        }
        DjLog.i(TAG, "stems of $id: uploading ${wav.size / 1024} kB to the stems server")
        val conn = (URL(s.url + "/v1/stems").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "audio/wav")
            setRequestProperty("X-Video-Id", id)
            StemsSettings.token()?.let { setRequestProperty("Authorization", "Bearer $it") }
            setFixedLengthStreamingMode(wav.size)
        }
        try {
            conn.outputStream.use { it.write(wav) }
            val code = conn.responseCode
            if (code != 200) {
                DjLog.w(TAG, "stems of $id: the server answered $code ${runCatching { conn.errorStream?.bufferedReader()?.readText()?.take(160) }.getOrNull().orEmpty()}")
                return
            }
            val body = conn.inputStream.bufferedReader().readText()
            val p = json.decodeFromString(StemProfile.serializer(), body)
            StemsCache.put(id, p)
            DjLog.i(TAG, "stems of $id: ${p.frames} frames of ${p.hopMs} ms (${p.model}) in ${(System.nanoTime() - t0) / 1_000_000} ms, ${p.vocalRanges().size} sung ranges")
            onChanged?.invoke(id)
        } finally {
            conn.disconnect()
        }
    }

    private fun metered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun wav16(x: FloatArray, sr: Int): ByteArray {
        val data = x.size * 2
        val out = ByteArrayOutputStream(44 + data)
        fun i32(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF); out.write(v shr 16 and 0xFF); out.write(v shr 24 and 0xFF) }
        fun i16(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF) }
        out.write("RIFF".toByteArray()); i32(36 + data); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(sr); i32(sr * 2); i16(2); i16(16)
        out.write("data".toByteArray()); i32(data)
        val buf = ByteArray(data)
        for (i in x.indices) {
            val v = (x[i].coerceIn(-1f, 1f) * 32767f).toInt()
            buf[2 * i] = (v and 0xFF).toByte()
            buf[2 * i + 1] = (v shr 8 and 0xFF).toByte()
        }
        out.write(buf)
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "stems"
        /** Demucs on a CPU takes a few minutes for a song: the request waits that long. */
        private const val READ_TIMEOUT_MS = 600_000
    }
}
