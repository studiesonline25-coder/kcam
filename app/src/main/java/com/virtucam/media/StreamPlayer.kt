package com.virtucam.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * ExoPlayer wrapper for broadcasting live RTSP/RTMP streams from OBS.
 * Hardware-decodes the stream directly onto our hijacked OpenGL Surface.
 */
class StreamPlayer(
    private val context: Context,
    private val streamUrl: String,
    private val outputSurface: Surface,
    private val useTcp: Boolean = true,
    private val onFrameAvailable: () -> Unit
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

    /**
     * Start connecting to the live stream
     */
    fun start() {
        handlerThread = HandlerThread("VirtuCam-StreamPlayer")
        handlerThread?.start()
        handler = Handler(handlerThread!!.looper)
        
        handler?.post {
            initializePlayer()
        }
    }

    private fun initializePlayer() {
        if (exoPlayer != null) return

        // 1. Build ExoPlayer instance with more resilient buffer settings (balanced for latency and stability)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2000, 8000, 1500, 2000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        exoPlayer = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context))
            .setLoadControl(loadControl)
            .build()
            
        // 2. Set the target OpenGL-backed Surface for rendering
        exoPlayer?.setVideoSurface(outputSurface)

        // 3. Configure the stream (trim to avoid whitespace issues)
        val trimmedUrl = streamUrl.trim()
        val uri = Uri.parse(trimmedUrl)
        
        val mediaSource = if (trimmedUrl.startsWith("rtsp", ignoreCase = true)) {
            // Use RtspMediaSource for explicit RTSP support
            RtspMediaSource.Factory()
                .setForceUseRtpTcp(useTcp) // Configurable: TCP is safer for firewalls, UDP is lower latency
                .createMediaSource(MediaItem.fromUri(uri))
        } else {
            // Default factory for RTMP/HTTP
            DefaultMediaSourceFactory(context).createMediaSource(MediaItem.fromUri(uri))
        }

        exoPlayer?.setMediaSource(mediaSource)

        // 4. Configure listener to trigger rendering frame updates
        exoPlayer?.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                super.onVideoSizeChanged(videoSize)
                if (videoSize.unappliedRotationDegrees == 90 || videoSize.unappliedRotationDegrees == 270) {
                    videoWidth = videoSize.height
                    videoHeight = videoSize.width
                    Log.d(TAG, "Stream Video Size (Rotated): ${videoWidth}x${videoHeight}")
                } else {
                    videoWidth = videoSize.width
                    videoHeight = videoSize.height
                    Log.d(TAG, "Stream Video Size: ${videoWidth}x${videoHeight}")
                }
            }

            override fun onRenderedFirstFrame() {
                super.onRenderedFirstFrame()
                Log.d(TAG, "First stream frame rendered!")
                // Tell the virtual render thread a new frame is available for OpenGL swapping
                onFrameAvailable()
            }
            
            override fun onPlaybackStateChanged(state: Int) {
                val stateString = when (state) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN"
                }
                Log.d(TAG, "Playback state changed: $stateString")
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                super.onPlayerError(error)
                Log.e(TAG, "Stream error: ${error.message} (code: ${error.errorCode})")
            }
        })
        
        // 5. Start playback immediately
        exoPlayer?.playWhenReady = true
        exoPlayer?.prepare()
    }

    /**
     * Stop and release the player
     */
    fun stop() {
        handler?.post {
            try {
                exoPlayer?.stop()
                exoPlayer?.release()
                exoPlayer = null
            } finally {
                handlerThread?.quitSafely()
                handlerThread = null
                handler = null
            }
        }
    }
}
