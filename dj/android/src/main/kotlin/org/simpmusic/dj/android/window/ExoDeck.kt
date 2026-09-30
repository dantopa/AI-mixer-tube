@file:OptIn(UnstableApi::class)

package org.simpmusic.dj.android.window

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.wav.WavExtractor
import java.io.File

/**
 * [Deck] over an [ExoPlayer]. Every call must come from the player's application thread (the adapter's
 * main-thread scope), exactly like any other ExoPlayer use in the app.
 *
 * Position signal: `ExoPlayer.getCurrentPosition()` while playing is `renderer position + (SystemClock delta
 * since the renderer last reported) * speed`, and the renderer position comes from
 * `DefaultAudioSink.getCurrentPositionUs`, i.e. from AudioTrack's playhead / timestamp poller. So it is
 * continuous but only as accurate as AudioTrack timestamp updates; [TransitionController] therefore feeds it
 * through a [PositionEstimator] (median of position - t) instead of trusting single readings.
 * Both hand-offs compare two players on the SAME output path and configuration, so their common latency
 * offset cancels; what remains is their relative timestamp jitter, which is what the estimator averages out.
 */
class ExoDeck(
    override val name: String,
    private val player: ExoPlayer,
) : Deck {
    private var released = false

    init {
        // A hard seek must land on the sample the controller asked for, not the previous keyframe.
        player.setSeekParameters(SeekParameters.EXACT)
    }

    override var volume: Float
        get() = player.volume
        set(value) {
            if (!released) player.volume = value.coerceIn(0f, 1f)
        }

    override val isReady: Boolean get() = !released && player.playbackState == Player.STATE_READY

    override val isPlaying: Boolean get() = !released && player.isPlaying

    override fun positionMs(): Double = if (released) 0.0 else player.currentPosition.toDouble()

    override fun seekTo(ms: Long) {
        if (!released) player.seekTo(ms)
    }

    override fun play() {
        if (!released) player.playWhenReady = true
    }

    override fun pause() {
        if (!released) player.playWhenReady = false
    }

    override fun release() {
        if (released) return
        released = true
        try {
            player.stop()
            player.release()
        } catch (_: Exception) {
        }
    }
}

/** MediaSource for a rendered window WAV: a plain progressive read of a local file, WAV extractor only. */
object WindowMediaSource {
    /**
     * @param metadata the INCOMING track's media item, so the session/notification shows its title while the
     *   window is the audible player. Only its metadata is used; its URI is replaced by the window file.
     */
    fun create(file: File, metadata: MediaItem): MediaSource {
        val extractors = ExtractorsFactory { arrayOf(WavExtractor()) }
        val item = metadata.buildUpon().setUri(Uri.fromFile(file)).build()
        return ProgressiveMediaSource.Factory(FileDataSource.Factory(), extractors).createMediaSource(item)
    }
}
