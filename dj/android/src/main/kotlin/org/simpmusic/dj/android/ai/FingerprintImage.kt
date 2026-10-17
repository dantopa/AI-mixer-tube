package org.simpmusic.dj.android.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.simpmusic.dj.ai.MixBrief
import org.simpmusic.dj.analysis.Harmony
import org.simpmusic.dj.timeline.TrackTimeline
import java.io.ByteArrayOutputStream

/**
 * The fingerprint of both tracks as one picture for Claude's eye (the owner, 2026-10-17: "let it see the fingerprint of
 * both"): per track a lane, one column per bar, with loudness, bass, highs and voice as bars of light, a 12-row chroma
 * strip (the notes being played: a held key shows as a steady pattern, a key change as a new one), and the phrase lines
 * (thick = 16-bar block, thin = 8-bar phrase) with bar numbers every 4 bars, the same numbers as the tables. The exits Claude
 * may choose are shaded on the outgoing lane. Drawn with android.graphics, a PNG of at most ~1560 x 760.
 */
object FingerprintImage {
    private const val MAX_W = 1560
    private const val LEFT = 64
    private const val LANE_H = 360
    private val NOTES = arrayOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

    fun png(brief: MixBrief.Brief, out: TrackTimeline, inc: TrackTimeline, outTitle: String, inTitle: String): ByteArray? =
        runCatching {
            val maxBars = maxOf(brief.outBarsMs.size, brief.inBarsMs.size).coerceAtLeast(1)
            val px = ((MAX_W - LEFT - 16) / maxBars).coerceIn(4, 18)
            val w = LEFT + maxBars * px + 16
            val h = 2 * LANE_H + 40
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.rgb(18, 18, 24))
            lane(c, 8f, "OUTGOING: $outTitle", out, brief.outBarsMs, px, brief.exitFrom..brief.exitTo)
            lane(c, 8f + LANE_H + 24f, "INCOMING: $inTitle", inc, brief.inBarsMs, px, null)
            val bytes = ByteArrayOutputStream().use { s -> bmp.compress(Bitmap.CompressFormat.PNG, 100, s); s.toByteArray() }
            bmp.recycle()
            bytes
        }.getOrNull()

    private fun lane(c: Canvas, top: Float, title: String, t: TrackTimeline, bars: List<Long>, px: Int, allowed: IntRange?) {
        val a = t.refined
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15f }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(170, 170, 185); textSize = 11f }
        val fill = Paint().apply { style = Paint.Style.FILL }
        c.drawText(title.take(80), LEFT.toFloat(), top + 14f, text)
        val x0 = LEFT.toFloat()
        val xOf = { i: Int -> x0 + i * px }
        val end = a.durationMs
        val hop = a.energyHopMs
        // rows: name, height, curve, colour
        val rows =
            listOf(
                Triple("loud", 70, a.energy) to Color.rgb(120, 190, 255),
                Triple("bass", 54, a.lowBandEnergy) to Color.rgb(255, 150, 70),
                Triple("highs", 40, a.highBandEnergy) to Color.rgb(200, 200, 120),
            )
        var y = top + 24f
        if (allowed != null && !allowed.isEmpty()) {
            fill.color = Color.argb(40, 80, 220, 120)
            c.drawRect(xOf(allowed.first), y, xOf(allowed.last + 1), top + LANE_H - 30f, fill)
        }
        for ((row, colour) in rows) {
            val (name, rh, curve) = row
            c.drawText(name, 6f, y + rh / 2f + 4f, small)
            val lv = MixBrief.levelsOf(curve, hop)
            fill.color = colour
            for (i in bars.indices) {
                val s = bars[i]
                val e = bars.getOrNull(i + 1) ?: end
                val v = lv(s, e).toIntOrNull() ?: continue
                val bh = rh * (v + 1) / 10f
                c.drawRect(xOf(i) + 1, y + rh - bh, xOf(i + 1) - 1, y + rh, fill)
            }
            y += rh + 6
        }
        // voice: where someone sings (lyrics or a trusted detection), 0-9 per bar as opacity
        c.drawText("voice", 6f, y + 12f, small)
        val vocals = t.vocals?.value
        for (i in bars.indices) {
            val s = bars[i]
            val e = bars.getOrNull(i + 1) ?: end
            val v = vocals?.let { MixBrief.voiceLevel(it, s, e).toIntOrNull() }
            fill.color = if (v == null) Color.rgb(50, 50, 60) else Color.argb(40 + 215 * v / 9, 240, 90, 160)
            c.drawRect(xOf(i) + 1, y, xOf(i + 1) - 1, y + 16, fill)
        }
        y += 24
        // chroma: 12 rows, the notes being played in each bar (brightest = loudest pitch class of that bar)
        val profile = Harmony.profile(a)
        val ch = 7
        for (k in 0 until 12) c.drawText(NOTES[11 - k], 6f, y + k * ch + ch, small.apply { textSize = 9f })
        small.textSize = 11f
        if (profile != null) {
            for (i in bars.indices) {
                val s = bars[i]
                val e = bars.getOrNull(i + 1) ?: end
                val v = profile.window(s, e - s) ?: continue
                val mx = v.max().coerceAtLeast(1e-9)
                for (k in 0 until 12) {
                    val g = (255 * (v[k] / mx).coerceIn(0.0, 1.0)).toInt()
                    fill.color = Color.rgb(g / 3, g, g / 2)
                    c.drawRect(xOf(i), y + (11 - k) * ch, xOf(i + 1), y + (12 - k) * ch, fill)
                }
            }
        }
        y += 12 * ch + 8
        // phrase lines and bar numbers
        val line = Paint().apply { color = Color.argb(140, 255, 255, 255) }
        val blocks = t.blocks?.value?.startsMs.orEmpty()
        val phrases = t.phrases?.value?.startsMs.orEmpty()
        for (i in bars.indices) {
            val s = bars[i]
            val isBlock = blocks.any { kotlin.math.abs(it - s) < 300 }
            val isPhrase = isBlock || phrases.any { kotlin.math.abs(it - s) < 300 }
            if (isPhrase) {
                line.strokeWidth = if (isBlock) 3f else 1f
                c.drawLine(xOf(i), top + 24f, xOf(i), y, line)
            }
            if (i % 4 == 0) c.drawText("$i", xOf(i), y + 12f, small)
        }
    }
}
