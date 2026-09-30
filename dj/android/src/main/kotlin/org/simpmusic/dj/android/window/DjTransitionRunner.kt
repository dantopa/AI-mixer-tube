@file:OptIn(UnstableApi::class)

package org.simpmusic.dj.android.window

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.simpmusic.dj.android.DjHooks
import org.simpmusic.dj.android.PreparedTransition

/**
 * The ONLY coupling between the DJ window machinery and the player adapter. Implemented by
 * `CrossfadeExoPlayerAdapter` (in the core patch); everything here is called on its main-thread scope.
 */
interface DjPlayerPort {
    /** The player that is audible right now (the outgoing track). Null when the adapter is idle/released. */
    fun currentPlayer(): ExoPlayer?

    /** The user's volume (0..1): decks are faded relative to it, exactly like the app's crossfade does. */
    fun userVolume(): Float

    /** A fresh ExoPlayer with the app's normal audio chain (user EQ / delay / reverb apply as on any deck). */
    fun createWindowPlayer(): ExoPlayer

    /** Metadata item of the INCOMING track, so the session shows its title while the window is the audible player. */
    fun incomingMetadata(): MediaItem?

    /**
     * Point of no return. Flip the queue to the incoming track (UI, MediaSession metadata, active listener),
     * make [windowPlayer] the session delegate, and return the LIVE incoming player prepared, PAUSED and parked
     * at [seekSourceMs]; null when that is not possible (the transition then aborts, nothing audible happened).
     */
    fun commitToIncoming(seekSourceMs: Long, windowPlayer: ExoPlayer, uiPosition: () -> Long?): ExoPlayer?

    /** Ran to completion: the live incoming player is alone, audible, at full volume. Promote it. */
    fun onFinished()

    /**
     * The runner gave up by itself after cleaning up. If [result].committed the queue already moved to the
     * incoming track and the adapter must adopt the (parked or playing) live incoming player; otherwise
     * nothing changed and normal playback simply continues.
     */
    fun onFailed(reason: String, result: AbortResult)

    fun log(message: String)
}

/**
 * Owns one prepared transition on the device: builds the window player, drives the [TransitionController]
 * with a ~10 ms tick on the main thread, and bridges the controller's [TransitionHost] callbacks to the
 * adapter through a [DjPlayerPort].
 *
 * Why the main thread and not a dedicated one: ExoPlayer insists on a single application thread
 * (`verifyApplicationThread`), and every player in the app is created on the service's main-thread scope.
 * The tick does a handful of property reads/writes, so the cost is negligible; and nothing here is
 * timing-critical at the sample level: beat alignment is baked into the window's samples, and the hand-offs
 * are corrected closed-loop while the follower is silent.
 */
class DjTransitionRunner(
    private val port: DjPlayerPort,
    val prepared: PreparedTransition,
    private val hooks: DjHooks,
    private val scope: CoroutineScope,
    private val clock: DjClock = DjClock { SystemClock.elapsedRealtimeNanos() / 1_000_000.0 },
    private val tickMs: Long = 10L,
) {
    private var windowPlayer: ExoPlayer? = null
    private var windowDeck: ExoDeck? = null
    private var controller: TransitionController? = null
    private var tickJob: Job? = null

    @Volatile
    private var windowError: PlaybackException? = null

    @Volatile
    var isRunning = false
        private set

    /** True after [abort]/failure/finish: the runner will not act again. */
    @Volatile
    var isDone = false
        private set

    private var incomingPlayer: ExoPlayer? = null

    /** Builds and prepares the window player (paused at 0). Cheap; called as soon as the render finished. */
    fun prepareWindow(): Boolean {
        val metadata = port.incomingMetadata() ?: return false
        return try {
            val player = port.createWindowPlayer()
            player.volume = 0f
            player.playWhenReady = false
            player.setMediaSource(WindowMediaSource.create(prepared.window.file, metadata))
            player.addListener(
                object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        windowError = error
                    }
                },
            )
            player.prepare()
            windowPlayer = player
            windowDeck = ExoDeck("window", player)
            true
        } catch (e: Exception) {
            port.log("dj: window player setup failed: $e")
            windowPlayer?.release()
            windowPlayer = null
            false
        }
    }

    val isWindowReady: Boolean get() = windowDeck?.isReady == true

    /** Releases the prepared window player when the transition is never started. */
    fun discard() {
        if (isRunning) return
        isDone = true
        windowDeck?.release()
        windowDeck = null
        windowPlayer = null
    }

    /** Starts the transition: window plays silently next to the outgoing player. False when it cannot start. */
    fun start(): Boolean {
        val outPlayer = port.currentPlayer() ?: return false
        val wDeck = windowDeck ?: return false
        val wPlayer = windowPlayer ?: return false
        if (!wDeck.isReady || isDone) return false
        val outDeck = ExoDeck("live-out", outPlayer)
        val host = Host(wPlayer)
        val c =
            TransitionController(
                timeline = prepared.timeline,
                outgoing = outDeck,
                window = wDeck,
                host = host,
                clock = clock,
                userVolume = port::userVolume,
                calibrator = hooks.calibrator,
                log = { hooks.log(it) },
            )
        controller = c
        isRunning = true
        // Never let the window mix on top of the outgoing player's own effects: both decks share the app's
        // audio chain (equalizer, delay, reverb are per-player instances), so they apply once per deck.
        c.start()
        tickJob =
            scope.launch {
                while (isActive && !c.isFinished) {
                    if (c.phase == TransitionController.Phase.LOCK_OUT && port.currentPlayer() !== outPlayer) {
                        // The adapter moved on (track change / release) before the commit: nothing to undo.
                        val result = c.abort()
                        finish(failed = "outgoing player replaced", result = result)
                        return@launch
                    }
                    windowError?.let { e ->
                        port.log("dj: window player error ${e.errorCodeName}")
                        val result = c.abort()
                        finish(failed = "window error ${e.errorCodeName}", result = result)
                        return@launch
                    }
                    c.tick()
                    delay(tickMs)
                }
            }
        return true
    }

    /**
     * Synchronous cancel (user action, queue change, cast, release). Idempotent. Returns null when nothing
     * was running; otherwise what the adapter needs to adopt the incoming player.
     */
    fun abort(): AbortResult? {
        val c = controller
        if (c == null || isDone) {
            if (!isRunning) discard()
            return null
        }
        tickJob?.cancel()
        val result = c.abort()
        isDone = true
        isRunning = false
        return result
    }

    /** UI position on the incoming track once it is the current track, else null. */
    fun uiPositionMs(): Long? = controller?.uiPositionMs()

    fun outgoingUiPositionMs(): Long? = controller?.outgoingUiPositionMs()

    val phase: TransitionController.Phase? get() = controller?.phase

    private fun finish(failed: String?, result: AbortResult?) {
        if (isDone && failed == null) return
        isDone = true
        isRunning = false
        if (failed != null && result != null) port.onFailed(failed, result) else port.onFinished()
    }

    private inner class Host(val wPlayer: ExoPlayer) : TransitionHost {
        override fun commitToIncoming(seekSourceMs: Long): Deck? {
            val p = port.commitToIncoming(seekSourceMs, wPlayer) { controller?.uiPositionMs() } ?: return null
            incomingPlayer = p
            return ExoDeck("live-in", p)
        }

        override fun onFinished() = finish(failed = null, result = null)

        override fun onFailed(reason: String, result: AbortResult) {
            tickJob?.cancel()
            finish(failed = reason, result = result)
        }

        override fun onEvent(event: TransitionEvent) {
            hooks.log("dj event: $event")
        }
    }
}
