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
    @Volatile
    var isMirrored: Boolean = false
    
    @Volatile
    var isVideo: Boolean = false
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
    
    // Global state for MediaStore hijacking
    @Volatile var pendingCaptureUri: Uri? = null
    @Volatile var pendingCaptureResolver: android.content.ContentResolver? = null
    
    // global capture state for render threads
    @Volatile var captureCount = 0
    val captureQueue = java.util.concurrent.LinkedBlockingQueue<Pair<Long, Int>>()
    val formatBridges = java.util.concurrent.ConcurrentHashMap<android.util.Size, FormatConverterBridge>()
    
    // Global telemetry for surface dimensions
    private val surfaceSizes = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Surface, Pair<Int, Int>>())
    private val surfaceTextureSizes = java.util.Collections.synchronizedMap(java.util.WeakHashMap<SurfaceTexture, Pair<Int, Int>>())

    // Dynamic Xiaomi Vendor Tag Discovery
    private val discoveredXiaomiKeys = java.util.concurrent.ConcurrentHashMap<String, android.hardware.camera2.CaptureRequest.Key<*>>()

    // [ONCE AND FOR ALL] Surface tracking and crash prevention structures
    private val surfaceFormats = Collections.synchronizedMap(WeakHashMap<Surface, Int>())
    private val hookedListenerClasses = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Class<*>, Boolean>())
    private val captureSurfaces = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Surface, Boolean>())

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
            hookSubmitCaptureRequest(lpparam)
            hookCameraError(lpparam)
            hookCaptureSurfaces(lpparam)
            hookCameraDevice(lpparam)
            hookCameraDeviceOutputConfigurations(lpparam)
            hookCamera1(lpparam)
            hookCaptureCallback(lpparam)
            hookXiaomiBypass(lpparam)
            hookContentResolver(lpparam)
            
            Log.d(TAG, "VirtuCam_Hook: All hooks deployed successfully.")
        } catch (t: Throwable) {
            Log.e(TAG, "VirtuCam_Hook: CRITICAL failure in $targetPackage", t)
        }
    }

    /**
     * Intercepts MediaStore insertions by the Camera app. When it creates a PENDING image,
     * we capture the URI. This allows our virtual bridge to bypass broken algorithm engines
     * by injecting the JPEG directly into the MediaStore and instantly unlocking it.
     */
    private fun hookContentResolver(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // 1. Hook 'insert' to capture the URI
            XposedBridge.hookAllMethods(
                android.content.ContentResolver::class.java,
                "insert",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            if (!isEnabled) return
                            val uri = param.args[0] as? Uri ?: return
                            val resultUri = param.result as? Uri ?: return
                            
                            if (uri.toString().contains("media/external/images/media")) {
                                val values = param.args.getOrNull(1) as? android.content.ContentValues
                                val isPending = values?.getAsInteger("is_pending")
                                
                                // Xiaomi bypasses standard is_pending=1 logic, so we capture the latest URI regardless!
                                Log.d(TAG, "VirtuCam_MediaStore: Captured MediaStore URI: $resultUri (isPending: $isPending)")
                                pendingCaptureUri = resultUri
                                pendingCaptureResolver = param.thisObject as? android.content.ContentResolver
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "VirtuCam_MediaStore: ContentResolver insert hook error", t)
                        }
                    }
                }
            )

            // 2. Hook 'delete' to prevent the Camera app from cleaning up our injected photo 
            // when its own internal MIVI/AlgoEngine pipeline fails and triggers a rollback.
            XposedBridge.hookAllMethods(
                android.content.ContentResolver::class.java,
                "delete",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (!isEnabled) return
                            val uri = param.args[0] as? Uri ?: return
                            
                            val protectedUri = pendingCaptureUri
                            if (protectedUri != null && uri == protectedUri) {
                                Log.w(TAG, "VirtuCam_MediaStore: BLOCKED attempt by Camera app to delete injected photo: $uri")
                                param.result = 0 // Return 0 rows deleted, successfully spoofing a no-op
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "VirtuCam_MediaStore: ContentResolver delete hook error", t)
                        }
                    }
                }
            )

            Log.d(TAG, "VirtuCam_Hook: ContentResolver hooks active (Insert & Delete protected)")
        } catch (t: Throwable) {
            Log.e(TAG, "VirtuCam_Hook: Failed to hook ContentResolver", t)
        }
    }

    /**
     * Hook CameraCaptureSession.capture() and setRepeatingRequest() to intercept
     * the CaptureCallback and identify the exact moment a photo is taken.
     */
    private fun hookCaptureCallback(lpparam: XC_LoadPackage.LoadPackageParam) {
        val sessionClass = XposedHelpers.findClassIfExists(
            "android.hardware.camera2.impl.CameraCaptureSessionImpl", lpparam.classLoader
        ) ?: return

        val callbackHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val callbackIndex = if (param.method.name == "capture") 1 else 1
                    val originalCallback = if (param.args.size > callbackIndex) param.args[callbackIndex] as? android.hardware.camera2.CameraCaptureSession.CaptureCallback else null
                    
                    if (originalCallback == null) return
                    
                    // Wrap the original callback to intercept onCaptureCompleted
                    val wrappedCallback = object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureStarted(session: android.hardware.camera2.CameraCaptureSession, request: android.hardware.camera2.CaptureRequest, timestamp: Long, frameNumber: Long) {
                            originalCallback.onCaptureStarted(session, request, timestamp, frameNumber)
                        }
                        
                        override fun onCaptureCompleted(session: android.hardware.camera2.CameraCaptureSession, request: android.hardware.camera2.CaptureRequest, result: android.hardware.camera2.TotalCaptureResult) {
                            try {
                                val isSingleCapture = !request.tag.toString().contains("Repeating") // Heuristic
                                // On Xiaomi, repeating requests (preview) should be ignored for captureCount
                                if (param.method.name == "capture") {
                                    val sensorTimestamp = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) ?: System.nanoTime()
                                    synchronized(CameraHook) {
                                        captureCount++
                                        captureQueue.offer(Pair(sensorTimestamp, captureCount))
                                        Log.d(TAG, "VirtuCam_Hook: Capture Event Detected! TS=$sensorTimestamp, QueueSize=${captureQueue.size}")
                                    }
                                }
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

        XposedBridge.hookAllMethods(sessionClass, "capture", callbackHook)
        XposedBridge.hookAllMethods(sessionClass, "captureBurst", callbackHook)
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

    private fun hookCaptureSurfaces(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val mediaCodecClass = XposedHelpers.findClassIfExists("android.media.MediaCodec", lpparam.classLoader)
            if (mediaCodecClass != null) {
                XposedBridge.hookAllMethods(mediaCodecClass, "createInputSurface", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val s = param.result as? Surface ?: return
                        captureSurfaces.add(s)
                        Log.d(TAG, "VirtuCam_Hook: Tracked MediaCodec Capture Surface")
                    }
                })
            }
            
            val mediaRecorderClass = XposedHelpers.findClassIfExists("android.media.MediaRecorder", lpparam.classLoader)
            if (mediaRecorderClass != null) {
                XposedBridge.hookAllMethods(mediaRecorderClass, "getSurface", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val s = param.result as? Surface ?: return
                        captureSurfaces.add(s)
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
        XposedBridge.hookAllMethods(managerClass, "openCamera", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val cameraId = param.args[0] as? String
                if (cameraId != null) {
                    lastOpenedCameraId = cameraId
                    
                    // Trigger Discovery: Find all Xiaomi vendor tags supported by this specific camera sensor
                    try {
                        val manager = param.thisObject as android.hardware.camera2.CameraManager
                        val characteristics = manager.getCameraCharacteristics(cameraId)
                        discoverXiaomiVendorTags(characteristics)
                    } catch (_: Exception) {}
                }
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
     * [Xiaomi Parallel Bypass] Force legacy synchronous capture path by disabling 
     * proprietary background processing features that reject virtual buffers.
     */
    private fun hookXiaomiBypass(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!android.os.Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) return

        try {
            val builderClass = XposedHelpers.findClassIfExists(
                "android.hardware.camera2.CaptureRequest\$Builder", lpparam.classLoader
            ) ?: return

            XposedBridge.hookAllMethods(builderClass, "build", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (!isEnabled) return
                        val builder = param.thisObject ?: return
                        
                        // [Redmi 14C Fix] Apply Xiaomi Capture Bypass (forces 'Simple' capture path)
                        // By disabling the Parallel Engine (MiAlgo/MIVI), we prevent it from rejecting virtual buffers.
                        val template = try { XposedHelpers.getIntField(builder, "mTemplate") } catch (e: Exception) { -1 }
                        
                        // 1. DYNAMIC SUPPRESSION: Automatically disable all discovered vendor tags related to post-processing
                        for ((name, key) in discoveredXiaomiKeys) {
                            try {
                                if (name.contains("parallel.enabled", true) || 
                                    name.contains("mivi.enabled", true) || 
                                    name.contains("algoengine.enabled", true) ||
                                    name.contains("hdr.enabled", true) ||
                                    name.contains("mfnr.enabled", true) ||
                                    name.contains("snapshot.optimize", true) ||
                                    name.contains("super.pixel.enabled", true)) {
                                    
                                    // Set to false or 0 based on reasonable guess (setXiaomiVendorTag handles the .set() call)
                                    setXiaomiVendorTag(builder, name, false)
                                    setXiaomiVendorTag(builder, name, 0.toByte()) 
                                } else if (name == "xiaomi.capturepipeline.simple") {
                                    setXiaomiVendorTag(builder, name, 1.toByte())
                                }
                            } catch (_: Exception) {}
                        }

                        // 2. HARDCODED OVERRIDES: Ensure critical known tags are set (even if not discovered in availableKeys)
                        setXiaomiVendorTag(builder, "xiaomi.parallel.enabled", 0.toByte())
                        setXiaomiVendorTag(builder, "xiaomi.mivi.enabled", false)
                        setXiaomiVendorTag(builder, "xiaomi.algoengine.enabled", 0.toByte())

                        if (template != 2 && template != 5) return // Early exit for preview if it's not a capture request
                        
                        Log.d(TAG, "XiaomiBypass: Applied dynamic suppression to template $template")
                        
                        // Disable multi-frame and post-processing dependencies
                        setXiaomiVendorTag(builder, "xiaomi.mfnr.enabled", 0.toByte())
                        setXiaomiVendorTag(builder, "xiaomi.hdr.enabled", 0.toByte())
                        setXiaomiVendorTag(builder, "xiaomi.multiframe.inputNum", 1)
                        setXiaomiVendorTag(builder, "xiaomi.snapshot.optimize.enabled", 0.toByte())
                        setXiaomiVendorTag(builder, "xiaomi.mivi.super.pixel.enabled", false)
                        setXiaomiVendorTag(builder, "xiaomi.mivi.super.night.enabled", false)
                        setXiaomiVendorTag(builder, "xiaomi.capturepipeline.simple", 1.toByte())
                        setXiaomiVendorTag(builder, "xiaomi.sat.enabled", 0.toByte())
                        
                    } catch (_: Throwable) {}
                }
            })
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
            
            // Use reflection for the .set() call to avoid Builder<T> vs Builder compile issues
            XposedHelpers.callMethod(builder, "set", key, value)
            
            // Helpful logging to verify which tags were accepted by the HAL
            Log.v(TAG, "XiaomiBypass: Set $name (reflected) success")
        } catch (_: Throwable) {
            Log.v(TAG, "XiaomiBypass: Tag $name not supported on this device")
        }
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

        XposedBridge.hookAllMethods(deviceImplClass, "submitCaptureRequest", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!isEnabled || surfaceMap.isEmpty()) return

                try {
                    // First argument is List<CaptureRequest>
                    val requestList = param.args[0] as? List<*> ?: return

                    for (reqObj in requestList) {
                        if (reqObj == null) continue
                        val reqClass = reqObj.javaClass
                        
                        // CaptureRequest internally stores surfaces in `mSurfaceSet` (or `mTargetSurfaces` in older versions)
                        var surfaceSetField = XposedHelpers.findFieldIfExists(reqClass, "mSurfaceSet")
                        if (surfaceSetField == null) {
                            surfaceSetField = XposedHelpers.findFieldIfExists(reqClass, "mTargetSurfaces")
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
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "VirtuCam_Hook: Error mutating immutable CaptureRequest in submitCaptureRequest", t)
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

        XposedHelpers.findAndHookMethod(imageReaderClass, "getSurface", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val reader = param.thisObject as? ImageReader ?: return
                val surface = param.result as? Surface ?: return
                val format = XposedHelpers.callMethod(reader, "getImageFormat") as? Int ?: return
                val w = XposedHelpers.callMethod(reader, "getWidth") as? Int ?: 0
                val h = XposedHelpers.callMethod(reader, "getHeight") as? Int ?: 0
                
                surfaceFormats[surface] = format
                captureSurfaces.add(surface)
                if (w > 0 && h > 0) {
                    surfaceSizes[surface] = Pair(w, h)
                    Log.d(TAG, "VirtuCam_Hook: Tracked ImageReader surface ${w}x${h} format=$format")
                }
            }
        })

        // Hook SurfaceTexture for Preview size tracking
        try {
            XposedHelpers.findAndHookMethod(
                "android.graphics.SurfaceTexture",
                lpparam.classLoader,
                "setDefaultBufferSize",
                Int::class.java, Int::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val st = param.thisObject as? SurfaceTexture ?: return
                        val w = param.args[0] as Int
                        val h = param.args[1] as Int
                        surfaceTextureSizes[st] = Pair(w, h)
                    }
                }
            )

            // Associate SurfaceTexture with Surface for later lookup
            XposedHelpers.findAndHookConstructor(
                "android.view.Surface",
                lpparam.classLoader,
                SurfaceTexture::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val s = param.thisObject as? Surface ?: return
                        val st = param.args[0] as? SurfaceTexture ?: return
                        val size = surfaceTextureSizes[st]
                        if (size != null) {
                            surfaceSizes[s] = size
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

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
                                bridge.overwriteImageWithLatestYuv(image, image.timestamp)
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
                                bridge.overwriteImageWithLatestYuv(image, image.timestamp)
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
                    startRenderThreads(listOf(Pair(Surface(st), false)))
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
                    startRenderThreads(listOf(Pair(surface, false)))
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
        
        Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: Creating Dummy Sink Surface. Width=$width, Height=$height, Format=$originalFormat")

        // [STABILITY FIX] Use maxImages=5 to give the Camera HAL plenty of breathing room during bursts. 
        // A value of 1 crashes the HAL if our encoder blocks the queue even for a few milliseconds (Error 4).
        val reader = try {
            ImageReader.newInstance(width, height, originalFormat, 5)
        } catch (e: Exception) {
            Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: Failed to create ImageReader with $width x $height format $originalFormat! Trying YUV fallback...", e)
            ImageReader.newInstance(width, height, android.graphics.ImageFormat.YUV_420_888, 5)
        }
        
        reader.setOnImageAvailableListener({ ir ->
            try {
                // Instantly consume and discard the image to keep the pipeline flowing
                val realImage = ir.acquireNextImage()
                val realTimestamp = realImage?.timestamp ?: 0L
                realImage?.close()
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
        val isPreview = (format == 0x22 || format == 0x1)
        
        Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: Swapping OutputConfig Surface. Size=${w}x${h}, Format=$format, isPreview=$isPreview")
        
        val bridge = if (!isPreview) {
            Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: Creating FormatConverterBridge for $w x $h (Format $format)")
            val b = FormatConverterBridge(w, h, targetSurface, format)
            activeBridges.add(b)
            formatBridges[android.util.Size(w, h)] = b
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

    @Volatile
    var compensationFactor: Float = 1.0f

    /**
     * Poll configuration via ContentProvider
     */
    fun loadConfiguration() {
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
                        compensationFactor = if (it.columnCount > 6) it.getFloat(6) else 1.0f
                        isMirrored = if (it.columnCount > 7) it.getInt(7) == 1 else false
                        
                        Log.d(TAG, "VirtuCam_Hook: Config loaded. Enabled: $isEnabled, Factor: $compensationFactor, Mirrored: $isMirrored")
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
                            val targetSurfaces = ArrayList<Pair<Surface, Boolean>>()
                            
                            for (targetSurface in surfacesList) {
                                val size = SurfaceUtils.getSurfaceSize(targetSurface)
                                val w = size.first
                                val h = size.second
                                
                                val isCapture = captureSurfaces.contains(targetSurface)
                                val format = SurfaceUtils.getSurfaceFormat(targetSurface)
                                val isPreview = (format == 0x22 || format == 0x1)
                                
                                val bridge = if (!isPreview) {
                                    val b = FormatConverterBridge(w, h, targetSurface, format)
                                    activeBridges.add(b)
                                    formatBridges[android.util.Size(w, h)] = b
                                    targetSurfaces.add(Pair(b.inputSurface ?: targetSurface, isCapture))
                                    b
                                } else {
                                    targetSurfaces.add(Pair(targetSurface, isCapture))
                                    null
                                }
                                
                                val dummySurface = createDummySurface(targetSurface, w, h, bridge)
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
                            
                            val targetSurfaces = ArrayList<Pair<Surface, Boolean>>()
                            
                            for (config in configs) {
                                if (config == null) continue
                                
                                val getSurfaceMethod = config.javaClass.getMethod("getSurface")
                                val targetSurface = getSurfaceMethod.invoke(config) as? Surface
                                
                                if (targetSurface != null) {
                                    val isCapture = captureSurfaces.contains(targetSurface)
                                    val resolvedSurface = swapSurfaceInOutputConfig(config, targetSurface)
                                    targetSurfaces.add(Pair(resolvedSurface, isCapture))
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
                                
                                val targetSurfaces = ArrayList<Pair<Surface, Boolean>>()
                                
                                for (config in configs) {
                                    if (config == null) continue
                                    val getSurfaceMethod = config.javaClass.getMethod("getSurface")
                                    val targetSurface = getSurfaceMethod.invoke(config) as? Surface
                                    
                                    if (targetSurface != null) {
                                        val isCapture = captureSurfaces.contains(targetSurface)
                                        val resolvedSurface = swapSurfaceInOutputConfig(config, targetSurface)
                                        targetSurfaces.add(Pair(resolvedSurface, isCapture))
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
        formatBridges.clear()
    }

    private fun startRenderThreads(targetSurfaces: List<Pair<Surface, Boolean>>) {
        val context = AndroidAppHelper.currentApplication() ?: return
        
        var sensorOrientation = 0
        try {
            val cameraManager = context.getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val characteristics = cameraManager.getCameraCharacteristics(lastOpenedCameraId ?: "0")
            sensorOrientation = characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            Log.d(TAG, "VirtuCam_Hook: Using SENSOR_ORIENTATION $sensorOrientation for camera $lastOpenedCameraId")
        } catch (e: Exception) {
            Log.e(TAG, "VirtuCam_Hook: Failed to read SENSOR_ORIENTATION", e)
        }
        
        if (targetSurfaces.isNotEmpty()) {
            try {
                // [Sync Fix] Start ONE master thread for ALL surfaces to ensure sync and save CPU
                val thread = VirtualRenderThread(targetSurfaces, context, isVideo, isStream, streamUrl, sensorOrientation)
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
                // Heuristic: If we don't know the format, we try to guess if it's a preview surface.
                // Preview surfaces usually don't have ImageReader metadata attached.
                0x22 // Default to PRIVATE (Hijack-able)
            } catch (e: Exception) {
                0x22
            }
        }

        fun getSurfaceSize(surface: Surface): Pair<Int, Int> {
            // Priority 1: Our robust telemetry map
            val tracked = surfaceSizes[surface]
            if (tracked != null) return tracked

            // Priority 2: Generic fallback (Legacy)
            return Pair(1280, 720)
        }
    }
}

/**
 * Dedicated thread targeting the hijacked surfaces via EGL
 */
class VirtualRenderThread(
    private val targetSurfaces: List<Pair<Surface, Boolean>>,
    private val context: android.content.Context,
    private val isVideo: Boolean,
    private val isStream: Boolean,
    private val streamUrl: String,
    private val sensorOrientation: Int = 0
) : Thread("VirtuCam-RenderThread") {
    
    @Volatile
    private var isRunning = true
    
    private var eglCore: EglCore? = null
    private val eglSurfaceTargets = mutableListOf<Pair<android.opengl.EGLSurface, Boolean>>()
    private var textureRenderer: TextureRenderer? = null
    
    private var videoPlayer: VideoPlayer? = null
    private var streamPlayer: StreamPlayer? = null
    
    private var mediaSurfaceTexture: SurfaceTexture? = null
    private var mediaSurface: Surface? = null

    private fun getTargetRatio(vW: Int, vH: Int, isCapture: Boolean): Float {
        return try {
            if (isCapture) {
                // Captured Output (Photos/Videos) do NOT get blindly stretched by the OEM UI.
                // We must return true geometrical ratio to prevent JPEG/YUV output from being heavily squished.
                vW.toFloat() / vH.toFloat()
            } else {
                // Most modern camera apps stretch 4:3 Preview buffers to exactly a 16:9 UI viewfinder.
                (9f / 16f) * CameraHook.compensationFactor
            }
        } catch (e: Exception) {
            vW.toFloat() / vH.toFloat()
        }
    }

    override fun run() {
        try {
            eglCore = EglCore()
            
            // Create EGL surfaces for ALL targets
            for (targetPair in targetSurfaces) {
                try {
                    val surface = targetPair.first
                    if (!surface.isValid) continue
                    
                    // Add a tiny retry for "already connected" race conditions
                    var es: android.opengl.EGLSurface? = null
                    for (i in 0..2) {
                        try {
                            es = eglCore!!.createWindowSurface(surface)
                            if (es != null) break
                        } catch (e: Exception) {
                            if (i == 2) throw e
                            Thread.sleep(50)
                        }
                    }
                    
                    if (es != null) {
                        eglSurfaceTargets.add(Pair(es, targetPair.second))
                        Log.d("VirtuCam_Render", "Created EGL surface for target: ${targetPair.first}")
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
            
            textureRenderer = TextureRenderer(isVideo)
            textureRenderer!!.init()
            
            val uri = Uri.parse("content://com.virtucam.provider/file")
            
            if (isStream) {
                // Live Stream Pipeline (ExoPlayer)
                mediaSurfaceTexture = SurfaceTexture(textureRenderer!!.textureId)
                mediaSurface = Surface(mediaSurfaceTexture)
                val hasNewFrame = java.util.concurrent.atomic.AtomicBoolean(false)
                mediaSurfaceTexture?.setOnFrameAvailableListener { hasNewFrame.set(true) }
                
                streamPlayer = StreamPlayer(context, streamUrl, mediaSurface!!) {}
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
                val stream = context.contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(stream)
                stream?.close()
                
                if (bitmap != null) {
                    textureRenderer!!.loadBitmap(bitmap)
                    val staticImageW = bitmap.width
                    val staticImageH = bitmap.height
                    bitmap.recycle()
                    
                    val matrix = FloatArray(16)
                    Matrix.setIdentityM(matrix, 0)
                    
                    while (isRunning) {
                        if (!drawToAllSurfaces(matrix, staticImageW, staticImageH)) break
                        
                        // Handle Photo/Capture Requests (Static Image)
                        synchronized(CameraHook) {
                            while (CameraHook.captureCount > 0) {
                                val capture = CameraHook.captureQueue.poll()
                                val timestamp = capture?.first ?: System.nanoTime()
                                CameraHook.formatBridges.values.forEach { it.pushLatestFrameToWriter(timestamp) }
                                CameraHook.captureCount--
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
        var frameCount = 0
        val matrix = FloatArray(16)
        while (isRunning) {
            frameCount++
            if (frameCount % 60 == 0) CameraHook.loadConfiguration()
            
            if (hasNewFrame.compareAndSet(true, false)) {
                try { mediaSurfaceTexture?.updateTexImage() } catch (_: Exception) {}
            }
            mediaSurfaceTexture?.getTransformMatrix(matrix)
            
            val (vw, vh) = sizeProvider()
            if (!drawToAllSurfaces(matrix, vw, vh)) break

            // Handle Photo/Capture Requests
            synchronized(CameraHook) {
                while (CameraHook.captureCount > 0) {
                    val capture = CameraHook.captureQueue.poll()
                    val timestamp = capture?.first ?: System.nanoTime()
                    
                    CameraHook.formatBridges.values.forEach { 
                        it.pushLatestFrameToWriter(timestamp) 
                    }
                    CameraHook.captureCount--
                }
            }
            
            sleep(30)
        }
    }

    private fun drawToAllSurfaces(matrix: FloatArray, contentW: Int, contentH: Int): Boolean {
        val it = eglSurfaceTargets.iterator()
        while (it.hasNext()) {
            val (es, isCapture) = it.next()
            try {
                eglCore!!.makeCurrent(es)
                val vw = eglCore!!.querySurface(es, android.opengl.EGL14.EGL_WIDTH)
                val vh = eglCore!!.querySurface(es, android.opengl.EGL14.EGL_HEIGHT)
                // Draw the frame
            val applyRotation = if (isCapture) sensorOrientation else 0
            textureRenderer?.draw(matrix, contentW, contentH, vw, vh, getTargetRatio(vw, vh, isCapture), applyRotation, CameraHook.isMirrored)
            
            if (eglCore?.swapBuffers(es) == false) {
                    Log.w("VirtuCam_Render", "Surface abandoned, removing.")
                    it.remove()
                }
            } catch (e: Exception) {
                it.remove()
            }
        }
        return eglSurfaceTargets.isNotEmpty()
    }

    private fun releaseResources() {
        isRunning = false
        videoPlayer?.stop()
        streamPlayer?.stop()
        mediaSurface?.release()
        mediaSurfaceTexture?.release()
        
        eglSurfaceTargets.forEach { 
            try { eglCore?.releaseSurface(it.first) } catch (_: Exception) {}
        }
        eglSurfaceTargets.clear()
        
        textureRenderer?.release()
        eglCore?.release()
    }
    
    fun quit() {
        isRunning = false
    }
}

