package com.virtucam.hooks

import android.app.AndroidAppHelper
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.PixelFormat
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
    
    private val isSourceLoading = ThreadLocal<Boolean>()

    @Volatile
    private var activeCameraId: String = "0"
    @Volatile
    var xiaomiRequestedOrientation: Int = -1
    
    internal val surfaceFormats = java.util.concurrent.ConcurrentHashMap<Surface, Int>()
    private val cameraOrientations = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val cameraFacings = java.util.concurrent.ConcurrentHashMap<String, Int>() 
    
    @Volatile var latestVirtualJpeg: ByteArray? = null
    @Volatile var latestVirtualJpegArea: Int = 0
    
    @Volatile var captureCount = 0
    val captureQueue = java.util.concurrent.LinkedBlockingQueue<Pair<Long, Int>>()
    val formatBridges = java.util.concurrent.ConcurrentHashMap<android.util.Size, FormatConverterBridge>()
    
    private val surfaceSizes = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Surface, Pair<Int, Int>>())
    private val surfaceTextureSizes = java.util.Collections.synchronizedMap(java.util.WeakHashMap<SurfaceTexture, Pair<Int, Int>>())

    @Volatile var zoomFactor: Float = 1.0f
    @Volatile var rtspUseTcp: Boolean = true
    @Volatile
    var isColorSwapped: Boolean = false

    @Volatile
    var isLivenessEnabled: Boolean = true

    @Volatile
    var isGeneratingJpeg: Boolean = false

    @Volatile
    var isBufferCaptureEnabled: Boolean = true

    @Volatile
    var compensationFactor: Float = 1.0f
    
    @Volatile
    var lastRequestedOrientation: Int = -1 

    /**
     * Fresh [CameraCharacteristics.SENSOR_ORIENTATION] for [activeCameraId].
     */
    fun resolveSensorOrientationDeg(): Int {
        val id = activeCameraId
        try {
            val app = AndroidAppHelper.currentApplication()
                ?: return normalizeOrientationDeg(cameraOrientations[id] ?: 90)
            val mgr = app.getSystemService(android.content.Context.CAMERA_SERVICE)
                as android.hardware.camera2.CameraManager
            val chars = mgr.getCameraCharacteristics(id)
            val o = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val normalized = normalizeOrientationDeg(o)
            cameraOrientations[id] = normalized
            return normalized
        } catch (_: Throwable) {
            val cached = cameraOrientations[id]
            if (cached != null) return normalizeOrientationDeg(cached)
            return 90
        }
    }

    private fun normalizeOrientationDeg(deg: Int): Int = ((deg % 360) + 360) % 360

    fun trackedSurfaceSize(surface: Surface): Pair<Int, Int>? = surfaceSizes[surface]

    private val captureSurfaces = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Surface, Boolean>())

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            targetPackage = lpparam.packageName
            Log.d(TAG, "VirtuCam_Hook: Initializing hooks for $targetPackage")

            hookCameraManager(lpparam)
            hookImageReader(lpparam)
            hookCaptureRequest(lpparam)
            hookSubmitCaptureRequest(lpparam)
            hookCameraError(lpparam)
            hookCameraDevice(lpparam)
            hookCameraDeviceOutputConfigurations(lpparam)
            hookCaptureCallback(lpparam)
            hookXiaomiBypass(lpparam)
            hookLazyClasses(lpparam) 
            hookFileOutputStream(lpparam)
            hookContentResolver(lpparam)
            hookContextWrapper(lpparam)
            
            Log.d(TAG, "VirtuCam_Hook: All hooks deployed successfully.")
        } catch (t: Throwable) {
            Log.e(TAG, "VirtuCam_Hook: CRITICAL failure in $targetPackage", t)
        }
    }

    private fun hookContextWrapper(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedBridge.hookAllMethods(android.content.ContextWrapper::class.java, "attachBaseContext", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val ctx = param.thisObject as? android.content.Context ?: return
                        applyDeferredHooksToClassLoader(ctx.classLoader)
                    } catch (_: Throwable) {}
                }
            })
        } catch (_: Throwable) {}
    }

    private var isStorageHooked = false
    private var isPtdHooked = false
    private var isZipperHooked = false
    private var isAlgoHooked = false
    private val searchedClassLoaders = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<ClassLoader, Boolean>())

    private fun hookLazyClasses(lpparam: XC_LoadPackage.LoadPackageParam) {
        applyDeferredHooksToClassLoader(lpparam.classLoader)
    }

    internal fun applyDeferredHooksToClassLoader(classLoader: ClassLoader?) {
        if (classLoader == null || !isEnabled) return
        if (isStorageHooked && isPtdHooked && isAlgoHooked) return
        if (!searchedClassLoaders.add(classLoader)) return
        
        Log.e("DIAGNOSTIC_VIRTUCAM", "Searching new ClassLoader for Xiaomi classes: ${classLoader.javaClass.simpleName}")

        if (!isStorageHooked) {
            try {
                val storageClass = XposedHelpers.findClassIfExists("com.android.camera.storage.Storage", classLoader)
                if (storageClass != null) {
                    isStorageHooked = true
                    applyStorageHooks(storageClass)
                }
            } catch (t: Throwable) {}
        }
    }

    private fun applyStorageHooks(storageClass: Class<*>) {
        val replaceImageHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    if (!isEnabled) return
                    val virtualJpeg = latestVirtualJpeg ?: return
                    var swapped = false
                    for (i in param.args.indices) {
                        val arg = param.args[i]
                        if (arg is ByteArray && arg.size > 100000) {
                            param.args[i] = virtualJpeg
                            swapped = true
                        }
                    }
                    if (swapped) {
                        Log.w(TAG, "VirtuCam_Storage: Payload swapped in ${param.method.name}()!")
                        latestVirtualJpeg = null
                    }
                } catch (_: Throwable) {}
            }
        }
        XposedBridge.hookAllMethods(storageClass, "addImage", replaceImageHook)
        XposedBridge.hookAllMethods(storageClass, "updateImage", replaceImageHook)
    }

    private fun hookFileOutputStream(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookConstructor("java.io.FileOutputStream", lpparam.classLoader, java.io.File::class.java, Boolean::class.javaPrimitiveType, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!isEnabled) return
                val file = param.args[0] as? java.io.File ?: return
                XposedHelpers.setAdditionalInstanceField(param.thisObject, "vcPath", file.absolutePath)
            }
        })
        
        XposedHelpers.findAndHookMethod("java.io.FileOutputStream", lpparam.classLoader, "write", ByteArray::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!isEnabled) return
                val virtualJpeg = latestVirtualJpeg ?: return
                val data = param.args[0] as? ByteArray ?: return
                val path = XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcPath") as? String ?: ""
                if (data.size > 100000 && path.endsWith(".jpg")) { 
                     param.args[0] = virtualJpeg
                     Log.w(TAG, "VirtuCam_Storage: FileOutputStream.write() SWAPPED! path=$path")
                }
            }
        })
    }

    private fun hookContentResolver(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.hookAllMethods(android.content.ContentResolver::class.java, "insert", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!isEnabled) return
                val resultUri = param.result as? android.net.Uri ?: return
                if (!resultUri.toString().contains("images")) return
                
                val values = param.args.firstOrNull { it is android.content.ContentValues } as? android.content.ContentValues
                val displayName = values?.getAsString("_display_name")
                
                if (displayName != null && displayName.endsWith(".jpg")) {
                    val capturedUri = resultUri
                    Thread {
                        try {
                            var jpegData: ByteArray? = null
                            for (i in 0 until 50) { 
                                jpegData = latestVirtualJpeg
                                if (jpegData != null) break
                                Thread.sleep(50)
                            }
                            if (jpegData != null) {
                                val context = AndroidAppHelper.currentApplication()
                                context?.contentResolver?.openOutputStream(capturedUri)?.use { it.write(jpegData) }
                                Log.e("DIAGNOSTIC_VIRTUCAM", "URI DIRECT WRITE SUCCESS: $capturedUri")
                                latestVirtualJpeg = null
                            }
                        } catch (_: Exception) {}
                    }.start()
                }
            }
        })
    }

    private fun hookCameraDevice(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cameraDeviceClass = XposedHelpers.findClassIfExists("android.hardware.camera2.impl.CameraDeviceImpl", lpparam.classLoader) ?: return
        XposedHelpers.findAndHookMethod(cameraDeviceClass, "createCaptureSession", List::class.java, android.hardware.camera2.CameraCaptureSession.StateCallback::class.java, Handler::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!isEnabled) return
                val outputs = param.args[0] as? MutableList<Surface> ?: return
                val filteredOutputs = mutableListOf<Surface>()
                for (surface in outputs) {
                    val size = trackedSurfaceSize(surface)
                    if (size != null) {
                        val bridge = FormatConverterBridge(size.first, size.second, surface, sensorOrientation = resolveSensorOrientationDeg(), isColorSwapped = isColorSwapped)
                        activeBridges.add(bridge)
                        surfaceMap[surface] = bridge.inputSurface!!
                        filteredOutputs.add(bridge.inputSurface!!)
                        Log.d(TAG, "VirtuCam_Hook: Substituted surface ${size.first}x${size.second}")
                    } else filteredOutputs.add(surface)
                }
                param.args[0] = filteredOutputs
            }
        })
    }

    private fun hookCameraDeviceOutputConfigurations(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cameraDeviceClass = XposedHelpers.findClassIfExists("android.hardware.camera2.impl.CameraDeviceImpl", lpparam.classLoader) ?: return
        try {
            XposedBridge.hookAllMethods(cameraDeviceClass, "createCaptureSessionByOutputConfigurations", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isEnabled) return
                    val configs = param.args[0] as? List<*> ?: return
                    for (config in configs) {
                        if (config == null) continue
                        val surface = XposedHelpers.callMethod(config, "getSurface") as? Surface ?: continue
                        val size = trackedSurfaceSize(surface)
                        if (size != null) {
                            val bridge = FormatConverterBridge(size.first, size.second, surface, sensorOrientation = resolveSensorOrientationDeg(), isColorSwapped = isColorSwapped)
                            activeBridges.add(bridge)
                            surfaceMap[surface] = bridge.inputSurface!!
                            XposedHelpers.callMethod(config, "addSurface", bridge.inputSurface)
                            Log.d(TAG, "VirtuCam_Hook: Added Virtual Surface to OutputConfiguration ${size.first}x${size.second}")
                        }
                    }
                }
            })
        } catch (_: Throwable) {}
    }

    private fun hookCaptureRequest(lpparam: XC_LoadPackage.LoadPackageParam) {
        val builderClass = XposedHelpers.findClassIfExists("android.hardware.camera2.CaptureRequest\$Builder", lpparam.classLoader) ?: return
        XposedHelpers.findAndHookMethod(builderClass, "addTarget", Surface::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!isEnabled) return
                val surface = param.args[0] as? Surface ?: return
                val mapped = surfaceMap[surface]
                if (mapped != null) param.args[0] = mapped
            }
        })
    }

    private fun hookSubmitCaptureRequest(lpparam: XC_LoadPackage.LoadPackageParam) {
        val sessionClass = XposedHelpers.findClassIfExists("android.hardware.camera2.impl.CameraCaptureSessionImpl", lpparam.classLoader) ?: return
        XposedBridge.hookAllMethods(sessionClass, "setRepeatingRequest", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!isEnabled) return
                val request = param.args[0] as? android.hardware.camera2.CaptureRequest ?: return
                if (isLivenessEnabled) jitterCaptureRequest(request)
            }
        })
    }

    private fun jitterCaptureRequest(request: android.hardware.camera2.CaptureRequest) {
        try {
            val focusJitter = (Math.random() * 0.05).toFloat() - 0.025f
            setCaptureRequestField(request, android.hardware.camera2.CaptureRequest.LENS_FOCUS_DISTANCE, 0.5f + focusJitter)
            val expJitter = (Math.random() * 500000).toLong() - 250000
            setCaptureRequestField(request, android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME, 33333333L + expJitter)
        } catch (_: Throwable) {}
    }

    private fun setCaptureRequestField(request: android.hardware.camera2.CaptureRequest, key: Any, value: Any) {
        try {
            val mSettings = XposedHelpers.getObjectField(request, "mSettings")
            XposedHelpers.callMethod(mSettings, "set", key, value)
        } catch (_: Throwable) {}
    }

    private fun hookCaptureCallback(lpparam: XC_LoadPackage.LoadPackageParam) {
        val callbackClass = XposedHelpers.findClassIfExists("android.hardware.camera2.CameraCaptureSession\$CaptureCallback", lpparam.classLoader) ?: return
        XposedBridge.hookAllMethods(callbackClass, "onCaptureCompleted", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!isEnabled) return
                val result = param.args[2] as? android.hardware.camera2.TotalCaptureResult ?: return
                val ts = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) ?: return
                activeBridges.forEach { it.pushLatestFrameToWriter(ts) }
            }
        })
    }

    private fun hookCameraManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        val mgrClass = XposedHelpers.findClassIfExists("android.hardware.camera2.CameraManager", lpparam.classLoader) ?: return
        XposedHelpers.findAndHookMethod(mgrClass, "openCamera", String::class.java, android.hardware.camera2.CameraDevice.StateCallback::class.java, Handler::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                activeCameraId = param.args[0] as String
                try {
                    val mgr = param.thisObject as android.hardware.camera2.CameraManager
                    val chars = mgr.getCameraCharacteristics(activeCameraId)
                    cameraOrientations[activeCameraId] = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                } catch (_: Throwable) {}
            }
        })
    }

    private fun hookImageReader(lpparam: XC_LoadPackage.LoadPackageParam) {
        val irClass = XposedHelpers.findClassIfExists("android.media.ImageReader", lpparam.classLoader) ?: return
        XposedHelpers.findAndHookMethod(irClass, "getSurface", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val reader = param.thisObject as ImageReader
                val s = param.result as Surface
                surfaceSizes[s] = Pair(reader.width, reader.height)
                surfaceFormats[s] = reader.imageFormat
            }
        })
    }

    private fun hookXiaomiBypass(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (android.os.Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) {
            val zipperClass = XposedHelpers.findClassIfExists("com.xiaomi.camera.ParallelDataZipper", lpparam.classLoader)
            if (zipperClass != null) {
                XposedBridge.hookAllMethods(zipperClass, "join", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) { param.result = null }
                })
            }
            val requestClass = XposedHelpers.findClassIfExists("android.hardware.camera2.CaptureRequest", lpparam.classLoader)
            if (requestClass != null) {
                XposedHelpers.findAndHookMethod(requestClass, "setVendorTagInternal", Int::class.javaPrimitiveType, Any::class.java, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) { param.result = null }
                })
            }
        }
    }

    private fun hookCameraError(lpparam: XC_LoadPackage.LoadPackageParam) {
        val deviceClass = XposedHelpers.findClassIfExists("android.hardware.camera2.impl.CameraDeviceImpl", lpparam.classLoader) ?: return
        XposedHelpers.findAndHookMethod(deviceClass, "checkAndThrowExceptionIfError", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) { param.result = null }
        })
    }
}
