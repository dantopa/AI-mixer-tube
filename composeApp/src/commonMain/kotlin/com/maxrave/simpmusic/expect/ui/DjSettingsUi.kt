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
