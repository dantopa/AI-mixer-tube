package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable

// The AI DJ drives Media3 players; the desktop player (libmpv) has no equivalent, so there is nothing to show.
@Composable
actual fun DjSettingsSection(castRemote: Boolean) = Unit

@Composable
actual fun rememberDjModeEnabled(): Boolean = false

@Composable
actual fun rememberDjFallbackSeconds(): Int = 0
