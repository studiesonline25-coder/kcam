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
 * Uses rtsp-client-android (com.alexvas.rtsp) and direct MediaCodec to bypass ExoPlayer bugs.
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
        handlerThread = HandlerThread("VirtuCam-StreamPlayer")
        handlerThread?.start()
        handler = Handler(handlerThread!!.looper)
        handler?.post { initializeAndRun() }
    }

    fun stop() {
        isPlaying = false
        exitFlag.set(true) // This will signal RtspClient to exit its loop
        
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
                    Log.d(TAG, "RTSP Connected! Video track found: ${sdpInfo.videoTrack != null}")
                }

                override fun onRtspVideoNalUnitReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
                    if (outputSurface == null || exitFlag.get()) return
                    parseH264Nal(data, offset, length, timestamp)
                }

                override fun onRtspAudioSampleReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {}
                override fun onRtspApplicationDataReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {}
                override fun onRtspDisconnecting() {
                    Log.d(TAG, "RTSP Disconnecting...")
                }

                override fun onRtspDisconnected() {
                    Log.d(TAG, "RTSP Disconnected")
                }

                override fun onRtspFailedUnauthorized() {
                    Log.e(TAG, "RTSP Unauthorized")
                    onStreamError?.invoke("RTSP Unauthorized")
                }

                override fun onRtspFailed(message: String?) {
                    Log.e(TAG, "RTSP Error: $message")
                    onStreamError?.invoke(message ?: "Unknown RTSP error")
                }
            }

            rtspClient = RtspClient.Builder(socket, streamUrl, exitFlag, listener)
                .requestAudio(false)
                .requestVideo(true)
                .withDebug(false)
                .build()

            isPlaying = true
            rtspClient?.execute() // This is a blocking call
            
        } catch (e: Exception) {
            Log.e(TAG, "RTSP Execution failed", e)
            onStreamError?.invoke(e.message ?: "Connection failed")
        } finally {
            isPlaying = false
            try {
                socket?.close()
            } catch (ioe: IOException) {
                Log.e(TAG, "Error closing socket", ioe)
            }
        }
    }

    private fun parseH264Nal(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
        if (length < 1) return
        
        val nalType = VideoCodecUtils.getNalUnitType(data, offset, length, false)

        if (nalType == VideoCodecUtils.NAL_SPS) {
            spsData = data.copyOfRange(offset, offset + length)
            Log.d(TAG, "Captured SPS (${length} bytes)")
        } else if (nalType == VideoCodecUtils.NAL_PPS) {
            ppsData = data.copyOfRange(offset, offset + length)
            Log.d(TAG, "Captured PPS (${length} bytes)")
        }

        if (spsData != null && ppsData != null && !isConfigured) {
            setupMediaCodec()
        }

        if (isConfigured) {
            feedDecoder(data, offset, length, timestamp)
        }
    }

    private fun setupMediaCodec() {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight)
            // Add SPS/PPS to CSD-0/CSD-1
            spsData?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
            ppsData?.let { format.setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
            
            // Set rotation if needed (most RTSP streams are 0, but camera sensors are often 90)
            format.setInteger(MediaFormat.KEY_ROTATION, videoRotation)

            mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec?.configure(format, outputSurface, null, 0)
            mediaCodec?.start()
            isConfigured = true
            Log.i(TAG, "MediaCodec configured successfully for RTSP stream")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup MediaCodec", e)
        }
    }

    private fun feedDecoder(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
        val codec = mediaCodec ?: return
        try {
            val inputIndex = codec.dequeueInputBuffer(10000)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)
                inputBuffer?.clear()
                inputBuffer?.put(data, offset, length)
                codec.queueInputBuffer(inputIndex, 0, length, timestamp * 1000, 0)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            while (outputIndex >= 0) {
                // Render to surface
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
