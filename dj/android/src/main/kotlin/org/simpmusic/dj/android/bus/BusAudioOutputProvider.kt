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

    /** First in the player's chain: reports where each stream starts in the song (see [BusStreamTap]). */
    val streamTap = BusStreamTap(this)

    /** Song time (us) of the first frame of the stream the sink writes next, from [streamTap]; [UNSET_US] before any. */
    @Volatile var streamStartUs: Long = UNSET_US
        private set

    @Volatile private var warnedNoTap = false

    internal fun onStreamStart(positionOffsetUs: Long) {
        streamStartUs = positionOffsetUs
    }

    /**
     * Song time to address the deck by for a stream whose first write carries [presentationTimeUs] (renderer time).
     * Without the tap (an older core patch) the renderer time is the only clue: it is used and the problem is logged,
     * since every mix on that player would then be refused as too late.
     */
    internal fun streamOriginUs(presentationTimeUs: Long): Long {
        val s = streamStartUs
        if (s != UNSET_US) return s
        if (!warnedNoTap) {
            warnedNoTap = true
            DjLog.e("bus", "deck $deckId: no stream tap in the player's chain; the deck is addressed by renderer time $presentationTimeUs us and cannot mix")
        }
        return presentationTimeUs
    }

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
        return BusAudioOutput(bus, this, deckId, outputConfig.sampleRate)
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

    companion object {
        const val UNSET_US = Long.MIN_VALUE

        /**
         * Buffer the output REPORTS to Media3 (the deck ring really holds 12 s). ExoPlayer 1.11.1 schedules its playback
         * thread dynamically: the audio renderer asks to be woken after half the buffered audio, capped at 1 s, and the
         * player's reported position only moves on those wakes. With the ring's 12 s the position moved in ~1.1 s steps
         * (the build-au log: 4.39 / 5.49 s per 5.02 s of wall time), which made the 1-2-3-4 counter jump 1 -> 3 -> 1.
         * 100 ms keeps the wakes at ~50 ms, as with a normal AudioTrack.
         */
        const val REPORTED_BUFFER_MS = 100L
    }
}

/** One Media3 output stream into a deck: written by the player's playback thread. */
private class BusAudioOutput(
    private val bus: AndroidMixBus,
    private val provider: BusAudioOutputProvider,
    private val deckId: Int,
    private val sampleRate: Int,
) : AudioOutput {
    private val ring = bus.core.ring(deckId)
    private val listeners = CopyOnWriteArraySet<AudioOutput.Listener>()
    @Volatile private var released = false
    @Volatile private var playing = false
    @Volatile private var advancingReported = false
    @Volatile private var params = PlaybackParameters.DEFAULT

    /** Song time of this stream's first frame (decided at its first write; the ring only reads the first write's time). */
    private var originUs = BusAudioOutputProvider.UNSET_US

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
        if (originUs == BusAudioOutputProvider.UNSET_US) {
            originUs = provider.streamOriginUs(presentationTimeUs)
            DjLog.d("bus", "deck $deckId: stream starts at song ${originUs / 1000} ms (renderer pts $presentationTimeUs us)")
        }
        ring.write(buffer, originUs)
        return !buffer.hasRemaining()
    }

    override fun flush() {
        if (!released) ring.flush()
        originUs = BusAudioOutputProvider.UNSET_US
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

    override fun getBufferSizeInFrames(): Long = BusAudioOutputProvider.REPORTED_BUFFER_MS * sampleRate / 1000

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
