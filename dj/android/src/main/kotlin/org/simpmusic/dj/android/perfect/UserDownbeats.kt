package org.simpmusic.dj.android.perfect

import android.content.Context
import android.content.SharedPreferences
import org.simpmusic.dj.analysis.BarPhase
import org.simpmusic.dj.android.log.DjLog

/**
 * The owner's "tap the 1": one source time per track where they heard a 1, kept forever (SharedPreferences
 * `dj_user_downbeats`). [BarPhase] snaps it to the nearest beat and forces the bar lattice through it, so the owner settles
 * the 1-or-3 question for good on tracks where the network's evidence is ambiguous (cumbia, dembow).
 *
 * Every tap is also a label: a corpus of tapped tracks is what a per-genre "Perfect" certification needs.
 */
object UserDownbeats {
    /**
     * The tap lands this long after the reported position of the beat the owner heard: output latency (the speaker
     * plays what the player reported a little earlier) minus the anticipation of a tap on the beat. BarPhase snaps to
     * the nearest beat within half a beat, so the exact value matters only beyond a quarter beat (~160 ms at 92 bpm).
     */
    const val TAP_DELAY_MS = 120L

    @Volatile private var prefs: SharedPreferences? = null

    fun install(context: Context) {
        prefs = context.getSharedPreferences("dj_user_downbeats", Context.MODE_PRIVATE)
        BarPhase.anchors = ::get
        DjLog.i("perfect", "tapped 1s: ${prefs?.all?.size ?: 0} tracks")
    }

    fun get(videoId: String): Long? = prefs?.let { p -> if (p.contains(videoId)) p.getLong(videoId, 0L) else null }

    /** Records a tap made while [videoId] played at reported position [positionMs]; returns the stored source time. */
    fun tap(videoId: String, positionMs: Long): Long {
        val t = (positionMs - TAP_DELAY_MS).coerceAtLeast(0L)
        prefs?.edit()?.putLong(videoId, t)?.apply()
        DjLog.i("perfect", "tapped the 1 of $videoId at $t ms (position $positionMs ms)")
        return t
    }

    fun clear(videoId: String) {
        prefs?.edit()?.remove(videoId)?.apply()
    }

    /** All taps (videoId -> ms), for the export. */
    fun all(): Map<String, Long> = prefs?.all?.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }?.toMap() ?: emptyMap()
}
