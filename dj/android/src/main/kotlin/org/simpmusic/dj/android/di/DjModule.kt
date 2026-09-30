@file:OptIn(UnstableApi::class)

package org.simpmusic.dj.android.di

import android.os.Process
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import com.maxrave.common.Config.DOWNLOAD_CACHE
import com.maxrave.common.Config.PLAYER_CACHE
import com.maxrave.common.Config.SERVICE_SCOPE
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
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
import org.simpmusic.dj.android.auto.AnalysisPrefetcher
import org.simpmusic.dj.android.auto.AnalysisRequester
import org.simpmusic.dj.android.auto.AutoDjController
import org.simpmusic.dj.android.auto.DjRuntime
import org.simpmusic.dj.android.auto.HandlerPlayerPort
import org.simpmusic.dj.android.decode.AudioSourceResolver
import org.simpmusic.dj.android.decode.CacheFirstAudioSourceResolver
import org.simpmusic.dj.android.diag.DjDiagnostics
import org.simpmusic.dj.android.diag.DjSelfCheck
import org.simpmusic.dj.android.library.DomainLibrarySource
import org.simpmusic.dj.android.library.LibraryAnalysisCoordinator
import org.simpmusic.dj.android.library.LibraryAnalysisPolicy
import org.simpmusic.dj.android.library.LibrarySource
import org.simpmusic.dj.android.library.SchedulerBackgroundPort
import org.simpmusic.dj.android.log.DjLog
import org.simpmusic.dj.android.log.LoggingPlanner
import org.simpmusic.dj.android.log.LoggingRenderer
import org.simpmusic.dj.android.recommend.AnalysisPool
import org.simpmusic.dj.android.recommend.DjPlayerPort
import org.simpmusic.dj.android.recommend.DjRecommendationService
import org.simpmusic.dj.model.AnalysisPriority
import org.simpmusic.dj.android.decode.MediaCodecTrackDecoder
import org.simpmusic.dj.android.decode.RepositoryStreamUrlProvider
import org.simpmusic.dj.android.decode.TrackDecoder
import org.simpmusic.dj.android.render.TransitionWindowRenderer
import org.simpmusic.dj.android.render.BrainWindowRenderer
import org.simpmusic.dj.android.scheduler.AndroidDeviceConditions
import org.simpmusic.dj.android.scheduler.AnalysisPolicy
import org.simpmusic.dj.android.scheduler.DeviceConditions
import org.simpmusic.dj.android.scheduler.DjAnalysisScheduler
import org.simpmusic.dj.android.analysis.DjAnalyzerFactory
import org.simpmusic.dj.android.settings.DjSettingsRepository
import org.simpmusic.dj.android.store.AnalysisStore
import org.simpmusic.dj.model.DjSettings
import org.simpmusic.dj.model.TrackAnalyzer
import org.simpmusic.dj.model.TransitionPlanner
import org.simpmusic.dj.planner.DjTransitionPlanner
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
 * The analyzer is the DSP analyzer with the Beat This! grid overlaid when its model asset is bundled, the planner is the
 * brain's [DjTransitionPlanner] and the renderer is the brain's OfflineMixRenderer behind [BrainWindowRenderer]. When a
 * plan or a render is not possible the engine falls back to the app's normal crossfade.
 */
val djModule =
    module {
        // First, and eagerly: everything below logs, and the log must exist (file + Logcat mirror) before the first line.
        single<DjLogInstaller>(createdAtStart = true) {
            DjLog.install(File(androidContext().filesDir, "dj"))
            DjLog.i("boot", "DJ log installed: file=${File(androidContext().filesDir, "dj/dj-debug.log").path} pid=${android.os.Process.myPid()} sdk=${android.os.Build.VERSION.SDK_INT} device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} abi=${android.os.Build.SUPPORTED_ABIS.joinToString()}")
            DjLogInstaller
        }
        single { DjSettingsRepository(androidContext()) }
        single<StateFlow<DjSettings>>(named("djSettings")) {
            get<DjLogInstaller>()
            get<DjSettingsRepository>().settings.stateIn(get<CoroutineScope>(named(SERVICE_SCOPE)), SharingStarted.Eagerly, DjSettings())
        }
        single<CoroutineDispatcher>(ANALYSIS_DISPATCHER) { backgroundDispatcher("dj-analysis") }
        single<CoroutineDispatcher>(RENDER_DISPATCHER) { backgroundDispatcher("dj-render") }

        single { AnalysisStore(File(androidContext().filesDir, "dj/analysis")) }
        single<DeviceConditions> { AndroidDeviceConditions(androidContext()) }

        single<TrackAnalyzer> { get<DjLogInstaller>(); DjAnalyzerFactory.create(androidContext()) }
        single<TransitionPlanner> { LoggingPlanner(DjTransitionPlanner()) }
        single<TransitionWindowRenderer> { LoggingRenderer(BrainWindowRenderer()) }

        single<AudioSourceResolver> { audioResolver() }
        single<TrackDecoder>(ANALYSIS_DECODER) { MediaCodecTrackDecoder(get(), get(ANALYSIS_DISPATCHER)) }
        single<TrackDecoder>(WINDOW_DECODER) { MediaCodecTrackDecoder(get(), get(RENDER_DISPATCHER)) }

        // "Analyze on mobile data" applies to the playing / next track; it defaults to ON (about 4 MB per track).
        single<StateFlow<Boolean>>(named("djAnalyzeOnMetered")) {
            get<DjSettingsRepository>().analyzeOnMetered.stateIn(get<CoroutineScope>(named(SERVICE_SCOPE)), SharingStarted.Eagerly, true)
        }

        single {
            val analyzeOnMetered = get<StateFlow<Boolean>>(named("djAnalyzeOnMetered"))
            DjAnalysisScheduler(
                store = get(),
                analyzer = get(),
                decoder = get(ANALYSIS_DECODER),
                policy = AnalysisPolicy(get()) { analyzeOnMetered.value },
                scope = CoroutineScope(SupervisorJob() + get<CoroutineDispatcher>(ANALYSIS_DISPATCHER)),
                worker = get(ANALYSIS_DISPATCHER),
            )
        }

        single<DjHooks> {
            val librarySource = get<LibrarySource>()
            val engine =
                DjEngine(
                    scope = get(named(SERVICE_SCOPE)),
                    scheduler = get(),
                    planner = get(),
                    renderer = get(),
                    decoder = get(WINDOW_DECODER),
                    settingsFlow = get(named("djSettings")),
                    heavyDispatcher = get(RENDER_DISPATCHER),
                    windowDir = File(androidContext().cacheDir, "dj/windows"),
                    titleOf = { id -> librarySource.find(id)?.title },
                )
            // The library analysis, the recommender, Auto DJ and the diagnostics start with the engine (they idle while off).
            // A failure here must never take the player down with it: the transition engine works without any of this.
            try {
                get<DjRuntime>().start()
            } catch (e: Throwable) {
                DjLog.e("boot", "DJ runtime could not be created: library analysis, recommendations, Auto DJ and the prefetcher are OFF", e)
            }
            engine
        }
        single<DjEngine> { get<DjHooks>() as DjEngine }

        // ---- library analysis, recommendations, Auto DJ, diagnostics ----
        single<LibrarySource> { DomainLibrarySource(get()) }
        single<DjPlayerPort> { HandlerPlayerPort({ get<MediaPlayerHandler>() }, get()) }
        single {
            val store = get<AnalysisStore>()
            val analyzerId = get<TrackAnalyzer>().id
            AnalysisPool(get(), AnalysisPool.storeLookup { id -> store.get(id, analyzerId) })
        }
        single {
            LibraryAnalysisCoordinator(
                source = get(),
                port = SchedulerBackgroundPort(get(), get(), get<TrackAnalyzer>().id),
                policy = LibraryAnalysisPolicy(get()),
                transitionBusy = { get<DjEngine>().debug.value.isHeavy },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                log = { DjLog.i("library", it) },
            )
        }
        single {
            val settings = get<StateFlow<DjSettings>>(named("djSettings"))
            val scheduler = get<DjAnalysisScheduler>()
            DjRecommendationService(
                pool = get(),
                source = get(),
                analyses = scheduler,
                planner = get(),
                settings = { settings.value },
                player = get(),
                analyzerAvailable = { scheduler.state.value.analyzerAvailable },
            )
        }
        single {
            AutoDjController(
                scope = get(named(SERVICE_SCOPE)),
                settings = get(named("djSettings")),
                port = get(),
                pool = get(),
                analyses = get<DjAnalysisScheduler>(),
                source = get(),
                planner = get(),
                log = { DjLog.i("autodj", it) },
            )
        }
        single {
            val scheduler = get<DjAnalysisScheduler>()
            AnalysisPrefetcher(
                scope = get(named(SERVICE_SCOPE)),
                settings = get(named("djSettings")),
                port = get(),
                requester =
                    object : AnalysisRequester {
                        override suspend fun request(videoId: String, priority: AnalysisPriority) = scheduler.request(videoId, priority)

                        override fun cancelStale(current: String?, next: String?) = scheduler.cancelStale(current, next)
                    },
            )
        }
        single {
            val store = get<AnalysisStore>()
            val pool = get<AnalysisPool>()
            val scheduler = get<DjAnalysisScheduler>()
            val selfCheck = DjSelfCheck(androidContext(), File(androidContext().filesDir, "dj/analysis"))
            DjDiagnostics(
                resolver = get(),
                decoder = MediaCodecTrackDecoder(get(), Dispatchers.IO),
                analyzer = get(),
                store = store,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                selfCheck = { selfCheck.run() },
                onStored = {
                    pool.invalidate(it)
                    scheduler.announceStored(it)
                },
            )
        }
        single {
            DjRuntime(
                scope = get(named(SERVICE_SCOPE)),
                settings = get(named("djSettings")),
                repository = get(),
                coordinator = get(),
                controller = get(),
                prefetcher = get(),
                diagnostics = get(),
            )
        }
    }

/** Marker returned by the eager log-installer definition. */
object DjLogInstaller

private fun org.koin.core.scope.Scope.audioResolver(): CacheFirstAudioSourceResolver =
    CacheFirstAudioSourceResolver.fromCaches(
        downloads = get<SimpleCache>(named(DOWNLOAD_CACHE)),
        player = get<SimpleCache>(named(PLAYER_CACHE)),
        urls = RepositoryStreamUrlProvider(get(), get()),
    )

/** Registers [djModule]. Idempotent enough for the app's single load site. */
fun loadDjModule() {
    loadKoinModules(djModule)
}
