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

        // STABILITY BUFFER: Increased to 2.5s to eliminate "green particles" and allow
        // the decoder enough time to synchronize with OBS Keyframes.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                2500, // Min buffer
                5000, // Max buffer
                1000, // Buffer for playback
                1500  // Buffer for rebuffering
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // FORCE SOFTWARE DECODER: Bypass buggy MediaTek (MTK) hardware.
        val renderersFactory = DefaultRenderersFactory(context).apply {
            setEnableDecoderFallback(true)
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val infos = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                
                // AGGRESSIVE FILTER: Explicitly REJECT any MediaTek (mtk) hardware decoders.
                // We only want software decoders (google/android) for this specific device.
                val filteredInfos = infos.filter { 
                    val name = it.name.lowercase()
                    !name.contains("mtk") && !name.contains("mediatek")
                }
                
                if (filteredInfos.isNotEmpty()) {
                    Log.d("StreamPlayer", "Forcing Decoder: ${filteredInfos[0].name}")
                    filteredInfos
                } else {
                    Log.w("StreamPlayer", "No non-MTK decoders found, falling back to default.")
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
            .setDebugLoggingEnabled(true)
            .setTimeoutMs(30000) // Increased to 30s for slow handshakes
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
}
