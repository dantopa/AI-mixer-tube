package org.simpmusic.dj.android

import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.simpmusic.dj.android.store.AnalysisStore
import org.simpmusic.dj.model.TrackAnalysis
import java.io.File
import java.nio.file.Files

class AnalysisStoreTest {
    private lateinit var dir: File
    private var now = 1_000L

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("djstore").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun store(maxBytes: Long = Long.MAX_VALUE, maxEntries: Int = 1000) =
        AnalysisStore(dir, maxBytes, maxEntries) { now }

    @Test
    fun roundTrip() {
        val s = store()
        val a = fakeAnalysis("abc")
        s.put(a)
        assertEquals(a, s.get("abc", "fake-1"))
        assertTrue(s.has("abc"))
    }

    @Test
    fun analyzerIdMismatchInvalidatesAndDeletes() {
        val s = store()
        s.put(fakeAnalysis("abc", analyzerId = "old"))
        assertNull(s.get("abc", "new"))
        assertFalse("stale file must be removed", File(dir, "abc.json").exists())
    }

    @Test
    fun schemaVersionMismatchInvalidates() {
        val s = store()
        val stale = fakeAnalysis("abc").copy(schemaVersion = TrackAnalysis.SCHEMA_VERSION + 7)
        File(dir, "abc.json").writeText(Json.encodeToString(TrackAnalysis.serializer(), stale))
        assertNull(s.get("abc"))
        assertFalse(File(dir, "abc.json").exists())
    }

    @Test
    fun corruptFileIsTreatedAsMissing() {
        val s = store()
        File(dir, "abc.json").writeText("{ not json")
        assertNull(s.get("abc"))
        assertFalse(File(dir, "abc.json").exists())
    }

    @Test
    fun noTempFilesLeftBehind() {
        val s = store()
        repeat(5) { s.put(fakeAnalysis("t$it")) }
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun lruTrimmingKeepsRecentlyReadEntries() {
        val s = store(maxEntries = 3)
        for (id in listOf("a", "b", "c")) {
            now += 10
            s.put(fakeAnalysis(id))
        }
        now += 10
        assertNotNull(s.get("a")) // refreshes a
        now += 10
        s.put(fakeAnalysis("d")) // evicts the oldest: b
        assertEquals(3, s.count())
        assertNotNull(s.get("a"))
        assertNull(s.get("b"))
        assertNotNull(s.get("c"))
        assertNotNull(s.get("d"))
    }

    @Test
    fun byteCapTrims() {
        val one = store().also { it.put(fakeAnalysis("x")) }.sizeBytes()
        val s = store(maxBytes = one * 2 + one / 2)
        for (id in listOf("a", "b", "c", "d")) {
            now += 10
            s.put(fakeAnalysis(id))
        }
        assertTrue(s.sizeBytes() <= one * 2 + one / 2)
        assertNotNull(s.get("d"))
    }

    @Test
    fun hostileIdsCannotEscapeTheDirectory() {
        val s = store()
        val evil = "../../etc/passwd"
        s.put(fakeAnalysis(evil))
        assertEquals(1, dir.listFiles()!!.count { it.name.endsWith(".json") })
        assertNotNull(s.get(evil))
        assertTrue(dir.parentFile.listFiles()!!.none { it.name.contains("passwd") })
    }
}
