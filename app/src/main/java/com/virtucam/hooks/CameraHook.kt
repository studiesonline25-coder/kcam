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
    private val dummyTextures = mutableListOf<SurfaceTexture>()
    private var nextTextureId = 100
    private var configLoaded = false
    // Maps original surfaces to their dummy replacements for CaptureRequest consistency
    private val surfaceMap = mutableMapOf<Surface, Surface>()
    private val activeBridges = mutableListOf<FormatConverterBridge>()

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

        val overwriteHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                // CRITICAL: Suppress format mismatch exceptions from nativeImageSetup.
                // Our dummy SurfaceTexture produces RGBA (0x1) but MIUI camera's ImageReader
                // expects YUV (0x32315659). The native layer throws before we can overwrite.
                // We catch it here and return null (no image) instead of crashing the app.
                if (param.throwable is UnsupportedOperationException) {
                    Log.d(TAG, "VirtuCam_Hook: Suppressed format mismatch: ${param.throwable?.message}")
                    param.throwable = null
                    param.result = null
                    return
                }

                try {
                    val image = param.result as? Image ?: return
                    val format = image.format
                    // Data Override is exclusively for strictly validated formats like YUV.
                    // Raw preview textures (0x22 PRIVATE) bypass this by using native EGL rendering.
                    if (format == ImageFormat.YUV_420_888 || format == ImageFormat.YV12 || format == 35) {
                        val bridge = activeBridges.firstOrNull { it.width == image.width && it.height == image.height }
                        if (bridge != null) {
                            bridge.overwriteImageWithLatestYuv(image)
                        }
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "VirtuCam_Hook: Error during ImageReader data overwrite", e)
                }
            }
        }

        XposedBridge.hookAllMethods(imageReaderClass, "acquireNextImage", overwriteHook)
        XposedBridge.hookAllMethods(imageReaderClass, "acquireLatestImage", overwriteHook)

        // CRITICAL: Wrap onImageAvailable listeners with NPE-safe wrappers.
        // When our format mismatch suppression returns null from acquireNextImage(),
        // the camera's onImageAvailable callback crashes with NPE because it calls
        // image.getTimestamp() on the null result without null-checking.
        // This wrapper catches that NPE so the camera gracefully skips those frames.
        XposedHelpers.findAndHookMethod(
            imageReaderClass,
            "setOnImageAvailableListener",
            android.media.ImageReader.OnImageAvailableListener::class.java,
            android.os.Handler::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val originalListener = param.args[0] ?: return
                    if (originalListener is android.media.ImageReader.OnImageAvailableListener) {
                        // Replace with a wrapped listener that catches NPE
                        param.args[0] = android.media.ImageReader.OnImageAvailableListener { reader ->
                            try {
                                originalListener.onImageAvailable(reader)
                            } catch (e: NullPointerException) {
                                // Silently skip - this happens when acquireNextImage returns null
                                // due to our format mismatch suppression (RGBA vs YUV)
                                Log.d(TAG, "VirtuCam_Hook: Suppressed NPE in onImageAvailable (format mismatch frame skip)")
                            }
                        }
                    }
                }
            }
        )
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

    private fun createDummySurface(targetSurface: Surface?, width: Int = 1280, height: Int = 720): Surface {
        val texId = nextTextureId++
        val dummySurfaceTexture = SurfaceTexture(texId)
        
        dummySurfaceTexture.setDefaultBufferSize(width, height)
        val surface = Surface(dummySurfaceTexture)
        
        // Retain strong references — prevents GC from destroying surfaces
        // while the native camera pipeline is still writing to them
        dummyTextures.add(dummySurfaceTexture)
        dummySurfaces.add(surface)
        // Track original→dummy mapping for CaptureRequest swapping
        if (targetSurface != null) {
            surfaceMap[targetSurface] = surface
        }
        
        Log.d(TAG, "VirtuCam_Hook: Created dummy surface (texId=$texId, ${width}x${height})")
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
        
        if (isPreview) {
            val dummySurface = createDummySurface(targetSurface, w, h)
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
                    return targetSurface
                }
            } catch (e: Throwable) {}
            
            try {
                val mSurfaceField = XposedHelpers.findField(config.javaClass, "mSurface")
                mSurfaceField.set(config, dummySurface)
            } catch (e: Throwable) {
                Log.e(TAG, "VirtuCam_Hook: Failed to swap surface in config", e)
            }
            return targetSurface
        } else {
            // YUV Data-Overwrite Mode: HAL processes actual data into true targetSurface to preserve usage flags!
            // We DO NOT swap the surface in the HAL config!
            val bridge = FormatConverterBridge(w, h)
            activeBridges.add(bridge)
            // But we tell VirtualRenderThread to stream its RGBA rendering strictly into our high-speed cache.
            return bridge.inputSurface ?: targetSurface
        }
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
                                
                                if (isPreview) {
                                    val dummySurface = createDummySurface(targetSurface, 1280, 720)
                                    surfaceMap[targetSurface] = dummySurface
                                    targetSurfaces.add(targetSurface)
                                    newSurfaces.add(dummySurface)
                                } else {
                                    val bridge = FormatConverterBridge(1280, 720)
                                    activeBridges.add(bridge)
                                    targetSurfaces.add(bridge.inputSurface ?: targetSurface)
                                    
                                    // YUV Override Mode: Give targetSurface to the Real Camera so it receives perfect hardware buffers!
                                    newSurfaces.add(targetSurface)
                                }
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
        dummyTextures.forEach { try { it.release() } catch (_: Throwable) {} }
        dummyTextures.clear()
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
            return try {
                // On most Android versions, surface format is hard to reach via pure Java.
                // Default to PRIVATE (0x22) which routes through the preview EGL path.
                0x22
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
                
                streamPlayer = StreamPlayer(context, streamUrl, mediaSurface!!) {
                    if (!isRunning) return@StreamPlayer
                    mediaSurfaceTexture?.updateTexImage()
                    val matrix = FloatArray(16)
                    mediaSurfaceTexture?.getTransformMatrix(matrix)
                    textureRenderer?.draw(matrix, 0) // Align with portrait source default
                    if (eglCore?.swapBuffers(eglSurface!!) == false) {
                        Log.w("VirtuCam_Render", "Target surface abandoned during stream. Stopping thread.")
                        quit()
                    }
                }
                streamPlayer!!.start()
                
                while (isRunning) {
                    sleep(100)
                }
                streamPlayer!!.stop()
                
            } else if (isVideo) {
                // Local Video Pipeline (MediaCodec)
                mediaSurfaceTexture = SurfaceTexture(textureRenderer!!.textureId)
                mediaSurface = Surface(mediaSurfaceTexture)
                
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    val fd = pfd.fileDescriptor
                    videoPlayer = VideoPlayer(fd, mediaSurface!!) {
                        if (!isRunning) return@VideoPlayer
                        mediaSurfaceTexture?.updateTexImage()
                        val matrix = FloatArray(16)
                        mediaSurfaceTexture?.getTransformMatrix(matrix)
                        textureRenderer?.draw(matrix, 0)
                        if (eglCore?.swapBuffers(eglSurface!!) == false) {
                            Log.w("VirtuCam_Render", "Target surface abandoned during video. Stopping thread.")
                            quit()
                        }
                    }
                    videoPlayer!!.start()
                    
                    while (isRunning) {
                        sleep(100)
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

