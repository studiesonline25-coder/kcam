package com.virtucam.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * Robust RTSP Streamer for Xiaomi Devices.
 * Uses native Android MediaPlayer to avoid ExoPlayer RtspMediaSource fragmentation bugs.
 */
class StreamPlayer(
    private val context: Context,
    private val streamUrl: String,
    private var outputSurface: Surface?,
    private val useTcp: Boolean = true,
    private val onFrameAvailable: () -> Unit,
    private val onFirstFrame: ((Bitmap) -> Unit)? = null,
    private val onStreamError: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "VIRTUCAM_RTSP"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    var videoWidth: Int = 0
        private set
    var videoHeight: Int = 0
        private set
    var videoRotation: Int = 0
        private set
    var rawRotation: Int = 0
        private set
    var isPlaying: Boolean = false
        private set

    private var firstFrameFired = false
    private var retryCount = 0
    private val MAX_RETRIES = 5

    fun start() {
        handlerThread = HandlerThread("VirtuCam-StreamPlayer")
        handlerThread?.start()
        handler = Handler(handlerThread!!.looper)
        handler?.post { initializePlayer() }
    }

    fun stop() {
        handler?.post {
            isPlaying = false
            try {
                mediaPlayer?.stop()
            } catch (_: Exception) {}
            try {
                mediaPlayer?.release()
            } catch (_: Exception) {}
            mediaPlayer = null
            handlerThread?.quitSafely()
        }
    }

    fun updateSurface(newSurface: Surface?) {
        this.outputSurface = newSurface
        handler?.post {
            try {
                mediaPlayer?.setSurface(newSurface)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update surface", e)
            }
        }
    }

    private fun initializePlayer() {
        if (mediaPlayer != null) return

        Log.d(TAG, "Initializing native MediaPlayer for: $streamUrl")

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(streamUrl)
                outputSurface?.let { setSurface(it) }

                setOnVideoSizeChangedListener { _, width, height ->
                    Log.d(TAG, "Stream Resolution: ${width}x${height}")
                    if (width == 0 || height == 0) return@setOnVideoSizeChangedListener

                    // Native MediaPlayer does not directly expose raw rotation in the same way.
                    this@StreamPlayer.rawRotation = 0
                    this@StreamPlayer.videoRotation = 90 // Default to 90 for typical portrait camera streams

                    val rotated = videoRotation == 90 || videoRotation == 270
                    if (rotated && rawRotation != 0) {
                        this@StreamPlayer.videoWidth = height
                        this@StreamPlayer.videoHeight = width
                    } else {
                        this@StreamPlayer.videoWidth = width
                        this@StreamPlayer.videoHeight = height
                    }
                }

                setOnInfoListener { _, what, extra ->
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        Log.e(TAG, "!!! FIRST FRAME RENDERED !!! RTSP PIPELINE ACTIVE")
                        if (!firstFrameFired) {
                            firstFrameFired = true
                            this@StreamPlayer.isPlaying = true

                            // RELEASE THE GUARD: Signal to FormatConverterBridge that we have real data
                            com.virtucam.hooks.CameraHook.isStreamActive = true

                            // Signal first frame to bridge and UI
                            onFrameAvailable()
                            onFirstFrame?.invoke(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
                        }
                    }
                    true
                }

                setOnPreparedListener { mp ->
                    Log.d(TAG, "MediaPlayer prepared, starting playback")
                    mp.start()
                }

                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer Error: what=$what extra=$extra")
                    val errorMsg = "Error what=$what extra=$extra"

                    if (retryCount < MAX_RETRIES) {
                        retryCount++
                        Log.e(TAG, "Retrying... ($retryCount/$MAX_RETRIES)")
                        handler?.postDelayed({
                            stop()
                            initializePlayer()
                        }, 2000)
                    } else {
                        Log.e(TAG, "Max retries reached. Stream failed.")
                        onStreamError?.invoke(errorMsg)
                    }
                    true
                }

                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaPlayer", e)
            onStreamError?.invoke(e.message ?: "Unknown init error")
        }
    }
}
