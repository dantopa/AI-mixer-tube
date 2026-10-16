package org.simpmusic.dj.android.bus

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer
import org.simpmusic.dj.android.log.DjLog
import org.simpmusic.dj.bus.MixBusCore
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * The real-time DJ engine's Android half: ONE [AudioTrack] fed by one bus thread that renders [MixBusCore] (every
 * player's deck -> the mixer -> this output). Because both decks of a transition come out of the same buffer, they
 * share one sample clock: the mix is sample-exact by construction, with no seek bias, lock or hand-off between players.
 *
 * Players reach it through [BusAudioOutputProvider] (one per ExoPlayer, `DefaultAudioSink.Builder.setAudioOutputProvider`),
 * which writes the player's post-processing PCM into its deck and reports the deck's played frames as the player's
 * position, so the player's clock IS what is heard.
 *
 * Threads: the bus thread renders (URGENT_AUDIO priority) and runs the [MixBusCore.Listener] callbacks, which are
 * re-posted to the main thread for [transitionListener]. Producers (the players' playback threads) only touch their
 * rings and the deck flags. The output pauses when no deck has played for [IDLE_PAUSE_MS] (no wakeups while silent).
 */
class AndroidMixBus(val sampleRate: Int = SAMPLE_RATE) : MixBusCore.Listener {
    val core = MixBusCore(sampleRate = sampleRate, maxBlockFrames = BLOCK_FRAMES, listener = this)

    /** Transition callbacks, on the MAIN thread. */
    @Volatile var transitionListener: MixBusCore.Listener? = null

    private val main = Handler(Looper.getMainLooper())
    private val lock = Object()
    private val playing = ConcurrentHashMap.newKeySet<Int>()
    /** Which deck each player writes into (weak: a released player must not be kept alive by the bus). */
    private val playerDecks = java.util.Collections.synchronizedMap(java.util.WeakHashMap<ExoPlayer, BusAudioOutputProvider>())

    @Volatile private var alive = true
    private var thread: Thread? = null
    private var track: AudioTrack? = null
    @Volatile private var trackPlaying = false

    /** Bus output frame that the current [track]'s frame 0 carries. */
    @Volatile private var trackBaseFrame = 0L
    @Volatile private var lastPlayed = 0L
    private val timestamp = AudioTimestamp()
    @Volatile private var tsFrame = -1L
    @Volatile private var tsNanos = 0L
    @Volatile private var tsReadAt = 0L

    /** Audio session of the output (what the players report for effects such as the loudness enhancer). */
    @Volatile var audioSessionId: Int = AudioManager.AUDIO_SESSION_ID_GENERATE
        private set

    private val block = FloatArray(BLOCK_FRAMES * 2)

    // ============================================================================================ decks

    fun addDeck(): Int = core.addDeck(ringCapacityFrames = RING_SECONDS * sampleRate)

    /**
     * Removes a released player's deck. A deck a transition still holds (an incoming lost after it was heard, while the plan
     * runs on) is removed by the bus thread once the transition is over: refusing it for good leaked its 12 s ring
     * (~4.6 MB) for the rest of the process (the build-av log: "deck 5 kept: deck 5 is part of a transition").
     */
    fun removeDeck(deckId: Int) {
        playing -= deckId
        try {
            core.removeDeck(deckId)
        } catch (e: IllegalStateException) {
            DjLog.d(TAG, "deck $deckId: removed when the transition holding it ends")
            pendingRemoval += deckId
        } catch (e: Exception) {
            DjLog.w(TAG, "deck $deckId not removed: ${e.message}")
        }
    }

    private val pendingRemoval = ConcurrentHashMap.newKeySet<Int>()

    /** Bus thread: the decks whose removal waited for a transition to end. */
    private fun removePendingDecks() {
        if (pendingRemoval.isEmpty() || core.state != MixBusCore.State.IDLE) return
        for (id in pendingRemoval.toList()) {
            try {
                core.removeDeck(id)
                pendingRemoval -= id
                DjLog.d(TAG, "deck $id removed")
            } catch (e: IllegalStateException) {
                return // armed again meanwhile: next time
            } catch (e: Exception) {
                pendingRemoval -= id
            }
        }
    }

    /** Remembers which deck a player writes into (the transition runner needs it). */
    fun bind(player: ExoPlayer, provider: BusAudioOutputProvider) {
        playerDecks[player] = provider
    }

    /** The deck [player] writes into, or null when it is not on the bus (built before the engine was turned on). */
    fun deckOf(player: ExoPlayer): Int? = playerDecks[player]?.deckId

    fun play(deckId: Int) {
        core.play(deckId)
        playing += deckId
        ensureRunning()
        synchronized(lock) { lock.notifyAll() }
    }

    fun pause(deckId: Int) {
        core.pause(deckId)
        playing -= deckId
    }

    /** True while some deck plays or a transition is armed/running: the output must run. */
    private fun busy(): Boolean = playing.isNotEmpty() || core.state != MixBusCore.State.IDLE

    // ============================================================================================ clock

    /**
     * The bus output frame at the speaker right now: the output's playback head (AudioTimestamp, extrapolated on the
     * monotonic clock; the head position before the first timestamp) mapped to bus frames. Monotonic.
     */
    fun playedFrame(): Long {
        val t = track ?: return lastPlayed
        val nowNs = System.nanoTime()
        var played: Long
        if (trackPlaying && nowNs - tsReadAt > TIMESTAMP_REFRESH_NS) {
            synchronized(timestamp) {
                if (t.getTimestamp(timestamp)) {
                    tsFrame = timestamp.framePosition
                    tsNanos = timestamp.nanoTime
                }
                tsReadAt = nowNs
            }
        }
        played =
            if (tsFrame >= 0 && trackPlaying) {
                tsFrame + ((nowNs - tsNanos) * sampleRate / 1_000_000_000L)
            } else {
                (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
            }
        // never ahead of what was written, never backwards
        played = (trackBaseFrame + played).coerceAtMost(core.renderedFrames)
        if (played < lastPlayed) played = lastPlayed
        lastPlayed = played
        return played
    }

    // ============================================================================================ output thread

    private fun ensureRunning() {
        if (thread != null) return
        synchronized(this) {
            if (thread != null) return
            thread = Thread({ loop() }, "dj-bus").apply { start() }
        }
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var idleSinceMs = -1L
        while (alive) {
            removePendingDecks()
            if (!busy()) {
                val now = SystemClock.elapsedRealtime()
                if (idleSinceMs < 0) idleSinceMs = now
                if (trackPlaying && now - idleSinceMs > IDLE_PAUSE_MS) {
                    track?.pause()
                    trackPlaying = false
                    DjLog.d(TAG, "output paused (idle)")
                }
                if (!trackPlaying) {
                    synchronized(lock) { lock.wait(IDLE_WAIT_MS) }
                    continue
                }
            } else {
                idleSinceMs = -1L
            }
            var t = track
            if (t == null) {
                t = createTrack()
                if (t == null) {
                    synchronized(lock) { lock.wait(500L) }
                    continue
                }
            }
            if (!trackPlaying) {
                t.play()
                trackPlaying = true
                tsFrame = -1L
            }
            try {
                core.render(block, BLOCK_FRAMES)
            } catch (e: Throwable) {
                DjLog.e(TAG, "bus render failed: silence for this block", e)
                block.fill(0f)
            }
            val w = t.write(block, 0, block.size, AudioTrack.WRITE_BLOCKING)
            if (w < 0) {
                DjLog.w(TAG, "output write error $w: recreating the output")
                releaseTrack()
            }
        }
        releaseTrack()
    }

    private fun createTrack(): AudioTrack? =
        try {
            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            val bytes = max(min * 2, BLOCK_FRAMES * 2 * 4 * 4)
            val builder =
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
            if (audioSessionId != AudioManager.AUDIO_SESSION_ID_GENERATE) builder.setSessionId(audioSessionId)
            val t = builder.build()
            audioSessionId = t.audioSessionId
            // frames rendered into a released output were never heard: the new output's frame 0 is the next bus frame
            trackBaseFrame = core.renderedFrames
            track = t
            DjLog.i(TAG, "output up: $sampleRate Hz float stereo, buffer ${bytes / 8} frames (${bytes / 8 * 1000 / sampleRate} ms), session $audioSessionId")
            t
        } catch (e: Exception) {
            DjLog.e(TAG, "output could not be created", e)
            null
        }

    private fun releaseTrack() {
        val t = track ?: return
        track = null
        trackPlaying = false
        tsFrame = -1L
        try {
            t.pause()
            t.flush()
            t.release()
        } catch (_: Exception) {
        }
    }

    fun shutdown() {
        alive = false
        synchronized(lock) { lock.notifyAll() }
    }

    // ============================================================================================ transitions

    override fun onTransitionStarted(planId: Int, outputFrame: Long) = post { it.onTransitionStarted(planId, outputFrame) }

    override fun onIncomingStarted(planId: Int, outputFrame: Long) = post { it.onIncomingStarted(planId, outputFrame) }

    override fun onTransitionDone(planId: Int, outputFrame: Long) = post { it.onTransitionDone(planId, outputFrame) }

    override fun onTransitionAborted(planId: Int, reason: MixBusCore.AbortReason, incomingStarted: Boolean) =
        post { it.onTransitionAborted(planId, reason, incomingStarted) }

    override fun onDeckDrained(deckId: Int) {
        playing -= deckId
    }

    private inline fun post(crossinline call: (MixBusCore.Listener) -> Unit) {
        val l = transitionListener ?: return
        main.post { call(l) }
    }

    companion object {
        private const val TAG = "bus"
        const val SAMPLE_RATE = 48_000
        const val BLOCK_FRAMES = 480
        const val RING_SECONDS = 12
        private const val IDLE_PAUSE_MS = 3_000L
        private const val IDLE_WAIT_MS = 250L
        private const val TIMESTAMP_REFRESH_NS = 20_000_000L
    }
}
