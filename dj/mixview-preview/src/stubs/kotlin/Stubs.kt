@file:OptIn(org.jetbrains.compose.resources.InternalResourceApi::class)

// Compile-only stand-ins for what composeApp provides (generated Res, icons, the surface colours), so DjMixCard.kt and
// DjMixSheet.kt, which use them, are type-checked in this standalone build. They are never rendered from here.
package simpmusic.composeapp.generated.resources

import org.jetbrains.compose.resources.StringResource

object Res {
    object string
}

private fun s(k: String) = StringResource("string:$k", k, emptySet())

val Res.string.dj_mix_sheet_title get() = s("dj_mix_sheet_title")
val Res.string.dj_mix_analysing get() = s("dj_mix_analysing")
val Res.string.dj_mix_kind_beat_matched get() = s("dj_mix_kind_beat_matched")
val Res.string.dj_mix_kind_cut get() = s("dj_mix_kind_cut")
val Res.string.dj_mix_kind_crossfade get() = s("dj_mix_kind_crossfade")
val Res.string.dj_mix_kind_echo_out get() = s("dj_mix_kind_echo_out")
val Res.string.dj_mix_phase_ready get() = s("dj_mix_phase_ready")
val Res.string.dj_mix_phase_lead_in get() = s("dj_mix_phase_lead_in")
val Res.string.dj_mix_phase_mixing get() = s("dj_mix_phase_mixing")
val Res.string.dj_mix_phase_settling get() = s("dj_mix_phase_settling")
val Res.string.dj_mix_overlap get() = s("dj_mix_overlap")
val Res.string.dj_mix_legend_fader get() = s("dj_mix_legend_fader")
val Res.string.dj_mix_legend_cut get() = s("dj_mix_legend_cut")
val Res.string.dj_mix_close get() = s("dj_mix_close")
