package org.simpmusic.dj.android.scheduler

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.simpmusic.dj.android.decode.AudioUnavailableException
import org.simpmusic.dj.android.decode.CancelSignal
import org.simpmusic.dj.android.decode.TrackDecoder
import org.simpmusic.dj.android.store.AnalysisStore
import org.simpmusic.dj.model.AnalysisPriority
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.model.TrackAnalysisRepository
import org.simpmusic.dj.model.TrackAnalyzer
import java.util.concurrent.atomic.AtomicBoolean

/** Thrown by [UnavailableAnalyzer]: the DSP/ML analyzer from the analysis module is not wired in yet. */
class AnalyzerUnavailableException(message: String) : UnsupportedOperationException(message)

/**
 * Placeholder [TrackAnalyzer] bound until the real analyzer (`DspTrackAnalyzer`, id "dsp-1") is merged.
 * It fails fast with a documented exception; the scheduler recognises it, drops the work instead of
 * retrying, and the DJ falls back to the plain crossfade because no analysis ever appears.
 */
class UnavailableAnalyzer : TrackAnalyzer {
    override val id: String = "unavailable"

    override fun analyze(videoId: String, audio: org.simpmusic.dj.model.PcmAudio): TrackAnalysis =
        throw AnalyzerUnavailableException("No TrackAnalyzer installed: bind org.simpmusic.dj.analysis.DspTrackAnalyzer in DjModule")
}

data class SchedulerState(
    val queued: Int = 0,
    val running: String? = null,
    val lastError: String? = null,
    val analyzerAvailable: Boolean = true,
    val completed: Int = 0,
)

/**
 * Background analysis queue, and the app's [TrackAnalysisRepository].
 *
 *  - One worker, on [worker] (a single low-priority thread in production): analysis never competes with playback.
 *  - Priority order NOW_PLAYING > NEXT_UP > BACKGROUND, FIFO inside a level; a repeat request for a queued
 *    id only ever RAISES its priority. An id is never queued twice nor analysed twice at once.
 *  - [cancelStale] drops queued user-facing work for tracks that are no longer current/next and aborts the
 *    running job when it is one of them (the decoder checks the [CancelSignal] between codec buffers).
 *  - [AnalysisPolicy] gates battery saver / low battery / metered network; blocked work waits and is retried.
 *  - Failures back off (5 s, 20 s, 80 s) and after three are remembered as failed for [failureMemoryMs] so a
 *    track that cannot be decoded does not spin the worker.
 */
class DjAnalysisScheduler(
    private val store: AnalysisStore,
    private val analyzer: TrackAnalyzer,
    private val decoder: TrackDecoder,
    private val policy: AnalysisPolicy,
    scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val blockedRetryMs: Long = 30_000L,
    private val failureMemoryMs: Long = 10 * 60_000L,
    private val log: (String) -> Unit = {},
) : TrackAnalysisRepository {
    private class Entry(val videoId: String, var priority: AnalysisPriority, val seq: Long, var notBeforeMs: Long = 0L, var failures: Int = 0)

    private val lock = Any()
    private val queue = LinkedHashMap<String, Entry>()
    private val failedUntil = HashMap<String, Long>()
    private var seq = 0L
    private var runningId: String? = null
    private var runningCancel: AtomicBoolean? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val updates = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private val _state = MutableStateFlow(SchedulerState(analyzerAvailable = analyzer !is UnavailableAnalyzer))
    val state: StateFlow<SchedulerState> = _state.asStateFlow()

    private val loop: Job =
        scope.launch(worker) {
            try {
                runLoop()
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Throwable) {
                log("dj scheduler loop died: $e")
                throw e
            }
        }

    override suspend fun get(videoId: String): TrackAnalysis? = withContext(worker) { store.get(videoId, analyzer.id) }

    override fun observe(videoId: String): Flow<TrackAnalysis?> =
        updates
            .filter { it == videoId }
            .map { get(videoId) }
            .onStart { emit(get(videoId)) }

    override suspend fun request(videoId: String, priority: AnalysisPriority) {
        if (analyzer is UnavailableAnalyzer || !_state.value.analyzerAvailable) return
        val fresh = withContext(worker) { store.has(videoId, analyzer.id) }
        if (fresh) return
        synchronized(lock) {
            if (!_state.value.analyzerAvailable) return
            val until = failedUntil[videoId]
            if (until != null && clock() < until && priority == AnalysisPriority.BACKGROUND) return
            val existing = queue[videoId]
            if (existing != null) {
                if (priority < existing.priority) existing.priority = priority // enum order: NOW_PLAYING is lowest
                existing.notBeforeMs = 0L
            } else if (runningId != videoId) {
                queue[videoId] = Entry(videoId, priority, seq++)
            }
            publishState()
        }
        wake.trySend(Unit)
    }

    /** Forget queued NOW_PLAYING/NEXT_UP work that is not for [current] or [next]; abort the running one if stale. */
    fun cancelStale(current: String?, next: String?) {
        synchronized(lock) {
            val keep = setOfNotNull(current, next)
            val it = queue.values.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.priority != AnalysisPriority.BACKGROUND && e.videoId !in keep) it.remove()
            }
            val id = runningId
            if (id != null && id !in keep && runningPriority != AnalysisPriority.BACKGROUND) runningCancel?.set(true)
            publishState()
        }
    }

    fun shutdown() {
        loop.cancel()
    }

    // ---- worker ----

    @Volatile
    private var runningPriority: AnalysisPriority = AnalysisPriority.BACKGROUND

    private suspend fun runLoop() {
        while (true) {
            val entry = nextRunnable()
            if (entry == null) {
                if (synchronized(lock) { queue.isNotEmpty() }) delayOrWake(blockedRetryMs) else wake.receive()
                continue
            }
            process(entry)
        }
    }

    private fun nextRunnable(): Entry? =
        synchronized(lock) {
            val now = clock()
            val entry =
                queue.values
                    .filter { it.notBeforeMs <= now && policy.mayRun(it.priority) }
                    .minWithOrNull(compareBy<Entry>({ it.priority }, { it.seq }))
            if (entry == null) {
                // Nothing runnable: the queue is empty, waiting out a back-off, or blocked by battery /
                // power saver (blocked entries STAY queued and are looked at again later).
                null
            } else {
                queue.remove(entry.videoId)
                runningId = entry.videoId
                runningPriority = entry.priority
                runningCancel = AtomicBoolean(false)
                publishState()
                entry
            }
        }

    private suspend fun delayOrWake(ms: Long) {
        val next = synchronized(lock) { queue.values.minOfOrNull { it.notBeforeMs } }
        val wait = if (next != null && next > clock()) minOf(ms, next - clock()) else ms
        kotlinx.coroutines.withTimeoutOrNull(wait) { wake.receive() }
    }

    private suspend fun process(entry: Entry) {
        val cancelFlag = runningCancel!!
        val cancel = CancelSignal { cancelFlag.get() }
        try {
            if (store.has(entry.videoId, analyzer.id)) return
            val pcm = decoder.decodeForAnalysis(entry.videoId, policy.mayUseNetwork(entry.priority), cancel)
            if (cancelFlag.get()) return
            val analysis = analyzer.analyze(entry.videoId, pcm)
            store.put(analysis)
            log("dj analysis stored for ${entry.videoId} by ${analysis.analyzerId}")
            synchronized(lock) { _state.value = _state.value.copy(completed = _state.value.completed + 1, lastError = null) }
            updates.tryEmit(entry.videoId)
        } catch (e: AnalyzerUnavailableException) {
            log("dj analyzer unavailable: ${e.message}")
            synchronized(lock) {
                queue.clear()
                _state.value = _state.value.copy(analyzerAvailable = false, lastError = e.message)
            }
        } catch (e: java.util.concurrent.CancellationException) {
            if (!cancelFlag.get()) throw e // real scope cancellation
            log("dj analysis of ${entry.videoId} cancelled (stale)")
        } catch (e: Exception) {
            val retryable = e is AudioUnavailableException || e is java.io.IOException
            entry.failures++
            synchronized(lock) {
                _state.value = _state.value.copy(lastError = "${entry.videoId}: ${e.message}")
                if (entry.failures >= MAX_FAILURES || !retryable && entry.failures >= 2) {
                    failedUntil[entry.videoId] = clock() + failureMemoryMs
                    log("dj analysis of ${entry.videoId} given up: ${e.message}")
                } else {
                    entry.notBeforeMs = clock() + BACKOFF_MS * (1L shl (2 * (entry.failures - 1)))
                    queue[entry.videoId] = entry
                    log("dj analysis of ${entry.videoId} failed (${e.message}); retry in ${entry.notBeforeMs - clock()} ms")
                }
            }
        } finally {
            synchronized(lock) {
                runningId = null
                runningCancel = null
                publishState()
            }
        }
    }

    private fun publishState() {
        _state.value = _state.value.copy(queued = queue.size, running = runningId)
    }

    private companion object {
        const val MAX_FAILURES = 3
        const val BACKOFF_MS = 5_000L
    }
}
