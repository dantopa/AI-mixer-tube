package org.simpmusic.dj.ai

import org.simpmusic.dj.analysis.SongMap
import org.simpmusic.dj.timeline.TrackTimeline

/**
 * "Order the list with AI": what Claude reads to put the radio's next tracks in the order that mixes best, and how its
 * answer is read back. YouTube's radio picks the style; Claude picks the running order, like a DJ lining up records: every
 * pair should join as a natural continuation (close tempos, keys that sit together, an instrumental end into an intro), and
 * the tracks that cannot join anything go last.
 *
 * Each track is a label (T1, T2, ...) so the answer can only name tracks it was given; [parse] keeps the given labels in
 * Claude's order and appends any it left out in the radio's order.
 */
object SetBrief {
    /** One upcoming track: its [title] ("title - artist"), and its analysis when it has one (else title only). */
    class Track(val id: String, val title: String, val timeline: TrackTimeline?)

    class Brief(val system: String, val user: String, val labels: List<String>, val ids: List<String>)

    /** The answer: [ids] in the new order (every given id once), and Claude's one-line reason. */
    class Order(val ids: List<String>, val why: String)

    val SCHEMA: Map<String, Any> by lazy {
        val props =
            mapOf(
                "order" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "Every label (T1, T2, ...) once, in the order to play them."),
                "why" to mapOf("type" to "string", "description" to "One or two short sentences: the arc of the set and which joins are the best."),
            )
        mapOf("type" to "object", "properties" to props, "required" to props.keys.toList(), "additionalProperties" to false)
    }

    val SYSTEM: String =
        """
        You are the DJ of an app that plays cumbia, Argentine cumbia, cumbia villera and other Latin music from a streaming
        radio. The radio already chose tracks of the same style; you choose the ORDER the upcoming ones play in, so that
        each one joins the next as a natural continuation, like a live band's medley ("enganchado"): the groove never stops,
        the beat lands where it should, and the next song comes in where the ear expects the music to go on.

        For every track you get its title, and when it was analysed: its tempo (bpm), key, length, where it sings and where
        only the band plays (the song map, in seconds: "inst" = instrumental, "voice" = sung), and how loud it is. Use what
        you know about the songs too.

        What makes two tracks join well, most important first:
        1. Tempos within about 6 % (or half / double): the engine bends each deck a little, not more. A jump beyond that
           cannot be beat-matched and is a plain crossfade, so keep those to a minimum and put an off-tempo track where it
           fits a neighbour, or at the end.
        2. Keys that sit together: the same, relative (Am / C), or a fifth apart (Am / Em, C / G).
        3. An outgoing that ends on the band (instrumental, with the bass) into an incoming that starts on the band (an
           intro), so the two instrumentals can overlap; two tracks that both sing to the very edge are harder.
        4. A sensible energy arc: no sudden drops; build up, keep it, let it breathe now and then.
        5. Same style and feel next to each other (the radio already mixed styles; keep similar ones together).

        The first track is playing now and stays first: order only the others. Name every label once. Answer with the JSON
        only.
        """.trimIndent()

    /**
     * The brief: [current] is playing (not reordered), [upcoming] in the radio's order. Null when fewer than 3 tracks are
     * upcoming (nothing worth ordering).
     */
    fun build(current: Track, upcoming: List<Track>, taste: String? = null): Brief? {
        if (upcoming.size < 3) return null
        val labels = upcoming.indices.map { "T${it + 1}" }
        val user =
            buildString {
                append("PLAYING NOW (stays first): ").append(line(current)).append("\n\n")
                append("UPCOMING, in the radio's order:\n")
                upcoming.forEachIndexed { i, t -> append(labels[i]).append(": ").append(line(t)).append('\n') }
                taste?.takeIf { it.isNotBlank() }?.let { append('\n').append(it.trimEnd()).append('\n') }
                append("\nOrder T1..T").append(upcoming.size).append(" so every join is a natural continuation.")
            }
        return Brief(SYSTEM, user, labels, upcoming.map { it.id })
    }

    /** Claude's [json] read against [brief]: unknown labels are dropped, repeats ignored, missing ones appended. */
    fun parse(brief: Brief, json: String): Result<Order> =
        runCatching {
            val o = kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
            val asked =
                (o["order"] as? kotlinx.serialization.json.JsonArray ?: error("no order"))
                    .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.uppercase() }
            val seen = LinkedHashSet<Int>()
            asked.forEach { l -> brief.labels.indexOf(l).takeIf { it >= 0 }?.let { seen += it } }
            require(seen.size >= brief.labels.size / 2) { "the order names only ${seen.size} of ${brief.labels.size} tracks" }
            brief.labels.indices.forEach { seen += it }
            val why = (o["why"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            Order(seen.map { brief.ids[it] }, why)
        }

    private fun line(t: Track): String =
        buildString {
            append(t.title.take(MAX_TITLE))
            val tl = t.timeline
            if (tl == null) {
                append(" | not analysed yet")
                return@buildString
            }
            val a = tl.refined
            tl.bpm?.value?.let { append(" | %.0f bpm".format(java.util.Locale.ROOT, it)) }
            a.key?.let { k -> append(" | key ").append(k.value).append(if (k.confidence < org.simpmusic.dj.model.Trust.KEY) " (unsure)" else "") }
            append(" | ").append(a.durationMs / 60_000).append(':').append("%02d".format(a.durationMs / 1000 % 60))
            if (a.energy.isNotEmpty()) append(" | loud %.2f".format(java.util.Locale.ROOT, a.energy.average()))
            SongMap.describe(a)?.let { append(" | song map: ").append(it.take(MAX_MAP)) }
        }

    private const val MAX_TITLE = 80
    private const val MAX_MAP = 220
}
