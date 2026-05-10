package com.virtucam.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.alexeyvasilyev.rtsp.client.RtspClient
import java.nio.ByteBuffer

/**
 * Robust RTSP Streamer for Xiaomi Devices.
 * Uses rtsp-client-android and direct MediaCodec to bypass ExoPlayer bugs.
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
        private const val NAL_TYPE_SPS = 7
        private const val NAL_TYPE_PPS = 8
        private const val NAL_TYPE_IDR = 5
    }

    private var rtspClient: RtspClient? = null
    private var mediaCodec: MediaCodec? = null
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

    private var isConfigured = false
    private var firstFrameFired = false
    private var spsData: ByteArray? = null
    private var ppsData: ByteArray? = null

    fun start() {
        handlerThread = HandlerThread("VirtuCam-StreamPlayer")
        handlerThread?.start()
        handler = Handler(handlerThread!!.looper)
        handler?.post { initializePlayer() }
    }

    fun stop() {
        handler?.post {
            isPlaying = false
            rtspClient?.stop()
            rtspClient = null
            
            try {
                mediaCodec?.stop()
                mediaCodec?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing MediaCodec", e)
            }
            mediaCodec = null
            isConfigured = false
            
            handlerThread?.quitSafely()
        }
    }

    fun updateSurface(newSurface: Surface?) {
        this.outputSurface = newSurface
    }

    private fun initializePlayer() {
        if (rtspClient != null) return

        Log.d(TAG, "Initializing RtspClient for: $streamUrl")
        
        // Defaults
        videoWidth = 1080
        videoHeight = 1920
        videoRotation = 90
        rawRotation = 0

        rtspClient = RtspClient.Builder(streamUrl)
            .withConnectTimeout(5000)
            .withReadTimeout(10000)
            .withListener(object : RtspClient.RtspClientListener {
                override fun onRtspConnected(sdpInfo: com.alexeyvasilyev.rtsp.client.data.SdpInfo) {
                    Log.d(TAG, "RTSP Connected! Video codec: ${sdpInfo.videoTrack?.videoCodec}")
                }

                override fun onRtspVideoNalUnitReceived(
                    data: ByteArray, offset: Int, length: Int, timestamp: Long
                ) {
                    if (outputSurface == null) return
                    parseH264Nal(data, offset, length, timestamp)
                }

                override fun onRtspAudioSampleReceived(
                    data: ByteArray, offset: Int, length: Int, timestamp: Long
                ) {
                    // Ignore audio for virtual camera
                }

                override fun onRtspDisconnected() {
                    Log.d(TAG, "RTSP Disconnected")
                    onStreamError?.invoke("Disconnected")
                }

                override fun onRtspFailedToConnect() {
                    Log.e(TAG, "RTSP Failed to connect")
                    onStreamError?.invoke("Failed to connect")
                }

                override fun onRtspFailed(message: String?) {
                    Log.e(TAG, "RTSP Failed: $message")
                    onStreamError?.invoke(message ?: "Unknown RTSP error")
                }
            })
            .build()

        try {
            rtspClient?.start()
            isPlaying = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RtspClient", e)
            onStreamError?.invoke(e.message ?: "Failed to start RtspClient")
        }
    }

    private fun parseH264Nal(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
        if (length < 5) return

        val nalOffset = findStartCode(data, offset, length)
        if (nalOffset < 0) return

        val nalType = data[nalOffset].toInt() and 0x1F

        when (nalType) {
            NAL_TYPE_SPS -> {
                spsData = data.copyOfRange(offset, offset + length)
                Log.d(TAG, "SPS Received")
            }
            NAL_TYPE_PPS -> {
                ppsData = data.copyOfRange(offset, offset + length)
                Log.d(TAG, "PPS Received")
                if (!isConfigured) {
                    handler?.post { initDecoder() }
                }
            }
            NAL_TYPE_IDR -> {
                if (!isConfigured) {
                    handler?.post { initDecoder() }
                }
                feedToDecoder(data, offset, length, timestamp, MediaCodec.BUFFER_FLAG_KEY_FRAME)
            }
            else -> {
                if (isConfigured) {
                    feedToDecoder(data, offset, length, timestamp, 0)
                }
            }
        }
    }

    private fun findStartCode(data: ByteArray, offset: Int, length: Int): Int {
        for (i in offset until offset + length - 4) {
            if (data[i] == 0x00.toByte() && 
                data[i+1] == 0x00.toByte() && 
                data[i+2] == 0x00.toByte() && 
                data[i+3] == 0x01.toByte()) {
                return i + 4
            }
        }
        return -1
    }

    private fun initDecoder() {
        if (isConfigured || outputSurface == null) return
        
        try {
            val mimeType = MediaFormat.MIMETYPE_VIDEO_AVC
            mediaCodec = MediaCodec.createDecoderByType(mimeType).apply {
                val format = MediaFormat.createVideoFormat(mimeType, videoWidth, videoHeight).apply {
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
                    spsData?.let { setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
                    ppsData?.let { setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
                }
                configure(format, outputSurface, null, 0)
                start()
                isConfigured = true
                Log.d(TAG, "MediaCodec initialized natively!")
                
                if (!firstFrameFired) {
                    firstFrameFired = true
                    com.virtucam.hooks.CameraHook.isStreamActive = true
                    onFrameAvailable()
                    onFirstFrame?.invoke(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init MediaCodec", e)
        }
    }

    private fun feedToDecoder(data: ByteArray, offset: Int, length: Int, timestamp: Long, flags: Int) {
        val codec = mediaCodec ?: return
        
        try {
            val inputBufferIndex = codec.dequeueInputBuffer(10000)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: return
                inputBuffer.clear()
                inputBuffer.put(data, offset, length)
                codec.queueInputBuffer(inputBufferIndex, 0, length, timestamp, flags)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            
            while (outputBufferIndex >= 0) {
                codec.releaseOutputBuffer(outputBufferIndex, true) // Render to surface!
                outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            }
            
            if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = codec.outputFormat
                videoWidth = newFormat.getInteger(MediaFormat.KEY_WIDTH)
                videoHeight = newFormat.getInteger(MediaFormat.KEY_HEIGHT)
                Log.d(TAG, "MediaCodec format changed: \${videoWidth}x\${videoHeight}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decode error: \${e.message}")
        }
    }
}
