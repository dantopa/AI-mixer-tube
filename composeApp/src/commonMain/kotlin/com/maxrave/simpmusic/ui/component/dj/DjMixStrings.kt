package com.maxrave.simpmusic.ui.component.dj

import androidx.compose.runtime.Immutable
import org.simpmusic.dj.mixview.DjMixKind
import org.simpmusic.dj.mixview.DjMixPhase
import org.simpmusic.dj.mixview.DjMixViewData
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Every user-visible string of the mix view, already resolved. The composables that draw the picture take this instead
 * of reading resources themselves so they can be rendered off-screen (see `dj/docs/mixview.md`); the resource-backed
 * factory is `rememberDjMixStrings()` in DjMixCard.kt.
 *
 * The `*Template` strings carry one `{0}` placeholder, filled by the helpers below (Compose Resources cannot format
 * from a non-composable, and the countdown changes ten times a second).
 */
@Immutable
data class DjMixStrings(
    val sheetTitle: String = "AI DJ mix",
    val analysing: String = "Analysing the tracks…",
    val kindBeatMatched: String = "Beat-matched",
    val kindCut: String = "Beat cut",
    val kindCrossfade: String = "Crossfade",
    val kindEchoOut: String = "Echo out",
    val phaseReady: String = "Mix ready",
    val phaseLeadInTemplate: String = "Mix starts in {0} s",
    val phaseMixingTemplate: String = "Mixing · {0}",
    val phaseSettling: String = "Settling in",
    val overlapTemplate: String = "Overlap {0} s",
    val legendFader: String = "Fader",
    val legendCut: String = "EQ cut",
    val close: String = "Close",
) {
    fun kindLabel(kind: DjMixKind?): String = when (kind) {
        DjMixKind.BEAT_MATCHED -> kindBeatMatched
        DjMixKind.CUT -> kindCut
        DjMixKind.SIMPLE_CROSSFADE -> kindCrossfade
        DjMixKind.ECHO_OUT -> kindEchoOut
        null -> analysing
    }

    /** The one-line status: countdown, progress or state. */
    fun phaseLine(data: DjMixViewData): String = when (data.phase) {
        DjMixPhase.ANALYSING -> analysing
        DjMixPhase.READY -> phaseReady
        DjMixPhase.LEAD_IN -> phaseLeadInTemplate.replace("{0}", oneDecimal((data.msUntilT0 ?: 0L) / 1000.0))
        DjMixPhase.MIXING -> phaseMixingTemplate.replace("{0}", "${((data.mixProgress ?: 0f) * 100).roundToInt()}%")
        DjMixPhase.SETTLING -> phaseSettling
    }

    fun overlapLabel(overlapMs: Long): String = overlapTemplate.replace("{0}", oneDecimal(overlapMs / 1000.0))
}

/** "12.3": one decimal without String.format (not in common Kotlin). */
internal fun oneDecimal(v: Double): String {
    val tenths = (abs(v) * 10).roundToInt()
    val sign = if (v < 0 && tenths != 0) "-" else ""
    return "$sign${tenths / 10}.${tenths % 10}"
}

/** "124" for a whole BPM, "124.5" otherwise. */
internal fun bpmText(v: Float): String {
    val r = (v * 10).roundToInt()
    return if (r % 10 == 0) "${r / 10}" else "${r / 10}.${r % 10}"
}
