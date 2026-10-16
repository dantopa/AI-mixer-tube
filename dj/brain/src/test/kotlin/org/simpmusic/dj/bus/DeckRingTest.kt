package org.simpmusic.dj.bus

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeckRingTest {
    private val sr = 48_000

    private fun readAll(ring: DeckRing, from: Long, n: Int): Pair<FloatArray, FloatArray> {
        val l = FloatArray(n)
        val r = FloatArray(n)
        ring.read(from, n, l, r, 0)
        return l to r
    }

    @Test
    fun formatsAreConvertedToStereoFloat() {
        // float stereo, little endian
        run {
            val ring = DeckRing(sr, 1000)
            ring.configure(sr, 2, DeckRing.Encoding.PCM_FLOAT)
            val b = ByteBuffer.allocate(4 * 8).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until 4) { b.putFloat(i * 0.1f); b.putFloat(-i * 0.1f) }
            b.flip()
            assertEquals(4, ring.write(b, 0L))
            assertFalse(b.hasRemaining())
            val (l, r) = readAll(ring, 0, 4)
            for (i in 0 until 4) { assertEquals(i * 0.1f, l[i]); assertEquals(-i * 0.1f, r[i]) }
        }
        // 16-bit mono, big endian: duplicated to both channels, s / 32768
        run {
            val ring = DeckRing(sr, 1000)
            ring.configure(sr, 1, DeckRing.Encoding.PCM_16)
            val b = ByteBuffer.allocate(2 * 3).order(ByteOrder.BIG_ENDIAN)
            b.putShort(16384); b.putShort(-32768); b.putShort(1)
            b.flip()
            assertEquals(3, ring.write(b, 0L))
            val (l, r) = readAll(ring, 0, 3)
            assertEquals(listOf(0.5f, -1f, 1f / 32768f), l.toList())
            assertEquals(l.toList(), r.toList())
        }
        // 6 channels: the first two are kept
        run {
            val ring = DeckRing(sr, 1000)
            ring.configure(sr, 6, DeckRing.Encoding.PCM_16)
            val b = ByteBuffer.allocate(2 * 6 * 2).order(ByteOrder.nativeOrder())
            for (f in 0 until 2) for (c in 0 until 6) b.putShort((1000 * (c + 1) + f).toShort())
            b.flip()
            assertEquals(2, ring.write(b, 0L))
            val (l, r) = readAll(ring, 0, 2)
            assertEquals(1000 / 32768f, l[0]); assertEquals(2000 / 32768f, r[0])
            assertEquals(1001 / 32768f, l[1]); assertEquals(2001 / 32768f, r[1])
        }
        // a partial frame at the end of a buffer stays in the buffer
        run {
            val ring = DeckRing(sr, 1000)
            ring.configure(sr, 2, DeckRing.Encoding.PCM_16)
            val b = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
            b.putShort(1); b.putShort(2); b.putShort(3); b.putShort(4); b.putShort(5)
            b.flip()
            assertEquals(2, ring.write(b, 0L))
            assertEquals(2, b.remaining())
        }
    }

    @Test
    fun aDeckAtAnotherRateFailsLoudly() {
        val ring = DeckRing(sr)
        assertFailsWith<IllegalArgumentException> { ring.configure(44_100, 2, DeckRing.Encoding.PCM_16) }
    }

    @Test
    fun theFirstWriteAfterAFlushDefinesTheOrigin() {
        val ring = DeckRing(sr, 10_000)
        val v = DeckRing.View()
        assertFalse(ring.snapshot(v))
        val x = FloatArray(200) { it.toFloat() }
        // 1 s -> frame 48000; later timestamps are ignored (a retried buffer carries its original one)
        assertEquals(50, ring.write(x, 0, 50, 2, 1_000_000L))
        assertEquals(50, ring.write(x, 100, 50, 2, 9_999_999L))
        assertTrue(ring.snapshot(v))
        assertEquals(48_000L, v.origin)
        assertEquals(48_000L, v.start)
        assertEquals(48_100L, v.end)
        val (l, _) = readAll(ring, 48_049, 2)
        assertEquals(98f, l[0]); assertEquals(100f, l[1])
        val g0 = ring.generation
        ring.flush()
        assertEquals(g0 + 1, ring.generation)
        assertFalse(ring.snapshot(v))
        assertEquals(0, ring.read(48_000, 10, FloatArray(10), FloatArray(10), 0))
        // the new stream at 0.5 s; a reader bound to the old one sees silence only
        ring.write(x, 0, 100, 2, 500_000L)
        assertTrue(ring.snapshot(v))
        assertEquals(24_000L, v.origin)
        val l2 = FloatArray(4)
        assertEquals(0, ring.read(24_000, 4, l2, FloatArray(4), 0, generation = g0))
        assertEquals(4, ring.read(24_000, 4, l2, FloatArray(4), 0, generation = g0 + 1))
        assertEquals(listOf(0f, 2f, 4f, 6f), l2.toList())
        // mono float writes are duplicated
        val ring2 = DeckRing(sr, 100)
        ring2.write(floatArrayOf(0.25f, 0.5f), 0, 2, 1, 0L)
        val (a, b) = readAll(ring2, 0, 2)
        assertEquals(listOf(0.25f, 0.5f), a.toList()); assertEquals(a.toList(), b.toList())
    }

    @Test
    fun backPressureAndRelease() {
        val ring = DeckRing(sr, 1000)
        ring.configure(sr, 2, DeckRing.Encoding.PCM_FLOAT)
        val b = ByteBuffer.allocate(1500 * 8).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 1500) { b.putFloat(i.toFloat()); b.putFloat(0f) }
        b.flip()
        assertEquals(1000, ring.write(b, 0L))
        assertEquals(500 * 8, b.remaining())
        assertEquals(0, ring.freeFrames())
        assertEquals(0, ring.write(b, 0L)) // full: the sink retries later
        ring.release(300)
        assertEquals(300, ring.freeFrames())
        assertEquals(300, ring.write(b, 0L)) // the same buffer, the same pts: appended contiguously
        val (l, _) = readAll(ring, 300, 1000)
        for (i in 0 until 1000) assertEquals((300 + i).toFloat(), l[i])
        // released frames are gone (and counted as missing), release never goes back
        val before = ring.missingFrames
        ring.read(299, 1, FloatArray(1), FloatArray(1), 0)
        assertEquals(before + 1, ring.missingFrames)
        ring.release(100)
        assertEquals(0, ring.freeFrames())
    }

    @Test
    fun underrunsAreCountedUntilTheEndOfStream() {
        val ring = DeckRing(sr, 1000)
        ring.write(FloatArray(200), 0, 100, 2, 0L)
        ring.read(90, 20, FloatArray(20), FloatArray(20), 0)
        assertEquals(10L, ring.underrunFrames)
        ring.endOfStream()
        ring.read(95, 20, FloatArray(20), FloatArray(20), 0)
        assertEquals(10L, ring.underrunFrames, "reading past a signalled end of stream is not an underrun")
        val v = DeckRing.View()
        ring.snapshot(v)
        assertTrue(v.endOfStream)
        // before the origin: missing, not underrun
        ring.read(-5, 5, FloatArray(5), FloatArray(5), 0)
        assertEquals(5L, ring.missingFrames)
    }

    @Test
    fun concurrentProducerWithFlushesNeverMixesStreams() {
        // every sample encodes (stream, frame): a read that mixed two streams, or misplaced a frame, shows at once
        val ring = DeckRing(sr, 4096)
        val stop = AtomicBoolean(false)
        val failure = AtomicReference<String?>(null)
        fun value(stream: Int, frame: Long) = (stream % 64) * 200_000f + (frame % 100_000).toFloat() // exact in a float
        val streamOfGeneration = java.util.concurrent.ConcurrentHashMap<Int, Int>()
        val producer = Thread {
            val rnd = Random(1)
            val buf = FloatArray(2 * 2048)
            var stream = 0
            var next = 0L
            var written = 0L
            streamOfGeneration[ring.generation] = stream
            while (!stop.get()) {
                if (written > 20_000 && rnd.nextInt(40) == 0) {
                    stream++
                    next = rnd.nextInt(1_000_000).toLong()
                    written = 0
                    ring.flush()
                    streamOfGeneration[ring.generation] = stream
                }
                val n = 64 + rnd.nextInt(1984)
                for (i in 0 until n) { val v = value(stream, next + i); buf[2 * i] = v; buf[2 * i + 1] = -v }
                var off = 0
                val pts = (next * 1_000_000.0 / sr).let { Math.round(it) }
                while (off < n && !stop.get()) {
                    val got = ring.write(buf, 2 * off, n - off, 2, pts)
                    off += got
                    if (got == 0) Thread.onSpinWait()
                }
                next += n
                written += n
            }
        }
        producer.start()
        val v = DeckRing.View()
        val l = FloatArray(1024)
        val r = FloatArray(1024)
        var gen = -1
        var pos = 0L
        var checked = 0L
        var streams = 0
        val deadline = System.nanoTime() + 1_500_000_000L
        while (System.nanoTime() < deadline && failure.get() == null) {
            if (!ring.snapshot(v)) continue
            if (v.generation != gen) { gen = v.generation; pos = v.origin; streams++ }
            val n = ring.contiguousFrames(pos, 1024, gen)
            if (n == 0) { Thread.onSpinWait(); continue }
            val got = ring.read(pos, n, l, r, 0, gen)
            if (got == 0) continue // flushed in between
            val stream = streamOfGeneration[gen] ?: continue
            for (i in 0 until got) {
                val want = value(stream, pos + i)
                if (l[i] != want || r[i] != -want) { failure.set("stream $stream frame ${pos + i}: ${l[i]} / ${r[i]} instead of $want"); break }
            }
            pos += got
            checked += got
            ring.release(pos, gen)
        }
        stop.set(true)
        producer.join()
        println("ring stress: $checked frames checked over $streams streams")
        assertEquals(null, failure.get())
        assertTrue(checked > 100_000 && streams > 3, "too little exercised: $checked frames, $streams streams")
    }
}
