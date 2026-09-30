package org.simpmusic.dj.android.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.simpmusic.dj.model.DjSettings
import org.simpmusic.dj.model.EnergyArc

private val Context.djDataStore: DataStore<Preferences> by preferencesDataStore(name = "dj_settings")

/**
 * Preferences-DataStore backed [DjSettings] (plus the device-level knobs that are not part of the
 * brain's contract). Deliberately its own DataStore file: core's DataStoreManager stays untouched.
 */
class DjSettingsRepository(
    private val store: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.applicationContext.djDataStore)

    /** The settings the planner/engine consume. Values are clamped on read, so a corrupt file cannot crash playback. */
    val settings: Flow<DjSettings> = store.data.map { it.toSettings() }

    /** Whether the playing / next track's analysis may fetch a stream on a metered connection (default ON). The library warm-up never does. */
    val analyzeOnMetered: Flow<Boolean> = store.data.map { it[ANALYZE_ON_METERED] ?: DEFAULT_ANALYZE_ON_METERED }

    suspend fun update(transform: (DjSettings) -> DjSettings) {
        store.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[ENABLED] = next.enabled
            prefs[OVERLAP_BARS] = next.overlapBars
            prefs[MAX_TEMPO_BEND] = next.maxTempoBend
            prefs[ALLOW_KEY_SHIFT] = next.allowKeyShift
            prefs[MAX_PITCH_SHIFT] = next.maxPitchShift
            prefs[BASS_SWAP] = next.bassSwap
            prefs[MIN_CONFIDENCE] = next.minConfidence
            prefs[FALLBACK_CROSSFADE_MS] = next.fallbackCrossfadeMs
            prefs[AUTO_DJ] = next.autoDj
            prefs[AUTO_DJ_ARC] = next.autoDjArc.name
        }
    }

    suspend fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }

    suspend fun setOverlapBars(bars: Int) = update { it.copy(overlapBars = bars) }

    suspend fun setMaxTempoBend(fraction: Float) = update { it.copy(maxTempoBend = fraction) }

    suspend fun setAllowKeyShift(allow: Boolean) = update { it.copy(allowKeyShift = allow) }

    suspend fun setBassSwap(enabled: Boolean) = update { it.copy(bassSwap = enabled) }

    suspend fun setAutoDj(enabled: Boolean) = update { it.copy(autoDj = enabled) }

    suspend fun setAutoDjArc(arc: EnergyArc) = update { it.copy(autoDjArc = arc) }

    /** Whether the user asked for the background library analysis to run (survives a restart; not part of [DjSettings]). */
    val libraryAnalysis: Flow<Boolean> = store.data.map { it[LIBRARY_ANALYSIS] ?: false }

    suspend fun setLibraryAnalysis(on: Boolean) {
        store.edit { it[LIBRARY_ANALYSIS] = on }
    }

    suspend fun setAnalyzeOnMetered(allow: Boolean) {
        store.edit { it[ANALYZE_ON_METERED] = allow }
    }

    private fun Preferences.toSettings(): DjSettings {
        val d = DjSettings()
        return DjSettings(
            enabled = this[ENABLED] ?: d.enabled,
            overlapBars = (this[OVERLAP_BARS] ?: d.overlapBars).coerceIn(MIN_OVERLAP_BARS, MAX_OVERLAP_BARS),
            maxTempoBend = (this[MAX_TEMPO_BEND] ?: d.maxTempoBend).coerceIn(0f, MAX_TEMPO_BEND_LIMIT),
            allowKeyShift = this[ALLOW_KEY_SHIFT] ?: d.allowKeyShift,
            maxPitchShift = (this[MAX_PITCH_SHIFT] ?: d.maxPitchShift).coerceIn(0, 6),
            bassSwap = this[BASS_SWAP] ?: d.bassSwap,
            minConfidence = (this[MIN_CONFIDENCE] ?: d.minConfidence).coerceIn(0f, 1f),
            fallbackCrossfadeMs = (this[FALLBACK_CROSSFADE_MS] ?: d.fallbackCrossfadeMs).coerceIn(1000L, 30_000L),
            autoDj = this[AUTO_DJ] ?: d.autoDj,
            autoDjArc = this[AUTO_DJ_ARC]?.let { name -> EnergyArc.entries.firstOrNull { it.name == name } } ?: d.autoDjArc,
        )
    }

    companion object {
        /** On by default: one track is about 4 MB, and without it the next track can never be analysed in time on mobile data. */
        const val DEFAULT_ANALYZE_ON_METERED = true

        const val MIN_OVERLAP_BARS = 2
        const val MAX_OVERLAP_BARS = 32
        const val MAX_TEMPO_BEND_LIMIT = 0.16f

        private val ENABLED = booleanPreferencesKey("dj_enabled")
        private val OVERLAP_BARS = intPreferencesKey("dj_overlap_bars")
        private val MAX_TEMPO_BEND = floatPreferencesKey("dj_max_tempo_bend")
        private val ALLOW_KEY_SHIFT = booleanPreferencesKey("dj_allow_key_shift")
        private val MAX_PITCH_SHIFT = intPreferencesKey("dj_max_pitch_shift")
        private val BASS_SWAP = booleanPreferencesKey("dj_bass_swap")
        private val MIN_CONFIDENCE = floatPreferencesKey("dj_min_confidence")
        private val FALLBACK_CROSSFADE_MS = longPreferencesKey("dj_fallback_crossfade_ms")
        private val AUTO_DJ = booleanPreferencesKey("dj_auto_dj")
        private val AUTO_DJ_ARC = stringPreferencesKey("dj_auto_dj_arc")
        private val LIBRARY_ANALYSIS = booleanPreferencesKey("dj_library_analysis")
        private val ANALYZE_ON_METERED = booleanPreferencesKey("dj_analyze_on_metered")
    }
}
