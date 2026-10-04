package org.simpmusic.dj.tools

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.simpmusic.dj.audio.WavIo
import org.simpmusic.dj.model.DjSettings
import org.simpmusic.dj.model.PcmAudio
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.planner.DjTransitionPlanner
import org.simpmusic.dj.render.AudioSegment
import org.simpmusic.dj.render.OfflineMixRenderer
import org.simpmusic.dj.render.StereoPcm
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * Command line demo: mixes the end of one WAV into the start of another the way the planner would on device.
 *
 *     MixDemo <from.wav> <to.wav> <out.wav> [options]
 *       --bpm-from N --bpm-to N            tempo overrides (otherwise a crude estimate)
 *       --first-beat-from MS --first-beat-to MS
 *       --key-from 8A|Am|C# --key-to ...   key overrides (Camelot code or note name)
 *       --analysis-from f.json --analysis-to f.json   TrackAnalysis JSON (kotlinx.serialization) instead of the above
 *       --bars N  --bend F  --no-bass-swap --no-key-shift --min-confidence F
 *       --lead-ms N (default 12000)  --tail-ms N (default 12000)
 *       --plan-out plan.json               also write the TransitionPlan as JSON
 *
 * The analyzer proper arrives later; until then the grid comes from [GridAnalysis].
 */
object MixDemo {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(args, System.out))
    }

    fun run(args: Array<String>, log: PrintStream = System.out): Int {
        val pos = ArrayList<String>()
        val opt = HashMap<String, String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a.startsWith("--")) {
                val name = a.removePrefix("--")
                if (name in FLAGS) opt[name] = "true" else { opt[name] = args.getOrNull(i + 1) ?: error("missing value for $a"); i++ }
            } else pos += a
            i++
        }
        if (pos.size != 3) {
            log.println("usage: MixDemo <from.wav> <to.wav> <out.wav> [options] (see source header)")
            return 2
        }
        val fromWav = WavIo.read(File(pos[0]))
        val toWav = WavIo.read(File(pos[1]))
        require(fromWav.sampleRate == toWav.sampleRate) { "both files need the same sample rate (${fromWav.sampleRate} vs ${toWav.sampleRate})" }
        val sr = fromWav.sampleRate

        fun analysis(id: String, wav: WavIo.Wav, side: String): TrackAnalysis {
            opt["analysis-$side"]?.let { return json.decodeFromString<TrackAnalysis>(File(it).readText()) }
            return GridAnalysis.build(
                id, PcmAudio(wav.mono(), wav.sampleRate),
                bpm = opt["bpm-$side"]?.toFloat(), firstBeatMs = opt["first-beat-$side"]?.toInt(),
                key = opt["key-$side"]?.let { GridAnalysis.parseKey(it) ?: error("cannot parse key '$it'") },
            )
        }

        val a = analysis("from", fromWav, "from")
        val b = analysis("to", toWav, "to")
        val settings = DjSettings(
            enabled = true,
            overlapBars = opt["bars"]?.toInt() ?: 8,
            maxTempoBend = opt["bend"]?.toFloat() ?: 0.08f,
            bassSwap = "no-bass-swap" !in opt,
            allowKeyShift = "no-key-shift" !in opt,
            minConfidence = opt["min-confidence"]?.toFloat() ?: 0.5f,
        )
        val plan = DjTransitionPlanner().plan(a, b, settings)
        log.println("plan: ${plan.kind}  exit=${plan.exitPointMs}ms entry=${plan.entryPointMs}ms overlap=${plan.overlapMs}ms mixBpm=${plan.mixBpm} conf=${plan.confidence}")
        log.println("reason: ${plan.reason}")
        opt["plan-out"]?.let { File(it).writeText(json.encodeToString(plan)) }

        val lead = opt["lead-ms"]?.toLong() ?: 12_000L
        val tail = opt["tail-ms"]?.toLong() ?: 12_000L
        val t0 = System.nanoTime()
        val window = OfflineMixRenderer.render(
            plan, AudioSegment(toStereo(fromWav)), AudioSegment(toStereo(toWav)),
            OfflineMixRenderer.Options(leadMs = maxOf(lead, -plan.preRollMs), tailMs = tail),
        )
        val ms = (System.nanoTime() - t0) / 1e6
        log.println("rendered ${window.frames * 1000L / sr} ms in ${ms.toLong()} ms (real-time factor ${"%.3f".format(ms / (window.frames * 1000.0 / sr))})")
        WavIo.write(File(pos[2]), WavIo.Wav(arrayOf(window.audio.left, window.audio.right), sr))
        log.println("wrote ${pos[2]}")
        return 0
    }

    private fun toStereo(w: WavIo.Wav): StereoPcm =
        if (w.channels.size >= 2) StereoPcm(w.channels[0], w.channels[1], w.sampleRate) else StereoPcm.fromMono(w.channels[0], w.sampleRate)

    private val FLAGS = setOf("no-bass-swap", "no-key-shift")
}
