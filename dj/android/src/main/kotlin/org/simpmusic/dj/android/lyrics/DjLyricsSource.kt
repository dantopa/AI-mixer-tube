package org.simpmusic.dj.android.lyrics

import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.repository.LyricsCanvasRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
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
import org.simpmusic.dj.timeline.LyricQuery
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
 * (SimpMusic by video id, BetterLyrics, LRCLIB by the cleaned name and the video captions, all asked at once). The lines are cached per track as JSON in
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
    /**
     * Title, artist and length of a track from the player's queue, for a radio track not in the library (no song row):
     * without it only SimpMusic (by video id) could be asked, and most radio tracks came back without lyrics.
     */
    private val queuedMeta: (String) -> QueuedMeta? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    /** LRCLIB asked by the cleaned name and judged by title, artist and length (the app's own search takes the first hit). */
    private val lrclib: LrclibClient = LrclibClient(),
) {
    /** What the queue knows of a track: enough to ask BetterLyrics and LRCLIB by name. */
    class QueuedMeta(val title: String, val artist: String, val durationSeconds: Int?)

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

    /**
     * The stored lyric lines of [videoId] on the TRACK's clock (the offset the validation found applied), for "AI Mix"; null
     * when there are none. Blocking file read: call it off the main thread.
     */
    fun linesOnTrack(videoId: String): List<org.simpmusic.dj.timeline.LyricLine>? {
        val lines = readFile(videoId)?.lines ?: return null
        val offset = LyricVocalsCache.get(videoId)?.offsetMs ?: 0L
        return lines.map { it.copy(startMs = it.startMs + offset, endMs = it.endMs?.plus(offset), wordStartsMs = it.wordStartsMs.map { w -> w + offset }) }
    }

    private suspend fun load(id: String) {
        val now = clock()
        val cached = readFile(id)
        val sheet =
            when {
                cached?.lines != null -> cached
                cached != null && (cached.retryAtEpochMs ?: 0L) > now && cached.searchVersion >= SEARCH_VERSION -> {
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

    /**
     * Fetches the lines for [id] and writes them (or "no lyrics") to the cache; null when there are none. Every provider is
     * asked AT ONCE (one timeout for all, not one per provider in a row: in a row, slow answers cost a minute and the track
     * had started before anything came back), then the first in [PRIORITY] that has synced lines wins.
     */
    private suspend fun fetch(id: String): LyricsFile? {
        val song = withTimeoutOrNull(FETCH_TIMEOUT_MS) { songs.getSongById(id).firstOrNull() }
        val queued = if (song == null) queuedMeta(id) else null
        val durationMs = ((song?.durationSeconds ?: queued?.durationSeconds) ?: 0) * 1000L

        // 1. the app's own cache (what the lyrics screen showed for this track)
        val saved = withTimeoutOrNull(FETCH_TIMEOUT_MS) { lyrics.getSavedLyrics(id).firstOrNull() }
        val fromCache = saved?.takeIf { !it.error }?.let { e -> toLines(e.lines, e.syncType)?.let { LyricsFile(id, "cache", e.syncType, it, durationMs, clock()) } }
        if (fromCache != null) {
            writeFile(fromCache)
            return fromCache
        }

        val named =
            song?.let { Triple(artistOf(it), it.title, it.durationSeconds.takeIf { d -> d > 0 }) }
                ?: queued?.takeIf { it.title.isNotBlank() }?.let { Triple(it.artist, it.title, it.durationSeconds?.takeIf { d -> d > 0 }) }
        val q = named?.let { LyricQuery.of(it.second, it.first) }

        val outcomes: Map<String, Outcome> =
            coroutineScope {
                val jobs =
                    buildList {
                        add(async { "simpmusic" to ask("simpmusic", id, durationMs) { lyrics.getSimpMusicLyrics(id) } })
                        if (named != null && q != null) {
                            val (artist, title, seconds) = named
                            add(async { "betterlyrics" to ask("betterlyrics", id, durationMs) { lyrics.getBetterLyrics(q.mainArtist.ifEmpty { artist }, q.title, seconds) } })
                            add(async { "lrclib" to askLrclib(id, title, artist, seconds, durationMs) })
                        }
                        add(async { "youtube" to askCaptions(id, durationMs) })
                    }
                jobs.awaitAll().toMap()
            }
        val got = PRIORITY.firstNotNullOfOrNull { outcomes[it]?.file }
        val answered = outcomes.values.count { it.answered }
        val timedOut = outcomes.values.count { !it.answered }
        val asked = q?.let { "asked as '${it.mainArtist} - ${it.title}'" } ?: "by video id only"
        if (got != null) {
            val found = outcomes.filterValues { it.file != null }.keys
            DjLog.i(TAG, "lyrics $id: using ${got.provider} (synced lines from ${found.joinToString(", ")}; $asked)")
            writeFile(got)
            return got
        }
        // "no lyrics" only when the providers answered (a timeout is the network, not an answer: retried next process)
        // and the song is in the library (a track whose row lands a moment after it starts must not be "no lyrics" for a week)
        if (timedOut == 0 && answered > 1 && (song != null || queued != null)) {
            writeFile(LyricsFile(id, null, null, null, durationMs, clock(), retryAtEpochMs = clock() + NO_LYRICS_RETRY_MS, searchVersion = SEARCH_VERSION))
        }
        DjLog.i(TAG, "lyrics $id: none (${if (song == null && queued == null) "not in the library nor the queue; only SimpMusic and YouTube asked" else "$answered providers answered, $timedOut timed out; $asked"})")
        return null
    }

    /** One provider's answer. [answered] false = it did not answer in time (the network, not "no lyrics"). */
    private class Outcome(val answered: Boolean, val file: LyricsFile?)

    private class Box<T>(val value: T)

    private suspend fun ask(provider: String, id: String, durationMs: Long, flow: () -> Flow<Resource<Lyrics>>): Outcome {
        val r = withTimeoutOrNull(FETCH_TIMEOUT_MS) { flow().firstOrNull() } ?: return Outcome(false, null)
        val data = (if (r is Resource.Success) r.data else null) ?: return Outcome(true, null)
        val lines = toLines(data.lines, data.syncType) ?: return Outcome(true, null)
        return Outcome(true, LyricsFile(id, provider, data.syncType, lines, durationMs, clock()))
    }

    private suspend fun askLrclib(id: String, title: String, artist: String, seconds: Int?, durationMs: Long): Outcome {
        val r = withTimeoutOrNull(FETCH_TIMEOUT_MS) { runInterruptible(Dispatchers.IO) { lrclib.find(title, artist, seconds) } } ?: return Outcome(false, null)
        DjLog.i(TAG, "lyrics $id: ${r.note}")
        return Outcome(r.answered, r.lines?.let { LyricsFile(id, "lrclib", "LINE_SYNCED", it, durationMs, clock()) })
    }

    /** YouTube's own caption track of the video (official videos often carry the words, sometimes automatic ones). */
    private suspend fun askCaptions(id: String, durationMs: Long): Outcome {
        // the repository's flow emits nothing when the request throws: an empty flow is an answer ("none"), not a timeout
        val r = withTimeoutOrNull(FETCH_TIMEOUT_MS) { Box(lyrics.getYouTubeCaption(CAPTION_LANG, id).firstOrNull()) } ?: return Outcome(false, null)
        val data = (r.value as? Resource.Success)?.data?.first ?: return Outcome(true, null)
        val lines = toLines(data.lines, data.syncType)?.takeIf { l -> l.count { !LyricVocals.isInstrumental(it.text) } >= MIN_CAPTION_LINES }
        return Outcome(true, lines?.let { LyricsFile(id, "youtube", data.syncType, it, durationMs, clock()) })
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

        /**
         * Bumped when the search finds lyrics it could not before: a "no lyrics" written by an older search is asked again
         * at once instead of waiting out its week. 1 = in a row, raw names; 2 = all at once, cleaned names, LRCLIB judged by
         * title, artist and length, the video captions.
         */
        const val SEARCH_VERSION = 2

        /**
         * Which provider wins when several have synced lines: SimpMusic is matched by video id (exact), BetterLyrics often has
         * word timing, LRCLIB is matched by name, the video's captions (often automatic) only say roughly where someone sings.
         */
        private val PRIORITY = listOf("simpmusic", "betterlyrics", "lrclib", "youtube")

        /** Caption language asked of YouTube (the owner's radio is Spanish; otherwise the video's first caption track). */
        private const val CAPTION_LANG = "es"

        /** A caption track with fewer sung lines is a credit or a title card, not the song's words. */
        private const val MIN_CAPTION_LINES = 6

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
    /** "cache" (the app's own), "simpmusic", "betterlyrics", "lrclib", "youtube"; null for "no lyrics". */
    val provider: String? = null,
    val syncType: String? = null,
    val lines: List<LyricLine>? = null,
    /** The track's duration as the library knows it (0 = unknown), for validating before the analysis exists. */
    val durationMs: Long = 0,
    val fetchedAtEpochMs: Long = 0,
    /** For "no lyrics": when to ask the providers again. */
    val retryAtEpochMs: Long? = null,
    /** The [DjLyricsSource.SEARCH_VERSION] that wrote a "no lyrics" (0 = before it existed). */
    val searchVersion: Int = 0,
)
