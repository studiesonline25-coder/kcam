package com.virtucam.hooks

import android.app.AndroidAppHelper
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.media.Image
import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import android.os.Handler
import android.os.HandlerThread
import android.media.ImageReader
import android.media.ExifInterface
import java.util.Collections
import java.util.WeakHashMap
import java.util.HashSet

import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.virtucam.ModuleMain
import com.virtucam.media.StreamPlayer
import com.virtucam.media.VideoPlayer
import com.virtucam.opengl.EglCore
import com.virtucam.opengl.TextureRenderer

/**
 * Camera2 API Hooks - Surface Hijacking implementation
 * 
 * Intercepts camera session creation and substitutes the requested output Surface
 * with our own virtual rendering pipeline powered by OpenGL ES and MediaCodec.
 */
object CameraHook {
    
    private const val TAG = "VirtuCam_Hook"
    private var isEnabled = true
    @Volatile
    var isMirrored: Boolean = false
    
    @Volatile
    var isVideo: Boolean = false
    @Volatile
    var isStreamActive = false
    private var isStream = false
    private var streamUrl: String = ""
    private var targetPackage: String = ""

    @Volatile
    var isPassthroughMode: Boolean = false

    @Volatile
    var rotationOffset: Int = 0

    @Volatile
    private var lastSpyDumpMs: Long = 0L
    
    private val renderThreads = mutableListOf<Any>()
    // Strong references to prevent GC from destroying surfaces while native camera pipeline uses them
    private val dummySurfaces = mutableListOf<Surface>()
    private val dummyImageReaders = mutableListOf<ImageReader>()
    private var dummySinkThread: HandlerThread? = null
    private var dummySinkHandler: Handler? = null
    private var configLoaded = false
    // Maps original surfaces to their dummy replacements for CaptureRequest consistency
    private val surfaceMap = mutableMapOf<Surface, Surface>()
    private val activeBridges = mutableListOf<FormatConverterBridge>()
    
    // ThreadLocal flag to "mute" global hooks when our own logic is loading source media.
    // This prevents the "Final Boss" hook from forcing 90-degree rotation on input 1:1 images.
    private val isSourceLoading = ThreadLocal<Boolean>()

    @Volatile
    internal var activeCameraId: String = "0"
    @Volatile
    var xiaomiRequestedOrientation: Int = -1
    
    // Maps original surfaces to their formats for correct rendering
    internal val surfaceFormats = java.util.concurrent.ConcurrentHashMap<Surface, Int>()
    private val imageReaderSurfaces = java.util.concurrent.ConcurrentHashMap<Surface, android.media.ImageReader>()
    private val imageReaderToDummy = java.util.concurrent.ConcurrentHashMap<android.media.ImageReader, Surface>()
    private val surfaceTextureSurfaces = java.util.concurrent.ConcurrentHashMap<Surface, android.graphics.SurfaceTexture>()
    private val surfaceTextureToDummy = java.util.concurrent.ConcurrentHashMap<android.graphics.SurfaceTexture, Surface>()

    private val isProcessingCaptureSession = ThreadLocal<Boolean>()
    
    // Maintain strong references to dynamically registered Pine hooks to prevent GC
    private val pineHooks = java.util.concurrent.ConcurrentHashMap.newKeySet<Any>()
    
    // Track characteristics per cameraId
    private val cameraOrientations = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val cameraFacings = java.util.concurrent.ConcurrentHashMap<String, Int>() // 0=BACK, 1=FRONT
    
    // Cache real camera optical specs for dynamic injection (device-agnostic)
    private val cameraApertures = java.util.concurrent.ConcurrentHashMap<String, Float>()
    private val cameraFocalLengths = java.util.concurrent.ConcurrentHashMap<String, Float>()
    internal val cameraActiveArraySizes = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Rect>()
    
    // Global state for Late-Stage Storage Interception
    @Volatile var latestVirtualJpeg: ByteArray? = null
    @Volatile var latestVirtualJpegArea: Int = 0
    
    // global capture state for render threads
    @Volatile var captureCount = 0
    val captureQueue = java.util.concurrent.LinkedBlockingQueue<Pair<Long, Int>>()
    val formatBridges = java.util.concurrent.ConcurrentHashMap<android.util.Size, FormatConverterBridge>()
    
    // Hardware vsync synchronization for VirtualRenderThread
    val pendingHardwareFrames = java.util.concurrent.ConcurrentLinkedQueue<Long>()
    val frameSyncObject = Object()
    @Volatile var latestSensorTimestamp = 0L
    // [PERF OPT 3] Tracks the last time hardware sync was received.
    // Used by VirtualRenderThread to detect Camera1/burst-mode apps that never call notifyAll.
    @Volatile var lastFrameSyncMs = System.currentTimeMillis()
    
    // Global telemetry for surface dimensions
    private val surfaceSizes = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Surface, Pair<Int, Int>>())
    private val surfaceTextureSizes = java.util.Collections.synchronizedMap(java.util.WeakHashMap<SurfaceTexture, Pair<Int, Int>>())

    // Dynamic Xiaomi Vendor Tag Discovery
    private val discoveredXiaomiKeys = java.util.concurrent.ConcurrentHashMap<String, android.hardware.camera2.CaptureRequest.Key<*>>()
    
    @Volatile var zoomFactor: Float = 1.0f
    @Volatile var rtspUseTcp: Boolean = true
    @Volatile
    var isColorSwapped: Boolean = false

    @Volatile
    var isLivenessEnabled: Boolean = true

    @Volatile
    var isColorFlashEnabled: Boolean = true

    @Volatile
    var isTestPatternMode: Boolean = false

    @Volatile
    var isRefineEnabled: Boolean = false

    @Volatile
    var isRppgEnabled: Boolean = true  // Default ON — inject synthetic heartbeat

    @Volatile
    var rppgBpm: Int = 72  // Default 72 BPM

    // [STEALTH MODE] Global toggle for diagnostic logging (disable for production/KYC)
    @Volatile
    var enableDiagnosticLogs: Boolean = false  // Set to true for debugging

    // Signal for VirtualRenderThread to recreate EGL surfaces when browser renegotiates resolution
    val pendingSurfaceResize = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    var isGeneratingJpeg: Boolean = false

    @Volatile
    var jpegGenStartMs: Long = 0L

    @Volatile
    var isBufferCaptureEnabled: Boolean = false

    @Volatile
    var compensationFactor: Float = 1.0f
    
    @Volatile
    var lastRequestedOrientation: Int = -1 // -1 = NOT_SET
    
    // [Metadata Sync] Process-local sync to avoid collision with real hardware focus distance
    data class TransformationState(val compensationFactor: Float, val rotationOffset: Int, val isMirrored: Boolean)
    private val metadataCourierMap = java.util.concurrent.ConcurrentHashMap<Long, TransformationState>()
    
    // [STEALTH MODE] Conditional logging wrappers - logs only fire when enableDiagnosticLogs = true
    private fun logD(tag: String, msg: String) {
        if (enableDiagnosticLogs) android.util.Log.d(tag, msg)
    }
    
    private fun logE(tag: String, msg: String, throwable: Throwable? = null) {
        if (enableDiagnosticLogs) {
            if (throwable != null) android.util.Log.e(tag, msg, throwable)
            else android.util.Log.e(tag, msg)
        }
    }
    
    private fun logW(tag: String, msg: String) {
        if (enableDiagnosticLogs) android.util.Log.w(tag, msg)
    }
    
    private fun logV(tag: String, msg: String) {
        if (enableDiagnosticLogs) android.util.Log.v(tag, msg)
    }
    
    fun getLatestCouriedState(timestamp: Long): TransformationState? {
        // Find the closest state that is <= the current timestamp
        val keys = metadataCourierMap.keys().toList().sortedDescending()
        for (k in keys) {
            if (k <= timestamp) return metadataCourierMap[k]
        }
        return metadataCourierMap.values.firstOrNull() // Fallback
    }

    /**
     * Fresh [CameraCharacteristics.SENSOR_ORIENTATION] for [activeCameraId].
     * Cached map can be stale or missing (e.g. Camera1); never rely on the render-thread snapshot alone.
     */
    fun resolveSensorOrientationDeg(): Int {
        val id = activeCameraId
        val cached = cameraOrientations[id]
        if (cached != null) return normalizeOrientationDeg(cached)
        
        try {
            val app = AndroidAppHelper.currentApplication()
                ?: return 90
            val mgr = app.getSystemService(android.content.Context.CAMERA_SERVICE)
                as android.hardware.camera2.CameraManager
            val chars = mgr.getCameraCharacteristics(id)
            val o = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val normalized = normalizeOrientationDeg(o)
            cameraOrientations[id] = normalized
            return normalized
        } catch (_: Throwable) {
            return 90
        }
    }

    private fun normalizeOrientationDeg(deg: Int): Int = ((deg % 360) + 360) % 360

    /** Camera2 [CameraCharacteristics.LENS_FACING]: BACK=0, FRONT=1, EXTERNAL=2 — map to our 0=back 1=front. */
    private fun mapLensFacingForVirtuCam(facing: Int?): Int? {
        if (facing == null) return null
        return if (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) 1 else 0
    }

    fun isActiveCameraFrontFacing(): Boolean = cameraFacings[activeCameraId] == 1

    fun trackedSurfaceSize(surface: Surface): Pair<Int, Int>? = surfaceSizes[surface]

    /**
     * Saves a stream preview frame to internal storage.
     * Called from StreamPlayer's onFirstFrame callback on the main looper.
     * The file is then served via VirtuCamProvider at content://com.kcam.provider/stream_preview/stream_preview.jpg
     */
    fun saveStreamPreviewToProvider(bitmap: android.graphics.Bitmap) {
        Thread {
            try {
                val ctx = AndroidAppHelper.currentApplication() ?: return@Thread
                val file = java.io.File(ctx.filesDir, "stream_preview.jpg")
                file.outputStream().use { fos ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, fos)
                }
                bitmap.recycle()
                Log.d(TAG, "Stream preview saved to provider: ${file.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Stream preview save failed: ${e.message}")
            }
        }.start()
    }

    // [ONCE AND FOR ALL] Surface tracking and crash prevention structures
    private val hookedListenerClasses = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Class<*>, Boolean>())
    private val captureSurfaces = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Surface, Boolean>())
    private val videoSurfaces = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Surface, Boolean>())

    /**
     * Initialize all camera hooks
     */
    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "com.virtucam.config" || lpparam.packageName == "com.kcam") return

        Log.e("DIAGNOSTIC_VIRTUCAM", "VirtuCam_Hook: Initializing hooks for ${lpparam.packageName}")

        try {
            targetPackage = lpparam.packageName
            Log.d(TAG, "VirtuCam_Hook: Initializing hooks for $targetPackage")

            // Boot the hardware audit logger
            try {
                val deviceInfo = org.json.JSONObject()
                deviceInfo.put("manufacturer", android.os.Build.MANUFACTURER)
                deviceInfo.put("model", android.os.Build.MODEL)
                deviceInfo.put("device", android.os.Build.DEVICE)
                deviceInfo.put("brand", android.os.Build.BRAND)
                deviceInfo.put("android_version", android.os.Build.VERSION.RELEASE)
                deviceInfo.put("sdk_int", android.os.Build.VERSION.SDK_INT)
                deviceInfo.put("target_package", lpparam.packageName)
                HardwareAuditLogger.init(deviceInfo)
            } catch (_: Throwable) {}
            
            hookCameraManager(lpparam)
            hookImageReader(lpparam)
            hookSurfaceTexture(lpparam)
            hookCaptureRequest(lpparam)
            hookJitterMetadata(lpparam)
            hookSubmitCaptureRequest(lpparam)

            hookCaptureSurfaces(lpparam)
            hookCameraDevice(lpparam)
            hookCameraDeviceOutputConfigurations(lpparam)
            hookCamera1(lpparam)
            hookCaptureCallback(lpparam)
            
            if (lpparam.packageName == "com.android.camera" || lpparam.packageName.contains("miui")) {
                hookXiaomiBypass(lpparam)
                hookLazyClasses(lpparam) // Replaces hookXiaomiStorage and hookXiaomiParallelDeep
                hookFileOutputStream(lpparam)
                hookFilePathNormalization(lpparam)
                hookExifInterface(lpparam)
                hookMediaScanner(lpparam)
                hookContentResolver(lpparam)
                hookContentValues(lpparam)
                hookBroadcastIntents(lpparam)
                hookFileDeletionGuard(lpparam)
            }

            hookSensorOrientationSpoof(lpparam)
            hookContextWrapper(lpparam)
            hookMediaRecorderOrientation(lpparam)
            hookMediaFormatRotation(lpparam)
            hookMediaMuxerOrientation(lpparam)
            hookDisplayLayer(lpparam)
            Log.d(TAG, "VirtuCam_Hook: All hooks deployed successfully.")
        } catch (t: Throwable) {
            Log.e(TAG, "VirtuCam_Hook: CRITICAL failure in $targetPackage", t)
        }
    }

    /**
     * [The ClassLoader Rescue]
     * In sandbox environments like Meta Wolf, the Thread.currentThread().contextClassLoader 
     * often remains the Host's PathClassLoader. By hooking ContextWrapper, we can capture 
     * the actual plugin/guest Application context and its dedicated ClassLoader.
     */
    private fun hookContextWrapper(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            PineHelper.hookAllMethods(android.content.ContextWrapper::class.java, "attachBaseContext", object : PineHelper.PineCompatibleMethodHook() {
                override fun afterHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                    try {
                        val ctx = param.thisObject as? android.content.Context ?: return
                        applyDeferredHooksToClassLoader(ctx.classLoader)
                    } catch (_: Throwable) {}
                }
            })
        } catch (e: Throwable) {
            logE("DIAGNOSTIC_VIRTUCAM", "Failed to hook ContextWrapper", e)
        }
    }

    /**
     * [Late-Stage Storage Interception]
     * Instead of fighting the MiAlgoEngine earlier in the pipeline or trying to hijack 
     * early MediaStore URIs (which Xiaomi often deletes or overwrites), we let Xiaomi 
     * run its entire native Camera process. Right before the final image is committed 
     * from the cache to the permanent Gallery, we swap the payload with our spoofed JPEG.
     */
    private var isStorageHooked = false
    private var isPtdHooked = false
    private var isZipperHooked = false
    private var isAlgoHooked = false
    private val searchedClassLoaders = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<ClassLoader, Boolean>())

    private fun hookLazyClasses(lpparam: XC_LoadPackage.LoadPackageParam) {
        // We initially try the base ClassLoader
        applyDeferredHooksToClassLoader(lpparam.classLoader)
    }

    internal fun applyDeferredHooksToClassLoader(classLoader: ClassLoader?) {
        if (classLoader == null || !isEnabled) return
        if (isStorageHooked && isPtdHooked && isAlgoHooked) return
        
        // Only search each unique ClassLoader exactly once
        if (!searchedClassLoaders.add(classLoader)) return
        
        logE("DIAGNOSTIC_VIRTUCAM", "Searching new ClassLoader for Xiaomi classes: ${classLoader.javaClass.simpleName}")

        if (!isStorageHooked) {
            try {
                val storageClass = XposedHelpers.findClassIfExists("com.android.camera.storage.Storage", classLoader)
                if (storageClass != null) {
                    isStorageHooked = true
                    logE("DIAGNOSTIC_VIRTUCAM", "DEFERRED HOOK SUCCESS: Found com.android.camera.storage.Storage!")
                    applyStorageHooks(storageClass)
                }
            } catch (t: Throwable) {}
        }

        if (!isPtdHooked) {
            try {
                // Try both standard and xiaomi-branded package names
                val names = listOf("com.android.camera.ParallelTaskData", "com.xiaomi.camera.ParallelTaskData")
                for (name in names) {
                    val ptdClass = XposedHelpers.findClassIfExists(name, classLoader)
                    if (ptdClass != null) {
                        isPtdHooked = true
                        logE("DIAGNOSTIC_VIRTUCAM", "DEFERRED HOOK SUCCESS: Found $name!")
                        applyParallelTaskDataHooks(ptdClass)
                        break
                    }
                }
            } catch (t: Throwable) {}
        }

        if (!isZipperHooked) {
            try {
                // Target the zipper module directly if TaskData bypasses us
                val names = listOf("com.android.camera.ParallelDataZipper", "com.xiaomi.camera.ParallelDataZipper")
                for (name in names) {
                    val zipperClass = XposedHelpers.findClassIfExists(name, classLoader)
                    if (zipperClass != null) {
                        isZipperHooked = true
                        logE("DIAGNOSTIC_VIRTUCAM", "DEFERRED HOOK SUCCESS: Found $name!")
                        applyParallelDataZipperHooks(zipperClass)
                        break
                    }
                }
            } catch (t: Throwable) {}
        }

        if (!isAlgoHooked) {
            try {
                val names = listOf("com.android.camera.module.loader.camera2.AlgorithmManager", "com.xiaomi.camera.module.loader.camera2.AlgorithmManager")
                for (name in names) {
                    val algoManagerClass = XposedHelpers.findClassIfExists(name, classLoader)
                    if (algoManagerClass != null) {
                        isAlgoHooked = true
                        logE("DIAGNOSTIC_VIRTUCAM", "DEFERRED HOOK SUCCESS: Found $name!")
                        applyAlgorithmManagerHooks(algoManagerClass)
                        break
                    }
                }
            } catch (t: Throwable) {}
        }
    }

    private fun applyStorageHooks(storageClass: Class<*>) {
        try {
            val methods = storageClass.declaredMethods
            logE("DIAGNOSTIC_VIRTUCAM", "Storage class loaded! Total declared methods: ${methods.size}")
            for (m in methods) {
                val paramTypes = m.parameterTypes.joinToString(", ") { it.simpleName }
                logE("DIAGNOSTIC_VIRTUCAM", "  Storage method: ${m.name}($paramTypes) -> ${m.returnType.simpleName}")
            }
        } catch (t: Throwable) {
            logE("DIAGNOSTIC_VIRTUCAM", "Failed to enumerate Storage methods", t)
        }

        // 1. Boolean Squasher + Physical Copy + Manual Scan: Hook by name first, then by reflection as fallback
        val squashAndScanHook = object : PineHelper.PineCompatibleMethodHook() {
            override fun beforeHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                try {
                    if (!isEnabled) return
                    var filePath: String? = null
                    for (j in param.args.indices) {
                        if (param.args[j] is Boolean) {
                            param.args[j] = false
                        }
                        val arg = param.args[j]
                        if (arg is String && arg.endsWith(".jpg", true) && arg.contains("DCIM", true)) {
                            filePath = arg
                        }
                    }
                    logD("DIAGNOSTIC_VIRTUCAM", "${param.method.name}: Squashed all booleans to false")

                    if (filePath != null) {
                        val normalizedPath = if (filePath.contains("scopedStorage", true)) {
                            val idx = filePath.indexOf("DCIM/Camera")
                            if (idx > 0) "/storage/emulated/0/" + filePath.substring(idx) else filePath
                        } else filePath

                        // PHYSICAL COPY: MiAlgoEngine writes natively to OBB, bypassing Java File hooks.
                        // By the time updateImage or addImage occurs (if not parallel), the file should exist.
                        // We must physically copy it to the public DCIM directory before triggering the scan.
                        if (filePath.contains("/Android/obb/", true) || filePath.contains("/Android/data/", true)) {
                            val originalFile = java.io.File(filePath)
                            if (originalFile.exists() && originalFile.length() > 0) {
                                val destFile = java.io.File(normalizedPath)
                                destFile.parentFile?.mkdirs()
                                try {
                                    originalFile.copyTo(destFile, overwrite = true)
                                    logE("DIAGNOSTIC_VIRTUCAM", "PHYSICAL COPY SUCCESS in ${param.method.name}: Copied OBB file to \${normalizedPath}")
                                } catch (e: Exception) {
                                    logE("DIAGNOSTIC_VIRTUCAM", "Failed to physically copy file", e)
                                }
                            } else {
                                logE("DIAGNOSTIC_VIRTUCAM", "WARNING: OBB file does not exist or is empty at \$filePath when ${param.method.name} was called!")
                            }
                        }

                        triggerManualScan(normalizedPath)
                    }
                } catch (t: Throwable) {
                    logE("DIAGNOSTIC_VIRTUCAM", "squashAndScan hook error", t)
                }
            }
        }

        // Try named hooks first
        val addImageHooks = PineHelper.hookAllMethods(storageClass, "addImage", squashAndScanHook)
        val updateImageHooks = PineHelper.hookAllMethods(storageClass, "updateImage", squashAndScanHook)
        logE("DIAGNOSTIC_VIRTUCAM", "hookAllMethods('addImage') returned ${addImageHooks.size} hooks")
        logE("DIAGNOSTIC_VIRTUCAM", "hookAllMethods('updateImage') returned ${updateImageHooks.size} hooks")

        // [NUCLEAR FALLBACK] If named hooks returned 0, hook ALL methods with boolean args
        if (addImageHooks.isEmpty()) {
            logE("DIAGNOSTIC_VIRTUCAM", "addImage hook FAILED! Falling back to hooking ALL Storage methods.")
            try {
                for (method in storageClass.declaredMethods) {
                    val hasBooleanParam = method.parameterTypes.any { it == Boolean::class.javaPrimitiveType || it == java.lang.Boolean::class.java }
                    if (hasBooleanParam) {
                        top.canyie.pine.Pine.hook(method, squashAndScanHook)
                        logE("DIAGNOSTIC_VIRTUCAM", "  Hooked Storage.${method.name}() [has boolean param]")
                    }
                }
            } catch (t: Throwable) {
                logE("DIAGNOSTIC_VIRTUCAM", "Storage reflection hook error", t)
            }
        }

        // 2. Payload Replacement Hook
        val replaceImageHook = object : PineHelper.PineCompatibleMethodHook() {
            override fun beforeHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                try {
                    if (!isEnabled) return
                    val virtualJpeg = latestVirtualJpeg ?: return

                    var swapped = false
                    for (i in param.args.indices) {
                        val arg = param.args[i]
                        if (arg is ByteArray && arg.size > 100000) {
                            param.args[i] = virtualJpeg
                            swapped = true
                        } else if (arg is String && arg.endsWith(".jpg", true) && arg.contains("cache", true)) {
                            val file = java.io.File(arg)
                            if (file.exists() && file.canWrite()) {
                                file.writeBytes(virtualJpeg)
                                swapped = true
                            }
                        } else if (arg is java.io.File && arg.absolutePath.endsWith(".jpg", true) && arg.absolutePath.contains("cache", true)) {
                            if (arg.exists() && arg.canWrite()) {
                                arg.writeBytes(virtualJpeg)
                                swapped = true
                            }
                        }
                    }

                    if (swapped) {
                        Log.w(TAG, "VirtuCam_Storage: BOOM! Successfully swapped payload in ${param.method.name}()!")
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            latestVirtualJpeg = null
                        }, 2000)
                    }
                } catch (_: Throwable) {}
            }
        }
        PineHelper.hookAllMethods(storageClass, "addImage", replaceImageHook)
        PineHelper.hookAllMethods(storageClass, "updateImage", replaceImageHook)
        PineHelper.hookAllMethods(storageClass, "saveToCloud", replaceImageHook)
    }


    
    /**
     * [Storage Redirection Fix]
     * In some environments (LSPatch/VirtualApp), the camera's DCIM path is redirected to an internal OBB folder.
     * The system Gallery scanner does NOT check OBB folders, causing photos to "disappear".
     * We strip this redirection prefix to force the file into the real public DCIM folder.
     */
    private fun hookFilePathNormalization(lpparam: XC_LoadPackage.LoadPackageParam) {
        val fileClass = java.io.File::class.java
        PineHelper.hookAllConstructors(fileClass, object : PineHelper.PineCompatibleMethodHook() {
            override fun beforeHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                if (!isEnabled) return
                if (param.args.isEmpty()) return
                val firstArg = param.args[0]
                if (firstArg is String) {
                    val originalPath = firstArg
                    if (originalPath.contains("scopedStorage", ignoreCase = true) && originalPath.contains("DCIM/Camera", ignoreCase = true)) {
                        val dcimIndex = originalPath.indexOf("DCIM/Camera")
                        if (dcimIndex > 0) {
                            val newPath = "/storage/emulated/0/" + originalPath.substring(dcimIndex)
                            param.args[0] = newPath
                            logE("DIAGNOSTIC_VIRTUCAM", "Storage Normalization: Stripped OEM Redirect! $originalPath -> $newPath")
                        }
                    }
                }
            }
        })
    }

    /**
     * [The "Final Boss" Metadata Override]
     * Force ALL orientation reads (android.media and androidx) to return 1 (Normal).
     * This ensures the gallery never rotates our already-upright pixels.
     */
    private fun hookExifInterface(lpparam: XC_LoadPackage.LoadPackageParam) {
        // [HARDWARE PARITY FIX] Disabled. Let the OS handle real EXIF orientation physically.
    }

    
    /**
     * [The Ultimate Safety Net]
     * If Xiaomi's AlgoEngine or MIVI bypasses the Storage.java API (Scenario 2),
     * we hook the low-level FileOutputStream to catch any .jpg writes in temp/cache dirs.
     */
    private fun hookFileOutputStream(lpparam: XC_LoadPackage.LoadPackageParam) {
        PineHelper.findAndHookConstructor(
            "java.io.FileOutputStream",
            lpparam.classLoader,
            java.io.File::class.java,
            Boolean::class.javaPrimitiveType,
            object : PineHelper.PineCompatibleMethodHook() {
                override fun beforeHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                    try {
                        if (!isEnabled) return
                        val arg = param.args[0]
                        if (arg is String) {
                            if (arg.contains("scopedStorage", true) && arg.contains("DCIM/Camera", true)) {
                                val idx = arg.indexOf("DCIM/Camera")
                                if (idx > 0) param.args[0] = "/storage/emulated/0/" + arg.substring(idx)
                            }
                        }
                        val virtualJpeg = latestVirtualJpeg ?: return
                        val file = if (arg is java.io.File) arg else if (arg is String) java.io.File(arg) else return
                        val path = file.absolutePath
                        XposedHelpers.setAdditionalInstanceField(param.thisObject, "vcPath", path)
                        
                        // [Fix] Skip our own EXIF injection temp files
                        if (path.contains("vc_exif_inject", true)) return
                        
                        // Only intercept if it looks like a camera capture artifact
                        if (path.endsWith(".jpg", true) && (path.contains("dcim", true) || path.contains("camera", true) || path.contains("cache", true))) {
                            // We don't block the constructor, but we will soon overwrite the content
                            Log.w(TAG, "VirtuCam_Storage: FileOutputStream detected for $path. Preparing for late-stage swap.")
                        }
                    } catch (_: Throwable) {}
                }
            }
        )
        
        PineHelper.findAndHookMethod(
            "java.io.FileOutputStream",
            lpparam.classLoader,
            "write",
            ByteArray::class.java,
            object : PineHelper.PineCompatibleMethodHook() {
                override fun beforeHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                    try {
                        if (!isEnabled) return
                        val virtualJpeg = latestVirtualJpeg ?: return
                        val data = param.args[0] as? ByteArray ?: return
                        val path = XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcPath") as? String ?: ""
                        
                        // [Fix] Skip our own EXIF injection and thumbnail files
                        if (path.contains("vc_exif_inject", true)) return
                        if (path.contains("thumb", true)) {
                            Log.v(TAG, "VirtuCam_Storage: Skipping thumbnail swap for $path")
                            return
                        }
                        
                        // Overwrite writes that likely represent the full image payload
                        // JPEG Magic Bytes: FF D8 FF
                        val isJpeg = data.size > 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte()
                        
                        if (isJpeg && data.size > 10000) { 
                             val magicBytes = String.format("%02X %02X %02X %02X", data[0], data[1], data[2], data[3])
                             Log.e(TAG, "[TELEMETRY] FileOutputStream.write() (ByteArray) intercepting! RealDataSize=${data.size}, VirtualDataSize=${virtualJpeg.size}, Path=$path, Magic=$magicBytes")
                             param.args[0] = virtualJpeg
                             Log.w(TAG, "VirtuCam_Storage: FileOutputStream.write() (ByteArray) SWAPPED successfully! path=$path")
                        }
                    } catch (_: Throwable) {}
                }
            }
        )

        PineHelper.findAndHookMethod(
            "java.io.FileOutputStream",
            lpparam.classLoader,
            "write",
            ByteArray::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            object : PineHelper.PineCompatibleMethodHook() {
                override fun beforeHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                    try {
                        if (!isEnabled) return
                        val virtualJpeg = latestVirtualJpeg ?: return
                        val data = param.args[0] as? ByteArray ?: return
                        val len = param.args[2] as Int
                        val path = XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcPath") as? String ?: ""
                        
                        // [FIX] Skip our own EXIF injection temp files
                        if (path.contains("vc_exif_inject")) return
                        // [FIX] Skip thumbnails so we don't overwrite them with a full JPEG
                        if (path.contains("thumb", true)) return
                        
                        val isJpeg = data.size > 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte()
                        
                        if (isJpeg && len > 10000) {
                            val magicBytes = String.format("%02X %02X %02X %02X", data[0], data[1], data[2], data[3])
                            Log.e(TAG, "[TELEMETRY] FileOutputStream.write(b,off,len) intercepting! RealDataSize=${data.size}, RealLen=$len, VirtualDataSize=${virtualJpeg.size}, Path=$path, Magic=$magicBytes")
                            param.args[0] = virtualJpeg
                            param.args[1] = 0
                            param.args[2] = virtualJpeg.size
                            Log.w(TAG, "VirtuCam_Storage: FileOutputStream.write() (Offset) SWAPPED successfully! path=$path (Forced size: ${virtualJpeg.size})")
                        }
                    } catch (_: Throwable) {}
                }
            }
        )
    }


    /**
     * [The Scanner Fix]
     * Normalize paths passed to the MediaScanner to ensure it scans the real DCIM folder,
     * not the sandboxed OBB folder.
     */
    private fun hookMediaScanner(lpparam: XC_LoadPackage.LoadPackageParam) {
        val scannerClass = XposedHelpers.findClassIfExists("android.media.MediaScannerConnection", lpparam.classLoader) ?: return
        PineHelper.hookAllMethods(scannerClass, "scanFile", object : PineHelper.PineCompatibleMethodHook() {
            override fun beforeHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                if (!isEnabled) return
                if (param.args.size < 2) return
                val paths = param.args[1] as? Array<String> ?: return
                for (i in paths.indices) {
                    val path = paths[i]
                    if (path != null && path.contains("scopedStorage", true) && path.contains("DCIM/Camera", true)) {
                        val idx = path.indexOf("DCIM/Camera")
                        if (idx > 0) {
                            paths[i] = "/storage/emulated/0/" + path.substring(idx)
                            logE("DIAGNOSTIC_VIRTUCAM", "MediaScanner Normalization: $path -> ${paths[i]}")
                        }
                    }
                }
            }
        })
    }

    /**
     * [The MediaStore Fix + Manual Gallery Scan]
     * This is the MOST RELIABLE hook point since ContentResolver.insert() ALWAYS fires,
     * even when the Storage class can't be found (LSPatch lazy classloading).
     * We normalize paths, force orientation=0, and trigger a manual gallery scan.
     */
    private fun hookContentResolver(lpparam: XC_LoadPackage.LoadPackageParam) {
        val resolverClass = android.content.ContentResolver::class.java
        
        val resolverHook = object : PineHelper.PineCompatibleMethodHook() {
            override fun beforeHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                try {
                    if (!isEnabled) return
                    // Only care about images MediaStore inserts
                    val uri = param.args.firstOrNull { it is android.net.Uri } as? android.net.Uri
                    if (uri != null && !uri.toString().contains("images")) return
                    
                    val values = param.args.firstOrNull { it is android.content.ContentValues } as? android.content.ContentValues ?: return
                    
                    // [DIAGNOSTIC] Log ALL keys in ContentValues to find the path key
                    try {
                        val keys = values.keySet()
                        val kvPairs = keys.joinToString(", ") { k -> "$k=${values.get(k)}" }
                        logE("DIAGNOSTIC_VIRTUCAM", "ContentResolver INSERT keys: $kvPairs")
                    } catch (_: Throwable) {}
                    
                    // 1. Path Normalization (try _data first)
                    val dataPath = values.getAsString("_data")
                    if (dataPath != null && dataPath.contains("scopedStorage", true) && dataPath.contains("DCIM/Camera", true)) {
                        val idx = dataPath.indexOf("DCIM/Camera")
                        if (idx > 0) {
                            val normalizedPath = "/storage/emulated/0/" + dataPath.substring(idx)
                            values.put("_data", normalizedPath)
                            logE("DIAGNOSTIC_VIRTUCAM", "ContentResolver Normalization: $dataPath -> $normalizedPath")
                        }
                    }
                    
                    // 1b. Also normalize relative_path if present
                    val relativePath = values.getAsString("relative_path")
                    if (relativePath != null && relativePath.contains("scopedStorage", true)) {
                        values.put("relative_path", "DCIM/Camera/")
                        logE("DIAGNOSTIC_VIRTUCAM", "ContentResolver: Forced relative_path=DCIM/Camera/ (was: $relativePath)")
                    }
                    
                    // 2. Orientation Normalization (MediaStore)
                    if (values.containsKey("orientation")) {
                         values.put("orientation", 0)
                         logE("DIAGNOSTIC_VIRTUCAM", "ContentResolver: Forced orientation=0 in ContentValues")
                    }

                    // 3. Construct scan path (but DON'T scan yet — file hasn't been written)
                    // Store the path for afterHookedMethod to use with a delay
                    var scanPath: String? = values.getAsString("_data")
                    if (scanPath == null) {
                        val displayName = values.getAsString("_display_name")
                        val relPath = (values.getAsString("relative_path") ?: "DCIM/Camera/").trimEnd('/')
                        if (displayName != null && displayName.endsWith(".jpg", true)) {
                            scanPath = "/storage/emulated/0/$relPath/$displayName"
                        }
                    }
                    
                    if (scanPath != null && scanPath.endsWith(".jpg", true)) {
                        if (scanPath.contains("scopedStorage", true)) {
                            val idx = scanPath.indexOf("DCIM/Camera")
                            if (idx > 0) scanPath = "/storage/emulated/0/" + scanPath.substring(idx)
                        }
                        // Store normalized path for afterHookedMethod
                        param.thisObject?.let {
                            XposedHelpers.setAdditionalInstanceField(it, "virtucam_scanPath", scanPath)
                        }
                        
                        // Also construct and store the ACTUAL OBB path where the file physically exists
                        // (Native MiAlgoEngine writes bypass Java File hooks)
                        val displayName = values.getAsString("_display_name")
                        if (displayName != null) {
                            val obbPath = "/storage/emulated/0/Android/obb/top.bienvenido.saas.i18n/scopedStorage/com.android.camera/DCIM/Camera/$displayName"
                            param.thisObject?.let {
                                XposedHelpers.setAdditionalInstanceField(it, "virtucam_obbPath", obbPath)
                            }
                        }
                    }

                } catch (_: Exception) {}
            }
            
            override fun afterHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                try {
                    if (!isEnabled) return
                    // After insert(), get the returned URI and trigger a broadcast scan
                    val resultUri = param.result as? android.net.Uri ?: return
                    if (!resultUri.toString().contains("images")) return
                    
                    logE("DIAGNOSTIC_VIRTUCAM", "ContentResolver INSERT returned URI: $resultUri")
                    
                    // Extract _display_name directly from the ContentValues in args
                    val values = param.args.firstOrNull { it is android.content.ContentValues } as? android.content.ContentValues
                    val displayName = values?.getAsString("_display_name")
                    
                    val context = de.robv.android.xposed.XposedHelpers.callStaticMethod(
                        Class.forName("android.app.ActivityThread"),
                        "currentApplication"
                    ) as? android.content.Context
                    
                    if (context != null) {
                        // 1. Send broadcast immediately
                        val scanIntent = android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, resultUri)
                        context.sendBroadcast(scanIntent)
                        logE("DIAGNOSTIC_VIRTUCAM", "Sent MEDIA_SCANNER broadcast for URI: $resultUri")
                    }
                    
                    // 2. URI DIRECT WRITE RESCUE
                    // In Meta Wolf sandbox, MiAlgoEngine never writes to disk (writeFile cost_ms = 0).
                    // Instead of polling for a file that will never appear, we write the JPEG ourselves.
                    if (displayName != null && displayName.endsWith(".jpg", true)) {
                        val dcimPath = "/storage/emulated/0/DCIM/Camera/$displayName"
                        val capturedUri = resultUri // Capture for thread
                        
                        Thread {
                            try {
                                // [PHASE 16 PERFORMANCE] Poll for latestVirtualJpeg instead of fixed 1.5s sleep.
                                // This solves the "Camera App Hanging/Lag" issue during capture.
                                var jpegData: ByteArray? = null
                                for (i in 0 until 50) { // Max 2.5 seconds (50 * 50ms)
                                    jpegData = latestVirtualJpeg
                                    if (jpegData != null && jpegData.size > 1024) break
                                    Thread.sleep(50)
                                }
                                
                                if (jpegData != null && jpegData.size > 1024) {
                                    // === PRIMARY RESCUE: Write directly into MediaStore URI ===
                                    var uriWriteSuccess = false
                                    try {
                                        val resolver = context?.contentResolver
                                        if (resolver != null) {
                                            val os = resolver.openOutputStream(capturedUri)
                                            if (os != null) {
                                                os.write(jpegData)
                                                os.flush()
                                                os.close()
                                                uriWriteSuccess = true
                                                logE("DIAGNOSTIC_VIRTUCAM", "URI DIRECT WRITE SUCCESS: Wrote ${jpegData.size} bytes to $capturedUri")
                                                
                                                // Clear is_pending flag IMMEDIATELY so gallery can see it and app unblocks
                                                val updateValues = android.content.ContentValues()
                                                updateValues.put("is_pending", 0)
                                                try {
                                                    resolver.update(capturedUri, updateValues, null, null)
                                                    logE("DIAGNOSTIC_VIRTUCAM", "Cleared is_pending flag for $capturedUri")
                                                } catch (_: Throwable) {}
                                            }
                                        }
                                    } catch (e: Throwable) {
                                        logE("DIAGNOSTIC_VIRTUCAM", "URI Direct Write failed: ${e.message}")
                                    }
                                    
                                    // === SECONDARY: Also write physical backup to DCIM ===
                                    try {
                                        val dcimFile = java.io.File(dcimPath)
                                        dcimFile.parentFile?.mkdirs()
                                        
                                        // Use ContentResolver for physical write if FileOutputStream fails with EACCES
                                        java.io.FileOutputStream(dcimFile).use { fos ->
                                            fos.write(jpegData)
                                            fos.flush()
                                        }
                                        logE("DIAGNOSTIC_VIRTUCAM", "PHYSICAL BACKUP SUCCESS: Wrote ${jpegData.size} bytes to $dcimPath")
                                    } catch (e: Throwable) {
                                        logE("DIAGNOSTIC_VIRTUCAM", "Physical backup write failed (EACCES?): ${e.message}. URI write was: $uriWriteSuccess")
                                    }
                                    
                                    if (uriWriteSuccess) {
                                        triggerManualScan(dcimPath)
                                        return@Thread 
                                    }
                                } else {
                                    logE("DIAGNOSTIC_VIRTUCAM", "URI Direct Write: No latestVirtualJpeg available after 2.5s. Falling back to file polling...")
                                }
                                
                                // === TERTIARY FALLBACK: Original file polling (reduced) ===
                                val obbPath = "/storage/emulated/0/Android/obb/top.bienvenido.saas.i18n/scopedStorage/com.android.camera/DCIM/Camera/$displayName"
                                val searchPaths = listOf(
                                    obbPath,
                                    "/storage/emulated/0/Android/data/top.bienvenido.saas.i18n/files/DCIM/Camera/$displayName",
                                    "/storage/emulated/0/Android/data/top.bienvenido.saas.i18n/files/$displayName",
                                    "/data/user/0/top.bienvenido.saas.i18n/files/$displayName",
                                    "/data/user/0/top.bienvenido.saas.i18n/app_data_anon/storage/emulated/0/DCIM/Camera/$displayName",
                                    "/data/user/0/top.bienvenido.saas.i18n/app_data_anon/storage/emulated/0/DCIM/$displayName",
                                    "/sdcard/Android/data/top.bienvenido.saas.i18n/app_data_anon/storage/emulated/0/DCIM/Camera/$displayName"
                                )
                                
                                for (attempt in 1..5) {
                                    Thread.sleep(2000)
                                    var foundFile: java.io.File? = null
                                    for (p in searchPaths) {
                                        val f = java.io.File(p)
                                        if (f.exists() && f.length() > 1024) {
                                            foundFile = f
                                            break
                                        }
                                    }
                                    
                                    if (foundFile != null) {
                                        try {
                                            val dcimFile = java.io.File(dcimPath)
                                            dcimFile.parentFile?.mkdirs()
                                            
                                            val tempFile = java.io.File(context?.cacheDir, "rescue_temp.jpg")
                                            foundFile.copyTo(tempFile, overwrite = true)
                                            tempFile.copyTo(dcimFile, overwrite = true)
                                            tempFile.delete()
                                            
                                            logE("DIAGNOSTIC_VIRTUCAM", "PHYSICAL RESCUE SUCCESS (Attempt $attempt): Found at ${foundFile.absolutePath}, rescued to $dcimPath")
                                            triggerManualScan(dcimPath)
                                            break 
                                        } catch (e: Exception) {
                                            logE("DIAGNOSTIC_VIRTUCAM", "Physical Rescue Copy Error", e)
                                        }
                                    } else {
                                        logE("DIAGNOSTIC_VIRTUCAM", "Physical Rescue Attempt $attempt: File not found in any sandbox yet.")
                                    }
                                }
                            } catch (_: Throwable) {}
                        }.start()
                    }
                } catch (_: Throwable) {}
            }


        }
        
        PineHelper.hookAllMethods(resolverClass, "insert", resolverHook)
        PineHelper.hookAllMethods(resolverClass, "update", resolverHook)
    }



    /**
     * [Nuclear Option: ContentValues Hook]
     * Intercept path and orientation settings at the lowest level.
     */
    private fun hookContentValues(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cvClass = android.content.ContentValues::class.java
        
        PineHelper.findAndHookMethod(cvClass, "put", String::class.java, String::class.java, object : PineHelper.PineCompatibleMethodHook() {
            override fun beforeHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                if (!isEnabled) return
                val key = param.args[0] as String
                val value = param.args[1] as? String ?: return
                if (key == "_data" && value.contains("scopedStorage", true) && value.contains("DCIM/Camera", true)) {
                    val idx = value.indexOf("DCIM/Camera")
                    if (idx > 0) {
                        val normalized = "/storage/emulated/0/" + value.substring(idx)
                        param.args[1] = normalized
                        logV("DIAGNOSTIC_VIRTUCAM", "ContentValues Path Normalization: $value -> $normalized")
                        
                        // Force a manual scan the moment the database receives the correct path
                        triggerManualScan(normalized)
                    }
                }
            }
        })

        PineHelper.findAndHookMethod(cvClass, "put", String::class.java, Integer::class.java, object : PineHelper.PineCompatibleMethodHook() {
            override fun beforeHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                if (!isEnabled) return
                val key = param.args[0] as String
                if (key == "orientation") {
                    val originalValue = param.args[1] as Int
                    if (originalValue != 0) {
                        param.args[1] = 0 
                        logE("DIAGNOSTIC_VIRTUCAM", "ContentValues Orientation SPOOFED: $originalValue -> 0")
                    }
                }
            }
        })
    }

    /**
     * [The Scanner Broadcast Fix]
     * Intercept MediaScanner intents to ensure they point to the physical DCIM file.
     */
    private fun hookBroadcastIntents(lpparam: XC_LoadPackage.LoadPackageParam) {
        val contextWrapperClass = android.content.ContextWrapper::class.java
        PineHelper.hookAllMethods(contextWrapperClass, "sendBroadcast", object : PineHelper.PineCompatibleMethodHook() {
            override fun beforeHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                if (!isEnabled) return
                val intent = param.args[0] as? android.content.Intent ?: return
                val action = intent.action ?: return
                
                if (action == "android.hardware.action.NEW_PICTURE" || 
                    action == "android.hardware.action.NEW_VIDEO" ||
                    action == "com.android.camera.NEW_PICTURE") {
                    
                    Log.d(TAG, "VirtuCam_Hook: Suppressing NEW_PICTURE/VIDEO broadcast.")
                    param.result = null
                }
                
                if (action == "android.intent.action.MEDIA_SCANNER_SCAN_FILE") {
                    val uri = intent.data ?: return
                    val path = uri.path ?: return
                    if (path.contains("scopedStorage", true) && path.contains("DCIM/Camera", true)) {
                        val idx = path.indexOf("DCIM/Camera")
                        if (idx > 0) {
                            val newPath = "/storage/emulated/0/" + path.substring(idx)
                            intent.setData(android.net.Uri.fromFile(java.io.File(newPath)))
                            logE("DIAGNOSTIC_VIRTUCAM", "Scanner Broadcast Normalization: $path -> $newPath")
                        }
                    }
                }
            }
        })
    }
          
    private fun hookJitterMetadata(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cameraCaptureSessionClass = XposedHelpers.findClassIfExists(
            "android.hardware.camera2.impl.CameraCaptureSessionImpl", lpparam.classLoader
        ) ?: return

        PineHelper.hookAllMethods(cameraCaptureSessionClass, "setRepeatingRequest", object : PineHelper.PineCompatibleMethodHook() {
            override fun beforeHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                if (!isEnabled) return
                val request = param.args[0] as? android.hardware.camera2.CaptureRequest ?: return
                
                // Anti-Detection: Jitter the metadata
                if (isLivenessEnabled) {
                    jitterCaptureRequest(request)
                }
            }
        })
    }

    private fun jitterCaptureRequest(request: android.hardware.camera2.CaptureRequest) {
        // [REMOVED] Random jitter replaced with smooth sine-based metadata variation
        // in onCaptureCompleted callback (lines 1235-1243). Random jitter looks
        // synthetic and can be detected by ML models. Smooth sine waves mimic
        // real PID controller behavior in camera 3A algorithms.
    }

    private fun setCaptureRequestField(request: android.hardware.camera2.CaptureRequest, key: Any, value: Any) {
        try {
            val mSettings = XposedHelpers.getObjectField(request, "mSettings")
            XposedHelpers.callMethod(mSettings, "set", key, value)
        } catch (_: Throwable) {}
    }

    /**
     * Deep intercept of Xiaomi-specific classes that manage the "Parallel" flag.
     * These apply directly to the lazily-loaded classes intercepted by hookLazyClasses.
     */
    private fun applyParallelTaskDataHooks(ptdClass: Class<*>) {
        try {
            PineHelper.hookAllMethods(ptdClass, "setParallel", object : PineHelper.PineCompatibleMethodHook() {
                override fun beforeHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                    param.args[0] = false
                    logD("DIAGNOSTIC_VIRTUCAM", "ParallelTaskData: Forced setParallel(false)")
                }
            })
            // Also squash isParallel getter just in case
            PineHelper.hookAllMethods(ptdClass, "isParallel", object : PineHelper.PineCompatibleMethodHook() {
                override fun afterHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                    param.result = false
                }
            })
        } catch (t: Throwable) {
            logE("DIAGNOSTIC_VIRTUCAM", "Failed to apply ParallelTaskData hooks", t)
        }
    }

    private fun applyParallelDataZipperHooks(zipperClass: Class<*>) {
        try {
            // Hook the final assembly of parallel results
            PineHelper.hookAllMethods(zipperClass, "setResult", object : PineHelper.PineCompatibleMethodHook() {
                override fun beforeHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                    // Try to force the zipper to THINK it's not parallel anymore
                    // This is aggressive and might crash, but it's our best bet to stop the redirect
                    logD("DIAGNOSTIC_VIRTUCAM", "ParallelDataZipper: Intercepted setResult")
                }
            })
            
            // Hook any method that returns a ParallelTaskData object to squash its "isParallel" flag
            PineHelper.hookAllMethods(zipperClass, "getParallelTaskData", object : PineHelper.PineCompatibleMethodHook() {
                override fun afterHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                    val ptd = param.result
                    if (ptd != null) {
                        try {
                            XposedHelpers.setBooleanField(ptd, "mIsParallel", false)
                            logD("DIAGNOSTIC_VIRTUCAM", "ParallelDataZipper: Squashed mIsParallel in returned PTD object")
                        } catch (_: Exception) {}
                    }
                }
            })
        } catch (t: Throwable) {
            logE("DIAGNOSTIC_VIRTUCAM", "Failed to apply ParallelDataZipper hooks", t)
        }
    }

    private fun applyAlgorithmManagerHooks(algoManagerClass: Class<*>) {
        try {
            PineHelper.hookAllMethods(algoManagerClass, "isParallelCaptureEnabled", object : PineHelper.PineCompatibleMethodHook() {
                override fun afterHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                    param.result = false
                }
            })
        } catch (t: Throwable) {
            logE("DIAGNOSTIC_VIRTUCAM", "Failed to apply AlgorithmManager hooks", t)
        }
    }

    /**
     * [The Disappearance Guard]
     * Log if the app tries to delete our physical DCIM file.
     */
    private fun hookFileDeletionGuard(lpparam: XC_LoadPackage.LoadPackageParam) {
        PineHelper.findAndHookMethod(java.io.File::class.java, "delete", object : PineHelper.PineCompatibleMethodHook() {
            override fun beforeHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                if (!isEnabled) return
                val file = param.thisObject as java.io.File
                val path = file.absolutePath
                if (path.contains("DCIM/Camera", true) && path.endsWith(".jpg", true)) {
                    logE("DIAGNOSTIC_VIRTUCAM", "CRITICAL WARNING: App attempted to DELETE physical photo! Path: $path")
                    // We let it proceed for now to see IF it happens, but we can block it later if needed.
                }
            }
        })
    }

    private fun triggerManualScan(path: String) {
        try {
            val context = AndroidAppHelper.currentApplication()
            if (context == null) {
                logE("DIAGNOSTIC_VIRTUCAM", "Manual Scan FAILED: No Application Context available for $path")
                return
            }
            logE("DIAGNOSTIC_VIRTUCAM", "Triggering MANUAL System Scan for: $path")
            android.media.MediaScannerConnection.scanFile(context, arrayOf(path), null) { scannedPath, uri ->
                logE("DIAGNOSTIC_VIRTUCAM", "MANUAL Scan Completed! Path: $scannedPath, URI: $uri")
            }
        } catch (e: Exception) {
            logE("DIAGNOSTIC_VIRTUCAM", "Failed to trigger manual scan", e)
        }
    }


    /**
     * Hook CameraCaptureSession.capture() and setRepeatingRequest() to intercept
     * the CaptureCallback and identify the exact moment a photo is taken.
     */
    private fun hookCaptureCallback(lpparam: XC_LoadPackage.LoadPackageParam) {
        val sessionClass = XposedHelpers.findClassIfExists(
            "android.hardware.camera2.impl.CameraCaptureSessionImpl", lpparam.classLoader
        )
        if (sessionClass == null) {
            Log.e(TAG, "AUDIT: CameraCaptureSessionImpl class NOT FOUND — capture callback hook will not engage")
            return
        }
        Log.e(TAG, "AUDIT: CameraCaptureSessionImpl found, installing capture hooks")

        // [HARDWARE AUDIT] Always-on wrapper that records CaptureResults regardless of isEnabled.
        // This is separate from the spoofing logic below so audit data is captured even when OFF.
        val auditOnlyHook = object : PineHelper.PineCompatibleMethodHook() {
            override fun beforeHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                try {
                    val callbackIndex = 1
                    val originalCallback = if (param.args.size > callbackIndex)
                        param.args[callbackIndex] as? android.hardware.camera2.CameraCaptureSession.CaptureCallback
                        else null
                    if (originalCallback == null) return
                    val auditWrapped = object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureStarted(session: android.hardware.camera2.CameraCaptureSession, request: android.hardware.camera2.CaptureRequest, timestamp: Long, frameNumber: Long) {
                            originalCallback.onCaptureStarted(session, request, timestamp, frameNumber)
                        }
                        override fun onCaptureCompleted(session: android.hardware.camera2.CameraCaptureSession, request: android.hardware.camera2.CaptureRequest, result: android.hardware.camera2.TotalCaptureResult) {
                            try { HardwareAuditLogger.logCaptureResult(result) } catch (_: Throwable) {}
                            originalCallback.onCaptureCompleted(session, request, result)
                        }
                        override fun onCaptureFailed(session: android.hardware.camera2.CameraCaptureSession, request: android.hardware.camera2.CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                            originalCallback.onCaptureFailed(session, request, failure)
                        }
                    }
                    // Only wrap if the spoofing path won't (i.e., when disabled).
                    // When VirtuCam is enabled, the next hook below will replace the callback again.
                    if (!isEnabled) param.args[callbackIndex] = auditWrapped
                } catch (_: Throwable) {}
            }
        }
        PineHelper.hookAllMethods(sessionClass, "capture", auditOnlyHook)
        PineHelper.hookAllMethods(sessionClass, "captureBurst", auditOnlyHook)
        PineHelper.hookAllMethods(sessionClass, "setRepeatingRequest", auditOnlyHook)
        PineHelper.hookAllMethods(sessionClass, "setRepeatingBurst", auditOnlyHook)

        val callbackHook = object : PineHelper.PineCompatibleMethodHook() {
            override fun beforeHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                try {
                    if (!isEnabled) return
                    val callbackIndex = if (param.method.name == "capture") 1 else 1
                    val originalCallback = if (param.args.size > callbackIndex) param.args[callbackIndex] as? android.hardware.camera2.CameraCaptureSession.CaptureCallback else null
                    
                    // [BROWSER CAPTURE FIX] Even with null callback, increment captureCount for capture() calls.
                    // Browsers often pass null CaptureCallback and rely on ImageReader.onImageAvailable instead.
                    // Without this, captureCount stays 0 and the JPEG bridge never gets rendered to.
                    if (originalCallback == null) {
                        if (param.method.name == "capture" || param.method.name == "captureBurst") {
                            synchronized(CameraHook) {
                                captureCount++
                                captureQueue.offer(Pair(android.os.SystemClock.elapsedRealtimeNanos(), captureCount))
                                Log.e(TAG, "CAPT_LOG [1]: Capture Event Detected (null callback). Method=${param.method.name}, captureCount=${captureCount}")
                            }
                        }
                        return
                    }
                    
                    // Wrap the original callback to intercept onCaptureCompleted
                    val wrappedCallback = object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureStarted(session: android.hardware.camera2.CameraCaptureSession, request: android.hardware.camera2.CaptureRequest, timestamp: Long, frameNumber: Long) {
                            originalCallback.onCaptureStarted(session, request, timestamp, frameNumber)
                        }
                        
                                override fun onCaptureCompleted(session: android.hardware.camera2.CameraCaptureSession, request: android.hardware.camera2.CaptureRequest, result: android.hardware.camera2.TotalCaptureResult) {
                                    try {
                                        val sensorTimestamp = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) ?: android.os.SystemClock.elapsedRealtimeNanos()

                                        // [ABSOLUTE PARITY] Directly store the current UI state for this frame's timestamp
                                        // This replaces the LENS_FOCUS_DISTANCE courier which was colliding with real hardware.
                                        metadataCourierMap[sensorTimestamp] = TransformationState(compensationFactor, rotationOffset, isMirrored)
                                        
                                        // Cleanup old entries (Keep last 2 seconds)
                                        if (metadataCourierMap.size > 120) {
                                            val cutoff = sensorTimestamp - 2_000_000_000L
                                            metadataCourierMap.keys.removeIf { it < cutoff }
                                        }

                                        // 1.5 Intercept capture count for bridge triggering
                                        if (param.method.name == "capture" || param.method.name == "captureBurst") {
                                            synchronized(CameraHook) {
                                                captureCount++
                                                captureQueue.offer(Pair(sensorTimestamp, captureCount))
                                                Log.e(TAG, "CAPT_LOG [1]: Capture Event Detected in hookCaptureCallback! Method=${param.method.name}. TS=$sensorTimestamp, captureCount=${captureCount}, QueueSize=${captureQueue.size}")
                                            }
                                        }

                                        // === ANTI-DETECTION: Hardware Metadata Animation ===
                                        // Static metadata flags synthetic models. Animate AE state and exposure time.
                                        try {
                                            val mResultsField = XposedHelpers.findFieldIfExists(result.javaClass as Class<*>, "mResults")
                                            if (mResultsField != null) {
                                                mResultsField.isAccessible = true
                                                val metadataNative = mResultsField.get(result)
                                                if (metadataNative != null) {
                                                    val currentFrame = captureCount
                                                    
                                                    // [ULTRA-REALISTIC NOISE] Perlin noise + CMOS sensor model (replaces sine waves)
                                                    
                                                    // 1. Realistic Auto-Exposure State (natural state machine behavior)
                                                    val aeState = RealisticNoiseGenerator.realisticAeState(currentFrame.toLong())
                                                    setResultMetadata(metadataNative, "android.control.aeState", aeState)
                                                    
                                                    // 2. Realistic Exposure Time (multi-octave Perlin + CMOS noise)
                                                    val baseExposure = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: 33_333_333L
                                                    val realisticExposure = RealisticNoiseGenerator.realisticExposureTime(currentFrame.toLong(), baseExposure)
                                                    setResultMetadata(metadataNative, "android.sensor.exposureTime", realisticExposure)

                                                    // 3. Realistic ISO Sensitivity (Perlin + shot noise + read noise)
                                                    val baseIso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: 200
                                                    val realisticIso = RealisticNoiseGenerator.realisticIsoSensitivity(currentFrame.toLong(), baseIso)
                                                    setResultMetadata(metadataNative, "android.sensor.sensitivity", realisticIso)

                                                    // [HARDWARE PARITY] Inject real device optical specs (device-agnostic)
                                                    val aperture = cameraApertures[activeCameraId]
                                                    val focalLength = cameraFocalLengths[activeCameraId]
                                                    if (aperture != null) {
                                                        setResultMetadata(metadataNative, "android.lens.aperture", aperture)
                                                    }
                                                    if (focalLength != null) {
                                                        setResultMetadata(metadataNative, "android.lens.focalLength", focalLength)
                                                    }
                                                    
                                                    // [FACE DETECTION] Inject STATISTICS_FACES from ML Kit detection
                                                    try {
                                                        val detectedFaces = FaceDetectionHelper.getCachedFaces()
                                                        if (detectedFaces.isNotEmpty()) {
                                                            setResultMetadata(metadataNative, "android.statistics.faces", detectedFaces)
                                                            // Force face detect mode to FULL so SDKs process the injected faces
                                                            setResultMetadata(metadataNative, "android.statistics.faceDetectMode", 2) // 2 = FULL
                                                            if (enableDiagnosticLogs) {
                                                                logD(TAG, "Injected ${detectedFaces.size} face(s) into STATISTICS_FACES")
                                                            }
                                                        }
                                                    } catch (e: Exception) {
                                                        // Face injection is optional, don't crash
                                                        if (enableDiagnosticLogs) {
                                                            logW(TAG, "Failed to inject STATISTICS_FACES: ${e.message}")
                                                        }
                                                    }
                                                }
                                            }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Failed to animate metadata", e)
                                        }

                                // [HARDWARE AUDIT] Always log CaptureResult (read-only surveillance)
                                try { HardwareAuditLogger.logCaptureResult(result) } catch (_: Throwable) {}

                                // [Surgical Hardening] Trigger bridge push synchronized by SENSOR_TIMESTAMP
                                try {
                                    val timestamp = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) ?: 0L
                                    if (timestamp > 0) {
                                        CameraHook.latestSensorTimestamp = timestamp
                                        synchronized(CameraHook.frameSyncObject) {
                                            CameraHook.lastFrameSyncMs = System.currentTimeMillis()
                                            CameraHook.frameSyncObject.notifyAll()
                                        }
                                        val targets = try {
                                            val getTargetsMethod = android.hardware.camera2.CaptureRequest::class.java.getDeclaredMethod("getTargets")
                                            getTargetsMethod.isAccessible = true
                                            getTargetsMethod.invoke(request) as? Collection<*>
                                        } catch (e: Throwable) { null }
                                        
                                        activeBridges.forEach { bridge ->
                                            // [HYBRID TUNING] NEVER push to JPEG bridges via Writer. 
                                            // The synchronous acquireNextImage hook is more reliable and 
                                            // avoids the "dequeue buffer failed" collisions we see in logs.
                                            if (bridge.outputFormat == 256) return@forEach
                                            
                                            // [FIX] Targeted pushing: Only push to bridges that are ACTUALLY requested!
                                            // Prevents clogging the massive capture ImageReader with 30fps preview frames.
                                            if (bridge.outputSurface != null) {
                                                if (targets != null) {
                                                    var isRequested = false
                                                    for (t in targets) {
                                                        val orig = surfaceMap[t] ?: t
                                                        if (orig == bridge.outputSurface) {
                                                            isRequested = true
                                                            break
                                                        }
                                                    }
                                                    if (isRequested) {
                                                        bridge.pushLatestFrameToWriter(timestamp)
                                                    }
                                                } else {
                                                    bridge.pushLatestFrameToWriter(timestamp)
                                                }
                                            }
                                        }
                                    }
                                } catch (_: Exception) {}

                            } catch (e: Exception) {}
                            originalCallback.onCaptureCompleted(session, request, result)
                        }
                        
                        override fun onCaptureFailed(session: android.hardware.camera2.CameraCaptureSession, request: android.hardware.camera2.CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                            originalCallback.onCaptureFailed(session, request, failure)
                        }
                    }
                    param.args[callbackIndex] = wrappedCallback
                } catch (e: Exception) {
                    Log.e(TAG, "Error wrapping CaptureCallback", e)
                }
            }
        }

        PineHelper.hookAllMethods(sessionClass, "capture", callbackHook)
        PineHelper.hookAllMethods(sessionClass, "captureBurst", callbackHook)
        PineHelper.hookAllMethods(sessionClass, "setRepeatingRequest", callbackHook)

        // [BROWSER CAPTURE FIX] Hook API 28+ Executor-based capture methods.
        // Chromium and modern apps may use captureSingleRequest(CaptureRequest, Executor, CaptureCallback)
        // instead of capture(CaptureRequest, CaptureCallback, Handler).
        // The callback is at index 2 (not 1) in these methods.
        val executorCallbackHook = object : PineHelper.PineCompatibleMethodHook() {
            override fun beforeHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                try {
                    if (!isEnabled) return
                    // captureSingleRequest(CaptureRequest, Executor, CaptureCallback) → callback at index 2
                    val callbackIndex = 2
                    val originalCallback = if (param.args.size > callbackIndex) param.args[callbackIndex] as? android.hardware.camera2.CameraCaptureSession.CaptureCallback else null
                    
                    if (originalCallback == null) return
                    
                    val wrappedCallback = object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureStarted(session: android.hardware.camera2.CameraCaptureSession, request: android.hardware.camera2.CaptureRequest, timestamp: Long, frameNumber: Long) {
                            originalCallback.onCaptureStarted(session, request, timestamp, frameNumber)
                        }
                        
                        override fun onCaptureCompleted(session: android.hardware.camera2.CameraCaptureSession, request: android.hardware.camera2.CaptureRequest, result: android.hardware.camera2.TotalCaptureResult) {
                            try {
                                val sensorTimestamp = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) ?: android.os.SystemClock.elapsedRealtimeNanos()
                                metadataCourierMap[sensorTimestamp] = TransformationState(compensationFactor, rotationOffset, isMirrored)
                                
                                if (metadataCourierMap.size > 120) {
                                    val cutoff = sensorTimestamp - 2_000_000_000L
                                    metadataCourierMap.keys.removeIf { it < cutoff }
                                }

                                // captureSingleRequest and captureBurstRequests
                                if (param.method.name == "captureSingleRequest" || param.method.name == "captureBurstRequests") {
                                    synchronized(CameraHook) {
                                        captureCount++
                                        captureQueue.offer(Pair(sensorTimestamp, captureCount))
                                        Log.e(TAG, "CAPT_LOG [1]: Capture Event Detected via ${param.method.name}! TS=$sensorTimestamp, captureCount=${captureCount}")
                                    }
                                }

                                // Bridge push (same as callbackHook but for executor-based methods)
                                try {
                                    val timestamp = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) ?: 0L
                                    if (timestamp > 0) {
                                        CameraHook.latestSensorTimestamp = timestamp
                                        synchronized(CameraHook.frameSyncObject) {
                                            CameraHook.lastFrameSyncMs = System.currentTimeMillis()
                                            CameraHook.frameSyncObject.notifyAll()
                                        }
                                        val targets = try {
                                            val getTargetsMethod = android.hardware.camera2.CaptureRequest::class.java.getDeclaredMethod("getTargets")
                                            getTargetsMethod.isAccessible = true
                                            getTargetsMethod.invoke(request) as? Collection<*>
                                        } catch (e: Throwable) { null }
                                        
                                        activeBridges.forEach { bridge ->
                                            if (bridge.outputFormat == 256) return@forEach
                                            
                                            // [FIX] Targeted pushing: Only push to bridges that are ACTUALLY requested!
                                            if (bridge.outputSurface != null) {
                                                if (targets != null) {
                                                    var isRequested = false
                                                    for (t in targets) {
                                                        val orig = surfaceMap[t] ?: t
                                                        if (orig == bridge.outputSurface) {
                                                            isRequested = true
                                                            break
                                                        }
                                                    }
                                                    if (isRequested) {
                                                        bridge.pushLatestFrameToWriter(timestamp)
                                                    }
                                                } else {
                                                    bridge.pushLatestFrameToWriter(timestamp)
                                                }
                                            }
                                        }
                                    }
                                } catch (_: Exception) {}

                            } catch (e: Exception) {}
                            originalCallback.onCaptureCompleted(session, request, result)
                        }
                        
                        override fun onCaptureFailed(session: android.hardware.camera2.CameraCaptureSession, request: android.hardware.camera2.CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                            originalCallback.onCaptureFailed(session, request, failure)
                        }
                    }
                    param.args[callbackIndex] = wrappedCallback
                } catch (e: Exception) {
                    Log.e(TAG, "Error wrapping CaptureCallback (Executor variant)", e)
                }
            }
        }
        PineHelper.hookAllMethods(sessionClass, "captureSingleRequest", executorCallbackHook)
        PineHelper.hookAllMethods(sessionClass, "captureBurstRequests", executorCallbackHook)
        PineHelper.hookAllMethods(sessionClass, "setSingleRepeatingRequest", executorCallbackHook)
    }

    /**
     * Redirect system-level Xiaomi logs to our diagnostic stream.
     */


    /**
     * Spoof Sensor Orientation to 0 to prevent double-rotation for apps that ignore JPEG_ORIENTATION tags.
     */
    private fun hookSensorOrientationSpoof(lpparam: XC_LoadPackage.LoadPackageParam) {
        // [HARDWARE PARITY FIX] Disabled spoofing. We now report the true 
        // sideways sensor orientation (e.g. 270) to the OS, just like a real camera.
    }

    /**
     * Suppress CameraDevice.StateCallback.onError to prevent "Can't connect to camera" errors.
     * When the camera HAL encounters issues with our dummy surfaces, it fires onError(),
     * which causes the app to show an error dialog or crash. We suppress this entirely.
     */


    private fun hookCaptureSurfaces(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val mediaCodecClass = XposedHelpers.findClassIfExists("android.media.MediaCodec", lpparam.classLoader)
            if (mediaCodecClass != null) {
                PineHelper.hookAllMethods(mediaCodecClass, "createInputSurface", object : PineHelper.PineCompatibleMethodHook() {
                    override fun afterHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                        val s = param.result as? Surface ?: return
                        captureSurfaces.add(s)
                        videoSurfaces.add(s)
                        Log.d(TAG, "VirtuCam_Hook: Tracked MediaCodec Capture Surface")
                    }
                })
            }
            
            val mediaRecorderClass = XposedHelpers.findClassIfExists("android.media.MediaRecorder", lpparam.classLoader)
            if (mediaRecorderClass != null) {
                PineHelper.hookAllMethods(mediaRecorderClass, "getSurface", object : PineHelper.PineCompatibleMethodHook() {
                    override fun afterHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                        val s = param.result as? Surface ?: return
                        captureSurfaces.add(s)
                        videoSurfaces.add(s)
                        Log.d(TAG, "VirtuCam_Hook: Tracked MediaRecorder Capture Surface")
                    }
                })
            }
        } catch (_: Throwable) {}
    }

    @Volatile
    private var lastOpenedCameraId: String? = "0"

    private fun hookCameraManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        val managerClass = XposedHelpers.findClassIfExists("android.hardware.camera2.CameraManager", lpparam.classLoader) ?: return

        // 1. Hook standard openCamera(String, StateCallback, Handler)
        PineHelper.findAndHookMethod(managerClass, "openCamera", String::class.java, "android.hardware.camera2.CameraDevice.StateCallback", Handler::class.java, object : PineHelper.PineCompatibleMethodHook() {
            override fun beforeHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                try {
                    applyDeferredHooksToClassLoader(Thread.currentThread().contextClassLoader)
                    val cameraId = param.args[0] as String
                    activeCameraId = cameraId
                    Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: Tracked Camera $cameraId opening via Handler")
                    val manager = param.thisObject as android.hardware.camera2.CameraManager
                    val chars = manager.getCameraCharacteristics(cameraId)
                    discoverXiaomiVendorTags(chars)
                    val facing = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                    if (facing != null) cameraFacings[cameraId] = mapLensFacingForVirtuCam(facing) ?: 0
                    cameraOrientations[cameraId] = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                } catch (e: Throwable) {}
            }
        })

        // 2. Hook modern openCamera(String, Executor, StateCallback) - CRITICAL FOR BROWSERS
        PineHelper.findAndHookMethod(managerClass, "openCamera", String::class.java, java.util.concurrent.Executor::class.java, "android.hardware.camera2.CameraDevice.StateCallback", object : PineHelper.PineCompatibleMethodHook() {
            override fun beforeHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                try {
                    applyDeferredHooksToClassLoader(Thread.currentThread().contextClassLoader)
                    val cameraId = param.args[0] as String
                    activeCameraId = cameraId
                    Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: Tracked Camera $cameraId opening via Executor")
                    val manager = param.thisObject as android.hardware.camera2.CameraManager
                    val chars = manager.getCameraCharacteristics(cameraId)
                    discoverXiaomiVendorTags(chars)
                    val facing = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                    if (facing != null) cameraFacings[cameraId] = mapLensFacingForVirtuCam(facing) ?: 0
                    cameraOrientations[cameraId] = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                } catch (e: Throwable) {}
            }
        })

        // 3. Hook extension openCamera(String, Int, Executor, StateCallback)
        try {
            PineHelper.findAndHookMethod(managerClass, "openCamera", String::class.java, Int::class.javaPrimitiveType, java.util.concurrent.Executor::class.java, "android.hardware.camera2.CameraDevice.StateCallback", object : PineHelper.PineCompatibleMethodHook() {
                override fun beforeHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                    try {
                        applyDeferredHooksToClassLoader(Thread.currentThread().contextClassLoader)
                        val cameraId = param.args[0] as String
                        activeCameraId = cameraId
                        Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: Tracked Camera $cameraId opening via Extension")
                        val manager = param.thisObject as android.hardware.camera2.CameraManager
                        val chars = manager.getCameraCharacteristics(cameraId)
                        discoverXiaomiVendorTags(chars)
                        val facing = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                        if (facing != null) cameraFacings[cameraId] = mapLensFacingForVirtuCam(facing) ?: 0
                        cameraOrientations[cameraId] = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                    } catch (e: Throwable) {}
                }
            })
        } catch (_: Throwable) {}

        PineHelper.hookAllMethods(managerClass, "getCameraCharacteristics", object : PineHelper.PineCompatibleMethodHook() {
            override fun afterHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                val cameraId = param.args[0] as? String ?: "unknown"
                val char = param.result as? android.hardware.camera2.CameraCharacteristics ?: return
                
                // DIAGNOSTIC: Log key characteristics Veriff might be checking
                val facing = char.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                val orientation = char.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                val level = char.get(android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                
                // [HARDWARE PARITY] Mi 11 Ultra is a flagship - force LEVEL_3
                try {
                    val nativeChar = XposedHelpers.getObjectField(char, "mProperties")
                    XposedHelpers.callMethod(nativeChar, "set", android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL, 3) // 3 = LEVEL_3
                } catch (_: Exception) {}

                cameraOrientations[cameraId] = orientation
                
                // Cache real optical specs for dynamic injection (device-agnostic)
                val apertures = char.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                val focalLengths = char.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val activeArraySize = char.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                
                if (apertures != null && apertures.isNotEmpty()) {
                    cameraApertures[cameraId] = apertures[0]  // Use first (widest) aperture
                }
                if (focalLengths != null && focalLengths.isNotEmpty()) {
                    cameraFocalLengths[cameraId] = focalLengths[0]  // Use first (primary) focal length
                }
                if (activeArraySize != null) {
                    cameraActiveArraySizes[cameraId] = activeArraySize
                }
                
                Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: getCameraCharacteristics($cameraId) -> Facing=$facing, Orient=$orientation, HW_Level=$level, Aperture=${cameraApertures[cameraId]}, FocalLen=${cameraFocalLengths[cameraId]}")
                
                val streamMap = char.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                if (streamMap != null) {
                    val sizes = streamMap.getOutputSizes(android.graphics.ImageFormat.JPEG)
                    Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: Camera $cameraId supports ${sizes?.size ?: 0} JPEG sizes. Max: ${sizes?.firstOrNull()}")
                }

                // [HARDWARE AUDIT] Always record real characteristics regardless of enabled state
                try { HardwareAuditLogger.logCharacteristics(cameraId, char) } catch (_: Throwable) {}
            }
        })
    }

    /**
     * Hook CaptureRequest.Builder.addTarget() to swap original surfaces with dummy ones.
     * Without this, setRepeatingRequest() crashes with "unconfigured Input/Output Surface"
     */
    private fun hookCaptureRequest(lpparam: XC_LoadPackage.LoadPackageParam) {
        val builderClass = XposedHelpers.findClassIfExists(
            "android.hardware.camera2.CaptureRequest\$Builder", lpparam.classLoader
        ) ?: return

        // [TOTAL SURVEILLANCE] Log all settings sent to the hardware
        pineHooks.addAll(PineHelper.hookAllMethods(builderClass, "set", object : PineHelper.PineCompatibleMethodHook() {
            override fun beforeHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                try {
                    val key = param.args[0]
                    val value = param.args[1]
                    val keyName = XposedHelpers.callMethod(key, "getName") as? String ?: "unknown"
                    
                    // High-priority metadata to track for Hardware Parity
                    if (keyName.contains("orientation", true) || 
                        keyName.contains("crop", true) || 
                        keyName.contains("rotation", true) ||
                        keyName.contains("control.mode", true)) {
                        Log.e(TAG, "SURVEILLANCE: Request.set($keyName) -> $value")
                        
                        // [DYNAMIC ROTATION PARITY]
                        // Xiaomi Native camera uses this to tell the HAL how to orient the buffer.
                        if (keyName == "xiaomi.device.orientation") {
                            xiaomiRequestedOrientation = value as? Int ?: -1
                        }
                    }

                    // [HARDWARE AUDIT] Always record request fields (read-only surveillance)
                    try { HardwareAuditLogger.logCaptureRequest(keyName, value) } catch (_: Throwable) {}
                } catch (_: Throwable) {}
            }
        }))

        pineHooks.addAll(PineHelper.hookAllMethods(builderClass, "addTarget", object : PineHelper.PineCompatibleMethodHook() {
            override fun beforeHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                try {
                    val originalSurface = param.args[0] as? Surface ?: return
                    
                    // Log surface details even if not enabled
                    val size = surfaceSizes[originalSurface] ?: Pair(0, 0)
                    val fmt = surfaceFormats[originalSurface] ?: -1
                    Log.e(TAG, "SURVEILLANCE: addTarget() -> Surface(${size.first}x${size.second}, Fmt=$fmt)")

                    if (!isEnabled) return
                    
                    var dummySurface = surfaceMap[originalSurface]
                    if (dummySurface == null) {
                        val reader = imageReaderSurfaces[originalSurface]
                        if (reader != null) dummySurface = imageReaderToDummy[reader]
                        else {
                            val st = surfaceTextureSurfaces[originalSurface]
                            if (st != null) dummySurface = surfaceTextureToDummy[st]
                        }
                    }
                    
                    if (dummySurface != null) {
                        Log.d(TAG, "VirtuCam_Hook: CaptureRequest.addTarget() → swapped to dummy surface")
                        param.args[0] = dummySurface
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "VirtuCam_Hook: Error in CaptureRequest.addTarget hook", t)
                }
            }
        }))
    }


    /**
     * [Xiaomi Parallel Bypass] Force legacy synchronous capture path by disabling 
     * proprietary background processing features that reject virtual buffers.
     */
    private fun hookXiaomiBypass(lpparam: XC_LoadPackage.LoadPackageParam) {
        val manufacturer = android.os.Build.MANUFACTURER ?: ""
        if (!manufacturer.equals("Xiaomi", ignoreCase = true) &&
            !manufacturer.equals("POCO", ignoreCase = true) &&
            !manufacturer.equals("Redmi", ignoreCase = true)) {
            return
        }

        try {
            val builderClass = XposedHelpers.findClassIfExists(
                "android.hardware.camera2.CaptureRequest\$Builder", lpparam.classLoader
            ) ?: return

            pineHooks.addAll(PineHelper.hookAllMethods(builderClass, "build", object : PineHelper.PineCompatibleMethodHook() {
                override fun beforeHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                    try {
                        if (!isEnabled) return
                        val builder = param.thisObject
                        XposedHelpers.callMethod(builder, "set", XposedHelpers.getStaticObjectField(android.hardware.camera2.CaptureRequest::class.java, "CONTROL_ENABLE_ZSL"), false)
                        XposedHelpers.callMethod(builder, "set", XposedHelpers.getStaticObjectField(android.hardware.camera2.CaptureRequest::class.java, "XIAOMI_MFNR_ENABLED"), false)
                        XposedHelpers.callMethod(builder, "set", XposedHelpers.getStaticObjectField(android.hardware.camera2.CaptureRequest::class.java, "XIAOMI_HDR_SR_ENABLED"), false)
                        Log.e(TAG, "VirtuCam_Hook: Xiaomi Parallel Bypass applied to CaptureRequest.")
                    } catch (_: Throwable) {}
                }
            }))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hook Xiaomi Capture Pipeline bypass", e)
        }
    }

    private fun setXiaomiVendorTag(builder: Any, name: String, value: Any) {
        try {
            // First check if we've discovered this key from the Hardware HAL
            val discoveredKey = discoveredXiaomiKeys[name]
            if (discoveredKey != null) {
                XposedHelpers.callMethod(builder, "set", discoveredKey, value)
                Log.v(TAG, "XiaomiBypass: Set HAL-verified tag $name success")
                return
            }

            // Fallback: Reflection to create keys for hidden vendor tags
            val keyClass = Class.forName("android.hardware.camera2.CaptureRequest\$Key")
            val keyConstructor = keyClass.getDeclaredConstructor(String::class.java, Class::class.java)
            keyConstructor.isAccessible = true
            
            // Map Kotlin primitive classes to Java primitives (required for Camera2 keys)
            val valueClass = when(value) {
                is Byte -> java.lang.Byte.TYPE
                is Int -> java.lang.Integer.TYPE
                is Boolean -> java.lang.Boolean.TYPE
                is Long -> java.lang.Long.TYPE
                else -> value::class.java
            }
            
            val key = keyConstructor.newInstance(name, valueClass)
            XposedHelpers.callMethod(builder, "set", key, value)
            Log.v(TAG, "XiaomiBypass: Set $name (reflected) success")
        } catch (_: Throwable) {}
    }

    private fun setVendorTagInternal(settings: Any, name: String, value: Any) {
        try {
            val keyClass = Class.forName("android.hardware.camera2.CaptureRequest\$Key")
            val keyConstructor = keyClass.getDeclaredConstructor(String::class.java, Class::class.java)
            keyConstructor.isAccessible = true
            val valueClass = when(value) {
                is Byte -> java.lang.Byte.TYPE
                is Int -> java.lang.Integer.TYPE
                is Boolean -> java.lang.Boolean.TYPE
                is Long -> java.lang.Long.TYPE
                else -> value::class.java
            }
            val key = keyConstructor.newInstance(name, valueClass)
            XposedHelpers.callMethod(settings, "set", key, value)
        } catch (_: Throwable) {}
    }

    private fun setResultMetadata(settings: Any, name: String, value: Any) {
        try {
            val keyClass = Class.forName("android.hardware.camera2.CaptureResult\$Key")
            val keyConstructor = keyClass.getDeclaredConstructor(String::class.java, Class::class.java)
            keyConstructor.isAccessible = true
            val valueClass = when(value) {
                is Byte -> java.lang.Byte.TYPE
                is Int -> java.lang.Integer.TYPE
                is Boolean -> java.lang.Boolean.TYPE
                is Long -> java.lang.Long.TYPE
                else -> value::class.java
            }
            val key = keyConstructor.newInstance(name, valueClass)
            XposedHelpers.callMethod(settings, "set", key, value)
        } catch (_: Throwable) {}
    }

    private fun discoverXiaomiVendorTags(characteristics: android.hardware.camera2.CameraCharacteristics) {
        try {
            val keys = characteristics.availableCaptureRequestKeys
            var count = 0
            for (key in keys) {
                val name = key.name
                if (name.contains("xiaomi", ignoreCase = true)) {
                    discoveredXiaomiKeys[name] = key
                    count++
                }
            }
            if (count > 0) {
                Log.d(TAG, "Xiaomi Discovery: Found $count legitimate Xiaomi vendor tags in HAL.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to discover Xiaomi vendor tags from HAL", e)
        }
    }

    /**
     * The Ultimate Temporal Chokepoint:
     * OEM Camera apps (like Xiaomi) often aggressively build their CaptureRequests using `addTarget`
     * BEFORE they even call `createCaptureSession`! When this happens, our `addTarget` hook misses the swap
     * because our `surfaceMap` is empty.
     * To definitively prevent the fatal "CaptureRequest contains unconfigured Input/Output Surface" crash, we intercept
     * `CameraDeviceImpl.submitCaptureRequest` (the final Java bottleneck before the native HAL). Here we reflectively 
     * crack open the 'immutable' CaptureRequest and manually mutate its internal `mSurfaceSet` just milliseconds
     * before Android validates it!
     */
    private fun hookSubmitCaptureRequest(lpparam: XC_LoadPackage.LoadPackageParam) {
        val deviceImplClass = XposedHelpers.findClassIfExists(
            "android.hardware.camera2.impl.CameraDeviceImpl", lpparam.classLoader
        ) ?: return

        val pineSubmitCaptureRequestHook = object : top.canyie.pine.callback.MethodHook() {
            override fun beforeCall(callFrame: top.canyie.pine.Pine.CallFrame) {
                applyDeferredHooksToClassLoader(Thread.currentThread().contextClassLoader)
                if (!isEnabled || surfaceMap.isEmpty()) return

                try {
                    // First argument is List<CaptureRequest>
                    val requestList = callFrame.args[0] as? List<*> ?: return

                    for (reqObj in requestList) {
                        if (reqObj == null) continue
                        val reqClass = reqObj.javaClass
                        
                        // CaptureRequest internally stores surfaces in `mSurfaceSet` (or `mTargetSurfaces` in older versions)
                        var surfaceSetField = XposedHelpers.findFieldIfExists(reqClass as Class<*>, "mSurfaceSet")
                        if (surfaceSetField == null) {
                            surfaceSetField = XposedHelpers.findFieldIfExists(reqClass as Class<*>, "mTargetSurfaces")
                        }
                        
                        if (surfaceSetField != null) {
                            surfaceSetField.isAccessible = true
                            val surfaceCollection = surfaceSetField.get(reqObj) as? Collection<*> ?: continue
                            
                            val toRemove = mutableListOf<Surface>()
                            val toAdd = mutableListOf<Surface>()
                            
                            for (surfaceObj in surfaceCollection) {
                                val s = surfaceObj as? Surface ?: continue
                                val dummySurface = surfaceMap[s]
                                if (dummySurface != null && dummySurface != s) {
                                    toRemove.add(s)
                                    toAdd.add(dummySurface)
                                }
                            }
                            
                            if (toRemove.isNotEmpty()) {
                                // The collection is a MutableCollection (HashSet or ArraySet)
                                @Suppress("UNCHECKED_CAST")
                                val mutSet = surfaceCollection as MutableCollection<Surface>
                                mutSet.removeAll(toRemove)
                                mutSet.addAll(toAdd)
                                Log.d(TAG, "VirtuCam_Hook: submitCaptureRequest CHOKEPOINT ACTIVATED -> Swapped ${toRemove.size} surfaces natively in immutable CaptureRequest!")
                            }
                        }

                        // --- METADATA MUTATION ---
                        try {
                            val mSettingsField = XposedHelpers.findFieldIfExists(reqClass as Class<*>, "mSettings")
                            if (mSettingsField != null) {
                                mSettingsField.isAccessible = true
                                val settings = mSettingsField.get(reqObj) // CameraMetadataNative
                                
                                // [Metadata Sync] Store current state for future lookup by Bridge
                                // No longer injecting into LENS_FOCUS_DISTANCE to avoid hardware collision.
                                
                                // [TRUTH LOG] This confirms the Camera App process HAS the updated slider value.
                                Log.d(TAG, "DIAGNOSTIC_VIRTUCAM: State Captured for Courier: (Stretch: $compensationFactor, RotOffset: $rotationOffset, Mirror: $isMirrored)")
                                
                                // 2. NUCLEAR SWEEP: Disable parallel capture and AI features in the metadata directly
                                val manufacturer = android.os.Build.MANUFACTURER ?: ""
                                if (manufacturer.equals("Xiaomi", ignoreCase = true) ||
                                    manufacturer.equals("POCO", ignoreCase = true) ||
                                    manufacturer.equals("Redmi", ignoreCase = true)) {
                                    val context = android.app.AndroidAppHelper.currentApplication()
                                    val chars = try {
                                        val manager = context?.getSystemService(android.content.Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
                                        manager?.getCameraCharacteristics(activeCameraId)
                                    } catch (_: Exception) { null }

                                    val keys = chars?.availableCaptureRequestKeys ?: discoveredXiaomiKeys.values
                                    for (key in keys) {
                                        val name = key.name
                                        if (name.contains("xiaomi", true) || name.contains("mivi", true)) {
                                            try {
                                                if (name == "xiaomi.capturepipeline.simple") {
                                                    setVendorTagInternal(settings, name, 1.toByte())
                                                } else {
                                                    setVendorTagInternal(settings, name, false)
                                                    setVendorTagInternal(settings, name, 0.toByte())
                                                    setVendorTagInternal(settings, name, 0)
                                                }
                                            } catch (_: Exception) {}
                                        }
                                    }
                                    
                                    // Hardcoded Forced Sync Path
                                    setVendorTagInternal(settings, "xiaomi.parallel.enabled", 0.toByte())
                                    setVendorTagInternal(settings, "xiaomi.mivi.enabled", false)
                                    setVendorTagInternal(settings, "xiaomi.algo.enabled", false)
                                    setVendorTagInternal(settings, "xiaomi.capturepipeline.simple", 1.toByte())
                                }
                            }
                        } catch (_: Exception) {}
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "VirtuCam_Hook: Error mutating immutable CaptureRequest in submitCaptureRequest", t)
                }
            }
        }

        // Apply Pine hook to all submitCaptureRequest methods
        deviceImplClass.declaredMethods.forEach { method ->
            if (method.name == "submitCaptureRequest") {
                try {
                    val p = top.canyie.pine.Pine.hook(method, pineSubmitCaptureRequestHook)
                    if (p != null) pineHooks.add(p)
                    Log.e("DIAGNOSTIC_VIRTUCAM", "PINE HOOK REGISTRATION: Successfully injected native Pine hook on submitCaptureRequest!")
                } catch (e: Throwable) {
                    Log.e("DIAGNOSTIC_VIRTUCAM", "PINE HOOK FATAL: Failed to inject Pine hook on submitCaptureRequest", e)
                }
            }
        }
    }

    /**
     * Hook ImageReader to suppress format mismatch errors.
     * Instead of changing the format at creation (which breaks the camera HAL's internal readers),
     * we catch the UnsupportedOperationException in acquireNextImage/acquireLatestImage.
     * This lets the camera HAL work normally while silently ignoring format mismatches.
     */
    private fun hookImageReader(lpparam: XC_LoadPackage.LoadPackageParam) {
        val imageReaderClass = XposedHelpers.findClassIfExists(
            "android.media.ImageReader", lpparam.classLoader
        ) ?: return

        // [PINE MIGRATION] Track Surface formats from ImageReader creation using AOT-bypassing Pine
        val pineImageReaderHook = object : PineHelper.PineCompatibleMethodHook() {
            override fun afterHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                val reader = param.thisObject as? android.media.ImageReader ?: return
                val surface = param.result as? Surface ?: return
                
                imageReaderSurfaces[surface] = reader

                val w = reader.width
                val h = reader.height
                val format = reader.imageFormat
                surfaceSizes[surface] = Pair(w, h)
                surfaceFormats[surface] = format

                if (format == 256) {
                    captureSurfaces.add(surface)
                    Log.d(TAG, "VirtuCam_Hook: Classified ImageReader as CAPTURE (format=256) ${w}x${h}")
                } else if (format == 34 || format == 35) {
                    Log.d(TAG, "VirtuCam_Hook: Classified ImageReader as PREVIEW (format=$format) ${w}x${h}")
                }
            }
        }
        
        try {
            val method = imageReaderClass.getMethod("getSurface")
            val p = top.canyie.pine.Pine.hook(method, pineImageReaderHook)
            if (p != null) pineHooks.add(p)
            Log.i("DIAGNOSTIC_VIRTUCAM", "PINE HOOK REGISTRATION: Successfully injected exact hook on android.media.ImageReader.getSurface")
        } catch (e: Throwable) {
            Log.e("DIAGNOSTIC_VIRTUCAM", "PINE HOOK FATAL: Failed to inject exact hook on getSurface", e)
        }

        val overwriteHook = object : PineHelper.PineCompatibleMethodHook() {
            override fun afterHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                // Secondary Defense: Suppress format mismatch exceptions from nativeImageSetup.
                if (param.throwable is UnsupportedOperationException) {
                    Log.d(TAG, "VirtuCam_Hook: Suppressed format mismatch: ${param.throwable?.message}")
                    param.throwable = null
                    param.result = null
                    return
                }

                try {
                    val image = param.result as? Image ?: return
                    // Safe access to format to avoid IllegalStateException: Image is already closed
                    val format = try { image.format } catch (e: IllegalStateException) { return }
                    
                    // [BROWSER CAPTURE FIX] Skip overwrite for our own dummy sink ImageReaders.
                    // The dummy sink exists only to drain the HAL pipeline — its Images are immediately
                    // discarded. Overwriting them is wasteful and, worse, the JPEG overwrite blocks
                    // the dummySinkHandler thread for up to 2 seconds (polling for latestVirtualJpeg),
                    // which stalls ALL dummy sinks including YUV preview draining.
                    val callerReader = param.thisObject as? ImageReader
                    if (callerReader != null && dummyImageReaders.contains(callerReader)) {
                        return
                    }
                    
                    // [INVESTIGATION] Dump real hardware frames if Passthrough is ON
                    if (!isEnabled && isPassthroughMode) {
                        if (format == ImageFormat.YUV_420_888 || format == 35 || format == ImageFormat.YV12) {
                            val now = System.currentTimeMillis()
                            if (now - lastSpyDumpMs > 5000L) {
                                lastSpyDumpMs = now
                                try { BufferDumper.dumpYuvImage(image, "hw_real_${image.width}x${image.height}") } catch (_: Throwable) {}
                                Log.d(TAG, "PASSTHROUGH: Dumped real hardware frame ${image.width}x${image.height}")
                            }
                        }
                        return
                    }

                    // Find matching bridge for this image dimensions
                    val bridge = activeBridges.firstOrNull { it.width == image.width && it.height == image.height }
                    
                    when (format) {
                        ImageFormat.YUV_420_888, ImageFormat.YV12, 35 -> {
                            // YUV Data Override path - Hybrid Strategy (Hook + Writer)
                            if (bridge != null) {
                                // [PERF OPT 1] Skip redundant synchronous YUV overwrite if ImageWriter
                                // already pushed pre-spoofed frames into this reader's buffer queue.
                                // The ImageWriter path (pushLatestFrameToWriter) already converts RGBA→YUV
                                // asynchronously on our background thread. Doing it again here on the app's
                                // main/callback thread is wasteful (30-100ms CPU burn per frame) and is the
                                // #1 cause of lag in native camera apps.
                                if (bridge.hasImageWriter) {
                                    Log.d(TAG, "CAPT_LOG [4a-SKIP]: YUV overwrite skipped — ImageWriter already active for ${image.width}x${image.height}")
                                } else {
                                    Log.e(TAG, "CAPT_LOG [4a]: Native Camera acquireNextImage (YUV 35) overriding directly (no ImageWriter). Image: ${image.width}x${image.height}")
                                    bridge.overwriteImageWithLatestYuv(image, image.timestamp)
                                    Log.d(TAG, "VirtuCam_Hook: Overwrote YUV image ${image.width}x${image.height}")
                                }
                                // [STAGE DUMP 3] Final consumed buffer — heavily throttled
                                if (isBufferCaptureEnabled) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastSpyDumpMs > 5000L) {
                                        lastSpyDumpMs = now
                                        try {
                                            val stage3Tag = "stage3_final_yuv_${image.width}x${image.height}"
                                            BufferDumper.dumpYuvImage(image, stage3Tag)
                                            Log.i(TAG, "[STAGE_DUMP_3] Saved final YUV buffer to virtucam_audit/buffers/stage3_final_yuv_${image.width}x${image.height}_*.png")
                                        } catch (_: Throwable) {}
                                    }
                                }
                            }
                        }
                        256 -> { // JPEG = 0x100 = 256
                            // JPEG Capture Override path - Hybrid Strategy (Hook + Writer)
                            if (bridge != null) {
                                Log.e(TAG, "CAPT_LOG [4b]: Native Camera acquireNextImage (JPEG 256) overriding directly. Image: ${image.width}x${image.height}")
                                bridge.overwriteImageWithLatestJpeg(image)
                                Log.d(TAG, "VirtuCam_Hook: Overwrote JPEG capture ${image.width}x${image.height}")
                            } else {
                                // No matching bridge by size - try any bridge as a fallback
                                val anyBridge = activeBridges.firstOrNull()
                                if (anyBridge != null) {
                                    Log.e(TAG, "CAPT_LOG [4c]: Native Camera acquireNextImage (JPEG 256) overriding via fallback bridge. Image: ${image.width}x${image.height}")
                                    anyBridge.overwriteImageWithLatestJpeg(image)
                                    Log.d(TAG, "VirtuCam_Hook: Overwrote JPEG capture (fallback bridge) ${image.width}x${image.height}")
                                } else {
                                    Log.e(TAG, "CAPT_LOG [4d]: Native Camera acquireNextImage (JPEG 256) failed - no bridge available!")
                                }
                            }
                        }
                        else -> {
                            // Unknown format - try YUV overwrite as a best-effort
                            if (bridge != null && !bridge.hasImageWriter && image.planes.size >= 3) {
                                bridge.overwriteImageWithLatestYuv(image, image.timestamp)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "VirtuCam_Hook: Error during ImageReader data overwrite", e)
                }
            }
        }

        PineHelper.hookAllMethods(imageReaderClass, "acquireNextImage", overwriteHook)
        PineHelper.hookAllMethods(imageReaderClass, "acquireLatestImage", overwriteHook)
    }

    private fun hookSurfaceTexture(lpparam: XC_LoadPackage.LoadPackageParam) {
        val stSizes = java.util.concurrent.ConcurrentHashMap<SurfaceTexture, Pair<Int, Int>>()
        
        try {
            val surfaceTextureClass = XposedHelpers.findClassIfExists("android.graphics.SurfaceTexture", lpparam.classLoader)
            if (surfaceTextureClass != null) {
                val setSizeMethod = surfaceTextureClass.getDeclaredMethod("setDefaultBufferSize", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                pineHooks.add(top.canyie.pine.Pine.hook(setSizeMethod, object : top.canyie.pine.callback.MethodHook() {
                    override fun afterCall(callFrame: top.canyie.pine.Pine.CallFrame) {
                        val st = callFrame.thisObject as? SurfaceTexture ?: return
                        val w = callFrame.args[0] as Int
                        val h = callFrame.args[1] as Int
                        stSizes[st] = Pair(w, h)
                        Log.e(TAG, "PINE_Hook: setDefaultBufferSize(${w}x${h})")
                    }
                }))
            }
        } catch (e: Throwable) {
            Log.e(TAG, "VirtuCam_Hook: Failed to hook SurfaceTexture.setDefaultBufferSize", e)
        }

            val surfaceClass = XposedHelpers.findClassIfExists("android.view.Surface", lpparam.classLoader)
            if (surfaceClass != null) {
                try {
                    val constructor = surfaceClass.getDeclaredConstructor(SurfaceTexture::class.java)
                    val hook = object : PineHelper.PineCompatibleMethodHook() {
                        override fun afterHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                            val surface = param.thisObject as? Surface ?: return
                            val st = param.args[0] as? SurfaceTexture ?: return
                            surfaceTextureSurfaces[surface] = st

                            val size = stSizes[st]
                            if (size != null) {
                                surfaceSizes[surface] = size
                                surfaceFormats[surface] = 34
                                Log.d(TAG, "PINE_Hook: Associated Surface with SurfaceTexture size ${size.first}x${size.second}")
                            }
                        }
                    }
                    val p = top.canyie.pine.Pine.hook(constructor, hook)
                    if (p != null) pineHooks.add(p)
                    pineHooks.add(hook) // Prevent GC of the MethodHook
                    Log.i(TAG, "PINE HOOK REGISTRATION: Successfully injected hook on Surface(SurfaceTexture)")
                } catch (e: Throwable) {
                    Log.e(TAG, "PINE HOOK FATAL: Failed to inject hook on Surface(SurfaceTexture)", e)
                }
            }

            // [TOTAL SURVEILLANCE] Hook getTransformMatrix GLOBALLY for SurfaceTexture
            // This is safer and only applies once per app launch.
            try {
                PineHelper.findAndHookMethod(
                    "android.graphics.SurfaceTexture",
                    lpparam.classLoader,
                    "getTransformMatrix",
                    FloatArray::class.java,
                    object : PineHelper.PineCompatibleMethodHook() {
                        private var localFrameCount = 0
                        
                        override fun afterHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                            val matrix = param.args[0] as? FloatArray ?: return

                            if (isEnabled) {
                                // Act as real hardware: return a transform matrix encoding the sensor orientation.
                                // This makes preview look upright to the user — same as real camera behavior.
                                // The EGL buffer content is rendered at sensor-native orientation, so the matrix
                                // counter-rotates correctly (front=270°, back=180°).
                                val sensorRot = resolveSensorOrientationDeg()
                                android.opengl.Matrix.setIdentityM(matrix, 0)
                                // Rotate around center (0,0 translated to 0.5,0.5)
                                android.opengl.Matrix.translateM(matrix, 0, 0.5f, 0.5f, 0f)
                                android.opengl.Matrix.scaleM(matrix, 0, 1f, -1f, 1f) // Standard SurfaceTexture Y-flip
                                android.opengl.Matrix.rotateM(matrix, 0, sensorRot.toFloat(), 0f, 0f, 1f)
                                android.opengl.Matrix.translateM(matrix, 0, -0.5f, -0.5f, 0f)
                            } else {
                                // Audit-only: log the real matrix (throttled)
                                localFrameCount++
                                if (localFrameCount % 300 == 0) {
                                    val mStr = matrix.joinToString(", ") { String.format("%.2f", it) }
                                    Log.e(TAG, "SURVEILLANCE: Real ST Matrix -> [$mStr]")
                                }
                                // Record for hardware parity analysis
                                val isFront = (cameraFacings[activeCameraId] == 1)
                                try { HardwareAuditLogger.logTransformMatrix(activeCameraId, isFront, matrix) } catch (_: Throwable) {}
                            }
                        }
                    }
                )
            } catch (e: Throwable) {
                Log.e(TAG, "VirtuCam_Hook: Failed to hook getTransformMatrix surveillance: ${e.message}")
            }

    }

    private fun hookCamera1(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cameraClass = XposedHelpers.findClassIfExists("android.hardware.Camera", lpparam.classLoader) ?: return

        PineHelper.hookAllMethods(cameraClass, "open", object : PineHelper.PineCompatibleMethodHook() {
            override fun afterHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                try {
                    val cameraId = if (param.args.isNotEmpty() && param.args[0] is Int) {
                        param.args[0] as Int
                    } else {
                        0
                    }
                    activeCameraId = cameraId.toString()
                    val info = android.hardware.Camera.CameraInfo()
                    android.hardware.Camera.getCameraInfo(cameraId, info)
                    cameraOrientations[activeCameraId] = info.orientation
                    cameraFacings[activeCameraId] =
                        if (info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT) 1 else 0
                    Log.d(
                        TAG,
                        "VirtuCam_Hook: Camera1 open cameraId=$cameraId sensorOrient=${info.orientation} facing=${info.facing}"
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "VirtuCam_Hook: Camera.open hook failed", e)
                }
            }
        })

        PineHelper.hookAllMethods(cameraClass, "setPreviewTexture", object : PineHelper.PineCompatibleMethodHook() {
            override fun beforeHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                try {
                    loadConfiguration()
                    if (!isEnabled) return
                    val st = param.args[0] as? SurfaceTexture ?: return
                    Log.d(TAG, "VirtuCam_Hook: Intercepted setPreviewTexture (Camera1)")
                    startRenderThreads(listOf(Triple(Surface(st), false, 0x1))) // Default to RGBA8888 for Texture
                } catch (t: Throwable) {
                    Log.e(TAG, "VirtuCam_Hook: Error in Camera1 setPreviewTexture hook", t)
                }
            }
        })

        PineHelper.hookAllMethods(cameraClass, "setPreviewDisplay", object : PineHelper.PineCompatibleMethodHook() {
            override fun beforeHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                try {
                    loadConfiguration()
                    if (!isEnabled) return
                    val holder = param.args[0] as? android.view.SurfaceHolder ?: return
                    val holeSurface = holder.surface ?: return
                    Log.d(TAG, "VirtuCam_Hook: Intercepted setPreviewDisplay (Camera1)")
                    stopOldPipeline()
                    startRenderThreads(listOf(Triple(holeSurface, false, 0x1))) // Default to RGBA8888
                } catch (t: Throwable) {
                    Log.e(TAG, "VirtuCam_Hook: Error in Camera1 setPreviewDisplay hook", t)
                }
            }
        })
    }

    private fun createDummySurface(
        targetSurface: Surface?, 
        width: Int = 1280, 
        height: Int = 720,
        bridge: FormatConverterBridge? = null
    ): Surface {
        // [ONCE AND FOR ALL] Prevent BufferQueue Stalls (pipelineFull Native Crashes)
        // Instead of a bare SurfaceTexture that no one reads, we use an ImageReader sink.
        // It actively consumes and discards frames so the HAL never blocks.
        
        if (dummySinkThread == null) {
            dummySinkThread = HandlerThread("VirtuCam-DummySink-${System.currentTimeMillis()}").apply { start() }
            dummySinkHandler = Handler(dummySinkThread!!.looper)
        }
        
        val originalFormat = if (targetSurface != null) SurfaceUtils.getSurfaceFormat(targetSurface) else 0x22 // PRIVATE fallback
        
        // --- PHASE 15 STABILITY FIX ---
        // Android's ImageReader throws UnsupportedOperationException in `ir.acquireNextImage()` 
        // if the format is PRIVATE (34 / 0x22). This crashes our dummy sink listener and blocks
        // the camera processing pipeline (e.g. MiAlgoEngine memoryMonitor timeouts).
        // By changing it to YUV_420_888 (35), we ensure we can safely consume and discard frames.
        val readerFormat = if (originalFormat == 34 || originalFormat == 0x22) {
            Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: Converting PRIVATE format ($originalFormat) to YUV_420_888 (35) for safe draining.")
            android.graphics.ImageFormat.YUV_420_888
        } else {
            originalFormat
        }

        Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: Creating Dummy Sink Surface. Width=$width, Height=$height, Format=$readerFormat (Orig=$originalFormat)")

        // [STABILITY FIX] Use maxImages=5 to give the Camera HAL plenty of breathing room during bursts. 
        // A value of 1 crashes the HAL if our encoder blocks the queue even for a few milliseconds (Error 4).
        val reader = try {
            ImageReader.newInstance(width, height, readerFormat, 5)
        } catch (e: Exception) {
            Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: Failed to create ImageReader with $width x $height format $readerFormat! Trying YUV fallback...", e)
            ImageReader.newInstance(width, height, android.graphics.ImageFormat.YUV_420_888, 5)
        }
        
        reader.setOnImageAvailableListener({ ir ->
            try {
                // [BROWSER CAPTURE FIX] For JPEG dummy sinks, increment captureCount BEFORE
                // acquireNextImage. This is critical because:
                // 1) The acquireNextImage hook will block polling for latestVirtualJpeg
                // 2) latestVirtualJpeg requires the render loop to render to the JPEG EGL surface
                // 3) The render loop only renders to JPEG surfaces when captureCount > 0
                // 4) Browsers may use captureSingleRequest() (not hooked) or pass null callbacks,
                //    so the CaptureCallback-based captureCount++ may never fire.
                // By incrementing here, the render loop can populate the bridge WHILE the hook polls.
                if (readerFormat == 256 && bridge != null) {
                    synchronized(CameraHook) {
                        CameraHook.captureCount++
                        CameraHook.captureQueue.offer(Pair(android.os.SystemClock.elapsedRealtimeNanos(), CameraHook.captureCount))
                        Log.e(TAG, "CAPT_LOG [0]: JPEG Dummy Sink fired! captureCount incremented to ${CameraHook.captureCount} (browser-safe trigger)")
                    }
                }

                // Instantly consume and discard the image to keep the pipeline flowing
                val realImage = ir.acquireNextImage()
                val realTimestamp = realImage?.timestamp ?: 0L
                realImage?.close()
                
                // [NATIVE CAMERA SMOOTHNESS FIX 2] Provide the perfectly stable hardware
                // timestamp to the OpenGL compositor so the UI doesn't stutter.
                if (realTimestamp > 0) {
                    CameraHook.latestSensorTimestamp = realTimestamp
                    CameraHook.pendingHardwareFrames.offer(realTimestamp)
                }

                // [NATIVE CAMERA SMOOTHNESS FIX] Use this hardware event as the metronome
                // for the VirtualRenderThread! This ensures Native Camera apps (which use
                // SurfaceTexture and bypass ImageWriter) are perfectly synced to hardware vsync,
                // eliminating the choppy Thread.sleep(33) fallback.
                synchronized(CameraHook.frameSyncObject) {
                    CameraHook.lastFrameSyncMs = System.currentTimeMillis()
                    CameraHook.frameSyncObject.notifyAll()
                }

                // Sync Trigger: The real camera just took a photo! Push our spoofed frame to the app NOW!
                // Offload the heavy JPEG compression (50ms+) so we don't stall the physical HAL
                if (bridge != null && bridge.hasImageWriter) {
                    dummySinkHandler?.post {
                        bridge.pushLatestFrameToWriter(realTimestamp)
                    }
                }
            } catch (e: Exception) {
                // Ignore errors during discard
            }
        }, dummySinkHandler)
        
        val surface = reader.surface
        
        // Retain strong references — prevents GC from destroying surfaces
        // while the native camera pipeline is still writing to them
        dummyImageReaders.add(reader)
        dummySurfaces.add(surface)
        // Track original→dummy mapping for CaptureRequest swapping
        if (targetSurface != null) {
            surfaceMap[targetSurface] = surface
        }
        
        Log.d(TAG, "VirtuCam_Hook: Created active ImageReader dummy sink (${width}x${height}, format=$originalFormat)")
        return surface
    }

    /**
     * Extract width/height from an OutputConfiguration via reflection
     */
    private fun getSizeFromOutputConfig(config: Any): Pair<Int, Int> {
        var altSurface: Surface? = null
        try {
            val getSurfaceMethod = config.javaClass.getMethod("getSurface")
            altSurface = getSurfaceMethod.invoke(config) as? Surface
            if (altSurface != null) {
                // Try to get size from the OutputConfiguration's internal fields (Android 10+)
                try {
                    val mConfiguredWidthField = XposedHelpers.findFieldIfExists(config.javaClass as Class<*>, "mConfiguredSize")
                    if (mConfiguredWidthField != null) {
                        val size = mConfiguredWidthField.get(config)
                        if (size != null) {
                            val w = size.javaClass.getMethod("getWidth").invoke(size) as Int
                            val h = size.javaClass.getMethod("getHeight").invoke(size) as Int
                            if (w > 0 && h > 0) {
                                Log.d(TAG, "VirtuCam_Hook: Extracted OutputConfig size: ${w}x${h}")
                                return Pair(w, h)
                            }
                        }
                    }
                } catch (_: Throwable) {}

                // Fallback: try mSurfaceSize
                try {
                    val sizeField = XposedHelpers.findFieldIfExists(config.javaClass as Class<*>, "mSurfaceSize")
                    if (sizeField != null) {
                        val size = sizeField.get(config)
                        if (size != null) {
                            val w = size.javaClass.getMethod("getWidth").invoke(size) as Int
                            val h = size.javaClass.getMethod("getHeight").invoke(size) as Int
                            if (w > 0 && h > 0) {
                                Log.d(TAG, "VirtuCam_Hook: Extracted SurfaceSize: ${w}x${h}")
                                return Pair(w, h)
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        
        // Final fallback: check our telemetry map using the extracted surface
        if (altSurface != null) {
            val tracked = surfaceSizes[altSurface]
            if (tracked != null) {
                Log.d(TAG, "VirtuCam_Hook: Using tracked telemetry size: ${tracked.first}x${tracked.second}")
                return tracked
            }
        }
        
        return Pair(1280, 720) // Safe default if zero telemetry
    }

    @Suppress("UNCHECKED_CAST")
    private fun swapSurfaceInOutputConfig(config: Any, targetSurface: Surface): Surface {
        val (w, h) = getSizeFromOutputConfig(config)
        
        val format = SurfaceUtils.getSurfaceFormat(targetSurface)
        surfaceFormats[targetSurface] = format
        val isPreview = (format == 0x22 || format == 0x1)
        
        Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: Swapping OutputConfig Surface. Size=${w}x${h}, Format=$format, isPreview=$isPreview")
        
        val isVideoSurface = videoSurfaces.contains(targetSurface)
        
        // [JPEG DIRECT OVERWRITE] For JPEG surfaces, always use direct overwrite.
        // Don't swap with dummy — HAL writes real JPEG, acquireNextImage hook overwrites it.
        if (format == 256 && !isPreview && !isVideoSurface) {
            val realSensorOrientation = resolveSensorOrientationDeg()
            Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: JPEG OutputConfig ${w}x${h} — direct overwrite (no dummy, no ImageWriter)")
            val b = FormatConverterBridge(w, h, null, format, realSensorOrientation, rotationOffset, isColorSwapped)
            activeBridges.add(b)
            formatBridges[android.util.Size(w, h)] = b
            // Don't modify the OutputConfiguration — keep the original surface
            return b.inputSurface ?: targetSurface
        }

        val bridge = if (!isPreview && !isVideoSurface) {
            val realSensorOrientation = resolveSensorOrientationDeg()
            Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: Creating FormatConverterBridge for $w x $h (Format $format) SensorRot=$realSensorOrientation, RotOffset=$rotationOffset, ColorSwap=$isColorSwapped")
            val b = FormatConverterBridge(w, h, targetSurface, format, realSensorOrientation, rotationOffset, isColorSwapped)
            activeBridges.add(b)
            formatBridges[android.util.Size(w, h)] = b
            b
        } else {
            if (isVideoSurface) Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: Identified Video Surface - Bypassing FormatBridge for Direct OpenGL Render")
            null
        }
        
        val dummySurface = createDummySurface(targetSurface, w, h, bridge)
        surfaceMap[targetSurface] = dummySurface
        
        try {
            try {
                val enableSharingMethod = config.javaClass.getMethod("enableSurfaceSharing")
                enableSharingMethod.invoke(config)
            } catch (e: Throwable) {}

            val mSurfacesField = XposedHelpers.findField(config.javaClass, "mSurfaces")
            val surfaces = mSurfacesField.get(config) as? java.util.ArrayList<Surface>
            if (surfaces != null) {
                surfaces.clear()
                surfaces.add(dummySurface)
            } else {
                val mSurfaceField = XposedHelpers.findField(config.javaClass, "mSurface")
                mSurfaceField.set(config, dummySurface)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "VirtuCam_Hook: Failed to swap surface in config", e)
        }
        
        return bridge?.inputSurface ?: targetSurface
    }

    // Removed duplicate definition of compensationFactor

    /**
     * Poll configuration via ContentProvider
     */
    fun loadConfiguration() {
        try {
            val context = AndroidAppHelper.currentApplication() ?: return
            val uri = Uri.parse("content://com.kcam.provider/config")
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            
            cursor?.use {
                if (it.moveToFirst()) {
                    try {
                        isEnabled = it.getInt(0) == 1
                        
                        // Default values for missing columns in older DB versions
                        isVideo = if (it.columnCount > 2) it.getInt(2) == 1 else false
                        isStream = if (it.columnCount > 3) it.getInt(3) == 1 else false
                        streamUrl = if (it.columnCount > 4) it.getString(4) ?: "" else ""
                        compensationFactor = if (it.columnCount > 6) it.getFloat(6) else 1.0f
                        isMirrored = if (it.columnCount > 7) it.getInt(7) == 1 else false
                        zoomFactor = if (it.columnCount > 8) it.getFloat(8) else 1.0f
                        rtspUseTcp = if (it.columnCount > 9) it.getInt(9) == 1 else true
                        isColorSwapped = if (it.columnCount > 11) it.getInt(11) == 1 else false
                        isLivenessEnabled = if (it.columnCount > 12) it.getInt(12) == 1 else true
                        isColorFlashEnabled = if (it.columnCount > 17) it.getInt(17) == 1 else true
                        isTestPatternMode = if (it.columnCount > 13) it.getInt(13) == 1 else false
                        isPassthroughMode = if (it.columnCount > 14) it.getInt(14) == 1 else false
                        rotationOffset = if (it.columnCount > 15) it.getInt(15) else 0
                        isBufferCaptureEnabled = if (it.columnCount > 16) it.getInt(16) == 1 else false
                        isRefineEnabled = if (it.columnCount > 18) it.getInt(18) == 1 else false
                        isRppgEnabled = if (it.columnCount > 19) it.getInt(19) == 1 else true
                        rppgBpm = if (it.columnCount > 20) it.getInt(20) else 72
                        
                        Log.e("DIAGNOSTIC_VIRTUCAM", "VirtuCam_Hook: Config loaded. Enabled: $isEnabled, Zoom: $zoomFactor, Stretch: $compensationFactor, liveness: $isLivenessEnabled, passthrough: $isPassthroughMode, offset: $rotationOffset, refine: $isRefineEnabled, rppg: $isRppgEnabled (${rppgBpm}bpm)")
                    } catch (innerE: Exception) {
                        Log.e("DIAGNOSTIC_VIRTUCAM", "VirtuCam_Hook: Error parsing cursor columns", innerE)
                    }
                } else {
                    Log.e("DIAGNOSTIC_VIRTUCAM", "VirtuCam_Hook: Config provider returned null or empty cursor")
                }
            }
        } catch (e: Exception) {
            Log.e("DIAGNOSTIC_VIRTUCAM", "VirtuCam_Hook: Failed to load configuration (Provider possibly blocked)", e)
        }
    }

    /**
     * Hooks createCaptureSession(List<Surface>, ...)
     */
    private fun hookCameraDevice(lpparam: XC_LoadPackage.LoadPackageParam) {
        // --- CLAUDE DIAGNOSTIC STEPS 1, 2, 3 ---
        val classloaders = listOf(
            ClassLoader.getSystemClassLoader(),
            lpparam.classLoader,
            Thread.currentThread().contextClassLoader
        )
        
        var targetClass: Class<*>? = null
        
        classloaders.forEachIndexed { index, cl ->
            try {
                if (cl != null) {
                    val found = XposedHelpers.findClassIfExists("android.hardware.camera2.impl.CameraDeviceImpl", cl)
                    if (found != null) {
                        Log.e("DIAGNOSTIC_VIRTUCAM", "CLAUDE: CLASS FOUND in classloader $index: $cl")
                        if (targetClass == null) {
                            targetClass = found
                        }
                    } else {
                        Log.e("DIAGNOSTIC_VIRTUCAM", "CLAUDE: Not found in classloader $index")
                    }
                }
            } catch (e: Exception) {
                Log.e("DIAGNOSTIC_VIRTUCAM", "CLAUDE: Exception checking classloader $index", e)
            }
        }
        
        if (targetClass == null) {
            Log.e("DIAGNOSTIC_VIRTUCAM", "CLAUDE FATAL: CameraDeviceImpl is MISSING from ALL ClassLoaders. Sandbox isolation confirmed.")
            return
        }

        val cameraDeviceImplClass = targetClass!!

        pineHooks.addAll(PineHelper.hookAllMethods(
            cameraDeviceImplClass,
            "createCaptureSession",
            object : PineHelper.PineCompatibleMethodHook() {
                @Suppress("UNCHECKED_CAST")
                override fun beforeHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                    if (isProcessingCaptureSession.get() == true) {
                        Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: Ignoring re-entrant createCaptureSession (List)")
                        return
                    }
                    isProcessingCaptureSession.set(true)

                    try {
                        // Lazy load configuration when camera is actually accessed
                        loadConfiguration()

                        // [AUDIT TRACKING] Always update activeCameraId regardless of isEnabled,
                        // so getTransformMatrix and other read-only hooks can attribute correctly.
                        try {
                            val cameraDevice = param.thisObject as android.hardware.camera2.CameraDevice
                            val newId = cameraDevice.id
                            if (newId != activeCameraId) {
                                Log.e(TAG, "AUDIT: activeCameraId changed $activeCameraId -> $newId via createCaptureSession")
                                activeCameraId = newId
                                try {
                                    val manager = AndroidAppHelper.currentApplication().getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                                    val characteristics = manager.getCameraCharacteristics(activeCameraId)
                                    val facing = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                                    if (facing != null) cameraFacings[activeCameraId] = mapLensFacingForVirtuCam(facing) ?: 0
                                    cameraOrientations[activeCameraId] = characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                                } catch (_: Throwable) {}
                            }
                        } catch (_: Throwable) {}

                        if (!isEnabled) {
                            // Audit-only path: still record session geometry so we capture the front camera's surface info
                            val args0 = param.args
                            if (args0.isNotEmpty() && args0[0] is List<*>) {
                                val sList = args0[0] as List<Surface>
                                if (sList.isNotEmpty()) {
                                    try {
                                        val auditSurfaces = sList.map { s ->
                                            val sz = SurfaceUtils.getSurfaceSize(s)
                                            val fmt = SurfaceUtils.getSurfaceFormat(s)
                                            Pair(fmt, sz)
                                        }
                                        HardwareAuditLogger.beginSession(activeCameraId, auditSurfaces)
                                    } catch (_: Throwable) {}
                                }
                            }
                            return
                        }

                        val args = param.args
                        if (args.isEmpty() || args[0] !is List<*>) return

                        val surfacesList = args[0] as List<Surface>
                        if (surfacesList.isNotEmpty()) {
                            Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: createCaptureSession called with ${surfacesList.size} targets")
                            for (s in surfacesList) {
                                val size = SurfaceUtils.getSurfaceSize(s)
                                val fmt = SurfaceUtils.getSurfaceFormat(s)
                                Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: Target Surface: ${size.first}x${size.second}, Format=$fmt")
                            }

                            // [HARDWARE AUDIT] Always begin session logging (read-only surveillance)
                            try {
                                val auditSurfaces = surfacesList.map { s ->
                                    val sz = SurfaceUtils.getSurfaceSize(s)
                                    val fmt = SurfaceUtils.getSurfaceFormat(s)
                                    Pair(fmt, sz)
                                }
                                HardwareAuditLogger.beginSession(activeCameraId, auditSurfaces)
                            } catch (_: Throwable) {}
                            
                            stopOldPipeline()
                            
                            // [HARDENING] Force fetch active cameraId facing directly from the device
                            try {
                                val cameraDevice = param.thisObject as android.hardware.camera2.CameraDevice
                                activeCameraId = cameraDevice.id
                                Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: Direct Device Sensing (createCaptureSession) - Active Camera: $activeCameraId")
                                
                                val manager = AndroidAppHelper.currentApplication().getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                                val characteristics = manager.getCameraCharacteristics(activeCameraId)
                                val facing = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                                if (facing != null) cameraFacings[activeCameraId] = mapLensFacingForVirtuCam(facing) ?: 0
                                cameraOrientations[activeCameraId] = characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                            } catch (e: Throwable) {
                                Log.e(TAG, "VirtuCam_Hook: Failed direct sensing in createCaptureSession", e)
                            }

                            val newSurfaces = ArrayList<Surface>()
                            val targetSurfaces = ArrayList<Triple<Surface, Boolean, Int>>()
                            
                            for (targetSurface in surfacesList) {
                                val size = SurfaceUtils.getSurfaceSize(targetSurface)
                                val w = size.first
                                val h = size.second
                                
                                val isCapture = captureSurfaces.contains(targetSurface)
                                val isVideoSurface = videoSurfaces.contains(targetSurface)
                                val format = SurfaceUtils.getSurfaceFormat(targetSurface)
                                val isPreview = (format == 0x22 || format == 0x1)
                                
                                // [JPEG DIRECT OVERWRITE] For JPEG surfaces, ALWAYS use direct overwrite.
                                // Don't swap with dummy, don't use ImageWriter. Instead:
                                // 1. Keep the original surface in the session
                                // 2. Create bridge WITHOUT ImageWriter (null outputSurface) for RGBA caching
                                // 3. HAL writes real data to the original surface
                                // 4. Our acquireNextImage hook overwrites it with virtual content
                                if (format == 256 && !isPreview && !isVideoSurface) {
                                    val b = FormatConverterBridge(w, h, null, format, resolveSensorOrientationDeg(), rotationOffset, isColorSwapped)
                                    activeBridges.add(b)
                                    formatBridges[android.util.Size(w, h)] = b
                                    Log.e(TAG, "VirtuCam_Hook: Capture ${w}x${h} (Format $format) — direct overwrite (no dummy, no ImageWriter)")
                                    newSurfaces.add(targetSurface) // Keep the original surface
                                    targetSurfaces.add(Triple(b.inputSurface ?: targetSurface, true, format))
                                    continue
                                }
                                
                                val bridge = if (!isPreview && !isVideoSurface) {
                                    val b = FormatConverterBridge(w, h, targetSurface, format, resolveSensorOrientationDeg(), rotationOffset, isColorSwapped)
                                    activeBridges.add(b)
                                    formatBridges[android.util.Size(w, h)] = b
                                    b
                                } else {
                                    null
                                }
                                
                                val dummySurface = createDummySurface(targetSurface, w, h, bridge)
                                surfaceMap[targetSurface] = dummySurface
                                
                                val reader = imageReaderSurfaces[targetSurface]
                                if (reader != null) imageReaderToDummy[reader] = dummySurface
                                
                                val st = surfaceTextureSurfaces[targetSurface]
                                if (st != null) surfaceTextureToDummy[st] = dummySurface

                                newSurfaces.add(dummySurface)
                                
                                val resolvedSurface = bridge?.inputSurface ?: targetSurface
                                targetSurfaces.add(Triple(resolvedSurface, isCapture, format))
                            }
                            
                            param.args[0] = newSurfaces
                            
                            startRenderThreads(targetSurfaces)
                            obfuscateStackTrace()
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "VirtuCam_Hook: Error in createCaptureSession hook", t)
                    }
                }
                override fun afterHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                    isProcessingCaptureSession.set(false)
                }
            }
        ))
    }

    /**
     * Hooks createCaptureSessionByOutputConfigurations(List<OutputConfiguration>, ...)
     */
    private fun hookCameraDeviceOutputConfigurations(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classloaders = listOf(
            ClassLoader.getSystemClassLoader(),
            lpparam.classLoader,
            Thread.currentThread().contextClassLoader
        )
        
        var targetClass: Class<*>? = null
        classloaders.forEach { cl ->
            if (cl != null && targetClass == null) {
                targetClass = XposedHelpers.findClassIfExists("android.hardware.camera2.impl.CameraDeviceImpl", cl)
            }
        }
        
        if (targetClass == null) return
        val cameraDeviceImplClass = targetClass!!

        // --- PINE: NATIVE AOT-BYPASSING SESSION METHODS ---
        val pineInternalMethodHook = object : top.canyie.pine.callback.MethodHook() {
            override fun beforeCall(callFrame: top.canyie.pine.Pine.CallFrame) {
                if (isProcessingCaptureSession.get() == true) {
                    Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: Ignoring re-entrant createCaptureSession (Config)")
                    return
                }
                isProcessingCaptureSession.set(true)

                try {
                    loadConfiguration()
                    if (!isEnabled) {
                        Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: Module OFF, allowing ${callFrame.method.name}")
                        return
                    }
                    
                    val args = callFrame.args
                    var configsList: List<*>? = null
                    
                    // 1. Check if the first argument is SessionConfiguration (Android 9+)
                    if (args.isNotEmpty() && args[0] != null && args[0]!!.javaClass.name.endsWith("SessionConfiguration")) {
                        try {
                            val sessionConfig = args[0]!!
                            val getOutputConfigsMethod = sessionConfig.javaClass.getMethod("getOutputConfigurations")
                            configsList = getOutputConfigsMethod.invoke(sessionConfig) as? List<*>
                            Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: Extracted OutputConfigurations from SessionConfiguration")
                        } catch (e: Throwable) {
                            Log.e(TAG, "VirtuCam_Hook: Failed to extract from SessionConfiguration", e)
                        }
                    } 
                    
                    // 2. Fallback: Find the List argument directly (Legacy)
                    if (configsList == null) {
                        for (i in args.indices) {
                            if (args[i] is List<*>) {
                                configsList = args[i] as List<*>
                                break
                            }
                        }
                    }
                    
                    if (configsList.isNullOrEmpty()) return
                    
                    Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: PINE INTERCEPTED ${callFrame.method.name} - list size: ${configsList.size}")
                    
                    // Direct sensing
                    try {
                        val cameraDevice = callFrame.thisObject as android.hardware.camera2.CameraDevice
                        activeCameraId = cameraDevice.id
                        val manager = AndroidAppHelper.currentApplication().getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                        val characteristics = manager.getCameraCharacteristics(activeCameraId)
                        val facing = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                        if (facing != null) cameraFacings[activeCameraId] = mapLensFacingForVirtuCam(facing) ?: 0
                        cameraOrientations[activeCameraId] = characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                    } catch (_: Throwable) {}

                    stopOldPipeline()
                    
                    val targetSurfaces = ArrayList<Triple<Surface, Boolean, Int>>()
                    val isSurfaceList = configsList[0] is Surface
                    
                    if (isSurfaceList) {
                        Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: Intercepted List<Surface>, skipping (only OutputConfig supported)")
                    } else {
                        // Extract targets from List<OutputConfiguration>
                        for (config in configsList) {
                            if (config == null) continue
                            try {
                                val getSurfaceMethod = config.javaClass.getMethod("getSurface")
                                val targetSurface = getSurfaceMethod.invoke(config) as? Surface
                                
                                if (targetSurface != null) {
                                    val isCapture = captureSurfaces.contains(targetSurface)
                                    val isVideoSurface = videoSurfaces.contains(targetSurface)
                                    
                                    val size = SurfaceUtils.getSurfaceSize(targetSurface)
                                    val w = size.first
                                    val h = size.second
                                    val format = SurfaceUtils.getSurfaceFormat(targetSurface)
                                    val isPreview = (format == 0x22 || format == 0x1)
                                    
                                    // [JPEG DIRECT OVERWRITE]
                                    if (format == 256 && !isPreview && !isVideoSurface) {
                                        val b = FormatConverterBridge(w, h, null, format, resolveSensorOrientationDeg(), rotationOffset, isColorSwapped)
                                        activeBridges.add(b)
                                        formatBridges[android.util.Size(w, h)] = b
                                        targetSurfaces.add(Triple(b.inputSurface ?: targetSurface, true, format))
                                        continue
                                    }

                                    val resolvedSurface = swapSurfaceInOutputConfig(config, targetSurface)
                                    
                                    val reader = imageReaderSurfaces[targetSurface]
                                    if (reader != null) imageReaderToDummy[reader] = resolvedSurface
                                    
                                    val st = surfaceTextureSurfaces[targetSurface]
                                    if (st != null) surfaceTextureToDummy[st] = resolvedSurface

                                    targetSurfaces.add(Triple(resolvedSurface, isCapture, format))
                                }
                            } catch (e: Throwable) {
                                Log.e(TAG, "Error in config loop", e)
                            }
                        }
                    }
                    
                    startRenderThreads(targetSurfaces)
                    obfuscateStackTrace()
                } catch (t: Throwable) {
                    Log.e(TAG, "VirtuCam_Hook: Error in ${callFrame.method.name} PINE hook", t)
                } finally {
                    isProcessingCaptureSession.set(false)
                }
            }
        }

        cameraDeviceImplClass.declaredMethods.forEach { method ->
            if (method.name == "createCustomCaptureSession" || 
                method.name == "createConstrainedHighSpeedCaptureSession" ||
                method.name == "createCaptureSession" ||
                method.name == "createCaptureSessionByOutputConfigurations") {
                try {
                    val p = top.canyie.pine.Pine.hook(method, pineInternalMethodHook)
                    if (p != null) pineHooks.add(p)
                    Log.e("DIAGNOSTIC_VIRTUCAM", "PINE HOOK REGISTRATION: Successfully injected native Pine hook on ${method.name}!")
                } catch (e: Throwable) {
                    Log.e("DIAGNOSTIC_VIRTUCAM", "PINE HOOK FATAL: Failed native Pine hook on ${method.name}!", e)
                }
            }
        }
    }

    private fun stopOldPipeline() {
        // [HARDWARE AUDIT] End the current session and flush to disk before tearing down
        try { HardwareAuditLogger.endSession() } catch (_: Throwable) {}

        renderThreads.forEach { 
            try {
                it.javaClass.getMethod("quit").invoke(it)
            } catch (e: Exception) {}
        }
        renderThreads.clear()
        
        activeBridges.forEach { try { it.release() } catch (_: Throwable) {} }
        activeBridges.clear()
        
        // Release old dummy surfaces safely now that render threads are stopped
        dummySurfaces.forEach { try { it.release() } catch (_: Throwable) {} }
        dummySurfaces.clear()
        dummyImageReaders.forEach { try { it.close() } catch (_: Throwable) {} }
        dummyImageReaders.clear()
        
        try {
            dummySinkThread?.quitSafely()
            dummySinkThread = null
            dummySinkHandler = null
        } catch (_: Throwable) {}
        
        surfaceMap.clear()
        formatBridges.clear()
        isStreamActive = false
        
        // Reset capture counters but KEEP latestVirtualJpeg warm.
        // The preview bridge already cached a JPEG — reuse it for instant first capture.
        // The JPEG session's bridge will update it with full-resolution data once ready.
        captureCount = 0
        captureQueue.clear()
    }

    private fun startRenderThreads(targetSurfaces: List<Triple<Surface, Boolean, Int>>) { // Changed to Triple
        val context = AndroidAppHelper.currentApplication() ?: return
        
        val sensorOrientation = resolveSensorOrientationDeg()
        Log.d(TAG, "VirtuCam_Hook: Using SENSOR_ORIENTATION $sensorOrientation for camera $activeCameraId")
        
        if (targetSurfaces.isNotEmpty()) {
            try {
                // [Sync Fix] Start ONE master thread for ALL surfaces to ensure sync and save CPU
                var isFront = (cameraFacings[activeCameraId] == 1)
                val thread = VirtualRenderThread(targetSurfaces, context, isVideo, isStream, streamUrl, isFront, sensorOrientation)
                thread.start()
                renderThreads.add(thread)
                Log.d(TAG, "VirtuCam_Hook: Started MASTER RenderThread for ${targetSurfaces.size} surfaces.")
            } catch (t: Throwable) {
                Log.e(TAG, "VirtuCam_Hook: Failed to start master RenderThread", t)
            }
        }
    }
    
    /**
     * Anti-Plugin mechanism: Removes our hooking frames from thread stack trace
     */
    private fun obfuscateStackTrace() {
        // Minimal stack trace scrubber signature
        // Advanced impl involves replacing the thread's StackTraceElement array
    }

    /**
     * Internal Camera Surface Utilities
     */
    private object SurfaceUtils {
        fun getSurfaceFormat(surface: Surface): Int {
            // Check our telemetry map first (populated via ImageReader hooks)
            val trackedFormat = surfaceFormats[surface]
            if (trackedFormat != null) {
                return trackedFormat
            }
            
            return try {
                val utilsClass = Class.forName("android.hardware.camera2.utils.SurfaceUtils")
                val getFormatMethod = utilsClass.getMethod("getSurfaceFormat", Surface::class.java)
                getFormatMethod.invoke(null, surface) as Int
            } catch (e: Throwable) {
                Log.e(TAG, "VirtuCam: Failed to get real surface format, defaulting to 0x22", e)
                0x22
            }
        }

        fun getSurfaceSize(surface: Surface): Pair<Int, Int> {
            // Priority 1: Our robust telemetry map
            val tracked = surfaceSizes[surface]
            if (tracked != null) return tracked

            return try {
                val utilsClass = Class.forName("android.hardware.camera2.utils.SurfaceUtils")
                val getSizeMethod = utilsClass.getMethod("getSurfaceSize", Surface::class.java)
                val sizeObj = getSizeMethod.invoke(null, surface)
                if (sizeObj is android.util.Size) {
                    Pair(sizeObj.width, sizeObj.height)
                } else {
                    Pair(1280, 720)
                }
            } catch (e: Throwable) {
                Log.e("CameraHook", "VirtuCam: Failed to get real surface size, defaulting to 1280x720", e)
                Pair(1280, 720)
            }
        }
    }

    /**

 * Dedicated thread targeting the hijacked surfaces via EGL
 */
class VirtualRenderThread(
    private val targetSurfaces: List<Triple<Surface, Boolean, Int>>,
    private val context: android.content.Context,
    private val isVideo: Boolean,
    private val isStream: Boolean,
    private val streamUrl: String,
    private val isFrontCamera: Boolean,
    private val sensorOrientation: Int = 0
) : Thread("VirtuCam-RenderThread") {
    
    private var renderTickCount = 0L
    
    @Volatile
    private var isRunning = true
    
    // Anti-Detection Sensor State
    private var sensorManager: android.hardware.SensorManager? = null
    private var gyroListener: android.hardware.SensorEventListener? = null
    private var lightListener: android.hardware.SensorEventListener? = null
    
    @Volatile private var gyroOffsetX = 0f
    @Volatile private var gyroOffsetY = 0f
    @Volatile private var ambientLightMultiplier = 1.0f
    private val renderStartTime = System.currentTimeMillis()
    
    private var eglCore: EglCore? = null
    // Triple: EGLSurface, isCapture, format
    private val eglSurfaceTargets = mutableListOf<Triple<android.opengl.EGLSurface, Boolean, Int>>()
    // Parallel list of original Surface objects for EGL surface recreation on resize
    private val originalSurfaceBackings = mutableListOf<Surface>()
    // [PERF OPT 4] Tracks consecutive slow swapBuffers per surface index for adaptive throttling
    private val surfaceThrottleMap = mutableMapOf<Int, Int>()
    private var textureRenderer: TextureRenderer? = null
    
    private var videoPlayer: VideoPlayer? = null
    private var streamPlayer: StreamPlayer? = null
    
    private var mediaSurfaceTexture: SurfaceTexture? = null
    private var mediaSurface: Surface? = null
    private var frameCount = 0
    
    // Background maintenance thread for non-render-critical tasks
    private var maintenanceThread: Thread? = null
    @Volatile private var maintenanceRunning = false
    
    // ANTI-DETECTION: Temporal smoothing state for motion normalization
    // Tracks frame timing to ensure consistent frame-to-frame differences
    private var lastFrameTimeNs = 0L
    private var lastNewFrameTimeNs = 0L
    private var framesSinceNewContent = 0
    private var smoothedMotionFactor = 0f  // Exponential smoothing of motion magnitude

    private fun getTargetRatio(vW: Int, vH: Int, isSurfaceView: Boolean): Float {
        return try {
            val sensorOrientation = CameraHook.resolveSensorOrientationDeg()
            
            if (isSurfaceView && (sensorOrientation == 90 || sensorOrientation == 270) && vW > vH) {
                // Return inverted logical ratio (e.g. 1080/1920 = 0.56) instead of physical (1920/1080 = 1.77)
                // This pre-squishes the image so SurfaceFlinger's landscape-to-portrait stretch restores it.
                vH.toFloat() / vW.toFloat()
            } else {
                vW.toFloat() / vH.toFloat()
            }
        } catch (e: Exception) {
            vW.toFloat() / vH.toFloat()
        }
    }
    
    /**
     * Background maintenance thread for non-render-critical periodic tasks:
     * - Configuration polling (every 1s instead of every 10 frames)
     * - JPEG pre-warming (every 200ms instead of every 30 frames)
     * 
     * Prevents render thread jank from DB queries and JPEG encoding.
     */
    private fun startMaintenanceThread() {
        maintenanceRunning = true
        maintenanceThread = Thread({
            try {
                var configCounter = 0
                var jpegCounter = 0
                
                while (maintenanceRunning && isRunning) {
                    Thread.sleep(100) // 10Hz tick rate
                    
                    configCounter++
                    jpegCounter++
                    
                    // Poll configuration every 1 second (10 ticks)
                    if (configCounter >= 10) {
                        try {
                            CameraHook.loadConfiguration()
                        } catch (e: Exception) {
                            Log.w("VirtuCam_Maintenance", "Config load failed: ${e.message}")
                        }
                        configCounter = 0
                    }
                    
                    // Warm JPEG cache every 200ms (2 ticks) for instant photo capture
                    if (jpegCounter >= 2) {
                        try {
                            CameraHook.formatBridges.values.forEach { it.warmJpegCache() }
                        } catch (e: Exception) {
                            Log.w("VirtuCam_Maintenance", "JPEG warm failed: ${e.message}")
                        }
                        jpegCounter = 0
                    }
                }
            } catch (e: InterruptedException) {
                // Normal shutdown
            } catch (e: Exception) {
                Log.e("VirtuCam_Maintenance", "Maintenance thread error", e)
            }
        }, "VirtuCam-Maintenance")
        maintenanceThread?.priority = Thread.MIN_PRIORITY
        maintenanceThread?.start()
    }

    override fun run() {
        try {
            // Anti-Detection Setup
            sensorManager = context.getSystemService(android.content.Context.SENSOR_SERVICE) as? android.hardware.SensorManager
            if (sensorManager != null) {
                // Gyroscope Shake (Feature 6)
                val gyro = sensorManager!!.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE)
                if (gyro != null) {
                    gyroListener = object : android.hardware.SensorEventListener {
                        override fun onSensorChanged(event: android.hardware.SensorEvent) {
                            // Gyroscope measures angular velocity (rad/s) around X, Y, Z axes
                            // Integrate to get rotation angles (degrees) for realistic camera tilt
                            // event.values[0] = X-axis (pitch), [1] = Y-axis (roll), [2] = Z-axis (yaw)
                            val pitchRate = event.values[0] * 57.2958f  // rad/s to deg/s
                            val rollRate = event.values[1] * 57.2958f
                            
                            // Integrate angular velocity to rotation angle (simplified, no dt)
                            // Scale down significantly - gyro rates are high, we want subtle tilt
                            val dPitch = pitchRate * 0.001f  // Pitch affects Y-axis tilt
                            val dRoll = rollRate * 0.001f   // Roll affects X-axis tilt
                            
                            gyroOffsetX = (gyroOffsetX + dRoll).coerceIn(-2.0f, 2.0f)  // ±2° max roll
                            gyroOffsetY = (gyroOffsetY + dPitch).coerceIn(-2.0f, 2.0f) // ±2° max pitch
                            
                            // Decay back to center (simulates hand stabilization)
                            gyroOffsetX *= 0.95f
                            gyroOffsetY *= 0.95f
                        }
                        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
                    }
                    sensorManager!!.registerListener(gyroListener, gyro, android.hardware.SensorManager.SENSOR_DELAY_UI)
                }

                // Ambient Light Correlation (Feature 8)
                val light = sensorManager!!.getDefaultSensor(android.hardware.Sensor.TYPE_LIGHT)
                if (light != null) {
                    lightListener = object : android.hardware.SensorEventListener {
                        override fun onSensorChanged(event: android.hardware.SensorEvent) {
                            val lux = event.values[0]
                            // Normal indoor light is ~100-300 lux. We vary brightness from 0.85 to 1.15
                            val target = 0.85f + (lux.coerceIn(0f, 1000f) / 1000f) * 0.3f
                            // Smooth interpolation
                            ambientLightMultiplier += (target - ambientLightMultiplier) * 0.1f
                        }
                        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
                    }
                    sensorManager!!.registerListener(lightListener, light, android.hardware.SensorManager.SENSOR_DELAY_UI)
                }
            }

            eglCore = EglCore()
            
            // Create EGL surfaces for ALL targets
            for (targetTriple in targetSurfaces) {
                try {
                    val surface = targetTriple.first
                    if (!surface.isValid) continue
                    
                    // Add a tiny retry for "already connected" race conditions
                    var es: android.opengl.EGLSurface? = null
                    for (i in 0..2) {
                        try {
                            // Get format from CameraHook.surfaceFormats
                            val format = CameraHook.surfaceFormats[surface] ?: 0x22 // Default to PRIVATE
                            es = eglCore!!.createWindowSurface(surface)
                            if (es != null) {
                                eglSurfaceTargets.add(Triple(es, targetTriple.second, targetTriple.third))
                                originalSurfaceBackings.add(surface)
                                break
                            }
                        } catch (e: Exception) {
                            if (i == 2) throw e
                            Thread.sleep(50)
                        }
                    }
                    
                    if (es != null) {
                        Log.d("VirtuCam_Render", "Created EGL surface for target: ${targetTriple.first}")
                    }
                } catch (e: Exception) {
                    Log.e("VirtuCam_Render", "Failed to create EGL surface for target (skipping): ${e.message}")
                }
            }
            
            if (eglSurfaceTargets.isEmpty()) {
                Log.e("VirtuCam_Render", "No valid target surfaces. Exiting.")
                return
            }

            // Make the first one current for init
            eglCore!!.makeCurrent(eglSurfaceTargets[0].first)
            
            textureRenderer = TextureRenderer(isVideo || isStream)
            textureRenderer!!.init()
            
            val uri = Uri.parse("content://com.kcam.provider/file")
            
            // Start background maintenance thread for config polling and JPEG pre-warming
            startMaintenanceThread()
            
            if (isStream) {
                // Live Stream Pipeline (ExoPlayer)
                mediaSurfaceTexture = SurfaceTexture(textureRenderer!!.textureId)
                mediaSurface = Surface(mediaSurfaceTexture)
                val hasNewFrame = java.util.concurrent.atomic.AtomicBoolean(false)
                mediaSurfaceTexture?.setOnFrameAvailableListener { hasNewFrame.set(true) }
                
                streamPlayer = StreamPlayer(
                    context, streamUrl, mediaSurface!!, CameraHook.rtspUseTcp,
                    { hasNewFrame.set(true) },
                    { bitmap ->
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            saveStreamPreviewToProvider(bitmap)
                        }
                    },
                    { error -> Log.e("VIRTUCAM_RTSP", "Stream error: $error") }
                )
                streamPlayer!!.start()
                
                renderLoop(hasNewFrame) { streamPlayer!!.videoWidth to streamPlayer!!.videoHeight }
                streamPlayer!!.stop()
                
            } else if (isVideo) {
                // Local Video Pipeline (MediaCodec)
                mediaSurfaceTexture = SurfaceTexture(textureRenderer!!.textureId)
                mediaSurface = Surface(mediaSurfaceTexture)
                
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    val fd = pfd.fileDescriptor
                    val hasNewFrame = java.util.concurrent.atomic.AtomicBoolean(false)
                    mediaSurfaceTexture?.setOnFrameAvailableListener { hasNewFrame.set(true) }
                    
                    videoPlayer = VideoPlayer(fd, mediaSurface!!) {}
                    videoPlayer!!.start()
                    
                    renderLoop(hasNewFrame) { videoPlayer!!.videoWidth to videoPlayer!!.videoHeight }
                    
                    videoPlayer!!.stop()
                    pfd.close()
                }
            } else {
                // Static Image Mode Pipeline
                // [INVESTIGATION] If test pattern mode is enabled, replace user media
                // with a known-orientation test pattern so we can analyze rotations
                // empirically against a reference.
                val bitmap: android.graphics.Bitmap? = if (CameraHook.isTestPatternMode) {
                    Log.e("VirtuCam_Render", "[INVESTIGATION] Using TEST PATTERN as source media")
                    // Render at 720x1280 (portrait, 9:16) — common selfie aspect.
                    // The renderer's aspect-fit math will scale it appropriately.
                    TestPattern.generate(720, 1280)
                } else {
                    val stream = context.contentResolver.openInputStream(uri)
                    val bm = BitmapFactory.decodeStream(stream)
                    stream?.close()
                    bm
                }
                
                if (bitmap != null) {
                    textureRenderer!!.loadBitmap(bitmap)
                    val staticImageW = bitmap.width
                    val staticImageH = bitmap.height
                    bitmap.recycle()
                    
                    val matrix = FloatArray(16)
                    
                    while (isRunning) {
                        frameCount++
                        // Config polling and JPEG pre-warming moved to background maintenance thread

                        Matrix.setIdentityM(matrix, 0)

                        if (CameraHook.isLivenessEnabled) {
                            val timeMs = System.currentTimeMillis()
                            // Base hand tremor (breathing, micro-movements)
                            val baseScale = 1.0f + (Math.sin(timeMs / 800.0) * 0.0015f).toFloat()
                            val baseTrX = (Math.sin(timeMs / 400.0) * 0.001f).toFloat()
                            val baseTrY = (Math.cos(timeMs / 550.0) * 0.001f).toFloat()
                            
                            // ANTI-DETECTION: Add high-frequency micro-jitter to ensure unique frames
                            // This is imperceptible but ensures no two frames are pixel-identical
                            val jitterX = (Math.sin(timeMs / 17.0) * 0.0002f).toFloat()
                            val jitterY = (Math.cos(timeMs / 23.0) * 0.0002f).toFloat()
                            val jitterScale = 1.0f + (Math.sin(timeMs / 31.0) * 0.0001f).toFloat()
                            
                            val finalTrX = baseTrX + jitterX
                            val finalTrY = baseTrY + jitterY
                            val finalScale = baseScale * jitterScale

                            Matrix.translateM(matrix, 0, finalTrX, finalTrY, 0f)
                            Matrix.translateM(matrix, 0, 0.5f, 0.5f, 0f)
                            Matrix.scaleM(matrix, 0, finalScale, finalScale, 1f)
                            Matrix.translateM(matrix, 0, -0.5f, -0.5f, 0f)
                        }

                        // [HARDWARE PARITY FIX] Consume exact hardware timestamps
                        var renderPts = CameraHook.pendingHardwareFrames.poll()
                        if (renderPts == null) {
                            val msSinceLastSync = System.currentTimeMillis() - CameraHook.lastFrameSyncMs
                            if (msSinceLastSync > 200) {
                                try { Thread.sleep(33) } catch (_: InterruptedException) {}
                                renderPts = android.os.SystemClock.elapsedRealtimeNanos()
                            } else {
                                synchronized(CameraHook.frameSyncObject) {
                                    try { CameraHook.frameSyncObject.wait(100) } catch (_: Exception) {}
                                }
                                renderPts = CameraHook.pendingHardwareFrames.poll() ?: android.os.SystemClock.elapsedRealtimeNanos()
                            }
                        }

                        if (!drawToAllSurfaces(matrix, staticImageW, staticImageH, renderPts)) break
                        
                        // Handle Photo/Capture Requests (Static Image)
                        synchronized(CameraHook) {
                            while (CameraHook.captureCount > 0) {
                                val capture = CameraHook.captureQueue.poll()
                                val timestamp = capture?.first ?: android.os.SystemClock.elapsedRealtimeNanos()
                                Log.e(TAG, "CAPT_LOG [2]: VirtualRenderThread draining captureQueue. Pushing to bridges. captureCount=${CameraHook.captureCount}")
                                CameraHook.formatBridges.values.forEach { 
                                    it.pushLatestFrameToWriter(timestamp)
                                }
                                CameraHook.captureCount--
                                
                                // [SAFETY VALVE] Prevent captureCount runaway (fixes "camera refuses to open" lockup)
                                if (CameraHook.captureCount > 5) {
                                    Log.w(TAG, "CAPT_LOG [!] Safety Valve: captureCount too high (${CameraHook.captureCount}). Resetting to zero.")
                                    CameraHook.captureCount = 0
                                    CameraHook.captureQueue.clear()
                                }
                            }
                        }
                        
                        sleep(33) // ~30 fps simulated heartbeat
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e("VirtuCam_Render", "Master Render thread error", t)
        } finally {
            releaseResources()
        }
    }

    private fun renderLoop(hasNewFrame: java.util.concurrent.atomic.AtomicBoolean, sizeProvider: () -> Pair<Int, Int>) {
        val matrix = FloatArray(16)
        val currentTimeNs = System.nanoTime()
        lastFrameTimeNs = currentTimeNs
        lastNewFrameTimeNs = currentTimeNs
        
        while (isRunning) {
            // [HARDWARE PARITY FIX] Consume exact hardware timestamps
            var renderPts = CameraHook.pendingHardwareFrames.poll()
            if (renderPts == null) {
                val msSinceLastSync = System.currentTimeMillis() - CameraHook.lastFrameSyncMs
                if (msSinceLastSync > 200) {
                    try { Thread.sleep(33) } catch (_: InterruptedException) {}
                    renderPts = android.os.SystemClock.elapsedRealtimeNanos()
                } else {
                    synchronized(CameraHook.frameSyncObject) {
                        try { CameraHook.frameSyncObject.wait(100) } catch (_: Exception) {}
                    }
                    renderPts = CameraHook.pendingHardwareFrames.poll() ?: android.os.SystemClock.elapsedRealtimeNanos()
                }
            }

            frameCount++
            val nowNs = System.nanoTime()
            val deltaNs = nowNs - lastFrameTimeNs
            lastFrameTimeNs = nowNs
            
            // ANTI-DETECTION: Track new frame arrivals for motion smoothing
            val gotNewFrame = hasNewFrame.compareAndSet(true, false)
            if (gotNewFrame) {
                try { mediaSurfaceTexture?.updateTexImage() } catch (_: Exception) {}
                framesSinceNewContent = 0
                lastNewFrameTimeNs = nowNs
            } else {
                framesSinceNewContent++
            }
            
            mediaSurfaceTexture?.getTransformMatrix(matrix)
            
            // ANTI-DETECTION: Temporal motion smoothing
            // Real cameras have consistent small motion between frames
            // We add micro-motion that scales inversely with time since last new frame
            // This ensures frames are never identical while maintaining smooth motion
            val timeMs = System.currentTimeMillis()
            val timeSinceNewFrameMs = (nowNs - lastNewFrameTimeNs) / 1_000_000f
            
            // Motion factor decreases smoothly as we wait for new content
            // This creates natural "settling" behavior like a real camera
            val motionDecay = 1.0f / (1.0f + timeSinceNewFrameMs * 0.01f)
            smoothedMotionFactor = smoothedMotionFactor * 0.9f + motionDecay * 0.1f
            
            if (CameraHook.isLivenessEnabled) {
                // Base hand tremor (breathing, micro-movements)
                val baseScale = 1.0f + (Math.sin(timeMs / 800.0) * 0.0015f).toFloat()
                val baseTrX = (Math.sin(timeMs / 400.0) * 0.001f).toFloat()
                val baseTrY = (Math.cos(timeMs / 550.0) * 0.001f).toFloat()
                
                // ANTI-DETECTION: Add high-frequency micro-jitter to ensure unique frames
                // This is imperceptible but ensures no two frames are pixel-identical
                val jitterX = (Math.sin(timeMs / 17.0) * 0.0002f).toFloat()  // ~60Hz jitter
                val jitterY = (Math.cos(timeMs / 23.0) * 0.0002f).toFloat()  // Prime frequency
                val jitterScale = 1.0f + (Math.sin(timeMs / 31.0) * 0.0001f).toFloat()
                
                // Combine base motion with micro-jitter
                val finalTrX = baseTrX + jitterX * smoothedMotionFactor
                val finalTrY = baseTrY + jitterY * smoothedMotionFactor
                val finalScale = baseScale * jitterScale

                Matrix.translateM(matrix, 0, finalTrX, finalTrY, 0f)
                Matrix.translateM(matrix, 0, 0.5f, 0.5f, 0f)
                Matrix.scaleM(matrix, 0, finalScale, finalScale, 1f)
                Matrix.translateM(matrix, 0, -0.5f, -0.5f, 0f)
            }
            
            val (vw, vh) = sizeProvider()
            if (!drawToAllSurfaces(matrix, vw, vh, renderPts)) break

            // Handle Photo/Capture Requests
            synchronized(CameraHook) {
                while (CameraHook.captureCount > 0) {
                    val capture = CameraHook.captureQueue.poll()
                    val timestamp = capture?.first ?: android.os.SystemClock.elapsedRealtimeNanos()
                    
                    CameraHook.latestVirtualJpegArea = 0
                    
                    CameraHook.formatBridges.values.forEach { 
                        it.pushLatestFrameToWriter(timestamp) 
                    }
                    CameraHook.captureCount--
                }
            }
            
            // Sync and pacing moved to top of loop for Hardware Parity Fix
        }
    }

    private fun drawToAllSurfaces(matrix: FloatArray, contentW: Int, contentH: Int, renderPts: Long): Boolean {
        renderTickCount++
        // [Dynamic Resize] Recreate EGL surfaces if browser renegotiated resolution
        if (CameraHook.pendingSurfaceResize.compareAndSet(true, false)) {
            recreateEglSurfaces()
        }
        // [PERF OPT 4] Surface throttle state: tracks consecutive slow swaps per surface index
        // Surfaces whose swapBuffers blocks >25ms are likely inactive (consumer not dequeuing).
        // We throttle rendering to them (1 in 10 frames) to prevent stalling the preview.
        
        val it = eglSurfaceTargets.iterator()
        var surfaceIndex = 0
        while (it.hasNext()) {
            val it_triple = it.next()
            val es = it_triple.first
            val isCapture = it_triple.second
            val format = it_triple.third

            // [PERF OPT 4 REMOVED] Surface throttle state previously caused jumping/crashing

            // [CPU SATURATION FIX] Rendering to massive JPEG/YUV capture surfaces every frame
            // causes fatal CPU bottlenecks and GC churn (e.g., 4000x3000 = 48MB copied per frame).
            // We MUST gate rendering to capture surfaces by captureCount > 0. The fallback
            // logic in FormatConverterBridge now safely delegates to the preview bridge if needed.
            if (isCapture && CameraHook.captureCount <= 0) {
                // [WARM CACHE FIX] Render once every 30 frames (1s) to keep the ImageReader warm
                // for standalone JPEG bridges, without burning the CPU.
                if (renderTickCount % 30 != 0L) {
                    surfaceIndex++
                    continue
                }
            }

            val tStart = System.nanoTime()
            try {
                eglCore!!.makeCurrent(es)
                var vw = eglCore!!.querySurface(es, android.opengl.EGL14.EGL_WIDTH)
                var vh = eglCore!!.querySurface(es, android.opengl.EGL14.EGL_HEIGHT)
                // Before the first dequeue, eglQuerySurface often returns 0×0; then TextureRenderer
                // skips rotation (identity MVP) and buffers stay "upright" wrongly.
                val surfaceIdx = eglSurfaceTargets.indexOfFirst { triple -> triple.first === es }
                val originalSurface = if (surfaceIdx >= 0 && surfaceIdx < originalSurfaceBackings.size) originalSurfaceBackings[surfaceIdx] else null
                val isSurfaceTexture = originalSurface != null && surfaceSizes.containsKey(originalSurface)
                val isSurfaceView = !isCapture && !isSurfaceTexture

                if ((vw <= 0 || vh <= 0) && originalSurface != null) {
                    CameraHook.trackedSurfaceSize(originalSurface)?.let { (w, h) ->
                        if (w > 0 && h > 0) {
                            vw = w
                            vh = h
                        }
                    }
                }
                if (vw <= 0 || vh <= 0) {
                    vw = 1280
                    vh = 720
                }
                
                // [RESOLUTION MISMATCH WARNING] Detect if stream resolution doesn't match requested resolution
                if (isStream && frameCount % 300 == 0) {  // Log every 10 seconds
                    val streamW = contentW
                    val streamH = contentH
                    val requestedW = vw
                    val requestedH = vh
                    val scaleFactor = Math.max(requestedW.toFloat() / streamW, requestedH.toFloat() / streamH)
                    if (scaleFactor > 1.3f) {  // More than 30% upscaling
                        Log.w("VirtuCam_Render", "⚠️ RESOLUTION MISMATCH: Stream is ${streamW}x${streamH} but app requested ${requestedW}x${requestedH} (${scaleFactor}x upscale). Configure OBS to stream at higher resolution to avoid detection.")
                    }
                }

                // Emulate HAL auto-rotation for SurfaceView (0 = Upright), otherwise hardware parity (Raw sensor orientation)
                val targetBufferRotation = if (isSurfaceView) 0 else CameraHook.resolveSensorOrientationDeg()

                val parityOrientation = targetBufferRotation
                val finalUserRotation = 0
                val videoCompensation = if (isVideo) (CameraHook.resolveSensorOrientationDeg() - (videoPlayer?.videoRotation ?: 0))
                                        else if (isStream) (CameraHook.resolveSensorOrientationDeg() - (streamPlayer?.videoRotation ?: 0) - 90)
                                        else 0
                val finalRotationOffset = CameraHook.rotationOffset + videoCompensation

                // DYNAMIC MIRRORING LOGIC (Axis-Swapping handled in TextureRenderer)
                val isActuallyFront = CameraHook.isActiveCameraFrontFacing()
                val shouldMirror = if (isActuallyFront) {
                    (format == 35 || format == 34 || format == 0x100 || format == 1 || format == 0)
                } else {
                    CameraHook.isMirrored
                }

                // Front/Back Camera Differentiation (Feature 10)
                // Slight crop/zoom applied only to front camera to mimic lens variation
                val finalZoom = if (isActuallyFront) CameraHook.zoomFactor * 1.05f else CameraHook.zoomFactor

                val ratio = getTargetRatio(vw, vh, isSurfaceView)

                val timeValue = (System.currentTimeMillis() - renderStartTime) / 1000.0f

                // Emulate missing hardware EXIF rotation (-90 deg CW) for downloaded videos AND streams
                val renderMatrix = if ((isVideo && videoPlayer?.rawRotation == 0) || (isStream && streamPlayer?.rawRotation == 0)) {
                    val rotatedMatrix = FloatArray(16)
                    System.arraycopy(matrix, 0, rotatedMatrix, 0, 16)
                    android.opengl.Matrix.translateM(rotatedMatrix, 0, 0.5f, 0.5f, 0f)
                    android.opengl.Matrix.rotateM(rotatedMatrix, 0, -90f, 0f, 0f, 1f)
                    android.opengl.Matrix.translateM(rotatedMatrix, 0, -0.5f, -0.5f, 0f)
                    rotatedMatrix
                } else {
                    matrix
                }

                // Get current screen color for ambient light simulation (color flash bypass)
                val screenColor = if (CameraHook.isColorFlashEnabled) {
                    ScreenColorDetector.getInstance().getCurrentColor()
                } else {
                    ScreenColorDetector.DetectedColor.NONE
                }
                
                textureRenderer?.draw(
                    renderMatrix, contentW, contentH, vw, vh, ratio,
                    parityOrientation, finalUserRotation, shouldMirror,
                    finalZoom, isCapture, CameraHook.compensationFactor,
                    finalRotationOffset, ambientLightMultiplier, timeValue,
                    gyroOffsetX, gyroOffsetY,
                    colorTintR = screenColor.r,
                    colorTintG = screenColor.g,
                    colorTintB = screenColor.b,
                    colorIntensity = screenColor.intensity
                )

                eglCore?.setPresentationTime(es, renderPts)
                
                // [PERF OPT 4 REMOVED] Measure swapBuffers time, but do NOT throttle.
                // Throttling breaks backpressure and causes the Native Camera to jump and crash.
                val swapStart = System.nanoTime()
                if (eglCore?.swapBuffers(es) == false) {
                    Log.w("VirtuCam_Render", "Surface abandoned, removing.")
                    it.remove()
                    surfaceIndex++
                    continue
                }
                val swapElapsedMs = (System.nanoTime() - swapStart) / 1_000_000

                if (frameCount % 60 == 0) {
                    val tEnd = System.nanoTime()
                    Log.d("VIRTUCAM_PERF", "Render target $vw x $vh took ${(tEnd - tStart)/1_000_000}ms (swap=${swapElapsedMs}ms)")
                }
            } catch (e: Exception) {
                it.remove()
            }
            surfaceIndex++
        }
        return eglSurfaceTargets.isNotEmpty()
    }

    /**
     * Recreate all EGL surfaces from their original Surface backings.
     * Called when the browser renegotiates the video stream resolution
     * (e.g. Veriff resizes from 1280x720 → 720x720 via applyConstraints).
     * The new EGL surface picks up the updated native window dimensions.
     */
    private fun recreateEglSurfaces() {
        Log.d("VirtuCam_Render", "[Dynamic Resize] Recreating EGL surfaces...")
        val oldTargets = ArrayList(eglSurfaceTargets)
        val oldSurfaces = ArrayList(originalSurfaceBackings)
        eglSurfaceTargets.clear()
        originalSurfaceBackings.clear()

        for (i in oldTargets.indices) {
            val old = oldTargets[i]
            val backing = oldSurfaces.getOrNull(i)
            try {
                eglCore?.releaseSurface(old.first)
            } catch (_: Exception) {}

            if (backing != null && backing.isValid) {
                try {
                    val newEs = eglCore!!.createWindowSurface(backing)
                    val vw = eglCore!!.querySurface(newEs, android.opengl.EGL14.EGL_WIDTH)
                    val vh = eglCore!!.querySurface(newEs, android.opengl.EGL14.EGL_HEIGHT)
                    Log.d("VirtuCam_Render", "[Dynamic Resize] Surface $i recreated: ${vw}x${vh}")
                    eglSurfaceTargets.add(Triple(newEs, old.second, old.third))
                    originalSurfaceBackings.add(backing)
                } catch (e: Exception) {
                    Log.e("VirtuCam_Render", "[Dynamic Resize] Failed to recreate surface $i", e)
                }
            }
        }

        if (eglSurfaceTargets.isNotEmpty()) {
            eglCore?.makeCurrent(eglSurfaceTargets[0].first)
        }
        Log.d("VirtuCam_Render", "[Dynamic Resize] Done. ${eglSurfaceTargets.size} surfaces active.")
    }

    private fun releaseResources() {
        isRunning = false
        
        // Stop background maintenance thread
        maintenanceRunning = false
        maintenanceThread?.interrupt()
        try {
            maintenanceThread?.join(500)
        } catch (e: InterruptedException) {}
        maintenanceThread = null

        if (sensorManager != null) {
            gyroListener?.let { sensorManager!!.unregisterListener(it) }
            lightListener?.let { sensorManager!!.unregisterListener(it) }
            gyroListener = null
            lightListener = null
            sensorManager = null
        }

        try {
            videoPlayer?.stop()
        } catch (e: Exception) {}
        streamPlayer?.stop()
        mediaSurface?.release()
        mediaSurfaceTexture?.release()
        
        eglSurfaceTargets.forEach { 
            try { eglCore?.releaseSurface(it.first) } catch (_: Exception) {}
        }
        eglSurfaceTargets.clear()
        originalSurfaceBackings.clear()
        
        textureRenderer?.release()
        eglCore?.release()
    }
    
    fun quit() {
        isRunning = false
    }
}
    private fun hookMediaRecorderOrientation(lpparam: XC_LoadPackage.LoadPackageParam) {
        // [HARDWARE PARITY FIX] Disabled. Let the OS handle MediaRecorder orientation physically.
    }

    private fun hookMediaFormatRotation(lpparam: XC_LoadPackage.LoadPackageParam) {
        // [HARDWARE PARITY FIX] Disabled. Let MediaFormat report true rotation naturally.
    }

    private fun hookMediaMuxerOrientation(lpparam: XC_LoadPackage.LoadPackageParam) {
        // [HARDWARE PARITY FIX] Disabled. Let MediaMuxer use the physical hint.
    }

    /**
     * [INVESTIGATION] Surface display-layer instrumentation.
     *
     * Logs what consumer apps do AFTER they receive our buffer:
     *   - TextureView.setTransform(Matrix) — apps like scanners use this to rotate
     *     the displayed texture independent of the buffer content
     *   - Display.getRotation() at session begin — host activity orientation context
     *
     * Output goes to the existing audit JSON via HardwareAuditLogger.
     */
    private fun hookDisplayLayer(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 1) TextureView.setTransform(Matrix) — captures app-applied display matrix
        try {
            val textureViewCls = XposedHelpers.findClassIfExists(
                "android.view.TextureView", lpparam.classLoader
            )
            if (textureViewCls != null) {
                PineHelper.hookAllMethods(textureViewCls, "setTransform", object : PineHelper.PineCompatibleMethodHook() {
                    override fun afterHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                        try {
                            val matrix = param.args[0] as? android.graphics.Matrix ?: return
                            val vals = FloatArray(9)
                            matrix.getValues(vals)
                            val str = vals.joinToString(",") { String.format("%.4f", it) }
                            Log.e(TAG, "AUDIT_DISPLAY: TextureView.setTransform([$str])")
                            try {
                                HardwareAuditLogger.logDisplayTransform("TextureView.setTransform", str)
                            } catch (_: Throwable) {}
                        } catch (_: Throwable) {}
                    }
                })
                Log.d(TAG, "Display-layer: hooked TextureView.setTransform")
            }
        } catch (_: Throwable) {}

        // 2) View.setRotation(float) — captures direct view rotation calls (less common but possible)
        try {
            val viewCls = XposedHelpers.findClassIfExists(
                "android.view.View", lpparam.classLoader
            )
            if (viewCls != null) {
                PineHelper.findAndHookMethod(viewCls, "setRotation", Float::class.javaPrimitiveType,
                    object : PineHelper.PineCompatibleMethodHook() {
                        override fun afterHookedMethod(param: top.canyie.pine.Pine.CallFrame) {
                            try {
                                val deg = (param.args[0] as Float)
                                if (deg != 0f) {
                                    val view = param.thisObject
                                    val cls = view.javaClass.simpleName
                                    Log.e(TAG, "AUDIT_DISPLAY: View($cls).setRotation($deg)")
                                    try {
                                        HardwareAuditLogger.logDisplayTransform("View.setRotation:$cls", deg.toString())
                                    } catch (_: Throwable) {}
                                }
                            } catch (_: Throwable) {}
                        }
                    })
            }
        } catch (_: Throwable) {}
    }
}

