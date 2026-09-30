package org.simpmusic.dj.analysis

import org.simpmusic.dj.SyntheticTracks
import org.simpmusic.dj.model.*
import kotlin.test.Test

class RhythmExperiment {
    @Test
    fun run() {
        val an = DspTrackAnalyzer()
        for (bpm in listOf(70f, 90f, 100f, 118f, 124f, 128f, 140f, 150f, 174f)) {
            val r = SyntheticTracks.render(SyntheticTracks.Spec(bpm = bpm, key = MusicalKey(0, Mode.MAJOR)))
            val a = an.analyze(r.spec.videoId, r.audio)
            println("bpm $bpm: ${Eval(a, r)}")
        }
        for (mode in Mode.values()) for (pc in 0 until 12) {
            val r = SyntheticTracks.render(SyntheticTracks.Spec(bpm = 124f, key = MusicalKey(pc, mode)))
            val a = an.analyze(r.spec.videoId, r.audio)
            val e = Eval(a, r)
            println("key $mode $pc: est=${a.key?.value} c=${a.key?.confidence?.let { "%.2f".format(it) }} ok=${e.keyCorrect}")
        }
        val r3 = SyntheticTracks.render(SyntheticTracks.Spec(bpm = 120f, key = MusicalKey(5, Mode.MAJOR), beatsPerBar = 3))
        println("3/4: ${Eval(an.analyze(r3.spec.videoId, r3.audio), r3)}")
    }
}
