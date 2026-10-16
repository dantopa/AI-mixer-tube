package org.simpmusic.dj.android.lyrics

import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.repository.LyricsCanvasRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.simpmusic.dj.android.log.DjLog
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.timeline.LyricLine
import org.simpmusic.dj.timeline.LyricVocals
import org.simpmusic.dj.timeline.TrackTimeline
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The validated lyric vocals of every track this process has looked at, read by `UserAnnotations.lyricVocals` (and so by
 * the brain's `TrackTimeline.vocals`) and by the export. Filled by [DjLyricsSource]; thread-safe.
 */
object LyricVocalsCache {
    private val map = ConcurrentHashMap<String, LyricVocals>()

    fun get(videoId: String): LyricVocals? = map[videoId]

    internal fun put(videoId: String, v: LyricVocals?) {
        if (v == null) map.remove(videoId) else map[videoId] = v
    }

    /** videoId -> its lyric vocals, for the export. */
    fun all(): Map<String, LyricVocals> = HashMap(map)

    /** One TSV row per track: id, provider, offset ms, confidence, ranges ("start-end" ms, comma-separated). */
    fun tsv(): String =
        all().entries.sortedBy { it.key }.joinToString("\n") { (id, v) ->
            "$id\t${v.provider}\t${v.offsetMs}\t${"%.2f".format(java.util.Locale.ROOT, v.confidence)}\t${v.ranges.joinToString(",") { "${it.startMs}-${it.endMs}" }}"
        }
}

/**
 * Where the voice is, from the songs' own synced lyrics (see the brain's [LyricVocals]): for the playing and the next track
 * (the engine calls [request] from its queue context), first the app's cached lyrics, else the providers in the app's order
 * (SimpMusic by video id, then BetterLyrics, which has word timing, then LRCLIB). The lines are cached per track as JSON in
 * [dir] (`filesDir/dj/lyrics/<id>.json`; "no lyrics" too, retried after [NO_LYRICS_RETRY_MS]), fetched at most once per
 * track per process, never on the main thread. They are validated against the track's analysis (duration, offset against
 * the detector's evidence) when it exists, and again when a full analysis with the detector's profile lands later.
 */
class DjLyricsSource(
    private val lyrics: LyricsCanvasRepository,
    private val songs: SongRepository,
    private val dir: File,
    /** Any stored analysis of a track (blocking file read: called on [scope]'s IO dispatcher). */
    private val analysisOf: (String) -> TrackAnalysis?,
    /** The full analysis of a track as it is stored, re-emitted on every store (the scheduler's `observe`). */
    private val analysisUpdates: (String) -> Flow<TrackAnalysis?>,
    /** Background scope (IO dispatcher); nothing here runs on the main thread. */
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Called (on [scope]) when a track's trusted lyric vocals changed: the engine re-plans the pair it concerns. */
    @Volatile var onChanged: ((String) -> Unit)? = null

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Tracks already requested in this process (fetch at most once each). */
    private val requested = ConcurrentHashMap.newKeySet<String>()

    init {
        dir.mkdirs()
    }

    /** Cheap and main-thread safe: starts the work for [videoId] the first time it is asked for in this process. */
    fun request(videoId: String) {
        if (videoId.isBlank() || !requested.add(videoId)) return
        scope.launch {
            try {
                load(videoId)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Throwable) {
                DjLog.w(TAG, "lyrics $videoId: failed (${e.javaClass.simpleName}: ${e.message})")
            }
        }
    }

    private suspend fun load(id: String) {
        val now = clock()
        val cached = readFile(id)
        val sheet =
            when {
                cached?.lines != null -> cached
                cached != null && (cached.retryAtEpochMs ?: 0L) > now -> {
                    DjLog.i(TAG, "lyrics $id: none (cached; retry after ${(cached.retryAtEpochMs!! - now) / 3_600_000} h)")
                    return
                }
                else -> fetch(id) ?: return
            }
        val analysis = analysisOf(id)
        validate(id, sheet, analysis)
        if (analysis?.vocalProfile == null) {
            // the detector's evidence is what aligns the lyrics with this audio: re-validate once the full analysis lands
            withTimeoutOrNull(REVALIDATE_WAIT_MS) {
                val full = analysisUpdates(id).filterNotNull().first { it.vocalProfile != null }
                validate(id, sheet, full)
            }
        }
    }

    /** Fetches the lines for [id] and writes them (or "no lyrics") to the cache; null when there are none. */
    private suspend fun fetch(id: String): LyricsFile? {
        val song = withTimeoutOrNull(FETCH_TIMEOUT_MS) { songs.getSongById(id).firstOrNull() }
        val durationMs = (song?.durationSeconds ?: 0) * 1000L
        var answered = 0
        var timedOut = 0

        suspend fun attempt(provider: String, flow: () -> Flow<Resource<Lyrics>>): LyricsFile? {
            val r = withTimeoutOrNull(FETCH_TIMEOUT_MS) { flow().firstOrNull() }
            if (r == null) {
                timedOut++
                return null
            }
            answered++
            val data = (if (r is Resource.Success) r.data else null) ?: return null
            val lines = toLines(data.lines, data.syncType) ?: return null
            return LyricsFile(id, provider, data.syncType, lines, durationMs, clock())
        }

        // 1. the app's own cache (what the lyrics screen showed for this track)
        val saved = withTimeoutOrNull(FETCH_TIMEOUT_MS) { lyrics.getSavedLyrics(id).firstOrNull() }
        val fromCache = saved?.takeIf { !it.error }?.let { e -> toLines(e.lines, e.syncType)?.let { LyricsFile(id, "cache", e.syncType, it, durationMs, clock()) } }
        val got =
            fromCache
                ?: attempt("simpmusic") { lyrics.getSimpMusicLyrics(id) }
                ?: song?.let { s ->
                    val artist = artistOf(s)
                    val seconds = s.durationSeconds.takeIf { it > 0 }
                    attempt("betterlyrics") { lyrics.getBetterLyrics(artist, s.title, seconds) }
                        ?: attempt("lrclib") { lyrics.getLrclibLyricsData(artist, s.title, seconds) }
                }
        if (got != null) {
            writeFile(got)
            return got
        }
        // "no lyrics" only when the providers answered (a timeout is the network, not an answer: retried next process)
        // and the song is in the library (a track whose row lands a moment after it starts must not be "no lyrics" for a week)
        if (timedOut == 0 && answered > 0 && song != null) {
            writeFile(LyricsFile(id, null, null, null, durationMs, clock(), retryAtEpochMs = clock() + NO_LYRICS_RETRY_MS))
        }
        DjLog.i(TAG, "lyrics $id: none (${if (song == null) "song not in the library; only SimpMusic asked" else "$answered providers answered, $timedOut timed out"})")
        return null
    }

    private fun validate(id: String, sheet: LyricsFile, analysis: TrackAnalysis?) {
        val lines = sheet.lines ?: return
        val duration = analysis?.durationMs?.takeIf { it > 0 } ?: sheet.durationMs
        val built = LyricVocals.build(lines, sheet.provider ?: "?", duration, analysis)
        val v = built.vocals
        val before = LyricVocalsCache.get(id)
        LyricVocalsCache.put(id, v)
        DjLog.i(TAG, summary(id, sheet, built, analysis, duration))
        val changed = (before?.takeIf { it.trusted }?.ranges) != (v?.takeIf { it.trusted }?.ranges)
        if (changed) onChanged?.invoke(id)
    }

    private fun summary(id: String, sheet: LyricsFile, built: LyricVocals.Built, analysis: TrackAnalysis?, duration: Long): String {
        val v = built.vocals
        val lines = sheet.lines?.size ?: 0
        val head = "lyrics $id: ${sheet.provider} (${sheet.syncType}), $lines lines"
        if (v == null) return "$head: rejected (${built.rejected})"
        val det =
            when {
                analysis == null -> "no analysis yet"
                analysis.vocals == null -> "no detection"
                TrackTimeline.trustedVocals(analysis) == null -> "untrusted detection"
                else -> "trusted detection"
            }
        val detCoverage =
            analysis?.let { a ->
                val r = a.vocals?.value
                if (r != null && a.durationMs > 0) " " + fmt("%.0f", 100.0 * r.sumOf { it.durationMs } / a.durationMs) + "%" else null
            } ?: ""
        val use =
            if (v.trusted) {
                "USED, replaces the detector ($det$detCoverage)"
            } else {
                "not used (c${fmt("%.2f", v.confidence)} below ${fmt("%.2f", LyricVocals.MIN_CONFIDENCE)}; the detector keeps it: $det$detCoverage)"
            }
        // formatted piece by piece: the note and the coverage carry '%', which must never reach a format string
        return head + ": " + (if (v.wordLevel) "word-level" else "line-level") +
            ", offset " + fmt("%+.1f", v.offsetMs / 1000.0) + " s, c" + fmt("%.2f", v.confidence) +
            ", vocal coverage " + fmt("%.0f", v.coverage(duration) * 100.0) + "%, " + v.ranges.size + " ranges; " + use + " [" + v.note + "]"
    }

    private fun fmt(pattern: String, value: Any): String = String.format(java.util.Locale.ROOT, pattern, value)

    private fun fileFor(id: String) = File(dir, id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    private fun readFile(id: String): LyricsFile? =
        try {
            fileFor(id).takeIf { it.isFile }?.readText()?.let { json.decodeFromString(LyricsFile.serializer(), it) }?.takeIf { it.videoId == id }
        } catch (e: Exception) {
            null
        }

    private fun writeFile(f: LyricsFile) {
        try {
            val target = fileFor(f.videoId)
            val tmp = File(dir, target.name + ".tmp")
            tmp.writeText(json.encodeToString(LyricsFile.serializer(), f))
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        } catch (e: Exception) {
            DjLog.w(TAG, "lyrics ${f.videoId}: cache write failed (${e.message})")
        }
    }

    companion object {
        private const val TAG = "lyrics"

        /** "No lyrics" is asked again after a week (providers gain lyrics over time). */
        const val NO_LYRICS_RETRY_MS = 7L * 24 * 3_600_000

        private const val FETCH_TIMEOUT_MS = 20_000L

        /** How long a track waits for its full analysis (the detector's profile) to re-validate its lyrics. */
        private const val REVALIDATE_WAIT_MS = 15 * 60_000L

        private val WORD_TIME = Regex("""<(\d{1,3}):(\d{2})\.(\d{2,3})>""")
        private val SPACES = Regex("\\s+")

        /** The app's artist choice for a lyrics search (`SharedViewModel.getLyricsFromFormat`): the first, unless "Various Artists". */
        private fun artistOf(s: SongEntity): String = s.artistName?.firstOrNull()?.takeIf { !it.contains("Various Artists") }.orEmpty()

        /**
         * The app's lyric lines as the brain's [LyricLine]s: line start (and end, when the provider gives one), the word
         * starts of rich sync (`<mm:ss.xx>` markers in the words), the bare text. Null for unsynced lyrics.
         */
        internal fun toLines(lines: List<Line>?, syncType: String?): List<LyricLine>? {
            if (lines.isNullOrEmpty() || (syncType != "LINE_SYNCED" && syncType != "RICH_SYNCED")) return null
            val out =
                lines.mapNotNull { l ->
                    val start = l.startTimeMs.trim().toLongOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
                    val end = l.endTimeMs.trim().toLongOrNull()?.takeIf { it > start }
                    val words =
                        WORD_TIME.findAll(l.words).mapNotNull { m ->
                            val min = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                            val sec = m.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                            val frac = m.groupValues[3]
                            val ms = (frac.toLongOrNull() ?: 0L) * (if (frac.length == 2) 10 else 1)
                            min * 60_000 + sec * 1000 + ms
                        }.toList()
                    LyricLine(start, end, words, l.words.replace(WORD_TIME, " ").replace(SPACES, " ").trim())
                }
            return out.takeIf { it.isNotEmpty() }
        }
    }
}

/** What the DJ keeps of a track's lyrics on disk: the lines (or "none, retry at"), not the validated vocals. */
@Serializable
internal data class LyricsFile(
    val videoId: String,
    /** "cache" (the app's own), "simpmusic", "betterlyrics", "lrclib"; null for "no lyrics". */
    val provider: String? = null,
    val syncType: String? = null,
    val lines: List<LyricLine>? = null,
    /** The track's duration as the library knows it (0 = unknown), for validating before the analysis exists. */
    val durationMs: Long = 0,
    val fetchedAtEpochMs: Long = 0,
    /** For "no lyrics": when to ask the providers again. */
    val retryAtEpochMs: Long? = null,
)
