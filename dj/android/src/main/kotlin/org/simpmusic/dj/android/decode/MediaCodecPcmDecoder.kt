package org.simpmusic.dj.android.decode

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import org.simpmusic.dj.android.log.DjLog
import java.io.IOException
import java.nio.ByteOrder

/** Thrown when the source has no decodable audio track or MediaCodec gives up. */
class DecodeException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Cooperative cancellation: checked between codec buffers. */
fun interface CancelSignal {
    fun isCancelled(): Boolean

    companion object {
        val NEVER = CancelSignal { false }
    }
}

/**
 * Streaming decoder: MediaExtractor + MediaCodec -> float PCM in the requested rate/channel layout.
 *
 * - Works for whatever the platform decodes (Opus in WebM, AAC in MP4, ...): no container assumptions.
 * - Memory is bounded by a few codec buffers plus the resampler history; the full track is never held
 *   here (a [PcmSink] decides what to retain).
 * - A ranged decode seeks to the sync sample before `startMs` and trims by presentation time, so the
 *   first frame delivered is the frame at `startMs` to within one sample.
 * - Cancellable between buffers; codec and extractor are always released.
 *
 * Not unit-testable on the JVM (needs the platform codecs): see dj/docs/android.md, "not verified".
 */
class MediaCodecPcmDecoder {
    fun decode(
        input: AudioInput,
        request: DecodeRequest,
        sink: PcmSink,
        cancel: CancelSignal = CancelSignal.NEVER,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val stats = Stats()
        val t0 = System.nanoTime()
        var stage = "open"
        try {
            try {
                input.applyTo(extractor)
            } catch (e: Exception) {
                throw DecodeException("cannot open audio source ${input.describe()}: ${e.message}", e)
            }
            stage = "select-track"
            val mimes = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
            val trackIndex =
                (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: throw DecodeException("no audio track in ${input.describe()} (tracks=$mimes)")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            DjLog.i(
                TAG,
                "extractor opened ${input.describe()} in ${(System.nanoTime() - t0) / 1_000_000} ms: tracks=${extractor.trackCount} $mimes selected=$trackIndex mime=$mime " +
                    "sampleRate=${intOf(format, MediaFormat.KEY_SAMPLE_RATE)} channels=${intOf(format, MediaFormat.KEY_CHANNEL_COUNT)} " +
                    "durationUs=${if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1} " +
                    "range=[${request.startMs},${if (request.endMs == Long.MAX_VALUE) "end" else request.endMs.toString()}) ms out=${request.outSampleRate} Hz x${request.outChannels}",
            )

            val startUs = request.startMs * 1000L
            val endUs = if (request.endMs == Long.MAX_VALUE) Long.MAX_VALUE else request.endMs * 1000L
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            stage = "create-codec"
            codec = MediaCodec.createDecoderByType(mime)
            stage = "configure"
            codec.configure(format, null, null, 0)
            stage = "start"
            codec.start()
            DjLog.d(TAG, "codec started: name=${codec.name} mime=$mime")
            stage = "pump"
            pump(extractor, codec, request, startUs, endUs, sink, cancel, stats)
            DjLog.i(
                TAG,
                "decode finished in ${(System.nanoTime() - t0) / 1_000_000} ms: codec=${codec.name} inputBuffers=${stats.inputBuffers} inputBytes=${stats.inputBytes} " +
                    "pcmFrames(in ${stats.inRate} Hz)=${stats.decodedFrames} resample ${stats.inRate}->${request.outSampleRate} (ratio ${"%.4f".format(request.outSampleRate.toDouble() / maxOf(1, stats.inRate))}) " +
                    "sinkSamples=${stats.sinkSamples} floatPcm=${stats.floatPcm}",
            )
        } catch (e: java.util.concurrent.CancellationException) {
            DjLog.d(TAG, "decode cancelled at stage=$stage after ${(System.nanoTime() - t0) / 1_000_000} ms")
            throw e
        } catch (e: Throwable) {
            DjLog.e(
                TAG,
                "decode FAILED at stage=$stage after ${(System.nanoTime() - t0) / 1_000_000} ms (codec=${codec?.let { runCatching { it.name }.getOrNull() }} " +
                    "inputBuffers=${stats.inputBuffers} inputBytes=${stats.inputBytes} decodedFrames=${stats.decodedFrames} outputEos=${stats.outputEos} input=${input.describe()})",
                e,
            )
            throw e
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            extractor.release()
            input.close()
        }
    }

    private fun pump(
        extractor: MediaExtractor,
        codec: MediaCodec,
        request: DecodeRequest,
        startUs: Long,
        endUs: Long,
        sink: PcmSink,
        cancel: CancelSignal,
        stats: Stats,
    ) {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        var inRate = 0
        var inCh = 0
        var floatPcm = false
        var resampler: StreamingResampler? = null

        var scratchIn = FloatArray(0)
        var scratchMix = FloatArray(0)

        var stalled = 0
        var lastProgressNs = System.nanoTime()
        val tPump = System.nanoTime()
        var nextReportS = PROGRESS_EVERY_S

        val emit: (FloatArray, Int) -> Unit = { buf, n ->
            stats.sinkSamples += n
            sink.write(buf, n)
        }

        while (!outputDone) {
            if (cancel.isCancelled()) throw java.util.concurrent.CancellationException("decode cancelled")

            if (!inputDone) {
                // Keep the codec fed: queue every input slot that is free before waiting for output. Feeding ONE packet and then
                // waiting for its output made each of a track's ~10 000 Opus packets pay the full round trip (27 s for a 215 s
                // track on a Pixel 10 Pro, 2026-09-30); with the pipeline full the output is already waiting.
                var fed = 0
                while (!inputDone && fed < MAX_FEED_PER_LOOP) {
                    // Never wait for an input slot: with every slot already queued (the normal state once the pipeline is
                    // full) a 10 ms wait here was paid on EVERY loop turn before the one output below was taken, so the
                    // decoder ran at one 20 ms Opus packet per ~10 ms = 2x real time, even from a local file (device log
                    // 2026-10-01: 60 s of audio in 27 s on dj-render, 1.8x on dj-analysis). Waiting belongs on the output.
                    val inIdx = codec.dequeueInputBuffer(0L)
                    if (inIdx < 0) break
                    val buf = codec.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    val t = extractor.sampleTime
                    if (size < 0 || (endUs != Long.MAX_VALUE && t > endUs + PAD_US)) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, t, 0)
                        stats.inputBuffers++
                        lastProgressNs = System.nanoTime()
                        stats.inputBytes += size
                        extractor.advance()
                    }
                    fed++
                }
            }

            val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    inRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    inCh = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm =
                        f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    resampler = StreamingResampler(inRate, request.outSampleRate, request.outChannels)
                    stats.inRate = inRate
                    stats.floatPcm = floatPcm
                    DjLog.d(TAG, "codec output format: $f -> $inRate Hz x$inCh floatPcm=$floatPcm")
                }

                outIdx >= 0 -> {
                    stalled = 0
                    lastProgressNs = System.nanoTime()
                    val out = codec.getOutputBuffer(outIdx)!!
                    if (info.size > 0 && inRate > 0) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        out.order(ByteOrder.nativeOrder())
                        val bytesPerSample = if (floatPcm) 4 else 2
                        val frames = info.size / (bytesPerSample * inCh)
                        stats.decodedFrames += frames
                        val audioS = stats.decodedFrames / inRate
                        if (audioS >= nextReportS) {
                            val ms = (System.nanoTime() - tPump) / 1_000_000
                            DjLog.i(TAG, "decode progress: ${audioS}s of audio in $ms ms (${"%.1f".format(audioS * 1000.0 / maxOf(1L, ms))}x real time) on ${Thread.currentThread().name} prio=${android.os.Process.getThreadPriority(android.os.Process.myTid())}")
                            nextReportS += PROGRESS_EVERY_S
                        }
                        if (scratchIn.size < frames * inCh) scratchIn = FloatArray(frames * inCh)
                        if (floatPcm) {
                            out.asFloatBuffer().get(scratchIn, 0, frames * inCh)
                        } else {
                            val sb = out.asShortBuffer()
                            for (i in 0 until frames * inCh) scratchIn[i] = sb.get() / 32768f
                        }
                        // Frame clock: pts of this buffer, in frames, from the first delivered buffer.
                        val bufStartUs = info.presentationTimeUs
                        var from = 0
                        var to = frames
                        if (bufStartUs < startUs) {
                            from = (((startUs - bufStartUs) * inRate) / 1_000_000L).toInt().coerceIn(0, frames)
                        }
                        if (endUs != Long.MAX_VALUE) {
                            val limit = (((endUs - bufStartUs) * inRate) / 1_000_000L).toInt()
                            if (limit < to) to = limit.coerceIn(0, frames)
                        }
                        if (to > from) {
                            val n = to - from
                            if (scratchMix.size < n * request.outChannels) scratchMix = FloatArray(n * request.outChannels)
                            val slice = if (from == 0) scratchIn else scratchIn.copyOfRange(from * inCh, to * inCh)
                            ChannelMixer.convert(slice, n, inCh, request.outChannels, scratchMix)
                            resampler!!.process(scratchMix, n, emit)
                        }
                        if (endUs != Long.MAX_VALUE && bufStartUs + (frames * 1_000_000L / inRate) >= endUs) {
                            codec.releaseOutputBuffer(outIdx, false)
                            outputDone = true
                            continue
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                        stats.outputEos = true
                    }
                }

                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // With input exhausted and no output for a long time the codec is wedged.
                    if (inputDone && ++stalled > STALL_LIMIT) throw DecodeException("codec stalled at end of stream")
                    if (System.nanoTime() - lastProgressNs > NO_PROGRESS_NS) {
                        throw DecodeException("codec made no progress for ${NO_PROGRESS_NS / 1_000_000_000L} s (inputDone=$inputDone, in=${stats.inputBuffers} buffers)")
                    }
                }
            }
        }
        resampler?.finish(emit)
    }

    /** Counters for the log; one per [decode] call. */
    private class Stats {
        var inputBuffers = 0
        var inputBytes = 0L
        var decodedFrames = 0L
        var sinkSamples = 0L
        var inRate = 0
        var floatPcm = false
        var outputEos = false
    }

    private fun intOf(f: MediaFormat, key: String): Int = if (f.containsKey(key)) f.getInteger(key) else -1

    private companion object {
        const val TAG = "codec"
        const val TIMEOUT_US = 10_000L

        /** Input packets queued per loop pass (the codec only accepts as many as it has free slots). */
        const val MAX_FEED_PER_LOOP = 8
        const val PROGRESS_EVERY_S = 30L
        const val PAD_US = 200_000L
        const val STALL_LIMIT = 300 // * TIMEOUT_US = 3 s
        const val NO_PROGRESS_NS = 30_000_000_000L
    }
}
