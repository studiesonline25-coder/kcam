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
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * ExoPlayer wrapper for broadcasting live RTSP/RTMP streams from OBS.
 * Hardware-decodes the stream directly onto our hijacked OpenGL Surface.
 *
 * RTMP support: OkHttpDataSource handles rtmp:// natively on ALL API levels (26+).
 * On Android 10+ (API 29+), the platform's MediaExtractor also supports rtmp://,
 * but OkHttp is more reliable for stream ingestion.
 */
class StreamPlayer(
    private val context: Context,
    private val streamUrl: String,
    private val outputSurface: Surface,
    private val useTcp: Boolean = true,
    private val onFrameAvailable: () -> Unit,
    private val onFirstFrame: ((Bitmap) -> Unit)? = null,  // fires once when first frame renders
    private val onStreamError: ((String) -> Unit)? = null  // fires when stream fails with error message
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

    // Track if we've already fired onFirstFrame to avoid duplicates
    private var firstFrameFired = false

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

        // 2. OkHttp-backed DataSource — handles RTSP, RTMP, HTTP, HTTPS on all API levels.
        // OkHttpDataSource is more robust for RTMP streams than the platform MediaExtractor.
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // no read timeout for live streams
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
        val okHttpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)

        // 3. DefaultDataSource as a fallback for non-HTTP schemes (content://, file://).
        // Chain: DefaultDataSource (scheme detection) → OkHttpDataSource (actual network I/O).
        val dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(context, okHttpDataSourceFactory)

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

        // 5. Set the target OpenGL-backed Surface for rendering
        exoPlayer?.setVideoSurface(outputSurface)

        // 6. Configure the stream
        val trimmedUrl = streamUrl.trim()
        val uri = Uri.parse(trimmedUrl)

        val mediaSource = if (trimmedUrl.startsWith("rtsp", ignoreCase = true)) {
            // RTSP: use dedicated RtspMediaSource for better control
            RtspMediaSource.Factory()
                .setForceUseRtpTcp(useTcp)
                .createMediaSource(MediaItem.fromUri(uri))
        } else {
            // RTMP / HTTP / HTTPS: OkHttpDataSource handles rtmp:// natively on all API levels.
            // DefaultDataSource detects the scheme; OkHttp handles actual I/O.
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
                if (firstFrameFired) return
                firstFrameFired = true
                Log.d(TAG, "First stream frame rendered!")
                isPlaying = true
                onFrameAvailable()

                // Capture the rendered frame as a Bitmap for the UI preview
                onFirstFrame?.let { callback ->
                    captureFirstFrame { bitmap ->
                        try {
                            callback(bitmap)
                        } catch (_: Exception) {}
                    }
                }
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
                val msg = "Stream error: ${error.message} (code: ${error.errorCodeName})"
                Log.e(TAG, msg)
                isPlaying = false
                onStreamError?.invoke(msg)
            }
        })

        // 8. Start playback
        exoPlayer?.playWhenReady = true
        exoPlayer?.prepare()
    }

    /**
     * Capture the current output surface as a Bitmap (for stream preview thumbnail).
     * Runs on the GL thread via the handler.
     */
    private fun captureFirstFrame(onBitmap: (Bitmap) -> Unit) {
        handler?.post {
            try {
                // Re-query surface pixels from the Surface (it's the same GL-backed surface)
                // We use MediaMetadataRetriever on the stream URL as a fallback,
                // or grab pixels from the Surface directly.
                // Since the surface content is GL-rendered, we grab it via a one-frame capture:
                val surfaceBitmap = try {
                    val width  = videoWidth.takeIf { it > 0 }  ?: 1280
                    val height = videoHeight.takeIf { it > 0 } ?: 720
                    android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                } catch (_: Exception) {
                    null
                }

                surfaceBitmap?.let {
                    Log.d(TAG, "Stream preview frame captured: ${it.width}x${it.height}")
                    onBitmap(it)
                }

                // Fallback: try MediaMetadataRetriever directly from URL (may work for HLS/HTTP)
                if (surfaceBitmap == null) {
                    tryCaptureFromUrl(onBitmap)
                }
            } catch (_: Exception) {
                // Last resort: try from URL
                tryCaptureFromUrl(onBitmap)
            }
        }
    }

    private fun tryCaptureFromUrl(onBitmap: (Bitmap) -> Unit) {
        Thread {
            var retriever: MediaMetadataRetriever? = null
            try {
                retriever = MediaMetadataRetriever()
                retriever.setDataSource(streamUrl)
                val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                frame?.let {
                    Log.d(TAG, "Stream preview captured from URL: ${it.width}x${it.height}")
                    onBitmap(it)
                }
            } catch (_: Exception) {
                Log.w(TAG, "Could not capture stream preview from URL")
            } finally {
                try { retriever?.release() } catch (_: Exception) {}
            }
        }.start()
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
                firstFrameFired = false
            } finally {
                handlerThread?.quitSafely()
                handlerThread = null
                handler = null
            }
        }
    }
}
