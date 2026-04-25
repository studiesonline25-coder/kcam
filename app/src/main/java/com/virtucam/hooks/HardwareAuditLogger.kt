package com.virtucam.hooks

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HardwareAuditLogger
 *
 * Passive surveillance mode: when VirtuCam is DISABLED, this class silently
 * records every signal that real camera hardware produces. The output is a
 * structured JSON file saved to /sdcard/Download/virtucam_audit/ for analysis.
 *
 * This data drives achieving true hardware parity — we replicate exactly what
 * the real HAL reports, field by field.
 *
 * Captured signal categories:
 *  A. CameraCharacteristics  — what apps see when they query the camera
 *  B. CaptureResult fields   — AE/AF/AWB state, timestamps, exposure, focus, etc.
 *  C. SurfaceTexture matrix  — the exact 4x4 transform matrix per camera/frame
 *  D. CaptureRequest fields  — what the app actually sends to the HAL
 *  E. Surface geometry       — formats and sizes negotiated per session
 */
object HardwareAuditLogger {

    private const val TAG = "VirtuCam_Audit"
    private const val AUDIT_DIR = "/sdcard/Download/virtucam_audit"

    // ---- Master audit document ----
    private val auditDoc = JSONObject()
    private val sessions = JSONArray()   // one entry per createCaptureSession call
    private var currentSession = JSONObject()
    // Top-level pools that persist even if no session is begun
    private val globalCharacteristics = JSONObject()
    private val globalMatrices = JSONArray()
    private val globalCaptureResults = JSONArray()
    private val globalCaptureRequests = JSONArray()

    // ---- Transform matrix history (one sample per unique matrix seen) ----
    // key = camera_id + facing, value = list of observed matrices
    private val matrixHistory = mutableMapOf<String, MutableSet<String>>()

    // ---- CaptureResult sample buffer (ring buffer, keep last 60 frames per session) ----
    private val resultBuffer = ConcurrentLinkedQueue<JSONObject>()
    private val resultCount = java.util.concurrent.atomic.AtomicInteger(0)

    // ---- CaptureRequest sample buffer ----
    private val requestBuffer = ConcurrentLinkedQueue<JSONObject>()

    // ---- Guard: only flush once per session ----
    private val isFlushing = AtomicBoolean(false)

    // ---- Session counter ----
    private var sessionIndex = 0

    // ---- Throttle: log matrix only when it changes ----
    private var lastMatrixStr = ""
    private var matrixFrameCount = 0

    // ---- Initialized flag ----
    private var initialized = false

    // ---- Periodic auto-flush (every 30s) so data isn't lost during long sessions ----
    private var autoFlushThread: Thread? = null

    fun init(deviceInfo: JSONObject) {
        if (initialized) return
        initialized = true
        auditDoc.put("device", deviceInfo)
        auditDoc.put("capture_time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        auditDoc.put("characteristics", globalCharacteristics)
        auditDoc.put("transform_matrices", globalMatrices)
        auditDoc.put("capture_results", globalCaptureResults)
        auditDoc.put("capture_requests", globalCaptureRequests)
        auditDoc.put("sessions", sessions)
        Log.i(TAG, "HardwareAuditLogger initialized. Audit dir: $AUDIT_DIR")

        // Start periodic auto-flush thread — always flush so file reflects latest state
        autoFlushThread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(15_000)
                    Log.i(TAG, "Auto-flush: chars=${globalCharacteristics.length()}, matrices=${globalMatrices.length()}, results=${globalCaptureResults.length()}, requests=${globalCaptureRequests.length()}")
                    flush()
                } catch (_: InterruptedException) { break }
                  catch (_: Throwable) {}
            }
        }.also {
            it.name = "VirtuCam-AuditAutoFlush"
            it.isDaemon = true
            it.start()
        }
    }

    // -----------------------------------------------------------------------
    // A. CameraCharacteristics
    // -----------------------------------------------------------------------

    fun logCharacteristics(cameraId: String, chars: CameraCharacteristics) {
        try {
            val obj = JSONObject()
            obj.put("camera_id", cameraId)

            // Facing: 0=BACK, 1=FRONT, 2=EXTERNAL
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            obj.put("lens_facing", facing)
            obj.put("lens_facing_name", when (facing) {
                CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                else -> "UNKNOWN($facing)"
            })

            // Sensor orientation (degrees the sensor image is rotated relative to the device)
            obj.put("sensor_orientation", chars.get(CameraCharacteristics.SENSOR_ORIENTATION))

            // Hardware support level
            val hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            obj.put("hw_level", hwLevel)
            obj.put("hw_level_name", when (hwLevel) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                else -> "UNKNOWN($hwLevel)"
            })

            // AF modes
            val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            obj.put("af_available_modes", afModes?.let { JSONArray(it.toList()) } ?: JSONArray())

            // AE modes
            val aeModes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
            obj.put("ae_available_modes", aeModes?.let { JSONArray(it.toList()) } ?: JSONArray())

            // AWB modes
            val awbModes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            obj.put("awb_available_modes", awbModes?.let { JSONArray(it.toList()) } ?: JSONArray())

            // Lens: aperture, min focus distance
            val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            obj.put("apertures", apertures?.let { JSONArray(it.toList()) } ?: JSONArray())
            obj.put("min_focus_distance", chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE))
            obj.put("hyperfocal_distance", chars.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE))

            // Sensor: pixel array size, active array size
            val pixelArray = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            obj.put("pixel_array_size", "${pixelArray?.width}x${pixelArray?.height}")
            val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            obj.put("active_array", "${activeArray?.width()}x${activeArray?.height()}")

            // Sensor sensitivity range
            val sensitivityRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            obj.put("sensitivity_range", "${sensitivityRange?.lower}..${sensitivityRange?.upper}")

            // Sensor exposure time range
            val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            obj.put("exposure_time_range_ns", "${exposureRange?.lower}..${exposureRange?.upper}")

            // Frame duration range
            val frameDuration = chars.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
            obj.put("max_frame_duration_ns", frameDuration)

            // Noise reduction modes
            val nrModes = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
            obj.put("noise_reduction_modes", nrModes?.let { JSONArray(it.toList()) } ?: JSONArray())

            // OIS availability
            obj.put("ois_available", chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) ?: false)

            // Flash availability
            obj.put("has_flash", chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))

            // Digital zoom max
            obj.put("max_digital_zoom", chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM))

            // JPEG sizes
            val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpegSizes = streamMap?.getOutputSizes(android.graphics.ImageFormat.JPEG)
            val jpegArr = JSONArray()
            jpegSizes?.forEach { jpegArr.put("${it.width}x${it.height}") }
            obj.put("jpeg_output_sizes", jpegArr)

            // YUV_420_888 sizes
            val yuvSizes = streamMap?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
            val yuvArr = JSONArray()
            yuvSizes?.forEach { yuvArr.put("${it.width}x${it.height}") }
            obj.put("yuv_output_sizes", yuvArr)

            // PRIVATE (preview / SurfaceTexture) sizes
            val privSizes = streamMap?.getOutputSizes(android.graphics.PixelFormat.RGBA_8888)
            val privArr = JSONArray()
            privSizes?.forEach { privArr.put("${it.width}x${it.height}") }
            obj.put("private_output_sizes", privArr)

            // Available request keys (names only)
            val reqKeys = chars.availableCaptureRequestKeys
            val reqKeyArr = JSONArray()
            reqKeys?.forEach { reqKeyArr.put(it.name) }
            obj.put("available_request_keys", reqKeyArr)

            // Available result keys (names only)
            val resKeys = chars.availableCaptureResultKeys
            val resKeyArr = JSONArray()
            resKeys?.forEach { resKeyArr.put(it.name) }
            obj.put("available_result_keys", resKeyArr)

            // Write to BOTH the global pool (always persists) and current session (if any)
            globalCharacteristics.put("camera_$cameraId", obj)
            currentSession.put("characteristics_$cameraId", obj)
            Log.i(TAG, "Logged CameraCharacteristics for camera $cameraId")
        } catch (e: Throwable) {
            Log.e(TAG, "Error logging characteristics", e)
        }
    }

    // -----------------------------------------------------------------------
    // B. CaptureResult
    // -----------------------------------------------------------------------

    fun logCaptureResult(result: TotalCaptureResult) {
        try {
            // Throttle: keep at most 200 results total
            if (globalCaptureResults.length() >= 200) return
            resultCount.incrementAndGet()

            val obj = JSONObject()

            // --- 3A States (the most important for KYC detection) ---
            val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
            obj.put("ae_state", aeState)
            obj.put("ae_state_name", aeStateName(aeState))

            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            obj.put("af_state", afState)
            obj.put("af_state_name", afStateName(afState))

            val awbState = result.get(CaptureResult.CONTROL_AWB_STATE)
            obj.put("awb_state", awbState)
            obj.put("awb_state_name", awbStateName(awbState))

            val aeMode = result.get(CaptureResult.CONTROL_AE_MODE)
            obj.put("ae_mode", aeMode)

            val afMode = result.get(CaptureResult.CONTROL_AF_MODE)
            obj.put("af_mode", afMode)

            // --- Sensor timestamps ---
            val sensorTs = result.get(CaptureResult.SENSOR_TIMESTAMP)
            obj.put("sensor_timestamp_ns", sensorTs)

            // --- Exposure ---
            val exposureTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            obj.put("exposure_time_ns", exposureTime)

            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            obj.put("iso", iso)

            val frameDuration = result.get(CaptureResult.SENSOR_FRAME_DURATION)
            obj.put("frame_duration_ns", frameDuration)

            // --- Lens ---
            val focusDist = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            obj.put("focus_distance", focusDist)

            val aperture = result.get(CaptureResult.LENS_APERTURE)
            obj.put("aperture", aperture)

            val focalLength = result.get(CaptureResult.LENS_FOCAL_LENGTH)
            obj.put("focal_length_mm", focalLength)

            val oisMode = result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
            obj.put("ois_mode", oisMode)

            // --- JPEG ---
            val jpegOrientation = result.get(CaptureResult.JPEG_ORIENTATION)
            obj.put("jpeg_orientation", jpegOrientation)

            val jpegQuality = result.get(CaptureResult.JPEG_QUALITY)
            obj.put("jpeg_quality", jpegQuality)

            // --- Flash ---
            val flashState = result.get(CaptureResult.FLASH_STATE)
            obj.put("flash_state", flashState)

            // --- Control mode ---
            val controlMode = result.get(CaptureResult.CONTROL_MODE)
            obj.put("control_mode", controlMode)

            // --- Noise reduction ---
            val nrMode = result.get(CaptureResult.NOISE_REDUCTION_MODE)
            obj.put("noise_reduction_mode", nrMode)

            // --- Scaler crop region ---
            val cropRegion = result.get(CaptureResult.SCALER_CROP_REGION)
            if (cropRegion != null) {
                obj.put("crop_region", "${cropRegion.left},${cropRegion.top},${cropRegion.right},${cropRegion.bottom}")
            }

            // --- Rolling shutter skew ---
            val rollingSkew = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW)
            obj.put("rolling_shutter_skew_ns", rollingSkew)

            resultBuffer.offer(obj)
            // Always also persist to global pool so data isn't lost if no session begins
            globalCaptureResults.put(obj)
        } catch (e: Throwable) {
            Log.e(TAG, "Error logging CaptureResult", e)
        }
    }

    // -----------------------------------------------------------------------
    // C. SurfaceTexture transform matrix
    // -----------------------------------------------------------------------

    /**
     * Call this from the getTransformMatrix hook (surveillance path).
     * @param cameraId  active camera id
     * @param isFront   true if front-facing
     * @param matrix    the 16-float column-major matrix returned by the OS
     */
    fun logTransformMatrix(cameraId: String, isFront: Boolean, matrix: FloatArray) {
        matrixFrameCount++
        val matStr = matrix.joinToString(",") { String.format("%.6f", it) }

        // Only record when matrix changes (avoids flooding with identical frames)
        if (matStr == lastMatrixStr) return
        lastMatrixStr = matStr

        val key = "cam${cameraId}_${if (isFront) "front" else "back"}"
        val seenSet = matrixHistory.getOrPut(key) { mutableSetOf() }
        if (seenSet.add(matStr)) {
            // New unique matrix — record it
            val matObj = JSONObject()
            matObj.put("camera_id", cameraId)
            matObj.put("is_front", isFront)
            matObj.put("frame_num", matrixFrameCount)
            matObj.put("matrix_4x4", matStr)
            // Also break it into rows for readability
            val rows = JSONArray()
            for (row in 0..3) {
                val rowArr = JSONArray()
                for (col in 0..3) rowArr.put(matrix[col * 4 + row]) // column-major
                rows.put(rowArr)
            }
            matObj.put("matrix_rows", rows)

            // Write to BOTH global pool (always persists) and current session
            globalMatrices.put(matObj)
            val matrixLog = currentSession.optJSONArray("transform_matrices") ?: JSONArray().also {
                currentSession.put("transform_matrices", it)
            }
            matrixLog.put(matObj)
            Log.i(TAG, "New transform matrix for $key: [$matStr]")
        }
    }

    // -----------------------------------------------------------------------
    // D. CaptureRequest fields
    // -----------------------------------------------------------------------

    fun logCaptureRequest(keyName: String, value: Any?) {
        if (globalCaptureRequests.length() >= 500) return
        val obj = JSONObject()
        obj.put("key", keyName)
        obj.put("value", value?.toString() ?: "null")
        requestBuffer.offer(obj)
        // Persist to global pool so data isn't lost without a session
        globalCaptureRequests.put(obj)
    }

    // -----------------------------------------------------------------------
    // E. Session geometry
    // -----------------------------------------------------------------------

    fun beginSession(cameraId: String, surfaces: List<Pair<Int, Pair<Int, Int>>>) {
        // Flush previous session before starting new one
        endSession()

        sessionIndex++
        currentSession = JSONObject()
        currentSession.put("session_index", sessionIndex)
        currentSession.put("camera_id", cameraId)
        currentSession.put("timestamp_ms", System.currentTimeMillis())
        resultCount.set(0)
        resultBuffer.clear()
        requestBuffer.clear()
        lastMatrixStr = ""
        matrixFrameCount = 0

        val surfaceArr = JSONArray()
        for ((fmt, size) in surfaces) {
            val s = JSONObject()
            s.put("format", fmt)
            s.put("format_name", formatName(fmt))
            s.put("width", size.first)
            s.put("height", size.second)
            surfaceArr.put(s)
        }
        currentSession.put("output_surfaces", surfaceArr)
        Log.i(TAG, "Audit: began session $sessionIndex for camera $cameraId")
    }

    fun endSession() {
        if (currentSession.length() == 0) return

        // Drain result buffer into session
        val resultsArr = JSONArray()
        while (resultBuffer.isNotEmpty()) {
            resultBuffer.poll()?.let { resultsArr.put(it) }
        }
        currentSession.put("capture_results", resultsArr)

        // Drain request buffer
        val requestsArr = JSONArray()
        while (requestBuffer.isNotEmpty()) {
            requestBuffer.poll()?.let { requestsArr.put(it) }
        }
        currentSession.put("capture_request_fields", requestsArr)

        sessions.put(currentSession)
        currentSession = JSONObject()
        flush()
    }

    // -----------------------------------------------------------------------
    // Flush to disk
    // -----------------------------------------------------------------------

    fun flush() {
        if (isFlushing.getAndSet(true)) return
        val snapshot = auditDoc.toString(2)
        // Capture target package and PID for per-process audit file
        val pkg = try { android.app.AndroidAppHelper.currentPackageName() ?: "unknown" } catch (_: Throwable) { "unknown" }
        val pid = android.os.Process.myPid()
        Thread {
            try {
                val dir = File(AUDIT_DIR)
                if (!dir.exists()) dir.mkdirs()
                // Single rolling file per (package, pid) — overwritten on each flush
                val fileName = "hw_audit_${pkg}_pid${pid}.json"
                val file = File(dir, fileName)
                FileWriter(file).use { it.write(snapshot) }
                Log.i(TAG, "Hardware audit saved to ${file.absolutePath} (${snapshot.length} chars)")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to write audit file", e)
            } finally {
                isFlushing.set(false)
            }
        }.also { it.name = "VirtuCam-AuditFlush"; it.start() }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun aeStateName(state: Int?) = when (state) {
        CaptureResult.CONTROL_AE_STATE_INACTIVE -> "INACTIVE"
        CaptureResult.CONTROL_AE_STATE_SEARCHING -> "SEARCHING"
        CaptureResult.CONTROL_AE_STATE_CONVERGED -> "CONVERGED"
        CaptureResult.CONTROL_AE_STATE_LOCKED -> "LOCKED"
        CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> "FLASH_REQUIRED"
        CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> "PRECAPTURE"
        null -> "NULL"
        else -> "UNKNOWN($state)"
    }

    private fun afStateName(state: Int?) = when (state) {
        CaptureResult.CONTROL_AF_STATE_INACTIVE -> "INACTIVE"
        CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> "PASSIVE_SCAN"
        CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> "PASSIVE_FOCUSED"
        CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> "ACTIVE_SCAN"
        CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> "FOCUSED_LOCKED"
        CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> "NOT_FOCUSED_LOCKED"
        CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> "PASSIVE_UNFOCUSED"
        null -> "NULL"
        else -> "UNKNOWN($state)"
    }

    private fun awbStateName(state: Int?) = when (state) {
        CaptureResult.CONTROL_AWB_STATE_INACTIVE -> "INACTIVE"
        CaptureResult.CONTROL_AWB_STATE_SEARCHING -> "SEARCHING"
        CaptureResult.CONTROL_AWB_STATE_CONVERGED -> "CONVERGED"
        CaptureResult.CONTROL_AWB_STATE_LOCKED -> "LOCKED"
        null -> "NULL"
        else -> "UNKNOWN($state)"
    }

    private fun formatName(fmt: Int) = when (fmt) {
        android.graphics.ImageFormat.JPEG -> "JPEG"
        android.graphics.ImageFormat.YUV_420_888 -> "YUV_420_888"
        android.graphics.ImageFormat.YV12 -> "YV12"
        android.graphics.ImageFormat.NV21 -> "NV21"
        android.graphics.PixelFormat.RGBA_8888 -> "RGBA_8888"
        0x22 -> "PRIVATE"
        0x1 -> "RGBA_8888(0x1)"
        35 -> "YCbCr_420_SP(35)"
        else -> "UNKNOWN(0x${fmt.toString(16)})"
    }
}
