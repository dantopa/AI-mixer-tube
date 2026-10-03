package org.simpmusic.dj.android.perfect

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.simpmusic.dj.analysis.AnalysisRefiner
import org.simpmusic.dj.analysis.PhraseGrid
import org.simpmusic.dj.model.TrackAnalysis
import java.io.ByteArrayOutputStream
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Whether the DJ's phrase lines are right, as a picture: the track folded on its 16-bar blocks, one row per block, one
 * column per bar. The left half colours each bar by its bass (orange), the right half by its loudness (blue). Right lines
 * show as changes that start at column 1 (or 9): a section beginning, the bass coming back after a dark fill bar in
 * column 16. Lines one bar off show the change one column early or late on every row.
 */
object Phrasegram {
    private const val CELL = 16
    private const val HEADER = 50

    fun png(raw: TrackAnalysis, title: String?): ByteArray? {
        val r = AnalysisRefiner.cached(raw)
        val p = r.phrases ?: return null
        val beats = r.beatTimesMs?.value ?: return null
        val downs = r.downbeatBeatIndices?.value ?: return null
        if (downs.size != p.barPositions.size || downs.isEmpty()) return null
        val starts = downs.map { beats[it].toLong() }
        val hop = r.energyHopMs.coerceAtLeast(1)
        fun level(env: List<Float>, i: Int): Double {
            val t0 = starts[i]
            val t1 = if (i + 1 < starts.size) starts[i + 1] else t0 + 2000
            val h0 = (t0 / hop).toInt()
            val h1 = min(env.size, max(h0 + 1, (t1 / hop).toInt()))
            if (h0 >= env.size) return ln(1e-3)
            return (h0 until h1).map { ln(max(env[it].toDouble(), 1e-3)) }.average()
        }
        val lows = starts.indices.map { level(r.lowBandEnergy, it) }
        val ens = starts.indices.map { level(r.energy, it) }
        fun norm(v: Double, xs: List<Double>) = ((v - xs.min()) / (xs.max() - xs.min()).coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
        val rows = ArrayList<MutableList<Int>>()
        for (i in starts.indices) {
            if (rows.isEmpty() || p.barPositions[i] == 0 || p.barPositions[i] < p.barPositions[i - 1]) rows += mutableListOf<Int>()
            rows.last() += i
        }
        val half = 16 * CELL
        val width = 30 + 2 * half + 20
        val height = HEADER + rows.size * CELL + 10
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(24, 24, 28))
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13f }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; textSize = 10f }
        c.drawText((title ?: raw.videoId).take(60), 6f, 16f, text)
        c.drawText(PhraseGrid.describe(r).take(110), 6f, 31f, small)
        c.drawText("rows = 16-bar blocks; left = bass, right = loudness; lines at columns 1 and 9", 6f, 44f, small)
        val fill = Paint()
        for ((row, bars) in rows.withIndex()) {
            val y = HEADER + row * CELL
            c.drawText("${(starts[bars.first()] / 1000)}s", 2f, (y + CELL - 4).toFloat(), small)
            for (i in bars) {
                val col = p.barPositions[i]
                val lo = norm(lows[i], lows)
                val en = norm(ens[i], ens)
                fill.color = Color.rgb((255 * lo).toInt(), (120 * lo).toInt(), 30)
                c.drawRect((30 + col * CELL).toFloat(), y.toFloat(), (30 + col * CELL + CELL - 2).toFloat(), (y + CELL - 2).toFloat(), fill)
                fill.color = Color.rgb(30, (200 * en).toInt(), (255 * en).toInt())
                val x = 30 + half + 20 + col * CELL
                c.drawRect(x.toFloat(), y.toFloat(), (x + CELL - 2).toFloat(), (y + CELL - 2).toFloat(), fill)
            }
        }
        val line = Paint().apply { color = Color.WHITE; strokeWidth = 1f }
        for (x0 in listOf(30, 30 + half + 20)) for (col in listOf(0, 8)) {
            val x = (x0 + col * CELL - 1).toFloat()
            c.drawLine(x, HEADER.toFloat(), x, height.toFloat() - 10, line)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
