package com.virtucam.data

import android.content.Context
import android.net.Uri
import com.virtucam.VirtuCamApp

/**
 * Manages VirtuCam configuration and settings
 * Stores the spoof media URI and target app list
 */
class VirtuCamConfig(context: Context) {
    
    private var prefs = context.getSharedPreferences(
        VirtuCamApp.PREFS_NAME, 
        Context.MODE_PRIVATE
    )
    
    /**
     * Force a reload of the preferences from disk.
     * Essential for multi-process synchronization (e.g. Xiaomi MiAlgoEngine).
     */
    fun reload(context: Context) {
        prefs = context.getSharedPreferences(
            VirtuCamApp.PREFS_NAME,
            Context.MODE_PRIVATE
        )
    }
    
    /**
     * Whether VirtuCam is enabled
     */
    var isEnabled: Boolean
        get() = prefs.getBoolean(VirtuCamApp.KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(VirtuCamApp.KEY_ENABLED, value).apply()
    
    /**
     * URI of the media to inject as camera feed
     */
    var spoofMediaUri: Uri?
        get() = prefs.getString("media_uri", null)?.let { Uri.parse(it) }
        set(value) = prefs.edit().putString("media_uri", value?.toString()).apply()

    /**
     * Whether the selected media is a video
     */
    var isSpoofVideo: Boolean
        get() = prefs.getBoolean("is_video", false)
        set(value) = prefs.edit().putBoolean("is_video", value).apply()
    
    /**
     * Whether the selected media is a live stream
     */
    var isStream: Boolean
        get() = prefs.getBoolean("is_stream", false)
        set(value) = prefs.edit().putBoolean("is_stream", value).apply()

    /**
     * URL of the live stream (RTSP/RTMP)
     */
    var streamUrl: String?
        get() = prefs.getString("stream_url", null)
        set(value) = prefs.edit().putString("stream_url", value).apply()

    /**
     * Whether to force TCP for RTSP streams (prevents UDP timeout issues)
     */
    var rtspUseTcp: Boolean
        get() = prefs.getBoolean("rtsp_use_tcp", true)
        set(value) = prefs.edit().putBoolean("rtsp_use_tcp", value).apply()

    /**
     * Aspect ratio compensation factor (user nudge)
     * 1.0 = Default (16:9 target)
     * > 1.0 = Increase height
     * < 1.0 = Decrease height
     */
    var compensationFactor: Float
        get() = prefs.getFloat("compensation_factor", 1.0f)
        set(value) = prefs.edit().putFloat("compensation_factor", value).apply()

    /**
     * Zoom scale factor (global multiplier)
     * 1.0 = Default
     */
    var zoomFactor: Float
        get() = prefs.getFloat("zoom_factor", 1.0f)
        set(value) = prefs.edit().putFloat("zoom_factor", value).apply()
    
    /**
     * Whether to mirror the output (lateral inversion fix)
     */
    var isMirrored: Boolean
        get() = prefs.getBoolean("is_mirrored", false)
        set(value) = prefs.edit().putBoolean("is_mirrored", value).apply()
    
    /**
     * Rotation override (clockwise in degrees: 0, 90, 180, 270)
     */
    var rotation: Int
        get() = prefs.getInt("rotation_override", 0)
        set(value) = prefs.edit().putInt("rotation_override", value).apply()

    /**
     * Whether to swap U and V planes (color fix for some devices)
     */
    var isColorSwapped: Boolean
        get() = prefs.getBoolean("is_color_swapped", false)
        set(value) = prefs.edit().putBoolean("is_color_swapped", value).apply()

    /**
     * AI Liveness Simulator (micro-movements jitter)
     */
    var isLivenessEnabled: Boolean
        get() = prefs.getBoolean("is_liveness_enabled", true) // Default to ON
        set(value) = prefs.edit().putBoolean("is_liveness_enabled", value).apply()

    /**
     * [INVESTIGATION] Test pattern mode.
     * When ON, replaces user-uploaded media with a known-orientation test pattern
     * (TOP/BOTTOM/L/R labels + arrow). Used for rotation/mirror analysis.
     */
    var isTestPatternMode: Boolean
        get() = prefs.getBoolean("is_test_pattern_mode", false)
        set(value) = prefs.edit().putBoolean("is_test_pattern_mode", value).apply()

    /**
     * [INVESTIGATION] Passthrough mode.
     * When ON, VirtuCam does NOT replace camera surfaces. Real hardware camera
     * frames flow to the app unchanged. However, audit hooks remain active
     * (HardwareAuditLogger, display-layer hooks) and a spy ImageReader is
     * injected into the session to capture real hardware YUV buffers as PNGs.
     * This lets us see exactly what the real sensor produces for comparison.
     */
    var isPassthroughMode: Boolean
        get() = prefs.getBoolean("is_passthrough_mode", false)
        set(value) = prefs.edit().putBoolean("is_passthrough_mode", value).apply()

    /**
     * Manual rotation offset (0, 90, 180, 270)
     */
    var rotationOffset: Int
        get() = prefs.getInt("rotation_offset", 0)
        set(value) = prefs.edit().putInt("rotation_offset", value).apply()


    /**
     * List of package names for apps to target
     */
    var targetApps: Set<String>
        get() = prefs.getStringSet(VirtuCamApp.KEY_TARGET_APPS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(VirtuCamApp.KEY_TARGET_APPS, value).apply()
    
    /**
     * Add a target app
     */
    fun addTargetApp(packageName: String) {
        targetApps = targetApps + packageName
    }
    
    /**
     * Remove a target app
     */
    fun removeTargetApp(packageName: String) {
        targetApps = targetApps - packageName
    }
    
    /**
     * Check if an app is targeted
     */
    fun isAppTargeted(packageName: String): Boolean {
        return targetApps.isEmpty() || packageName in targetApps
    }
    
    /**
     * ATOMIC DISK SYNC (Global): Reads values from the physical data partition.
     * Bypasses Android's process-level sandbox isolation by trying absolute paths.
     */
    fun getFloatDirectSync(context: Context?, key: String, default: Float): Float {
        // Try multiple possible paths to overcome different process user/root mappings
        val possiblePaths = listOf(
            "/data/user/0/com.virtucam/shared_prefs/${VirtuCamApp.PREFS_NAME}.xml",
            "/data/data/com.virtucam/shared_prefs/${VirtuCamApp.PREFS_NAME}.xml"
        )
        
        for (path in possiblePaths) {
            try {
                val prefsFile = java.io.File(path)
                if (!prefsFile.exists()) {
                    android.util.Log.v("DIAGNOSTIC_VIRTUCAM", "DirectSync: Path not found: $path")
                    continue
                }
                if (!prefsFile.canRead()) {
                    android.util.Log.e("DIAGNOSTIC_VIRTUCAM", "DirectSync: PERMISSION DENIED for $path")
                    continue
                }
                
                val content = prefsFile.readText()
                // Pattern for <float name="key" value="0.59" />
                val pattern = "name=\"$key\"\\s+value=\"([\\d\\.-]+)\"".toRegex()
                val match = pattern.find(content)
                if (match != null) {
                    val value = match.groupValues[1].toFloat()
                    android.util.Log.d("DIAGNOSTIC_VIRTUCAM", "DirectSync: Successfully read $key=$value from $path")
                    return value
                } else {
                    android.util.Log.e("DIAGNOSTIC_VIRTUCAM", "DirectSync: Key $key not found in XML at $path")
                }
            } catch (e: Exception) {
                android.util.Log.e("DIAGNOSTIC_VIRTUCAM", "DirectSync: Error reading $path: ${e.message}")
            }
        }
        
        // Final fallback to memory/context if file fails
        return try {
            prefs.getFloat(key, default)
        } catch (_: Exception) {
            default
        }
    }

    /**
     * Clear all settings
     */
    fun clear() {
        prefs.edit().clear().apply()
    }
    
    companion object {
        @Volatile
        private var INSTANCE: VirtuCamConfig? = null
        
        fun getInstance(context: Context): VirtuCamConfig {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VirtuCamConfig(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
