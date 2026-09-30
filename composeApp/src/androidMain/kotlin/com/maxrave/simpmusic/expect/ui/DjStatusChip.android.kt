package com.maxrave.simpmusic.expect.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.SimpleMediaState
import com.maxrave.simpmusic.ui.component.dj.DjMixSheet
import com.maxrave.simpmusic.ui.icon.GraphicEq
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.theme.typo
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.simpmusic.dj.android.DjDebugState
import org.simpmusic.dj.android.DjHooks
import org.simpmusic.dj.android.scheduler.AnalysisStatus
import org.simpmusic.dj.android.scheduler.BlockReason
import org.simpmusic.dj.android.settings.DjSettingsRepository
import org.simpmusic.dj.model.DjSettings
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.ai_dj_status_blocked_battery_saver
import simpmusic.composeapp.generated.resources.ai_dj_status_blocked_low_battery
import simpmusic.composeapp.generated.resources.ai_dj_status_blocked_mobile_data
import simpmusic.composeapp.generated.resources.ai_dj_status_needs_network
import simpmusic.composeapp.generated.resources.dj_chip_analysing_current
import simpmusic.composeapp.generated.resources.dj_chip_analysing_next
import simpmusic.composeapp.generated.resources.dj_chip_analysis_failed
import simpmusic.composeapp.generated.resources.dj_chip_blocked
import simpmusic.composeapp.generated.resources.dj_chip_decoding
import simpmusic.composeapp.generated.resources.dj_chip_error
import simpmusic.composeapp.generated.resources.dj_chip_fallback
import simpmusic.composeapp.generated.resources.dj_chip_idle
import simpmusic.composeapp.generated.resources.dj_chip_last
import simpmusic.composeapp.generated.resources.dj_chip_mix_playing
import simpmusic.composeapp.generated.resources.dj_chip_mix_playing_simple
import simpmusic.composeapp.generated.resources.dj_chip_mixing
import simpmusic.composeapp.generated.resources.dj_chip_planning
import simpmusic.composeapp.generated.resources.dj_chip_ready
import simpmusic.composeapp.generated.resources.dj_chip_rendering
import simpmusic.composeapp.generated.resources.dj_chip_retrying
import simpmusic.composeapp.generated.resources.dj_chip_waiting

/**
 * One unobtrusive line on Now Playing, only while AI DJ mode is on: what the DJ engine is doing right now, in plain words
 * ("analysing next track… 12 s", "ready, mix in 0:42 (124→128 BPM, 8A→9A)", "mixing", "crossfade fallback: ..."). Tapping it
 * opens the DJ log. Reads the same [DjDebugState] the settings line reads.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun DjStatusChip(modifier: Modifier) {
    val settings = koinInject<DjSettingsRepository>()
    val enabled by settings.settings.collectAsStateWithLifecycle(DjSettings())
    if (!enabled.enabled) return

    val hooks = koinInject<DjHooks>()
    val handler = koinInject<MediaPlayerHandler>()
    val debug by hooks.debug.collectAsStateWithLifecycle()
    val media by handler.simpleMediaState.collectAsStateWithLifecycle()
    val mix by hooks.mixView.collectAsStateWithLifecycle()
    var showLog by remember { mutableStateOf(false) }
    var showMix by remember { mutableStateOf(false) }

    val positionMs = (media as? SimpleMediaState.Progress)?.progress ?: 0L
    val mixing = debug.isMixing
    // The mix countdown runs on the wall clock (the window's own timeline), so it needs a ticking clock while a mix plays.
    val nowMs by produceState(System.currentTimeMillis(), mixing) {
        while (mixing) {
            value = System.currentTimeMillis()
            delay(500)
        }
    }
    val text = chipText(debug, positionMs, nowMs)

    Surface(
        // Tap: the picture of the mix (both tracks overlapping) when there is one, else the log. Long-press: always the log.
        modifier = modifier.combinedClickable(onClick = { if (mix != null) showMix = true else showLog = true }, onLongClick = { showLog = true }),
        shape = CircleShape,
        // Unmistakable while a mix is actually playing: the accent colour instead of the quiet dark pill.
        color = if (mixing) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.45f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            androidx.compose.foundation.Image(
                imageVector = SimpIcons.GraphicEq,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.85f)),
            )
            Text(
                text = text,
                style = if (mixing) typo().labelMedium else typo().labelSmall,
                fontWeight = if (mixing) FontWeight.Bold else null,
                color = if (mixing) MaterialTheme.colorScheme.onPrimary else Color.White.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (showLog) DjLogViewerDialog(onDismiss = { showLog = false })
    // Read live: the sheet keeps following the engine's flow while it is open, and closes itself when the mix is gone.
    if (showMix) DjMixSheet(data = mix, onDismiss = { showMix = false })
}

@Composable
private fun chipText(d: DjDebugState, positionMs: Long, nowMs: Long): String {
    // The analysis of either track decides first: nothing else can happen until both exist.
    val cur = d.currentAnalysis
    val nxt = d.nextAnalysis
    return when (d.phase) {
        "waiting-analysis" -> analysisText(cur, nxt)
        "planning" -> stringResource(Res.string.dj_chip_planning)
        "decoding" -> stringResource(Res.string.dj_chip_decoding)
        "rendering" -> stringResource(Res.string.dj_chip_rendering)
        "mixing" -> {
            val remaining = ((d.mixStartedAtEpochMs ?: nowMs) + (d.mixDurationMs ?: 0L) - nowMs).coerceAtLeast(0L)
            val facts = mixFacts(d)
            if (facts.isEmpty()) {
                stringResource(Res.string.dj_chip_mix_playing_simple, formatMinSec(remaining))
            } else {
                stringResource(Res.string.dj_chip_mix_playing, facts, formatMinSec(remaining))
            }
        }
        "ready" -> {
            val remaining = (d.mixAtMs ?: 0L) - positionMs
            if (d.mixAtMs != null && remaining <= 0L) {
                stringResource(Res.string.dj_chip_mixing)
            } else {
                val facts = mixFacts(d).let { if (it.isEmpty()) "" else " ($it)" }
                stringResource(Res.string.dj_chip_ready, formatMinSec(remaining.coerceAtLeast(0L)), facts)
            }
        }
        "fallback" -> stringResource(Res.string.dj_chip_fallback, d.reason ?: d.kind?.name?.lowercase().orEmpty())
        "blocked" -> stringResource(Res.string.dj_chip_blocked, d.reason.orEmpty())
        "error" -> stringResource(Res.string.dj_chip_error, d.reason.orEmpty())
        else ->
            if (d.lastOutcome != null) stringResource(Res.string.dj_chip_last, d.lastOutcome!!) else stringResource(Res.string.dj_chip_idle)
    }
}

@Composable
private fun analysisText(cur: AnalysisStatus?, nxt: AnalysisStatus?): String {
    // The playing track first: without it there is nothing to mix out of.
    val focus = if (cur != null && cur != AnalysisStatus.Analysed) cur else nxt
    val isCurrent = focus === cur && cur != null && cur != AnalysisStatus.Analysed
    return when (focus) {
        is AnalysisStatus.Analysing ->
            if (isCurrent) stringResource(Res.string.dj_chip_analysing_current, focus.seconds) else stringResource(Res.string.dj_chip_analysing_next, focus.seconds)
        is AnalysisStatus.Blocked ->
            stringResource(
                Res.string.dj_chip_blocked,
                when (focus.reason) {
                    BlockReason.BATTERY_SAVER -> stringResource(Res.string.ai_dj_status_blocked_battery_saver).removePrefix("blocked: ")
                    BlockReason.LOW_BATTERY -> stringResource(Res.string.ai_dj_status_blocked_low_battery).removePrefix("blocked: ")
                },
            )
        is AnalysisStatus.NeedsNetwork ->
            stringResource(
                Res.string.dj_chip_blocked,
                if (focus.metered) stringResource(Res.string.ai_dj_status_blocked_mobile_data).removePrefix("blocked: ") else stringResource(Res.string.ai_dj_status_needs_network),
            )
        is AnalysisStatus.Retrying -> stringResource(Res.string.dj_chip_retrying, focus.inSeconds)
        is AnalysisStatus.Failed -> stringResource(Res.string.dj_chip_analysis_failed)
        AnalysisStatus.AnalyzerMissing -> stringResource(Res.string.dj_chip_analysis_failed)
        else -> stringResource(Res.string.dj_chip_waiting)
    }
}

/** "124→128 BPM, 8A→9A", whatever of it is known. */
private fun mixFacts(d: DjDebugState): String {
    val bpm = if (d.fromBpm != null && d.toBpm != null) "%.0f→%.0f BPM".format(d.fromBpm, d.toBpm) else null
    val key = if (d.fromKey != null && d.toKey != null) "${d.fromKey}→${d.toKey}" else null
    return listOfNotNull(bpm, key).joinToString(", ")
}

private fun formatMinSec(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
