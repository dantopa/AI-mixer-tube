package org.simpmusic.dj.planner

/**
 * The owner's own mix points for one track, marked by ear while it plays (2026-10-10). The owner's words: "start of the
 * mix, where a crossfade with the other track starts on a beat, and end of the mix, from where only track 2 sounds".
 * Marked per track, not per pair, so any two marked tracks mix with each other:
 *  - [exitMs]: a good place to start leaving THIS track ("Lo Dejaría Todo": its instrumental after the chorus, 1:33);
 *  - [entryMs]: where this track starts when another one mixes into it ("A Prueba de Balas": its instrumental, 1:20);
 *  - [soloMs]: from here this track plays alone, the end of the overlap ("A Prueba de Balas": its voice, 1:31).
 * All three are source times snapped to a bar line by whoever stores them.
 */
data class MixMarks(val exitMs: Long? = null, val entryMs: Long? = null, val soloMs: Long? = null) {
    companion object {
        /** Start of a plan's reason when the owner's marks placed it. */
        const val TAG = "OWNER MARKS: "

        /** Where the marks come from (the app installs its store here; tests set their own). */
        @Volatile var provider: (String) -> MixMarks? = { null }

        fun of(videoId: String): MixMarks? = provider(videoId)
    }
}
