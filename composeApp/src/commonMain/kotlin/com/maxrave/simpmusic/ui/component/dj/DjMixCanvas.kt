package com.maxrave.simpmusic.ui.component.dj

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import org.simpmusic.dj.mixview.DjMixDeck
import org.simpmusic.dj.mixview.DjMixKind
import org.simpmusic.dj.mixview.DjMixPhase
import org.simpmusic.dj.mixview.DjMixViewData
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Colours of the picture: all taken from the theme, resolved once per composition. */
private class MixColors(
    val a: Color,
    val b: Color,
    val zone: Color,
    val cut: Color,
    val ink: Color,
    val surface: Color,
    val lane: Color,
    val muted: Color,
    /** What the low band is blended toward: brighter on a dark surface, darker on a light one. */
    val lowInk: Color,
)

/**
 * The DJ-software view of one transition: two lanes (outgoing on top, incoming below) drawn on the plan's time axis,
 * with beat ticks facing each other in the gap, the overlap zone, both faders, the EQ cut bands and a playhead.
 *
 * [data] carries the playhead as `nowMs` (fed at ~10 Hz is plenty): between updates the playhead is carried forward on
 * the frame clock and never moves backwards, so it does not step. Set [animatePlayhead] to false for a static render.
 */
@Composable
fun DjMixCanvas(
    data: DjMixViewData,
    modifier: Modifier = Modifier,
    strings: DjMixStrings = DjMixStrings(),
    animatePlayhead: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val colors = MixColors(
        a = cs.primary,
        b = cs.tertiary,
        zone = cs.secondary,
        cut = cs.onSurface,
        ink = cs.onSurface,
        surface = cs.surface,
        lane = cs.onSurface,
        muted = cs.onSurfaceVariant,
        lowInk = if (cs.surface.luminance() > 0.5f) Color.Black else Color.White,
    )
    val measurer = rememberTextMeasurer()
    val small = MaterialTheme.typography.labelSmall
    val labelStyle = remember(small, colors.muted) { small.copy(color = colors.muted) }
    val badgeStyleA = remember(small, cs.onPrimary) { small.copy(color = cs.onPrimary) }
    val badgeStyleB = remember(small, cs.onTertiary) { small.copy(color = cs.onTertiary) }
    val emptyStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.muted)
    val cache = remember { HashMap<String, TextLayoutResult>() }
    val shown = rememberSmoothNow(data.nowMs, animatePlayhead)
    val overlapText = if (data.kind != null && data.overlapEndMs > 0) strings.overlapLabel(data.overlapEndMs) else ""

    Canvas(modifier = modifier) {
        val now = if (animatePlayhead) shown.value else data.nowMs
        val painter = MixPainter(this, data, colors, measurer, cache, labelStyle, badgeStyleA, badgeStyleB)
        painter.draw(now, overlapText, if (data.phase == DjMixPhase.ANALYSING) strings.analysing else null, emptyStyle)
    }
}

/** Playhead in plan ms, carried forward between engine updates on the frame clock; monotone, snaps on real jumps. */
@Composable
private fun rememberSmoothNow(nowMs: Double?, animate: Boolean): androidx.compose.runtime.MutableState<Double?> {
    val target = rememberUpdatedState(nowMs)
    val shown = remember { mutableStateOf(nowMs) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        var lastSeen: Double? = null
        var anchorValue = 0.0
        var anchorNanos = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { frame ->
                val t = target.value
                if (t == null) {
                    shown.value = null
                    lastSeen = null
                } else {
                    if (t != lastSeen) {
                        lastSeen = t
                        anchorValue = t
                        anchorNanos = frame
                    }
                    val predicted = min(anchorValue + (frame - anchorNanos) / 1e6, t + MAX_LEAD_MS)
                    val cur = shown.value
                    shown.value = if (cur != null && abs(cur - predicted) < SNAP_MS) max(cur, predicted) else predicted
                }
            }
        }
    }
    return shown
}

private const val MAX_LEAD_MS = 250.0
private const val SNAP_MS = 400.0

private class MixPainter(
    private val scope: DrawScope,
    private val data: DjMixViewData,
    private val c: MixColors,
    private val measurer: TextMeasurer,
    private val cache: HashMap<String, TextLayoutResult>,
    private val labelStyle: TextStyle,
    private val badgeStyleA: TextStyle,
    private val badgeStyleB: TextStyle,
) {
    private val dp1 = with(scope) { 1.dp.toPx() }
    private val w = scope.size.width
    private val h = scope.size.height

    private val topPad = 26 * dp1
    private val axisH = 18 * dp1
    private val gap = 18 * dp1
    private val laneH = max(1f, (h - topPad - axisH - gap) / 2f)
    private val laneATop = topPad
    private val laneBTop = topPad + laneH + gap
    private val spanMs = (data.endMs - data.startMs).toFloat().coerceAtLeast(1f)

    private fun x(tMs: Double): Float = ((tMs - data.startMs) / spanMs * w).toFloat()

    private fun text(s: String, style: TextStyle): TextLayoutResult = cache.getOrPut("${style.hashCode()}|$s") { measurer.measure(s, style) }

    fun draw(now: Double?, overlapText: String, emptyText: String?, emptyStyle: TextStyle) = with(scope) {
        lane(laneATop, c.a, isTop = true)
        lane(laneBTop, c.b, isTop = false)
        if (emptyText != null) {
            skeleton(laneATop, c.a)
            skeleton(laneBTop, c.b)
            val t = text(emptyText, emptyStyle)
            drawText(t, topLeft = Offset((w - t.size.width) / 2f, (h - axisH - t.size.height) / 2f))
            axis()
            return@with
        }
        zone(overlapText)
        grid(data.outgoing, laneATop, c.a)
        grid(data.incoming, laneBTop, c.b)
        waveform(data.outgoing, laneATop, c.a)
        waveform(data.incoming, laneBTop, c.b)
        cutBands(data.outgoing, laneATop)
        cutBands(data.incoming, laneBTop)
        fader(data.outgoing, laneATop)
        fader(data.incoming, laneBTop)
        ticks(data.outgoing, laneATop + laneH, down = true, color = c.a)
        ticks(data.incoming, laneBTop, down = false, color = c.b)
        axis()
        if (now != null) playhead(now)
        badge("A", c.a, badgeStyleA, laneATop, readout(data.outgoing))
        badge("B", c.b, badgeStyleB, laneBTop, readout(data.incoming))
    }

    private fun DrawScope.lane(top: Float, color: Color, isTop: Boolean) {
        drawRoundRect(
            color = c.lane.copy(alpha = 0.06f), topLeft = Offset(0f, top), size = Size(w, laneH),
            cornerRadius = CornerRadius(6 * dp1),
        )
        drawLine(c.lane.copy(alpha = 0.10f), Offset(0f, top + laneH / 2), Offset(w, top + laneH / 2), strokeWidth = dp1)
        // A thin lane-coloured edge on the outer side: which deck is which, even without reading the badge.
        val y = if (isTop) top else top + laneH
        drawLine(color.copy(alpha = 0.55f), Offset(0f, y), Offset(w, y), strokeWidth = 1.5f * dp1)
    }

    /** Flat placeholder bars for the empty state. */
    private fun DrawScope.skeleton(top: Float, color: Color) {
        val cy = top + laneH / 2
        val step = 4 * dp1
        var i = 0
        var px = step / 2
        while (px < w) {
            val a = (0.18f + 0.14f * sin(i * 0.37f) + 0.10f * sin(i * 0.11f + 1f)).coerceIn(0.05f, 0.5f)
            val half = a * laneH * 0.4f
            drawLine(color.copy(alpha = 0.16f), Offset(px, cy - half), Offset(px, cy + half), strokeWidth = 2 * dp1, cap = StrokeCap.Round)
            px += step
            i++
        }
    }

    private fun DrawScope.zone(overlapText: String) {
        val kind = data.kind
        val x0 = x(0.0)
        if (kind == DjMixKind.CUT || kind == DjMixKind.ECHO_OUT || data.overlapEndMs <= 0) {
            drawLine(
                c.zone.copy(alpha = 0.9f), Offset(x0, topPad - 4 * dp1), Offset(x0, h - axisH),
                strokeWidth = 2 * dp1, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6 * dp1, 4 * dp1)),
            )
            return
        }
        val x1 = x(data.overlapEndMs.toDouble())
        drawRect(c.zone.copy(alpha = 0.16f), Offset(x0, topPad - 4 * dp1), Size(x1 - x0, h - axisH - topPad + 4 * dp1))
        val dash = PathEffect.dashPathEffect(floatArrayOf(5 * dp1, 4 * dp1))
        for (xx in floatArrayOf(x0, x1)) {
            drawLine(c.zone.copy(alpha = 0.85f), Offset(xx, topPad - 4 * dp1), Offset(xx, h - axisH), strokeWidth = 1.5f * dp1, pathEffect = dash)
        }
        if (overlapText.isNotEmpty()) {
            val t = text(overlapText, labelStyle.copy(color = c.zone))
            val mid = (x0 + x1) / 2
            if (t.size.width < x1 - x0 - 4 * dp1) drawText(t, topLeft = Offset(mid - t.size.width / 2f, 0f))
            else drawText(t, topLeft = Offset(min(max(0f, x0), w - t.size.width.toFloat()), 0f))
        }
    }

    /** Bar lines through the lane at each downbeat: the beat grid of the deck, in its own colour. */
    private fun DrawScope.grid(deck: DjMixDeck, top: Float, color: Color) {
        for (t in deck.downbeatsMs) {
            val xx = x(t.toDouble())
            if (xx < 0 || xx > w) continue
            drawLine(color.copy(alpha = 0.22f), Offset(xx, top), Offset(xx, top + laneH), strokeWidth = dp1)
        }
    }

    private fun DrawScope.waveform(deck: DjMixDeck, top: Float, color: Color) {
        val cy = top + laneH / 2
        val half = laneH / 2 - 3 * dp1
        val barPitch = 3 * dp1
        val barW = 2 * dp1
        val n = deck.energy.size
        if (n == 0) return
        val vMax = max(1e-3f, deck.volume.maxOrNull() ?: 1f)
        val lowColor = color.blend(c.lowInk, 0.30f)
        var px = barPitch / 2
        while (px < w) {
            val t = (data.startMs + (px / w) * spanMs).toDouble()
            if (t >= deck.activeFromMs) {
                val e = sampleAt(deck.energy, t)
                val lo = min(sampleAt(deck.lowEnergy, t), e)
                val vol = (sampleAt(deck.volume, t) / vMax).coerceIn(0f, 1f)
                val alpha = 0.30f + 0.70f * vol
                if (e > 0.004f) {
                    val eh = max(1.5f * dp1, e.pow(0.8f) * half)
                    // Full-band energy as the outer body, a little dimmer than the low-band core inside it.
                    drawLine(color.copy(alpha = alpha * 0.62f), Offset(px, cy - eh), Offset(px, cy + eh), strokeWidth = barW, cap = StrokeCap.Butt)
                    // The bass core fades as the deck's low-cut closes on it: the bass swap reads as the core changing hands.
                    val kept = 1f - cutFraction(sampleAt(deck.lowCutHz, t))
                    if (lo > 0.02f) {
                        val lh = max(1f * dp1, lo.pow(0.8f) * half * 0.85f)
                        drawLine(lowColor.copy(alpha = alpha * (0.15f + 0.85f * kept)), Offset(px, cy - lh), Offset(px, cy + lh), strokeWidth = barW, cap = StrokeCap.Butt)
                    }
                }
            } else {
                // Deck not started: a dotted flat line, so the empty stretch reads as "waiting", not as broken.
                drawCircle(color.copy(alpha = 0.35f), radius = 0.8f * dp1, center = Offset(px, cy))
            }
            px += barPitch
        }
    }

    /** Where the low-cut (from the bottom) and high-cut (from the top) filters are biting, as tinted bands. */
    private fun DrawScope.cutBands(deck: DjMixDeck, top: Float) {
        val n = deck.lowCutHz.size
        if (n < 2) return
        val bottom = top + laneH
        val maxBand = laneH * 0.34f
        val first = ((deck.activeFromMs - data.startMs) / data.stepMs).toInt().coerceIn(0, n - 2)
        val low = Path()
        val high = Path()
        val xs = FloatArray(n) { x((data.startMs + it.toLong() * data.stepMs).toDouble()) }
        val lowFrac = FloatArray(n) { cutFraction(deck.lowCutHz[it]) }
        val highFrac = FloatArray(n) { (ln(20_000f / max(deck.highCutHz[it], 1000f)) / ln(20_000f / 1000f)).coerceIn(0f, 1f) }
        if ((first until n).any { lowFrac[it] > 0.02f }) {
            low.moveTo(xs[first], bottom)
            for (i in first until n) low.lineTo(xs[i], bottom - lowFrac[i] * maxBand)
            low.lineTo(xs[n - 1], bottom)
            low.close()
            drawPath(low, c.cut.copy(alpha = 0.16f))
            val edge = Path()
            edge.moveTo(xs[first], bottom - lowFrac[first] * maxBand)
            for (i in first + 1 until n) edge.lineTo(xs[i], bottom - lowFrac[i] * maxBand)
            drawPath(edge, c.cut.copy(alpha = 0.55f), style = Stroke(width = 1.25f * dp1, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4 * dp1, 3 * dp1))))
        }
        if ((first until n).any { highFrac[it] > 0.02f }) {
            high.moveTo(xs[first], top)
            for (i in first until n) high.lineTo(xs[i], top + highFrac[i] * maxBand)
            high.lineTo(xs[n - 1], top)
            high.close()
            drawPath(high, c.cut.copy(alpha = 0.12f))
        }
    }

    private fun DrawScope.fader(deck: DjMixDeck, top: Float) {
        val n = deck.volume.size
        if (n < 2) return
        val vTop = max(1f, deck.volume.maxOrNull() ?: 1f)
        val bottom = top + laneH - 3 * dp1
        val range = (laneH - 6 * dp1) * 0.92f
        val path = Path()
        var started = false
        for (i in 0 until n) {
            val t = data.startMs + i.toLong() * data.stepMs
            if (t < deck.activeFromMs) continue
            val xx = x(t.toDouble())
            val yy = bottom - (deck.volume[i] / vTop).coerceIn(0f, 1f) * range
            if (!started) {
                path.moveTo(xx, yy)
                started = true
            } else {
                path.lineTo(xx, yy)
            }
        }
        if (!started) return
        drawPath(path, c.surface.copy(alpha = 0.75f), style = Stroke(width = 4 * dp1, cap = StrokeCap.Round))
        drawPath(path, c.ink.copy(alpha = 0.92f), style = Stroke(width = 2 * dp1, cap = StrokeCap.Round))
    }

    /** Beat ticks hang from the inner edges of the two lanes into the gap: aligned grids make them mirror each other. */
    private fun DrawScope.ticks(deck: DjMixDeck, edgeY: Float, down: Boolean, color: Color) {
        val sign = if (down) 1f else -1f
        val downSet = deck.downbeatsMs.toHashSet()
        for (t in deck.beatsMs) {
            val xx = x(t.toDouble())
            if (xx < 0 || xx > w) continue
            val strong = t in downSet
            val len = (if (strong) 9f else 4.5f) * dp1
            drawLine(
                color.copy(alpha = if (strong) 1f else 0.7f), Offset(xx, edgeY), Offset(xx, edgeY + sign * len),
                strokeWidth = (if (strong) 2f else 1.25f) * dp1,
            )
        }
    }

    private fun DrawScope.badge(letter: String, color: Color, style: TextStyle, top: Float, readout: String) {
        val t = text(letter, style)
        val pad = 4 * dp1
        val size = max(t.size.width, t.size.height) + pad
        val off = Offset(4 * dp1, top + 4 * dp1)
        drawRoundRect(color, off, Size(size, size), CornerRadius(size / 2))
        drawText(t, topLeft = Offset(off.x + (size - t.size.width) / 2, off.y + (size - t.size.height) / 2))
        if (readout.isNotEmpty()) {
            val r = text(readout, labelStyle.copy(color = c.ink))
            val rx = off.x + size + 4 * dp1
            val ry = off.y + (size - r.size.height) / 2
            drawRoundRect(
                c.surface.copy(alpha = 0.72f), Offset(rx - 3 * dp1, ry - 1 * dp1), Size(r.size.width + 6 * dp1, r.size.height + 2 * dp1),
                CornerRadius(4 * dp1),
            )
            drawText(r, topLeft = Offset(rx, ry))
        }
    }

    /** "x1.069 +2 st": what the tempo and key lanes do to this deck at the moment it becomes audible. */
    private fun readout(deck: DjMixDeck): String {
        if (deck.rate.isEmpty()) return ""
        val i = ((0 - data.startMs) / data.stepMs).toInt().coerceIn(0, deck.rate.size - 1)
        val parts = ArrayList<String>(2)
        val r = deck.rate[i]
        if (abs(r - 1f) >= 0.0015f) parts += "\u00D7" + threeDecimals(r)
        val pitch = deck.pitchSemitones.maxByOrNull { abs(it) } ?: 0f
        if (abs(pitch) >= 0.05f) parts += (if (pitch > 0) "+" else "\u2212") + oneDecimal(abs(pitch).toDouble()).removeSuffix(".0") + " st"
        return parts.joinToString(" ")
    }

    private fun threeDecimals(v: Float): String {
        val m = kotlin.math.round(v * 1000f).toInt()
        return "${m / 1000}.${(m % 1000).toString().padStart(3, '0')}"
    }

    private fun DrawScope.axis() {
        val minPx = 56 * dp1
        val candidates = longArrayOf(1_000, 2_000, 4_000, 5_000, 8_000, 10_000, 15_000, 20_000, 30_000)
        val interval = candidates.firstOrNull { it / spanMs * w >= minPx } ?: candidates.last()
        val y = h - axisH
        var t = ceil(data.startMs.toDouble() / interval).toLong() * interval
        while (t <= data.endMs) {
            val xx = x(t.toDouble())
            drawLine(c.muted.copy(alpha = 0.6f), Offset(xx, y + 1 * dp1), Offset(xx, y + 5 * dp1), strokeWidth = dp1)
            val s = when {
                t == 0L -> "0"
                t > 0 -> "+${t / 1000}s"
                else -> "−${-t / 1000}s"
            }
            val tr = text(s, if (t == 0L) labelStyle.copy(color = c.zone) else labelStyle)
            val left = (xx - tr.size.width / 2f).coerceIn(0f, w - tr.size.width)
            drawText(tr, topLeft = Offset(left, y + 6 * dp1))
            t += interval
        }
    }

    private fun DrawScope.playhead(now: Double) {
        val xx = x(now)
        if (xx < 0 || xx > w) return
        // What is already played is dimmed, so the eye reads what is still to come.
        drawRect(c.surface.copy(alpha = 0.40f), Offset(0f, laneATop), Size(xx, h - axisH - laneATop))
        drawLine(c.ink.copy(alpha = 0.18f), Offset(xx, topPad - 2 * dp1), Offset(xx, h - axisH), strokeWidth = 7 * dp1)
        drawLine(c.ink, Offset(xx, topPad - 2 * dp1), Offset(xx, h - axisH), strokeWidth = 2 * dp1)
        val tri = Path().apply {
            moveTo(xx - 5 * dp1, topPad - 10 * dp1)
            lineTo(xx + 5 * dp1, topPad - 10 * dp1)
            lineTo(xx, topPad - 2 * dp1)
            close()
        }
        drawPath(tri, c.ink)
    }

    /** 0 (filter open, 20 Hz) .. 1 (300+ Hz): how much of the bass a high-pass at [hz] removes, on a log scale. */
    private fun cutFraction(hz: Float): Float = (ln(max(hz, 20f) / 20f) / ln(300f / 20f)).coerceIn(0f, 1f)

    private fun sampleAt(series: List<Float>, tMs: Double): Float {
        val f = (tMs - data.startMs) / data.stepMs
        val i = floor(f).toInt()
        if (i < 0) return series.first()
        if (i >= series.size - 1) return series.last()
        val fr = (f - i).toFloat()
        return series[i] + (series[i + 1] - series[i]) * fr
    }
}

private fun Color.blend(other: Color, amount: Float): Color = Color(
    red + (other.red - red) * amount,
    green + (other.green - green) * amount,
    blue + (other.blue - blue) * amount,
    alpha,
)
