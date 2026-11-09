package org.simpmusic.dj.timeline

import java.text.Normalizer
import kotlin.math.abs

/**
 * How the DJ looks a song's synced lyrics up by name. A radio track only has what YouTube shows: a video title full of
 * noise ("Bombón Asesino (Video Oficial)", "Artista - Tema | Cumbia 2024") and one artist string ("Q' Lokura, Los Herrera",
 * "Artista ft. Otro"). Lyrics databases store the clean title and their own artist spelling, so an exact lookup misses most
 * of them, and the old "first result within 10 s" took a different song with the same length. [LyricQuery] builds the
 * clean variants to ask with, [LyricMatch] decides whether a found sheet is THIS song.
 */
object LyricQuery {
    /** What to ask a lyrics database for one track. */
    data class Query(
        /** The cleaned title, the first one to ask with. */
        val title: String,
        /** Further title spellings (without the "Artist - " prefix, without the feat. part...), most specific first. */
        val titleVariants: List<String>,
        /** Every artist named (the uploader string split on , & / x y feat.), main one first. */
        val artists: List<String>,
    ) {
        val mainArtist: String get() = artists.firstOrNull().orEmpty()
    }

    // "(Video Oficial)", "[Official Music Video]", "(Lyric Video)", "(Audio)", "(Visualizer)", "(Letra)", "(HD)", "(4K)"...
    private val NOISE_WORDS =
        listOf(
            "video oficial", "videoclip oficial", "video clip oficial", "official video", "official music video",
            "official audio", "audio oficial", "official lyric video", "lyric video", "video lyric", "lyrics", "letra",
            "con letra", "visualizer", "visualiser", "audio", "video", "videoclip", "hd", "hq", "4k", "remastered",
            "remasterizado", "official", "oficial", "music video", "clip oficial", "vídeo oficial", "lyric",
        )
    private val BRACKETS = Regex("""[(\[{【][^)\]}】]*[)\]}】]""")
    private val FEAT = Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
    private val ARTIST_SPLIT = Regex("""\s*(?:,|&|/|\+|\s+x\s+|\s+y\s+|\s+and\s+|\s+e\s+|\s+feat\.?\s+|\s+ft\.?\s+|\s+featuring\s+|\s+con\s+|\s+vs\.?\s+)\s*""", RegexOption.IGNORE_CASE)
    private val PIPE_TAIL = Regex("""\s*[|•·#].*$""")
    private val DASH = Regex("""\s+[-–—]\s+""")
    private val SPACES = Regex("""\s+""")
    private val TOPIC = Regex("""\s+-\s+topic$""", RegexOption.IGNORE_CASE)

    /** The variants of [rawTitle] / [rawArtist] (a YouTube title and uploader / artist string) worth asking with. */
    fun of(rawTitle: String, rawArtist: String): Query {
        val artists =
            rawArtist.replace(TOPIC, "").replace("VEVO", "").split(ARTIST_SPLIT).map { it.trim() }
                .filter { it.isNotEmpty() && !it.contains("Various Artists", ignoreCase = true) }
                .distinctBy { key(it) }
        val variants = LinkedHashSet<String>()
        var t = rawTitle.replace(PIPE_TAIL, "").trim()
        // "Artist - Title": drop the part that names an artist we know (or the left part when the artist is unknown)
        val parts = t.split(DASH)
        if (parts.size >= 2) {
            val left = parts.first()
            val right = parts.drop(1).joinToString(" - ")
            // "Karina - ..." for "La Princesita Karina": the left part may be a short form of the artist
            val leftIsArtist = artists.isEmpty() || artists.any { a -> overlap(key(a), key(left)) >= 0.5 || overlap(key(left), key(a)) >= 0.8 }
            val rightIsArtist = artists.any { a -> overlap(key(a), key(right)) >= 0.8 }
            t = when {
                leftIsArtist -> right
                rightIsArtist -> left
                else -> t
            }
        }
        val noBrackets = t.replace(BRACKETS) { m -> if (isNoise(m.value)) " " else m.value }.squash()
        val noFeat = noBrackets.replace(FEAT, "").squash()
        val bare = noFeat.replace(BRACKETS, " ").squash()
        listOf(bare, noFeat, noBrackets).filter { it.isNotBlank() }.forEach { variants.add(it) }
        val title = variants.firstOrNull() ?: rawTitle.trim()
        return Query(title, variants.toList(), artists)
    }

    private fun isNoise(bracketed: String): Boolean {
        val inner = key(bracketed)
        if (inner.isEmpty()) return true
        if (NOISE_WORDS.any { inner == key(it) }) return true
        // "(Video Oficial 2024)", "(En Vivo)" stays: a live take has its own timing and its own sheet
        return inner.split(' ').all { w -> w.all(Char::isDigit) || NOISE_WORDS.any { key(it) == w } }
    }

    private fun String.squash(): String = replace(SPACES, " ").trim().trim('-', '–', '—', ' ')

    /** Lowercase, accents and punctuation removed, single spaces: "Bombón  Asesino!" -> "bombon asesino". */
    fun key(s: String): String {
        val n = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return n.map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").replace(SPACES, " ").trim()
    }

    /** Share of [a]'s words that are in [b] (0..1); 1 when [a] is empty. */
    internal fun overlap(a: String, b: String): Double {
        val wa = a.split(' ').filter { it.isNotEmpty() }
        if (wa.isEmpty()) return 1.0
        val wb = b.split(' ').filter { it.isNotEmpty() }.toSet()
        return wa.count { it in wb }.toDouble() / wa.size
    }
}

/**
 * Whether a sheet found by name is the song being played. Three signals, in the order a wrong match fails them: the
 * length (a different recording of the same title is seconds off; a different song is usually tens), the title, the
 * artist. A sheet with no timing at all is never a match for the DJ.
 */
object LyricMatch {
    /** Below this a candidate is not this song. */
    const val ACCEPT = 0.6

    /** The length must agree within this to count as the same recording. */
    const val MAX_DURATION_GAP_S = 6.0

    /**
     * A music video is often longer than the album track it carries (an intro, a scene at the end): up to this gap a sheet
     * whose title AND artist agree is still taken, at the bare acceptance score, and [LyricVocals.build] then finds the
     * offset against the audio (it searches 15 s either way) or rejects the sheet.
     */
    const val MAX_VIDEO_GAP_S = 25.0

    data class Candidate(val title: String, val artist: String, val durationS: Double?, val synced: Boolean)

    /** 0..1, or -1 when [c] cannot be this song. [wantedDurationS] null = unknown (then the title and artist must carry it). */
    fun score(c: Candidate, q: LyricQuery.Query, wantedDurationS: Double?): Double {
        if (!c.synced) return -1.0
        val ct = LyricQuery.key(c.title)
        val ca = LyricQuery.key(c.artist)
        val titleScore =
            q.titleVariants.maxOfOrNull { v ->
                val k = LyricQuery.key(v)
                when {
                    k.isEmpty() -> 0.0
                    k == ct -> 1.0
                    // "bombon asesino" vs "bombon asesino en vivo": one contains the other
                    ct.contains(k) || k.contains(ct) -> 0.8
                    else -> minOf(LyricQuery.overlap(k, ct), LyricQuery.overlap(ct, k))
                }
            } ?: 0.0
        if (titleScore < 0.5) return -1.0
        val artistScore =
            if (q.artists.isEmpty()) {
                0.5
            } else {
                q.artists.maxOf { a ->
                    val k = LyricQuery.key(a)
                    maxOf(LyricQuery.overlap(k, ca), if (ca.isNotEmpty() && k.contains(ca)) 1.0 else 0.0)
                }
            }
        val durScore =
            if (wantedDurationS == null || c.durationS == null || wantedDurationS <= 0) {
                null
            } else {
                val gap = abs(wantedDurationS - c.durationS)
                when {
                    gap <= MAX_DURATION_GAP_S -> 1.0 - gap / MAX_DURATION_GAP_S
                    gap <= MAX_VIDEO_GAP_S && titleScore >= 0.8 && artistScore >= 0.8 -> 0.0
                    else -> return -1.0
                }
            }
        return if (durScore == null) {
            // no length to check: the artist must agree for the title to be trusted
            if (artistScore < 0.5) -1.0 else 0.5 * titleScore + 0.5 * artistScore - 0.1
        } else {
            0.35 * titleScore + 0.25 * artistScore + 0.4 * durScore
        }
    }

    /** The best acceptable candidate, or null. */
    fun <T> best(items: List<T>, candidate: (T) -> Candidate, q: LyricQuery.Query, wantedDurationS: Double?): Pair<T, Double>? =
        items.map { it to score(candidate(it), q, wantedDurationS) }.filter { it.second >= ACCEPT }.maxByOrNull { it.second }
}

/** LRC text ("[mm:ss.xx] words", several stamps per line allowed, `<mm:ss.xx>` word stamps of enhanced LRC) as lines. */
object Lrc {
    private val LINE_STAMP = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val WORD_STAMP = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>""")
    private val SPACES = Regex("""\s+""")

    fun parse(text: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (raw in text.lineSequence()) {
            val stamps = ArrayList<Long>()
            var rest = raw.trim()
            while (true) {
                val m = LINE_STAMP.matchAt(rest, 0) ?: break
                stamps.add(ms(m.groupValues[1], m.groupValues[2], m.groupValues[3]))
                rest = rest.substring(m.range.last + 1).trimStart()
            }
            if (stamps.isEmpty()) continue
            val words = WORD_STAMP.findAll(rest).map { ms(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }.toList()
            val words2 = rest.replace(WORD_STAMP, " ").replace(SPACES, " ").trim()
            for (s in stamps) out.add(LyricLine(s, null, if (stamps.size == 1) words else emptyList(), words2))
        }
        return out.sortedBy { it.startMs }
    }

    private fun ms(min: String, sec: String, frac: String): Long {
        val f = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.take(3).toLong()
        }
        return min.toLong() * 60_000 + sec.toLong() * 1000 + f
    }
}
