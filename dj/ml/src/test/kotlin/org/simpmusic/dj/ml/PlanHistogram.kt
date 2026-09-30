package org.simpmusic.dj.ml

import kotlinx.serialization.json.Json
import org.simpmusic.dj.model.DjSettings
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.planner.DjTransitionPlanner
import java.io.File
import kotlin.test.Test

/**
 * Manual: plans every ordered pair of the cached real-music analyses with the DEFAULT settings and prints the
 * histogram of plan kinds, where in the outgoing track the exit falls, and why pairs fell back to a crossfade.
 * Uses only the stable planner API, so the very same file compiles against older revisions of the planner
 * (that is how the "before" numbers were measured).
 *
 * Env: DJ_CACHE (folder of `<id>.json` TrackAnalysis files). Skipped when unset.
 */
class PlanHistogram {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun run() {
        val cache = System.getenv("DJ_CACHE")?.let(::File)?.takeIf { it.isDirectory } ?: return println("skipped: no DJ_CACHE")
        val ana = LinkedHashMap<String, TrackAnalysis>()
        for (f in cache.listFiles { x -> x.name.endsWith(".json") }!!.sortedBy { it.name }) {
            ana[f.name.removeSuffix(".json")] = json.decodeFromString(f.readText())
        }
        val planner = DjTransitionPlanner()
        val settings = DjSettings(enabled = true)
        val kinds = java.util.TreeMap<String, Int>()
        val exitDeciles = IntArray(10)
        val reasons = java.util.TreeMap<String, Int>()
        var pairs = 0
        for ((ia, a) in ana) for ((ib, b) in ana) {
            if (ia == ib) continue
            val p = planner.plan(a, b, settings)
            pairs++
            kinds.merge(p.kind.name, 1, Int::plus)
            if (a.durationMs > 0) exitDeciles[((10.0 * p.exitPointMs / a.durationMs).toInt()).coerceIn(0, 9)]++
            if (p.kind.name == "SIMPLE_CROSSFADE") reasons.merge(p.reason.substringAfter("): ").take(90), 1, Int::plus)
        }
        println("PLANHIST pairs=$pairs kinds=$kinds")
        println("PLANHIST exit position in the outgoing track, share per 10% bucket: " + exitDeciles.joinToString(" ") { "%d".format(it) })
        reasons.entries.sortedByDescending { it.value }.forEach { println("PLANHIST crossfade reason x${it.value}: ${it.key}") }
    }
}
