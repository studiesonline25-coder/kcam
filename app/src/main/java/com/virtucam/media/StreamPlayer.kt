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
 * ExoPlayer wrapper for broadcasting live RTSP/RTMP/SRT streams from OBS.
 * Simplified Version: Listens to the UDP proxy running in the main VirtuCam app.
 * This version contains NO FFmpeg dependencies to prevent crashes in host apps.
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

        handler?.post {
            initializePlayer()
        }
    }

    private fun initializePlayer() {
        if (exoPlayer != null) return

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1500, 5000, 500, 1000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
        val okHttpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)

        val dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(context, okHttpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        exoPlayer = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()

        exoPlayer?.setVideoSurface(outputSurface)

        // Point to the local UDP proxy running in the main app
        val finalUri = Uri.parse("udp://127.0.0.1:9998")
        Log.d(TAG, "StreamPlayer listening to local proxy at $finalUri")

        val mediaSource = MediaItem.Builder()
            .setUri(finalUri)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setMaxPlaybackSpeed(1.05f)
                    .setMinPlaybackSpeed(0.95f)
                    .build()
            )
            .setRequestMetadata(RequestMetadata.Builder().build())
            .build()
            .let { mediaSourceFactory.createMediaSource(it) }

        exoPlayer?.setMediaSource(mediaSource)

        exoPlayer?.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width == 0 || videoSize.height == 0) return
                rawRotation = videoSize.unappliedRotationDegrees
                videoRotation = if (rawRotation == 0) 90 else rawRotation
                val rotated = videoRotation == 90 || videoRotation == 270
                if (rotated && rawRotation != 0) {
                    videoWidth  = videoSize.height
                    videoHeight = videoSize.width
                } else {
                    videoWidth  = videoSize.width
                    videoHeight = videoSize.height
                }
            }

            override fun onRenderedFirstFrame() {
                if (firstFrameFired) return
                firstFrameFired = true
                isPlaying = true
                onFrameAvailable()
                onFirstFrame?.let { callback ->
                    captureFirstFrame { bitmap ->
                        try { callback(bitmap) } catch (_: Exception) {}
                    }
                }
            }

            private var retryCount = 0
            private const val MAX_RETRIES = 5

            override fun onPlayerError(error: PlaybackException) {
                val msg = "Stream error: ${error.message} (code: ${error.errorCodeName})"
                Log.e(TAG, msg)
                
                // If the proxy is still warming up, retry after 1 second
                if (retryCount < MAX_RETRIES && error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED) {
                    retryCount++
                    Log.d(TAG, "Proxy not ready yet, retrying... ($retryCount/$MAX_RETRIES)")
                    handler?.postDelayed({
                        exoPlayer?.prepare()
                        exoPlayer?.play()
                    }, 1500)
                    return
                }

                isPlaying = false
                onStreamError?.invoke(msg)
            }
        })

        exoPlayer?.playWhenReady = true
        exoPlayer?.prepare()
    }

    private fun captureFirstFrame(onBitmap: (Bitmap) -> Unit) {
        handler?.post {
            try {
                val width  = videoWidth.takeIf { it > 0 }  ?: 1280
                val height = videoHeight.takeIf { it > 0 } ?: 720
                val surfaceBitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                onBitmap(surfaceBitmap)
            } catch (e: Exception) {
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
                frame?.let { onBitmap(it) }
            } catch (_: Exception) {
            } finally {
                try { retriever?.release() } catch (_: Exception) {}
            }
        }.start()
    }

    fun stop() {
        handler?.post {
            try {
                isPlaying = false
                exoPlayer?.stop()
                exoPlayer?.release()
                exoPlayer = null
                handlerThread?.quitSafely()
                handlerThread = null
                handler = null
            } catch (_: Exception) {}
        }
    }

    fun updateSurface(newSurface: Surface?) {
        this.outputSurface = newSurface
        handler?.post {
            exoPlayer?.setVideoSurface(newSurface)
        }
    }
}
