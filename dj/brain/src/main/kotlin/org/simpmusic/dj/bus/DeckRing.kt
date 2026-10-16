package org.simpmusic.dj.bus

import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * The PCM a player has delivered for its deck, waiting for the bus: interleaved float stereo frames addressed by
 * absolute SOURCE frame index (frame `f` = media time `f / sampleRate` s).
 *
 * One producer thread (the player's audio sink: [write], [flush], [endOfStream], [configure]) and one consumer thread
 * (the bus: [read], [release], [snapshot]); lock-free, no allocation after construction.
 *
 * Addressing: after construction or [flush], the FIRST write's presentation time defines the origin (its first frame
 * is source frame `round(ptsUs * sampleRate / 1e6)`); every later write appends contiguously. Later presentation times
 * are deliberately not used for placement: a sink that retries a partially accepted buffer passes the same buffer
 * (and timestamp) again, so only the first write of a stream says where it starts.
 *
 * Back-pressure: [write] accepts as many frames as there is room for and returns that count; the producer retries the
 * rest later (Media3's `AudioOutput.write` returning false). Room is made by the consumer's [release].
 *
 * Reads of frames that are not there yet return silence and count [underrunFrames]; reads before the readable range
 * (never delivered, released, or before the stream's origin) return silence and count [missingFrames].
 *
 * Concurrency: positions live in a monotonic "stream position" space (total frames ever written), so the producer
 * never rewrites what the consumer is reading in the current stream. A [flush] starts a new stream; its fields are
 * published under a sequence counter (odd while changing), and a reader that saw the counter change discards what it
 * copied, so a read never mixes two streams.
 */
class DeckRing(
    val sampleRate: Int = 48_000,
    val capacityFrames: Int = 12 * sampleRate,
) {
    enum class Encoding { PCM_16, PCM_FLOAT }

    /** Consistent view of the ring for the consumer (reused, so taking one allocates nothing). */
    class View {
        /** Stream generation: changes on every [flush]. */
        var generation = 0
        /** Source frame of the stream's first frame; [UNSET] until the first write after a flush. */
        var origin = UNSET
        /** First readable source frame (frames before it were released or never delivered). */
        var start = 0L
        /** One past the last written source frame. */
        var end = 0L
        /** The producer has signalled the end of the stream: nothing will come after [end]. */
        var endOfStream = false
    }

    init {
        require(sampleRate > 0 && capacityFrames > 0)
    }

    private val buf = FloatArray(capacityFrames * 2)

    // ---- shared state ----
    @Volatile private var seq = 0
    @Volatile private var origin = UNSET
    @Volatile private var streamStartPos = 0L
    @Volatile private var writePos = 0L
    @Volatile private var readPos = 0L
    @Volatile private var eosPos = -1L

    /** Frames read past the end of what was written (not counting a signalled end of stream). Consumer-written. */
    @Volatile var underrunFrames = 0L
        private set

    /** Frames read before the readable range. Consumer-written. */
    @Volatile var missingFrames = 0L
        private set

    // ---- producer-owned input format ----
    private var inChannels = 2
    private var inEncoding = Encoding.PCM_FLOAT

    // =============================================================================================
    // producer

    /**
     * Declares the format of the following writes. The rate must be the bus rate: the player resamples (Media3:
     * `SonicAudioProcessor.setOutputSampleRateHz`) — a deck at another rate would play at the wrong speed and break
     * every source-frame mapping, so it fails loudly here.
     */
    fun configure(sampleRate: Int, channels: Int, encoding: Encoding) {
        require(sampleRate == this.sampleRate) { "deck delivers $sampleRate Hz, the bus runs at ${this.sampleRate} Hz: resample in the player" }
        require(channels >= 1) { "channels: $channels" }
        inChannels = channels
        inEncoding = encoding
    }

    private val bytesPerFrame: Int get() = inChannels * if (inEncoding == Encoding.PCM_16) 2 else 4

    /**
     * Appends the whole frames in [buffer] (from its position, in the buffer's byte order, in the [configure]d format),
     * advancing the buffer's position past the frames accepted. Mono is duplicated to both channels; beyond two channels
     * only the first two are kept. Returns the number of frames accepted (0 when the ring is full).
     */
    fun write(buffer: ByteBuffer, presentationTimeUs: Long): Int {
        val bpf = bytesPerFrame
        val frames = buffer.remaining() / bpf
        if (frames == 0) return 0
        beginStream(presentationTimeUs)
        val n = min(frames, freeFrames())
        if (n == 0) return 0
        val p0 = buffer.position()
        val ch = inChannels
        val wp = writePos
        var slot = (wp % capacityFrames).toInt() * 2
        val wrap = capacityFrames * 2
        if (inEncoding == Encoding.PCM_16) {
            for (j in 0 until n) {
                val b = p0 + j * bpf
                val l = buffer.getShort(b) / 32768f
                val r = if (ch == 1) l else buffer.getShort(b + 2) / 32768f
                buf[slot] = l; buf[slot + 1] = r
                slot += 2
                if (slot == wrap) slot = 0
            }
        } else {
            for (j in 0 until n) {
                val b = p0 + j * bpf
                val l = buffer.getFloat(b)
                val r = if (ch == 1) l else buffer.getFloat(b + 4)
                buf[slot] = l; buf[slot + 1] = r
                slot += 2
                if (slot == wrap) slot = 0
            }
        }
        buffer.position(p0 + n * bpf)
        writePos = wp + n // publishes the frames
        return n
    }

    /** Appends [frames] interleaved float frames of [channels] channels from [samples] at [offset] (see the ByteBuffer form). */
    fun write(samples: FloatArray, offset: Int, frames: Int, channels: Int, presentationTimeUs: Long): Int {
        require(channels >= 1)
        if (frames <= 0) return 0
        beginStream(presentationTimeUs)
        val n = min(frames, freeFrames())
        if (n == 0) return 0
        val wp = writePos
        var slot = (wp % capacityFrames).toInt() * 2
        val wrap = capacityFrames * 2
        for (j in 0 until n) {
            val b = offset + j * channels
            val l = samples[b]
            val r = if (channels == 1) l else samples[b + 1]
            buf[slot] = l; buf[slot + 1] = r
            slot += 2
            if (slot == wrap) slot = 0
        }
        writePos = wp + n
        return n
    }

    /** Frames the producer may write right now. */
    fun freeFrames(): Int {
        val used = writePos - max(readPos, streamStartPos)
        return (capacityFrames - used).toInt().coerceAtLeast(0)
    }

    private fun beginStream(presentationTimeUs: Long) {
        if (origin != UNSET) return
        // the stream's fields were reset by flush(); only the origin is still unknown. A reader that sees UNSET sees no
        // data; one that sees the origin also sees streamStartPos (written before, under the sequence counter).
        origin = Math.floorDiv(presentationTimeUs * sampleRate + 500_000L, 1_000_000L)
    }

    /** Drops everything: the next write starts a new stream (a seek). */
    fun flush() {
        seq++ // odd: changing
        streamStartPos = writePos
        origin = UNSET
        eosPos = -1L
        seq++ // even: stable
    }

    /** Nothing more will be written in this stream (end of track). */
    fun endOfStream() {
        eosPos = writePos
    }

    // =============================================================================================
    // consumer

    /** Fills [v] with a consistent view; false if the ring is between streams (no origin yet, or a flush in progress). */
    fun snapshot(v: View): Boolean {
        while (true) {
            val s1 = seq
            if (s1 and 1 != 0) { Thread.onSpinWait(); continue }
            val o = origin
            val ss = streamStartPos
            val wp = writePos
            val rp = max(readPos, ss)
            val eos = eosPos
            if (seq != s1) continue
            v.generation = s1 ushr 1
            v.origin = o
            if (o == UNSET) {
                v.start = 0L; v.end = 0L; v.endOfStream = false
                return false
            }
            v.start = o + (rp - ss)
            v.end = o + (wp - ss)
            v.endOfStream = eos >= 0 && eos >= wp
            return true
        }
    }

    /** Current stream generation (changes on every flush). */
    val generation: Int get() = seq ushr 1

    /**
     * Copies source frames [sourceFrame, sourceFrame + count) into dstL/dstR from [dstOffset]; silence where the ring
     * has nothing. With [generation] >= 0 only that stream is read (a voice bound to a stream never plays the next
     * one's audio by mistake). Returns how many frames were really there.
     */
    fun read(sourceFrame: Long, count: Int, dstL: FloatArray, dstR: FloatArray, dstOffset: Int, generation: Int = -1): Int {
        val s1 = seq
        val o = origin
        if (s1 and 1 != 0 || o == UNSET || (generation >= 0 && (s1 ushr 1) != generation)) {
            zero(dstL, dstR, dstOffset, count)
            return 0
        }
        val ss = streamStartPos
        val wp = writePos
        val rp = max(readPos, ss)
        val lo = o + (rp - ss)
        val hi = o + (wp - ss)
        val a = sourceFrame.coerceIn(lo, hi)
        val b = (sourceFrame + count).coerceIn(lo, hi)
        // before the range
        val before = (min(a, sourceFrame + count) - sourceFrame).toInt().coerceIn(0, count)
        val got = (b - a).toInt().coerceAtLeast(0)
        var i = 0
        while (i < before) { dstL[dstOffset + i] = 0f; dstR[dstOffset + i] = 0f; i++ }
        if (got > 0) {
            var slot = (((a - o) + ss) % capacityFrames).toInt() * 2
            val wrap = capacityFrames * 2
            for (j in 0 until got) {
                dstL[dstOffset + i] = buf[slot]
                dstR[dstOffset + i] = buf[slot + 1]
                slot += 2
                if (slot == wrap) slot = 0
                i++
            }
        }
        while (i < count) { dstL[dstOffset + i] = 0f; dstR[dstOffset + i] = 0f; i++ }
        if (seq != s1) {
            // flushed while copying: the slots may already hold the next stream
            zero(dstL, dstR, dstOffset, count)
            return 0
        }
        if (before > 0) missingFrames += before
        val after = count - before - got
        if (after > 0 && !(eosPos >= 0 && eosPos >= wp)) underrunFrames += after
        return got
    }

    /** Frames readable contiguously from [sourceFrame] (at most [max]) in [generation] (-1: the current stream). */
    fun contiguousFrames(sourceFrame: Long, max: Int, generation: Int = -1): Int {
        val s1 = seq
        val o = origin
        if (s1 and 1 != 0 || o == UNSET || (generation >= 0 && (s1 ushr 1) != generation)) return 0
        val ss = streamStartPos
        val lo = o + (max(readPos, ss) - ss)
        val hi = o + (writePos - ss)
        if (seq != s1 || sourceFrame < lo || sourceFrame >= hi) return 0
        return min(max.toLong(), hi - sourceFrame).toInt()
    }

    /**
     * The consumer no longer needs source frames before [sourceFrame] (of stream [generation]; -1 = current): the
     * producer may overwrite them. Never moves backwards.
     */
    fun release(sourceFrame: Long, generation: Int = -1) {
        val s1 = seq
        val o = origin
        if (s1 and 1 != 0 || o == UNSET || (generation >= 0 && (s1 ushr 1) != generation)) return
        val ss = streamStartPos
        val wp = writePos
        if (seq != s1) return
        val pos = (ss + (sourceFrame - o)).coerceIn(max(readPos, ss), wp)
        if (pos > readPos) readPos = pos
    }

    /** Counts frames the consumer wanted at rate 1 but the producer had not delivered (a stalled deck). */
    internal fun countStall(frames: Int) {
        if (frames > 0) underrunFrames += frames
    }

    private fun zero(l: FloatArray, r: FloatArray, off: Int, n: Int) {
        for (i in 0 until n) { l[off + i] = 0f; r[off + i] = 0f }
    }

    companion object {
        /** No origin yet (no write since the last flush). */
        const val UNSET = Long.MIN_VALUE
    }
}
