package org.simpmusic.dj.android.library

import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.toTrack
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull

/** [LibrarySource] over the domain [SongRepository] (liked, downloaded, most played and recent songs). */
class DomainLibrarySource(
    private val songs: SongRepository,
    private val recentPool: Int = 300,
) : LibrarySource {
    override suspend fun candidates(): List<LibraryCandidate> {
        val liked = songs.getLikedSongs().first()
        val downloaded = songs.getDownloadedSongs().firstOrNull().orEmpty().filter { it.downloadState == DownloadState.STATE_DOWNLOADED }
        val mostPlayed = songs.getMostPlayedSongs().first()
        val recent = songs.getRecentSong(recentPool, 0)
        val downloadedIds = downloaded.mapTo(HashSet()) { it.videoId }

        fun of(list: List<SongEntity>, source: CandidateSource) = list.map { LibraryCandidate(it.toLibraryTrack(), source, it.videoId in downloadedIds) }
        return of(liked, CandidateSource.LIKED) +
            of(downloaded, CandidateSource.DOWNLOADED) +
            of(mostPlayed, CandidateSource.MOST_PLAYED) +
            of(recent, CandidateSource.RECENT)
    }

    override suspend fun find(videoId: String): LibraryTrack? = songs.getSongById(videoId).firstOrNull()?.toLibraryTrack()

    override suspend fun recentlyPlayedIds(limit: Int): List<String> = songs.getRecentSong(limit, 0).map { it.videoId }
}

fun SongEntity.toLibraryTrack(): LibraryTrack =
    LibraryTrack(
        videoId = videoId,
        title = title,
        artist = artistName?.joinToString(", ").orEmpty(),
        album = albumName,
        thumbnailUrl = thumbnails,
        durationSeconds = durationSeconds,
        videoType = videoType,
        // toTrack() indexes artistId by artistName position and throws on a row with fewer ids than names.
        track = runCatching { toTrack() }.getOrElse { copy(artistId = null, artistName = null).toTrack().withoutArtists(artistName) },
    )

private fun Track.withoutArtists(names: List<String>?): Track =
    copy(artists = names?.map { com.maxrave.domain.data.model.searchResult.songs.Artist(null, it) })
