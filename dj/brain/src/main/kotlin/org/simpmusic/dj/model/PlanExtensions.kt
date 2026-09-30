package org.simpmusic.dj.model

import kotlin.math.max

/** Additive helpers over the contract (no contract type changed). */

/** Time (wall clock, relative to T0) after which every lane of [this] plan is constant. */
val TransitionPlan.settleMs: Long
    get() {
        var m = max(0L, overlapMs)
        for (d in listOf(outgoing, incoming)) {
            for (c in listOf(d.rate, d.pitchSemitones, d.volume, d.lowCutHz, d.highCutHz)) m = max(m, c.endMs)
        }
        return m
    }

/**
 * First wall-clock time at which the lane is 0 for good (i.e. the deck is inaudible from then on), or null when
 * the lane never stays at 0.
 */
fun ParamCurve.silentFromMs(epsilon: Float = 1e-5f): Long? {
    if (keys.last().value > epsilon) return null
    var j = keys.lastIndex
    while (j >= 0 && keys[j].value <= epsilon) j--
    return if (j < 0) Long.MIN_VALUE else keys[j + 1].tMs
}
