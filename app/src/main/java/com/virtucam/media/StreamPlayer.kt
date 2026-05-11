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
 * Ultra-Robust RTSP Streamer for Xiaomi Devices.
 * Features a Manual Bitstream Splitter to handle bundled NAL units.
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
        private const val NAL_TYPE_SPS = 7
        private const val NAL_TYPE_PPS = 8
        private const val NAL_TYPE_IDR = 5
        private const val NAL_TYPE_SLICE = 1
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
    private var spsData: ByteArray? = null
    private var ppsData: ByteArray? = null

    fun start() {
        exitFlag.set(false)
        firstFrameFired = false
        isConfigured = false
        spsData = null
        ppsData = null
        
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
        Log.d(TAG, "Starting RTSP Deep-Split Mode: $streamUrl")
        
        val uri = Uri.parse(streamUrl)
        val host = uri.host ?: "127.0.0.1"
        val port = if (uri.port != -1) uri.port else 554
        
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(host, port), 15000)
            socket.tcpNoDelay = true
            
            val listener = object : RtspClient.RtspClientListener {
                override fun onRtspConnecting() {}
                override fun onRtspConnected(sdpInfo: RtspClient.SdpInfo) {
                    sdpInfo.videoTrack?.sps?.let { spsData = it }
                    sdpInfo.videoTrack?.pps?.let { ppsData = it }
                }

                override fun onRtspVideoNalUnitReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
                    if (outputSurface == null || exitFlag.get()) return
                    // Hand off to our manual bitstream splitter
                    splitAndProcess(data, offset, length, timestamp)
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
            Log.e(TAG, "RTSP connection failed", e)
            onStreamError?.invoke(e.message ?: "Connection failed")
        } finally {
            isPlaying = false
            try { socket?.close() } catch (ioe: IOException) {}
        }
    }

    private fun splitAndProcess(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
        var pos = offset
        val end = offset + length
        
        while (pos < end) {
            // Find the next start code 00 00 00 01
            val startPos = findStartCode(data, pos, end)
            if (startPos == -1) {
                // If no start code found, but we are at the beginning of a packet from the library,
                // it might be a raw NAL without start code.
                if (pos == offset) {
                    processNal(data, offset, length, timestamp)
                }
                break
            }
            
            // Find the end of this NAL unit (the next start code)
            val nextStart = findStartCode(data, startPos + 4, end)
            val nalLength = if (nextStart == -1) end - startPos else nextStart - startPos
            
            processNal(data, startPos, nalLength, timestamp)
            
            if (nextStart == -1) break
            pos = nextStart
        }
    }

    private fun findStartCode(data: ByteArray, offset: Int, end: Int): Int {
        for (i in offset until end - 4) {
            if (data[i] == 0.toByte() && data[i+1] == 0.toByte() && data[i+2] == 0.toByte() && data[i+3] == 1.toByte()) {
                return i
            }
        }
        return -1
    }

    private fun processNal(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
        if (length < 5) return
        
        // Skip start code if present
        val hasStartCode = data[offset] == 0.toByte() && data[offset+1] == 0.toByte() && data[offset+2] == 0.toByte() && data[offset+3] == 1.toByte()
        val headerPos = if (hasStartCode) offset + 4 else offset
        val nalType = (data[headerPos].toInt() and 0x1F)

        if (nalType == NAL_TYPE_SPS && spsData == null) {
            spsData = data.copyOfRange(headerPos, offset + length)
            Log.d(TAG, "Clean SPS Captured: ${spsData?.size} bytes")
        } else if (nalType == NAL_TYPE_PPS && ppsData == null) {
            ppsData = data.copyOfRange(headerPos, offset + length)
            Log.d(TAG, "Clean PPS Captured: ${ppsData?.size} bytes")
        }

        if (spsData != null && ppsData != null && !isConfigured) {
            setupMediaCodec()
        }

        if (isConfigured) {
            val frame = if (hasStartCode) data.copyOfRange(offset, offset + length) else wrapInStartCode(data, offset, length)
            feedDecoder(frame, timestamp)
        }
    }

    private fun setupMediaCodec() {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight)
            spsData?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(wrapInStartCode(it, 0, it.size))) }
            ppsData?.let { format.setByteBuffer("csd-1", ByteBuffer.wrap(wrapInStartCode(it, 0, it.size))) }
            
            mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec?.configure(format, outputSurface, null, 0)
            mediaCodec?.start()
            isConfigured = true
            Log.i(TAG, "Hardware Decoder Active: ${mediaCodec?.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup MediaCodec", e)
        }
    }

    private fun wrapInStartCode(data: ByteArray, offset: Int, length: Int): ByteArray {
        val b = ByteArray(length + 4)
        System.arraycopy(START_CODE, 0, b, 0, 4)
        System.arraycopy(data, offset, b, 4, length)
        return b
    }

    private fun feedDecoder(data: ByteArray, timestamp: Long) {
        val codec = mediaCodec ?: return
        try {
            val inputIndex = codec.dequeueInputBuffer(0)
            if (inputIndex >= 0) {
                codec.getInputBuffer(inputIndex)?.apply {
                    clear()
                    put(data)
                }
                codec.queueInputBuffer(inputIndex, 0, data.size, timestamp * 1000, 0)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            while (outputIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    videoWidth = newFormat.getInteger(MediaFormat.KEY_WIDTH)
                    videoHeight = newFormat.getInteger(MediaFormat.KEY_HEIGHT)
                    videoRotation = if (videoWidth > videoHeight) 90 else 0
                } else if (outputIndex >= 0) {
                    codec.releaseOutputBuffer(outputIndex, true)
                    onFrameAvailable()
                    if (!firstFrameFired) {
                        firstFrameFired = true
                        Log.i(TAG, "RTSP stream is now visible and clean!")
                    }
                }
                outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decoder error", e)
        }
    }
}
