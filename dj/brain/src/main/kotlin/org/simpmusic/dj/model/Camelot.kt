package org.simpmusic.dj.model

import kotlin.math.abs

/** Camelot wheel helpers for harmonic mixing. */
object Camelot {
    // Camelot number for each pitch class, major then minor (B ring = major, A ring = minor).
    private val MAJOR_NUMBER = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1) // C, C#, D ... B
    private val MINOR_NUMBER = intArrayOf(5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10) // Cm, C#m ... Bm

    fun number(key: MusicalKey): Int =
        if (key.mode == Mode.MAJOR) MAJOR_NUMBER[key.pitchClass] else MINOR_NUMBER[key.pitchClass]

    fun code(key: MusicalKey): String = "${number(key)}${if (key.mode == Mode.MAJOR) 'B' else 'A'}"

    /**
     * Steps on the wheel: 0 identical, 1 = compatible (adjacent number, or the relative major/minor
     * on the same number), 2+ = increasingly clashing. Adjacent numbers with different rings count 2.
     */
    fun distance(a: MusicalKey, b: MusicalKey): Int {
        val na = number(a)
        val nb = number(b)
        val raw = abs(na - nb)
        val ring = minOf(raw, 12 - raw)
        val sameRing = a.mode == b.mode
        return if (sameRing) ring else ring + 1
    }

    /** Pitch class distance (0..6) of the shortest semitone shift that turns [from] into a key harmonically close to [to]. */
    fun shiftToward(from: MusicalKey, to: MusicalKey): Int {
        // Shift `from` by s semitones (same mode); pick the smallest |s| <= 3 minimising the wheel distance.
        var best = 0
        var bestDist = distance(from, to)
        for (s in listOf(1, -1, 2, -2, 3, -3)) {
            val shifted = MusicalKey(((from.pitchClass + s) % 12 + 12) % 12, from.mode)
            val d = distance(shifted, to)
            if (d < bestDist) {
                best = s
                bestDist = d
            }
        }
        return best
    }
}
