@file:OptIn(UnstableApi::class)

package org.simpmusic.dj.android.bus

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import org.simpmusic.dj.android.log.DjLog
import org.simpmusic.dj.bus.DeckRing
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArraySet

/**
 * One per ExoPlayer: routes the player's `DefaultAudioSink` into its deck on the [AndroidMixBus] instead of an AudioTrack.
 *
 * The deck outlives the outputs: Media3 1.11.1 releases its `AudioOutput` and builds a new one on every flush (a seek)
 * and format change, so each [getAudioOutput] starts a new stream in the SAME deck (the transition runner keeps the deck
 * id across the incoming player's seek). The player's media must reach the bus at its rate: the core patch makes the
 * sink's Sonic processor resample to [AndroidMixBus.SAMPLE_RATE].
 */
class BusAudioOutputProvider(private val bus: AndroidMixBus) : AudioOutputProvider {
    @Volatile var deckId: Int = bus.addDeck()
        private set
    @Volatile private var deckRemoved = false

    /** Channel count of the format the sink asked for last ([getOutputConfig] always precedes [getAudioOutput]). */
    @Volatile private var pendingChannels = 2

    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport {
        val f = formatConfig.format
        val pcm = MimeTypes.AUDIO_RAW == f.sampleMimeType && (f.pcmEncoding == C.ENCODING_PCM_16BIT || f.pcmEncoding == C.ENCODING_PCM_FLOAT)
        return if (pcm) {
            AudioOutputProvider.FormatSupport.Builder().setFormatSupportLevel(AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY).build()
        } else {
            AudioOutputProvider.FormatSupport.UNSUPPORTED
        }
    }

    override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig {
        val f = formatConfig.format
        if (f.sampleMimeType != MimeTypes.AUDIO_RAW) throw AudioOutputProvider.ConfigurationException("the DJ bus takes PCM only (${f.sampleMimeType})")
        pendingChannels = f.channelCount.coerceAtLeast(1)
        return AudioOutputProvider.OutputConfig.Builder()
            .setEncoding(f.pcmEncoding)
            .setSampleRate(f.sampleRate)
            .setChannelMask(if (f.channelCount == 1) android.media.AudioFormat.CHANNEL_OUT_MONO else android.media.AudioFormat.CHANNEL_OUT_STEREO)
            .setAudioAttributes(formatConfig.audioAttributes)
            .setAudioSessionId(bus.audioSessionId)
            .setBufferSize(AndroidMixBus.RING_SECONDS * f.sampleRate.coerceAtLeast(1) * 4)
            .setUsePlaybackParameters(false)
            .build()
    }

    override fun getAudioOutput(outputConfig: AudioOutputProvider.OutputConfig): AudioOutput {
        if (deckRemoved) {
            deckId = bus.addDeck()
            deckRemoved = false
        }
        val channels = pendingChannels
        val encoding = if (outputConfig.encoding == C.ENCODING_PCM_FLOAT) DeckRing.Encoding.PCM_FLOAT else DeckRing.Encoding.PCM_16
        val ring = bus.core.ring(deckId)
        try {
            ring.flush()
            ring.configure(outputConfig.sampleRate, channels, encoding)
        } catch (e: IllegalArgumentException) {
            DjLog.e("bus", "deck $deckId: ${e.message}")
            throw AudioOutputProvider.InitializationException(e)
        }
        return BusAudioOutput(bus, deckId, outputConfig.sampleRate)
    }

    override fun addListener(listener: AudioOutputProvider.Listener) {}

    override fun removeListener(listener: AudioOutputProvider.Listener) {}

    /** The player is released: its deck goes (unless a transition still holds it; then on the next output). */
    override fun release() {
        try {
            bus.pause(deckId)
            bus.core.removeDeck(deckId)
            deckRemoved = true
        } catch (e: Exception) {
            DjLog.w("bus", "deck $deckId kept: ${e.message}")
        }
    }
}

/** One Media3 output stream into a deck: written by the player's playback thread. */
private class BusAudioOutput(private val bus: AndroidMixBus, private val deckId: Int, private val sampleRate: Int) : AudioOutput {
    private val ring = bus.core.ring(deckId)
    private val listeners = CopyOnWriteArraySet<AudioOutput.Listener>()
    @Volatile private var released = false
    @Volatile private var playing = false
    @Volatile private var advancingReported = false
    @Volatile private var params = PlaybackParameters.DEFAULT

    override fun play() {
        if (released) return
        playing = true
        bus.play(deckId)
        if (!advancingReported) {
            advancingReported = true
            val t = System.currentTimeMillis()
            listeners.forEach { it.onPositionAdvancing(t) }
        }
    }

    override fun pause() {
        playing = false
        if (!released) bus.pause(deckId)
    }

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        if (released) return true
        ring.write(buffer, presentationTimeUs)
        return !buffer.hasRemaining()
    }

    override fun flush() {
        if (!released) ring.flush()
        advancingReported = false
    }

    override fun stop() {
        if (!released) ring.endOfStream()
    }

    override fun release() {
        if (released) return
        released = true
        playing = false
        try {
            bus.pause(deckId)
            ring.flush()
        } catch (_: Exception) {
        }
        listeners.forEach { it.onReleased() }
    }

    override fun setVolume(volume: Float) {
        if (!released) bus.core.setVolume(deckId, volume)
    }

    override fun isOffloadedPlayback(): Boolean = false

    override fun getAudioSessionId(): Int = bus.audioSessionId

    override fun getSampleRate(): Int = sampleRate

    override fun getBufferSizeInFrames(): Long = AndroidMixBus.RING_SECONDS.toLong() * sampleRate

    /** The deck's frames already HEARD (bus frame at the speaker mapped to the deck), as Media3's playout position. */
    override fun getPositionUs(): Long {
        if (released) return 0L
        val frames = try {
            bus.core.deckConsumedFramesAt(deckId, bus.playedFrame())
        } catch (_: Exception) {
            0L
        }
        return frames.coerceAtLeast(0L) * 1_000_000L / sampleRate
    }

    override fun getPlaybackParameters(): PlaybackParameters = params

    override fun isStalled(): Boolean = false

    override fun addListener(listener: AudioOutput.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: AudioOutput.Listener) {
        listeners -= listener
    }

    /** Speed and pitch run in the sink's Sonic processor (`usePlaybackParameters = false`): nothing to do here. */
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        params = playbackParameters
    }

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {}

    override fun setOffloadEndOfStream() {}

    override fun attachAuxEffect(effectId: Int) {}

    override fun setAuxEffectSendLevel(level: Float) {}

    override fun setPreferredDevice(preferredDevice: android.media.AudioDeviceInfo?) {}
}
