package com.virtucam.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import java.nio.ByteBuffer

/**
 * High-Compatibility RTSP Streamer for Xiaomi/Qualcomm Devices.
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

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(500, 1500, 500, 500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = RenderersFactory { eventHandler, videoListener, audioListener, textRendererOutput, metadataOutput ->
            arrayOf(
                XiaomiCompatVideoRenderer(context, MediaCodecSelector.DEFAULT),
                MediaCodecAudioRenderer(context, MediaCodecSelector.DEFAULT, eventHandler, audioListener)
            )
        }

        exoPlayer = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            .build()

        exoPlayer?.setVideoSurface(outputSurface)

        val mediaSource = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .setTimeoutMs(20000)
            .setDebugLoggingEnabled(true)
            .createMediaSource(MediaItem.fromUri(streamUrl))

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

    /**
     * Simplified Renderer that uses the correct Media3 1.5.1 signatures.
     */
    private inner class XiaomiCompatVideoRenderer(
        context: Context,
        mediaCodecSelector: MediaCodecSelector
    ) : MediaCodecVideoRenderer(context, mediaCodecSelector, 0, null, null, -1) {
        
        override fun configureCodec(
            codecInfo: MediaCodecInfo,
            codec: MediaCodec,
            format: Format,
            crypto: android.media.MediaCrypto?,
            codecMaxInputSize: Float
        ) {
            // This is the correct override for Media3 1.5.1
            super.configureCodec(codecInfo, codec, format, crypto, codecMaxInputSize)
        }

        override fun getMediaFormat(
            format: Format,
            codecMimeType: String,
            codecConfiguration: MediaCodecVideoRenderer.CodecMaxInputSize,
            codecOperatingRate: Float,
            deviceNeedsNoPostProcessWorkaround: Boolean,
            tunnelingAudioSessionId: Int
        ): MediaFormat {
            val mediaFormat = super.getMediaFormat(
                format,
                codecMimeType,
                codecConfiguration,
                codecOperatingRate,
                deviceNeedsNoPostProcessWorkaround,
                tunnelingAudioSessionId
            )
            
            // Force NV12 and larger buffer
            mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, 21) // NV12
            val w = if (format.width > 0) format.width else 1920
            val h = if (format.height > 0) format.height else 1080
            mediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, w * h * 2)
            
            Log.d(TAG, "Configuring Xiaomi Decoder: $codecMimeType")
            return mediaFormat
        }
    }
}
