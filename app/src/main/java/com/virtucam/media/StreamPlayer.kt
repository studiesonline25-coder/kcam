package com.virtucam.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.alexvas.rtsp.RtspClient
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-Performance RTSP Streamer for Xiaomi Devices.
 * Restores Hardware Acceleration and optimizes for 60fps throughput.
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
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
        
        // H.264 NAL Types
        private const val NAL_SPS = 7
        private const val NAL_PPS = 8
        private const val NAL_IDR = 5
    }

    private var rtspClient: RtspClient? = null
    private var mediaCodec: MediaCodec? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private val exitFlag = AtomicBoolean(false)

    var videoWidth: Int = 1280
        private set
    var videoHeight: Int = 720
        private set
    var videoRotation: Int = 90
        private set
    var rawRotation: Int = 0
        private set
    var isPlaying: Boolean = false
        private set

    private var isConfigured = false
    private var firstFrameFired = false
    private var seenKeyframe = false
    private var spsData: ByteArray? = null
    private var ppsData: ByteArray? = null

    fun start() {
        exitFlag.set(false)
        seenKeyframe = false
        firstFrameFired = false
        handlerThread = HandlerThread("VirtuCam-StreamPlayer", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        handlerThread?.start()
        handler = Handler(handlerThread!!.looper)
        handler?.post { initializeAndRun() }
    }

    fun stop() {
        isPlaying = false
        exitFlag.set(true)
        handler?.post {
            try {
                mediaCodec?.stop()
                mediaCodec?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing MediaCodec", e)
            }
            mediaCodec = null
            isConfigured = false
            rtspClient = null
            handlerThread?.quitSafely()
        }
    }

    fun updateSurface(newSurface: Surface?) {
        this.outputSurface = newSurface
    }

    private fun initializeAndRun() {
        Log.d(TAG, "Initializing Hardware RTSP: $streamUrl")
        
        val uri = Uri.parse(streamUrl)
        val host = uri.host ?: "127.0.0.1"
        val port = if (uri.port != -1) uri.port else 554
        
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(host, port), 15000)
            socket.setSoTimeout(10000)
            socket.tcpNoDelay = true
            
            val listener = object : RtspClient.RtspClientListener {
                override fun onRtspConnecting() {}
                override fun onRtspConnected(sdpInfo: RtspClient.SdpInfo) {
                    sdpInfo.videoTrack?.sps?.let { spsData = it }
                    sdpInfo.videoTrack?.pps?.let { ppsData = it }
                }

                override fun onRtspVideoNalUnitReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
                    if (outputSurface == null || exitFlag.get()) return
                    processIncomingNal(data, offset, length, timestamp)
                }

                override fun onRtspAudioSampleReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {}
                override fun onRtspApplicationDataReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {}
                override fun onRtspDisconnecting() {}
                override fun onRtspDisconnected() {}
                override fun onRtspFailedUnauthorized() { onStreamError?.invoke("RTSP Unauthorized") }
                override fun onRtspFailed(message: String?) { onStreamError?.invoke(message ?: "RTSP Error") }
            }

            rtspClient = RtspClient.Builder(socket, streamUrl, exitFlag, listener)
                .requestAudio(false).requestVideo(true).withDebug(false).build()

            isPlaying = true
            rtspClient?.execute()
            
        } catch (e: Exception) {
            Log.e(TAG, "RTSP error", e)
            onStreamError?.invoke(e.message ?: "Connection failed")
        } finally {
            isPlaying = false
            try { socket?.close() } catch (ioe: IOException) {}
        }
    }

    private fun processIncomingNal(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
        if (length < 4) return
        
        val hasStartCode = data[offset] == 0.toByte() && data[offset+1] == 0.toByte() && data[offset+2] == 0.toByte() && data[offset+3] == 1.toByte()
        val nalHeaderOffset = if (hasStartCode) offset + 4 else offset
        val type = (data[nalHeaderOffset].toInt() and 0x1F)

        // Keyframe/Bundle detection
        if (!seenKeyframe && (type == NAL_IDR || (type == NAL_SPS && length > 500))) {
            Log.i(TAG, "IDR detected. Starting decode.")
            seenKeyframe = true
        }

        if (spsData == null && type == NAL_SPS) {
            spsData = data.copyOfRange(nalHeaderOffset, offset + length)
        } else if (ppsData == null && type == NAL_PPS) {
            ppsData = data.copyOfRange(nalHeaderOffset, offset + length)
        }

        if (spsData != null && ppsData != null && !isConfigured) {
            setupMediaCodec()
        }

        if (isConfigured && seenKeyframe) {
            // Re-use wrapInStartCode only if start code is missing
            if (hasStartCode) {
                feedDecoder(data, offset, length, timestamp)
            } else {
                feedDecoder(wrapInStartCode(data, offset, length), 0, length + 4, timestamp)
            }
        }
    }

    private fun setupMediaCodec() {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight)
            spsData?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(wrapInStartCode(it, 0, it.size))) }
            ppsData?.let { format.setByteBuffer("csd-1", ByteBuffer.wrap(wrapInStartCode(it, 0, it.size))) }
            
            // Use Hardware Decoder (much faster)
            mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            
            mediaCodec?.configure(format, outputSurface, null, 0)
            mediaCodec?.start()
            isConfigured = true
            Log.i(TAG, "Hardware Decoder: ${mediaCodec?.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Hardware setup failed", e)
        }
    }

    private fun wrapInStartCode(data: ByteArray, offset: Int, length: Int): ByteArray {
        val b = ByteArray(length + 4)
        System.arraycopy(START_CODE, 0, b, 0, 4)
        System.arraycopy(data, offset, b, 4, length)
        return b
    }

    private fun feedDecoder(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
        val codec = mediaCodec ?: return
        try {
            val inputIndex = codec.dequeueInputBuffer(0) // No blocking
            if (inputIndex >= 0) {
                codec.getInputBuffer(inputIndex)?.apply {
                    clear()
                    put(data, offset, length)
                }
                codec.queueInputBuffer(inputIndex, 0, length, timestamp * 1000, 0)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            while (outputIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    videoWidth = newFormat.getInteger(MediaFormat.KEY_WIDTH)
                    videoHeight = newFormat.getInteger(MediaFormat.KEY_HEIGHT)
                    videoRotation = if (videoWidth > videoHeight) 90 else 0
                    Log.i(TAG, "Format corrected: ${videoWidth}x${videoHeight}")
                } else if (outputIndex >= 0) {
                    codec.releaseOutputBuffer(outputIndex, true)
                    onFrameAvailable()
                    if (!firstFrameFired) {
                        firstFrameFired = true
                        Log.i(TAG, "Stream Live.")
                    }
                }
                outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decoder error", e)
        }
    }
}
