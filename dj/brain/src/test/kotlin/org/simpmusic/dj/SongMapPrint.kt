package org.simpmusic.dj

import kotlinx.serialization.json.Json
import org.simpmusic.dj.analysis.SongMap
import org.simpmusic.dj.model.TrackAnalysis
import java.io.File
import kotlin.test.Test

/** Manual: the song map of every analysis with vocals in STRUCTURE_DIR/analysis (an "Export analyses" folder). */
class SongMapPrint {
    @Test
    fun run() {
        val dir = System.getenv("STRUCTURE_DIR")?.let(::File)?.takeIf { it.isDirectory } ?: return println("skipped: no STRUCTURE_DIR")
        val json = Json { ignoreUnknownKeys = true }
        for (f in (File(dir, "analysis").listFiles() ?: emptyArray()).sortedBy { it.name }) {
            val a = runCatching { json.decodeFromString(TrackAnalysis.serializer(), f.readText()) }.getOrNull() ?: continue
            SongMap.describe(a)?.let { println("MAP ${a.videoId}: $it") }
        }
    }
}
