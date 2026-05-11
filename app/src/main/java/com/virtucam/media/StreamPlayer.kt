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
 * Handles combined NAL bundles and ensures clean decoding.
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
        Log.d(TAG, "Initializing RTSP (Permissive Mode) for: $streamUrl")
        
        val uri = Uri.parse(streamUrl)
        val host = uri.host ?: "127.0.0.1"
        val port = if (uri.port != -1) uri.port else 554
        
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(host, port), 15000)
            socket.tcpNoDelay = true
            
            val listener = object : RtspClient.RtspClientListener {
                override fun onRtspConnecting() { Log.d(TAG, "RTSP Connecting...") }
                override fun onRtspConnected(sdpInfo: RtspClient.SdpInfo) {
                    Log.d(TAG, "RTSP Connected! SDP Video track: ${sdpInfo.videoTrack != null}")
                    sdpInfo.videoTrack?.sps?.let { 
                        Log.d(TAG, "SDP SPS found: ${it.size} bytes")
                        spsData = it 
                    }
                    sdpInfo.videoTrack?.pps?.let { 
                        Log.d(TAG, "SDP PPS found: ${it.size} bytes")
                        ppsData = it 
                    }
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
            Log.e(TAG, "RTSP connection failed", e)
            onStreamError?.invoke(e.message ?: "Connection failed")
        } finally {
            isPlaying = false
            try { socket?.close() } catch (ioe: IOException) {}
        }
    }

    private fun processIncomingNal(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
        if (length < 1) return
        
        val hasStartCode = length >= 4 && data[offset] == 0.toByte() && data[offset+1] == 0.toByte() && data[offset+2] == 0.toByte() && data[offset+3] == 1.toByte()
        val type = VideoCodecUtils.getNalUnitType(data, if (hasStartCode) offset + 4 else offset, length - (if (hasStartCode) 4 else 0), false)
        
        // Debug: Log first 8 bytes of any received packet
        if (!firstFrameFired && length > 0) {
            val hex = data.sliceArray(offset until Math.min(offset + 8, offset + length))
                .joinToString("") { "%02x ".format(it) }
            Log.v(TAG, "NAL Type $type, Length $length, Data: $hex")
        }

        // If it's an IDR frame OR a large bundle starting with SPS, consider it a keyframe
        if (!seenKeyframe && (type == VideoCodecUtils.NAL_IDR_SLICE || (type == VideoCodecUtils.NAL_SPS && length > 500))) {
            Log.i(TAG, "Keyframe/Bundle detected (Type $type, $length bytes). Enabling decode.")
            seenKeyframe = true
        }

        if (type == VideoCodecUtils.NAL_SPS && spsData == null) {
            spsData = if (hasStartCode) data.copyOfRange(offset + 4, offset + length) else data.copyOfRange(offset, offset + length)
        } else if (type == VideoCodecUtils.NAL_PPS && ppsData == null) {
            ppsData = if (hasStartCode) data.copyOfRange(offset + 4, offset + length) else data.copyOfRange(offset, offset + length)
        }

        if (spsData != null && ppsData != null && !isConfigured) {
            setupMediaCodec()
        }

        // Allow decoding if configured, even if we haven't seen a "formal" keyframe yet but have SPS/PPS from SDP
        val shouldDecode = isConfigured && (seenKeyframe || (spsData != null && ppsData != null))
        
        if (shouldDecode) {
            val frame = if (hasStartCode) data.copyOfRange(offset, offset + length) else wrapInStartCode(data, offset, length)
            feedDecoder(frame, timestamp)
        }
    }

    private fun setupMediaCodec() {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight)
            spsData?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(wrapInStartCode(it, 0, it.size))) }
            ppsData?.let { format.setByteBuffer("csd-1", ByteBuffer.wrap(wrapInStartCode(it, 0, it.size))) }
            
            mediaCodec = try {
                MediaCodec.createByCodecName("OMX.google.h264.decoder")
            } catch (e: Exception) {
                MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            }
            
            mediaCodec?.configure(format, outputSurface, null, 0)
            mediaCodec?.start()
            isConfigured = true
            Log.i(TAG, "MediaCodec initialized: ${mediaCodec?.name}")
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
            val inputIndex = codec.dequeueInputBuffer(10000)
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
                    Log.i(TAG, "Format corrected: ${videoWidth}x${videoHeight}, rot: $videoRotation")
                } else if (outputIndex >= 0) {
                    codec.releaseOutputBuffer(outputIndex, true)
                    onFrameAvailable()
                    if (!firstFrameFired) {
                        firstFrameFired = true
                        Log.i(TAG, "First frame rendered successfully.")
                    }
                }
                outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in decode loop", e)
        }
    }
}
