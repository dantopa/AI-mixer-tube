package com.maxrave.simpmusic.expect.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.SimpleMediaState
import com.maxrave.simpmusic.ui.component.dj.DjMixSheet
import com.maxrave.simpmusic.ui.icon.FastForward
import com.maxrave.simpmusic.ui.icon.Star
import com.maxrave.simpmusic.ui.icon.GraphicEq
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.theme.typo
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.simpmusic.dj.android.DjDebugState
import org.simpmusic.dj.android.DjHooks
import org.simpmusic.dj.android.MixNowResult
import org.simpmusic.dj.android.scheduler.AnalysisStatus
import org.simpmusic.dj.android.scheduler.BlockReason
import org.simpmusic.dj.android.settings.DjSettingsRepository
import org.simpmusic.dj.model.DjSettings
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.ai_dj_status_blocked_battery_saver
import simpmusic.composeapp.generated.resources.ai_dj_status_blocked_low_battery
import simpmusic.composeapp.generated.resources.ai_dj_status_blocked_not_charging
import simpmusic.composeapp.generated.resources.ai_dj_status_blocked_hot
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
import simpmusic.composeapp.generated.resources.dj_mix_now
import simpmusic.composeapp.generated.resources.dj_mix_now_mixing
import simpmusic.composeapp.generated.resources.dj_mix_now_no_pair
import simpmusic.composeapp.generated.resources.dj_mix_now_soon
import simpmusic.composeapp.generated.resources.dj_mix_now_started
import simpmusic.composeapp.generated.resources.dj_perfect_already
import simpmusic.composeapp.generated.resources.dj_perfect_mix
import simpmusic.composeapp.generated.resources.dj_perfect_ready
import simpmusic.composeapp.generated.resources.dj_perfect_started
import simpmusic.composeapp.generated.resources.dj_tap_one_done
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
    // Answer to the last "mix now" tap, shown in the chip for a few seconds.
    var mixNowAnswer by remember { mutableStateOf<MixNowResult?>(null) }
    // Which button the answer belongs to (the same results serve "mix now" and "perfect mix"), and the "tap the 1" ack.
    var answerIsPerfect by remember { mutableStateOf(false) }
    var tapped by remember { mutableStateOf(0) }
    LaunchedEffect(mixNowAnswer, tapped) {
        if (mixNowAnswer != null || tapped > 0) {
            delay(3500)
            mixNowAnswer = null
            tapped = 0
        }
    }

    val positionMs = (media as? SimpleMediaState.Progress)?.progress ?: 0L
    val mixing = debug.isMixing
    // The mix countdown runs on the wall clock (the window's own timeline), so it needs a ticking clock while a mix plays.
    val nowMs by produceState(System.currentTimeMillis(), mixing) {
        while (mixing) {
            value = System.currentTimeMillis()
            delay(500)
        }
    }
    val answer =
        when (mixNowAnswer) {
            MixNowResult.STARTED -> stringResource(if (answerIsPerfect) Res.string.dj_perfect_started else Res.string.dj_mix_now_started)
            MixNowResult.ALREADY_PERFECT -> stringResource(Res.string.dj_perfect_already)
            MixNowResult.NO_PAIR -> stringResource(Res.string.dj_mix_now_no_pair)
            MixNowResult.ALREADY_MIXING -> stringResource(Res.string.dj_mix_now_mixing)
            MixNowResult.ALREADY_SOON -> stringResource(Res.string.dj_mix_now_soon)
            MixNowResult.DISABLED, null -> null
        }
    val text = (if (tapped > 0) (debug.lastOutcome ?: stringResource(Res.string.dj_tap_one_done)) else null) ?: answer ?: chipText(debug, positionMs, nowMs)

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Surface(
        // Tap: the picture of the mix (both tracks overlapping) when there is one, else the log. Long-press: always the log.
        modifier = Modifier.weight(1f, fill = false).combinedClickable(onClick = { if (mix != null) showMix = true else showLog = true }, onLongClick = { showLog = true }),
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
    // "Mix now": the next good phrase start within ~20-45 s, instead of where the DJ would have chosen.
    if (!mixing) {
        Surface(
            modifier = Modifier.clickable { answerIsPerfect = false; mixNowAnswer = hooks.mixNow() },
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.45f),
        ) {
            androidx.compose.foundation.Image(
                imageVector = SimpIcons.FastForward,
                contentDescription = stringResource(Res.string.dj_mix_now),
                modifier = Modifier.padding(5.dp).size(16.dp),
                colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.9f)),
            )
        }
        // "Perfect mix": the best point within ~2 minutes where both 1s are trusted, phrase on phrase first; or why not.
        Surface(
            modifier = Modifier.clickable { answerIsPerfect = true; mixNowAnswer = hooks.perfectMix() },
            shape = CircleShape,
            color = if (debug.perfect) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.45f),
        ) {
            androidx.compose.foundation.Image(
                imageVector = SimpIcons.Star,
                contentDescription = stringResource(Res.string.dj_perfect_mix),
                modifier = Modifier.padding(5.dp).size(16.dp),
                colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.9f)),
            )
        }
    }
    // The 1-2-3-4 counter follows the DJ's bars: if its "1" does not light up on the 1 you hear, tap "»" to move it one
    // beat later (no timing needed). Tapping the counter on the 1s you hear (a few times) sets it too; long-press forgets.
    // It follows what is HEARD: the 20 Hz position minus the lyrics timing offset the owner sets for Bluetooth; derived,
    // so the chip recomposes only when the beat changes. Not carried forward at frame rate like the lyrics: that asks for
    // a frame on every vsync while Now Playing is open, a cost the counter (a beat is 450-750 ms) does not need.
    val dataStore = koinInject<DataStoreManager>()
    val outputDelayMs by dataStore.lyricsOffsetMs.collectAsStateWithLifecycle(0)
    val position by rememberUpdatedState(positionMs)
    val heard by remember(hooks) { derivedStateOf { (position - outputDelayMs).coerceAtLeast(0L) } }
    val barState = remember(hooks) { derivedStateOf { hooks.barBeatAt(heard) } }
    val bar = barState.value
    if (bar != null) {
        Surface(
            modifier = Modifier.combinedClickable(
                onClick = { if (hooks.tapTheOne(heard) != null) tapped++ },
                onLongClick = { hooks.clearTheOne(); tapped++ },
            ),
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.45f),
        ) {
            Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0 until bar.beatsPerBar) {
                    val on = i == bar.beatInBar
                    Text(
                        text = "${i + 1}",
                        style = typo().labelMedium,
                        fontWeight = if (on) FontWeight.Bold else null,
                        color =
                            when {
                                on && i == 0 -> if (bar.anchored || bar.sure) MaterialTheme.colorScheme.primary else Color(0xFFFFB74D)
                                on -> Color.White
                                else -> Color.White.copy(alpha = 0.35f)
                            },
                    )
                }
            }
        }
        Surface(
            modifier = Modifier.clickable { if (hooks.shiftTheOne(heard) != null) tapped++ },
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.45f),
        ) {
            Text(
                text = "»",
                style = typo().labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        // Where it is in the phrase, counted the way the owner hears it: 16 beats = 4 bars, "5/16". Lit on the 1 of the
        // phrase; amber while the phrase lines are a guess. Tap it when a phrase starts (a bar of slack is enough);
        // long-press forgets the mark.
        val inBlock = bar.barInBlock
        if (inBlock != null) {
            val beat = (inBlock % 4) * bar.beatsPerBar + bar.beatInBar + 1
            val sure = bar.phraseSure || bar.phraseMarked
            Surface(
                modifier = Modifier.combinedClickable(
                    onClick = { if (hooks.markPhrase(heard) != null) tapped++ },
                    onLongClick = { hooks.clearPhrase(); tapped++ },
                ),
                shape = CircleShape,
                color = if (beat == 1) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.45f),
            ) {
                Text(
                    text = "$beat/${4 * bar.beatsPerBar}",
                    style = typo().labelMedium,
                    fontWeight = if (beat == 1) FontWeight.Bold else null,
                    color = if (sure) Color.White.copy(alpha = 0.9f) else Color(0xFFFFB74D),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
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
                if (d.perfect) {
                    stringResource(Res.string.dj_perfect_ready, formatMinSec(remaining.coerceAtLeast(0L)), facts)
                } else {
                    stringResource(Res.string.dj_chip_ready, formatMinSec(remaining.coerceAtLeast(0L)), facts)
                }
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
                    BlockReason.NOT_CHARGING -> stringResource(Res.string.ai_dj_status_blocked_not_charging).removePrefix("blocked: ")
                    BlockReason.HOT -> stringResource(Res.string.ai_dj_status_blocked_hot).removePrefix("blocked: ")
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
