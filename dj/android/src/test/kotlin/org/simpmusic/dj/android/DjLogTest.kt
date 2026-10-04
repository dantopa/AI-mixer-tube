package org.simpmusic.dj.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.simpmusic.dj.android.log.DjLevel
import org.simpmusic.dj.android.log.DjLogSink
import org.simpmusic.dj.android.log.DjLogger
import org.simpmusic.dj.android.log.RotatingLogFile
import java.io.File
import java.nio.file.Files

class DjLogTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("djlog").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun logger(capacity: Int = 3000, now: () -> Long = { 1_000L }, sink: DjLogSink? = null, file: RotatingLogFile? = null) =
        DjLogger(capacity = capacity, wallClock = now, startedAtMs = 0L, threadName = { "main" }, sink = sink, file = file)

    @Test
    fun ringKeepsOnlyTheLastLines() {
        val l = logger(capacity = 5)
        repeat(12) { l.log(DjLevel.INFO, "t", "line $it") }
        val lines = l.lines()
        assertEquals(5, lines.size)
        assertTrue(lines.first().endsWith("line 7"))
        assertTrue(lines.last().endsWith("line 11"))
    }

    @Test
    fun headerCarriesWallTimeUptimeThreadTagAndLevel() {
        var t = 5_000L
        val l = logger(now = { t })
        t = 5_042L
        l.log(DjLevel.WARN, "sched", "hello")
        val line = l.lines().single()
        assertTrue("uptime in ms since start: $line", line.contains("+5042ms"))
        assertTrue(line.contains("[main]"))
        assertTrue(line.contains("sched"))
        assertTrue("level letter then message: $line", line.endsWith(" W hello"))
        // MM-dd HH:mm:ss.SSS
        assertTrue(Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d{3} """).containsMatchIn(line))
    }

    @Test
    fun multiLineMessagesAndExceptionsKeepEveryLineTimestamped() {
        val l = logger()
        val cause = IllegalStateException("root cause")
        val e = RuntimeException("boom", cause)
        l.log(DjLevel.ERROR, "codec", "first\nsecond", e)
        val lines = l.lines()
        assertTrue(lines.size >= 5)
        assertTrue(lines.all { Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d{3} \+""").containsMatchIn(it) })
        assertTrue(lines.any { it.contains("java.lang.RuntimeException: boom") })
        assertTrue(lines.any { it.contains("Caused by: java.lang.IllegalStateException: root cause") })
        assertTrue(lines.any { it.contains("    at ") })
    }

    @Test
    fun exceptionFormatLimitsFramesAndCauseChain() {
        val deep = (1..10).fold<Int, Throwable>(RuntimeException("c0")) { acc, i -> RuntimeException("c$i", acc) }
        val out = DjLogger.formatThrowable(deep, maxFrames = 8, causeFrames = 4, maxCauses = 4)
        assertEquals("first line is the exception itself", "java.lang.RuntimeException: c10", out.first())
        assertEquals(4, out.count { it.startsWith("Caused by:") })
        assertTrue("first exception shows at most 8 frames", out.takeWhile { !it.startsWith("Caused by:") }.count { it.startsWith("    at ") } <= 8)
    }

    @Test
    fun logcatMirrorReceivesEveryRecordWithItsLevel() {
        val seen = ArrayList<Pair<DjLevel, String>>()
        val l = logger(sink = { level, line -> seen += level to line })
        l.log(DjLevel.DEBUG, "a", "x")
        l.log(DjLevel.ERROR, "b", "y", RuntimeException("z"))
        assertEquals(DjLevel.DEBUG, seen.first().first)
        assertTrue(seen.drop(1).all { it.first == DjLevel.ERROR })
        assertTrue(seen.size >= 3)
    }

    @Test
    fun fileRotatesAtTheSizeLimitKeepingTwoFiles() {
        val file = RotatingLogFile(File(dir, "dj-debug.log"), maxBytes = 200)
        val l = logger(file = file)
        repeat(40) { l.log(DjLevel.INFO, "t", "line number $it padded padded padded") }
        val main = File(dir, "dj-debug.log")
        val old = File(dir, "dj-debug.log.1")
        assertTrue(main.isFile)
        assertTrue(old.isFile)
        assertTrue("main stays under the limit plus one line", main.length() <= 200 + 120)
        assertFalse("only two files ever exist", File(dir, "dj-debug.log.2").exists())
        assertTrue("the newest line is in the newest file", main.readText().contains("line number 39"))
        assertEquals(listOf(old, main), file.files())
    }

    @Test
    fun clearEmptiesTheRingAndBumpsTheVersion() {
        val l = logger()
        l.log(DjLevel.INFO, "t", "a")
        val v = l.version.value
        l.clear()
        assertTrue(l.lines().isEmpty())
        assertTrue(l.version.value > v)
    }

    @Test
    fun urlsAreReducedToHostItagAndLength() {
        val url = "https://rr1---sn-abc.googlevideo.com/videoplayback?expire=1&itag=251&clen=4123456&mime=audio%2Fwebm&sig=SECRET&n=TOKEN"
        val safe = DjLogger.redactUrl(url)
        assertTrue(safe.contains("host=rr1---sn-abc.googlevideo.com"))
        assertTrue(safe.contains("itag=251"))
        assertTrue(safe.contains("clen=4123456"))
        assertFalse(safe.contains("SECRET"))
        assertFalse(safe.contains("TOKEN"))
        assertFalse(safe.contains("videoplayback"))
        assertEquals("url=none", DjLogger.redactUrl(null))
    }
}
