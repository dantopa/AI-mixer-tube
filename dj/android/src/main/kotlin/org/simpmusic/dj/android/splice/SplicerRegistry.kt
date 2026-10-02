@file:OptIn(UnstableApi::class)

package org.simpmusic.dj.android.splice

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.util.WeakHashMap

/**
 * One [WindowSplicerAudioProcessor] per player the adapter builds, and which player owns which. The window being
 * spliced is process-wide (one transition at a time), so every engine reads it from here.
 */
class SplicerRegistry {
    private val engines = WeakHashMap<ExoPlayer, SpliceEngine>()

    /** The window of the transition being executed (set by the runner before it arms any engine). */
    @Volatile
    var currentWindow: WindowSamples? = null

    fun create(): AudioProcessor = WindowSplicerAudioProcessor(SpliceEngine(window = { currentWindow }))

    fun bind(
        player: ExoPlayer,
        processor: AudioProcessor,
    ) {
        val engine = (processor as? WindowSplicerAudioProcessor)?.engine ?: return
        synchronized(engines) { engines[player] = engine }
    }

    fun engineOf(player: ExoPlayer): SpliceEngine? = synchronized(engines) { engines[player] }
}
