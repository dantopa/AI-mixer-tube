package org.simpmusic.dj

import kotlinx.serialization.json.Json
import org.simpmusic.dj.model.*
import org.simpmusic.dj.planner.DjTransitionPlanner
import java.io.File
import kotlin.test.Test

/**
 * Manual: how the planner mixes every ordered pair of the analyses in STRUCTURE_DIR/analysis that carry vocals
 * (an "Export analyses" folder), with the app's default settings and Perfect first. Prints the kind of each plan.
 */
class StructureReport {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun run() {
        val dir = System.getenv("STRUCTURE_DIR")?.let(::File)?.takeIf { it.isDirectory } ?: return println("skipped: no STRUCTURE_DIR")
        val titles = File(dir, "titles.tsv").takeIf { it.isFile }?.readLines()?.mapNotNull { l -> l.split('\t').takeIf { it.size >= 2 }?.let { it[0] to it[1] } }?.toMap() ?: emptyMap()
        val all = (File(dir, "analysis").listFiles() ?: emptyArray()).sortedBy { it.name }.mapNotNull { f ->
            runCatching { json.decodeFromString(TrackAnalysis.serializer(), f.readText()) }.getOrNull()
        }.filter { it.vocals != null }
        val settings = DjSettings(enabled = true, allowEchoOut = false, allowKeyShift = false, maxTempoBend = 0.06f)
        val planner = DjTransitionPlanner()
        var structure = 0
        var clash = 0
        var bm = 0
        for (a in all) for (b in all) {
            if (a === b) continue
            val p = planner.plan(a, b, settings, PlanConstraints(preferPerfect = true))
            if (p.kind == PlanKind.BEAT_MATCHED) bm++
            if ("STRUCTURE" in p.reason) structure++
            // only the chosen plan's own reason, not the refused Perfect attempt appended after " | "
            if ("voice over voice" in p.reason.substringBefore(" | ")) { clash++; println("VOICE OVER VOICE: " + p.reason.substringBefore(" | ")) }
            val oc = org.simpmusic.dj.planner.TrackContext(org.simpmusic.dj.analysis.AnalysisRefiner.cached(a))
            val ic = org.simpmusic.dj.planner.TrackContext(org.simpmusic.dj.analysis.AnalysisRefiner.cached(b))
            val hs = org.simpmusic.dj.planner.StructureHandoff.find(oc, ic, org.simpmusic.dj.analysis.VocalClash.ranges(a), org.simpmusic.dj.analysis.VocalClash.ranges(b), 0, Long.MAX_VALUE, 8)
            if (System.getenv("STRUCTURE_VERBOSE") != null) println("   candidates: bars ${oc.barsTrusted}/${ic.barsTrusted} " + hs.joinToString { "exit %.1f entry %.1f %d bars%s".format(it.exitMs / 1000.0, it.entryMs / 1000.0, it.bars, if (it.outro) " outro" else "") })
            println("%-22s -> %-22s %s %s".format(titles[a.videoId]?.take(22) ?: a.videoId, titles[b.videoId]?.take(22) ?: b.videoId, p.kind, p.reason.take(230)))
        }
        val n = all.size * (all.size - 1)
        println("pairs $n: beat-matched $bm, structure hand-offs $structure, voice over voice $clash")
    }
}
