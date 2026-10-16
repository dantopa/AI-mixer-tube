package org.simpmusic.dj.android.perfect

import android.content.Context
import org.simpmusic.dj.planner.MixMarks
import org.simpmusic.dj.android.lyrics.LyricVocalsCache
import org.simpmusic.dj.timeline.Annotations
import org.simpmusic.dj.timeline.LyricVocals
import org.simpmusic.dj.timeline.TrackAnnotations

/**
 * The owner's annotations of every track, over the three stores ([UserDownbeats], [UserPhrases], [UserMixMarks]; their
 * SharedPreferences are unchanged), plus the songs' lyric vocals ([LyricVocalsCache]). Installed once ([install], from
 * `DjModule`) as the brain's [Annotations.installed], which wires the bar phase, the phrase grid, the planner's mix marks and
 * the timeline's vocals at the same time.
 */
object UserAnnotations : TrackAnnotations {
    override fun downbeatAnchorMs(videoId: String): Long? = UserDownbeats.get(videoId)

    override fun phraseAnchorMs(videoId: String): Long? = UserPhrases.get(videoId)

    override fun mixMarks(videoId: String): MixMarks? = UserMixMarks.get(videoId)

    /** The track's synced lyrics, validated (filled by `DjLyricsSource` for the playing and the next track). */
    override fun lyricVocals(videoId: String): LyricVocals? = LyricVocalsCache.get(videoId)

    /** Opens the three stores and installs them in the brain. */
    fun install(context: Context) {
        UserDownbeats.install(context)
        UserPhrases.install(context)
        UserMixMarks.install(context)
        Annotations.install(this)
    }
}
