package org.simpmusic.dj.ml

import kotlinx.serialization.json.Json
import org.simpmusic.dj.analysis.GridRepair
import org.simpmusic.dj.model.DjSettings
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.planner.DjTransitionPlanner
import java.io.File
import kotlin.test.Test

/**
 * Manual: per track, what [GridRepair] does to the cached analyses in DJ_CACHE, then every ordered pair planned on the
 * raw and on the repaired analyses (default and simple-mode settings). Skipped when DJ_CACHE is unset.
 */
class GridRepairReport {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun run() {
        val cache = System.getenv("DJ_CACHE")?.let(::File)?.takeIf { it.isDirectory } ?: return println("skipped: no DJ_CACHE")
        val raw = cache.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }.map { json.decodeFromString(TrackAnalysis.serializer(), it.readText()) }
        val fixed = raw.map { a ->
            val (r, rep) = GridRepair.repairWithReport(a)
            println("GRIDREPAIR ${a.videoId} ${if (rep.repaired) "REPAIRED" else "kept    "} bpm ${"%.1f".format(a.bpm?.value ?: 0f)} -> ${"%.1f".format(r.bpm?.value ?: 0f)} beats ${a.beatTimesMs?.value?.size} -> ${r.beatTimesMs?.value?.size} snapped ${rep.snapped} filled ${rep.filled} | ${rep.reason}")
            r
        }
        val planner = DjTransitionPlanner()
        val simple = DjSettings(enabled = true, allowEchoOut = false, allowKeyShift = false, bassSwap = false, maxTempoBend = 0.06f)
        for ((label, s) in listOf("default" to DjSettings(enabled = true), "simple" to simple)) {
            for ((name, set) in listOf("raw" to raw, "repaired" to raw)) {
                GridRepair.enabled = name == "repaired"
                val kinds = java.util.TreeMap<String, Int>()
                val reasons = java.util.TreeMap<String, Int>()
                for (a in set) for (b in set) {
                    if (a === b) continue
                    val p = planner.plan(a, b, s)
                    kinds.merge(p.kind.name, 1, Int::plus)
                    if (p.kind.name == "SIMPLE_CROSSFADE") reasons.merge(p.reason.substringAfter("): ").replace(Regex("[0-9.]+"), "#").take(70), 1, Int::plus)
                }
                GridRepair.enabled = true
                println("GRIDREPAIR $label $name kinds=$kinds")
                if (label == "simple") reasons.entries.sortedByDescending { it.value }.take(6).forEach { println("GRIDREPAIR   $name x${it.value}: ${it.key}") }
            }
        }
    }
}
