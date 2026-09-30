package org.simpmusic.dj.analysis

import org.simpmusic.dj.SyntheticTracks
import org.simpmusic.dj.model.*
import kotlin.test.Test

class ChromaDebug {
    @Test
    fun run() {
        KeyEstimator.debug = { println(it) }
        for (pc in listOf(9)) {
            val r = SyntheticTracks.render(SyntheticTracks.Spec(bpm = 124f, key = MusicalKey(pc, Mode.MINOR)))
            val a = DspTrackAnalyzer().analyze("x", r.audio)
            println("pc=$pc phrases=${a.phraseStartsMs.take(8)} truthDown=${r.truth.downbeatBeatIndices.take(8).map { r.truth.beatTimesMs[it] }} key=${a.key}")
            val beats = a.beatTimesMs!!.value
            println("   downIdx=${a.downbeatBeatIndices!!.value.take(6)} firstBeats=${beats.take(6)}")
        }
    }
}
