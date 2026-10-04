package com.maxrave.simpmusic.ui.component.dj

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.simpmusic.dj.mixview.DjMixViewData
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.dj_mix_analysing
import simpmusic.composeapp.generated.resources.dj_mix_close
import simpmusic.composeapp.generated.resources.dj_mix_kind_beat_matched
import simpmusic.composeapp.generated.resources.dj_mix_kind_crossfade
import simpmusic.composeapp.generated.resources.dj_mix_kind_cut
import simpmusic.composeapp.generated.resources.dj_mix_kind_echo_out
import simpmusic.composeapp.generated.resources.dj_mix_legend_cut
import simpmusic.composeapp.generated.resources.dj_mix_legend_fader
import simpmusic.composeapp.generated.resources.dj_mix_overlap
import simpmusic.composeapp.generated.resources.dj_mix_phase_lead_in
import simpmusic.composeapp.generated.resources.dj_mix_phase_mixing
import simpmusic.composeapp.generated.resources.dj_mix_phase_ready
import simpmusic.composeapp.generated.resources.dj_mix_phase_settling
import simpmusic.composeapp.generated.resources.dj_mix_sheet_title

private const val SLOT = "{0}"

/** The resource-backed [DjMixStrings]. Templates are read once with a `{0}` slot that the helpers fill in later. */
@Composable
fun rememberDjMixStrings(): DjMixStrings {
    val sheetTitle = stringResource(Res.string.dj_mix_sheet_title)
    val analysing = stringResource(Res.string.dj_mix_analysing)
    val beat = stringResource(Res.string.dj_mix_kind_beat_matched)
    val cut = stringResource(Res.string.dj_mix_kind_cut)
    val xfade = stringResource(Res.string.dj_mix_kind_crossfade)
    val echo = stringResource(Res.string.dj_mix_kind_echo_out)
    val ready = stringResource(Res.string.dj_mix_phase_ready)
    val leadIn = stringResource(Res.string.dj_mix_phase_lead_in, SLOT)
    val mixing = stringResource(Res.string.dj_mix_phase_mixing, SLOT)
    val settling = stringResource(Res.string.dj_mix_phase_settling)
    val overlap = stringResource(Res.string.dj_mix_overlap, SLOT)
    val fader = stringResource(Res.string.dj_mix_legend_fader)
    val eqCut = stringResource(Res.string.dj_mix_legend_cut)
    val close = stringResource(Res.string.dj_mix_close)
    return remember(sheetTitle, analysing, beat, cut, xfade, echo, ready, leadIn, mixing, settling, overlap, fader, eqCut, close) {
        DjMixStrings(
            sheetTitle = sheetTitle, analysing = analysing, kindBeatMatched = beat, kindCut = cut, kindCrossfade = xfade, kindEchoOut = echo,
            phaseReady = ready, phaseLeadInTemplate = leadIn, phaseMixingTemplate = mixing, phaseSettling = settling,
            overlapTemplate = overlap, legendFader = fader, legendCut = eqCut, close = close,
        )
    }
}

/**
 * Inline card for the AI DJ mix: "A -> B", kind + BPM/key summary, the picture and a status line.
 * Feed it [DjMixViewData] from the engine (see `dj/docs/mixview.md`); null renders nothing.
 */
@Composable
fun DjMixCard(
    data: DjMixViewData?,
    modifier: Modifier = Modifier,
    canvasHeight: Dp = 200.dp,
) {
    if (data == null) return
    DjMixCardContent(data = data, modifier = modifier, strings = rememberDjMixStrings(), canvasHeight = canvasHeight)
}
