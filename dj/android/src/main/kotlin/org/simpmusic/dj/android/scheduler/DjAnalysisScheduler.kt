package org.simpmusic.dj.android.scheduler

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.simpmusic.dj.android.decode.AudioNeedsNetworkException
import org.simpmusic.dj.android.decode.AudioUnavailableException
import org.simpmusic.dj.android.decode.CancelSignal
import org.simpmusic.dj.android.decode.TrackDecoder
import org.simpmusic.dj.android.log.DjLog
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

/** Terminal result of one background analysis, see [DjAnalysisScheduler.analyseInBackground]. */
sealed interface AnalysisOutcome {
    val videoId: String

    data class Done(override val videoId: String) : AnalysisOutcome

    data class Failed(override val videoId: String, val message: String?) : AnalysisOutcome

    data class Cancelled(override val videoId: String) : AnalysisOutcome

    /** No analyzer is installed: nothing will ever be analysed. */
    data class Unavailable(override val videoId: String) : AnalysisOutcome
}

data class SchedulerState(
    val queued: Int = 0,
    val running: String? = null,
    val lastError: String? = null,
    val analyzerAvailable: Boolean = true,
    val completed: Int = 0,
    /** The last three failure messages (`id: message`), newest last: what a screenshot needs to diagnose "waiting analysis". */
    val recentErrors: List<String> = emptyList(),
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
 *    track that cannot be decoded does not spin the worker. A decode that only lacks permission to use the network is
 *    NOT a failure: the track stays queued and is retried every [blockedRetryMs].
 *  - Nothing a job throws ends the loop: an `Error` (a missing native library, an out-of-memory) is a failed job like any
 *    other, and is logged with its stack.
 *
 * Every decision is written to [DjLog] under the tag `sched`; [statusOf] answers, per track, what the screen shows.
 */
class DjAnalysisScheduler(
    private val store: AnalysisStore,
    private val analyzer: TrackAnalyzer,
    private val decoder: TrackDecoder,
    private val policy: AnalysisPolicy,
    scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    /** Where stored analyses are read. NOT [worker]: that thread is busy decoding, and a read queued behind it would wait for the whole job. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val blockedRetryMs: Long = 30_000L,
    private val failureMemoryMs: Long = 10 * 60_000L,
    /**
     * A cheap analyzer (DSP only: tempo, key, energy) used for BACKGROUND library warm-up, so a whole library is classified in
     * minutes instead of an hour. The full [analyzer] later upgrades a track on demand (NOW_PLAYING / NEXT_UP). Ignored when it
     * is the same analyzer as [analyzer] (no neural model installed).
     */
    quick: TrackAnalyzer? = null,
    /** Called after an analysis is stored (caches that hold the previous one must forget it). */
    private val onStored: (String) -> Unit = {},
    /**
     * Called on the worker thread as each job starts. Production raises the thread to normal priority for NOW_PLAYING /
     * NEXT_UP (someone is waiting for that analysis) and drops it to background for the library warm-up: a thread left at
     * THREAD_PRIORITY_BACKGROUND is confined to the little cores with a sliver of CPU while the app is in use.
     */
    private val onJobStart: (AnalysisPriority) -> Unit = {},
    /**
     * Vocal detection, used to ADD the vocals to a stored full analysis made before it existed (the full [analyzer] already
     * fills them for new analyses): YAMNet alone, a few seconds, once per process for the playing and the next track.
     */
    private val vocals: org.simpmusic.dj.ml.VocalProvider? = null,
) : TrackAnalysisRepository {
    private val quick: TrackAnalyzer? = quick?.takeIf { it.id != analyzer.id }

    /** Analyzer for work at [priority]: the quick one for the library warm-up when there is one. */
    private fun analyzerFor(priority: AnalysisPriority): TrackAnalyzer = if (priority == AnalysisPriority.BACKGROUND) quick ?: analyzer else analyzer

    /** True when [videoId] already has what [priority] needs: BACKGROUND accepts a quick analysis, the others need the full one. */
    private fun fresh(videoId: String, priority: AnalysisPriority): Boolean {
        if (priority == AnalysisPriority.BACKGROUND && quick != null) return store.has(videoId)
        if (!store.has(videoId, analyzer.id)) return false
        return priority == AnalysisPriority.BACKGROUND || !needsBarEvidence(videoId)
    }

    /** Tracks re-analysed (or tried) in this process because their stored analysis had no bar-phase evidence. */
    private val upgraded = HashSet<String>()

    /**
     * A full analysis stored before the tracker kept its per-beat downbeat logits (build ac) cannot tell which beat is the 1
     * (see `BarPhase`), and one stored before the structure frames (build ae) gives `PhraseGrid` only loudness and bass to
     * find the phrases with: the playing and the next track are analysed again, once per process each, so a "Perfect" mix
     * becomes possible for them. The library keeps its old analyses (they still rank and beat-match).
     */
    private fun needsBarEvidence(videoId: String): Boolean {
        if (synchronized(upgraded) { videoId in upgraded }) return false
        val a = store.get(videoId, analyzer.id) ?: return false
        if (a.beatTimesMs == null) return false
        // only the device's full analyzer (Beat This! over the DSP pass) produces both; any other would re-analyse for nothing
        return "beat-this" in analyzer.id && (a.beatDownbeatLogits == null || a.structureFrames.isEmpty() || (vocals != null && (a.vocals == null || a.vocalProfile == null)))
    }

    private class Entry(val videoId: String, var priority: AnalysisPriority, val seq: Long, var notBeforeMs: Long = 0L, var failures: Int = 0)

    private class Failure(val message: String?, val needsNetwork: Boolean)

    private val lock = Any()
    private val queue = LinkedHashMap<String, Entry>()
    private val failedUntil = HashMap<String, Long>()
    private val lastFailure = HashMap<String, Failure>()
    private var seq = 0L
    private var runningId: String? = null
    private var runningSinceMs = 0L
    private var runningCancel: AtomicBoolean? = null
    private var lastBlockedLogMs = 0L
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val updates = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private val outcomes = MutableSharedFlow<AnalysisOutcome>(extraBufferCapacity = 64)
    private val _state = MutableStateFlow(SchedulerState(analyzerAvailable = analyzer !is UnavailableAnalyzer))
    val state: StateFlow<SchedulerState> = _state.asStateFlow()

    private val loop: Job =
        scope.launch(worker) {
            try {
                runLoop()
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Throwable) {
                DjLog.e(TAG, "scheduler loop died: analyses will never run again until restart", e)
                throw e
            }
        }

    init {
        DjLog.i(TAG, "scheduler up: analyzer=${analyzer.id} (${analyzer.javaClass.simpleName}) available=${analyzer !is UnavailableAnalyzer}")
    }

    override suspend fun get(videoId: String): TrackAnalysis? = withContext(io) { store.get(videoId, analyzer.id) }

    override fun observe(videoId: String): Flow<TrackAnalysis?> =
        updates
            .filter { it == videoId }
            .map { get(videoId) }
            .onStart { emit(get(videoId)) }

    override suspend fun request(videoId: String, priority: AnalysisPriority) {
        if (analyzer is UnavailableAnalyzer || !_state.value.analyzerAvailable) {
            DjLog.w(TAG, "request $videoId $priority ignored: no analyzer available")
            return
        }
        val fresh = withContext(io) { fresh(videoId, priority) }
        if (fresh) {
            DjLog.d(TAG, "request $videoId $priority: already analysed (fresh in store)")
            return
        }
        var verdict: String
        synchronized(lock) {
            if (!_state.value.analyzerAvailable) return
            val until = failedUntil[videoId]
            if (until != null && clock() < until && priority == AnalysisPriority.BACKGROUND) {
                DjLog.d(TAG, "request $videoId $priority: skipped, failed recently (${(until - clock()) / 1000} s of memory left)")
                return
            }
            val existing = queue[videoId]
            if (existing != null) {
                verdict =
                    if (priority < existing.priority) {
                        existing.priority = priority // enum order: NOW_PLAYING is lowest
                        "raised ${existing.priority}->$priority"
                    } else {
                        "dedup (already queued as ${existing.priority})"
                    }
                existing.notBeforeMs = 0L
            } else if (runningId != videoId) {
                queue[videoId] = Entry(videoId, priority, seq++)
                verdict = "queued"
            } else {
                verdict = "dedup (already running)"
            }
            // The single worker cannot start the playing track's analysis until the running one ends: a library warm-up job
            // (BACKGROUND) gives way. It is abandoned, not queued again: whoever asked for it asks again.
            if (priority != AnalysisPriority.BACKGROUND && runningId != null && runningId != videoId && runningPriority == AnalysisPriority.BACKGROUND) {
                runningCancel?.set(true)
                verdict += "; preempting background job $runningId"
            }
            publishState()
            DjLog.i(TAG, "request $videoId $priority: $verdict | ${policy.describe()} | queue=${queueSummary()} running=$runningId")
        }
        wake.trySend(Unit)
    }

    /** Upcoming queue tracks the look-ahead is analysing: [cancelStale] keeps them besides current and next. */
    @Volatile private var lookAhead: Set<String> = emptySet()

    fun setLookAhead(ids: Set<String>) {
        lookAhead = ids
    }

    /** Forget queued NOW_PLAYING/NEXT_UP work that is not for [current] or [next]; abort the running one if stale. */
    fun cancelStale(current: String?, next: String?) {
        synchronized(lock) {
            val keep = setOfNotNull(current, next) + lookAhead
            val dropped = ArrayList<String>()
            val it = queue.values.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.priority != AnalysisPriority.BACKGROUND && e.videoId !in keep) {
                    dropped += e.videoId
                    it.remove()
                }
            }
            val id = runningId
            var aborted = false
            if (id != null && id !in keep && runningPriority != AnalysisPriority.BACKGROUND) {
                runningCancel?.set(true)
                aborted = true
            }
            if (dropped.isNotEmpty() || aborted) DjLog.d(TAG, "cancelStale keep=$keep dropped=$dropped abortedRunning=${if (aborted) id else null}")
            publishState()
        }
    }

    /**
     * Queues [videoId] at BACKGROUND priority and suspends until the scheduler is done with it (analysed, given up on,
     * cancelled by [cancelBackground] or no analyzer). Used by the library coordinator to feed the queue one track at a time.
     * Returns at once when a fresh analysis is already stored. Cancelling the caller does not cancel the job itself: call
     * [cancelBackground] for that.
     */
    suspend fun analyseInBackground(videoId: String, timeoutMs: Long = 10 * 60_000L): AnalysisOutcome {
        if (analyzer is UnavailableAnalyzer || !_state.value.analyzerAvailable) return AnalysisOutcome.Unavailable(videoId)
        if (withContext(io) { fresh(videoId, AnalysisPriority.BACKGROUND) }) return AnalysisOutcome.Done(videoId)
        synchronized(lock) {
            val until = failedUntil[videoId]
            if (until != null && clock() < until) return AnalysisOutcome.Failed(videoId, "failed recently")
        }
        return coroutineScope {
            // Subscribe before requesting: the flow has no replay, and a fast worker could finish first.
            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeoutOrNull(timeoutMs) { outcomes.first { it.videoId == videoId } }
                }
            request(videoId, AnalysisPriority.BACKGROUND)
            result.await() ?: AnalysisOutcome.Failed(videoId, "timed out")
        }
    }

    /**
     * Someone else (the "Run analysis now" probe) stored an analysis for [videoId]: wake whoever waits for it (the engine's
     * `observe`, the library coordinator) and forget any queued or failed state, exactly as if the worker had done it.
     */
    fun announceStored(videoId: String) {
        synchronized(lock) {
            queue.remove(videoId)
            failedUntil.remove(videoId)
            lastFailure.remove(videoId)
            publishState()
        }
        DjLog.i(TAG, "analysis of $videoId stored from outside the queue (probe): waking waiters")
        updates.tryEmit(videoId)
        outcomes.tryEmit(AnalysisOutcome.Done(videoId))
    }

    /** Drops every queued BACKGROUND entry and aborts the running one when it is BACKGROUND. */
    fun cancelBackground() {
        val dropped = ArrayList<String>()
        synchronized(lock) {
            val it = queue.values.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.priority == AnalysisPriority.BACKGROUND) {
                    dropped += e.videoId
                    it.remove()
                }
            }
            if (runningId != null && runningPriority == AnalysisPriority.BACKGROUND) runningCancel?.set(true)
            publishState()
        }
        if (dropped.isNotEmpty()) DjLog.d(TAG, "cancelBackground dropped=$dropped")
        dropped.forEach { outcomes.tryEmit(AnalysisOutcome.Cancelled(it)) }
    }

    /** What is happening to [videoId] right now, for the settings line and the Now Playing chip. */
    suspend fun statusOf(videoId: String): AnalysisStatus {
        if (analyzer is UnavailableAnalyzer || !_state.value.analyzerAvailable) return AnalysisStatus.AnalyzerMissing
        if (withContext(io) { store.has(videoId, analyzer.id) }) return AnalysisStatus.Analysed
        return synchronized(lock) {
            val now = clock()
            val failure = lastFailure[videoId]
            if (runningId == videoId) return@synchronized AnalysisStatus.Analysing(((now - runningSinceMs) / 1000).toInt())
            val queued = queue[videoId]
            if (queued != null) {
                if (failure?.needsNetwork == true) return@synchronized AnalysisStatus.NeedsNetwork(metered = policy.isMetered)
                policy.blockReason(queued.priority)?.let { return@synchronized AnalysisStatus.Blocked(it) }
                if (queued.notBeforeMs > now) return@synchronized AnalysisStatus.Retrying(((queued.notBeforeMs - now) / 1000).toInt() + 1, failure?.message)
                return@synchronized AnalysisStatus.Queued
            }
            val until = failedUntil[videoId]
            if (until != null && now < until) return@synchronized AnalysisStatus.Failed(failure?.message)
            AnalysisStatus.NotRequested
        }
    }

    /**
     * True while a track that is already in the store is queued or being analysed again: the once-per-process patch that
     * adds the vocals / structure frames, or the re-analysis that adds the bar evidence. [statusOf] says `Analysed` then,
     * because the old record is still there, so a caller that needs the new fields must ask this instead.
     */
    fun upgradePending(videoId: String): Boolean = synchronized(lock) { runningId == videoId || queue.containsKey(videoId) }

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
                if (queue.isNotEmpty() && now - lastBlockedLogMs >= BLOCKED_LOG_EVERY_MS) {
                    lastBlockedLogMs = now
                    val why =
                        queue.values.joinToString { e ->
                            val block = policy.blockReason(e.priority)
                            "${e.videoId}/${e.priority}:" + (block?.name ?: if (e.notBeforeMs > now) "backoff ${(e.notBeforeMs - now) / 1000}s" else "?")
                        }
                    DjLog.i(TAG, "nothing runnable: $why | ${policy.describe()}")
                }
                null
            } else {
                queue.remove(entry.videoId)
                runningId = entry.videoId
                runningSinceMs = now
                runningPriority = entry.priority
                runningCancel = AtomicBoolean(false)
                publishState()
                entry
            }
        }

    private suspend fun delayOrWake(ms: Long) {
        val next = synchronized(lock) { queue.values.minOfOrNull { it.notBeforeMs } }
        val wait = if (next != null && next > clock()) minOf(ms, next - clock()) else ms
        withTimeoutOrNull(wait) { wake.receive() }
    }

    private suspend fun process(entry: Entry) {
        val cancelFlag = runningCancel!!
        val cancel = CancelSignal { cancelFlag.get() }
        val id = entry.videoId
        val t0 = clock()
        val network = policy.mayUseNetwork(entry.priority)
        try {
            onJobStart(entry.priority)
        } catch (_: Throwable) {
        }
        DjLog.i(TAG, "start $id ${entry.priority} attempt=${entry.failures + 1} network=${if (network) "allowed" else "forbidden"} | ${policy.describe()}")
        try {
            val useAnalyzer = analyzerFor(entry.priority)
            if (fresh(id, entry.priority)) {
                DjLog.d(TAG, "$id already in store, nothing to do")
                outcomes.tryEmit(AnalysisOutcome.Done(id))
                return
            }
            // A full analysis that only lacks the structure frames (build ae) and/or the vocals (build ak) needs the DSP pass and/or
            // the vocal model alone, not Beat This! again: a few seconds of CPU instead of ~25 s, once per track (less heat).
            val patch = if (entry.priority != AnalysisPriority.BACKGROUND) {
                store.get(id, analyzer.id)?.takeIf { it.beatDownbeatLogits != null && (it.structureFrames.isNotEmpty() || quick != null) }
            } else {
                null
            }
            if (entry.priority != AnalysisPriority.BACKGROUND && store.has(id, analyzer.id)) {
                synchronized(upgraded) { upgraded += id }
                val what = if (patch == null) {
                    "bar-phase evidence, analysing it again"
                } else {
                    listOfNotNull("structure frames".takeIf { patch.structureFrames.isEmpty() }, "vocals".takeIf { (patch.vocals == null || patch.vocalProfile == null) && vocals != null })
                        .joinToString(" and ") + ", adding only that"
                }
                DjLog.i(TAG, "$id: stored analysis predates the $what (once)")
            }
            val tDecode = clock()
            val pcm = decoder.decodeForAnalysis(id, network, cancel)
            DjLog.i(TAG, "$id decoded ${pcm.samples.size} samples @${pcm.sampleRate} Hz (${pcm.durationMs} ms of audio) in ${clock() - tDecode} ms")
            if (cancelFlag.get()) {
                DjLog.i(TAG, "$id cancelled after decode")
                outcomes.tryEmit(AnalysisOutcome.Cancelled(id))
                return
            }
            val tAnalyse = clock()
            val analysis =
                if (patch != null) {
                    var a: TrackAnalysis = patch
                    if (a.structureFrames.isEmpty()) {
                        val q = quick!!.analyze(id, pcm)
                        a = a.copy(highBandEnergy = q.highBandEnergy, structureHopMs = q.structureHopMs, structureFrames = q.structureFrames)
                    }
                    if ((a.vocals == null || a.vocalProfile == null) && vocals != null) {
                        a = (try { vocals.detect(pcm) } catch (e: Exception) { null })?.let { a.copy(vocals = it.ranges, vocalProfile = it.profile) } ?: a
                    }
                    a
                } else {
                    useAnalyzer.analyze(id, pcm)
                }
            val tStore = clock()
            store.put(analysis)
            onStored(id)
            synchronized(lock) {
                lastFailure.remove(id)
                failedUntil.remove(id)
                _state.value = _state.value.copy(completed = _state.value.completed + 1, lastError = null)
            }
            DjLog.i(
                TAG,
                "done $id by ${analysis.analyzerId}: analyse ${tStore - tAnalyse} ms, store ${clock() - tStore} ms, total ${clock() - t0} ms | ${summary(analysis)}",
            )
            updates.tryEmit(id)
            outcomes.tryEmit(AnalysisOutcome.Done(id))
        } catch (e: AnalyzerUnavailableException) {
            DjLog.e(TAG, "analyzer unavailable, dropping the whole queue", e)
            synchronized(lock) {
                queue.clear()
                _state.value = _state.value.copy(analyzerAvailable = false, lastError = e.message)
            }
            outcomes.tryEmit(AnalysisOutcome.Unavailable(id))
        } catch (e: java.util.concurrent.CancellationException) {
            if (!cancelFlag.get()) throw e // real scope cancellation
            DjLog.i(TAG, "$id cancelled (stale or preempted) after ${clock() - t0} ms")
            outcomes.tryEmit(AnalysisOutcome.Cancelled(id))
        } catch (e: AudioNeedsNetworkException) {
            // Not a failure: the audio is not (fully) cached and the network may not be used for this priority. Stay queued.
            synchronized(lock) {
                lastFailure[id] = Failure(e.message, needsNetwork = true)
                entry.notBeforeMs = clock() + blockedRetryMs
                queue[id] = entry
            }
            DjLog.w(TAG, "$id waits for the network: ${e.message} (partial cache=${e.partiallyCached}, ${policy.describe()}); retry in ${blockedRetryMs / 1000} s")
        } catch (e: Throwable) {
            // Exception AND Error: a missing native library or an OOM must fail this job, not kill the worker loop.
            val retryable = e is AudioUnavailableException || e is java.io.IOException
            entry.failures++
            val message = "${e.javaClass.simpleName}: ${e.message}"
            synchronized(lock) {
                lastFailure[id] = Failure(message, needsNetwork = false)
                _state.value = _state.value.copy(lastError = "$id: $message", recentErrors = (_state.value.recentErrors + "$id: $message").takeLast(3))
                if (entry.failures >= MAX_FAILURES || !retryable && entry.failures >= 2) {
                    failedUntil[id] = clock() + failureMemoryMs
                    DjLog.e(TAG, "GIVING UP on $id after ${entry.failures} failures (${clock() - t0} ms this time)", e)
                    outcomes.tryEmit(AnalysisOutcome.Failed(id, message))
                } else {
                    entry.notBeforeMs = clock() + BACKOFF_MS * (1L shl (2 * (entry.failures - 1)))
                    queue[id] = entry
                    DjLog.e(TAG, "FAILED $id (attempt ${entry.failures}, ${clock() - t0} ms); retry in ${(entry.notBeforeMs - clock()) / 1000} s", e)
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

    private fun summary(a: TrackAnalysis): String =
        "bpm=${a.bpm?.let { "%.1f(c%.2f)".format(it.value, it.confidence) }} beats=${a.beatTimesMs?.let { "${it.value.size}(c%.2f)".format(it.confidence) }} " +
            "downbeats=${a.downbeatBeatIndices?.let { "${it.value.size}(c%.2f)".format(it.confidence) }} key=${a.key?.let { "${it.value.camelot()}(c%.2f)".format(it.confidence) }} dur=${a.durationMs} ms vocals=${a.vocals?.value?.let { v -> "${v.size} ranges, ${v.sumOf { it.durationMs } / 1000} s" } ?: "none"} grid-repair: ${org.simpmusic.dj.analysis.GridRepair.repairWithReport(a).second.reason} ${org.simpmusic.dj.analysis.AnalysisRefiner.cached(a).let { r -> org.simpmusic.dj.analysis.BarPhase.describe(r) + "; " + org.simpmusic.dj.analysis.PhraseGrid.describe(r) }}" +
            (org.simpmusic.dj.analysis.SongMap.describe(a)?.let { " map: $it" } ?: "")

    private fun queueSummary(): String = queue.values.joinToString(prefix = "[", postfix = "]") { "${it.videoId}/${it.priority}" }

    private fun publishState() {
        _state.value = _state.value.copy(queued = queue.size, running = runningId)
    }

    private companion object {
        const val TAG = "sched"
        const val MAX_FAILURES = 3
        const val BACKOFF_MS = 5_000L
        const val BLOCKED_LOG_EVERY_MS = 10_000L
    }
}
