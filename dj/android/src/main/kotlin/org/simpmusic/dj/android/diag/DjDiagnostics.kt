package org.simpmusic.dj.android.diag

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.simpmusic.dj.android.decode.AudioSourceResolver
import org.simpmusic.dj.android.decode.CancelSignal
import org.simpmusic.dj.android.decode.TrackDecoder
import org.simpmusic.dj.android.log.DjLog
import org.simpmusic.dj.android.store.AnalysisStore
import org.simpmusic.dj.model.TrackAnalyzer
import kotlin.coroutines.cancellation.CancellationException

/** One step of "Run analysis now". */
data class ProbeStep(
    val name: String,
    val state: State,
    val detail: String = "",
    val ms: Long = 0L,
) {
    enum class State { RUNNING, OK, FAILED }
}

/**
 * "Run analysis now": analyses ONE track outside the scheduler, step by step (resolve the audio, decode it, analyse it,
 * store the result), reporting each step live and in the log (tag `probe`). One tap therefore isolates which step breaks,
 * without waiting for the queue, a policy or a back-off. It uses its own decoder thread, so a busy scheduler does not
 * delay it, and stores the result exactly like the scheduler would.
 */
class DjDiagnostics(
    private val resolver: AudioSourceResolver,
    private val decoder: TrackDecoder,
    private val analyzer: TrackAnalyzer,
    private val store: AnalysisStore,
    private val scope: CoroutineScope,
    private val selfCheck: () -> List<SelfCheckResult>,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val onStored: (String) -> Unit = {},
) {
    private val _steps = MutableStateFlow<List<ProbeStep>>(emptyList())
    val steps: StateFlow<List<ProbeStep>> = _steps.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _selfCheck = MutableStateFlow<List<SelfCheckResult>>(emptyList())
    val selfCheckResults: StateFlow<List<SelfCheckResult>> = _selfCheck.asStateFlow()

    private var job: Job? = null

    fun runSelfCheck() {
        scope.launch(compute) { _selfCheck.value = selfCheck() }
    }

    /** Starts the probe for [videoId]; ignored while one is running. */
    fun runNow(videoId: String?) {
        if (_running.value) return
        if (videoId == null) {
            _steps.value = listOf(ProbeStep("track", ProbeStep.State.FAILED, "nothing is playing"))
            return
        }
        _running.value = true
        _steps.value = emptyList()
        job =
            scope.launch {
                try {
                    probe(videoId)
                } catch (e: CancellationException) {
                    throw e
                } finally {
                    _running.value = false
                }
            }
    }

    private suspend fun probe(id: String) {
        DjLog.i(TAG, "=== run analysis now: $id ===")
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000

        if (!step("stored?") {
                if (store.has(id, analyzer.id)) "already analysed by ${analyzer.id}; re-running anyway" else "no analysis by ${analyzer.id} yet"
            }
        ) {
            return
        }
        var resolvedDetail = ""
        if (!step("1/4 resolve audio") {
                val r = resolver.resolve(id, allowNetwork = true) ?: throw IllegalStateException("no audio source (no cache, no stream url)")
                resolvedDetail = "${r.origin} via ${r.input.describe()}"
                r.input.close()
                resolvedDetail
            }
        ) {
            return
        }
        var pcm: org.simpmusic.dj.model.PcmAudio? = null
        if (!step("2/4 decode") {
                val p = decoder.decodeForAnalysis(id, allowNetwork = true, cancel = CancelSignal.NEVER)
                pcm = p
                "${p.samples.size} samples @${p.sampleRate} Hz = ${p.durationMs} ms of audio"
            }
        ) {
            return
        }
        var analysis: org.simpmusic.dj.model.TrackAnalysis? = null
        if (!step("3/4 analyse") {
                val a = withContext(compute) { analyzer.analyze(id, pcm!!) }
                analysis = a
                "${a.analyzerId}: bpm=${a.bpm?.value?.let { "%.1f".format(it) }} key=${a.key?.value?.camelot()} beats=${a.beatTimesMs?.value?.size} downbeats=${a.downbeatBeatIndices?.value?.size}"
            }
        ) {
            return
        }
        if (!step("4/4 store") {
                store.put(analysis!!)
                onStored(id)
                "stored (${store.count()} analyses on disk)"
            }
        ) {
            return
        }
        DjLog.i(TAG, "=== run analysis now finished OK in ${ms()} ms ===")
    }

    /** Runs one step, appending RUNNING then OK/FAILED to [steps]. Returns false on failure. */
    private suspend fun step(name: String, block: suspend () -> String): Boolean {
        _steps.update { it + ProbeStep(name, ProbeStep.State.RUNNING) }
        val t0 = System.nanoTime()
        return try {
            val detail = block()
            val ms = (System.nanoTime() - t0) / 1_000_000
            DjLog.i(TAG, "$name OK in $ms ms: $detail")
            replaceLast(ProbeStep(name, ProbeStep.State.OK, detail, ms))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val ms = (System.nanoTime() - t0) / 1_000_000
            DjLog.e(TAG, "$name FAILED after $ms ms: ${e.message}", e)
            replaceLast(ProbeStep(name, ProbeStep.State.FAILED, "${e.javaClass.simpleName}: ${e.message}", ms))
            false
        }
    }

    private fun replaceLast(s: ProbeStep) {
        _steps.update { it.dropLast(1) + s }
    }

    private companion object {
        const val TAG = "probe"
    }
}
