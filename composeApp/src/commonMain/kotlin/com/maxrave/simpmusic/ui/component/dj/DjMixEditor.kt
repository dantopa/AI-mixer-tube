package com.maxrave.simpmusic.ui.component.dj

import androidx.compose.runtime.Immutable

/**
 * What the mix editor can do with the prepared transition (the owner drags the tracks on the picture). The platform wires
 * these to the DJ engine; null in [DjMixPanel] = no editor (a static render, or a platform without the DJ).
 *
 * Shifts are in SOURCE ms of the track that was dragged (dragging a lane right by t means the earlier part of that track
 * now plays under the overlap: a negative shift). The engine snaps the result to the grid and re-plans.
 */
@Immutable
class DjMixEditor(
    val onShift: (exitShiftMs: Long, entryShiftMs: Long) -> Unit,
    val onBars: (delta: Int) -> Unit,
    val onLoop: (bars: Int) -> Unit,
    val onSave: () -> Unit,
    val onReset: () -> Unit,
    /** The engine's last word about the edit ("edit: out 1:32 -> in 0:12, 22.4 s", "edit not possible: ..."). */
    val status: String?,
    /** The prepared plan's overlap in bars, and its outgoing loop (0 = none). */
    val bars: Int?,
    val loopBars: Int,
    /** The prepared plan is the owner's edit, and it is saved for this pair. */
    val isEdit: Boolean,
    val isSaved: Boolean,
    /** False while the mix plays (the plan cannot change under the audio). */
    val enabled: Boolean,
)
