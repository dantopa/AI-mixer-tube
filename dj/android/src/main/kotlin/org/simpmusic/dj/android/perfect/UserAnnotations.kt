package org.simpmusic.dj.android.perfect

import android.content.Context
import org.simpmusic.dj.planner.MixMarks
import org.simpmusic.dj.timeline.Annotations
import org.simpmusic.dj.timeline.TrackAnnotations

/**
 * The owner's annotations of every track, over the three stores ([UserDownbeats], [UserPhrases], [UserMixMarks]; their
 * SharedPreferences are unchanged). Installed once ([install], from `DjModule`) as the brain's [Annotations.installed],
 * which wires the bar phase, the phrase grid and the planner's mix marks at the same time.
 */
object UserAnnotations : TrackAnnotations {
    override fun downbeatAnchorMs(videoId: String): Long? = UserDownbeats.get(videoId)

    override fun phraseAnchorMs(videoId: String): Long? = UserPhrases.get(videoId)

    override fun mixMarks(videoId: String): MixMarks? = UserMixMarks.get(videoId)

    /** Opens the three stores and installs them in the brain. */
    fun install(context: Context) {
        UserDownbeats.install(context)
        UserPhrases.install(context)
        UserMixMarks.install(context)
        Annotations.install(this)
    }
}
