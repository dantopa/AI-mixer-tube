package org.simpmusic.dj

import org.simpmusic.dj.analysis.PhraseGrid
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhraseGridTest {
    init { PhraseGrid.debug = { println(it) } }

    private val barMs = 4 * 60000.0 / 124
    private val hop = 100

    /**
     * Envelopes of a track built from 16-bar blocks after a [pickupBars]-bar pickup: each block has its own level and
     * bass, the second half of a block (bar 8) steps up a little, and the bass drops out in the last bar of every block
     * (the classic fill before the line). Returns bar starts, energy, low band, and the true block-start bars.
     */
    private fun track(blocks: List<Pair<Float, Float>>, pickupBars: Int, noise: Float, seed: Int = 1, structured: Boolean = true): Quad {
        val r = Random(seed)
        val bars = pickupBars + blocks.size * 16
        val starts = List(bars) { (it * barMs).toLong() + 500 }
        val n = ((starts.last() + barMs) / hop).toInt() + 20
        val e = FloatArray(n)
        val l = FloatArray(n)
        for (h in 0 until n) {
            val t = h * hop
            val bar = ((t - 500) / barMs).toInt()
            if (t < 500 || bar >= bars) continue
            val (lev, bass) = if (bar < pickupBars) 0.2f to 0.05f else blocks[(bar - pickupBars) / 16]
            val inBlock = if (bar < pickupBars) 0 else (bar - pickupBars) % 16
            val half = if (structured && inBlock >= 8) 1.15f else 1f
            val fill = structured && inBlock == 15
            e[h] = (lev * half * (1 + (r.nextFloat() * 2 - 1) * noise)).coerceIn(0.01f, 1f)
            l[h] = (if (fill) 0.03f else bass * half * (1 + (r.nextFloat() * 2 - 1) * noise)).coerceIn(0.01f, 1f)
        }
        val truth = (0 until blocks.size).map { pickupBars + 16 * it }
        return Quad(starts, e.toList(), l.toList(), truth)
    }

    class Quad(val starts: List<Long>, val e: List<Float>, val l: List<Float>, val truth: List<Int>)

    private val song = listOf(0.4f to 0.3f, 0.8f to 0.8f, 0.5f to 0.1f, 0.9f to 0.9f, 0.85f to 0.85f, 0.4f to 0.3f)

    @Test
    fun blocksAreFoundAfterAnOddPickup() {
        for (pickup in listOf(0, 2, 3, 6)) {
            val q = track(song, pickupBars = pickup, noise = 0.25f)
            val res = PhraseGrid.detect(q.starts, PhraseGrid.Envelopes(hop, q.e, q.l))!!
            val found = res.position.indices.filter { res.position[it] == 0 }
            println("pickup $pickup: blocks at $found (true ${q.truth}), margins phrase %.1f block %.1f, irregular ${res.irregular}".format(res.phraseMargin, res.blockMargin))
            assertTrue(q.truth.all { it in found }, "pickup $pickup: $found vs ${q.truth}")
            assertTrue(res.phraseMargin >= PhraseGrid.PHRASE_TRUST)
            assertTrue(res.blockMargin >= PhraseGrid.BLOCK_TRUST)
        }
    }

    @Test
    fun aFlatTrackIsNotTrusted() {
        val q = track(List(6) { 0.6f to 0.6f }, pickupBars = 0, noise = 0.3f, seed = 4, structured = false)
        val res = PhraseGrid.detect(q.starts, PhraseGrid.Envelopes(hop, q.e, q.l))!!
        println("flat: margins phrase %.1f block %.1f".format(res.phraseMargin, res.blockMargin))
        assertFalse(res.phraseMargin >= PhraseGrid.PHRASE_TRUST && res.blockMargin >= PhraseGrid.BLOCK_TRUST)
    }

    @Test
    fun theOwnersMarkIsALineEvenWhereTheEvidenceIsFlat() {
        val q = track(List(6) { 0.6f to 0.6f }, pickupBars = 0, noise = 0.3f, seed = 4, structured = false)
        val mark = q.starts[37]
        val res = PhraseGrid.detect(q.starts, PhraseGrid.Envelopes(hop, q.e, q.l), anchorMs = mark + 400)!!
        assertEquals(0, res.position[37])
        assertTrue(res.anchored)
    }

    /**
     * Noise-only tracks must rarely claim sure 16-bar lines, clean ones always, and on a noisy song the lines it claims to
     * be sure of must be right.
     */
    @Test
    fun sureMeansRightAndNoiseIsNotSure() {
        PhraseGrid.debug = null
        val flat = (1..60).map { seed -> val q = track(List(6) { 0.6f to 0.6f }, 0, 0.3f, seed, structured = false); PhraseGrid.detect(q.starts, PhraseGrid.Envelopes(hop, q.e, q.l))!! }
        val flatSure = flat.count { PhraseGrid.blocksSure(it) }
        // (noise, at least this many sure, at most this many sure but wrong); 1.5 is +-150 % per 100 ms hop: barely music
        for ((noise, minSure, maxWrong) in listOf(Triple(0.35f, 60, 0), Triple(0.8f, 40, 0), Triple(1.5f, 0, 1))) {
            val runs = (1..60).map { seed ->
                val q = track(song, seed % 7, noise, seed)
                val r = PhraseGrid.detect(q.starts, PhraseGrid.Envelopes(hop, q.e, q.l))!!
                r to q.truth.all { r.position[it] == 0 }
            }
            val sure = runs.count { PhraseGrid.blocksSure(it.first) }
            val sureWrong = runs.count { PhraseGrid.blocksSure(it.first) && !it.second }
            println("noise $noise: right ${runs.count { it.second }}/60, sure $sure, sure but wrong $sureWrong")
            assertTrue(sure >= minSure)
            assertTrue(sureWrong <= maxWrong)
        }
        println("noise only: sure $flatSure/60")
        assertTrue(flatSure <= 3)
        PhraseGrid.debug = { println(it) }
    }
}
