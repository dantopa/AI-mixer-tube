package org.simpmusic.dj.android.recommend

import com.maxrave.domain.data.model.browse.album.Track
import kotlinx.coroutines.flow.Flow

enum class RepeatKind { OFF, ONE, ALL }

/** What the DJ needs to know about the player and its queue at one instant. */
data class PlayerSnapshot(
    val currentId: String? = null,
    /** The whole queue in playing order (played + current + upcoming). */
    val queueIds: List<String> = emptyList(),
    /** Index of the current track in [queueIds], -1 when unknown. */
    val currentIndex: Int = -1,
    val repeat: RepeatKind = RepeatKind.OFF,
    val shuffle: Boolean = false,
    val casting: Boolean = false,
    /** A Listen Together room is driving playback (the player reports it through `crossfadeSuppressed`). */
    val listenTogether: Boolean = false,
    /** The current track is played as video. */
    val currentIsVideo: Boolean = false,
    /** The queue is a real YouTube radio (`RD…` id, type RADIO): it is extended by the app's endless queue. */
    val isRadio: Boolean = false,
) {
    /** Tracks still to come after the current one. */
    val tracksAhead: Int get() = if (currentIndex < 0) 0 else (queueIds.size - currentIndex - 1).coerceAtLeast(0)

    /** Ids already played in this queue, nearest first. */
    fun playedBefore(limit: Int): List<String> = if (currentIndex <= 0) emptyList() else queueIds.subList(0, currentIndex).asReversed().take(limit)

    /**
     * Why Auto DJ must sit this out, or null. The same guards as the transition engine (casting, listen together,
     * repeat one, video) plus the two that make a queue endless or unordered by themselves.
     */
    val blockedReason: String?
        get() =
            when {
                casting -> "casting"
                listenTogether -> "listen together"
                repeat == RepeatKind.ONE -> "repeat one"
                repeat == RepeatKind.ALL -> "repeat all"
                shuffle -> "shuffle"
                currentIsVideo -> "video"
                else -> null
            }
}

/** The DJ's window onto the player. Production: the app's `MediaPlayerHandler`; tests: a fake. */
interface DjPlayerPort {
    /** Emits whenever the current track, the queue or a guard changes. */
    val snapshots: Flow<PlayerSnapshot>

    fun snapshot(): PlayerSnapshot

    /** Inserts [track] right after the current one. */
    suspend fun playNext(track: Track)

    /** Adds [track] at the end of the queue. */
    suspend fun append(track: Track)

    /** Inserts [track] after the current one and skips to it. */
    suspend fun playNow(track: Track)

    /**
     * Moves the queued track [videoId] (somewhere after the current one) into the slot right after the current track,
     * shifting the ones in between back by one. Nothing is added or removed. False when it was not possible.
     */
    suspend fun moveToNext(videoId: String): Boolean = false
}
