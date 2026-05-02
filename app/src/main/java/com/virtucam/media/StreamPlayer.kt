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
import androidx.media3.common.MediaItem.RequestMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.DefaultLoadControl

/**
 * ExoPlayer wrapper for broadcasting live RTSP/RTMP streams from OBS.
 * Hardware-decodes the stream directly onto our hijacked OpenGL Surface.
 *
 * RTMP support: Media3 1.5+ uses platform MediaExtractor which handles rtmp://
 * on Android 10+ (API 29+). For older devices, add media3-datasource-okhttp.
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

    var isPlaying: Boolean = false
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

        // 1. LoadControl tuned for LOW latency (start on first keyframe)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs     = */ 1500,
                /* maxBufferMs     = */ 5000,
                /* bufferForPlaybackMs            = */ 500,   // minimal — show ASAP
                /* bufferForPlaybackAfterRebufferMs = */ 1000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // 2. DataSource that handles both RTSP and RTMP/HTTP
        // DefaultDataSource.Factory natively supports rtmp:// on Android 10+ via MediaExtractor.
        val dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(context)

        // 4. MediaSource factory using the data source
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        // 5. Software decoders preferred to avoid hardware surface deadlocks in VMs
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        exoPlayer = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()

        // 4. Set the target OpenGL-backed Surface for rendering
        exoPlayer?.setVideoSurface(outputSurface)

        // 5. Configure the stream
        val trimmedUrl = streamUrl.trim()
        val uri = Uri.parse(trimmedUrl)

        val mediaSource = if (trimmedUrl.startsWith("rtsp", ignoreCase = true)) {
            // RTSP: use dedicated RtspMediaSource for better control
            RtspMediaSource.Factory()
                .setForceUseRtpTcp(useTcp)
                .createMediaSource(MediaItem.fromUri(uri))
        } else {
            // RTMP / HTTP / HTTPS: DefaultDataSource handles rtmp:// on Android 10+.
            MediaItem.Builder()
                .setUri(uri)
                .setRequestMetadata(
                    RequestMetadata.Builder().build()
                )
                .build()
                .let { mediaSourceFactory.createMediaSource(it) }
        }

        exoPlayer?.setMediaSource(mediaSource)

        // 7. Player listeners
        exoPlayer?.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width == 0 || videoSize.height == 0) return
                val rotated = videoSize.unappliedRotationDegrees == 90 ||
                              videoSize.unappliedRotationDegrees == 270
                videoWidth  = if (rotated) videoSize.height else videoSize.width
                videoHeight = if (rotated) videoSize.width  else videoSize.height
                Log.d(TAG, "Stream size: ${videoWidth}x${videoHeight} (unappliedRotation=${videoSize.unappliedRotationDegrees})")
            }

            override fun onRenderedFirstFrame() {
                Log.d(TAG, "First stream frame rendered!")
                isPlaying = true
                onFrameAvailable()
            }

            override fun onPlaybackStateChanged(state: Int) {
                val stateString = when (state) {
                    Player.STATE_IDLE     -> "IDLE"
                    Player.STATE_BUFFERING-> "BUFFERING"
                    Player.STATE_READY    -> "READY"
                    Player.STATE_ENDED    -> "ENDED"
                    else                  -> "UNKNOWN"
                }
                Log.d(TAG, "Playback state: $stateString")
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Stream error: ${error.message} (code: ${error.errorCode})")
                isPlaying = false
            }
        })

        // 8. Start playback
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
                isPlaying = false
            } finally {
                handlerThread?.quitSafely()
                handlerThread = null
                handler = null
            }
        }
    }
}
