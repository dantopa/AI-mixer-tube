package org.simpmusic.dj.android.library

import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.utils.MusicVideoType

/** A song of the user's library as the DJ needs it: enough to show it, filter it and enqueue it. */
data class LibraryTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Int,
    /** YouTube's `MUSIC_VIDEO_TYPE_*` as stored on the song row (older builds stored invented labels), or null. */
    val videoType: String?,
    /** The app's own model of the song, what the player's "play next" / "add to queue" take. */
    val track: Track,
)

/** Where a candidate came from, in PRIORITY order: a lower ordinal is analysed first. */
enum class CandidateSource { LIKED, DOWNLOADED, MOST_PLAYED, RECENT }

data class LibraryCandidate(
    val track: LibraryTrack,
    val source: CandidateSource,
    /** Fully downloaded: its audio is in the download cache, so analysing it needs no network at all. */
    val downloaded: Boolean,
)

/** The app's library seen through the domain repositories; the only thing the DJ code knows about Room. */
interface LibrarySource {
    /**
     * Raw candidates, unfiltered, in priority order (liked, downloaded, most played, recently played). May contain one
     * song several times (a liked AND downloaded song): [CandidateSelection.select] keeps the first.
     */
    suspend fun candidates(): List<LibraryCandidate>

    /** One song by id, or null when the app has no row for it. */
    suspend fun find(videoId: String): LibraryTrack?

    /** The last [limit] played song ids, newest first. */
    suspend fun recentlyPlayedIds(limit: Int): List<String>
}

/** Which raw candidates the DJ works with. Pure: the unit tests pin every rule. */
object CandidateSelection {
    /** A track shorter than this is a jingle or a snippet, not something to mix. */
    const val MIN_DURATION_SECONDS = 60

    /**
     * A track longer than this is a DJ mix, a podcast or a compilation. Also a memory bound: the analysis decodes the
     * whole track to mono 22.05 kHz floats, 9 minutes = 48 MB.
     */
    const val MAX_DURATION_SECONDS = 9 * 60

    const val DEFAULT_CAP = 300

    /** True when the DJ may analyse and recommend [t]: not a known video, not a podcast episode, sane duration. */
    fun isEligible(t: LibraryTrack): Boolean {
        if (t.videoId.isBlank()) return false
        if (MusicVideoType.isVideoSong(t.videoType) || MusicVideoType.isPodcast(t.videoType)) return false
        // 0 = unknown duration: let it through, the decoder knows better.
        if (t.durationSeconds in 1 until MIN_DURATION_SECONDS) return false
        if (t.durationSeconds > MAX_DURATION_SECONDS) return false
        return true
    }

    /**
     * Deduplicates by id keeping the FIRST occurrence (= highest priority), sorts stably by [CandidateSource] (the input is
     * only trusted to be ordered inside a source), drops what is not eligible and cuts at [cap].
     */
    fun select(raw: List<LibraryCandidate>, cap: Int = DEFAULT_CAP): List<LibraryCandidate> {
        val seen = HashSet<String>()
        return raw
            .sortedBy { it.source.ordinal }
            .filter { isEligible(it.track) && seen.add(it.track.videoId) }
            .take(cap)
    }
}
