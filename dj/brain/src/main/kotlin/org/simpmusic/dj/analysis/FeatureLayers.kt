package org.simpmusic.dj.analysis

import org.simpmusic.dj.model.TrackAnalysis

/**
 * One independently computed part of a [TrackAnalysis], with the version of the code that produces it. Bump a [version]
 * when its detector changes: stored records then miss that layer alone, and the scheduler re-computes only it.
 */
enum class Layer(val key: String, val version: Int) {
    /** Beats, downbeats, beats per bar, phrase starts and bpm from the NEURAL tracker (Beat This!). Needs the full analyzer. */
    GRID("grid", 1),

    /** The tracker's per-beat downbeat log-odds ([TrackAnalysis.beatDownbeatLogits]), produced with the grid. */
    DOWNBEAT_LOGITS("downbeatLogits", 1),

    /** The DSP pass: energy, low and high band, loudness, timbre, key, sections. */
    ENVELOPES("envelopes", 1),

    /** [TrackAnalysis.structureFrames] (chroma + timbre per frame), from the DSP pass. */
    STRUCTURE("structure", 1),

    /** [TrackAnalysis.vocals] and [TrackAnalysis.vocalProfile], from the vocal detector. */
    VOCALS("vocals", 1),
    ;

    companion object {
        fun ofKey(key: String): Layer? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Which layers an analysis carries, which a consumer still needs, and the stamp an analyzer leaves on what it computed.
 *
 * A record with an empty [TrackAnalysis.layerVersions] predates stamping; its layers are inferred from the fields exactly as
 * the scheduler used to decide "is it stale?", each at [LEGACY_VERSION].
 */
object FeatureLayers {
    /** The version an inferred (legacy) layer counts as. */
    const val LEGACY_VERSION = 1

    /** What a mix of the playing and the next track wants: everything. */
    val FOR_MIX: Set<Layer> = Layer.entries.toSet()

    /** What ranking the library needs: the quick DSP pass. */
    val FOR_LIBRARY: Set<Layer> = setOf(Layer.ENVELOPES)

    /** The layers [a] carries, with their versions. Unknown keys (a newer app wrote them) are ignored. */
    fun present(a: TrackAnalysis): Map<Layer, Int> {
        if (a.layerVersions.isNotEmpty()) {
            val out = LinkedHashMap<Layer, Int>()
            for ((k, v) in a.layerVersions) Layer.ofKey(k)?.let { out[it] = v }
            return out
        }
        return inferred(a).associateWith { LEGACY_VERSION }
    }

    /** True when [a] carries no stamp, so [present] is an inference from its fields. */
    fun isLegacy(a: TrackAnalysis): Boolean = a.layerVersions.isEmpty()

    /**
     * The [required] layers [a] lacks or carries at a version older than [current] (null: nothing is stored, so all of them).
     * [current] is the code's version of each layer; tests pass a bumped one.
     */
    fun missing(a: TrackAnalysis?, required: Set<Layer>, current: (Layer) -> Int = { it.version }): Set<Layer> {
        if (a == null) return required
        val have = present(a)
        return required.filterTo(LinkedHashSet()) { (have[it] ?: Int.MIN_VALUE) < current(it) }
    }

    /**
     * [a] with [layers] recorded at their [current] versions. The layers it already carried are kept (a legacy record's are
     * materialised from the inference first), so stamping one layer on an old record does not make the others look absent.
     */
    fun stamp(a: TrackAnalysis, layers: Set<Layer>, current: (Layer) -> Int = { it.version }): TrackAnalysis {
        val merged = LinkedHashMap<String, Int>()
        for ((layer, v) in present(a)) merged[layer.key] = v
        if (a.layerVersions.isNotEmpty()) for ((k, v) in a.layerVersions) if (Layer.ofKey(k) == null) merged[k] = v // keep a newer app's keys
        for (layer in layers) merged[layer.key] = current(layer)
        return a.copy(layerVersions = merged)
    }

    /**
     * A freshly PRODUCED analysis (an analyzer's own output): exactly the stamps it already carries explicitly plus [layers],
     * with no inference. Use [stamp] instead to add a layer to a STORED record, whose older layers may only be inferred.
     */
    fun produced(a: TrackAnalysis, layers: Set<Layer>, current: (Layer) -> Int = { it.version }): TrackAnalysis {
        if (layers.isEmpty()) return a
        val merged = LinkedHashMap(a.layerVersions)
        for (layer in layers) merged[layer.key] = current(layer)
        return a.copy(layerVersions = merged)
    }

    /** One log fragment: `grid1 downbeatLogits1 envelopes1 structure1 vocals1`, with ` (inferred)` for a legacy record. */
    fun describe(a: TrackAnalysis): String {
        val p = present(a)
        val s = Layer.entries.joinToString(" ") { l -> p[l]?.let { "${l.key}$it" } ?: "-${l.key}" }
        return if (isLegacy(a)) "$s (inferred)" else s
    }

    /**
     * The rules the scheduler applied before layers existed:
     *  - GRID: the record was made by the device's full analyzer (`beat-this` in its id);
     *  - DOWNBEAT_LOGITS: the logits are stored, or there are no beats at all (nothing to vote; the old rule never
     *    re-analysed such a record);
     *  - ENVELOPES: always (every stored analysis went through the DSP pass);
     *  - STRUCTURE: the structure frames are stored;
     *  - VOCALS: both the ranges and the detector's profile are stored.
     */
    private fun inferred(a: TrackAnalysis): Set<Layer> =
        buildSet {
            if ("beat-this" in a.analyzerId) add(Layer.GRID)
            if (a.beatDownbeatLogits != null || a.beatTimesMs == null) add(Layer.DOWNBEAT_LOGITS)
            add(Layer.ENVELOPES)
            if (a.structureFrames.isNotEmpty()) add(Layer.STRUCTURE)
            if (a.vocals != null && a.vocalProfile != null) add(Layer.VOCALS)
        }
}
