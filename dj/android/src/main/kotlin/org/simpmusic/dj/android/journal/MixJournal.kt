package org.simpmusic.dj.android.journal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.simpmusic.dj.android.log.RotatingLogFile
import org.simpmusic.dj.model.PlanKind
import org.simpmusic.dj.model.PlanMeta
import org.simpmusic.dj.model.TransitionPlan
import org.simpmusic.dj.planner.MixMarks
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** The owner's mix marks of one track as the journal stores them. */
@Serializable
data class JournalMarks(val exitMs: Long? = null, val entryMs: Long? = null, val soloMs: Long? = null) {
    companion object {
        fun of(m: MixMarks?): JournalMarks? = m?.let { JournalMarks(it.exitMs, it.entryMs, it.soloMs) }
    }
}

/**
 * One line of `mixes.jsonl`. [type] is `started` (the plan as it was executed: kind, points, the planner's [meta] and
 * reason, the owner's marks on both tracks), `ended` (the adapter's outcome) or `rated` (the owner's thumbs, +1 / -1).
 * Fields a type does not use are left out of the line.
 */
@Serializable
data class MixRecord(
    val type: String,
    val atEpochMs: Long,
    val mixId: String,
    val fromId: String? = null,
    val toId: String? = null,
    val kind: PlanKind? = null,
    val exitMs: Long? = null,
    val entryMs: Long? = null,
    val overlapMs: Long? = null,
    val mixBpm: Float? = null,
    val meta: PlanMeta? = null,
    val reason: String? = null,
    val fromMarks: JournalMarks? = null,
    val toMarks: JournalMarks? = null,
    val outcome: String? = null,
    val rating: Int? = null,
)

/** The last mix that ended, for the rating prompt. [rating] is +1 / -1 once rated. */
data class LastMix(
    val id: String,
    val fromId: String,
    val toId: String,
    val endedAtEpochMs: Long,
    val rating: Int? = null,
) {
    /** Still open to a rating (the journal accepts one for [MixJournal.RATEABLE_MS] after the mix ends). */
    fun rateableAt(nowMs: Long): Boolean = nowMs - endedAtEpochMs in 0..MixJournal.RATEABLE_MS
}

/**
 * What each mix was and whether the owner liked it: the data a learned ranker needs (stage 2). JSON Lines in
 * `filesDir/dj/mixes.jsonl`, rotated at [MAX_BYTES] to `.1` like the DJ log, written on its own thread. Before [install]
 * (unit tests) every call is a no-op. Never takes the DJ down: a failed write is dropped.
 */
object MixJournal {
    const val FILE_NAME = "mixes.jsonl"
    const val MAX_BYTES = 2_000_000L

    /** A mix can be rated this long after it ended. */
    const val RATEABLE_MS = 10 * 60_000L

    // every optional field defaults to null, so a line carries only what its type uses; unknown keys are read past
    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    @Volatile
    private var file: RotatingLogFile? = null

    private val writer: Executor by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "dj-journal").apply { isDaemon = true } }
    }

    /** Idempotent. */
    @Synchronized
    fun install(dir: File) {
        if (file != null) return
        file = RotatingLogFile(File(dir, FILE_NAME), MAX_BYTES)
    }

    fun started(mixId: String, plan: TransitionPlan, atEpochMs: Long) =
        write(
            MixRecord(
                type = "started", atEpochMs = atEpochMs, mixId = mixId, fromId = plan.fromId, toId = plan.toId, kind = plan.kind,
                exitMs = plan.exitPointMs, entryMs = plan.entryPointMs, overlapMs = plan.overlapMs, mixBpm = plan.mixBpm,
                meta = plan.meta, reason = plan.reason,
                fromMarks = JournalMarks.of(marksOf(plan.fromId)), toMarks = JournalMarks.of(marksOf(plan.toId)),
            ),
        )

    fun ended(mixId: String, outcome: String, atEpochMs: Long) =
        write(MixRecord(type = "ended", atEpochMs = atEpochMs, mixId = mixId, outcome = outcome))

    fun rated(mixId: String, up: Boolean, atEpochMs: Long) =
        write(MixRecord(type = "rated", atEpochMs = atEpochMs, mixId = mixId, rating = if (up) 1 else -1))

    /** One JSON line (no newline). */
    fun encode(r: MixRecord): String = json.encodeToString(MixRecord.serializer(), r)

    fun decode(line: String): MixRecord = json.decodeFromString(MixRecord.serializer(), line)

    /** The two files on disk (older first), for the export. */
    fun files(): List<File> = file?.files().orEmpty()

    private fun marksOf(id: String): MixMarks? = try { MixMarks.of(id) } catch (_: Exception) { null }

    private fun write(r: MixRecord) {
        val f = file ?: return
        val line = try { encode(r) } catch (_: Exception) { return }
        try {
            writer.execute { f.append(line) }
        } catch (_: Exception) {
            // a full / shut-down executor: the record is dropped, the mix is not affected
        }
    }
}
