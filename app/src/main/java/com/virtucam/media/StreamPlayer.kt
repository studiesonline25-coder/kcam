package com.virtucam.media

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.rtmp.RtmpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource

/**
 * ExoPlayer-based stream player for RTSP and RTMP live streams.
 * Replaces the custom MediaCodec-based StreamPlayer which had buffer stall
 * issues on MediaTek devices (c2.mtk.avc.decoder stuck at 19 inputs / 2 outputs).
 *
 * ExoPlayer handles all MediaCodec buffer management internally and is
 * battle-tested on MediaTek hardware.
 */
@OptIn(UnstableApi::class)
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
        private const val TAG = "VIRTUCAM_STREAM"
    }

    private var player: ExoPlayer? = null
    private var playerHandler: Handler? = null
    private var playerThread: HandlerThread? = null

    var videoWidth: Int = 1280
    var videoHeight: Int = 720
    var videoRotation: Int = 0
    var rawRotation: Int = 0
    private var _isPlaying: Boolean = false
    val isPlaying: Boolean get() = _isPlaying

    private var frameCount = 0

    fun start() {
        // ExoPlayer must be created on a thread with a Looper
        playerThread = HandlerThread("VirtuCam-ExoPlayer").apply { start() }
        playerHandler = Handler(playerThread!!.looper)
        
        playerHandler?.post { initPlayer() }
    }

    fun stop() {
        _isPlaying = false
        playerHandler?.post {
            try {
                player?.stop()
                player?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping player: ${e.message}")
            }
            player = null
            playerThread?.quitSafely()
        }
    }

    private fun initPlayer() {
        try {
            Log.i(TAG, "STREAM_DIAG: Initializing ExoPlayer for: $streamUrl")
            
            // Configure buffering for live streams - balanced between latency and stability
            val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    1000,  // minBufferMs: start playback after 1s (was 500ms, too aggressive)
                    5000,  // maxBufferMs: buffer up to 5s (was 2s, caused disconnects)
                    500,   // bufferForPlaybackMs: resume after rebuffer with 500ms
                    1000   // bufferForPlaybackAfterRebufferMs: 1s after stall (was 500ms)
                )
                .setPrioritizeTimeOverSizeThresholds(true)  // Prioritize time-based buffering for live streams
                .build()
            
            player = ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)  // Keep network alive during playback
                .build().apply {
                // Set the output surface for decoded video frames
                setVideoSurface(outputSurface)
                
                // Disable audio — we only need video
                volume = 0f
                
                // Listen for events
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        val stateName = when (state) {
                            Player.STATE_IDLE -> "IDLE"
                            Player.STATE_BUFFERING -> "BUFFERING"
                            Player.STATE_READY -> "READY"
                            Player.STATE_ENDED -> "ENDED"
                            else -> "UNKNOWN($state)"
                        }
                        Log.i(TAG, "STREAM_DIAG: Playback state: $stateName")
                        
                        if (state == Player.STATE_READY && !isPlaying) {
                            _isPlaying = true
                            Log.i(TAG, "STREAM_DIAG: Stream is playing!")
                        }
                    }
                    
                    override fun onVideoSizeChanged(size: VideoSize) {
                        videoWidth = size.width
                        videoHeight = size.height
                        // ExoPlayer reports rotation separately
                        rawRotation = size.unappliedRotationDegrees
                        videoRotation = if (videoWidth > videoHeight) 90 else 0
                        Log.i(TAG, "STREAM_DIAG: Video size: ${videoWidth}x${videoHeight} rotation=$rawRotation")
                    }
                    
                    override fun onRenderedFirstFrame() {
                        Log.i(TAG, "STREAM_DIAG: First frame rendered!")
                        frameCount++
                        onFrameAvailable()
                    }
                    
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "STREAM_DIAG: Player error: ${error.message} (code=${error.errorCode})", error)
                        
                        // Auto-reconnect on network errors (connection closed, timeout, etc.)
                        if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                            error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED) {
                            Log.w(TAG, "STREAM_DIAG: Network error detected, attempting reconnect in 1s...")
                            playerHandler?.postDelayed({
                                try {
                                    player?.prepare()
                                    player?.play()
                                    Log.i(TAG, "STREAM_DIAG: Reconnect attempt initiated")
                                } catch (e: Exception) {
                                    Log.e(TAG, "STREAM_DIAG: Reconnect failed: ${e.message}")
                                    onStreamError?.invoke(error.message ?: "ExoPlayer error ${error.errorCode}")
                                }
                            }, 1000)
                        } else {
                            onStreamError?.invoke(error.message ?: "ExoPlayer error ${error.errorCode}")
                        }
                    }
                })
                
                // Set up video frame callback to track ongoing frames
                setVideoFrameMetadataListener(
                    androidx.media3.exoplayer.video.VideoFrameMetadataListener { presentationTimeUs, releaseTimeNs, format, mediaFormat ->
                        frameCount++
                        onFrameAvailable()
                        if (frameCount <= 5 || frameCount % 100 == 0) {
                            Log.d(TAG, "STREAM_DIAG: Frame #$frameCount pts=${presentationTimeUs/1000}ms")
                        }
                    }
                )
            }
            
            // Create the appropriate media source based on URL scheme
            val mediaSource = createMediaSource(streamUrl)
            
            if (mediaSource != null) {
                player?.setMediaSource(mediaSource)
            } else {
                // Fallback: let ExoPlayer auto-detect
                player?.setMediaItem(MediaItem.fromUri(streamUrl))
            }
            
            player?.prepare()
            player?.play()
            
            Log.i(TAG, "STREAM_DIAG: ExoPlayer prepared and playing. URL=$streamUrl")
            
        } catch (e: Exception) {
            Log.e(TAG, "STREAM_DIAG: Failed to initialize ExoPlayer", e)
            onStreamError?.invoke("Init failed: ${e.message}")
        }
    }
    
    private fun createMediaSource(url: String): MediaSource? {
        return when {
            url.startsWith("rtsp://", ignoreCase = true) -> {
                Log.i(TAG, "STREAM_DIAG: Using RTSP media source (TCP=$useTcp)")
                RtspMediaSource.Factory()
                    .setForceUseRtpTcp(useTcp)
                    .createMediaSource(MediaItem.fromUri(url))
            }
            url.startsWith("rtmp://", ignoreCase = true) -> {
                Log.i(TAG, "STREAM_DIAG: Using RTMP media source")
                val dataSourceFactory = RtmpDataSource.Factory()
                androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(url))
            }
            else -> {
                Log.w(TAG, "STREAM_DIAG: Unknown protocol, using auto-detect for: $url")
                null
            }
        }
    }
}
