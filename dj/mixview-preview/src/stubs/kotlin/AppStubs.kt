// Compile-only stand-ins (see Stubs.kt).
package com.maxrave.simpmusic.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

class SurfaceDarkColors(val container: Color, val handle: Color, val content: Color, val subtitle: Color, val disabled: Color)

@Composable
fun rememberSurfaceDarkColors(): SurfaceDarkColors {
    val cs = MaterialTheme.colorScheme
    return SurfaceDarkColors(cs.surfaceContainerLow, cs.outlineVariant, cs.onSurface, cs.onSurfaceVariant, cs.onSurfaceVariant)
}
