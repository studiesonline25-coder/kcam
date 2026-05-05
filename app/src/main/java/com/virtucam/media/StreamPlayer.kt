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
import com.arthenica.ffmpegkit.ReturnCode

/**
 * ExoPlayer wrapper for broadcasting live RTSP/RTMP/SRT streams from OBS.
 * Hardware-decodes the stream directly onto our hijacked OpenGL Surface.
 */
class StreamPlayer(
    private val context: Context,
    private val streamUrl: String,
    private val outputSurface: Surface,
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

        val trimmedUrl = streamUrl.trim()
        var finalUri = Uri.parse(trimmedUrl)

        // Feature: SRT and RTMP Proxy via FFmpeg (higher reliability than platform/ExoPlayer native)
        if (trimmedUrl.startsWith("srt", ignoreCase = true) || trimmedUrl.startsWith("rtmp", ignoreCase = true)) {
            val udpUrl = "udp://127.0.0.1:9998?pkt_size=1316&buffer_size=10000000"
            Log.d(TAG, "Starting FFmpeg proxy for $trimmedUrl")
            
            // Use array-based execution to avoid shell/quote escaping issues
            // Added low_delay flags and increased thread_queue_size to prevent glitching
            val cmd = arrayOf(
                "-fflags", "nobuffer+low_delay",
                "-thread_queue_size", "1024",
                "-i", trimmedUrl, 
                "-c", "copy", 
                "-f", "mpegts", 
                udpUrl
            )
            
            ffmpegSession = FFmpegKit.executeAsync(cmd.joinToString(" "), { session ->
                val state = session.state
                val returnCode = session.returnCode
                Log.d(TAG, "FFmpeg Proxy finished. State: $state, ReturnCode: $returnCode")
                if (returnCode == null || returnCode.value != 0) {
                    Log.e(TAG, "FFmpeg Proxy Error logs: ${session.allLogsAsString}")
                }
            }, { log ->
                Log.v("FFmpegProxy", log.message)
            }, { statistics ->
                // Progress stats if needed
            })
            
            finalUri = Uri.parse(udpUrl)
        }

        val mediaSource = if (finalUri.scheme?.startsWith("rtsp", ignoreCase = true) == true) {
            RtspMediaSource.Factory()
                .setForceUseRtpTcp(useTcp)
                .createMediaSource(MediaItem.fromUri(finalUri))
        } else {
            val builder = MediaItem.Builder()
                .setUri(finalUri)
            
            // For local UDP proxy, explicitly tell ExoPlayer to use MPEG-TS extractor
            if (finalUri.toString().contains("127.0.0.1:9998")) {
                builder.setMimeType(androidx.media3.common.MimeTypes.VIDEO_MP2T)
            }
            
            builder.build().let { mediaSourceFactory.createMediaSource(it) }
        }

        exoPlayer?.setMediaSource(mediaSource)

        exoPlayer?.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width == 0 || videoSize.height == 0) return
                
                rawRotation = videoSize.unappliedRotationDegrees
                videoRotation = rawRotation
                
                // Flawless Aspect Ratio Fix: 
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
}
