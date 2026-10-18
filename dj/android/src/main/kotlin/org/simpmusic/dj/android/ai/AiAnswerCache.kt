package org.simpmusic.dj.android.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.simpmusic.dj.android.log.DjLog
import org.simpmusic.dj.model.AiMixPoints
import java.io.File

/**
 * Claude's answers, kept per pair ("from>to") across skips and restarts, so a mix Claude already solved is not paid for
 * again (owner, 2026-10-18: "skip ahead, go back, and it says AI thinking again").
 *
 * - an answer the owner rated 👍 is reused for good;
 * - one rated 👎 is forgotten, so the next time that pair comes Claude is asked again;
 * - an unrated one is reused too (by default, no spending); the ✨ AI Mix button always asks again and replaces it.
 *
 * The points are the tracks' own source times, so they hold whatever the playhead: when the remembered exit is already
 * behind the playhead the planner refuses it and the engine's usual path applies. JSON in `filesDir/dj/ai_answers.json`,
 * at most [MAX] pairs (the oldest go). Thread-safe; file writes are small and synchronous.
 */
object AiAnswerCache {
    @Serializable
    data class Entry(val points: AiMixPoints, val model: String, val atEpochMs: Long, val rating: Int? = null)

    @Serializable
    private data class Store(val entries: Map<String, Entry> = emptyMap())

    private const val MAX = 400
    private const val TAG = "ai-cache"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private var file: File? = null
    private val map = LinkedHashMap<String, Entry>()

    @Synchronized
    fun install(dir: File) {
        if (file != null) return
        dir.mkdirs()
        val f = File(dir, "ai_answers.json")
        file = f
        runCatching { if (f.exists()) map.putAll(json.decodeFromString(Store.serializer(), f.readText()).entries) }
            .onFailure { DjLog.w(TAG, "could not read ${f.name}: ${it.message}") }
        DjLog.i(TAG, "${map.size} remembered answers (${map.values.count { it.rating == 1 }} liked)")
    }

    /** The answer to reuse for [key] (liked or unrated); null when there is none or the owner disliked it. */
    @Synchronized
    fun usable(key: String): Entry? = map[key]?.takeIf { (it.rating ?: 0) >= 0 }

    @Synchronized
    fun liked(key: String): Boolean = map[key]?.rating == 1

    /** A new answer for [key] replaces the old one (and its rating: it is a different mix). */
    @Synchronized
    fun put(key: String, points: AiMixPoints, model: String) {
        // an AI answer found ahead never replaces the owner's own edit (the ✨ / Ask again path drops it first)
        if (map[key]?.model == OWNER && !points.byOwner) return
        map.remove(key)
        map[key] = Entry(points, model, System.currentTimeMillis())
        while (map.size > MAX) map.remove(map.keys.first())
        save()
    }

    /**
     * The owner rated Claude's mix of [key] (the caller checks that the mix that played was Claude's: a regular mix of the
     * same pair says nothing about the answer): 👍 keeps its answer for good; 👎 forgets it.
     */
    @Synchronized
    fun rate(key: String, up: Boolean) {
        val e = map[key] ?: return
        if (up) map[key] = e.copy(rating = 1) else map.remove(key)
        DjLog.i(TAG, "$key ${if (up) "liked: kept for good" else "disliked: forgotten, Claude is asked again next time"}")
        save()
    }

    /** The owner's own edit of [key] (mix editor, saved): kept for good, like a 👍, and never replaced by an AI answer. */
    @Synchronized
    fun putOwner(key: String, points: AiMixPoints) {
        map.remove(key)
        map[key] = Entry(points.copy(byOwner = true), OWNER, System.currentTimeMillis(), rating = 1)
        while (map.size > MAX) map.remove(map.keys.first())
        save()
    }

    @Synchronized
    fun isOwner(key: String): Boolean = map[key]?.model == OWNER

    @Synchronized
    fun forgetOwner(key: String) {
        if (map[key]?.model == OWNER && map.remove(key) != null) save()
    }

    /** The owner's saved edits, for "learn from my ratings" (they are the owner's own mixes: the gold standard). */
    @Synchronized
    fun ownerEdits(): Map<String, AiMixPoints> = map.filterValues { it.model == OWNER }.mapValues { it.value.points }

    const val OWNER = "owner"

    @Synchronized
    fun count(): Int = map.size

    @Synchronized
    fun clear(): Int {
        val n = map.size
        map.clear()
        save()
        return n
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json.encodeToString(Store.serializer(), Store(LinkedHashMap(map))))
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }.onFailure { DjLog.w(TAG, "could not write ${f.name}: ${it.message}") }
    }
}
