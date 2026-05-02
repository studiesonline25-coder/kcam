package com.virtucam.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.FileDescriptor
import java.util.concurrent.locks.LockSupport

/**
 * Hardware-accelerated video player.
 * Uses MediaExtractor and MediaCodec to decode video from a FileDescriptor
 * and push frames to the provided Surface (which is linked to OpenGL).
 * Implements continuous looping with high-performance frame pacing.
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

    companion object {
        private const val TAG = "VideoPlayer"
        // Short timeout: don't block the loop waiting for codec buffers
        private const val INPUT_TIMEOUT_USEC = 0L
        private const val OUTPUT_TIMEOUT_USEC = 5000L // 5ms max wait for output
    }

    private var extractor: MediaExtractor? = null
    private var decoder: MediaCodec? = null

    private var playThread: Thread? = null
    @Volatile
    private var isPlaying = false

    /**
     * Start playback in a background thread
     */
    fun start() {
        if (isPlaying) return
        isPlaying = true

        playThread = Thread {
            try {
                decodeLoop()
            } finally {
                release()
            }
        }.apply {
            name = "VirtuCam-VideoPlayer"
            priority = Thread.MAX_PRIORITY // Elevate priority for smooth decode
            start()
        }
    }

    /**
     * Stop playback
     */
    fun stop() {
        isPlaying = false
        playThread?.interrupt()
        playThread?.join(1000)
    }

    private fun decodeLoop() {
        extractor = MediaExtractor()
        extractor!!.setDataSource(fd)

        var videoTrackIndex = -1
        var format: MediaFormat? = null

        // Find video track
        for (i in 0 until extractor!!.trackCount) {
            val f = extractor!!.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                videoTrackIndex = i
                format = f
                break
            }
        }

        if (videoTrackIndex < 0 || format == null) {
            Log.e(TAG, "No video track found")
            return
        }

        if (format.containsKey(MediaFormat.KEY_WIDTH)) {
            videoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
        }
        if (format.containsKey(MediaFormat.KEY_HEIGHT)) {
            videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
        }
        
        // Correct dimensions if the video has an EXIF rotation metadata
        if (format.containsKey(MediaFormat.KEY_ROTATION)) {
            videoRotation = format.getInteger(MediaFormat.KEY_ROTATION)
            if (videoRotation == 90 || videoRotation == 270) {
                val temp = videoWidth
                videoWidth = videoHeight
                videoHeight = temp
                Log.d(TAG, "Swapped dimensions because EXIF rotation is $videoRotation. New size: ${videoWidth}x${videoHeight}")
            }
        }

        // Extract framerate for fallback pacing
        val frameRate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            format.getInteger(MediaFormat.KEY_FRAME_RATE).coerceIn(1, 120)
        } else {
            30 // Default to 30fps
        }
        val frameDurationNs = 1_000_000_000L / frameRate
        Log.d(TAG, "Video: ${videoWidth}x${videoHeight} @ ${frameRate}fps (frameDuration=${frameDurationNs/1_000_000}ms)")

        extractor!!.selectTrack(videoTrackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        decoder = MediaCodec.createDecoderByType(mime)
        
        // Configure decoder to output to our Surface
        decoder!!.configure(format, outputSurface, null, 0)
        decoder!!.start()

        val info = MediaCodec.BufferInfo()
        var isEOS = false
        // Use nanoTime for precise pacing (System.currentTimeMillis has ~10ms granularity)
        var startNs = System.nanoTime()
        var lastFrameNs = startNs

        while (isPlaying) {
            // === INPUT: Feed data to the decoder (non-blocking) ===
            if (!isEOS) {
                val inIndex = decoder!!.dequeueInputBuffer(INPUT_TIMEOUT_USEC)
                if (inIndex >= 0) {
                    val buffer = decoder!!.getInputBuffer(inIndex)
                    if (buffer != null) {
                        val sampleSize = extractor!!.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            // End of stream, loop back!
                            Log.d(TAG, "Looping video")
                            extractor!!.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                            decoder!!.flush()
                            startNs = System.nanoTime()
                            lastFrameNs = startNs
                        } else {
                            val presentationTimeUs = extractor!!.sampleTime
                            decoder!!.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0)
                            extractor!!.advance()
                        }
                    }
                }
            }

            // === OUTPUT: Retrieve decoded frames ===
            val outIndex = decoder!!.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_USEC)
            when (outIndex) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Log.d(TAG, "Format changed")
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // Codec has no output yet — yield CPU briefly without the 10-15ms
                    // penalty of Thread.sleep(1) on Android
                    Thread.yield()
                }
                else -> {
                    if (outIndex >= 0) {
                        // === PRECISE FRAME PACING ===
                        // Calculate when this frame should be displayed
                        val targetNs = startNs + (info.presentationTimeUs * 1000L)
                        val nowNs = System.nanoTime()
                        val delayNs = targetNs - nowNs

                        if (delayNs > 1_000_000L) {
                            // More than 1ms ahead: use LockSupport for sub-ms precision
                            LockSupport.parkNanos(delayNs)
                        } else if (delayNs > 0) {
                            // Under 1ms: spin-wait for maximum precision
                            while (System.nanoTime() < targetNs && isPlaying) {
                                Thread.yield()
                            }
                        }
                        // If delayNs <= 0, we're behind schedule — render immediately (no drop)

                        val doRender = info.size != 0
                        // Release buffer and render it to surface
                        decoder!!.releaseOutputBuffer(outIndex, doRender)
                        if (doRender) {
                            lastFrameNs = System.nanoTime()
                            onFrameAvailable()
                        }
                    }
                }
            }

            // Handle loop logic in buffer flags (fallback)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                isEOS = true
            }
        }
    }

    private fun release() {
        try {
            decoder?.stop()
            decoder?.release()
        } catch (e: Exception) {
            // Ignore
        }
        decoder = null

        try {
            extractor?.release()
        } catch (e: Exception) {
            // Ignore
        }
        extractor = null
    }
}
