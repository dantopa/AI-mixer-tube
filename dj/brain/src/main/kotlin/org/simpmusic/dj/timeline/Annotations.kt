package org.simpmusic.dj.timeline

import org.simpmusic.dj.analysis.BarPhase
import org.simpmusic.dj.analysis.PhraseGrid
import org.simpmusic.dj.planner.MixMarks

/** Where a fact about a track comes from. */
enum class Provenance {
    /** As the analyzer stored it. */
    DETECTED,

    /** Rebuilt on read from the stored evidence (`GridRepair`, the `BarPhase` vote, `PhraseGrid`). */
    REFINED,

    /** The owner said so (a tapped 1, a marked phrase, a mix mark). */
    OWNER,
}

/** A value with where it comes from and how much it is trusted (0..1). */
data class Sourced<T>(val value: T, val provenance: Provenance, val confidence: Float)

/**
 * What the owner has told the DJ about a track, by ear: the 1 they tapped, where a phrase starts, their mix marks. The app
 * implements it over its stores (`UserAnnotations`); the brain only reads it.
 */
interface TrackAnnotations {
    /** Source time (ms) of a beat the owner heard as a 1; null when none. */
    fun downbeatAnchorMs(videoId: String): Long?

    /** Source time (ms) where the owner heard a phrase (16-bar block) start; null when none. */
    fun phraseAnchorMs(videoId: String): Long?

    /** The owner's mix marks; null when the track has none. */
    fun mixMarks(videoId: String): MixMarks?

    companion object {
        /** No annotations at all (the default, and every test that sets nothing). */
        val NONE: TrackAnnotations =
            object : TrackAnnotations {
                override fun downbeatAnchorMs(videoId: String): Long? = null

                override fun phraseAnchorMs(videoId: String): Long? = null

                override fun mixMarks(videoId: String): MixMarks? = null
            }
    }
}

/**
 * The ONE install point of the owner's annotations. `BarPhase.anchors`, `PhraseGrid.anchors` and `MixMarks.provider` read
 * [installed] unless something replaced them (tests do), so installing one object wires all three.
 */
object Annotations {
    @Volatile var installed: TrackAnnotations = TrackAnnotations.NONE

    /** Installs [annotations] and points the three hooks back at it (undoing any replacement). */
    fun install(annotations: TrackAnnotations) {
        installed = annotations
        BarPhase.anchors = BarPhase.DEFAULT_ANCHORS
        PhraseGrid.anchors = PhraseGrid.DEFAULT_ANCHORS
        MixMarks.provider = MixMarks.DEFAULT_PROVIDER
    }
}
