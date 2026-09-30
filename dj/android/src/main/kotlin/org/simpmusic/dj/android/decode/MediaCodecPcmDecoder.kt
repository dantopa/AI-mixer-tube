package org.simpmusic.dj.android.decode

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
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
        try {
            try {
                input.applyTo(extractor)
            } catch (e: Exception) {
                throw DecodeException("cannot open audio source ${input.describe()}: ${e.message}", e)
            }
            val trackIndex =
                (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: throw DecodeException("no audio track in ${input.describe()}")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!

            val startUs = request.startMs * 1000L
            val endUs = if (request.endMs == Long.MAX_VALUE) Long.MAX_VALUE else request.endMs * 1000L
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            pump(extractor, codec, request, startUs, endUs, sink, cancel)
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

        val emit: (FloatArray, Int) -> Unit = { buf, n -> sink.write(buf, n) }

        while (!outputDone) {
            if (cancel.isCancelled()) throw java.util.concurrent.CancellationException("decode cancelled")

            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    val t = extractor.sampleTime
                    if (size < 0 || (endUs != Long.MAX_VALUE && t > endUs + PAD_US)) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, t, 0)
                        extractor.advance()
                    }
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
                }

                outIdx >= 0 -> {
                    stalled = 0
                    val out = codec.getOutputBuffer(outIdx)!!
                    if (info.size > 0 && inRate > 0) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        out.order(ByteOrder.nativeOrder())
                        val bytesPerSample = if (floatPcm) 4 else 2
                        val frames = info.size / (bytesPerSample * inCh)
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
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }

                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // With input exhausted and no output for a long time the codec is wedged.
                    if (inputDone && ++stalled > STALL_LIMIT) throw DecodeException("codec stalled at end of stream")
                }
            }
        }
        resampler?.finish(emit)
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val PAD_US = 200_000L
        const val STALL_LIMIT = 300 // * TIMEOUT_US = 3 s
    }
}
