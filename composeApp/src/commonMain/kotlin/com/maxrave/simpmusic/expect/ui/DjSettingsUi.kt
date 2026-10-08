package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable

/**
 * The AI DJ block of Settings → Playback: master switch, overlap bars, tempo bend, key shift, bass swap and
 * a debug line explaining the last planned transition. Android only: the DJ works on Media3 players, so the
 * desktop actual draws nothing (and the caller does not even add the row there).
 *
 * @param castRemote true while casting; the block is greyed out exactly like the crossfade rows.
 */
@Composable
expect fun DjSettingsSection(castRemote: Boolean)

/** Whether AI DJ mode is on. While it is, the plain crossfade settings are only the DJ's fallback. Always false on Desktop. */
@Composable
expect fun rememberDjModeEnabled(): Boolean

/** Length in whole seconds of the crossfade the DJ falls back to; 0 where DJ mode does not exist. */
@Composable
expect fun rememberDjFallbackSeconds(): Int

/**
 * The AI DJ row on Now Playing: a "DJ" switch that turns DJ mode on and off without going into Settings, and while it is
 * on a one-line status ("analysing next track… 12 s", "ready, mix in 0:42", "mixing"...). Draws nothing while casting
 * ([castRemote]: the DJ cannot mix on a receiver), and on Desktop.
 */
@Composable
expect fun DjStatusChip(modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier, castRemote: Boolean = false)

/**
 * "DJ: what next?" sheet: the best songs of the analysed library to follow the playing one, with Play next / Play now.
 * Android only; Desktop draws nothing (its caller never opens it, see [rememberDjModeEnabled]).
 *
 * @param onDone called after a suggestion was enqueued, so the caller can close the sheet it was opened from.
 */
@Composable
expect fun DjNextSheet(
    onDismiss: () -> Unit,
    onDone: () -> Unit,
)
