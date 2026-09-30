package org.simpmusic.dj

import org.simpmusic.dj.model.*
import org.simpmusic.dj.recommend.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecommenderTest {
    private fun track(id: String, bpm: Float, key: MusicalKey, introE: Float = 0.5f, outroE: Float = 0.5f, timbre: List<Float> = List(13) { 0.1f * it }): TrackAnalysis {
        val n = 300
        val energy = List(n) { i -> if (i < n / 5) introE else if (i >= n - n / 5) outroE else 0.8f }
        return TrackAnalysis(
            videoId = id, analyzerId = "fake", analyzedAtEpochMs = 0, durationMs = 240_000,
            bpm = Confident(bpm, 0.9f), beatTimesMs = null, downbeatBeatIndices = null,
            key = Confident(key, 0.9f), energyHopMs = 800, energy = energy, lowBandEnergy = energy,
            sections = null, vocals = null, loudnessDb = -14f, timbre = timbre,
        )
    }

    private val settings = DjSettings(enabled = true)
    private val aMinor = MusicalKey(9, Mode.MINOR) // 8A

    @Test
    fun prefersCompatibleKeyAndTempo() {
        val cur = track("cur", 124f, aMinor)
        val good = track("good", 126f, aMinor) // same key, +1.6 %
        val badKey = track("badKey", 125f, MusicalKey(1, Mode.MAJOR)) // 3B: far away
        val badTempo = track("badTempo", 150f, aMinor)
        val halfTime = track("half", 62f, MusicalKey(4, Mode.MINOR)) // 9A at half-time of 124 still counts as a match
        val ranked = HeuristicRecommender().recommend(cur, listOf(badKey, badTempo, good, halfTime), settings)
        assertEquals("good", ranked.first().videoId)
        assertTrue(ranked.indexOfFirst { it.videoId == "half" } < ranked.indexOfFirst { it.videoId == "badTempo" })
        assertTrue(ranked.first().breakdown.getValue("harmony") > 0.8f)
    }

    @Test
    fun skipsHistoryAndSelf() {
        val cur = track("cur", 124f, aMinor)
        val other = track("o", 124f, aMinor)
        val ranked = HeuristicRecommender().withContext(RecommendContext(history = setOf("o"))).recommend(cur, listOf(cur, other), settings)
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun energyTrendShiftsPreference() {
        val cur = track("cur", 124f, aMinor, outroE = 0.5f)
        val hot = track("hot", 124f, aMinor, introE = 0.75f)
        val cool = track("cool", 124f, aMinor, introE = 0.3f)
        val up = HeuristicRecommender().withContext(RecommendContext(energyTrend = 1f)).recommend(cur, listOf(cool, hot), settings)
        val down = HeuristicRecommender().withContext(RecommendContext(energyTrend = -1f)).recommend(cur, listOf(cool, hot), settings)
        assertEquals("hot", up.first().videoId)
        assertEquals("cool", down.first().videoId)
    }

    @Test
    fun setBuilderChainsWithoutRepeats() {
        val keys = listOf(MusicalKey(9, Mode.MINOR), MusicalKey(4, Mode.MINOR), MusicalKey(11, Mode.MINOR), MusicalKey(2, Mode.MINOR))
        val pool = (1..12).map { track("t$it", 120f + it, keys[it % keys.size]) }
        val set = SetBuilder(HeuristicRecommender()).build(track("seed", 122f, aMinor), pool, 6, settings)
        assertEquals(6, set.tracks.size)
        assertEquals(6, set.tracks.map { it.videoId }.toSet().size)
    }
}
