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
import androidx.compose.runtime.Composable
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
) {
    val cs = MaterialTheme.colorScheme
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
        DjMixCanvas(
            data = data,
            strings = strings,
            animatePlayhead = animatePlayhead,
            modifier = Modifier.fillMaxWidth().height(canvasHeight),
        )
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
