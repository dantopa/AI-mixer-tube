package org.simpmusic.dj.android.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executor
import java.util.concurrent.Executors

enum class DjLevel(val letter: Char, val androidPriority: Int) {
    DEBUG('D', 3),
    INFO('I', 4),
    WARN('W', 5),
    ERROR('E', 6),
}

/** Where a formatted line is mirrored to (Logcat in production). */
fun interface DjLogSink {
    fun write(level: DjLevel, line: String)
}

/** `android.util.Log` under the tag `DJ`, so `adb logcat -s DJ` shows exactly the DJ. */
object AndroidLogSink : DjLogSink {
    override fun write(level: DjLevel, line: String) {
        try {
            android.util.Log.println(level.androidPriority, "DJ", line)
        } catch (_: Throwable) {
            // unit tests / stubs
        }
    }
}

/**
 * A size-bounded log file pair: [file] and `[file].1`. When [file] would pass [maxBytes] it becomes `.1` (replacing the
 * previous one) and a new [file] is started, so at most 2 x [maxBytes] stay on disk and the newest lines are always there.
 * Not thread-safe by itself: [DjLogger] serialises every call on one writer thread.
 */
class RotatingLogFile(private val file: File, private val maxBytes: Long = 1_000_000L) {
    private var size: Long = -1L

    fun append(line: String) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        try {
            file.parentFile?.mkdirs()
            if (size < 0) size = if (file.isFile) file.length() else 0L
            if (size > 0 && size + bytes.size > maxBytes) rotate()
            file.appendBytes(bytes)
            size += bytes.size
        } catch (_: Exception) {
            // Logging must never take the DJ down.
        }
    }

    private fun rotate() {
        val old = File(file.path + ".1")
        old.delete()
        file.renameTo(old)
        size = 0L
    }

    fun files(): List<File> = listOf(File(file.path + ".1"), file).filter { it.isFile }
}

/**
 * The DJ's log: a ring of the last [capacity] lines (what the in-app viewer tails), an optional rotating file, and a
 * mirror to Logcat. Every line carries wall time, milliseconds since this logger started (about process start), the
 * thread and a short component tag; a multi-line record (an exception) is written as several lines with the same header,
 * so grepping any single line still tells when and where it happened.
 *
 * Never log audio or a full URL: use [redactUrl].
 */
class DjLogger(
    private val capacity: Int = 3000,
    private val wallClock: () -> Long = System::currentTimeMillis,
    val startedAtMs: Long = wallClock(),
    private val threadName: () -> String = { Thread.currentThread().name },
    private val sink: DjLogSink? = null,
    private val writer: Executor = Executor { it.run() },
    private var file: RotatingLogFile? = null,
) {
    private val lock = Any()
    private val ring = ArrayDeque<String>(capacity + 1)
    private val _version = MutableStateFlow(0L)

    /** Bumped on every line: the viewer re-reads [lines] when it changes. */
    val version: StateFlow<Long> = _version.asStateFlow()

    fun attachFile(f: RotatingLogFile?) {
        file = f
    }

    fun log(level: DjLevel, tag: String, message: String, error: Throwable? = null) {
        val header = header(level, tag)
        val records = ArrayList<String>(2)
        message.lineSequence().forEach { records += "$header$it" }
        if (error != null) formatThrowable(error).forEach { records += "$header  $it" }
        synchronized(lock) {
            for (r in records) {
                ring.addLast(r)
                if (ring.size > capacity) ring.removeFirst()
            }
        }
        _version.value = _version.value + 1
        for (r in records) {
            sink?.write(level, r)
            val f = file
            if (f != null) writer.execute { f.append(r) }
        }
    }

    /** Adds an already formatted line to the ring only (used to carry lines over when the file/sink are attached). */
    internal fun injectRaw(line: String) {
        synchronized(lock) {
            ring.addLast(line)
            if (ring.size > capacity) ring.removeFirst()
        }
        _version.value = _version.value + 1
    }

    fun lines(): List<String> = synchronized(lock) { ring.toList() }

    fun text(): String = lines().joinToString("\n")

    fun clear() {
        synchronized(lock) { ring.clear() }
        _version.value = _version.value + 1
    }

    internal fun header(level: DjLevel, tag: String): String {
        val now = wallClock()
        val wall = WALL.format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()))
        return "$wall +${now - startedAtMs}ms [${threadName()}] ${tag.take(TAG_WIDTH).padEnd(TAG_WIDTH)} ${level.letter} "
    }

    companion object {
        const val TAG_WIDTH = 9
        private val WALL: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")

        /** `Class: message` plus the first [maxFrames] frames and the cause chain (each cause with [causeFrames] frames). */
        fun formatThrowable(t: Throwable, maxFrames: Int = 8, causeFrames: Int = 4, maxCauses: Int = 4): List<String> {
            val out = ArrayList<String>()
            var cur: Throwable? = t
            var depth = 0
            while (cur != null && depth <= maxCauses) {
                out += (if (depth == 0) "" else "Caused by: ") + cur.javaClass.name + ": " + cur.message
                val limit = if (depth == 0) maxFrames else causeFrames
                cur.stackTrace.take(limit).forEach { out += "    at $it" }
                if (cur.stackTrace.size > limit) out += "    ... ${cur.stackTrace.size - limit} more"
                cur = cur.cause?.takeIf { it !== cur }
                depth++
            }
            return out
        }

        /**
         * A URL reduced to what is safe and useful in a log: host, `itag`, `clen` (content length) and `mime`. Signatures,
         * tokens and the path never appear.
         */
        fun redactUrl(url: String?): String {
            if (url.isNullOrBlank()) return "url=none"
            return try {
                val uri = URI(url)
                val host = uri.host ?: return "url=${uri.scheme ?: "?"}:opaque"
                val q =
                    (uri.rawQuery ?: "")
                        .split('&')
                        .mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                        .toMap()
                buildString {
                    append("host=").append(host)
                    q["itag"]?.let { append(" itag=").append(it) }
                    q["clen"]?.let { append(" clen=").append(it) }
                    q["mime"]?.let { append(" mime=").append(it) }
                }
            } catch (_: Exception) {
                "url=unparseable"
            }
        }
    }
}

/**
 * The process-wide DJ log. Everything in `:djAndroid` writes through here; [install] (called once, from the Koin module)
 * adds the Logcat mirror and the rotating file.
 */
object DjLog {
    @Volatile
    var logger: DjLogger = DjLogger()
        private set

    @Volatile
    private var installedFile: RotatingLogFile? = null

    private val writer: Executor by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "dj-log").apply { isDaemon = true } }
    }

    /** Idempotent. Keeps what was logged before the call. */
    @Synchronized
    fun install(dir: File, sink: DjLogSink = AndroidLogSink, maxBytes: Long = 1_000_000L) {
        if (installedFile != null) return
        val f = RotatingLogFile(File(dir, "dj-debug.log"), maxBytes)
        installedFile = f
        val previous = logger.lines()
        logger = DjLogger(sink = sink, writer = writer, file = f, startedAtMs = logger.startedAtMs)
        // The lines logged before install are re-injected verbatim into the new ring.
        previous.forEach { logger.injectRaw(it) }
    }

    fun d(tag: String, msg: String) = logger.log(DjLevel.DEBUG, tag, msg)

    fun i(tag: String, msg: String) = logger.log(DjLevel.INFO, tag, msg)

    fun w(tag: String, msg: String, t: Throwable? = null) = logger.log(DjLevel.WARN, tag, msg, t)

    fun e(tag: String, msg: String, t: Throwable? = null) = logger.log(DjLevel.ERROR, tag, msg, t)

    fun lines(): List<String> = logger.lines()

    fun text(): String = logger.text()

    fun clear() = logger.clear()

    val version: StateFlow<Long> get() = logger.version

    /** The two files on disk (older first), for sharing. */
    fun files(): List<File> = installedFile?.files().orEmpty()

    fun redactUrl(url: String?): String = DjLogger.redactUrl(url)

    /** Runs [block] and logs `what ... ok in N ms` or `what ... FAILED in N ms` with the exception; rethrows. */
    inline fun <T> timed(tag: String, what: String, block: () -> T): T {
        val t0 = System.nanoTime()
        try {
            val r = block()
            d(tag, "$what ok in ${(System.nanoTime() - t0) / 1_000_000} ms")
            return r
        } catch (e: kotlinx.coroutines.CancellationException) {
            d(tag, "$what cancelled after ${(System.nanoTime() - t0) / 1_000_000} ms")
            throw e
        } catch (e: Throwable) {
            this.e(tag, "$what FAILED in ${(System.nanoTime() - t0) / 1_000_000} ms", e)
            throw e
        }
    }
}
