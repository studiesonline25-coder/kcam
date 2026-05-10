package com.virtucam.media

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.DefaultLoadControl

/**
 * Robust RTSP Streamer for Xiaomi Devices.
 * Forces Google Software Decoder to bypass buggy MediaTek hardware.
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

    private var exoPlayer: ExoPlayer? = null
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
            exoPlayer?.stop()
            exoPlayer?.release()
            exoPlayer = null
            handlerThread?.quitSafely()
        }
    }

    fun updateSurface(newSurface: Surface?) {
        this.outputSurface = newSurface
        handler?.post { exoPlayer?.setVideoSurface(newSurface) }
    }

    private fun initializePlayer() {
        if (exoPlayer != null) return

        Log.d(TAG, "Initializing Player for: $streamUrl")

        // TUNE BUFFER: 1.5s is the sweet spot for stability vs. connection speed.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                1500, // Min buffer (Lowered from 2.5s to speed up connection)
                5000, // Max buffer
                1000, // Buffer for playback
                1500  // Buffer for rebuffering
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // FORCE SOFTWARE DECODER
        val renderersFactory = DefaultRenderersFactory(context).apply {
            setEnableDecoderFallback(true)
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val infos = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                
                // Aggressively prioritize the known-stable Google Software Decoder
                val swDecoder = infos.find { it.name.lowercase() == "c2.android.avc.decoder" }
                if (swDecoder != null) {
                    Log.e(TAG, "FORCING GOOGLE SOFTWARE DECODER: ${swDecoder.name}")
                    return@setMediaCodecSelector listOf(swDecoder)
                }

                val filteredInfos = infos.filter { 
                    val name = it.name.lowercase()
                    !name.contains("mtk") && !name.contains("mediatek") && !name.contains("omx")
                }
                if (filteredInfos.isNotEmpty()) {
                    Log.d(TAG, "SELECTED FILTERED DECODER: ${filteredInfos[0].name}")
                    filteredInfos
                } else {
                    Log.w(TAG, "NO SOFTWARE DECODER FOUND! Using default.")
                    infos
                }
            }
        }

        exoPlayer = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            .build()

        exoPlayer?.setVideoSurface(outputSurface)

        val mediaSource = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .setDebugLoggingEnabled(true) // THIS ENABLES INTERNAL RTSP LOGS
            .setTimeoutMs(20000)
            .createMediaSource(MediaItem.fromUri(streamUrl))

        exoPlayer?.setMediaSource(mediaSource)
        exoPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val stateName = when(playbackState) {
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    Player.STATE_IDLE -> "IDLE"
                    else -> "UNKNOWN"
                }
                Log.d(TAG, "Player State Changed: $stateName")
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                Log.d(TAG, "Stream Resolution: ${videoSize.width}x${videoSize.height}")
                if (videoSize.width == 0 || videoSize.height == 0) return
                rawRotation = videoSize.unappliedRotationDegrees
                // Default to 90 if rawRotation is 0 to match Xiaomi sensor behavior
                videoRotation = if (rawRotation == 0) 90 else rawRotation
                
                val rotated = videoRotation == 90 || videoRotation == 270
                if (rotated && rawRotation != 0) {
                    videoWidth = videoSize.height
                    videoHeight = videoSize.width
                } else {
                    videoWidth = videoSize.width
                    videoHeight = videoSize.height
                }
            }

            override fun onRenderedFirstFrame() {
                Log.e(TAG, "!!! FIRST FRAME RENDERED !!! RTSP PIPELINE ACTIVE")
                if (firstFrameFired) return
                firstFrameFired = true
                isPlaying = true
                
                // RELEASE THE GUARD: Signal to FormatConverterBridge that we have real data
                com.virtucam.hooks.CameraHook.isStreamActive = true
                
                // Signal first frame to bridge and UI
                onFrameAvailable()
                onFirstFrame?.let { it(android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)) }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "ExoPlayer Error: ${error.message} (code: ${error.errorCodeName})")
                if (retryCount < MAX_RETRIES && error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED) {
                    retryCount++
                    handler?.postDelayed({
                        exoPlayer?.prepare()
                        exoPlayer?.play()
                    }, 2000)
                    return
                }
                onStreamError?.invoke(error.message ?: "Unknown Error")
            }
        })

        exoPlayer?.playWhenReady = true
        exoPlayer?.prepare()
    }
}
