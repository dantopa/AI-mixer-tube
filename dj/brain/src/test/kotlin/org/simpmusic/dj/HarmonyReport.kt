package org.simpmusic.dj

import kotlinx.serialization.json.Json
import org.simpmusic.dj.analysis.Harmony
import org.simpmusic.dj.model.*
import org.simpmusic.dj.planner.DjTransitionPlanner
import java.io.File
import kotlin.test.Test

/**
 * Manual: what the local harmonic fit changes on stored analyses in HARMONY_DIR (an "Export analyses" folder,
 * analysis/<id>.json). Every ordered pair of analyses that carry structure frames is planned twice with the app's
 * default (simple mode) settings, once as stored and once with the frames stripped (the planner before harmony), and the
 * chosen overlap of each is scored with the frames. Prints how many overlaps clash before and after.
 */
class HarmonyReport {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun run() {
        val dir = System.getenv("HARMONY_DIR")?.let(::File)?.takeIf { it.isDirectory } ?: return println("skipped: no HARMONY_DIR")
        val all = (File(dir, "analysis").listFiles() ?: emptyArray()).sortedBy { it.name }.mapNotNull { f ->
            runCatching { json.decodeFromString(TrackAnalysis.serializer(), f.readText()) }.getOrNull()
        }.filter { it.structureFrames.isNotEmpty() && (it.beatTimesMs?.value?.size ?: 0) > 64 }
        println("analyses with structure frames: ${all.size}")
        val settings = DjSettings(enabled = true, allowEchoOut = false, allowKeyShift = false, maxTempoBend = 0.06f)
        val planner = DjTransitionPlanner()
        val constraints = PlanConstraints(preferPerfect = true)
        var pairs = 0; var bmOld = 0; var bmNew = 0; var changed = 0
        var clashOld = 0; var clashNew = 0; var tenseOld = 0; var tenseNew = 0; var scored = 0
        var sumOld = 0.0; var sumNew = 0.0
        val t0 = System.currentTimeMillis()
        for (a in all) for (b in all) {
            if (a === b) continue
            pairs++
            val pNew = planner.plan(a, b, settings, constraints)
            val pOld = planner.plan(a.copy(structureFrames = emptyList(), structureHopMs = 0), b.copy(structureFrames = emptyList(), structureHopMs = 0), settings, constraints)
            if (pOld.kind == PlanKind.BEAT_MATCHED) bmOld++
            if (pNew.kind == PlanKind.BEAT_MATCHED) bmNew++
            if (pOld.kind != PlanKind.BEAT_MATCHED || pNew.kind != PlanKind.BEAT_MATCHED) continue
            if (pOld.exitPointMs != pNew.exitPointMs || pOld.entryPointMs != pNew.entryPointMs) changed++
            fun fit(p: TransitionPlan) = Harmony.fit(Harmony.profile(a), p.exitPointMs, p.overlapMs, Harmony.profile(b), p.entryPointMs, p.overlapMs)
            val fo = fit(pOld) ?: continue
            val fn = fit(pNew) ?: continue
            scored++
            sumOld += fo.excess; sumNew += fn.excess
            if (fo.clash) clashOld++ else if (fo.penalty > 0) tenseOld++
            if (fn.clash) clashNew++ else if (fn.penalty > 0) tenseNew++
        }
        fun pct(n: Int) = "%d (%.1f %%)".format(n, 100.0 * n / scored.coerceAtLeast(1))
        println("pairs $pairs in ${System.currentTimeMillis() - t0} ms; beat-matched before $bmOld, after $bmNew; exit/entry changed $changed")
        println("overlaps scored $scored: clash before ${pct(clashOld)}, after ${pct(clashNew)}; tense before ${pct(tenseOld)}, after ${pct(tenseNew)}")
        println("mean excess before %.4f, after %.4f".format(sumOld / scored.coerceAtLeast(1), sumNew / scored.coerceAtLeast(1)))
    }
}
