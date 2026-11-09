package org.simpmusic.dj.android.lyrics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.simpmusic.dj.timeline.LyricLine
import org.simpmusic.dj.timeline.LyricMatch
import org.simpmusic.dj.timeline.LyricQuery
import org.simpmusic.dj.timeline.Lrc
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * LRCLIB (lrclib.net, a free public database of synced lyrics) asked the way a radio track needs. The app's own LRCLIB
 * search sends the raw YouTube artist + title as one query and keeps the first result within 10 s, which misses the song
 * whenever YouTube spells it differently and can take another song of the same length. Here: the exact lookup by name and
 * length first (`/api/get`), then searches by the cleaned title with and without the artist; every result is judged by
 * [LyricMatch] (title, artist, length within 6 s, must be synced) and the best one wins. Blocking: call it on an IO thread.
 */
class LrclibClient(
    private val http: (String) -> String? = ::get,
) {
    @Serializable
    internal data class Row(
        val trackName: String? = null,
        val artistName: String? = null,
        val duration: Double? = null,
        val instrumental: Boolean = false,
        val syncedLyrics: String? = null,
    )

    /** What one lookup found. [answered] false = the site could not be reached (a timeout is not "no lyrics"). */
    class Result(val lines: List<LyricLine>?, val answered: Boolean, val note: String)

    fun find(rawTitle: String, rawArtist: String, durationS: Int?): Result {
        val q = LyricQuery.of(rawTitle, rawArtist)
        if (q.title.isBlank()) return Result(null, true, "no title")
        val want = durationS?.takeIf { it > 0 }?.toDouble()
        var answered = false
        val seen = HashMap<String, Row>()
        val urls = ArrayList<String>()
        if (want != null && q.mainArtist.isNotEmpty()) {
            urls += "$BASE/get?track_name=${enc(q.title)}&artist_name=${enc(q.mainArtist)}&duration=${durationS}"
        }
        if (q.mainArtist.isNotEmpty()) urls += "$BASE/search?track_name=${enc(q.title)}&artist_name=${enc(q.mainArtist)}"
        urls += "$BASE/search?q=${enc((q.title + " " + q.mainArtist).trim())}"
        urls += "$BASE/search?track_name=${enc(q.title)}"
        for (v in q.titleVariants.drop(1).take(1)) urls += "$BASE/search?track_name=${enc(v)}"
        for (url in urls) {
            val body =
                try {
                    http(url)
                } catch (e: IOException) {
                    continue
                }
            answered = true
            if (body.isNullOrBlank()) continue
            val rows =
                try {
                    if (body.trimStart().startsWith("[")) json.decodeFromString<List<Row>>(body) else listOf(json.decodeFromString<Row>(body))
                } catch (e: Exception) {
                    emptyList()
                }
            for (r in rows) seen.putIfAbsent("${r.artistName}|${r.trackName}|${r.duration}", r)
            val best = pick(seen.values.toList(), q, want)
            // a near-certain match ends the search early (fewer requests to a free service)
            if (best != null && best.second >= SURE) return found(best, q)
        }
        val best = pick(seen.values.toList(), q, want) ?: return Result(null, answered, "lrclib: ${seen.size} candidates, none is this song (${q.title} / ${q.mainArtist})")
        return found(best, q)
    }

    private fun pick(rows: List<Row>, q: LyricQuery.Query, want: Double?): Pair<Row, Double>? =
        LyricMatch.best(rows, { LyricMatch.Candidate(it.trackName.orEmpty(), it.artistName.orEmpty(), it.duration, !it.syncedLyrics.isNullOrBlank()) }, q, want)

    private fun found(best: Pair<Row, Double>, q: LyricQuery.Query): Result {
        val lines = Lrc.parse(best.first.syncedLyrics.orEmpty()).takeIf { it.isNotEmpty() }
        return Result(lines, true, "lrclib: '${best.first.artistName} - ${best.first.trackName}' for '${q.mainArtist} - ${q.title}' (match ${"%.2f".format(java.util.Locale.ROOT, best.second)})")
    }

    companion object {
        private const val BASE = "https://lrclib.net/api"
        private const val SURE = 0.9
        private val json = Json { ignoreUnknownKeys = true }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        /** The body of a 200, null for a 404 (LRCLIB's "not found"); throws on a network failure or another status. */
        private fun get(url: String): String? {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 6_000
                c.readTimeout = 8_000
                // LRCLIB asks clients to say who they are
                c.setRequestProperty("User-Agent", "BagrachoDJ (https://github.com/dantopa/AI-mixer-tube)")
                c.setRequestProperty("Accept", "application/json")
                return when (val code = c.responseCode) {
                    200 -> c.inputStream.bufferedReader().use { it.readText() }
                    404 -> null
                    else -> throw IOException("HTTP $code")
                }
            } finally {
                c.disconnect()
            }
        }
    }
}
