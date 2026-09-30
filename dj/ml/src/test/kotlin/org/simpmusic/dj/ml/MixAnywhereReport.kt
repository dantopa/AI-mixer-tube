package org.simpmusic.dj.ml

import kotlinx.serialization.json.Json
import org.simpmusic.dj.audio.WavIo
import org.simpmusic.dj.model.*
import org.simpmusic.dj.planner.DjTransitionPlanner
import org.simpmusic.dj.render.AudioSegment
import org.simpmusic.dj.render.OfflineMixRenderer
import org.simpmusic.dj.render.StereoPcm
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * Manual, real music. Plans all ordered pairs with mixPoint ANYWHERE and AT_END, prints the histogram of plan kinds,
 * the distribution of exit positions and the fallback reasons, then renders 8 demo transitions (14 s lead, 14 s tail):
 * 2 beat-matched mid-song, 2 echo-out with very different tempos, 2 with the exit well before the end, 2 at-end.
 *
 * Env: BEAT_THIS_CORPUS (folder with audio/<id>_44100.wav), DJ_CACHE (analyses), DJ_OUT (demo folder). Skipped if unset.
 */
class MixAnywhereReport {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private class Row(val a: String, val b: String, val plan: TransitionPlan, val exitFrac: Double, val ratio: Double)

    private fun histogram(name: String, rows: List<Row>) {
        val kinds = rows.groupingBy { it.plan.kind }.eachCount().toSortedMap()
        println("REPORT [$name] pairs=${rows.size} kinds=$kinds")
        val dec = IntArray(10)
        rows.forEach { dec[(10 * it.exitFrac).toInt().coerceIn(0, 9)]++ }
        println("REPORT [$name] exit position (share of the outgoing track), pairs per 10% bucket: " + dec.withIndex().joinToString(" ") { "${it.index * 10}%:${it.value}" })
        for (kind in PlanKind.values()) {
            val f = rows.filter { it.plan.kind == kind }.map { it.exitFrac }.sorted()
            if (f.isNotEmpty()) println("REPORT [$name] $kind exit fraction min/median/max = %.2f / %.2f / %.2f".format(f.first(), f[f.size / 2], f.last()))
        }
        rows.filter { it.plan.kind == PlanKind.SIMPLE_CROSSFADE }
            .groupingBy { it.plan.reason.substringAfter("): ").take(110) }.eachCount().entries.sortedByDescending { it.value }
            .forEach { println("REPORT [$name] crossfade reason x${it.value}: ${it.key}") }
        rows.filter { it.plan.kind == PlanKind.ECHO_OUT }.take(3).forEach { println("REPORT [$name] echo example ${it.a}->${it.b}: ${it.plan.reason.take(260)}") }
    }

    /** Tempo of the beat grid itself (the analysis bpm field can be a half/double-time artefact). */
    private fun gridBpm(a: TrackAnalysis): Double {
        val b = a.beatTimesMs?.value ?: return 120.0
        if (b.size < 4) return 120.0
        val d = b.zipWithNext { x, y -> (y - x).toDouble() }.sorted()
        return 60000.0 / d[d.size / 2]
    }

    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        val a = from.coerceIn(0, x.size); val b = to.coerceIn(a, x.size)
        if (b == a) return 0.0
        var s = 0.0
        for (i in a until b) s += x[i].toDouble() * x[i]
        return sqrt(s / (b - a))
    }

    @Test
    fun run() {
        val corpus = System.getenv("BEAT_THIS_CORPUS")?.let(::File) ?: return println("skipped: no BEAT_THIS_CORPUS")
        val cache = System.getenv("DJ_CACHE")?.let(::File) ?: return println("skipped: no DJ_CACHE")
        val out = System.getenv("DJ_OUT")?.let(::File)?.also { it.mkdirs() }
        val audio = HashMap<String, StereoPcm>()
        val ana = LinkedHashMap<String, TrackAnalysis>()
        for (f in corpus.resolve("audio").listFiles { x -> x.name.endsWith("_44100.wav") }!!.sortedBy { it.name }) {
            val id = f.name.removeSuffix("_44100.wav")
            val c = cache.resolve("$id.json").takeIf { it.exists() } ?: continue
            ana[id] = json.decodeFromString(c.readText())
            if (out != null) { val w = WavIo.read(f); audio[id] = StereoPcm.fromMono(w.mono(), w.sampleRate) }
        }
        val planner = DjTransitionPlanner()
        val anywhere = DjSettings(enabled = true)
        val atEnd = anywhere.copy(mixPoint = MixPoint.AT_END)
        fun plans(s: DjSettings) = ArrayList<Row>().also { rows ->
            for ((ia, a) in ana) for ((ib, b) in ana) if (ia != ib) {
                val p = planner.plan(a, b, s)
                rows += Row(ia, ib, p, p.exitPointMs.toDouble() / a.durationMs, gridBpm(b) / gridBpm(a))
            }
        }
        val rAny = plans(anywhere)
        val rEnd = plans(atEnd)
        histogram("ANYWHERE (default)", rAny)
        histogram("AT_END", rEnd)
        // determinism
        val again = plans(anywhere)
        println("REPORT deterministic: ${again.map { it.plan } == rAny.map { it.plan }}")
        // constraint: the caller has played 90 s and needs 40 s to render
        val cons = ArrayList<Row>()
        for ((ia, a) in ana) for ((ib, b) in ana) if (ia != ib) {
            val p = planner.plan(a, b, anywhere, PlanConstraints(earliestExitMs = 130_000))
            cons += Row(ia, ib, p, p.exitPointMs.toDouble() / a.durationMs, 1.0)
            check(p.exitPointMs >= 130_000 || p.kind == PlanKind.SIMPLE_CROSSFADE && p.exitPointMs + p.overlapMs >= 130_000 || a.durationMs < 130_000) { "constraint violated ${p.reason}" }
        }
        histogram("ANYWHERE, earliestExitMs=130000", cons)
        if (out == null) return println("no DJ_OUT: demos skipped")

        // ---------------------------------------------------------------- demos
        val used = HashSet<String>()
        val usedPairs = HashSet<String>()
        fun pick(rows: List<Row>, n: Int, filter: (Row) -> Boolean, order: Comparator<Row>): List<Row> {
            val chosen = ArrayList<Row>()
            for (r in rows.filter(filter).sortedWith(order)) {
                if ("${r.a}>${r.b}" in usedPairs || "${r.b}>${r.a}" in usedPairs) continue
                // spread over the corpus: a track used by an earlier demo is only reused when nothing else is left
                if ((r.a in used || r.b in used) && rows.any { o -> filter(o) && o.a !in used && o.b !in used }) continue
                chosen += r; used += r.a; used += r.b; usedPairs += "${r.a}>${r.b}"
                if (chosen.size == n) break
            }
            return chosen
        }
        val far = compareByDescending<Row> { maxOf(it.ratio, 1 / it.ratio) }.thenBy { it.a }.thenBy { it.b }
        val conf = compareByDescending<Row> { it.plan.confidence }.thenBy { it.a }.thenBy { it.b }
        val echoes = pick(rAny, 2, { it.plan.kind == PlanKind.ECHO_OUT && it.plan.reason.contains("beyond the") && it.exitFrac > 0.5 }, far)
        val beatMid = pick(rAny, 2, { it.plan.kind == PlanKind.BEAT_MATCHED && it.exitFrac < 0.8 && it.plan.overlapMs >= 8000 && (it.plan.mixBpm ?: 0f) in 80f..170f }, conf)
        val early = pick(rAny, 2, { it.plan.kind != PlanKind.SIMPLE_CROSSFADE && it.exitFrac < 0.7 }, compareBy<Row> { it.exitFrac }.thenBy { it.a })
        val end = pick(rEnd, 2, { it.plan.kind == PlanKind.BEAT_MATCHED && it.exitFrac > 0.8 && it.plan.overlapMs >= 8000 }, conf)
        val manifest = StringBuilder()
        fun render(tag: String, r: Row) {
            val plan = r.plan
            val lead = maxOf(14_000L, -plan.preRollMs)
            val win = OfflineMixRenderer.render(plan, AudioSegment(audio.getValue(r.a)), AudioSegment(audio.getValue(r.b)), OfflineMixRenderer.Options(leadMs = lead, tailMs = 14_000L))
            val name = "${tag}__${r.a}__to__${r.b}.wav"
            WavIo.write(File(out, name), WavIo.Wav(arrayOf(win.audio.left, win.audio.right), win.audio.sampleRate))
            val sr = win.audio.sampleRate
            val t0 = win.t0Frame
            var extra = ""
            plan.echoOut?.let { e ->
                val dryRef = rms(win.audio.left, t0 - 3 * sr, t0 - sr)
                val wetNow = OfflineMixRenderer.render(plan, AudioSegment(audio.getValue(r.a)), AudioSegment(audio.getValue(r.b)), OfflineMixRenderer.Options(leadMs = lead, tailMs = 1000L, solo = OfflineMixRenderer.Solo.OUTGOING))
                val wet = rms(wetNow.audio.left, wetNow.t0Frame, wetNow.t0Frame + sr)
                extra = " echoTail1s=%.1f dB vs outgoing before the fade (%.1f dB)".format(20 * log10(wet.coerceAtLeast(1e-9)), 20 * log10(dryRef.coerceAtLeast(1e-9)))
            }
            val bpmS = "%.0f->%.0f".format(gridBpm(ana.getValue(r.a)), gridBpm(ana.getValue(r.b)))
            val line = "DEMO $name gridBpm=$bpmS kind=${plan.kind} exit=${plan.exitPointMs}ms (${(100 * r.exitFrac).toInt()}% of ${ana.getValue(r.a).durationMs}ms) entry=${plan.entryPointMs}ms overlap=${plan.overlapMs}ms lead=${lead}ms;$extra ${plan.reason.take(200)}"
            println(line)
            manifest.appendLine(line)
        }
        echoes.forEachIndexed { i, r -> render("echo${i + 1}", r) }
        beatMid.forEachIndexed { i, r -> render("beatmid${i + 1}", r) }
        early.forEachIndexed { i, r -> render("early${i + 1}", r) }
        end.forEachIndexed { i, r -> render("atend${i + 1}", r) }
        File(out, "MANIFEST.txt").writeText(manifest.toString())
    }
}
