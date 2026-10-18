package com.maxrave.simpmusic.ui.component.dj

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.simpmusic.dj.mixview.DjMixKind
import org.simpmusic.dj.mixview.DjMixViewData

/**
 * The content of the mix view: title row, kind + BPM/key summary, the [DjMixCanvas] and a phase line with the legend.
 * No container of its own, so a sheet can put it on its own surface; [DjMixCardContent] adds the Card.
 */
@Composable
fun DjMixPanel(
    data: DjMixViewData,
    modifier: Modifier = Modifier,
    strings: DjMixStrings = DjMixStrings(),
    canvasHeight: Dp = 200.dp,
    animatePlayhead: Boolean = true,
    /** The mix editor (drag the tracks, length, loop, save); null = a view only. */
    editor: DjMixEditor? = null,
) {
    val cs = MaterialTheme.colorScheme
    var editing by remember { mutableStateOf(false) }
    var dragA by remember { mutableStateOf(0f) }
    var dragB by remember { mutableStateOf(0f) }
    var dragLane by remember { mutableStateOf(0) }
    var widthPx by remember { mutableStateOf(1) }
    // a new picture (the re-planned edit) starts from where the tracks now are
    LaunchedEffect(data) {
        dragA = 0f
        dragB = 0f
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeckTitle(data.outgoing.title, cs.primary, Modifier.weight(1f))
            Text(
                text = "→",
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            DeckTitle(data.incoming.title, cs.tertiary, Modifier.weight(1f))
        }
        Text(
            text = summary(data, strings),
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        val canEdit = editing && editor != null && editor.enabled && data.kind != null
        DjMixCanvas(
            data = data,
            strings = strings,
            animatePlayhead = animatePlayhead,
            shiftAPx = dragA,
            shiftBPx = dragB,
            modifier =
                Modifier.fillMaxWidth().height(canvasHeight).onSizeChanged { widthPx = it.width.coerceAtLeast(1) }.then(
                    if (!canEdit) {
                        Modifier
                    } else {
                        Modifier.pointerInput(data) {
                            detectHorizontalDragGestures(
                                onDragStart = { o -> dragLane = if (o.y < size.height / 2f) 1 else 2 },
                                onHorizontalDrag = { change, dx ->
                                    change.consume()
                                    if (dragLane == 1) dragA += dx else dragB += dx
                                },
                                onDragEnd = {
                                    val msPerPx = (data.endMs - data.startMs).toDouble() / widthPx
                                    // the track slid right by t: its EARLIER part now plays under the mix
                                    val exitShift = -(dragA * msPerPx).toLong()
                                    val entryShift = -(dragB * msPerPx).toLong()
                                    if (exitShift != 0L || entryShift != 0L) editor?.onShift?.invoke(exitShift, entryShift)
                                },
                                onDragCancel = {
                                    dragA = 0f
                                    dragB = 0f
                                },
                            )
                        }
                    },
                ),
        )
        if (editor != null) {
            DjMixEditBar(editor, strings, editing, onToggle = { editing = !editing })
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = strings.phaseLine(data),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(12.dp))
            Legend(strings)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DjMixEditBar(editor: DjMixEditor, strings: DjMixStrings, editing: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // hear the prepared mix (the AI's, the DJ's or the edit) without waiting for the song to get there
            EditPill(if (editor.isListening) "■ " + strings.listenStop else "▶ " + strings.listen, lit = editor.isListening, enabled = editor.enabled || editor.isListening, onClick = editor.onListen)
            EditPill(if (editing) strings.editDone else "✎ " + strings.editStart, lit = editing, enabled = true, onClick = onToggle)
            if (editing) {
                EditPill("−", enabled = editor.enabled) { editor.onBars(-1) }
                Text(
                    editor.bars?.let { strings.editBarsTemplate.replace("{0}", it.toString()) } ?: "—",
                    style = MaterialTheme.typography.labelLarge, color = cs.onSurface, modifier = Modifier.align(Alignment.CenterVertically),
                )
                EditPill("+", enabled = editor.enabled) { editor.onBars(+1) }
                // the loop cycles 0 -> 1 -> 2 -> 4 -> 0 bars of the outgoing from its exit
                val next = when (editor.loopBars) { 0 -> 1; 1 -> 2; 2 -> 4; else -> 0 }
                EditPill(
                    if (editor.loopBars == 0) strings.editLoopOff else strings.editLoopTemplate.replace("{0}", editor.loopBars.toString()),
                    lit = editor.loopBars > 0, enabled = editor.enabled,
                ) { editor.onLoop(next) }
                EditPill(if (editor.isSaved) "✓ " + strings.editSaved else strings.editSave, lit = editor.isSaved, enabled = editor.enabled && editor.isEdit && !editor.isSaved) { editor.onSave() }
                EditPill(strings.editReset, enabled = editor.enabled && editor.isEdit) { editor.onReset() }
            }
        }
        if (editing || editor.isListening || editor.status != null) {
            Text(
                text = if (!editor.enabled) strings.editBusy else editor.status ?: strings.editHint,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EditPill(label: String, lit: Boolean = false, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg = if (lit) cs.primary else cs.onSurface.copy(alpha = 0.08f)
    val fg = (if (lit) cs.onPrimary else cs.onSurface).copy(alpha = if (enabled) 1f else 0.38f)
    Box(
        Modifier
            .background(bg, androidx.compose.foundation.shape.RoundedCornerShape(50))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

/** [DjMixPanel] in a tonal [Card], for inline use. */
@Composable
fun DjMixCardContent(
    data: DjMixViewData,
    modifier: Modifier = Modifier,
    strings: DjMixStrings = DjMixStrings(),
    canvasHeight: Dp = 200.dp,
    animatePlayhead: Boolean = true,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        DjMixPanel(data, Modifier.padding(16.dp), strings, canvasHeight, animatePlayhead)
    }
}

@Composable
private fun DeckTitle(title: String, dot: Color, modifier: Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            text = title.ifBlank { "—" },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Legend(strings: DjMixStrings) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LegendItem(strings.legendFader) { c ->
            drawLine(c, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
        LegendItem(strings.legendCut) { _ ->
            drawRect(cs.onSurface.copy(alpha = 0.28f))
        }
    }
}

@Composable
private fun LegendItem(label: String, swatch: androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 14.dp, height = 8.dp)) { swatch(ink) }
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

private fun summary(data: DjMixViewData, strings: DjMixStrings): String {
    val parts = ArrayList<String>(3)
    parts += strings.kindLabel(data.kind)
    if (data.kind != null) {
        val out = data.outgoing.bpm
        val inc = data.incoming.bpm
        val mix = data.mixBpm
        val bpm = when {
            data.kind == DjMixKind.BEAT_MATCHED && mix != null && out != null && inc != null ->
                "${bpmText(out)} → ${bpmText(mix)} ← ${bpmText(inc)} BPM"
            out != null && inc != null -> "${bpmText(out)} / ${bpmText(inc)} BPM"
            else -> null
        }
        if (bpm != null) parts += bpm
        val ko = data.outgoing.key
        val ki = data.incoming.key
        if (ko != null && ki != null) parts += "$ko → $ki"
    }
    return parts.joinToString(" · ")
}
