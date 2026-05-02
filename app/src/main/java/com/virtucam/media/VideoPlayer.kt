package com.virtucam.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.FileDescriptor

/**
 * Hardware-accelerated video player.
 * Uses MediaExtractor and MediaCodec to decode video from a FileDescriptor
 * and push frames to the provided Surface (which is linked to OpenGL).
 * Implements continuous looping.
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
        private const val TIMEOUT_USEC = 10000L
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
            start()
        }
    }

    /**
     * Stop playback
     */
    fun stop() {
        isPlaying = false
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
                Log.d(TAG, "Swapped dimensions because EXIF rotation is $videoRotation. New size: \${videoWidth}x\${videoHeight}")
            }
        }

        extractor!!.selectTrack(videoTrackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        decoder = MediaCodec.createDecoderByType(mime)
        
        // Configure decoder to output to our Surface
        decoder!!.configure(format, outputSurface, null, 0)
        decoder!!.start()

        val info = MediaCodec.BufferInfo()
        var isEOS = false
        var startMs = System.currentTimeMillis()

        while (isPlaying) {
            if (!isEOS) {
                val inIndex = decoder!!.dequeueInputBuffer(TIMEOUT_USEC)
                if (inIndex >= 0) {
                    val buffer = decoder!!.getInputBuffer(inIndex)
                    if (buffer != null) {
                        val sampleSize = extractor!!.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            // End of stream, loop back!
                            Log.d(TAG, "Looping video")
                            extractor!!.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                            decoder!!.flush()
                            startMs = System.currentTimeMillis()
                        } else {
                            val presentationTimeUs = extractor!!.sampleTime
                            decoder!!.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0)
                            extractor!!.advance()
                        }
                    }
                }
            }

            val outIndex = decoder!!.dequeueOutputBuffer(info, TIMEOUT_USEC)
            when (outIndex) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Log.d(TAG, "Format changed")
                MediaCodec.INFO_TRY_AGAIN_LATER -> {} // Wait
                else -> {
                    if (outIndex >= 0) {
                        // Sleep to maintain framerate
                        val delayMs = (info.presentationTimeUs / 1000) - (System.currentTimeMillis() - startMs)
                        if (delayMs > 0) {
                            try {
                                Thread.sleep(delayMs)
                            } catch (e: InterruptedException) {
                                break
                            }
                        }

                        val doRender = info.size != 0
                        // Release buffer and render it to surface
                        decoder!!.releaseOutputBuffer(outIndex, doRender)
                        if (doRender) {
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
