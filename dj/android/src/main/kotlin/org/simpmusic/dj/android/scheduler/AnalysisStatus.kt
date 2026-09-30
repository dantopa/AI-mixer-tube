package org.simpmusic.dj.android.scheduler

/** Why queued analysis work is held back by the device (not by the track). */
enum class BlockReason { BATTERY_SAVER, LOW_BATTERY }

/**
 * What is happening to the analysis of ONE track, in terms the settings screen can turn into a short sentence.
 * Typed (not a string) so the UI can translate it; [Failed.message] is the raw exception text, which is the one thing
 * that has to reach the screen untranslated.
 */
sealed interface AnalysisStatus {
    /** A fresh analysis is stored. */
    data object Analysed : AnalysisStatus

    /** Nobody asked for it yet. */
    data object NotRequested : AnalysisStatus

    /** Waiting its turn behind other analyses. */
    data object Queued : AnalysisStatus

    data class Analysing(val seconds: Int) : AnalysisStatus

    /** Queued, but the device conditions do not allow analysis right now. */
    data class Blocked(val reason: BlockReason) : AnalysisStatus

    /**
     * The audio is not fully cached and fetching it is not allowed. [metered] tells which rule forbids it: the mobile-data
     * switch (true), or nothing but an incomplete cache on an allowed connection (false).
     */
    data class NeedsNetwork(val metered: Boolean) : AnalysisStatus

    /** A failure is waiting out its back-off. */
    data class Retrying(val inSeconds: Int, val lastError: String?) : AnalysisStatus

    /** Given up for now (remembered for a few minutes). */
    data class Failed(val message: String?) : AnalysisStatus

    /** No analyzer is installed in this build. */
    data object AnalyzerMissing : AnalysisStatus
}
