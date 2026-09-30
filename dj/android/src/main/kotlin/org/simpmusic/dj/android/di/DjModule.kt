@file:OptIn(UnstableApi::class)

package org.simpmusic.dj.android.di

import android.os.Process
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import com.maxrave.common.Config.DOWNLOAD_CACHE
import com.maxrave.common.Config.PLAYER_CACHE
import com.maxrave.common.Config.SERVICE_SCOPE
import com.maxrave.logger.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.loadKoinModules
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.simpmusic.dj.android.DjEngine
import org.simpmusic.dj.android.DjHooks
import org.simpmusic.dj.android.FallbackOnlyPlanner
import org.simpmusic.dj.android.decode.CacheFirstAudioSourceResolver
import org.simpmusic.dj.android.decode.MediaCodecTrackDecoder
import org.simpmusic.dj.android.decode.RepositoryStreamUrlProvider
import org.simpmusic.dj.android.decode.TrackDecoder
import org.simpmusic.dj.android.render.TransitionWindowRenderer
import org.simpmusic.dj.android.render.UnavailableWindowRenderer
import org.simpmusic.dj.android.scheduler.AndroidDeviceConditions
import org.simpmusic.dj.android.scheduler.AnalysisPolicy
import org.simpmusic.dj.android.scheduler.DeviceConditions
import org.simpmusic.dj.android.scheduler.DjAnalysisScheduler
import org.simpmusic.dj.android.scheduler.UnavailableAnalyzer
import org.simpmusic.dj.android.settings.DjSettingsRepository
import org.simpmusic.dj.android.store.AnalysisStore
import org.simpmusic.dj.model.DjSettings
import org.simpmusic.dj.model.TrackAnalyzer
import org.simpmusic.dj.model.TransitionPlanner
import java.io.File
import java.util.concurrent.Executors

private const val TAG = "DJ"
private val ANALYSIS_DISPATCHER = named("djAnalysisDispatcher")
private val RENDER_DISPATCHER = named("djRenderDispatcher")
private val ANALYSIS_DECODER = named("djAnalysisDecoder")
private val WINDOW_DECODER = named("djWindowDecoder")

/** A single daemon thread at background priority: DJ work must never compete with the playback threads. */
private fun backgroundDispatcher(name: String): CoroutineDispatcher =
    Executors
        .newSingleThreadExecutor { r ->
            Thread(
                {
                    try {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    } catch (_: Throwable) {
                    }
                    r.run()
                },
                name,
            ).apply { isDaemon = true }
        }.asCoroutineDispatcher()

/**
 * Koin bindings of the DJ. Loaded next to the media service module (see the core patch). It needs, from
 * that module: the service scope and the two SimpleCaches; from the app: StreamRepository/DataStoreManager.
 *
 * The three seams that other work plugs into are the only `single` bindings that will change:
 *  - [TrackAnalyzer]: `UnavailableAnalyzer` until the DSP/ML analyzer is merged,
 *  - [TransitionPlanner]: `FallbackOnlyPlanner` (always "plain crossfade") until the planner is merged,
 *  - [TransitionWindowRenderer]: `UnavailableWindowRenderer` until the offline renderer is wired.
 * Until all three exist DJ mode degrades to the app's normal crossfade.
 */
val djModule =
    module {
        single { DjSettingsRepository(androidContext()) }
        single<StateFlow<DjSettings>>(named("djSettings")) {
            get<DjSettingsRepository>().settings.stateIn(get<CoroutineScope>(named(SERVICE_SCOPE)), SharingStarted.Eagerly, DjSettings())
        }
        single<CoroutineDispatcher>(ANALYSIS_DISPATCHER) { backgroundDispatcher("dj-analysis") }
        single<CoroutineDispatcher>(RENDER_DISPATCHER) { backgroundDispatcher("dj-render") }

        single { AnalysisStore(File(androidContext().filesDir, "dj/analysis")) }
        single<DeviceConditions> { AndroidDeviceConditions(androidContext()) }

        single<TrackAnalyzer> { UnavailableAnalyzer() }
        single<TransitionPlanner> { FallbackOnlyPlanner() }
        single<TransitionWindowRenderer> { UnavailableWindowRenderer() }

        single<TrackDecoder>(ANALYSIS_DECODER) {
            MediaCodecTrackDecoder(audioResolver(), get(ANALYSIS_DISPATCHER)) { Logger.d(TAG, it) }
        }
        single<TrackDecoder>(WINDOW_DECODER) {
            MediaCodecTrackDecoder(audioResolver(), get(RENDER_DISPATCHER)) { Logger.d(TAG, it) }
        }

        single {
            val settingsRepo = get<DjSettingsRepository>()
            val scope = get<CoroutineScope>(named(SERVICE_SCOPE))
            val analyzeOnMetered = settingsRepo.analyzeOnMetered.stateIn(scope, SharingStarted.Eagerly, false)
            DjAnalysisScheduler(
                store = get(),
                analyzer = get(),
                decoder = get(ANALYSIS_DECODER),
                policy = AnalysisPolicy(get()) { analyzeOnMetered.value },
                scope = CoroutineScope(SupervisorJob() + get<CoroutineDispatcher>(ANALYSIS_DISPATCHER)),
                worker = get(ANALYSIS_DISPATCHER),
                log = { Logger.d(TAG, it) },
            )
        }

        single<DjHooks> {
            DjEngine(
                scope = get(named(SERVICE_SCOPE)),
                scheduler = get(),
                planner = get(),
                renderer = get(),
                decoder = get(WINDOW_DECODER),
                settingsFlow = get(named("djSettings")),
                heavyDispatcher = get(RENDER_DISPATCHER),
                windowDir = File(androidContext().cacheDir, "dj/windows"),
                logger = { Logger.d(TAG, it) },
            )
        }
        single<DjEngine> { get<DjHooks>() as DjEngine }
    }

private fun org.koin.core.scope.Scope.audioResolver(): CacheFirstAudioSourceResolver =
    CacheFirstAudioSourceResolver(
        caches = listOf(get<SimpleCache>(named(DOWNLOAD_CACHE)), get<SimpleCache>(named(PLAYER_CACHE))),
        urls = RepositoryStreamUrlProvider(get(), get()),
        log = { Logger.d(TAG, it) },
    )

/** Registers [djModule]. Idempotent enough for the app's single load site. */
fun loadDjModule() {
    loadKoinModules(djModule)
}
