package org.simpmusic.dj.timeline

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.test.Test

/**
 * Manual: LYRICS_LIVE=1 asks lrclib.net for YouTube-style titles the old way (raw "artist title" query, first result within
 * 10 s) and the new way (cleaned names, judged by [LyricMatch]). Not a unit test: it needs the network.
 */
class LyricSearchReport {
    @Serializable
    data class Row(val trackName: String? = null, val artistName: String? = null, val duration: Double? = null, val syncedLyrics: String? = null)

    private val json = Json { ignoreUnknownKeys = true }

    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "BagrachoDJ (https://github.com/dantopa/AI-mixer-tube)")
        return try {
            if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null
        } finally {
            c.disconnect()
        }
    }

    private fun rows(url: String): List<Row> {
        val b = get(url) ?: return emptyList()
        return if (b.trimStart().startsWith("[")) json.decodeFromString(b) else listOf(json.decodeFromString(b))
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    @Test
    fun run() {
        if (System.getenv("LYRICS_LIVE") != "1") return
        // title as YouTube shows it, artist as the queue gives it, length in seconds (approximate, as the queue would)
        val tracks =
            listOf(
                Triple("Los Palmeras - Bombón Asesino (Video Oficial)", "Los Palmeras", 205),
                Triple("Carita Triste (Video Oficial)", "Q' Lokura", 0),
                Triple("Me Enamoré (Video Oficial)", "Ráfaga", 0),
                Triple("Ráfaga - Mentirosa (Video Oficial)", "Ráfaga", 0),
                Triple("Amor Clasificado (En Vivo)", "Rodrigo", 0),
                Triple("La Mano de Dios", "Rodrigo", 0),
                Triple("Gilda - No Me Arrepiento De Este Amor (Video Oficial)", "Gilda", 0),
                Triple("Los Ángeles Azules - 17 Años (Video Oficial)", "Los Ángeles Azules", 0),
                Triple("Cómo Te Voy a Olvidar (Official Video)", "Los Ángeles Azules", 0),
                Triple("Damas Gratis - Se Te Ve la Tanga", "Damas Gratis", 0),
                Triple("Agapornis - Persiana Americana (Video Oficial)", "Agapornis", 0),
                Triple("La Bicicleta | Video Oficial", "Carlos Vives, Shakira", 0),
                Triple("Tan Bionica - Ciudad Mágica (Lyric Video)", "Tan Bionica", 0),
                Triple("Karina - Corazón Mentiroso (Video Oficial) ft. Damas Gratis", "La Princesita Karina", 0),
                Triple("Antonio Ríos - Nunca Me Faltes", "Antonio Rios", 0),
            )
        var oldHits = 0
        var newHits = 0
        for ((title, artist, dur) in tracks) {
            val d = dur.takeIf { it > 0 }
            // old: what the app's LRCLIB search does
            val oldRows = rows("https://lrclib.net/api/search?q=${enc("$artist $title")}")
            val old = if (d != null) oldRows.find { abs((it.duration ?: 0.0) - d) <= 10 } else oldRows.firstOrNull()
            val oldOk = !old?.syncedLyrics.isNullOrBlank()
            // new
            val q = LyricQuery.of(title, artist)
            val seen = LinkedHashMap<String, Row>()
            for (u in listOf(
                "https://lrclib.net/api/search?track_name=${enc(q.title)}&artist_name=${enc(q.mainArtist)}",
                "https://lrclib.net/api/search?q=${enc(q.title + " " + q.mainArtist)}",
                "https://lrclib.net/api/search?track_name=${enc(q.title)}",
            )) {
                for (r in rows(u)) seen.putIfAbsent("${r.artistName}|${r.trackName}|${r.duration}", r)
            }
            val best = LyricMatch.best(seen.values.toList(), { LyricMatch.Candidate(it.trackName.orEmpty(), it.artistName.orEmpty(), it.duration, !it.syncedLyrics.isNullOrBlank()) }, q, d?.toDouble())
            if (oldOk) oldHits++
            if (best != null) newHits++
            println(
                "%-55s old: %-38s new: %s".format(
                    "$artist | $title".take(55),
                    if (oldOk) "${old?.artistName} - ${old?.trackName}".take(38) else "-",
                    best?.let { "${it.first.artistName} - ${it.first.trackName} (%.2f) [q='${q.mainArtist} - ${q.title}']".format(it.second) } ?: "- [q='${q.mainArtist} - ${q.title}']",
                ),
            )
        }
        println("synced lyrics found: old $oldHits / ${tracks.size}, new $newHits / ${tracks.size}")
    }
}
