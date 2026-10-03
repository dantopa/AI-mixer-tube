package org.simpmusic.dj.android.perfect

import android.content.Context
import android.content.SharedPreferences
import org.simpmusic.dj.analysis.PhraseGrid
import org.simpmusic.dj.android.log.DjLog

/**
 * The owner's "a phrase starts here": one source time per track (SharedPreferences `dj_user_phrases`), kept forever.
 * [PhraseGrid] snaps it to the nearest bar line and forces a 16-bar block line there. Marking a phrase is easy where
 * marking a 1 is not: the tolerance is half a BAR (about a second), not half a beat.
 */
object UserPhrases {
    @Volatile private var prefs: SharedPreferences? = null

    fun install(context: Context) {
        prefs = context.getSharedPreferences("dj_user_phrases", Context.MODE_PRIVATE)
        PhraseGrid.anchors = ::get
        DjLog.i("perfect", "marked phrases: ${prefs?.all?.size ?: 0} tracks")
    }

    fun get(videoId: String): Long? = prefs?.let { p -> if (p.contains(videoId)) p.getLong(videoId, 0L) else null }

    fun set(videoId: String, timeMs: Long, why: String): Long {
        prefs?.edit()?.putLong(videoId, timeMs)?.apply()
        DjLog.i("perfect", "a phrase of $videoId starts at $timeMs ms ($why)")
        return timeMs
    }

    fun clear(videoId: String) {
        prefs?.edit()?.remove(videoId)?.apply()
        DjLog.i("perfect", "the phrase mark of $videoId cleared (back to detection)")
    }

    fun all(): Map<String, Long> = prefs?.all?.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }?.toMap() ?: emptyMap()
}
