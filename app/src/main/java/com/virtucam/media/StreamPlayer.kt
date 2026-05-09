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
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession

/**
 * ExoPlayer wrapper for broadcasting live RTSP/RTMP/SRT streams from OBS.
 * Hardware-decodes the stream directly onto our hijacked OpenGL Surface.
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
    private var ffmpegSession: FFmpegSession? = null

    companion object {
        private const val TAG = "StreamPlayer"
        private var isNativeLoaded = false

        fun loadNative() {
            if (isNativeLoaded) return
            try {
                // Manually load the core FFmpegKit library only when needed
                System.loadLibrary("ffmpegkit")
                isNativeLoaded = true
                Log.d(TAG, "FFmpeg native libraries loaded successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load FFmpeg native libraries: ${e.message}")
            }
        }
    }

    fun start() {
        loadNative()
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

        val trimmedUrl = streamUrl.trim()
        var finalUri = Uri.parse(trimmedUrl)

        // Feature: SRT and RTMP Proxy via FFmpeg (higher reliability than platform/ExoPlayer native)
        if (trimmedUrl.startsWith("srt", ignoreCase = true) || trimmedUrl.startsWith("rtmp", ignoreCase = true) || trimmedUrl.startsWith("rtsp", ignoreCase = true)) {
            var optimizedUrl = trimmedUrl
            var ffmpegInputArgs = ""
            
            // Handle Protocol-Specific Optimizations
            if (trimmedUrl.startsWith("srt", ignoreCase = true)) {
                // Increase SRT latency to 1000ms (1,000,000 microseconds) for maximum stability on WiFi
                if (!trimmedUrl.contains("latency=")) {
                    val separator = if (trimmedUrl.contains("?")) "&" else "?"
                    optimizedUrl = "$trimmedUrl${separator}latency=1000000"
                }
            } else if (trimmedUrl.startsWith("rtsp", ignoreCase = true)) {
                // Force TCP for RTSP to bypass firewall/UDP issues
                ffmpegInputArgs = "-rtsp_transport tcp "
            } else if (trimmedUrl.startsWith("rtmp", ignoreCase = true)) {
                if (trimmedUrl.contains("0.0.0.0") || trimmedUrl.contains("listen=1")) {
                    ffmpegInputArgs = "-listen 1 "
                    optimizedUrl = optimizedUrl.replace("?listen=1", "").replace("&listen=1", "")
                }
            }

            // Cleanup any existing session before starting a new one to prevent port conflicts
            try {
                ffmpegSession?.cancel()
                ffmpegSession = null
            } catch (_: Exception) {}

            // High-fidelity proxy buffer for all protocols
            val udpUrl = "udp://127.0.0.1:9998?pkt_size=1316&buffer_size=20971520&fifo_size=1000000&overrun_nonfatal=1"
            Log.d(TAG, "Starting FFmpeg proxy for ${optimizedUrl.substringBefore(":")}: $optimizedUrl")
            
            handler?.post {
                try {
                    // Increased probesize and analyzeduration to ensure stream format is detected correctly
                    val command = "$ffmpegInputArgs -probesize 2000000 -analyzeduration 2000000 -flags low_delay -i \"$optimizedUrl\" -c copy -f mpegts \"$udpUrl\""
                    ffmpegSession = FFmpegKit.executeAsync(command) { session ->
                        Log.d(TAG, "FFmpeg Proxy finished with state ${session.state} and return code ${session.returnCode}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start FFmpeg proxy: ${e.message}")
                }
            }
            finalUri = Uri.parse(udpUrl)
        }

        val mediaSource = if (finalUri.scheme?.startsWith("rtsp", ignoreCase = true) == true) {
            RtspMediaSource.Factory()
                .setForceUseRtpTcp(true) // Force TCP for native ExoPlayer RTSP too
                .createMediaSource(MediaItem.fromUri(finalUri))
        } else {
            MediaItem.Builder()
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
        }

        exoPlayer?.setMediaSource(mediaSource)

        exoPlayer?.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width == 0 || videoSize.height == 0) return
                
                rawRotation = videoSize.unappliedRotationDegrees
                videoRotation = rawRotation
                
                // Spoof 0 rotation to 90 for upright streams so CameraHook treats them like recorded videos.
                if (videoRotation == 0) {
                    videoRotation = 90
                }

                val rotated = videoRotation == 90 || videoRotation == 270
                
                // IMPORTANT: Only swap dimensions if it's a physically-sideways video (rawRotation 90/270).
                // If it's physically portrait (rawRotation 0), keep dimensions as-is.
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

            override fun onPlayerError(error: PlaybackException) {
                val msg = "Stream error: ${error.message} (code: ${error.errorCodeName})"
                Log.e(TAG, msg)
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
                ffmpegSession?.cancel()
                ffmpegSession = null
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
