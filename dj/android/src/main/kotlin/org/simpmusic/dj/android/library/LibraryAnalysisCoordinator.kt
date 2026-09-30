package org.simpmusic.dj.android.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.simpmusic.dj.android.scheduler.AnalysisOutcome
import org.simpmusic.dj.android.scheduler.DeviceConditions
import org.simpmusic.dj.android.scheduler.DjAnalysisScheduler
import org.simpmusic.dj.android.store.AnalysisStore
import kotlin.coroutines.cancellation.CancellationException

/** What the coordinator needs of the analysis machinery; the scheduler in production, a fake in the tests. */
interface BackgroundAnalysisPort {
    /** False when no analyzer is installed (nothing will ever be analysed). */
    val available: Boolean

    /** True when a fresh analysis (current analyzer, current schema) is stored for [videoId]. */
    suspend fun isAnalysed(videoId: String): Boolean

    /** Analyses one track at BACKGROUND priority and suspends until the scheduler is done with it. */
    suspend fun analyse(videoId: String): AnalysisOutcome

    /** Drops queued BACKGROUND work and aborts the running one. */
    fun cancelBackground()
}

class SchedulerBackgroundPort(
    private val scheduler: DjAnalysisScheduler,
    private val store: AnalysisStore,
    private val analyzerId: String,
) : BackgroundAnalysisPort {
    override val available: Boolean get() = scheduler.state.value.analyzerAvailable

    override suspend fun isAnalysed(videoId: String): Boolean = store.has(videoId, analyzerId)

    override suspend fun analyse(videoId: String): AnalysisOutcome = scheduler.analyseInBackground(videoId)

    override fun cancelBackground() = scheduler.cancelBackground()
}

/** Why the background analysis is not making progress right now (it resumes by itself when the reason goes away). */
enum class AnalysisPause {
    BATTERY_SAVER,
    LOW_BATTERY,
    METERED_NETWORK,
    TRANSITION_RENDERING,
    ANALYZER_MISSING,
}

data class LibraryAnalysisState(
    /** The user asked for it and it has not finished: it may still be paused for a [pause] reason. */
    val running: Boolean = false,
    /** Songs in the (capped, eligible) candidate set. */
    val total: Int = 0,
    /** How many of them have a fresh analysis. */
    val analysed: Int = 0,
    /** Songs given up on in this session (undecodable, no audio); retried on the next start. */
    val failed: Int = 0,
    val current: String? = null,
    val pause: AnalysisPause? = null,
    /** Everything that could be analysed is analysed. */
    val finished: Boolean = false,
)

/**
 * When may the library be analysed, and which song is next. Pure so the policy is unit-tested without a coordinator.
 *
 *  - Never in battery saver; only while charging OR at [MIN_BATTERY_PERCENT] or more.
 *  - Network: unmetered only. Hundreds of songs would silently spend a mobile data plan, so unlike the playing / next
 *    track's analysis this does NOT follow the "analyze on mobile data" switch. On a metered connection only DOWNLOADED
 *    songs are analysed, because those are decoded from the download cache without touching the network.
 */
class LibraryAnalysisPolicy(
    private val conditions: DeviceConditions,
) {
    /** A device-level reason not to start anything, or null. */
    fun blockedBy(): AnalysisPause? =
        when {
            conditions.isBatterySaver && !conditions.isCharging -> AnalysisPause.BATTERY_SAVER
            !conditions.isCharging && conditions.batteryPercent < MIN_BATTERY_PERCENT -> AnalysisPause.LOW_BATTERY
            else -> null
        }

    fun mayFetchStreams(): Boolean = !conditions.isMetered

    /** First of [pending] (already in priority order) the network rule allows, or null. */
    fun next(pending: List<LibraryCandidate>): LibraryCandidate? {
        val net = mayFetchStreams()
        return pending.firstOrNull { net || it.downloaded }
    }

    companion object {
        const val MIN_BATTERY_PERCENT = 50
    }
}

/**
 * Warms the library in the background so there is something to compare a playing track against.
 *
 * It feeds the scheduler ONE song at a time (never a burst): the analysis of the playing and the next track (priority
 * NOW_PLAYING / NEXT_UP) always jumps the queue, and nothing new is started while a DJ transition is being planned,
 * decoded, rendered or mixed ([transitionBusy]). The candidate set is [CandidateSelection.select] (cap 300), in the
 * order liked > downloaded > most played > recently played. The loop is resumable by construction: what is analysed is
 * on disk, so [start] after a stop or a restart simply skips it.
 */
class LibraryAnalysisCoordinator(
    private val source: LibrarySource,
    private val port: BackgroundAnalysisPort,
    private val policy: LibraryAnalysisPolicy,
    private val transitionBusy: () -> Boolean,
    private val scope: CoroutineScope,
    private val cap: Int = CandidateSelection.DEFAULT_CAP,
    private val idleRetryMs: Long = 30_000L,
    private val busyPollMs: Long = 2_000L,
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(LibraryAnalysisState())
    val state: StateFlow<LibraryAnalysisState> = _state.asStateFlow()

    private var job: Job? = null

    /** Ids known to be analysed. Checking one means parsing its stored JSON, so it is done once per id per process. */
    private val knownAnalysed: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private suspend fun isAnalysed(id: String): Boolean {
        if (id in knownAnalysed) return true
        if (!port.isAnalysed(id)) return false
        knownAnalysed += id
        return true
    }

    val isRunning: Boolean get() = job?.isActive == true

    /** Starts (or resumes) the analysis. Idempotent. */
    fun start() {
        if (job?.isActive == true) return
        _state.update { it.copy(running = true, finished = false, pause = null, failed = 0) }
        job =
            scope.launch {
                try {
                    loop()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log("dj library analysis stopped by $e")
                } finally {
                    _state.update { it.copy(running = false, current = null) }
                }
            }
    }

    /** Stops after cancelling the running job. What is already analysed is kept. */
    fun stop() {
        job?.cancel()
        job = null
        port.cancelBackground()
        _state.update { it.copy(running = false, current = null, pause = null) }
    }

    /** Recomputes "N of M" without starting anything (for the settings screen). */
    suspend fun refreshProgress() {
        val set = CandidateSelection.select(source.candidates(), cap)
        val analysed = set.count { isAnalysed(it.track.videoId) }
        _state.update { it.copy(total = set.size, analysed = analysed) }
    }

    private suspend fun loop() {
        val failed = HashSet<String>()
        while (currentCoroutineContext().isActive) {
            if (!port.available) {
                _state.update { it.copy(pause = AnalysisPause.ANALYZER_MISSING) }
                return
            }
            val set = CandidateSelection.select(source.candidates(), cap)
            val pending = ArrayList<LibraryCandidate>()
            for (c in set) {
                val id = c.track.videoId
                if (!isAnalysed(id)) pending += c
            }
            _state.update { it.copy(total = set.size, analysed = set.size - pending.size, failed = failed.size) }
            val todo = pending.filter { it.track.videoId !in failed }
            if (todo.isEmpty()) {
                log("dj library analysis finished: ${set.size - pending.size} of ${set.size} analysed, ${failed.size} failed")
                _state.update { it.copy(finished = true, pause = null, current = null) }
                return
            }
            val blocked = policy.blockedBy()
            if (blocked != null) {
                _state.update { it.copy(pause = blocked, current = null) }
                delay(idleRetryMs)
                continue
            }
            if (transitionBusy()) {
                _state.update { it.copy(pause = AnalysisPause.TRANSITION_RENDERING, current = null) }
                delay(busyPollMs)
                continue
            }
            val next = policy.next(todo)
            if (next == null) {
                _state.update { it.copy(pause = AnalysisPause.METERED_NETWORK, current = null) }
                delay(idleRetryMs)
                continue
            }
            val id = next.track.videoId
            _state.update { it.copy(pause = null, current = id) }
            when (val outcome = port.analyse(id)) {
                is AnalysisOutcome.Done -> knownAnalysed += id
                is AnalysisOutcome.Failed -> {
                    failed += id
                    log("dj library analysis: $id given up (${outcome.message})")
                }
                is AnalysisOutcome.Cancelled -> delay(busyPollMs) // preempted by the playing track's analysis, or stopped
                is AnalysisOutcome.Unavailable -> {
                    _state.update { it.copy(pause = AnalysisPause.ANALYZER_MISSING) }
                    return
                }
            }
        }
    }
}
