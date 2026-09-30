package org.simpmusic.dj.android.store

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.simpmusic.dj.model.TrackAnalysis
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * On-disk cache of [TrackAnalysis]: one JSON file per videoId. No Room, no core DB involvement.
 *
 * - Writes are atomic (temp file + rename), so a crash mid-write can never leave a half file that
 *   would later parse as garbage; a leftover `*.tmp` is swept on the next trim.
 * - A file is *stale* (and deleted on sight) when its schemaVersion is not [TrackAnalysis.SCHEMA_VERSION]
 *   or its analyzerId is not the one the caller expects. Bumping either constant re-analyses lazily.
 * - Size is capped: after each write, when the directory exceeds [maxBytes] or [maxEntries], the
 *   least-recently-used files (by last-modified, which reads refresh) are removed.
 *
 * Plain java.io on purpose: the whole class is unit-tested on the JVM.
 */
class AnalysisStore(
    private val dir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        dir.mkdirs()
    }

    /**
     * The stored analysis for [videoId], or null when there is none, it is unreadable, or it is
     * stale for [analyzerId] (null accepts any analyzer, but still enforces the schema version).
     */
    @Synchronized
    fun get(videoId: String, analyzerId: String? = null): TrackAnalysis? {
        val file = fileFor(videoId)
        if (!file.isFile) return null
        val analysis =
            try {
                json.decodeFromString(TrackAnalysis.serializer(), file.readText())
            } catch (e: SerializationException) {
                file.delete()
                return null
            } catch (e: IllegalArgumentException) {
                // A Confident/MusicalKey init{} require() rejected the stored values.
                file.delete()
                return null
            } catch (e: IOException) {
                return null
            }
        if (analysis.videoId != videoId ||
            analysis.schemaVersion != TrackAnalysis.SCHEMA_VERSION ||
            (analyzerId != null && analysis.analyzerId != analyzerId)
        ) {
            file.delete()
            return null
        }
        file.setLastModified(clock())
        return analysis
    }

    /** True when a fresh analysis exists (does not touch LRU order). */
    @Synchronized
    fun has(videoId: String, analyzerId: String? = null): Boolean {
        val file = fileFor(videoId)
        if (!file.isFile) return false
        return try {
            val a = json.decodeFromString(TrackAnalysis.serializer(), file.readText())
            a.videoId == videoId && a.schemaVersion == TrackAnalysis.SCHEMA_VERSION &&
                (analyzerId == null || a.analyzerId == analyzerId)
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    fun put(analysis: TrackAnalysis) {
        dir.mkdirs()
        val target = fileFor(analysis.videoId)
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(json.encodeToString(TrackAnalysis.serializer(), analysis))
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        target.setLastModified(clock())
        trim()
    }

    @Synchronized
    fun remove(videoId: String) {
        fileFor(videoId).delete()
    }

    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    @Synchronized
    fun sizeBytes(): Long = entries().sumOf { it.length() }

    @Synchronized
    fun count(): Int = entries().size

    /** Removes least-recently-used entries until both caps hold. Returns how many were removed. */
    @Synchronized
    fun trim(): Int {
        dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
        val files = entries().sortedBy { it.lastModified() }.toMutableList()
        var total = files.sumOf { it.length() }
        var removed = 0
        while (files.isNotEmpty() && (total > maxBytes || files.size > maxEntries)) {
            val oldest = files.removeAt(0)
            total -= oldest.length()
            if (oldest.delete()) removed++
        }
        return removed
    }

    private fun entries(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(EXT) }?.toList().orEmpty()

    private fun fileFor(videoId: String): File = File(dir, sanitize(videoId) + EXT)

    companion object {
        private const val EXT = ".json"
        const val DEFAULT_MAX_BYTES = 48L * 1024 * 1024
        const val DEFAULT_MAX_ENTRIES = 4000

        /** YouTube ids are already [A-Za-z0-9_-]{11}; anything else is escaped so it can never leave [dir]. */
        internal fun sanitize(videoId: String): String {
            require(videoId.isNotEmpty()) { "empty videoId" }
            val ok = videoId.all { it.isLetterOrDigit() || it == '_' || it == '-' }
            if (ok && videoId.length <= 64) return videoId
            val safe = videoId.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }.joinToString("").take(48)
            return safe + "~" + Integer.toHexString(videoId.hashCode())
        }
    }
}
