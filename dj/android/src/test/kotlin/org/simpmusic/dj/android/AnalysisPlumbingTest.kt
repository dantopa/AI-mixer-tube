package org.simpmusic.dj.android

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.simpmusic.dj.android.library.CandidateSelection
import org.simpmusic.dj.android.library.CandidateSource
import org.simpmusic.dj.android.settings.DjSettingsRepository
import org.simpmusic.dj.android.store.AnalysisStore
import org.simpmusic.dj.ml.BeatGrid
import org.simpmusic.dj.ml.BeatProvider
import org.simpmusic.dj.ml.CompositeAnalyzer
import org.simpmusic.dj.model.DjSettings
import org.simpmusic.dj.model.EnergyArc
import org.simpmusic.dj.model.PcmAudio
import org.simpmusic.dj.model.TrackAnalyzer
import java.io.File
import java.nio.file.Files

class AnalysisPlumbingTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("djplumb").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /**
     * The bug behind "waiting analysis" forever: when the neural beat grid is missing (model not loaded, fewer than four
     * beats) the composite returned the DSP result stamped with the DSP analyzer's id. The store deletes a row whose id
     * differs from the analyzer's, so the analysis never stuck and was redone on every request.
     */
    @Test
    fun aCompositeAnalysisWithoutABeatGridStillCarriesTheCompositeIdAndSticksInTheStore() {
        val dsp =
            object : TrackAnalyzer {
                override val id = "dsp-1"

                override fun analyze(videoId: String, audio: PcmAudio) = analysis(videoId, analyzerId = "dsp-1")
            }
        val noGrid =
            object : BeatProvider {
                override val id = "beat-this"

                override fun grid(audio: PcmAudio): BeatGrid? = null
            }
        val composite = CompositeAnalyzer(dsp, noGrid)
        val result = composite.analyze("v", PcmAudio(FloatArray(22_050), 22_050))
        assertEquals("dsp-1+beat-this", composite.id)
        assertEquals(composite.id, result.analyzerId)

        val store = AnalysisStore(dir)
        store.put(result)
        assertTrue("the store accepts it for this analyzer", store.has("v", composite.id))
        assertNotNull(store.get("v", composite.id))
    }

    @Test
    fun aBeatProviderThatThrowsAnExceptionKeepsTheDspResultToo() {
        val dsp =
            object : TrackAnalyzer {
                override val id = "dsp-1"

                override fun analyze(videoId: String, audio: PcmAudio) = analysis(videoId, analyzerId = "dsp-1")
            }
        val broken =
            object : BeatProvider {
                override val id = "beat-this"

                override fun grid(audio: PcmAudio): BeatGrid? = throw IllegalStateException("model missing")
            }
        assertEquals("dsp-1+beat-this", CompositeAnalyzer(dsp, broken).analyze("v", PcmAudio(FloatArray(10), 22_050)).analyzerId)
    }

    @Test
    fun candidateRulesAreExactlyTheBriefs() {
        val ok = libTrack("a")
        assertTrue(CandidateSelection.isEligible(ok))
        assertFalse("known video", CandidateSelection.isEligible(libTrack("v", videoType = UGC)))
        assertFalse("podcast", CandidateSelection.isEligible(libTrack("p", videoType = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE")))
        assertTrue("an unknown type is not a video", CandidateSelection.isEligible(libTrack("u", videoType = null)))
        assertTrue("an invented legacy label is not a video either", CandidateSelection.isEligible(libTrack("legacy", videoType = "Song")))
        assertFalse(CandidateSelection.isEligible(libTrack("s", durationSeconds = CandidateSelection.MIN_DURATION_SECONDS - 1)))
        assertFalse(CandidateSelection.isEligible(libTrack("l", durationSeconds = CandidateSelection.MAX_DURATION_SECONDS + 1)))
        assertTrue("duration unknown (0) passes", CandidateSelection.isEligible(libTrack("z", durationSeconds = 0)))
    }

    @Test
    fun selectionOrdersByPriorityDedupesAndCaps() {
        val raw =
            listOf(
                cand("m", CandidateSource.MOST_PLAYED),
                cand("r", CandidateSource.RECENT),
                cand("d", CandidateSource.DOWNLOADED, downloaded = true),
                cand("l", CandidateSource.LIKED),
                cand("d", CandidateSource.LIKED, downloaded = true),
            )
        assertEquals(listOf("l", "d", "m", "r"), CandidateSelection.select(raw).map { it.track.videoId })
        assertEquals(listOf("l", "d"), CandidateSelection.select(raw, cap = 2).map { it.track.videoId })
    }

    // ---- settings: additive and backward compatible ----

    private fun repo() = DjSettingsRepository(PreferenceDataStoreFactory.create(produceFile = { File(dir, "dj.preferences_pb") }))

    @Test
    fun aSettingsFileFromBeforeAutoDjDecodesWithSafeDefaults() =
        runBlocking {
            val store = PreferenceDataStoreFactory.create(produceFile = { File(dir, "old.preferences_pb") })
            store.edit {
                // exactly what the previous build wrote: no auto-dj keys, no analyze-on-metered key
                it[booleanPreferencesKey("dj_enabled")] = true
            }
            val repo = DjSettingsRepository(store)
            val s = repo.settings.first()
            assertTrue(s.enabled)
            assertFalse("Auto DJ is off by default", s.autoDj)
            assertEquals(EnergyArc.STEADY, s.autoDjArc)
            assertTrue("simple mode is the default", repo.simpleMode.first())
            assertFalse("simple mode: no echo-out by default", s.allowEchoOut)
            assertTrue("analyze on mobile data defaults to ON", repo.analyzeOnMetered.first())
            assertFalse("the library analysis is not running until asked", repo.libraryAnalysis.first())
            assertFalse("the bar counter is hidden until asked", repo.showBarCounter.first())
        }

    @Test
    fun aFreshInstallHasTheDjOnAndAnExplicitOffStaysOff() =
        runBlocking {
            assertTrue("AI DJ is on for a fresh install", repo().settings.first().enabled)
            val store = PreferenceDataStoreFactory.create(produceFile = { File(dir, "off.preferences_pb") })
            store.edit { it[booleanPreferencesKey("dj_enabled")] = false }
            assertFalse("a user who turned it off keeps it off", DjSettingsRepository(store).settings.first().enabled)
        }

    @Test
    fun autoDjAndItsArcAreStoredAndTheOtherFieldsSurvive() =
        runBlocking {
            val repo = repo()
            repo.setSimpleMode(false) // this test is about what is stored; simple mode only changes what is read
            repo.setEnabled(true)
            repo.setAutoDj(true)
            repo.setAutoDjArc(EnergyArc.WAVE)
            repo.setBassSwap(false)
            repo.setAnalyzeOnMetered(false)
            repo.setLibraryAnalysis(true)
            val s = repo.settings.first()
            assertEquals(DjSettings(enabled = true, autoDj = true, autoDjArc = EnergyArc.WAVE, bassSwap = false), s)
            assertFalse(repo.analyzeOnMetered.first())
            assertTrue(repo.libraryAnalysis.first())
        }

    @Test
    fun theSerialisedFormOfDjSettingsAcceptsOldJson() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(DjSettings.serializer(), """{"enabled":true,"overlapBars":8}""")
        assertEquals(DjSettings(enabled = true), old)
    }
}
