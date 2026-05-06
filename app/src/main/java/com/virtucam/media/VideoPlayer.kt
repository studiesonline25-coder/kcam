package com.virtucam.media

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.FileDescriptor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * Hardware-accelerated video player with separated decode/inject pipeline.
 *
 * FIX 1: Explicitly selects a hardware MediaCodec decoder (skips OMX.google.* / c2.android.*).
 * FIX 2: Decode thread fills an ArrayBlockingQueue(4), inject thread drains it.
 *        They never block each other — decode drops frames if queue is full,
 *        inject waits up to 30ms if queue is empty.
 */
class VideoPlayer(
    private val fd: FileDescriptor,
    private val outputSurface: Surface,
    private val onFrameAvailable: () -> Unit
) {

    var videoWidth: Int = 0
        private set
    var videoHeight: Int = 0
        private set
    var videoRotation: Int = 0
        private set
    var rawRotation: Int = 0
        private set

    companion object {
        private const val TAG = "VideoPlayer"
        private const val INPUT_TIMEOUT_USEC = 0L
        private const val OUTPUT_TIMEOUT_USEC = 5000L
        private const val QUEUE_CAPACITY = 4
    }

    /** Lightweight descriptor passed through the BlockingQueue */
    private data class DecodedFrame(
        val bufferIndex: Int,
        val presentationTimeUs: Long,
        val size: Int,
        val generation: Int
    )

    private var extractor: MediaExtractor? = null
    private var decoder: MediaCodec? = null
    private var decodeThread: Thread? = null
    private var injectThread: Thread? = null

    @Volatile private var isPlaying = false
    @Volatile private var loopGeneration = 0

    private val frameQueue = ArrayBlockingQueue<DecodedFrame>(QUEUE_CAPACITY)
    private val loopStartNs = AtomicLong(0L)

    fun start() {
        if (isPlaying) return
        isPlaying = true
        initDecoder()

        decodeThread = Thread {
            try { decodeLoop() } catch (t: Throwable) { Log.e(TAG, "Decode thread error", t) }
        }.apply { name = "VirtuCam-VP-Decode"; priority = Thread.MAX_PRIORITY; start() }

        injectThread = Thread {
            try { injectLoop() } catch (t: Throwable) { Log.e(TAG, "Inject thread error", t) }
            finally { release() }
        }.apply { name = "VirtuCam-VP-Inject"; priority = Thread.MAX_PRIORITY; start() }
    }

    fun stop() {
        isPlaying = false
        decodeThread?.interrupt()
        injectThread?.interrupt()
        decodeThread?.join(1000)
        injectThread?.join(1000)
    }

    // ── FIX 1: Explicit hardware decoder selection ──────────────────────
    private fun findHardwareDecoder(mime: String): MediaCodec {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in codecList.codecInfos) {
            if (info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue
            val name = info.name
            if (name.startsWith("OMX.google.", true) ||
                name.startsWith("c2.android.", true) ||
                name.contains("sw", true)) continue
            try {
                val codec = MediaCodec.createByCodecName(name)
                Log.d(TAG, "FIX1: Using HARDWARE decoder: $name for $mime")
                return codec
            } catch (e: Exception) {
                Log.w(TAG, "FIX1: Failed to create codec $name: ${e.message}")
            }
        }
        Log.w(TAG, "FIX1: No HW decoder found for $mime, falling back to default")
        return MediaCodec.createDecoderByType(mime)
    }

    private fun initDecoder() {
        extractor = MediaExtractor()
        extractor!!.setDataSource(fd)

        var videoTrackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor!!.trackCount) {
            val f = extractor!!.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) { videoTrackIndex = i; format = f; break }
        }
        if (videoTrackIndex < 0 || format == null) { Log.e(TAG, "No video track found"); return }

        if (format.containsKey(MediaFormat.KEY_WIDTH))  videoWidth  = format.getInteger(MediaFormat.KEY_WIDTH)
        if (format.containsKey(MediaFormat.KEY_HEIGHT)) videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)

        rawRotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
        videoRotation = rawRotation
        if (rawRotation == 0) videoRotation = 90
        if ((videoRotation == 90 || videoRotation == 270) && rawRotation != 0) {
            val tmp = videoWidth; videoWidth = videoHeight; videoHeight = tmp
        }

        val fps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getInteger(MediaFormat.KEY_FRAME_RATE).coerceIn(1,120) else 30
        Log.d(TAG, "Video: ${videoWidth}x${videoHeight} @ ${fps}fps")

        extractor!!.selectTrack(videoTrackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        decoder = findHardwareDecoder(mime)
        decoder!!.configure(format, outputSurface, null, 0)
        decoder!!.start()
        loopStartNs.set(System.nanoTime())
    }

    // ── FIX 2a: Decode thread ──────────────────────────────────────────
    private fun decodeLoop() {
        val info = MediaCodec.BufferInfo()
        while (isPlaying) {
            val inIndex = decoder!!.dequeueInputBuffer(INPUT_TIMEOUT_USEC)
            if (inIndex >= 0) {
                val buf = decoder!!.getInputBuffer(inIndex)
                if (buf != null) {
                    val sz = extractor!!.readSampleData(buf, 0)
                    if (sz < 0) {
                        Log.d(TAG, "Looping video")
                        frameQueue.clear()
                        decoder!!.flush()
                        loopGeneration++
                        loopStartNs.set(System.nanoTime())
                        extractor!!.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    } else {
                        decoder!!.queueInputBuffer(inIndex, 0, sz, extractor!!.sampleTime, 0)
                        extractor!!.advance()
                    }
                }
            }

            val outIndex = decoder!!.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_USEC)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Log.d(TAG, "Format changed")
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Thread.yield()
                outIndex >= 0 && info.size != 0 -> {
                    val frame = DecodedFrame(outIndex, info.presentationTimeUs, info.size, loopGeneration)
                    if (!frameQueue.offer(frame, 5, TimeUnit.MILLISECONDS)) {
                        decoder!!.releaseOutputBuffer(outIndex, false)
                    }
                }
                outIndex >= 0 -> decoder!!.releaseOutputBuffer(outIndex, false)
            }
        }
    }

    // ── FIX 2b: Inject thread ──────────────────────────────────────────
    private fun injectLoop() {
        while (isPlaying) {
            val frame = try { frameQueue.poll(30, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null } ?: continue
            if (frame.generation != loopGeneration) continue

            val startNs = loopStartNs.get()
            val jitterNs = (Math.random() * 6_000_000L - 3_000_000L).toLong()
            val targetNs = startNs + (frame.presentationTimeUs * 1000L) + jitterNs
            val nowNs = System.nanoTime()
            val delayNs = targetNs - nowNs

            if (delayNs > 1_000_000L) LockSupport.parkNanos(delayNs)
            else if (delayNs > 0) while (System.nanoTime() < targetNs && isPlaying) Thread.yield()

            try {
                decoder!!.releaseOutputBuffer(frame.bufferIndex, true)
                onFrameAvailable()
            } catch (_: Exception) {}
        }
    }

    private fun release() {
        try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
        decoder = null
        try { extractor?.release() } catch (_: Exception) {}
        extractor = null
    }
}
