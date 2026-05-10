package com.virtucam.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
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
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.DefaultLoadControl

/**
 * Pure Native ExoPlayer Streamer.
 * NO PROXIES. NO FFMPEG. NO NATIVE CRASHES.
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
        private const val TAG = "StreamPlayer"
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

    fun start() {
        handlerThread = HandlerThread("VirtuCam-StreamPlayer")
        handlerThread?.start()
        handler = Handler(handlerThread!!.looper)
        handler?.post { initializePlayer() }
    }

    private fun initializePlayer() {
        if (exoPlayer != null) return

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(500, 2000, 250, 500) // Ultra-low latency for direct RTSP
            .build()

        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(true) // Crucial: allow falling back to software if hardware gives green frames

        exoPlayer = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            .build()

        exoPlayer?.setVideoSurface(outputSurface)

        val mediaItem = MediaItem.fromUri(streamUrl)
        
        // RTSP Configuration for Maximum Compatibility
        val mediaSource = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true) 
            .setDebugLoggingEnabled(true)
            .setTimeoutMs(20000) // Increase timeout for slower connections
            .createMediaSource(mediaItem)

        exoPlayer?.setMediaSource(mediaSource)
        exoPlayer?.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width == 0 || videoSize.height == 0) return
                rawRotation = videoSize.unappliedRotationDegrees
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
                if (firstFrameFired) return
                firstFrameFired = true
                isPlaying = true
                onFrameAvailable()
                onFirstFrame?.let { it(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)) }
            }

            override fun onPlayerError(error: PlaybackException) {
                val msg = "Stream error: ${error.message} (code: ${error.errorCodeName})"
                Log.e(TAG, msg)
                onStreamError?.invoke(msg)
            }
        })

        exoPlayer?.playWhenReady = true
        exoPlayer?.prepare()
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
}
