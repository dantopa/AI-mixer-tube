package org.simpmusic.dj.recommend

import kotlin.math.abs

/**
 * Orders a radio's next few tracks the way a DJ orders a set: tracks that can be beat-matched follow each other, and the
 * unavoidable tempo jumps are as few as possible. YouTube's radio picks the style; this only picks the order among what it
 * queued.
 *
 * Why a path and not "the best next track": choosing greedily only pushes an off-tempo track one slot down, so it comes
 * back as the next track at once, and the set bounces between tempos. On the owner's build-ba radio (cumbia at 97 bpm with
 * two tracks at 122 and 125) the greedy look-ahead played 97 -> 97 -> 125 -> 97 -> 122 -> 108: four plain crossfades where
 * two were enough (97 -> 97 -> 97 -> 125 -> 122 -> 108). A shortest path over the window (Held-Karp, a window is at most
 * [MAX_TRACKS]) finds the order with the fewest jumps, the off-tempo tracks grouped at the end of the window, where the
 * radio's next tracks may match them.
 *
 * Costs per step: within the bend a step costs its tempo gap (closer is better, nearly free); beyond it a [JUMP]. Each
 * track also pays [DISPLACEMENT] per slot it moves from YouTube's order, so equal tempos keep the radio's order. Tempos are
 * compared half/double aware (85 and 170 bpm mix).
 */
object SetOrder {
    /** One upcoming track: its id and its tempo (bpm of its refined grid). */
    class Track(val id: String, val bpm: Double)

    /** The best order of the window ([ids], the first is the track to play next), how many tempo jumps it holds, its cost. */
    class Plan(val ids: List<String>, val jumps: Int, val cost: Double) {
        override fun toString() = "${ids.joinToString(" > ")} ($jumps jump${if (jumps == 1) "" else "s"})"
    }

    const val MAX_TRACKS = 8
    const val JUMP = 1.0
    const val DISPLACEMENT = 0.04

    /** Tempo gap of two tracks as a fraction, half/double aware: 0.03 = 3 %. */
    fun gap(a: Double, b: Double): Double {
        if (a <= 0.0 || b <= 0.0) return Double.MAX_VALUE
        val r = b / a
        return minOf(abs(r - 1.0), abs(r * 2.0 - 1.0), abs(r / 2.0 - 1.0))
    }

    fun isJump(a: Double, b: Double, bend: Double): Boolean = gap(a, b) > bend

    /**
     * The best order of [window] (in the radio's order) after a current track at [currentBpm], within a tempo [bend] (the
     * planner's: 0.06 in simple mode). With [first] set, that track is forced into the first slot (to compare candidates for
     * the next slot). Null for an empty window or one larger than [MAX_TRACKS].
     */
    fun best(currentBpm: Double, window: List<Track>, bend: Double, first: String? = null): Plan? {
        val n = window.size
        if (n == 0 || n > MAX_TRACKS) return null
        val f = first?.let { id -> window.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return null }
        fun step(a: Double, b: Double): Double = gap(a, b).let { g -> if (g <= bend) g else JUMP + g }
        fun place(i: Int, slot: Int): Double = DISPLACEMENT * abs(slot - i)
        val full = (1 shl n) - 1
        // cost[mask][last]: best cost of a path that has placed the tracks in mask (in slots 0..|mask|-1), ending on last
        val cost = Array(1 shl n) { DoubleArray(n) { Double.MAX_VALUE } }
        val from = Array(1 shl n) { IntArray(n) { -1 } }
        for (i in 0 until n) {
            if (f != null && i != f) continue
            cost[1 shl i][i] = step(currentBpm, window[i].bpm) + place(i, 0)
        }
        for (mask in 1..full) {
            val slot = Integer.bitCount(mask) // the slot the next track would take
            for (last in 0 until n) {
                val c = cost[mask][last]
                if (c == Double.MAX_VALUE) continue
                for (j in 0 until n) {
                    if (mask and (1 shl j) != 0) continue
                    val m2 = mask or (1 shl j)
                    val c2 = c + step(window[last].bpm, window[j].bpm) + place(j, slot)
                    if (c2 < cost[m2][j]) { cost[m2][j] = c2; from[m2][j] = last }
                }
            }
        }
        var end = -1
        for (i in 0 until n) if (cost[full][i] < (if (end < 0) Double.MAX_VALUE else cost[full][end])) end = i
        if (end < 0) return null
        val order = ArrayList<Int>(n)
        var mask = full
        var cur = end
        while (cur >= 0) {
            order += cur
            val prev = from[mask][cur]
            mask = mask and (1 shl cur).inv()
            cur = prev
        }
        order.reverse()
        var jumps = if (isJump(currentBpm, window[order[0]].bpm, bend)) 1 else 0
        for (k in 1 until order.size) if (isJump(window[order[k - 1]].bpm, window[order[k]].bpm, bend)) jumps++
        return Plan(order.map { window[it].id }, jumps, cost[full][end])
    }

    /** The jumps of the radio's own order, for the log. */
    fun jumpsInOrder(currentBpm: Double, window: List<Track>, bend: Double): Int {
        var prev = currentBpm
        var jumps = 0
        for (t in window) { if (isJump(prev, t.bpm, bend)) jumps++; prev = t.bpm }
        return jumps
    }
}
