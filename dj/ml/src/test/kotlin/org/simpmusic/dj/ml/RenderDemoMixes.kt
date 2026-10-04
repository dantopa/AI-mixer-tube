package org.simpmusic.dj.ml

import kotlinx.serialization.json.Json
import org.simpmusic.dj.audio.WavIo
import org.simpmusic.dj.model.*
import org.simpmusic.dj.planner.DjTransitionPlanner
import org.simpmusic.dj.render.AudioSegment
import org.simpmusic.dj.render.OfflineMixRenderer
import org.simpmusic.dj.render.StereoPcm
import java.io.File
import kotlin.test.Test

/** Manual: writes listenable demo transitions (DJ plan and the plain crossfade of the same pair) to DJ_OUT. */
class RenderDemoMixes {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun run() {
        val corpus = System.getenv("BEAT_THIS_CORPUS")?.let(::File) ?: return println("skipped")
        val cache = System.getenv("DJ_CACHE")?.let(::File) ?: return println("skipped")
        val out = System.getenv("DJ_OUT")?.let(::File)?.also { it.mkdirs() } ?: return println("skipped")
        val audio = HashMap<String, StereoPcm>()
        val ana = LinkedHashMap<String, TrackAnalysis>()
        for (f in corpus.resolve("audio").listFiles { x -> x.name.endsWith("_44100.wav") }!!.sortedBy { it.name }) {
            val id = f.name.removeSuffix("_44100.wav")
            val c = cache.resolve("$id.json").takeIf { it.exists() } ?: continue
            ana[id] = json.decodeFromString(c.readText())
            val w = WavIo.read(f)
            audio[id] = StereoPcm.fromMono(w.mono(), w.sampleRate)
        }
        val planner = DjTransitionPlanner()
        val settings = DjSettings(enabled = true)
        val candidates = ArrayList<Triple<String, String, TransitionPlan>>()
        for ((ia, a) in ana) for ((ib, b) in ana) if (ia != ib) {
            val p = planner.plan(a, b, settings)
            if (p.kind == PlanKind.BEAT_MATCHED && (p.mixBpm ?: 0f) in 90f..170f) candidates += Triple(ia, ib, p)
        }
        val chosen = ArrayList<Triple<String, String, TransitionPlan>>()
        val used = HashSet<String>()
        for (c in candidates.sortedByDescending { it.third.confidence }) {
            if (c.first in used || c.second in used) continue
            chosen += c; used += c.first; used += c.second
            if (chosen.size == 4) break
        }
        for ((ia, ib, plan) in chosen) {
            val lead = maxOf(14_000L, -plan.preRollMs)
            fun render(p: TransitionPlan, name: String) {
                val win = OfflineMixRenderer.render(p, AudioSegment(audio.getValue(ia)), AudioSegment(audio.getValue(ib)), OfflineMixRenderer.Options(leadMs = lead, tailMs = 14_000L))
                WavIo.write(File(out, name), WavIo.Wav(arrayOf(win.audio.left, win.audio.right), win.audio.sampleRate))
            }
            val tag = "${ia}__to__${ib}"
            render(plan, "$tag.DJ.wav")
            render(planner.plan(ana[ia], ana[ib], settings.copy(minConfidence = 1.1f)), "$tag.crossfade.wav")
            println("DEMO $tag: ${plan.reason.take(150)}")
        }
    }
}
