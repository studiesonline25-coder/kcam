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
 * Robust RTSP Streamer with NAL Reconstruction.
 * Engineered to eliminate green screen artifacts on MediaTek hardware.
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
        
        private const val NAL_TYPE_SPS = 7
        private const val NAL_TYPE_PPS = 8
        private const val NAL_TYPE_IDR = 5
    }

    private var rtspClient: RtspClient? = null
    private var mediaCodec: MediaCodec? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private val exitFlag = AtomicBoolean(false)

    var videoWidth: Int = 1280
    var videoHeight: Int = 720
    var isPlaying: Boolean = false
        private set

    private var isConfigured = false
    private val nalAssembler = NALAssembler()

    fun start() {
        exitFlag.set(false)
        handlerThread = HandlerThread("VirtuCam-RTSP", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        handlerThread?.start()
        handler = Handler(handlerThread!!.looper)
        handler?.post { runRtsp() }
    }

    fun stop() {
        isPlaying = false
        exitFlag.set(true)
        handler?.post {
            try {
                mediaCodec?.stop()
                mediaCodec?.release()
            } catch (_: Exception) {}
            mediaCodec = null
            isConfigured = false
            rtspClient = null
            handlerThread?.quitSafely()
        }
    }

    private fun runRtsp() {
        Log.d(TAG, "Connecting to RTSP: $streamUrl")
        val uri = Uri.parse(streamUrl)
        val host = uri.host ?: "127.0.0.1"
        val port = if (uri.port != -1) uri.port else 554
        
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(host, port), 10000)
            socket.setSoTimeout(5000)
            socket.tcpNoDelay = true
            
            val listener = object : RtspClient.RtspClientListener {
                override fun onRtspConnecting() {}
                override fun onRtspConnected(sdpInfo: RtspClient.SdpInfo) {
                    sdpInfo.videoTrack?.sps?.let { nalAssembler.setSps(it) }
                    sdpInfo.videoTrack?.pps?.let { nalAssembler.setPps(it) }
                }

                override fun onRtspVideoNalUnitReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
                    if (exitFlag.get()) return
                    
                    val assembled = nalAssembler.assemble(data, offset, length)
                    if (assembled != null) {
                        feedDecoder(assembled, timestamp)
                    }
                }

                override fun onRtspAudioSampleReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {}
                override fun onRtspApplicationDataReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {}
                override fun onRtspDisconnecting() {}
                override fun onRtspDisconnected() { isPlaying = false }
                override fun onRtspFailedUnauthorized() { onStreamError?.invoke("401 Unauthorized") }
                override fun onRtspFailed(message: String?) { onStreamError?.invoke(message ?: "RTSP Failure") }
            }

            rtspClient = RtspClient.Builder(socket, streamUrl, exitFlag, listener)
                .requestAudio(false).requestVideo(true).build()

            isPlaying = true
            rtspClient?.execute()
            
        } catch (e: Exception) {
            Log.e(TAG, "RTSP Error: ${e.message}")
            onStreamError?.invoke(e.message ?: "Connection error")
        } finally {
            isPlaying = false
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun setupMediaCodec() {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight)
            nalAssembler.getSps()?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(wrapStartCode(it))) }
            nalAssembler.getPps()?.let { format.setByteBuffer("csd-1", ByteBuffer.wrap(wrapStartCode(it))) }
            
            mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec?.configure(format, outputSurface, null, 0)
            mediaCodec?.start()
            isConfigured = true
            Log.i(TAG, "MediaCodec started: ${mediaCodec?.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaCodec", e)
        }
    }

    private fun wrapStartCode(data: ByteArray): ByteArray {
        val b = ByteArray(data.size + 4)
        System.arraycopy(START_CODE, 0, b, 0, 4)
        System.arraycopy(data, 0, b, 4, data.size)
        return b
    }

    private fun feedDecoder(data: ByteArray, timestamp: Long) {
        if (!isConfigured) {
            setupMediaCodec()
            if (!isConfigured) return
        }

        val codec = mediaCodec ?: return
        try {
            val inputIndex = codec.dequeueInputBuffer(10000)
            if (inputIndex >= 0) {
                codec.getInputBuffer(inputIndex)?.apply {
                    clear()
                    put(data)
                }
                val flags = if (nalAssembler.isKeyFrame(data)) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                codec.queueInputBuffer(inputIndex, 0, data.size, timestamp * 1000, flags)
            }

            val info = MediaCodec.BufferInfo()
            var outputIndex = codec.dequeueOutputBuffer(info, 0)
            while (outputIndex >= 0) {
                codec.releaseOutputBuffer(outputIndex, true)
                onFrameAvailable()
                outputIndex = codec.dequeueOutputBuffer(info, 0)
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val fmt = codec.outputFormat
                videoWidth = fmt.getInteger(MediaFormat.KEY_WIDTH)
                videoHeight = fmt.getInteger(MediaFormat.KEY_HEIGHT)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decode error: ${e.message}")
        }
    }

    /**
     * Reconstructs fragmented NAL units into solid frames.
     */
    private inner class NALAssembler {
        private var sps: ByteArray? = null
        private var pps: ByteArray? = null
        
        fun setSps(data: ByteArray) { sps = data }
        fun setPps(data: ByteArray) { pps = data }
        fun getSps() = sps
        fun getPps() = pps

        fun isKeyFrame(data: ByteArray): Boolean {
            val type = if (data.size > 4) (data[4].toInt() and 0x1F) else -1
            return type == NAL_TYPE_IDR
        }

        fun assemble(data: ByteArray, offset: Int, length: Int): ByteArray? {
            val hasStartCode = length > 4 && data[offset] == 0.toByte() && data[offset+1] == 0.toByte() && data[offset+2] == 0.toByte() && data[offset+3] == 1.toByte()
            val type = if (hasStartCode) (data[offset+4].toInt() and 0x1F) else (data[offset].toInt() and 0x1F)
            
            if (type == NAL_TYPE_IDR) {
                // Prepend SPS/PPS to IDR for complete keyframe delivery
                val s = sps ?: return null
                val p = pps ?: return null
                val totalSize = 4 + s.size + 4 + p.size + 4 + length - (if (hasStartCode) 4 else 0)
                val out = ByteArray(totalSize)
                var pos = 0
                
                System.arraycopy(START_CODE, 0, out, pos, 4); pos += 4
                System.arraycopy(s, 0, out, pos, s.size); pos += s.size
                System.arraycopy(START_CODE, 0, out, pos, 4); pos += 4
                System.arraycopy(p, 0, out, pos, p.size); pos += p.size
                System.arraycopy(START_CODE, 0, out, pos, 4); pos += 4
                System.arraycopy(data, if (hasStartCode) offset + 4 else offset, out, pos, length - (if (hasStartCode) 4 else 0))
                return out
            }
            
            return if (hasStartCode) data.copyOfRange(offset, offset + length) else wrapStartCode(data.copyOfRange(offset, offset + length))
        }
    }
}
