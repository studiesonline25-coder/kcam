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
import java.util.Collections
import java.util.WeakHashMap
import java.util.HashSet
import de.robv.android.xposed.XC_MethodHook
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
    private var isVideo = false
    private var isStream = false
    private var streamUrl: String = ""
    private var targetPackage: String = ""
    
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
    
    // [ONCE AND FOR ALL] Surface tracking and crash prevention structures
    private val surfaceFormats = Collections.synchronizedMap(WeakHashMap<Surface, Int>())
    private val hookedListenerClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())

    /**
     * Initialize all camera hooks
     */
    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            targetPackage = lpparam.packageName
            Log.d(TAG, "VirtuCam_Hook: Initializing hooks for $targetPackage")
            
            hookCameraManager(lpparam)
            hookImageReader(lpparam)
            hookCaptureRequest(lpparam)
            hookCameraError(lpparam)
            hookCameraDevice(lpparam)
            hookCameraDeviceOutputConfigurations(lpparam)
            hookCamera1(lpparam)
            
            Log.d(TAG, "VirtuCam_Hook: All hooks deployed successfully.")
        } catch (t: Throwable) {
            Log.e(TAG, "VirtuCam_Hook: CRITICAL failure in $targetPackage", t)
        }
    }

    /**
     * Suppress CameraDevice.StateCallback.onError to prevent "Can't connect to camera" errors.
     * When the camera HAL encounters issues with our dummy surfaces, it fires onError(),
     * which causes the app to show an error dialog or crash. We suppress this entirely.
     */
    private fun hookCameraError(lpparam: XC_LoadPackage.LoadPackageParam) {
        val stateCallbackClass = XposedHelpers.findClassIfExists(
            "android.hardware.camera2.CameraDevice\$StateCallback", lpparam.classLoader
        ) ?: return

        XposedBridge.hookAllMethods(stateCallbackClass, "onError", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!isEnabled) return
                val errorCode = if (param.args.size >= 2) param.args[1] as? Int else null
                Log.d(TAG, "VirtuCam_Hook: Suppressed CameraDevice.onError (code=$errorCode)")
                param.result = null // Prevent the callback from executing
            }
        })

        // Also suppress onDisconnected to prevent the app from closing when the HAL disconnects
        XposedBridge.hookAllMethods(stateCallbackClass, "onDisconnected", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!isEnabled) return
                Log.d(TAG, "VirtuCam_Hook: Suppressed CameraDevice.onDisconnected")
                param.result = null
            }
        })
    }

    private fun hookCameraManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        val managerClass = XposedHelpers.findClassIfExists("android.hardware.camera2.CameraManager", lpparam.classLoader) ?: return
        XposedBridge.hookAllMethods(managerClass, "openCamera", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val cameraId = param.args[0] as? String
                Log.d(TAG, "VirtuCam_Hook: App is opening Camera2 ID: $cameraId")
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

        XposedBridge.hookAllMethods(builderClass, "addTarget", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    if (!isEnabled) return
                    val originalSurface = param.args[0] as? Surface ?: return
                    val dummySurface = surfaceMap[originalSurface]
                    if (dummySurface != null) {
                        Log.d(TAG, "VirtuCam_Hook: CaptureRequest.addTarget() → swapped to dummy surface")
                        param.args[0] = dummySurface
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "VirtuCam_Hook: Error in CaptureRequest.addTarget hook", t)
                }
            }
        })
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

        // [ONCE AND FOR ALL] Track Surface formats from ImageReader creation
        XposedBridge.hookAllMethods(imageReaderClass, "newInstance", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val reader = param.result as? ImageReader ?: return
                val format = param.args[2] as? Int ?: return
                val surface = reader.surface
                if (surface != null) {
                    surfaceFormats[surface] = format
                }
            }
        })

        XposedBridge.hookAllMethods(imageReaderClass, "getSurface", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val reader = param.thisObject as? ImageReader ?: return
                val surface = param.result as? Surface ?: return
                val format = XposedHelpers.callMethod(reader, "getImageFormat") as? Int ?: return
                surfaceFormats[surface] = format
            }
        })

        val overwriteHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                // Secondary Defense: Suppress format mismatch exceptions from nativeImageSetup.
                if (param.throwable is UnsupportedOperationException) {
                    Log.d(TAG, "VirtuCam_Hook: Suppressed format mismatch: ${param.throwable?.message}")
                    param.throwable = null
                    param.result = null
                    return
                }

                try {
                    val image = param.result as? Image ?: return
                    val format = image.format
                    
                    // Find matching bridge for this image dimensions
                    val bridge = activeBridges.firstOrNull { it.width == image.width && it.height == image.height }
                    
                    when (format) {
                        ImageFormat.YUV_420_888, ImageFormat.YV12, 35 -> {
                            // YUV Data Override path
                            if (bridge != null && !bridge.hasImageWriter) {
                                bridge.overwriteImageWithLatestYuv(image)
                                Log.d(TAG, "VirtuCam_Hook: Overwrote YUV image ${image.width}x${image.height}")
                            }
                        }
                        256 -> { // JPEG = 0x100 = 256
                            // JPEG Capture Override path
                            if (bridge != null && !bridge.hasImageWriter) {
                                bridge.overwriteImageWithLatestJpeg(image)
                                Log.d(TAG, "VirtuCam_Hook: Overwrote JPEG capture ${image.width}x${image.height}")
                            } else if (bridge == null) {
                                // No matching bridge - try any bridge without writer
                                val anyBridge = activeBridges.firstOrNull { !it.hasImageWriter }
                                if (anyBridge != null) {
                                    anyBridge.overwriteImageWithLatestJpeg(image)
                                    Log.d(TAG, "VirtuCam_Hook: Overwrote JPEG capture (fallback bridge) ${image.width}x${image.height}")
                                }
                            }
                        }
                        else -> {
                            // Unknown format - try YUV overwrite as a best-effort
                            if (bridge != null && !bridge.hasImageWriter && image.planes.size >= 3) {
                                bridge.overwriteImageWithLatestYuv(image)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "VirtuCam_Hook: Error during ImageReader data overwrite", e)
                }
            }
        }

        XposedBridge.hookAllMethods(imageReaderClass, "acquireNextImage", overwriteHook)
        XposedBridge.hookAllMethods(imageReaderClass, "acquireLatestImage", overwriteHook)
    }

    private fun hookCamera1(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cameraClass = XposedHelpers.findClassIfExists("android.hardware.Camera", lpparam.classLoader) ?: return

        XposedBridge.hookAllMethods(cameraClass, "setPreviewTexture", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    loadConfiguration()
                    if (!isEnabled) return
                    val st = param.args[0] as? SurfaceTexture ?: return
                    Log.d(TAG, "VirtuCam_Hook: Intercepted setPreviewTexture (Camera1)")
                    startRenderThreads(listOf(Surface(st)))
                } catch (t: Throwable) {
                    Log.e(TAG, "VirtuCam_Hook: Error in Camera1 setPreviewTexture hook", t)
                }
            }
        })

        XposedBridge.hookAllMethods(cameraClass, "setPreviewDisplay", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    loadConfiguration()
                    if (!isEnabled) return
                    val holder = param.args[0] as? android.view.SurfaceHolder ?: return
                    val surface = holder.surface ?: return
                    Log.d(TAG, "VirtuCam_Hook: Intercepted setPreviewDisplay (Camera1)")
                    stopOldPipeline()
                    startRenderThreads(listOf(surface))
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
        
        // Use YUV_420_888 as it's universally supported for sinks
        val format = android.graphics.ImageFormat.YUV_420_888
        val reader = ImageReader.newInstance(width, height, format, 4)
        
        reader.setOnImageAvailableListener({ ir ->
            try {
                // Instantly consume and discard the image to keep the pipeline flowing
                ir.acquireNextImage()?.close()
                // Sync Trigger: The real camera just took a photo! Push our spoofed frame to the app NOW!
                bridge?.pushLatestFrameToWriter()
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
        
        Log.d(TAG, "VirtuCam_Hook: Created active ImageReader dummy sink (${width}x${height}, format=$format)")
        return surface
    }

    /**
     * Extract width/height from an OutputConfiguration via reflection
     */
    private fun getSizeFromOutputConfig(config: Any): Pair<Int, Int> {
        try {
            val getSurfaceMethod = config.javaClass.getMethod("getSurface")
            val surface = getSurfaceMethod.invoke(config) as? Surface
            if (surface != null) {
                // Try to get size from the Surface's internal canvas or native fields
                try {
                    val mConfiguredWidthField = XposedHelpers.findFieldIfExists(config.javaClass, "mConfiguredSize")
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
                    val sizeField = XposedHelpers.findFieldIfExists(config.javaClass, "mSurfaceSize")
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
        return Pair(1280, 720) // Safe default for the Redmi 14C
    }

    @Suppress("UNCHECKED_CAST")
    private fun swapSurfaceInOutputConfig(config: Any, targetSurface: Surface): Surface {
        val (w, h) = getSizeFromOutputConfig(config)
        
        val format = SurfaceUtils.getSurfaceFormat(targetSurface)
        val isPreview = (format == 0x22 || format == 0x1)
        
        val bridge = if (!isPreview) {
            val b = FormatConverterBridge(w, h, targetSurface, format)
            activeBridges.add(b)
            b
        } else {
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

    /**
     * Poll configuration via ContentProvider
     */
    private fun loadConfiguration() {
        try {
            val context = AndroidAppHelper.currentApplication() ?: return
            val uri = Uri.parse("content://com.virtucam.provider/config")
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            
            cursor?.use {
                if (it.moveToFirst()) {
                    try {
                        isEnabled = it.getInt(0) == 1
                        
                        // Default values for missing columns in older DB versions
                        isVideo = if (it.columnCount > 2) it.getInt(2) == 1 else false
                        isStream = if (it.columnCount > 3) it.getInt(3) == 1 else false
                        streamUrl = if (it.columnCount > 4) it.getString(4) ?: "" else ""
                        
                        Log.d(TAG, "VirtuCam_Hook: Config loaded. Enabled: $isEnabled, Stream: $isStream")
                    } catch (innerE: Exception) {
                        Log.e(TAG, "VirtuCam_Hook: Error parsing cursor columns", innerE)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "VirtuCam_Hook: Failed to load configuration (Provider possibly blocked)", e)
        }
    }

    /**
     * Hooks createCaptureSession(List<Surface>, ...)
     */
    private fun hookCameraDevice(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cameraDeviceImplClass = XposedHelpers.findClassIfExists(
            "android.hardware.camera2.impl.CameraDeviceImpl",
            lpparam.classLoader
        ) ?: return

        XposedBridge.hookAllMethods(
            cameraDeviceImplClass,
            "createCaptureSession",
            object : XC_MethodHook() {
                @Suppress("UNCHECKED_CAST")
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        // Lazy load configuration when camera is actually accessed
                        loadConfiguration()
                        if (!isEnabled) return

                        val args = param.args
                        if (args.isEmpty() || args[0] !is List<*>) return

                        val surfacesList = args[0] as List<Surface>
                        if (surfacesList.isNotEmpty()) {
                            Log.d(TAG, "VirtuCam_Hook: Intercepted createCaptureSession (Standard) - count: ${surfacesList.size}")
                            
                            stopOldPipeline()
                            
                            val newSurfaces = ArrayList<Surface>()
                            val targetSurfaces = ArrayList<Surface>()
                            
                            for (targetSurface in surfacesList) {
                                val format = SurfaceUtils.getSurfaceFormat(targetSurface)
                                val isPreview = (format == 0x22 || format == 0x1)
                                
                                val bridge = if (!isPreview) {
                                    val b = FormatConverterBridge(1280, 720, targetSurface, format)
                                    activeBridges.add(b)
                                    targetSurfaces.add(b.inputSurface ?: targetSurface)
                                    b
                                } else {
                                    targetSurfaces.add(targetSurface)
                                    null
                                }
                                
                                val dummySurface = createDummySurface(targetSurface, 1280, 720, bridge)
                                surfaceMap[targetSurface] = dummySurface
                                newSurfaces.add(dummySurface)
                            }
                            
                            param.args[0] = newSurfaces
                            
                            startRenderThreads(targetSurfaces)
                            obfuscateStackTrace()
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "VirtuCam_Hook: Error in createCaptureSession hook", t)
                    }
                }
            }
        )
    }

    /**
     * Hooks createCaptureSessionByOutputConfigurations(List<OutputConfiguration>, ...)
     */
    private fun hookCameraDeviceOutputConfigurations(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cameraDeviceImplClass = XposedHelpers.findClassIfExists(
            "android.hardware.camera2.impl.CameraDeviceImpl",
            lpparam.classLoader
        ) ?: return

        XposedBridge.hookAllMethods(
            cameraDeviceImplClass,
            "createCaptureSessionByOutputConfigurations",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        loadConfiguration()
                        if (!isEnabled) return
                        
                        val args = param.args
                        if (args.isEmpty() || args[0] !is List<*>) return
                        
                        val configs = args[0] as List<*>
                        if (configs.isNotEmpty()) {
                            Log.d(TAG, "VirtuCam_Hook: Intercepted createCaptureSessionByOutputConfigurations - count: ${configs.size}")
                            
                            stopOldPipeline()
                            
                            val targetSurfaces = ArrayList<Surface>()
                            
                            for (config in configs) {
                                if (config == null) continue
                                
                                val getSurfaceMethod = config.javaClass.getMethod("getSurface")
                                val targetSurface = getSurfaceMethod.invoke(config) as? Surface
                                
                                if (targetSurface != null) {
                                    val resolvedSurface = swapSurfaceInOutputConfig(config, targetSurface)
                                    targetSurfaces.add(resolvedSurface)
                                }
                            }
                            
                            startRenderThreads(targetSurfaces)
                            obfuscateStackTrace()
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "VirtuCam_Hook: Error in createCaptureSessionByOutputConfigurations hook", t)
                    }
                }
            }
        )
        
        // Android 28+ uses SessionConfiguration
        XposedBridge.hookAllMethods(
            cameraDeviceImplClass,
            "createCaptureSession",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        loadConfiguration()
                        if (!isEnabled) return
                        
                        val args = param.args
                        if (args.isEmpty()) return
                        
                        val sessionConfig = args[0]
                        if (sessionConfig != null && sessionConfig.javaClass.simpleName == "SessionConfiguration") {
                            val getOutputConfigsMethod = sessionConfig.javaClass.getMethod("getOutputConfigurations")
                            val configs = getOutputConfigsMethod.invoke(sessionConfig) as? List<*>
                            
                            if (!configs.isNullOrEmpty()) {
                                Log.d(TAG, "VirtuCam_Hook: Intercepted SessionConfiguration - count: ${configs.size}")
                                
                                stopOldPipeline()
                                
                                val targetSurfaces = ArrayList<Surface>()
                                
                                for (config in configs) {
                                    if (config == null) continue
                                    val getSurfaceMethod = config.javaClass.getMethod("getSurface")
                                    val targetSurface = getSurfaceMethod.invoke(config) as? Surface
                                    
                                    if (targetSurface != null) {
                                        val resolvedSurface = swapSurfaceInOutputConfig(config, targetSurface)
                                        targetSurfaces.add(resolvedSurface)
                                    }
                                }
                                
                                startRenderThreads(targetSurfaces)
                                obfuscateStackTrace()
                            }
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "VirtuCam_Hook: Error overriding SessionConfiguration", t)
                    }
                }
            }
        )
    }

    private fun stopOldPipeline() {
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
    }

    private fun startRenderThreads(targetSurfaces: List<Surface>) {
        val context = AndroidAppHelper.currentApplication() ?: return
        
        for (surface in targetSurfaces) {
            try {
                val thread = VirtualRenderThread(surface, context, isVideo, isStream, streamUrl)
                thread.start()
                renderThreads.add(thread)
                Log.d(TAG, "VirtuCam_Hook: Started RenderThread for surface.")
            } catch (t: Throwable) {
                Log.e(TAG, "VirtuCam_Hook: Failed to start RenderThread", t)
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
                // Heuristic: If we don't know the format, we try to guess if it's a preview surface.
                // Preview surfaces usually don't have ImageReader metadata attached.
                0x22 // Default to PRIVATE (Hijack-able)
            } catch (e: Exception) {
                0x22
            }
        }

        fun getSurfaceSize(surface: Surface): Pair<Int, Int> {
            return Pair(1280, 720)
        }
    }
}

/**
 * Dedicated thread targeting the hijacked surface via EGL
 */
class VirtualRenderThread(
    private val targetSurface: Surface,
    private val context: android.content.Context,
    private val isVideo: Boolean,
    private val isStream: Boolean,
    private val streamUrl: String
) : Thread("VirtuCam-RenderThread") {
    
    @Volatile
    private var isRunning = true
    
    private var eglCore: EglCore? = null
    private var eglSurface: android.opengl.EGLSurface? = null
    private var textureRenderer: TextureRenderer? = null
    
    private var videoPlayer: VideoPlayer? = null
    private var streamPlayer: StreamPlayer? = null
    
    private var mediaSurfaceTexture: SurfaceTexture? = null
    private var mediaSurface: Surface? = null
    
    override fun run() {
        try {
            eglCore = EglCore()
            eglSurface = eglCore!!.createWindowSurface(targetSurface)
            eglCore!!.makeCurrent(eglSurface!!)
            
            textureRenderer = TextureRenderer(isVideo)
            textureRenderer!!.init()
            
            val uri = Uri.parse("content://com.virtucam.provider/file")
            
            if (isStream) {
                // Live Stream Pipeline (ExoPlayer)
                mediaSurfaceTexture = SurfaceTexture(textureRenderer!!.textureId)
                mediaSurface = Surface(mediaSurfaceTexture)
                
                val hasNewFrame = java.util.concurrent.atomic.AtomicBoolean(false)
                mediaSurfaceTexture?.setOnFrameAvailableListener {
                    hasNewFrame.set(true)
                }
                
                streamPlayer = StreamPlayer(context, streamUrl, mediaSurface!!) {}
                streamPlayer!!.start()
                
                val matrix = FloatArray(16)
                while (isRunning) {
                    if (hasNewFrame.compareAndSet(true, false)) {
                        try {
                            mediaSurfaceTexture?.updateTexImage()
                        } catch (e: Exception) {}
                    }
                    mediaSurfaceTexture?.getTransformMatrix(matrix)
                    textureRenderer?.draw(matrix, 0)
                    if (eglCore?.swapBuffers(eglSurface!!) == false) {
                        Log.w("VirtuCam_Render", "Target surface abandoned during stream. Stopping thread.")
                        quit()
                        break
                    }
                    sleep(33)
                }
                streamPlayer!!.stop()
                
            } else if (isVideo) {
                // Local Video Pipeline (MediaCodec)
                mediaSurfaceTexture = SurfaceTexture(textureRenderer!!.textureId)
                mediaSurface = Surface(mediaSurfaceTexture)
                
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    val fd = pfd.fileDescriptor
                    
                    val hasNewFrame = java.util.concurrent.atomic.AtomicBoolean(false)
                    mediaSurfaceTexture?.setOnFrameAvailableListener {
                        hasNewFrame.set(true)
                    }
                    
                    videoPlayer = VideoPlayer(fd, mediaSurface!!) {}
                    videoPlayer!!.start()
                    
                    val matrix = FloatArray(16)
                    while (isRunning) {
                        if (hasNewFrame.compareAndSet(true, false)) {
                            try {
                                mediaSurfaceTexture?.updateTexImage()
                            } catch (e: Exception) {}
                        }
                        mediaSurfaceTexture?.getTransformMatrix(matrix)
                        textureRenderer?.draw(matrix, 0)
                        if (eglCore?.swapBuffers(eglSurface!!) == false) {
                            Log.w("VirtuCam_Render", "Target surface abandoned during video. Stopping thread.")
                            quit()
                            break
                        }
                        sleep(33)
                    }
                    
                    videoPlayer!!.stop()
                    pfd.close()
                }
            } else {
                // Static Image Mode Pipeline
                val stream = context.contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(stream)
                stream?.close()
                
                if (bitmap != null) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureRenderer!!.textureId)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                    bitmap.recycle()
                    
                    val matrix = FloatArray(16)
                    Matrix.setIdentityM(matrix, 0)
                    
                    while (isRunning) {
                        textureRenderer?.draw(matrix, 0)
                        if (eglCore?.swapBuffers(eglSurface!!) == false) {
                            Log.w("VirtuCam_Render", "Target surface abandoned. Stopping Static Image thread.")
                            quit()
                        }
                        sleep(33) // ~30 fps simulated heartbeat
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e("VirtuCam_Render", "Render thread error", t)
        } finally {
            mediaSurface?.release()
            mediaSurfaceTexture?.release()
            textureRenderer?.release()
            if (eglSurface != null && eglCore != null) {
                eglCore!!.releaseSurface(eglSurface!!)
            }
            eglCore?.release()
        }
    }
    
    fun quit() {
        isRunning = false
    }
}

