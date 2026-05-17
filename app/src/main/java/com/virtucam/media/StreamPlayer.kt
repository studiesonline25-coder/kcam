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
    var videoRotation: Int = 0
    var rawRotation: Int = 0
    var isPlaying: Boolean = false
        private set

    private var isConfigured = false
    private val nalAssembler = NALAssembler()
    
    // Diagnostic counters for green-screen debugging
    private var nalReceivedCount = 0
    private var nalAssembledCount = 0
    private var decoderInputCount = 0
    private var decoderOutputCount = 0

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
                override fun onRtspConnecting() {
                    Log.i(TAG, "RTSP_DIAG: Connecting to $streamUrl...")
                }
                override fun onRtspConnected(sdpInfo: RtspClient.SdpInfo) {
                    val vt = sdpInfo.videoTrack
                    Log.i(TAG, "RTSP_DIAG: Connected! VideoTrack=${vt != null} SPS=${vt?.sps?.size ?: 0}B PPS=${vt?.pps?.size ?: 0}B")
                    if (vt?.sps != null) {
                        Log.i(TAG, "RTSP_DIAG: SPS hex=${vt.sps!!.joinToString("") { "%02x".format(it) }}")
                    }
                    vt?.sps?.let { nalAssembler.setSps(it) }
                    vt?.pps?.let { nalAssembler.setPps(it) }
                }

                override fun onRtspVideoNalUnitReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
                    if (exitFlag.get()) return
                    nalReceivedCount++
                    
                    // Log first few NALs and then every 100th for ongoing health check
                    if (nalReceivedCount <= 5 || nalReceivedCount % 100 == 0) {
                        val nalType = if (length > 0) (data[offset].toInt() and 0x1F) else -1
                        Log.d(TAG, "RTSP_DIAG: NAL #$nalReceivedCount type=$nalType len=$length assembled=$nalAssembledCount decoderIn=$decoderInputCount decoderOut=$decoderOutputCount")
                    }
                    
                    val assembled = nalAssembler.assemble(data, offset, length)
                    if (assembled != null) {
                        nalAssembledCount++
                        feedDecoder(assembled, timestamp)
                    }
                }

                override fun onRtspAudioSampleReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {}
                override fun onRtspApplicationDataReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {}
                override fun onRtspDisconnecting() { Log.i(TAG, "RTSP_DIAG: Disconnecting") }
                override fun onRtspDisconnected() { 
                    Log.i(TAG, "RTSP_DIAG: Disconnected. Total NALs=$nalReceivedCount assembled=$nalAssembledCount decoderIn=$decoderInputCount decoderOut=$decoderOutputCount")
                    isPlaying = false 
                }
                override fun onRtspFailedUnauthorized() { 
                    Log.e(TAG, "RTSP_DIAG: 401 Unauthorized")
                    onStreamError?.invoke("401 Unauthorized") 
                }
                override fun onRtspFailed(message: String?) { 
                    Log.e(TAG, "RTSP_DIAG: Failed: $message")
                    onStreamError?.invoke(message ?: "RTSP Failure") 
                }
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
            // [MEDIATEK FIX] Parse actual resolution from SPS instead of using defaults.
            // MediaTek Helio decoders allocate buffers based on the initial format dimensions.
            // If these don't match the SPS, partial green frames result.
            nalAssembler.getSps()?.let { sps ->
                val parsed = parseSpsResolution(sps)
                if (parsed != null) {
                    videoWidth = parsed.first
                    videoHeight = parsed.second
                    Log.i(TAG, "Parsed SPS resolution: ${videoWidth}x${videoHeight}")
                }
            }
            
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight)
            nalAssembler.getSps()?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(wrapStartCode(it))) }
            nalAssembler.getPps()?.let { format.setByteBuffer("csd-1", ByteBuffer.wrap(wrapStartCode(it))) }
            
            // [MEDIATEK FIX] Set max input size hint — some MediaTek decoders need this
            // to allocate sufficiently large input buffers. Without it, large IDR frames
            // can be truncated → green/corrupt regions.
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, videoWidth * videoHeight)
            
            mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec?.configure(format, outputSurface, null, 0)
            mediaCodec?.start()
            isConfigured = true
            Log.i(TAG, "MediaCodec started: ${mediaCodec?.name} for ${videoWidth}x${videoHeight}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaCodec", e)
        }
    }
    
    /**
     * Minimal SPS parser — extracts width and height from H.264 SPS NAL unit.
     * Handles Baseline, Main, and High profile SPS structures.
     * Returns (width, height) or null on parse failure.
     */
    private fun parseSpsResolution(sps: ByteArray): Pair<Int, Int>? {
        try {
            val reader = BitReader(sps, 0)
            val forbidden = reader.readBits(1)
            val nalRefIdc = reader.readBits(2)
            val nalType = reader.readBits(5)
            if (nalType != 7) return null // Not SPS
            
            val profileIdc = reader.readBits(8)
            reader.readBits(8) // constraint flags + reserved
            val levelIdc = reader.readBits(8)
            reader.readUE() // seq_parameter_set_id
            
            // High profile (100) and above have extra fields
            if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122 || 
                profileIdc == 244 || profileIdc == 44 || profileIdc == 83 ||
                profileIdc == 86 || profileIdc == 118 || profileIdc == 128) {
                val chromaFormatIdc = reader.readUE()
                if (chromaFormatIdc == 3) reader.readBits(1) // separate_colour_plane_flag
                reader.readUE() // bit_depth_luma_minus8
                reader.readUE() // bit_depth_chroma_minus8
                reader.readBits(1) // qpprime_y_zero_transform_bypass_flag
                val seqScalingMatrixPresent = reader.readBits(1)
                if (seqScalingMatrixPresent == 1) {
                    val cnt = if (chromaFormatIdc != 3) 8 else 12
                    for (i in 0 until cnt) {
                        if (reader.readBits(1) == 1) { // scaling_list_present
                            val size = if (i < 6) 16 else 64
                            var lastScale = 8
                            var nextScale = 8
                            for (j in 0 until size) {
                                if (nextScale != 0) {
                                    val delta = reader.readSE()
                                    nextScale = (lastScale + delta + 256) % 256
                                }
                                lastScale = if (nextScale == 0) lastScale else nextScale
                            }
                        }
                    }
                }
            }
            
            reader.readUE() // log2_max_frame_num_minus4
            val picOrderCntType = reader.readUE()
            if (picOrderCntType == 0) {
                reader.readUE() // log2_max_pic_order_cnt_lsb_minus4
            } else if (picOrderCntType == 1) {
                reader.readBits(1) // delta_pic_order_always_zero_flag
                reader.readSE() // offset_for_non_ref_pic
                reader.readSE() // offset_for_top_to_bottom_field
                val numRefFrames = reader.readUE()
                for (i in 0 until numRefFrames) reader.readSE()
            }
            
            reader.readUE() // max_num_ref_frames
            reader.readBits(1) // gaps_in_frame_num_allowed
            val picWidthMbs = reader.readUE() + 1
            val picHeightMapUnits = reader.readUE() + 1
            val frameMbsOnly = reader.readBits(1)
            if (frameMbsOnly == 0) reader.readBits(1) // mb_adaptive_frame_field_flag
            reader.readBits(1) // direct_8x8_inference_flag
            
            var cropLeft = 0; var cropRight = 0; var cropTop = 0; var cropBottom = 0
            val frameCropping = reader.readBits(1)
            if (frameCropping == 1) {
                cropLeft = reader.readUE()
                cropRight = reader.readUE()
                cropTop = reader.readUE()
                cropBottom = reader.readUE()
            }
            
            val w = picWidthMbs * 16 - (cropLeft + cropRight) * 2
            val h = (2 - frameMbsOnly) * picHeightMapUnits * 16 - (cropTop + cropBottom) * 2
            return if (w > 0 && h > 0) Pair(w, h) else null
        } catch (e: Exception) {
            Log.w(TAG, "SPS parse failed, using defaults: ${e.message}")
            return null
        }
    }
    
    /** Bitstream reader for SPS parsing */
    private class BitReader(private val data: ByteArray, private var bitPos: Int) {
        fun readBits(n: Int): Int {
            var value = 0
            for (i in 0 until n) {
                val byteIdx = bitPos / 8
                val bitIdx = 7 - (bitPos % 8)
                if (byteIdx < data.size) {
                    value = (value shl 1) or ((data[byteIdx].toInt() ushr bitIdx) and 1)
                }
                bitPos++
            }
            return value
        }
        
        fun readUE(): Int {
            var zeros = 0
            while (readBits(1) == 0 && zeros < 31) zeros++
            return if (zeros == 0) 0 else ((1 shl zeros) - 1 + readBits(zeros))
        }
        
        fun readSE(): Int {
            val v = readUE()
            return if (v % 2 == 0) -(v / 2) else (v + 1) / 2
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
                decoderInputCount++
            }

            // Drain all available output frames
            val info = MediaCodec.BufferInfo()
            var draining = true
            while (draining) {
                val outputIndex = codec.dequeueOutputBuffer(info, 0)
                when {
                    outputIndex >= 0 -> {
                        decoderOutputCount++
                        if (decoderOutputCount <= 3) {
                            Log.i(TAG, "RTSP_DIAG: Decoder output frame #$decoderOutputCount size=${info.size} flags=${info.flags}")
                        }
                        codec.releaseOutputBuffer(outputIndex, true)
                        onFrameAvailable()
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = codec.outputFormat
                        videoWidth = fmt.getInteger(MediaFormat.KEY_WIDTH)
                        videoHeight = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                        videoRotation = if (videoWidth > videoHeight) 90 else 0
                        rawRotation = videoRotation
                        Log.i(TAG, "Decoder output format changed: ${videoWidth}x${videoHeight}")
                        // Continue draining — there may be frames available after format change
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                        // Deprecated but some MediaTek decoders still emit this
                    }
                    else -> draining = false // INFO_TRY_AGAIN_LATER or unknown
                }
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
