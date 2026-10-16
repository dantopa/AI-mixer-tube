package org.simpmusic.dj.analysis

import kotlinx.serialization.json.Json
import org.simpmusic.dj.SyntheticTracks
import org.simpmusic.dj.model.Confident
import org.simpmusic.dj.model.Mode
import org.simpmusic.dj.model.MusicalKey
import org.simpmusic.dj.model.PcmAudio
import org.simpmusic.dj.model.TimeRange
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.model.VocalProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureLayersTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun load(id: String): TrackAnalysis =
        json.decodeFromString(TrackAnalysis.serializer(), javaClass.getResource("/structure/$id.json")!!.readText())

    /** The owner's exports: all made by the device's full analyzer, all with logits, frames and vocals; three with a profile. */
    private val fixtures = listOf("7GUt3ooPJ1k", "Lw2aYkmrQcM", "OZGBG-GUDSo", "cXkiDWOrRZE", "lb5KOCVZB_E")

    @Test
    fun legacyRecordsInferTheirLayersExactlyAsTheSchedulerDecidedBefore() {
        for (id in fixtures) {
            val a = load(id)
            assertTrue(FeatureLayers.isLegacy(a), id)
            val p = FeatureLayers.present(a)
            assertTrue(p.values.all { it == FeatureLayers.LEGACY_VERSION }, id)
            assertTrue(Layer.GRID in p && Layer.DOWNBEAT_LOGITS in p && Layer.ENVELOPES in p && Layer.STRUCTURE in p, "$id: $p")
            // vocals count only with the detector's profile (the old "re-patch when the profile is missing" rule)
            assertEquals(a.vocalProfile != null, Layer.VOCALS in p, id)
            assertEquals(if (a.vocalProfile != null) emptySet() else setOf(Layer.VOCALS), FeatureLayers.missing(a, FeatureLayers.FOR_MIX), id)
            assertTrue(FeatureLayers.missing(a, FeatureLayers.FOR_LIBRARY).isEmpty(), id)
        }
        // both kinds are in the corpus
        assertTrue(fixtures.map(::load).any { it.vocalProfile == null } && fixtures.map(::load).any { it.vocalProfile != null })
    }

    @Test
    fun legacyInferenceOfEachMissingField() {
        val a = load("OZGBG-GUDSo")
        assertEquals(setOf(Layer.DOWNBEAT_LOGITS), FeatureLayers.missing(a.copy(beatDownbeatLogits = null), FeatureLayers.FOR_MIX))
        assertEquals(setOf(Layer.STRUCTURE), FeatureLayers.missing(a.copy(structureFrames = emptyList()), FeatureLayers.FOR_MIX))
        assertEquals(setOf(Layer.VOCALS), FeatureLayers.missing(a.copy(vocals = null), FeatureLayers.FOR_MIX))
        // a quick (DSP-only) record has no neural grid nor logits
        assertEquals(setOf(Layer.GRID, Layer.DOWNBEAT_LOGITS), FeatureLayers.missing(a.copy(analyzerId = "dsp-1", beatDownbeatLogits = null), FeatureLayers.FOR_MIX))
        // nothing stored: everything is missing
        assertEquals(FeatureLayers.FOR_MIX, FeatureLayers.missing(null, FeatureLayers.FOR_MIX))
    }

    @Test
    fun aBumpedVersionIsMissingAndOnlyThatLayer() {
        val a = load("OZGBG-GUDSo")
        val vocalsV2: (Layer) -> Int = { if (it == Layer.VOCALS) 2 else it.version }
        assertEquals(setOf(Layer.VOCALS), FeatureLayers.missing(a, FeatureLayers.FOR_MIX, vocalsV2))
        val stamped = FeatureLayers.stamp(a, setOf(Layer.VOCALS), vocalsV2)
        assertEquals(2, stamped.layerVersions[Layer.VOCALS.key])
        assertTrue(FeatureLayers.missing(stamped, FeatureLayers.FOR_MIX, vocalsV2).isEmpty())
        // and the code going back to v1 does not see a v2 layer as missing
        assertTrue(FeatureLayers.missing(stamped, FeatureLayers.FOR_MIX).isEmpty())
    }

    @Test
    fun stampingOneLayerOnALegacyRecordKeepsTheInferredOthers() {
        val a = load("cXkiDWOrRZE") // no vocal profile: VOCALS missing
        val patched = a.copy(vocalProfile = VocalProfile(480, listOf(-3f), listOf(24)))
        val stamped = FeatureLayers.stamp(patched, setOf(Layer.VOCALS))
        assertFalse(FeatureLayers.isLegacy(stamped))
        assertEquals(Layer.entries.associate { it.key to it.version }, stamped.layerVersions)
        // once stamped, the record says what it has: clearing a field no longer changes the answer
        assertTrue(FeatureLayers.missing(stamped.copy(structureFrames = emptyList()), FeatureLayers.FOR_MIX).isEmpty())
    }

    @Test
    fun stampRoundTripsThroughJsonAndOldJsonStillReads() {
        val a = FeatureLayers.stamp(load("Lw2aYkmrQcM"), setOf(Layer.VOCALS))
        val back = json.decodeFromString(TrackAnalysis.serializer(), json.encodeToString(TrackAnalysis.serializer(), a))
        assertEquals(a.layerVersions, back.layerVersions)
        assertEquals(FeatureLayers.present(a), FeatureLayers.present(back))
        // the fixtures are old JSON without the field: they read with an empty map
        assertTrue(load("Lw2aYkmrQcM").layerVersions.isEmpty())
        // a key written by a newer app is kept by a stamp but ignored by present()
        val future = a.copy(layerVersions = a.layerVersions + ("stems" to 3))
        assertEquals(FeatureLayers.present(a), FeatureLayers.present(future))
        assertEquals(3, FeatureLayers.stamp(future, setOf(Layer.GRID)).layerVersions["stems"])
    }

    @Test
    fun producedAddsExactlyTheGivenLayersWithoutInference() {
        val a = load("OZGBG-GUDSo").copy(layerVersions = mapOf(Layer.ENVELOPES.key to 1, Layer.STRUCTURE.key to 1))
        val p = FeatureLayers.produced(a, setOf(Layer.GRID))
        assertEquals(setOf(Layer.ENVELOPES, Layer.STRUCTURE, Layer.GRID), FeatureLayers.present(p).keys)
        assertEquals(setOf(Layer.DOWNBEAT_LOGITS, Layer.VOCALS), FeatureLayers.missing(p, FeatureLayers.FOR_MIX))
    }

    @Test
    fun theDspAnalyzerStampsEnvelopesAndStructureEvenOnSilence() {
        val dsp = DspTrackAnalyzer()
        val music = dsp.analyze("m", SyntheticTracks.render(SyntheticTracks.Spec(bpm = 124f, key = MusicalKey(0, Mode.MAJOR))).audio)
        assertEquals(setOf(Layer.ENVELOPES, Layer.STRUCTURE), FeatureLayers.present(music).keys)
        val silence = dsp.analyze("s", PcmAudio(FloatArray(22_050 * 5), 22_050))
        assertTrue(silence.structureFrames.isEmpty())
        assertEquals(setOf(Layer.ENVELOPES, Layer.STRUCTURE), FeatureLayers.present(silence).keys, "computed, not to compute again")
        assertEquals(setOf(Layer.GRID, Layer.DOWNBEAT_LOGITS, Layer.VOCALS), FeatureLayers.missing(silence, FeatureLayers.FOR_MIX))
    }

    @Test
    fun describeNamesWhatIsThereAndWhetherItWasInferred() {
        val a = load("cXkiDWOrRZE")
        assertEquals("grid1 downbeatLogits1 envelopes1 structure1 -vocals (inferred)", FeatureLayers.describe(a))
        val v = FeatureLayers.stamp(a.copy(vocals = Confident(listOf(TimeRange(0, 1)), 0.85f), vocalProfile = VocalProfile(480, emptyList(), emptyList())), setOf(Layer.VOCALS))
        assertEquals("grid1 downbeatLogits1 envelopes1 structure1 vocals1", FeatureLayers.describe(v))
    }
}
