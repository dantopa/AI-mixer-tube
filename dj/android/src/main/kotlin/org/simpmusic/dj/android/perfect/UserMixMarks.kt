package org.simpmusic.dj.android.perfect

import android.content.Context
import android.content.SharedPreferences
import org.simpmusic.dj.android.log.DjLog
import org.simpmusic.dj.planner.MixMarks

/** Which of the owner's three mix marks (see [MixMarks]). */
enum class MixMark(val key: String) { EXIT("exit"), ENTRY("entry"), SOLO("solo") }

/**
 * The owner's mix marks, three source times per track at most (SharedPreferences `dj_user_mix_marks`, keys `<id>|exit`,
 * `<id>|entry`, `<id>|solo`), kept forever and handed to the planner through [MixMarks.provider]. Each mark is a label
 * too: the export carries them (`mixmarks.tsv`) for measuring and, later, learning.
 */
object UserMixMarks {
    @Volatile private var prefs: SharedPreferences? = null

    fun install(context: Context) {
        prefs = context.getSharedPreferences("dj_user_mix_marks", Context.MODE_PRIVATE)
        MixMarks.provider = ::get
        DjLog.i("perfect", "mix marks: ${all().size} tracks")
    }

    private fun read(p: SharedPreferences, id: String, m: MixMark): Long? = "$id|${m.key}".let { if (p.contains(it)) p.getLong(it, 0L) else null }

    fun get(videoId: String): MixMarks? {
        val p = prefs ?: return null
        val m = MixMarks(read(p, videoId, MixMark.EXIT), read(p, videoId, MixMark.ENTRY), read(p, videoId, MixMark.SOLO))
        return m.takeIf { it.exitMs != null || it.entryMs != null || it.soloMs != null }
    }

    fun set(videoId: String, mark: MixMark, timeMs: Long, why: String): Long {
        prefs?.edit()?.putLong("$videoId|${mark.key}", timeMs)?.apply()
        DjLog.i("perfect", "mix mark ${mark.key} of $videoId at $timeMs ms ($why)")
        return timeMs
    }

    fun clear(videoId: String, mark: MixMark) {
        prefs?.edit()?.remove("$videoId|${mark.key}")?.apply()
        DjLog.i("perfect", "mix mark ${mark.key} of $videoId cleared")
    }

    /** videoId -> its marks, for the export. */
    fun all(): Map<String, MixMarks> =
        (prefs?.all?.keys ?: emptySet()).mapNotNull { it.substringBefore('|', "").takeIf { id -> id.isNotEmpty() } }.toSet()
            .mapNotNull { id -> get(id)?.let { id to it } }.toMap()
}
