package com.maxrave.simpmusic.expect.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxrave.simpmusic.ui.component.SettingItem
import com.maxrave.simpmusic.viewModel.SettingAlertState
import com.maxrave.simpmusic.viewModel.SettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.simpmusic.dj.android.DjHooks
import org.simpmusic.dj.android.auto.AutoDjController
import org.simpmusic.dj.android.auto.AutoDjDecision
import org.simpmusic.dj.android.library.AnalysisPause
import org.simpmusic.dj.android.library.LibraryAnalysisCoordinator
import org.simpmusic.dj.android.scheduler.DjAnalysisScheduler
import org.simpmusic.dj.android.settings.DjSettingsRepository
import org.simpmusic.dj.model.DjSettings
import org.simpmusic.dj.model.EnergyArc
import org.simpmusic.dj.model.MixPoint
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.ai_dj_allow_key_shift
import simpmusic.composeapp.generated.resources.ai_dj_analyze_on_metered
import simpmusic.composeapp.generated.resources.ai_dj_analyze_on_metered_description
import simpmusic.composeapp.generated.resources.ai_dj_mix_anywhere
import simpmusic.composeapp.generated.resources.ai_dj_simple_mode
import simpmusic.composeapp.generated.resources.ai_dj_simple_mode_description
import simpmusic.composeapp.generated.resources.ai_dj_mix_anywhere_description
import simpmusic.composeapp.generated.resources.ai_dj_arc_build
import simpmusic.composeapp.generated.resources.ai_dj_arc_cool_down
import simpmusic.composeapp.generated.resources.ai_dj_arc_steady
import simpmusic.composeapp.generated.resources.ai_dj_arc_wave
import simpmusic.composeapp.generated.resources.ai_dj_auto
import simpmusic.composeapp.generated.resources.ai_dj_auto_description
import simpmusic.composeapp.generated.resources.ai_dj_auto_last
import simpmusic.composeapp.generated.resources.ai_dj_auto_none
import simpmusic.composeapp.generated.resources.ai_dj_diag_errors
import simpmusic.composeapp.generated.resources.ai_dj_diag_next
import simpmusic.composeapp.generated.resources.ai_dj_diag_playing
import simpmusic.composeapp.generated.resources.ai_dj_energy_arc
import simpmusic.composeapp.generated.resources.ai_dj_library_analyse
import simpmusic.composeapp.generated.resources.ai_dj_library_description
import simpmusic.composeapp.generated.resources.ai_dj_library_done
import simpmusic.composeapp.generated.resources.ai_dj_library_pause
import simpmusic.composeapp.generated.resources.ai_dj_library_pause_analyzer
import simpmusic.composeapp.generated.resources.ai_dj_library_pause_battery_saver
import simpmusic.composeapp.generated.resources.ai_dj_library_pause_low_battery
import simpmusic.composeapp.generated.resources.ai_dj_library_pause_not_charging
import simpmusic.composeapp.generated.resources.ai_dj_library_pause_hot
import simpmusic.composeapp.generated.resources.ai_dj_library_pause_metered
import simpmusic.composeapp.generated.resources.ai_dj_library_pause_transition
import simpmusic.composeapp.generated.resources.ai_dj_library_nothing_to_do
import simpmusic.composeapp.generated.resources.ai_dj_library_progress
import simpmusic.composeapp.generated.resources.ai_dj_log
import simpmusic.composeapp.generated.resources.ai_dj_log_description
import simpmusic.composeapp.generated.resources.ai_dj_allow_key_shift_description
import simpmusic.composeapp.generated.resources.ai_dj_analyzer_missing
import simpmusic.composeapp.generated.resources.ai_dj_bars
import simpmusic.composeapp.generated.resources.ai_dj_bass_swap
import simpmusic.composeapp.generated.resources.ai_dj_bass_swap_description
import simpmusic.composeapp.generated.resources.ai_dj_max_tempo_bend
import simpmusic.composeapp.generated.resources.ai_dj_max_tempo_bend_description
import simpmusic.composeapp.generated.resources.ai_dj_mode
import simpmusic.composeapp.generated.resources.ai_dj_mode_description
import simpmusic.composeapp.generated.resources.ai_dj_no_plan_yet
import simpmusic.composeapp.generated.resources.ai_dj_overlap_bars
import simpmusic.composeapp.generated.resources.ai_dj_overlap_bars_description
import simpmusic.composeapp.generated.resources.ai_dj_why
import simpmusic.composeapp.generated.resources.cancel
import simpmusic.composeapp.generated.resources.change
import simpmusic.composeapp.generated.resources.not_available_while_casting
import kotlin.math.roundToInt

private val OVERLAP_BAR_CHOICES = listOf(4, 8, 16, 32)
private val TEMPO_BEND_CHOICES = listOf(0.04f, 0.06f, 0.08f, 0.10f, 0.12f, 0.16f)

@Composable
actual fun rememberDjModeEnabled(): Boolean {
    val repo = koinInject<DjSettingsRepository>()
    val settings by repo.settings.collectAsStateWithLifecycle(DjSettings())
    return settings.enabled
}

@Composable
actual fun rememberDjFallbackSeconds(): Int {
    val repo = koinInject<DjSettingsRepository>()
    val settings by repo.settings.collectAsStateWithLifecycle(DjSettings())
    return (settings.fallbackCrossfadeMs / 1000L).toInt()
}

@Composable
actual fun DjSettingsSection(castRemote: Boolean) {
    val repo = koinInject<DjSettingsRepository>()
    val hooks = koinInject<DjHooks>()
    val scheduler = koinInject<DjAnalysisScheduler>()
    val viewModel: SettingsViewModel = koinViewModel()
    val scope = rememberCoroutineScope()

    val coordinator = koinInject<LibraryAnalysisCoordinator>()
    val autoDj = koinInject<AutoDjController>()

    val settings by repo.settings.collectAsStateWithLifecycle(DjSettings())
    val simpleMode by repo.simpleMode.collectAsStateWithLifecycle(DjSettingsRepository.DEFAULT_SIMPLE_MODE)
    val analyzeOnMetered by repo.analyzeOnMetered.collectAsStateWithLifecycle(DjSettingsRepository.DEFAULT_ANALYZE_ON_METERED)
    val debug by hooks.debug.collectAsStateWithLifecycle()
    val scheduling by scheduler.state.collectAsStateWithLifecycle()
    val library by coordinator.state.collectAsStateWithLifecycle()
    val lastAuto by autoDj.lastDecision.collectAsStateWithLifecycle()
    var showLog by remember { mutableStateOf(false) }

    // "N of M" also has to move when the playing track's own analysis lands, not only when this screen starts something.
    LaunchedEffect(Unit) {
        while (true) {
            coordinator.refreshProgress()
            delay(5000)
        }
    }

    val notCasting = !castRemote
    val overlapTitle = stringResource(Res.string.ai_dj_overlap_bars)
    val tempoTitle = stringResource(Res.string.ai_dj_max_tempo_bend)
    val confirm = stringResource(Res.string.change)
    val dismiss = stringResource(Res.string.cancel)
    val barLabels = OVERLAP_BAR_CHOICES.map { it to stringResource(Res.string.ai_dj_bars, it) }
    val tempoLabels = TEMPO_BEND_CHOICES.map { it to "±${(it * 100).roundToInt()}%" }
    val arcTitle = stringResource(Res.string.ai_dj_energy_arc)
    val arcLabels =
        listOf(
            EnergyArc.STEADY to stringResource(Res.string.ai_dj_arc_steady),
            EnergyArc.BUILD to stringResource(Res.string.ai_dj_arc_build),
            EnergyArc.COOL_DOWN to stringResource(Res.string.ai_dj_arc_cool_down),
            EnergyArc.WAVE to stringResource(Res.string.ai_dj_arc_wave),
        )
    val libraryDone = stringResource(Res.string.ai_dj_library_done)
    val pauseLabels =
        mapOf(
            AnalysisPause.BATTERY_SAVER to stringResource(Res.string.ai_dj_library_pause_battery_saver),
            AnalysisPause.LOW_BATTERY to stringResource(Res.string.ai_dj_library_pause_low_battery),
            AnalysisPause.NOT_CHARGING to stringResource(Res.string.ai_dj_library_pause_not_charging),
            AnalysisPause.HOT to stringResource(Res.string.ai_dj_library_pause_hot),
            AnalysisPause.METERED_NETWORK to stringResource(Res.string.ai_dj_library_pause_metered),
            AnalysisPause.TRANSITION_RENDERING to stringResource(Res.string.ai_dj_library_pause_transition),
            AnalysisPause.ANALYZER_MISSING to stringResource(Res.string.ai_dj_library_pause_analyzer),
        )
    val playingLine = debug.currentAnalysis
    val nextLine = debug.nextAnalysis
    val playingText = if (playingLine != null && playingLine != org.simpmusic.dj.android.scheduler.AnalysisStatus.Analysed) stringResource(Res.string.ai_dj_diag_playing, playingLine.label()) else null
    val nextText = if (nextLine != null && nextLine != org.simpmusic.dj.android.scheduler.AnalysisStatus.Analysed) stringResource(Res.string.ai_dj_diag_next, nextLine.label()) else null
    val errorsText = if (debug.recentErrors.isNotEmpty()) stringResource(Res.string.ai_dj_diag_errors, debug.recentErrors.joinToString(" | ")) else null

    if (showLog) DjLogViewerDialog(onDismiss = { showLog = false })
    Column {
        SettingItem(
            title = stringResource(Res.string.ai_dj_mode),
            subtitle =
                if (castRemote) {
                    stringResource(Res.string.not_available_while_casting)
                } else {
                    stringResource(Res.string.ai_dj_mode_description)
                },
            smallSubtitle = true,
            switch = (settings.enabled to { on -> scope.launch { repo.setEnabled(on) }; Unit }),
            isEnable = notCasting,
        )
        AnimatedVisibility(visible = settings.enabled) {
            Column {
                SettingItem(
                    title = stringResource(Res.string.ai_dj_simple_mode),
                    subtitle = stringResource(Res.string.ai_dj_simple_mode_description),
                    smallSubtitle = true,
                    switch = (simpleMode to { on -> scope.launch { repo.setSimpleMode(on) }; Unit }),
                    isEnable = notCasting,
                )
                SettingItem(
                    title = overlapTitle,
                    subtitle =
                        if (castRemote) {
                            stringResource(Res.string.not_available_while_casting)
                        } else {
                            stringResource(Res.string.ai_dj_bars, settings.overlapBars) + " · " +
                                stringResource(Res.string.ai_dj_overlap_bars_description)
                        },
                    smallSubtitle = true,
                    isEnable = notCasting,
                    onClick = {
                        viewModel.setAlertData(
                            SettingAlertState(
                                title = overlapTitle,
                                selectOne =
                                    SettingAlertState.SelectData(
                                        listSelect = barLabels.map { (bars, label) -> (bars == settings.overlapBars) to label },
                                    ),
                                confirm =
                                    confirm to { state ->
                                        val picked = barLabels.firstOrNull { it.second == state.selectOne?.getSelected() }?.first
                                        if (picked != null) scope.launch { repo.setOverlapBars(picked) }
                                    },
                                dismiss = dismiss,
                            ),
                        )
                    },
                )
                SettingItem(
                    title = tempoTitle,
                    subtitle =
                        if (castRemote) {
                            stringResource(Res.string.not_available_while_casting)
                        } else {
                            "±${(settings.maxTempoBend * 100).roundToInt()}% · " + stringResource(Res.string.ai_dj_max_tempo_bend_description)
                        },
                    smallSubtitle = true,
                    isEnable = notCasting && !simpleMode,
                    onClick = {
                        viewModel.setAlertData(
                            SettingAlertState(
                                title = tempoTitle,
                                selectOne =
                                    SettingAlertState.SelectData(
                                        listSelect = tempoLabels.map { (bend, label) -> (kotlin.math.abs(bend - settings.maxTempoBend) < 0.004f) to label },
                                    ),
                                confirm =
                                    confirm to { state ->
                                        val picked = tempoLabels.firstOrNull { it.second == state.selectOne?.getSelected() }?.first
                                        if (picked != null) scope.launch { repo.setMaxTempoBend(picked) }
                                    },
                                dismiss = dismiss,
                            ),
                        )
                    },
                )
                SettingItem(
                    title = stringResource(Res.string.ai_dj_allow_key_shift),
                    subtitle =
                        if (castRemote) {
                            stringResource(Res.string.not_available_while_casting)
                        } else {
                            stringResource(Res.string.ai_dj_allow_key_shift_description)
                        },
                    smallSubtitle = true,
                    switch = (settings.allowKeyShift to { on -> scope.launch { repo.setAllowKeyShift(on) }; Unit }),
                    isEnable = notCasting && !simpleMode,
                )
                SettingItem(
                    title = stringResource(Res.string.ai_dj_bass_swap),
                    subtitle =
                        if (castRemote) {
                            stringResource(Res.string.not_available_while_casting)
                        } else {
                            stringResource(Res.string.ai_dj_bass_swap_description)
                        },
                    smallSubtitle = true,
                    switch = (settings.bassSwap to { on -> scope.launch { repo.setBassSwap(on) }; Unit }),
                    isEnable = notCasting && !simpleMode,
                )
                SettingItem(
                    title = stringResource(Res.string.ai_dj_mix_anywhere),
                    subtitle = stringResource(Res.string.ai_dj_mix_anywhere_description),
                    smallSubtitle = true,
                    switch = ((settings.mixPoint == MixPoint.ANYWHERE) to { on -> scope.launch { repo.setMixAnywhere(on) }; Unit }),
                    isEnable = notCasting,
                )
                SettingItem(
                    title = stringResource(Res.string.ai_dj_analyze_on_metered),
                    subtitle = stringResource(Res.string.ai_dj_analyze_on_metered_description),
                    smallSubtitle = true,
                    switch = (analyzeOnMetered to { on -> scope.launch { repo.setAnalyzeOnMetered(on) }; Unit }),
                )
                // ---- Auto DJ ----
                SettingItem(
                    title = stringResource(Res.string.ai_dj_auto),
                    subtitle = stringResource(Res.string.ai_dj_auto_description),
                    smallSubtitle = true,
                    switch = (settings.autoDj to { on -> scope.launch { repo.setAutoDj(on) }; Unit }),
                    isEnable = notCasting,
                )
                AnimatedVisibility(visible = settings.autoDj) {
                    Column {
                        SettingItem(
                            title = arcTitle,
                            subtitle = arcLabels.first { it.first == settings.autoDjArc }.second,
                            smallSubtitle = true,
                            onClick = {
                                viewModel.setAlertData(
                                    SettingAlertState(
                                        title = arcTitle,
                                        selectOne = SettingAlertState.SelectData(listSelect = arcLabels.map { (arc, label) -> (arc == settings.autoDjArc) to label }),
                                        confirm =
                                            confirm to { state ->
                                                val picked = arcLabels.firstOrNull { it.second == state.selectOne?.getSelected() }?.first
                                                if (picked != null) scope.launch { repo.setAutoDjArc(picked) }
                                            },
                                        dismiss = dismiss,
                                    ),
                                )
                            },
                        )
                        SettingItem(
                            title = stringResource(Res.string.ai_dj_auto_last),
                            subtitle = lastAuto?.summary() ?: stringResource(Res.string.ai_dj_auto_none),
                            smallSubtitle = true,
                        )
                    }
                }
                // ---- library analysis ----
                SettingItem(
                    title = stringResource(Res.string.ai_dj_library_progress, library.analysed, library.total),
                    subtitle =
                        buildString {
                            append(stringResource(Res.string.ai_dj_library_description))
                            if (library.finished) append("\n").append(libraryDone)
                            library.pause?.let { append("\n").append(pauseLabels.getValue(it)) }
                        },
                    smallSubtitle = true,
                )
                SettingItem(
                    title = if (library.running) stringResource(Res.string.ai_dj_library_pause) else stringResource(Res.string.ai_dj_library_analyse),
                    // Without this the button looked like it bounced: with nothing left to analyse it finishes at once and flips back.
                    subtitle =
                        when {
                            library.running -> stringResource(Res.string.ai_dj_library_progress, library.analysed, library.total)
                            library.finished && library.analysed >= library.total -> stringResource(Res.string.ai_dj_library_nothing_to_do, library.total)
                            else -> ""
                        },
                    smallSubtitle = true,
                    onClick = {
                        if (library.running) {
                            coordinator.stop()
                            scope.launch { repo.setLibraryAnalysis(false) }
                        } else {
                            scope.launch { repo.setLibraryAnalysis(true) }
                            coordinator.start()
                        }
                    },
                )
                SettingItem(
                    title = stringResource(Res.string.ai_dj_log),
                    subtitle = stringResource(Res.string.ai_dj_log_description),
                    smallSubtitle = true,
                    onClick = { showLog = true },
                )
                // The "why" line: what the planner last decided and on which facts, or why DJ mode sits out.
                SettingItem(
                    title = stringResource(Res.string.ai_dj_why),
                    subtitle =
                        buildString {
                            if (!scheduling.analyzerAvailable) {
                                append(stringResource(Res.string.ai_dj_analyzer_missing))
                            } else if (debug.kind == null && debug.reason == null && debug.lastOutcome == null && debug.phase == "idle") {
                                append(stringResource(Res.string.ai_dj_no_plan_yet))
                            } else {
                                append(debug.summary())
                            }
                            playingText?.let { append("\n").append(it) }
                            nextText?.let { append("\n").append(it) }
                            errorsText?.let { append("\n").append(it) }
                        },
                    smallSubtitle = true,
                )
            }
        }
    }
}
