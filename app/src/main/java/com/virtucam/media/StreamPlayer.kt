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
import com.alexvas.utils.VideoCodecUtils
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Robust RTSP Streamer for Xiaomi Devices.
 * Uses rtsp-client-android (com.alexvas.rtsp) and direct MediaCodec with proper NAL framing.
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
    }

    private var rtspClient: RtspClient? = null
    private var mediaCodec: MediaCodec? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private val exitFlag = AtomicBoolean(false)

    var videoWidth: Int = 1080
        private set
    var videoHeight: Int = 1920
        private set
    var videoRotation: Int = 0 // Usually RTSP is already oriented
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
        handlerThread = HandlerThread("VirtuCam-StreamPlayer")
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
        Log.d(TAG, "Initializing RTSP connection for: $streamUrl")
        
        val uri = Uri.parse(streamUrl)
        val host = uri.host ?: "127.0.0.1"
        val port = if (uri.port != -1) uri.port else 554
        
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(host, port), 5000)
            socket.tcpNoDelay = true
            
            val listener = object : RtspClient.RtspClientListener {
                override fun onRtspConnecting() {
                    Log.d(TAG, "RTSP Connecting...")
                }

                override fun onRtspConnected(sdpInfo: RtspClient.SdpInfo) {
                    Log.d(TAG, "RTSP Connected! SDP Video track: ${sdpInfo.videoTrack != null}")
                    // Try to extract SPS/PPS from SDP if available
                    sdpInfo.videoTrack?.sps?.let { 
                        Log.d(TAG, "Found SPS in SDP (${it.size} bytes)")
                        spsData = it 
                    }
                    sdpInfo.videoTrack?.pps?.let { 
                        Log.d(TAG, "Found PPS in SDP (${it.size} bytes)")
                        ppsData = it 
                    }
                }

                override fun onRtspVideoNalUnitReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
                    if (outputSurface == null || exitFlag.get()) return
                    processIncomingNal(data, offset, length, timestamp)
                }

                override fun onRtspAudioSampleReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {}
                override fun onRtspApplicationDataReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {}
                override fun onRtspDisconnecting() { Log.d(TAG, "RTSP Disconnecting...") }
                override fun onRtspDisconnected() { Log.d(TAG, "RTSP Disconnected") }
                override fun onRtspFailedUnauthorized() { onStreamError?.invoke("RTSP Unauthorized") }
                override fun onRtspFailed(message: String?) { onStreamError?.invoke(message ?: "RTSP Error") }
            }

            rtspClient = RtspClient.Builder(socket, streamUrl, exitFlag, listener)
                .requestAudio(false)
                .requestVideo(true)
                .withDebug(false)
                .build()

            isPlaying = true
            rtspClient?.execute()
            
        } catch (e: Exception) {
            Log.e(TAG, "RTSP Execution failed", e)
            onStreamError?.invoke(e.message ?: "Connection failed")
        } finally {
            isPlaying = false
            try { socket?.close() } catch (ioe: IOException) {}
        }
    }

    private fun processIncomingNal(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
        if (length < 1) return
        
        // Check for start codes already existing
        val hasStartCode = length >= 4 && data[offset] == 0.toByte() && data[offset+1] == 0.toByte() && data[offset+2] == 0.toByte() && data[offset+3] == 1.toByte()
        
        // Identify NAL type
        val type = VideoCodecUtils.getNalUnitType(data, if (hasStartCode) offset + 4 else offset, length - (if (hasStartCode) 4 else 0), false)
        
        // If it's a massive buffer (likely combined), we need to handle it
        if (length > 1000 && type == VideoCodecUtils.NAL_SPS) {
             // This is likely SPS + PPS + IDR. 
             // We'll feed it as-is but ENSURE there are start codes between units if the library didn't add them.
             // Actually, if it's 23KB, we should just feed it once configured.
             Log.d(TAG, "Received Large NAL (Type $type, $length bytes)")
        }

        if (type == VideoCodecUtils.NAL_SPS && spsData == null) {
            spsData = if (hasStartCode) data.copyOfRange(offset + 4, offset + length) else data.copyOfRange(offset, offset + length)
            Log.d(TAG, "Captured SPS (${spsData?.size} bytes)")
        } else if (type == VideoCodecUtils.NAL_PPS && ppsData == null) {
            ppsData = if (hasStartCode) data.copyOfRange(offset + 4, offset + length) else data.copyOfRange(offset, offset + length)
            Log.d(TAG, "Captured PPS (${ppsData?.size} bytes)")
        }

        if (spsData != null && ppsData != null && !isConfigured) {
            setupMediaCodec()
        }

        if (isConfigured) {
            // ALWAYS wrap in start code for MediaCodec
            val frame = if (hasStartCode) {
                data.copyOfRange(offset, offset + length)
            } else {
                val b = ByteArray(length + 4)
                System.arraycopy(START_CODE, 0, b, 0, 4)
                System.arraycopy(data, offset, b, 4, length)
                b
            }
            feedDecoder(frame, timestamp)
        }
    }

    private fun setupMediaCodec() {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight)
            // csd-0 and csd-1 MUST include the 00 00 00 01 start code
            spsData?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(wrapInStartCode(it))) }
            ppsData?.let { format.setByteBuffer("csd-1", ByteBuffer.wrap(wrapInStartCode(it))) }
            
            format.setInteger(MediaFormat.KEY_ROTATION, videoRotation)
            // Important for low-latency
            format.setInteger(MediaFormat.KEY_PRIORITY, 0) 
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())

            mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec?.configure(format, outputSurface, null, 0)
            mediaCodec?.start()
            isConfigured = true
            Log.i(TAG, "MediaCodec configured successfully for RTSP stream")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup MediaCodec", e)
        }
    }

    private fun wrapInStartCode(data: ByteArray): ByteArray {
        val b = ByteArray(data.size + 4)
        System.arraycopy(START_CODE, 0, b, 0, 4)
        System.arraycopy(data, 0, b, 4, data.size)
        return b
    }

    private fun feedDecoder(data: ByteArray, timestamp: Long) {
        val codec = mediaCodec ?: return
        try {
            val inputIndex = codec.dequeueInputBuffer(10000)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)
                inputBuffer?.clear()
                inputBuffer?.put(data)
                codec.queueInputBuffer(inputIndex, 0, data.size, timestamp * 1000, 0)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            while (outputIndex >= 0) {
                codec.releaseOutputBuffer(outputIndex, true)
                onFrameAvailable()
                if (!firstFrameFired) {
                    firstFrameFired = true
                    Log.i(TAG, "First RTSP frame rendered to surface!")
                }
                outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error feeding decoder", e)
        }
    }
}
