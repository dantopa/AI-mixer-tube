import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.maxrave.simpmusic.ui.component.dj.DjMixCanvas
import com.maxrave.simpmusic.ui.component.dj.DjMixCardContent
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat
import org.simpmusic.dj.android.mixview.DjMixViewBuilder
import org.simpmusic.dj.mixview.DjMixViewData
import org.simpmusic.dj.model.DjSettings
import org.simpmusic.dj.model.PlanKind
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.model.TransitionPlan
import org.simpmusic.dj.planner.DjTransitionPlanner
import java.io.File
import kotlin.test.Test

/** Manual: renders the mix view off-screen to PNG (MIXVIEW_OUT) from real cached analyses (DJ_CACHE). */
class RenderMixViews {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val dark: ColorScheme = darkColorScheme(
        primary = Color(0xFFD0BCFF), tertiary = Color(0xFFEFB8C8), secondary = Color(0xFFCCC2DC), surface = Color(0xFF141218),
        surfaceContainer = Color(0xFF211F26), onSurface = Color(0xFFE6E0E9), onSurfaceVariant = Color(0xFFCAC4D0),
    )
    private val light: ColorScheme = lightColorScheme(
        primary = Color(0xFF6750A4), tertiary = Color(0xFF7D5260), secondary = Color(0xFF625B71), surface = Color(0xFFFEF7FF),
        surfaceContainer = Color(0xFFF3EDF7), onSurface = Color(0xFF1D1B20), onSurfaceVariant = Color(0xFF49454F),
    )

    private fun png(w: Int, h: Int, scheme: ColorScheme, file: File, content: @androidx.compose.runtime.Composable () -> Unit) {
        val scene = ImageComposeScene(width = w * 2, height = h * 2, density = Density(2f)) {
            MaterialTheme(colorScheme = scheme) {
                Box(Modifier.fillMaxSize().background(scheme.surface)) { content() }
            }
        }
        val img = scene.render()
        file.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }

    @Test
    fun run() {
        val out = System.getenv("MIXVIEW_OUT")?.takeIf { it.isNotEmpty() }?.let(::File)?.also { it.mkdirs() } ?: return println("skipped")
        val cache = System.getenv("DJ_CACHE")?.takeIf { it.isNotEmpty() }?.let(::File) ?: return println("skipped")
        val ana = LinkedHashMap<String, TrackAnalysis>()
        for (f in cache.listFiles { x -> x.name.endsWith(".json") }!!.sortedBy { it.name }) {
            ana[f.name.removeSuffix(".json")] = json.decodeFromString(f.readText())
        }
        val planner = DjTransitionPlanner()
        val settings = DjSettings(enabled = true)
        data class C(val a: String, val b: String, val plan: TransitionPlan)
        val cands = ArrayList<C>()
        for ((ia, a) in ana) for ((ib, b) in ana) if (ia != ib) cands += C(ia, ib, planner.plan(a, b, settings))
        println("MIXVIEW candidates: " + cands.groupingBy { it.plan.kind }.eachCount())
        fun title(id: String) = id.replace('_', ' ').replaceFirstChar { it.uppercase() }
        fun view(c: C, plan: TransitionPlan = c.plan, now: Double? = null) =
            DjMixViewBuilder.build(plan, ana[c.a], ana[c.b], title(c.a) to title(c.b), now)

        val beat = cands.filter { it.plan.kind == PlanKind.BEAT_MATCHED && (it.plan.mixBpm ?: 0f) in 90f..170f }
        // Most tempo movement first: the picture is most interesting when a deck is visibly stretched.
        fun bend(c: C) = kotlin.math.abs(c.plan.outgoing.rate.keys.last().value - 1f) + kotlin.math.abs(c.plan.incoming.rate.keys.first().value - 1f)
        val chosen = beat.sortedByDescending { bend(it) }.take(3)
        chosen.forEach { println("MIXVIEW beat ${it.a}->${it.b} bend=${bend(it)} ${it.plan.reason.take(120)}") }
        val hero = chosen.first()
        val second = chosen.getOrElse(1) { hero }
        val fade = C(hero.a, hero.b, planner.plan(ana[hero.a], ana[hero.b], settings.copy(minConfidence = 1.1f)))
        val cut = cands.firstOrNull { it.plan.kind == PlanKind.CUT }

        val scenes = ArrayList<Pair<String, DjMixViewData>>()
        scenes += "beatmatched_mixing" to view(hero, now = hero.plan.overlapMs * 0.35)
        scenes += "beatmatched_leadin" to view(second, now = -3_500.0)
        scenes += "crossfade" to view(fade, now = fade.plan.overlapMs * 0.5)
        if (cut != null) scenes += "cut" to view(cut, now = 1_200.0)
        scenes += "analysing" to DjMixViewData.analysing(title(hero.a), title(hero.b))

        for ((name, data) in scenes) for ((mode, scheme) in listOf("dark" to dark, "light" to light)) {
            png(390, 260, scheme, File(out, "${name}_${mode}_390x260.png")) {
                DjMixCanvas(data, Modifier.fillMaxSize().padding(8.dp), animatePlayhead = false)
            }
            png(800, 400, scheme, File(out, "${name}_${mode}_800x400.png")) {
                DjMixCanvas(data, Modifier.fillMaxSize().padding(8.dp), animatePlayhead = false)
            }
            png(390, 420, scheme, File(out, "${name}_${mode}_card_390.png")) {
                DjMixCardContent(data, Modifier.width(390.dp).padding(8.dp), animatePlayhead = false, canvasHeight = 230.dp)
            }
        }
        println("MIXVIEW rendered ${scenes.size} scenes to $out")
    }
}
