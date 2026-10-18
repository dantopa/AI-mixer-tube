package com.maxrave.simpmusic.expect.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.SimpleMediaState
import com.maxrave.simpmusic.ui.component.dj.DjMixSheet
import com.maxrave.simpmusic.ui.icon.FastForward
import com.maxrave.simpmusic.ui.icon.GraphicEq
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.theme.typo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.simpmusic.dj.android.DjDebugState
import org.simpmusic.dj.android.DjHooks
import org.simpmusic.dj.android.AiMixResult
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
import simpmusic.composeapp.generated.resources.dj_mark_entry
import simpmusic.composeapp.generated.resources.dj_mark_exit
import simpmusic.composeapp.generated.resources.dj_mark_solo
import simpmusic.composeapp.generated.resources.dj_forget_track_marks
import simpmusic.composeapp.generated.resources.dj_ai_auto
import simpmusic.composeapp.generated.resources.dj_ai_auto_description
import simpmusic.composeapp.generated.resources.dj_ai_auto_on
import simpmusic.composeapp.generated.resources.dj_ai_recalc
import simpmusic.composeapp.generated.resources.dj_ai_recalc_description
import simpmusic.composeapp.generated.resources.dj_ai_order
import simpmusic.composeapp.generated.resources.dj_ai_order_description
import simpmusic.composeapp.generated.resources.dj_ai_busy
import simpmusic.composeapp.generated.resources.dj_ai_mix
import simpmusic.composeapp.generated.resources.dj_ai_mix_short
import simpmusic.composeapp.generated.resources.dj_ai_no_key
import simpmusic.composeapp.generated.resources.dj_mix_now
import simpmusic.composeapp.generated.resources.dj_mix_now_mixing
import simpmusic.composeapp.generated.resources.dj_mix_now_no_pair
import simpmusic.composeapp.generated.resources.dj_mix_now_soon
import simpmusic.composeapp.generated.resources.dj_mix_now_started
import simpmusic.composeapp.generated.resources.dj_perfect_ready
import simpmusic.composeapp.generated.resources.dj_rate_down
import simpmusic.composeapp.generated.resources.dj_rate_up
import simpmusic.composeapp.generated.resources.dj_tap_one_done
import simpmusic.composeapp.generated.resources.dj_chip_planning
import simpmusic.composeapp.generated.resources.dj_chip_ready
import simpmusic.composeapp.generated.resources.dj_chip_rendering
import simpmusic.composeapp.generated.resources.dj_chip_retrying
import simpmusic.composeapp.generated.resources.dj_chip_waiting

/**
 * The AI DJ row on Now Playing. A "DJ" switch first, always there (DJ mode on/off without going into Settings); while it
 * is on, one line of what the engine is doing ("analysing next track… 12 s", "ready, mix in 0:42 (124→128 BPM, 8A→9A)",
 * "mixing", "crossfade fallback: ...") and "mix now". Tapping the line opens the picture of the mix, or the DJ log.
 * The 1-2-3-4 counter and the phrase pill are tools to correct the DJ, shown only when Settings -> AI DJ -> "Bar counter"
 * is on. A perfect mix (1 on 1, 16 on 16) is planned by itself whenever the pair qualifies: there is no button for it.
 */
/**
 * What the last tap answered ("mix now", "AI Mix", a tapped 1): shown in the status pill for a few seconds. The buttons sit
 * on their own row, so the answer is hoisted here.
 */
@Stable
private class DjChipFeedback {
    var mixNow by mutableStateOf<MixNowResult?>(null)
    var ai by mutableStateOf<AiMixResult?>(null)
    var tapped by mutableStateOf(0)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun DjStatusChip(modifier: Modifier, castRemote: Boolean) {
    // the DJ cannot mix on a cast receiver; Settings says so, Now Playing simply shows nothing
    if (castRemote) return
    val settings = koinInject<DjSettingsRepository>()
    val enabled by settings.settings.collectAsStateWithLifecycle(DjSettings())
    val showCounter by settings.showBarCounter.collectAsStateWithLifecycle(false)
    val scope = rememberCoroutineScope()
    val on = enabled.enabled
    val feedback = remember { DjChipFeedback() }
    LaunchedEffect(feedback.mixNow, feedback.tapped) {
        if (feedback.mixNow != null || feedback.tapped > 0) {
            delay(3500)
            feedback.mixNow = null
            feedback.tapped = 0
        }
    }
    LaunchedEffect(feedback.ai) {
        if (feedback.ai != null) {
            delay(3500)
            feedback.ai = null
        }
    }

    // Three rows, one job each (the owner, 2026-10-17: "everything crammed together, nothing can be read"):
    //  1. the DJ switch and what the DJ is doing, which gets the whole remaining width;
    //  2. what the listener can ask for: mix now, AI Mix, and the rating after a mix;
    //  3. the tools that correct the DJ (Settings -> AI DJ -> Bar counter): 1-2-3-4, », n/16, Out / In / Solo.
    // Rows 2 and 3 wrap instead of running off the screen.
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(
                modifier = Modifier.toggleable(value = on, role = Role.Switch, onValueChange = { v -> scope.launch { settings.setEnabled(v) } }),
                shape = CircleShape,
                color = if (on) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.45f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    androidx.compose.foundation.Image(
                        imageVector = SimpIcons.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        colorFilter = ColorFilter.tint(if (on) MaterialTheme.colorScheme.onPrimary else Color.White.copy(alpha = 0.6f)),
                    )
                    Text(
                        text = "DJ",
                        style = typo().labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else Color.White.copy(alpha = 0.6f),
                    )
                }
            }
            if (on) DjStatusLine(feedback)
        }
        if (on) DjActionsRow(feedback)
        if (on && showCounter) DjCorrectionRow(feedback)
    }
}

/** A pill of the chip's rows: dark and quiet, or lit in the accent colour. */
@Composable
private fun ChipPill(
    modifier: Modifier = Modifier,
    lit: Boolean = false,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = if (lit) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.45f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) { content() }
    }
}

@Composable
private fun ChipLabel(text: String, bold: Boolean = false, color: Color = Color.White.copy(alpha = 0.9f)) {
    Text(text = text, style = typo().labelMedium, fontWeight = if (bold) FontWeight.Bold else null, color = color, maxLines = 1)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun androidx.compose.foundation.layout.RowScope.DjStatusLine(feedback: DjChipFeedback) {
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
    val answer =
        when (feedback.mixNow) {
            MixNowResult.STARTED -> stringResource(Res.string.dj_mix_now_started)
            MixNowResult.NO_PAIR -> stringResource(Res.string.dj_mix_now_no_pair)
            MixNowResult.ALREADY_MIXING -> stringResource(Res.string.dj_mix_now_mixing)
            MixNowResult.ALREADY_SOON -> stringResource(Res.string.dj_mix_now_soon)
            MixNowResult.ALREADY_PERFECT, MixNowResult.DISABLED, null -> null
        }
    // "AI Mix": the tap's own answer for a few seconds, then what Claude is doing or said (for AI_STATUS_MS after it spoke)
    val aiNow by produceState(System.currentTimeMillis(), debug.aiStatusAtEpochMs) {
        value = System.currentTimeMillis()
        val at = debug.aiStatusAtEpochMs ?: return@produceState
        val left = AI_STATUS_MS - (System.currentTimeMillis() - at)
        if (left > 0) {
            delay(left)
            value = System.currentTimeMillis()
        }
    }
    val aiText =
        when (feedback.ai) {
            AiMixResult.NO_KEY -> stringResource(Res.string.dj_ai_no_key)
            AiMixResult.BUSY -> stringResource(Res.string.dj_ai_busy)
            AiMixResult.NO_PAIR -> stringResource(Res.string.dj_mix_now_no_pair)
            AiMixResult.ALREADY_MIXING -> stringResource(Res.string.dj_mix_now_mixing)
            else -> null
        } ?: debug.aiStatus?.takeIf { debug.aiAsking || (debug.aiStatusAtEpochMs?.let { aiNow - it < AI_STATUS_MS } == true) }
    val text = (if (feedback.tapped > 0) (debug.lastOutcome ?: stringResource(Res.string.dj_tap_one_done)) else null) ?: answer ?: aiText ?: chipText(debug, positionMs, nowMs)

    Surface(
        // Tap: the picture of the mix (both tracks overlapping) when there is one, else the log. Long-press: always the log.
        // It takes the whole rest of the row, so the status is always readable.
        modifier = Modifier.weight(1f).combinedClickable(onClick = { if (mix != null) showMix = true else showLog = true }, onLongClick = { showLog = true }),
        shape = CircleShape,
        // Unmistakable while a mix is actually playing: the accent colour instead of the quiet dark pill.
        color = if (mixing) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.45f),
    ) {
        Text(
            text = text,
            style = typo().labelMedium,
            fontWeight = if (mixing) FontWeight.Bold else null,
            color = if (mixing) MaterialTheme.colorScheme.onPrimary else Color.White.copy(alpha = 0.9f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
    if (showLog) DjLogViewerDialog(onDismiss = { showLog = false })
    // Read live: the sheet keeps following the engine's flow while it is open, and closes itself when the mix is gone.
    if (showMix) DjMixSheet(data = mix, onDismiss = { showMix = false })
}

/** Row 2: what the listener can ask for. Nothing is drawn when nothing applies (a mix playing, no rating due). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DjActionsRow(feedback: DjChipFeedback) {
    val hooks = koinInject<DjHooks>()
    val debug by hooks.debug.collectAsStateWithLifecycle()
    val mixing = debug.isMixing
    val claude by org.simpmusic.dj.android.ai.ClaudeSettings.state.collectAsStateWithLifecycle()
    // After a mix ends: thumbs up / down for RATE_PROMPT_MS (or until rated), written to the mix journal. The engine's
    // last mix is re-read whenever its state changes (a mix ending updates it).
    val lastMix = remember(debug) { hooks.lastMix() }
    var ratedId by remember { mutableStateOf<String?>(null) }
    var expiredId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(lastMix?.id) {
        val m = lastMix ?: return@LaunchedEffect
        val left = RATE_PROMPT_MS - (System.currentTimeMillis() - m.endedAtEpochMs)
        if (left > 0) delay(left)
        expiredId = m.id
    }
    val rateable = lastMix?.takeIf {
        !mixing && it.rating == null && it.id != ratedId && it.id != expiredId && System.currentTimeMillis() - it.endedAtEpochMs < RATE_PROMPT_MS
    }
    if (mixing && rateable == null) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!mixing) {
            // "Mix now": the next good phrase start within ~20-45 s, instead of where the DJ would have chosen.
            ChipPill(modifier = Modifier.clickable { feedback.mixNow = hooks.mixNow() }) {
                androidx.compose.foundation.Image(
                    imageVector = SimpIcons.FastForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.9f)),
                )
                ChipLabel(stringResource(Res.string.dj_mix_now))
            }
            // "AI Mix": Claude reads both tracks bar by bar (and their lyrics) and chooses the exit, the entry and the overlap.
            // Only with a key in Settings -> AI DJ -> Claude.
            // "Auto" keeps it on: Claude chooses every mix by itself (once per pair); tap the lit pill to turn it off.
            if (claude.hasKey && claude.auto) {
                val autoDescription = stringResource(Res.string.dj_ai_auto_description)
                ChipPill(
                    modifier = Modifier.semantics { contentDescription = autoDescription }.clickable { org.simpmusic.dj.android.ai.ClaudeSettings.setAuto(false) },
                    lit = true,
                ) {
                    // "+2": the next mixes Claude has already solved ahead (it works a few tracks in advance, like a DJ)
                    ChipLabel("✨ " + stringResource(Res.string.dj_ai_auto_on) + (if (debug.aiAhead > 0) " · +${debug.aiAhead}" else ""), bold = true, color = MaterialTheme.colorScheme.onPrimary)
                }
                // Claude's answers are remembered per pair (a skip back costs nothing): this asks again for the playing pair
                val recalcDescription = stringResource(Res.string.dj_ai_recalc_description)
                ChipPill(
                    modifier = Modifier.semantics { contentDescription = recalcDescription }.clickable { feedback.ai = hooks.aiMix() },
                    lit = debug.aiAsking,
                ) {
                    ChipLabel("↻ " + stringResource(Res.string.dj_ai_recalc))
                }
            } else if (claude.hasKey) {
                val aiDescription = stringResource(Res.string.dj_ai_mix)
                ChipPill(
                    modifier = Modifier.semantics { contentDescription = aiDescription }.clickable { feedback.ai = hooks.aiMix() },
                    lit = debug.aiPlan || debug.aiAsking,
                ) {
                    ChipLabel("✨ " + stringResource(Res.string.dj_ai_mix_short))
                }
                val autoDescription = stringResource(Res.string.dj_ai_auto_description)
                ChipPill(
                    modifier = Modifier.semantics { contentDescription = autoDescription }.clickable {
                        org.simpmusic.dj.android.ai.ClaudeSettings.setAuto(true)
                        // the pair already planned is asked about now (or its remembered answer reused), the next ones as soon as they are analysed
                        feedback.ai = hooks.aiMixRemembered()
                    },
                ) {
                    ChipLabel(stringResource(Res.string.dj_ai_auto))
                }
            }
            // "Order with AI": Claude puts the radio's next tracks in the order where every join is a natural continuation
            if (claude.hasKey) {
                val orderDescription = stringResource(Res.string.dj_ai_order_description)
                ChipPill(modifier = Modifier.semantics { contentDescription = orderDescription }.clickable { feedback.ai = hooks.orderWithAi() }) {
                    ChipLabel("✨ " + stringResource(Res.string.dj_ai_order))
                }
            }
        }
        if (rateable != null) {
            for ((up, glyph, description) in listOf(
                Triple(true, "👍", stringResource(Res.string.dj_rate_up)),
                Triple(false, "👎", stringResource(Res.string.dj_rate_down)),
            )) {
                ChipPill(modifier = Modifier.semantics { contentDescription = description }.clickable { if (hooks.rateLastMix(up)) ratedId = rateable.id }) {
                    ChipLabel(glyph)
                }
            }
        }
    }
}

/**
 * Row 3, the tools that correct the DJ: the 1-2-3-4 counter, "»", the bar of the 16-bar phrase, and the owner's mix
 * marks ("Out" where this track can start to leave, "In" where it starts when another one mixes into it, "Solo" from
 * where it plays alone; lit when set, long-press forgets).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun DjCorrectionRow(feedback: DjChipFeedback) {
    val hooks = koinInject<DjHooks>()
    val handler = koinInject<MediaPlayerHandler>()
    val media by handler.simpleMediaState.collectAsStateWithLifecycle()
    val positionMs = (media as? SimpleMediaState.Progress)?.progress ?: 0L
    // The 1-2-3-4 counter follows the DJ's bars: if its "1" does not light up on the 1 you hear, tap "»" to move it one
    // beat later (no timing needed). Tapping the counter on the 1s you hear (a few times) sets it too; long-press forgets.
    // It follows what is HEARD: the 20 Hz position minus the lyrics timing offset the owner sets for Bluetooth; derived,
    // so the row recomposes only when the beat changes. Not carried forward at frame rate like the lyrics: that asks for
    // a frame on every vsync while Now Playing is open, a cost the counter (a beat is 450-750 ms) does not need.
    val dataStore = koinInject<DataStoreManager>()
    val outputDelayMs by dataStore.lyricsOffsetMs.collectAsStateWithLifecycle(0)
    val position by rememberUpdatedState(positionMs)
    val heard by remember(hooks) { derivedStateOf { (position - outputDelayMs).coerceAtLeast(0L) } }
    val bar by remember(hooks) { derivedStateOf { hooks.barBeatAt(heard) } }
    // the marks are re-read after every tap (they live in the engine's store, not in Compose state)
    val debug by hooks.debug.collectAsStateWithLifecycle()
    var version by remember { mutableStateOf(0) }
    val marks = remember(version, debug.fromId) { hooks.mixMarks() }
    val anyMark = remember(version, feedback.tapped, debug.fromId) { hooks.hasTrackMarks() }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        bar?.let { b ->
            ChipPill(
                modifier = Modifier.combinedClickable(
                    onClick = { if (hooks.tapTheOne(heard) != null) feedback.tapped++ },
                    onLongClick = { hooks.clearTheOne(); feedback.tapped++ },
                ),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (i in 0 until b.beatsPerBar) {
                        val on = i == b.beatInBar
                        ChipLabel(
                            text = "${i + 1}",
                            bold = on,
                            color =
                                when {
                                    on && i == 0 -> if (b.anchored || b.sure) MaterialTheme.colorScheme.primary else Color(0xFFFFB74D)
                                    on -> Color.White
                                    else -> Color.White.copy(alpha = 0.35f)
                                },
                        )
                    }
                }
            }
            ChipPill(modifier = Modifier.clickable { if (hooks.shiftTheOne(heard) != null) feedback.tapped++ }) {
                ChipLabel("»", bold = true)
            }
            // Which bar of the 16-bar phrase it is in ("5/16": a chorus is 16 bars, and the next 1 is where it starts again).
            // Lit on bar 1; amber while the phrase lines are a guess. Tap it when a phrase starts (a bar of slack is enough);
            // long-press forgets the mark.
            b.barInBlock?.let { inBlock ->
                val barNo = inBlock + 1
                val sure = b.blockSure || b.phraseMarked
                ChipPill(
                    modifier = Modifier.combinedClickable(
                        onClick = { if (hooks.markPhrase(heard) != null) feedback.tapped++ },
                        onLongClick = { hooks.clearPhrase(); feedback.tapped++ },
                    ),
                    lit = barNo == 1,
                ) {
                    ChipLabel("$barNo/16", bold = barNo == 1, color = if (sure) Color.White.copy(alpha = 0.9f) else Color(0xFFFFB74D))
                }
            }
        }
        for ((mark, label, at) in listOf(
            Triple(org.simpmusic.dj.android.perfect.MixMark.EXIT, stringResource(Res.string.dj_mark_exit), marks?.exitMs),
            Triple(org.simpmusic.dj.android.perfect.MixMark.ENTRY, stringResource(Res.string.dj_mark_entry), marks?.entryMs),
            Triple(org.simpmusic.dj.android.perfect.MixMark.SOLO, stringResource(Res.string.dj_mark_solo), marks?.soloMs),
        )) {
            ChipPill(
                modifier = Modifier.combinedClickable(
                    onClick = { if (hooks.markMix(mark, heard) != null) version++ },
                    onLongClick = { hooks.clearMix(mark); version++ },
                ),
                lit = at != null,
            ) {
                ChipLabel(if (at != null) "$label %d:%02d".format(at / 60_000, at / 1000 % 60) else label, bold = at != null)
            }
        }
        // A wrong mark binds the DJ (and Claude) to it: this forgets every mark of the playing track in one tap, and the
        // points are found from the analysis again. Shown only while there is something to forget.
        if (anyMark) {
            ChipPill(
                modifier = Modifier.clickable {
                    if (hooks.forgetTrackMarks()) {
                        version++
                        feedback.tapped++
                    }
                },
            ) {
                ChipLabel(stringResource(Res.string.dj_forget_track_marks))
            }
        }
    }
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

/** How long the thumbs up / down stay next to the chip after a mix ends (the journal accepts a rating for longer). */
private const val RATE_PROMPT_MS = 60_000L

/** How long Claude's last word ("out 2:31 → in 0:12, 8 bars · …", or why not) stays in the chip. */
private const val AI_STATUS_MS = 12_000L
