package com.virtucam.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession

/**
 * Background service that runs the FFmpeg transmuxing proxy.
 * This keeps the heavy native libraries and network logic inside the VirtuCam app
 * instead of the hooked target app, preventing crashes.
 */
class ProxyService : Service() {

    companion object {
        private const val TAG = "VirtuCam_Proxy"
        private const val CHANNEL_ID = "proxy_service"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_URL = "EXTRA_URL"
    }

    private var ffmpegSession: FFmpegSession? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VirtuCam Stream Proxy")
            .setContentText("Transmuxing stream for stability...")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .build()
        
        // Start foreground with a more compatible approach for different Android versions
        try {
            startForeground(1, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (url != null) startProxy(url)
            }
            ACTION_STOP -> stopProxy()
        }
        return START_NOT_STICKY
    }

    private fun startProxy(url: String) {
        stopProxy() // Ensure clean start
        
        val optimizedUrl = if (url.startsWith("srt", ignoreCase = true) && !url.contains("latency=")) {
            val separator = if (url.contains("?")) "&" else "?"
            "$url${separator}latency=1000000"
        } else url

        // RECONNECT LOGIC: Added -reconnect and -reconnect_streamed for robustness
        // Removed -flags low_delay as it causes timeouts with some RTSP servers
        val inputArgs = buildString {
            if (url.startsWith("rtsp", ignoreCase = true)) append("-rtsp_transport tcp ")
            append("-reconnect 1 -reconnect_streamed 1 -reconnect_delay_max 2 ")
            append("-probesize 1000000 -analyzeduration 1000000 ")
        }
        
        // NUT is a much more flexible container for local piping than MPEGTS
        // It handles H.264, H.265, and any audio/video combo without strict mapping
        val udpUrl = "udp://127.0.0.1:9998?pkt_size=1316&buffer_size=10485760&overrun_nonfatal=1"
        
        // Added -loglevel debug temporarily to see exactly why it fails if it doesn't start
        val command = "$inputArgs -i \"$optimizedUrl\" -c copy -f nut \"$udpUrl\""
        
        Log.d(TAG, "Starting proxy: $command")
        ffmpegSession = FFmpegKit.executeAsync(command) { session ->
            val logs = session.allLogsAsString
            Log.d(TAG, "Proxy finished. ReturnCode: ${session.returnCode}. State: ${session.state}")
            if (logs.isNotEmpty()) {
                Log.e(TAG, "FFmpeg Logs: $logs")
            }
        }
    }

    private fun stopProxy() {
        ffmpegSession?.cancel()
        ffmpegSession = null
        Log.d(TAG, "Proxy stopped")
    }

    override fun onDestroy() {
        stopProxy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Stream Proxy Service",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
