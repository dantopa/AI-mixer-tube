package org.simpmusic.dj.android.auto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.simpmusic.dj.android.diag.DjDiagnostics
import org.simpmusic.dj.android.library.LibraryAnalysisCoordinator
import org.simpmusic.dj.android.log.DjLog
import org.simpmusic.dj.android.settings.DjSettingsRepository
import org.simpmusic.dj.model.DjSettings

/**
 * Starts the DJ's background parts once the player exists: the analysis prefetcher, Auto DJ, the library analysis (when the
 * user left it running) and the startup self-check (each time DJ mode is switched on). Everything here idles while the DJ
 * is off, so the app behaves exactly as before.
 *
 * Started from the DJ hooks' definition, which is created while the player is still being built, hence [startDelayMs]:
 * nothing may look for the player's handler before it exists.
 */
class DjRuntime(
    private val scope: CoroutineScope,
    private val settings: StateFlow<DjSettings>,
    private val repository: DjSettingsRepository,
    private val coordinator: LibraryAnalysisCoordinator,
    private val controller: AutoDjController,
    private val prefetcher: AnalysisPrefetcher,
    private val diagnostics: DjDiagnostics,
    private val startDelayMs: Long = 3_000L,
) {
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            delay(startDelayMs)
            DjLog.i("boot", "DJ runtime starting: settings=${settings.value}")
            prefetcher.start()
            controller.start()
            if (repository.libraryAnalysis.first()) {
                DjLog.i("boot", "library analysis was left running: resuming")
                coordinator.start()
            }
            settings.map { it.enabled }.distinctUntilChanged().collect { on ->
                DjLog.i("boot", "AI DJ mode ${if (on) "ON" else "OFF"}")
                if (on) diagnostics.runSelfCheck()
            }
        }
    }
}
