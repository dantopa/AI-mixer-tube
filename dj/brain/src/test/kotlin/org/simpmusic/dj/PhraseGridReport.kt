package org.simpmusic.dj

import kotlinx.serialization.json.Json
import org.simpmusic.dj.analysis.AnalysisRefiner
import org.simpmusic.dj.analysis.PhraseGrid
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.simpmusic.dj.analysis.DspTrackAnalyzer
import org.simpmusic.dj.audio.WavIo
import org.simpmusic.dj.model.Confident
import org.simpmusic.dj.model.PcmAudio
import org.simpmusic.dj.model.TrackAnalysis
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test

/**
 * Manual: what [PhraseGrid] finds on stored analyses in PHRASE_DIR (an "Export analyses" folder: analysis/<id>.json and
 * titles.tsv), and a "phrase-gram" per track in PHRASE_OUT: one row per detected 16-bar block, one column per bar, the
 * cell coloured by the bar's low band (dark = no bass); the detected block line is the left edge. Skipped when unset.
 */
class PhraseGridReport {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun run() {
        val dir = System.getenv("PHRASE_DIR")?.let(::File)?.takeIf { it.isDirectory } ?: return println("skipped: no PHRASE_DIR")
        val out = System.getenv("PHRASE_OUT")?.let(::File)?.also { it.mkdirs() }
        val titles = File(dir, "titles.tsv").takeIf { it.exists() }?.readLines()?.associate { it.substringBefore('\t') to it.substringAfter('\t') } ?: emptyMap()
        val files = (File(dir, "analysis").listFiles() ?: dir.listFiles())!!.filter { it.name.endsWith(".json") }.sortedBy { it.name }
        var decided = 0; var phraseSure = 0; var blockSure = 0; var agreeCounted = 0; var blocksTotal = 0
        for (f in files) {
            val raw = runCatching { json.decodeFromString(TrackAnalysis.serializer(), f.readText()) }.getOrNull() ?: continue
            val r = AnalysisRefiner.cached(raw)
            val p = r.phrases ?: continue
            decided++
            if (p.phrasesTrusted) phraseSure++
            if (p.blocksTrusted) blockSure++
            // how often the counted convention (from the first decided 1, every 16) agrees with a detected block line
            val beats = r.beatTimesMs!!.value
            val downs = r.downbeatBeatIndices!!.value
            val counted = downs.indices.filter { it % 16 == 0 }.map { beats[downs[it]].toLong() }.toSet()
            agreeCounted += p.blockStartsMs.count { it in counted }
            blocksTotal += p.blockStartsMs.size
            println(
                "PHRASE ${raw.videoId} %5.1f bpm bars=${downs.size} %-60s %s".format(
                    r.bpm?.value ?: 0f,
                    (titles[raw.videoId] ?: "").take(60),
                    PhraseGrid.describe(r),
                ),
            )
            if (out != null) draw(r, File(out, "${raw.videoId}.png"))
        }
        println("PHRASE decided $decided, 8-bar phrases sure $phraseSure, 16-bar lines sure $blockSure; detected 16 lines on the counted ones $agreeCounted / $blocksTotal")
    }

    /**
     * Manual: the corpus audio in DJ_CORPUS (audio/<name>_22050.wav, refs/<name>.json) analysed by the DSP analyzer, its
     * bars replaced by the reference downbeats, phrases found with and without the structure frames. Skipped when unset.
     */
    @Test
    fun corpus() {
        val dir = System.getenv("DJ_CORPUS")?.let(::File)?.takeIf { it.isDirectory } ?: return println("skipped: no DJ_CORPUS")
        val out = System.getenv("PHRASE_OUT")?.let(::File)?.also { it.mkdirs() }
        val analyzer = DspTrackAnalyzer()
        val cacheDir = System.getenv("PHRASE_CACHE")?.let(::File)?.also { it.mkdirs() }
        var sureFull = 0; var sureLevels = 0; var n = 0
        for (ref in File(dir, "refs").listFiles()!!.filter { it.extension == "json" }.sortedBy { it.name }) {
            val name = ref.nameWithoutExtension
            val wavFile = File(dir, "audio/${name}_22050.wav").takeIf { it.exists() } ?: continue
            val cached = cacheDir?.let { File(it, "$name.json") }?.takeIf { it.exists() }
            val a0 = cached?.let { json.decodeFromString(TrackAnalysis.serializer(), it.readText()) } ?: run {
                val wav = WavIo.read(wavFile)
                analyzer.analyze(name, PcmAudio(wav.mono(), wav.sampleRate)).also { a -> cacheDir?.let { File(it, "$name.json").writeText(Json.encodeToString(TrackAnalysis.serializer(), a)) } }
            }
            val j = Json.parseToJsonElement(ref.readText()).jsonObject
            val beats = j["beatsSec"]!!.jsonArray.map { (it.jsonPrimitive.double * 1000).toInt() }
            val downs = j["downbeatsSec"]!!.jsonArray.map { d -> val t = d.jsonPrimitive.double * 1000; beats.indices.minBy { kotlin.math.abs(beats[it] - t) } }.distinct()
            val a = a0.copy(beatTimesMs = Confident(beats, 0.9f), downbeatBeatIndices = Confident(downs, 0.9f), beatDownbeatLogits = null, barPhase = null)
            val full = PhraseGrid.apply(a)
            val levels = PhraseGrid.apply(a.copy(highBandEnergy = emptyList(), structureFrames = emptyList(), structureHopMs = 0))
            n++
            if (full.phrases?.blocksTrusted == true) sureFull++
            if (levels.phrases?.blocksTrusted == true) sureLevels++
            println("PHRASE-CORPUS %-24s bars=%3d full: %s".format(name, downs.size, PhraseGrid.describe(full)))
            println("PHRASE-CORPUS %-24s          levels only: %s".format("", PhraseGrid.describe(levels)))
            if (out != null) draw(full, File(out, "$name.png"))
        }
        println("PHRASE-CORPUS 16-bar lines sure: with structure $sureFull / $n, levels only $sureLevels / $n")
    }

    private fun draw(a: TrackAnalysis, file: File) {
        val p = a.phrases ?: return
        val beats = a.beatTimesMs!!.value
        val downs = a.downbeatBeatIndices!!.value
        val starts = downs.map { beats[it].toLong() }
        val hop = a.energyHopMs
        fun lev(env: List<Float>, i: Int): Double {
            val t0 = starts[i]; val t1 = if (i + 1 < starts.size) starts[i + 1] else t0 + 2000
            val h0 = (t0 / hop).toInt(); val h1 = min(env.size, max(h0 + 1, (t1 / hop).toInt()))
            if (h0 >= env.size) return -7.0
            return (h0 until h1).map { ln(max(env[it].toDouble(), 1e-3)) }.average()
        }
        val rows = ArrayList<MutableList<Int>>()
        for (i in starts.indices) {
            if (rows.isEmpty() || p.barPositions[i] == 0 || p.barPositions[i] < p.barPositions[i - 1]) rows += mutableListOf<Int>()
            rows.last() += i
        }
        val cw = 22; val ch = 16; val lead = 16
        val img = BufferedImage(lead + 16 * cw * 2 + 8, rows.size * ch, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(20, 20, 26); g.fillRect(0, 0, img.width, img.height)
        val lows = starts.indices.map { lev(a.lowBandEnergy, it) }
        val ens = starts.indices.map { lev(a.energy, it) }
        fun norm(v: Double, xs: List<Double>) = ((v - xs.min()) / (xs.max() - xs.min()).coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
        for ((r, row) in rows.withIndex()) {
            for (i in row) {
                val col = p.barPositions[i]
                val lo = norm(lows[i], lows); val en = norm(ens[i], ens)
                g.color = Color((255 * lo).toInt(), (120 * lo).toInt(), 30)
                g.fillRect(lead + col * cw, r * ch, cw - 2, ch - 2)
                g.color = Color(30, (200 * en).toInt(), (255 * en).toInt())
                g.fillRect(lead + 16 * cw + 8 + col * cw, r * ch, cw - 2, ch - 2)
            }
        }
        g.color = Color.WHITE
        for (c in listOf(0, 8)) { g.drawLine(lead + c * cw - 1, 0, lead + c * cw - 1, img.height); g.drawLine(lead + 16 * cw + 8 + c * cw - 1, 0, lead + 16 * cw + 8 + c * cw - 1, img.height) }
        g.dispose()
        ImageIO.write(img, "png", file)
    }
}
