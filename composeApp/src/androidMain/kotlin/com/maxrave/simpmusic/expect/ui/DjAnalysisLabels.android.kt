package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import org.simpmusic.dj.android.scheduler.AnalysisStatus
import org.simpmusic.dj.android.scheduler.BlockReason
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.ai_dj_analyzer_missing
import simpmusic.composeapp.generated.resources.ai_dj_status_analysed
import simpmusic.composeapp.generated.resources.ai_dj_status_analysing
import simpmusic.composeapp.generated.resources.ai_dj_status_blocked_battery_saver
import simpmusic.composeapp.generated.resources.ai_dj_status_blocked_low_battery
import simpmusic.composeapp.generated.resources.ai_dj_status_blocked_mobile_data
import simpmusic.composeapp.generated.resources.ai_dj_status_failed
import simpmusic.composeapp.generated.resources.ai_dj_status_failed_unknown
import simpmusic.composeapp.generated.resources.ai_dj_status_needs_network
import simpmusic.composeapp.generated.resources.ai_dj_status_not_requested
import simpmusic.composeapp.generated.resources.ai_dj_status_queued
import simpmusic.composeapp.generated.resources.ai_dj_status_retrying
import simpmusic.composeapp.generated.resources.ai_dj_status_retrying_with_error

/** One short phrase for what is happening to a track's analysis ("analysing (12 s)", "blocked: battery saver", "failed: ..."). */
@Composable
fun AnalysisStatus?.label(): String =
    when (this) {
        null -> stringResource(Res.string.ai_dj_status_not_requested)
        AnalysisStatus.Analysed -> stringResource(Res.string.ai_dj_status_analysed)
        AnalysisStatus.NotRequested -> stringResource(Res.string.ai_dj_status_not_requested)
        AnalysisStatus.Queued -> stringResource(Res.string.ai_dj_status_queued)
        is AnalysisStatus.Analysing -> stringResource(Res.string.ai_dj_status_analysing, seconds)
        is AnalysisStatus.Blocked ->
            when (reason) {
                BlockReason.BATTERY_SAVER -> stringResource(Res.string.ai_dj_status_blocked_battery_saver)
                BlockReason.LOW_BATTERY -> stringResource(Res.string.ai_dj_status_blocked_low_battery)
            }
        is AnalysisStatus.NeedsNetwork ->
            if (metered) stringResource(Res.string.ai_dj_status_blocked_mobile_data) else stringResource(Res.string.ai_dj_status_needs_network)
        is AnalysisStatus.Retrying ->
            if (lastError != null) {
                stringResource(Res.string.ai_dj_status_retrying_with_error, inSeconds, lastError!!)
            } else {
                stringResource(Res.string.ai_dj_status_retrying, inSeconds)
            }
        is AnalysisStatus.Failed -> if (message != null) stringResource(Res.string.ai_dj_status_failed, message!!) else stringResource(Res.string.ai_dj_status_failed_unknown)
        AnalysisStatus.AnalyzerMissing -> stringResource(Res.string.ai_dj_analyzer_missing)
    }
