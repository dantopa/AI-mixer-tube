package com.maxrave.simpmusic.expect.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxrave.simpmusic.ui.component.SettingItem
import com.maxrave.simpmusic.viewModel.SettingAlertState
import com.maxrave.simpmusic.viewModel.SettingsViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.simpmusic.dj.android.DjHooks
import org.simpmusic.dj.android.scheduler.DjAnalysisScheduler
import org.simpmusic.dj.android.settings.DjSettingsRepository
import org.simpmusic.dj.model.DjSettings
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.ai_dj_allow_key_shift
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

    val settings by repo.settings.collectAsStateWithLifecycle(DjSettings())
    val debug by hooks.debug.collectAsStateWithLifecycle()
    val scheduling by scheduler.state.collectAsStateWithLifecycle()

    val notCasting = !castRemote
    val overlapTitle = stringResource(Res.string.ai_dj_overlap_bars)
    val tempoTitle = stringResource(Res.string.ai_dj_max_tempo_bend)
    val confirm = stringResource(Res.string.change)
    val dismiss = stringResource(Res.string.cancel)
    val barLabels = OVERLAP_BAR_CHOICES.map { it to stringResource(Res.string.ai_dj_bars, it) }
    val tempoLabels = TEMPO_BEND_CHOICES.map { it to "±${(it * 100).roundToInt()}%" }

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
                    isEnable = notCasting,
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
                    isEnable = notCasting,
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
                    isEnable = notCasting,
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
                        },
                    smallSubtitle = true,
                )
            }
        }
    }
}
