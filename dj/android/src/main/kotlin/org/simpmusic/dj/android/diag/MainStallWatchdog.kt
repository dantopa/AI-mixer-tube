package org.simpmusic.dj.android.diag

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import org.simpmusic.dj.android.log.DjLog

/** Which build of the DJ code this is, printed at boot so a pasted log says what it came from. Bump it with every shipped change. */
object DjBuild {
    const val ID = "2026-10-01-j drift-20ms"
}

/**
 * Finds out WHY the app feels laggy instead of guessing: a heartbeat posted to the main thread every 40 ms, and a background
 * thread that notices when the heartbeat is late. The first time a stall passes [STALL_MS] it logs what the main thread is
 * doing right then (top frames of its stack), and when it recovers it logs how long it lasted. Any code blocking the UI shows
 * up, not only the DJ's. Costs one message on the main looper every 40 ms.
 */
object MainStallWatchdog {
    private const val BEAT_MS = 40L
    private const val STALL_MS = 150L
    private const val MAX_REPORTS_PER_MINUTE = 30
    private const val TAG = "stall"

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var lastBeat = 0L

    @Volatile private var running = false

    private val beat =
        object : Runnable {
            override fun run() {
                lastBeat = SystemClock.uptimeMillis()
                if (running) main.postDelayed(this, BEAT_MS)
            }
        }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        lastBeat = SystemClock.uptimeMillis()
        main.post(beat)
        Thread({ watch() }, "dj-stall-watch").apply {
            isDaemon = true
            start()
        }
        DjLog.i(TAG, "main-thread stall watchdog on: reports a UI freeze over $STALL_MS ms with the code that was running")
    }

    @Synchronized
    fun stop() {
        running = false
        main.removeCallbacks(beat)
    }

    private fun watch() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        var stalledSince = 0L
        var secondSampleTaken = false
        var windowStart = SystemClock.uptimeMillis()
        var reports = 0
        while (running) {
            try {
                Thread.sleep(25)
            } catch (_: InterruptedException) {
                return
            }
            val now = SystemClock.uptimeMillis()
            if (now - windowStart > 60_000L) { windowStart = now; reports = 0 }
            val late = now - lastBeat
            if (late > STALL_MS) {
                if (stalledSince == 0L) {
                    stalledSince = lastBeat
                    secondSampleTaken = false
                    if (reports++ < MAX_REPORTS_PER_MINUTE) DjLog.w(TAG, "UI frozen for $late ms and counting, main thread is in: ${mainStack()}")
                } else if (!secondSampleTaken && late > 500L && reports <= MAX_REPORTS_PER_MINUTE) {
                    secondSampleTaken = true
                    DjLog.w(TAG, "UI still frozen after $late ms, main thread is in: ${mainStack()}")
                }
            } else if (stalledSince != 0L) {
                val total = now - stalledSince
                if (reports <= MAX_REPORTS_PER_MINUTE) DjLog.w(TAG, "UI stall over: the main thread was blocked for about $total ms")
                stalledSince = 0L
            }
        }
    }

    private fun mainStack(): String =
        try {
            Looper.getMainLooper().thread.stackTrace
                .take(14)
                .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        } catch (e: Throwable) {
            "unavailable (${e.javaClass.simpleName})"
        }
}
