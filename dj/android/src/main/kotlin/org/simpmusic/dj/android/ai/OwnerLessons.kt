package org.simpmusic.dj.android.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.simpmusic.dj.ai.MixBrief
import org.simpmusic.dj.android.log.DjLog
import java.io.File

/**
 * "Learn from my ratings": the closest thing to training this app can do. Claude's weights cannot be retrained from a
 * phone, so the owner's 👍 / 👎 are distilled ONCE (one question over every rated mix) into a short list of the owner's own
 * rules, kept on the phone (`filesDir/dj/ai_lessons.json`) and appended to the system prompt of every mix question. The
 * system prompt is cached, so the lessons cost a tenth of their tokens on every question after the first in 5 minutes.
 * Pressed again later, it re-learns from all the ratings (the old lessons are passed in, so nothing learned is lost).
 */
object OwnerLessons {
    @Serializable
    data class Lessons(val lessons: List<String>, val summary: String, val fromRatings: Int, val atEpochMs: Long, val model: String)

    private const val TAG = "ai-lessons"
    private const val MAX_LESSONS = 15
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private var file: File? = null

    private val _state = kotlinx.coroutines.flow.MutableStateFlow<Lessons?>(null)

    /** The lessons learned so far (null before the first "learn"), for the settings row. */
    val state: kotlinx.coroutines.flow.StateFlow<Lessons?> = _state

    var current: Lessons?
        get() = _state.value
        private set(v) {
            _state.value = v
        }

    @Synchronized
    fun install(dir: File) {
        if (file != null) return
        dir.mkdirs()
        val f = File(dir, "ai_lessons.json")
        file = f
        current = runCatching { if (f.exists()) json.decodeFromString(Lessons.serializer(), f.readText()) else null }.getOrNull()
        current?.let { DjLog.i(TAG, "${it.lessons.size} lessons from ${it.fromRatings} ratings") }
    }

    /** Appended to the mix system prompt; empty when nothing was learned yet. */
    fun block(): String {
        val l = current ?: return ""
        if (l.lessons.isEmpty()) return ""
        return buildString {
            append("\n\nTHE OWNER'S LESSONS, learned from ").append(l.fromRatings).append(" of their ratings (follow them; ")
            append("the ratings in the brief are the evidence, these are the rules):\n")
            l.lessons.forEachIndexed { i, s -> append(i + 1).append(". ").append(s).append('\n') }
        }
    }

    val SCHEMA: Map<String, Any> by lazy {
        val props =
            mapOf(
                "lessons" to mapOf(
                    "type" to "array", "items" to mapOf("type" to "string"),
                    "description" to "At most $MAX_LESSONS short, concrete rules for choosing where and how to mix, in the owner's taste.",
                ),
                "summary" to mapOf("type" to "string", "description" to "One sentence: what this owner wants from a mix."),
            )
        mapOf("type" to "object", "properties" to props, "required" to props.keys.toList(), "additionalProperties" to false)
    }

    val SYSTEM_PROMPT: String =
        """
        You coach the AI DJ of a music app that mixes cumbia and other Latin music. The owner rates mixes with LIKED or
        DISLIKED. Read every rated mix (its numbers, its moves, and the DJ's own why when Claude placed it) and write the
        owner's rules: what makes a mix they like and what makes one they dislike. Each rule is short, concrete and usable
        when choosing an exit, an entry, an overlap length and the moves (bass swap, fades, filters, echo, loops), e.g.
        "overlaps of 11-23 s are liked; 4-bar overlaps sound like a plain fade" or "never let two voices sing together".
        Each rated mix also says what the outgoing was doing at its exit and the incoming at its entry (sung or
        instrumental, a voice just before or soon after, the bass, a 16-bar or 8-bar line, where in the track). Learn the
        STRUCTURE: which shapes of exit and entry the owner likes and which failed, and which trick (overlap length, bass
        swap, fade, filter, echo, loop) worked for which pair of shapes, e.g. "outgoing sung to the end + incoming that
        sings at once: keep it under 4 bars with an early hand-over" or "instrumental after the chorus into the intro, on
        16-bar lines, 16 bars with the bass swap in the middle: liked". Write rules as "when <shape>, do <trick>".
        Mixes whose source is OWNER_EDIT were placed by the owner by hand in the mix editor: they show what the owner
        wants, learn their shape first. The owner's ideal: the incoming's voice starts exactly where the outgoing's voice
        would have come back, the two instrumentals overlapping with the same length.
        Only write rules the ratings support; when a rule rests on one or two mixes, say "tentative". Keep the earlier
        lessons that the ratings still support. Answer with the JSON only.
        """.trimIndent()

    /** The question: all [rated] mixes (newest first) plus the lessons learned so far. Null with fewer than 3 ratings. */
    fun question(rated: List<MixBrief.RatedMix>): String? {
        if (rated.size < 3) return null
        return buildString {
            current?.let { l ->
                append("LESSONS SO FAR (from ").append(l.fromRatings).append(" ratings):\n")
                l.lessons.forEach { append("- ").append(it).append('\n') }
                append('\n')
            }
            append(MixBrief.taste(rated, max = rated.size) ?: "")
            append("\n").append(rated.count { it.liked }).append(" liked, ").append(rated.count { !it.liked }).append(" disliked. Write the owner's rules.")
        }
    }

    /** Claude's [answer] stored as the new lessons. */
    fun store(answer: String, fromRatings: Int, model: String): Result<Lessons> =
        runCatching {
            val o = Json.parseToJsonElement(answer) as JsonObject
            val lessons = (o["lessons"] as? JsonArray ?: error("no lessons")).mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf { s -> s.isNotEmpty() } }.take(MAX_LESSONS)
            require(lessons.isNotEmpty()) { "Claude wrote no lessons" }
            val summary = (o["summary"] as? JsonPrimitive)?.content.orEmpty().trim()
            val l = Lessons(lessons, summary, fromRatings, System.currentTimeMillis(), model)
            current = l
            file?.let { f -> runCatching { f.writeText(json.encodeToString(Lessons.serializer(), l)) } }
            DjLog.i(TAG, "learned ${lessons.size} lessons from $fromRatings ratings: $summary | ${lessons.joinToString(" | ")}")
            l
        }

    @Synchronized
    fun clear() {
        current = null
        file?.delete()
    }
}
