package com.virtucam.media

import android.content.Context
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

class StreamPlayer(
    private val context: Context,
    private val streamUrl: String,
    private var outputSurface: Surface?,
    private val onFrameAvailable: () -> Unit
) {
    private companion object {
        const val TAG = "VIRTUCAM_RTSP"
        val START_CODE = byteArrayOf(0, 0, 0, 1)
    }

    private var mediaCodec: MediaCodec? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private val exitFlag = AtomicBoolean(false)
    private var isConfigured = false
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    fun start() {
        exitFlag.set(false)
        handlerThread = HandlerThread("VirtuCam-StreamPlayer").apply { start() }
        handler = Handler(handlerThread!!.looper).apply { post { runRtsp() } }
    }

    private fun runRtsp() {
        val uri = Uri.parse(streamUrl)
        val socket = Socket().apply { connect(InetSocketAddress(uri.host, if (uri.port != -1) uri.port else 554), 10000) }
        val listener = object : RtspClient.RtspClientListener {
            override fun onRtspVideoNalUnitReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
                splitAndProcess(data, offset, length, timestamp)
            }
            override fun onRtspConnected(sdp: RtspClient.SdpInfo) {
                sps = sdp.videoTrack?.sps; pps = sdp.videoTrack?.pps
            }
            // ... other required overrides
            override fun onRtspConnecting() {}
            override fun onRtspDisconnecting() {}
            override fun onRtspDisconnected() {}
            override fun onRtspFailedUnauthorized() {}
            override fun onRtspFailed(m: String?) {}
            override fun onRtspAudioSampleReceived(d: ByteArray, o: Int, l: Int, t: Long) {}
            override fun onRtspApplicationDataReceived(d: ByteArray, o: Int, l: Int, t: Long) {}
        }
        RtspClient.Builder(socket, streamUrl, exitFlag, listener).requestVideo(true).build().execute()
    }

    private fun splitAndProcess(data: ByteArray, offset: Int, length: Int, ts: Long) {
        if (!isConfigured && sps != null && pps != null) setupCodec()
        if (isConfigured) feedDecoder(wrap(data, offset, length), ts)
    }

    private fun setupCodec() {
        val format = MediaFormat.createVideoFormat("video/avc", 1280, 720)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(wrap(sps!!, 0, sps!!.size)))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(wrap(pps!!, 0, pps!!.size)))
        mediaCodec = MediaCodec.createDecoderByType("video/avc").apply { configure(format, outputSurface, null, 0); start() }
        isConfigured = true
    }

    private fun wrap(d: ByteArray, o: Int, l: Int): ByteArray {
        val b = ByteArray(l + 4)
        System.arraycopy(START_CODE, 0, b, 0, 4)
        System.arraycopy(d, o, b, 4, l)
        return b
    }

    private fun feedDecoder(data: ByteArray, ts: Long) {
        val codec = mediaCodec ?: return
        val idx = codec.dequeueInputBuffer(0)
        if (idx >= 0) {
            codec.getInputBuffer(idx)?.apply { clear(); put(data) }
            codec.queueInputBuffer(idx, 0, data.size, ts * 1000, 0)
        }
        val info = MediaCodec.BufferInfo()
        var outIdx = codec.dequeueOutputBuffer(info, 0)
        while (outIdx >= 0) {
            codec.releaseOutputBuffer(outIdx, true)
            onFrameAvailable()
            outIdx = codec.dequeueOutputBuffer(info, 0)
        }
    }

    fun stop() { exitFlag.set(true); mediaCodec?.stop(); mediaCodec?.release(); handlerThread?.quitSafely() }
}
