package org.simpmusic.dj.android

import com.maxrave.domain.data.model.browse.album.Track
import org.simpmusic.dj.android.library.CandidateSource
import org.simpmusic.dj.android.library.LibraryCandidate
import org.simpmusic.dj.android.library.LibrarySource
import org.simpmusic.dj.android.library.LibraryTrack
import org.simpmusic.dj.model.Confident
import org.simpmusic.dj.model.MusicalKey
import org.simpmusic.dj.model.Mode
import org.simpmusic.dj.model.TrackAnalysis

const val ATV = "MUSIC_VIDEO_TYPE_ATV"
const val UGC = "MUSIC_VIDEO_TYPE_UGC"

fun libTrack(id: String, durationSeconds: Int = 200, videoType: String? = ATV, title: String = "Title $id"): LibraryTrack =
    LibraryTrack(
        videoId = id,
        title = title,
        artist = "Artist",
        album = null,
        thumbnailUrl = null,
        durationSeconds = durationSeconds,
        videoType = videoType,
        track =
            Track(
                album = null,
                artists = null,
                duration = null,
                durationSeconds = durationSeconds,
                isAvailable = true,
                isExplicit = false,
                likeStatus = null,
                thumbnails = null,
                title = title,
                videoId = id,
                videoType = videoType,
                category = null,
                feedbackTokens = null,
                resultType = null,
            ),
    )

fun cand(id: String, source: CandidateSource, downloaded: Boolean = false, durationSeconds: Int = 200, videoType: String? = ATV) =
    LibraryCandidate(libTrack(id, durationSeconds, videoType), source, downloaded)

class FakeLibrarySource(
    var candidates: List<LibraryCandidate> = emptyList(),
    var recent: List<String> = emptyList(),
) : LibrarySource {
    override suspend fun candidates(): List<LibraryCandidate> = candidates

    override suspend fun find(videoId: String): LibraryTrack? = candidates.firstOrNull { it.track.videoId == videoId }?.track

    override suspend fun recentlyPlayedIds(limit: Int): List<String> = recent.take(limit)
}

/** A track analysis with an energy shape, a key and a tempo: what the recommender compares. */
fun analysis(
    id: String,
    bpm: Float = 124f,
    key: MusicalKey = MusicalKey(9, Mode.MINOR),
    introEnergy: Float = 0.5f,
    outroEnergy: Float = 0.5f,
    analyzerId: String = "fake-1",
): TrackAnalysis {
    val n = 300
    val energy = List(n) { i -> if (i < n / 5) introEnergy else if (i >= n - n / 5) outroEnergy else 0.8f }
    return TrackAnalysis(
        videoId = id,
        analyzerId = analyzerId,
        analyzedAtEpochMs = 0,
        durationMs = 240_000,
        bpm = Confident(bpm, 0.9f),
        beatTimesMs = null,
        downbeatBeatIndices = null,
        key = Confident(key, 0.9f),
        energyHopMs = 800,
        energy = energy,
        lowBandEnergy = energy,
        sections = null,
        vocals = null,
        loudnessDb = -14f,
        timbre = List(13) { 0.1f * it },
    )
}
