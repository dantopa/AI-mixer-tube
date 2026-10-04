package org.simpmusic.dj.android.auto

import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.extension.isVideo
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.handler.RadioQueueTrim
import com.maxrave.domain.mediaservice.handler.RepeatState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.simpmusic.dj.android.recommend.DjPlayerPort
import org.simpmusic.dj.android.recommend.PlayerSnapshot
import org.simpmusic.dj.android.recommend.RepeatKind

/**
 * [DjPlayerPort] over the app's [MediaPlayerHandler]. The handler is resolved lazily ([handler]): the DJ module is created
 * while the player itself is still being built, and must not ask for it before it exists.
 *
 * The DJ only ever goes through the handler's own "play next" / "add to queue" (`playNext`, `loadMoreCatalog(isAddToQueue)`),
 * the calls the queue UI makes, so every queue invariant (radio trim, shuffle order, precache, queue persistence) is kept.
 */
class HandlerPlayerPort(
    private val handler: () -> MediaPlayerHandler,
    private val dataStore: DataStoreManager,
) : DjPlayerPort {
    /** The "watch video instead of playing audio" setting, kept current by [snapshots] (false until it is collected). */
    @Volatile private var watchVideo = false

    override val snapshots: Flow<PlayerSnapshot> =
        flow {
            val h = handler()
            emitAll(
                combine(h.nowPlaying, h.queueData, h.controlState, h.castState, dataStore.watchVideoInsteadOfPlayingAudio) { _, _, _, _, watch ->
                    watchVideo = watch == DataStoreManager.TRUE
                }
                    .map { snapshotOf(h) },
            )
        }

    override fun snapshot(): PlayerSnapshot = snapshotOf(handler())

    private fun snapshotOf(h: MediaPlayerHandler): PlayerSnapshot {
        val queue = h.queueData.value?.data ?: QueueData.Data()
        val ids = queue.listTracks.map { it.videoId }
        val current = h.nowPlaying.value?.mediaId?.removePrefix(MERGING_DATA_TYPE.VIDEO)
        var index = h.currentOrderIndex()
        if (ids.getOrNull(index) != current) index = ids.indexOf(current) // the handler's index and the list disagree: trust the id
        val control = h.controlState.value
        return PlayerSnapshot(
            currentId = current,
            queueIds = ids,
            currentIndex = index,
            repeat =
                when (control.repeatState) {
                    RepeatState.One -> RepeatKind.ONE
                    RepeatState.All -> RepeatKind.ALL
                    RepeatState.None -> RepeatKind.OFF
                },
            shuffle = control.isShuffle,
            casting = h.castState.value.isRemote,
            listenTogether = h.player.crossfadeSuppressed,
            currentIsVideo = watchVideo && h.nowPlaying.value?.isVideo() == true,
            isRadio = RadioQueueTrim.appliesTo(queue.playlistType, queue.playlistId),
        )
    }

    override suspend fun playNext(track: Track) = handler().playNext(track)

    override suspend fun append(track: Track) = handler().loadMoreCatalog(arrayListOf(track), isAddToQueue = true)

    override suspend fun playNow(track: Track) {
        val h = handler()
        h.playNext(track)
        h.onPlayerEvent(PlayerEvent.Next)
    }

    override suspend fun moveToNext(videoId: String): Boolean {
        val h = handler()
        val s = snapshotOf(h)
        if (s.blockedReason != null || s.currentIndex < 0) return false
        val target = s.currentIndex + 1
        var pos = s.queueIds.drop(target).indexOf(videoId).let { if (it < 0) -1 else it + target }
        if (pos < target) return false
        while (pos > target) {
            // One step at a time through the handler's own move, re-checking that the queue still is what we planned on.
            val ids = h.queueData.value?.data?.listTracks?.map { it.videoId } ?: return false
            if (ids.getOrNull(pos) != videoId) return false
            h.moveItemUp(pos)
            pos--
        }
        return true
    }
}
