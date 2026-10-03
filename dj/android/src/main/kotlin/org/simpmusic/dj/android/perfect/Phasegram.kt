package org.simpmusic.dj.android.perfect

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.simpmusic.dj.analysis.AnalysisRefiner
import org.simpmusic.dj.analysis.BarPhase
import org.simpmusic.dj.model.TrackAnalysis
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.min

/**
 * A picture that shows whether the DJ's "1" is right, for the owner (or Claude, reading the export): the track folded on
 * its decided bar lattice, one row per bar, one column per beat of the bar, each cell coloured by the network's evidence
 * that this beat is a 1 (red = yes, blue = no).
 *
 * A right 1 is a red stripe down column 1. A half-bar error puts the stripe in column 3; a dropped or extra beat bends it
 * sideways; an evenly coloured grid means the evidence cannot tell (the margin says so too). A waveform with the 1s drawn
 * on it would not show any of this: on cumbia and dembow every beat has the same kick, and it looks right at any phase.
 *
 * Columns of [ROWS_PER_COLUMN] bars wrap side by side. A black dot marks the tracker's own picked downbeats, a white ring
 * the owner's tapped 1.
 */
object Phasegram {
    private const val CELL = 14
    private const val ROWS_PER_COLUMN = 48
    private const val HEADER = 54
    private const val GAP = 18

    fun png(raw: TrackAnalysis, title: String?): ByteArray? {
        val r = AnalysisRefiner.cached(raw)
        val info = r.barPhase ?: return null
        val beats = r.beatTimesMs?.value ?: return null
        val downs = r.downbeatBeatIndices?.value ?: return null
        val ev = r.beatDownbeatLogits ?: return null
        if (downs.isEmpty()) return null
        val bpb = r.beatsPerBar ?: 4
        val picked = raw.downbeatBeatIndices?.value?.mapNotNull { idx -> raw.beatTimesMs?.value?.getOrNull(idx) }?.toHashSet() ?: emptySet<Int>()
        val anchor = BarPhase.anchors(raw.videoId)

        val bars = downs.size
        val columns = (bars + ROWS_PER_COLUMN - 1) / ROWS_PER_COLUMN
        val colW = bpb * CELL + GAP + 26
        val width = maxOf(420, columns * colW + 10)
        val height = HEADER + min(bars, ROWS_PER_COLUMN) * CELL + 10
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(24, 24, 28))
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13f }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; textSize = 10f }
        c.drawText((title ?: raw.videoId).take(60), 6f, 16f, text)
        c.drawText(
            "${info.source} margin %.1f%s slips ${info.slips} moved ${info.changedBars}/$bars bars  (${if (info.trusted) "PERFECT-ready" else "not sure"})".format(
                info.margin, if (anchor != null) " tapped@${anchor}ms" else "",
            ),
            6f, 32f, small,
        )
        c.drawText("red = network says 1, blue = not; a right 1 is a red stripe in column 1", 6f, 46f, small)

        val fill = Paint()
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f }
        for (k in 0 until bars) {
            val col = k / ROWS_PER_COLUMN
            val row = k % ROWS_PER_COLUMN
            val x0 = 6 + col * colW
            val y0 = HEADER + row * CELL
            if (row % 4 == 0) c.drawText("$k", x0.toFloat(), (y0 + CELL - 3).toFloat(), small)
            val start = downs[k]
            val end = if (k + 1 < bars) downs[k + 1] else minOf(beats.size, start + bpb)
            for (j in 0 until bpb) {
                val bi = start + j
                val x = x0 + 22 + j * CELL
                if (bi >= end) {
                    // a short bar (the lattice skipped a beat here)
                    fill.color = Color.rgb(60, 60, 60)
                } else {
                    fill.color = diverging(ev[bi])
                }
                c.drawRect(x.toFloat(), y0.toFloat(), (x + CELL - 1).toFloat(), (y0 + CELL - 1).toFloat(), fill)
                if (bi < end) {
                    val t = beats[bi]
                    if (t in picked) c.drawCircle(x + CELL / 2f, y0 + CELL / 2f, 2.2f, dot)
                    if (anchor != null && abs(t - anchor) <= 120) c.drawCircle(x + CELL / 2f, y0 + CELL / 2f, CELL / 2f - 1, ring)
                }
            }
            if (end - start > bpb) c.drawText("+${end - start - bpb}", (x0 + 22 + bpb * CELL + 2).toFloat(), (y0 + CELL - 3).toFloat(), small)
        }
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            out.toByteArray()
        }
    }

    /** -6 (blue) .. 0 (grey) .. +6 (red) log-odds. */
    private fun diverging(v: Float): Int {
        val t = (v / 6f).coerceIn(-1f, 1f)
        return if (t >= 0) {
            Color.rgb((90 + 165 * t).toInt(), (90 - 60 * t).toInt(), (90 - 60 * t).toInt())
        } else {
            val u = -t
            Color.rgb((90 - 60 * u).toInt(), (90 - 30 * u).toInt(), (90 + 165 * u).toInt())
        }
    }

    private fun abs(x: Int) = if (x < 0) -x else x
    private fun abs(x: Long) = if (x < 0) -x else x
}
