package org.simpmusic.dj.android.preview

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import org.simpmusic.dj.android.log.DjLog
import java.io.File

/**
 * Plays a rendered preview of the prepared mix (the owner's "how do I hear it while I edit?"): the main playback is
 * paused, the WAV plays on its own, and when it ends or is stopped the main playback goes on where it was. The queue and
 * the prepared mix are not touched, so the real mix still happens when the song reaches it.
 */
interface MixPreviewPlayer {
    /** Starts [file]; [onEnd] runs on the main thread when it finishes or is stopped. */
    fun play(file: File, onEnd: () -> Unit)

    fun stop()
}

class AndroidMixPreviewPlayer(
    /** Pauses the app's playback; true when it was playing (so it is resumed after the preview). */
    private val pauseMain: () -> Boolean,
    private val resumeMain: () -> Unit,
) : MixPreviewPlayer {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var wasPlaying = false
    private var onEnd: (() -> Unit)? = null

    override fun play(file: File, onEnd: () -> Unit) {
        main.post {
            finish(resume = false)
            this.onEnd = onEnd
            wasPlaying = runCatching { pauseMain() }.getOrDefault(false)
            val mp = MediaPlayer()
            player = mp
            try {
                mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                mp.setDataSource(file.path)
                mp.setOnCompletionListener { finish(resume = true) }
                mp.setOnErrorListener { _, what, extra ->
                    DjLog.w(TAG, "preview player error $what/$extra")
                    finish(resume = true)
                    true
                }
                mp.prepare()
                mp.start()
                DjLog.i(TAG, "preview playing ${file.name} (${mp.duration} ms), main playback ${if (wasPlaying) "paused" else "was not playing"}")
            } catch (e: Exception) {
                DjLog.w(TAG, "preview could not play: ${e.message}")
                finish(resume = true)
            }
        }
    }

    override fun stop() {
        main.post { finish(resume = true) }
    }

    private fun finish(resume: Boolean) {
        val mp = player ?: return
        player = null
        runCatching { mp.stop() }
        runCatching { mp.release() }
        if (resume && wasPlaying) runCatching { resumeMain() }
        wasPlaying = false
        onEnd?.invoke()
        onEnd = null
    }

    private companion object {
        const val TAG = "preview"
    }
}
