package com.virtucam.hooks

import android.app.AndroidAppHelper
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.media.Image
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import android.os.Handler
import android.os.HandlerThread
import android.media.ImageReader
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object CameraHook {
    private const val TAG = "VirtuCam_Hook"
    private var isEnabled = true
    
    @Volatile var isVideo: Boolean = false
    @Volatile var isStreamActive = false
    @Volatile var latestVirtualJpeg: ByteArray? = null
    @Volatile var latestVirtualJpegArea: Int = 0
    @Volatile var isGeneratingJpeg: Boolean = false
    @Volatile var isLivenessEnabled: Boolean = true
    
    private val activeBridges = mutableListOf<FormatConverterBridge>()
    private val surfaceFormats = java.util.concurrent.ConcurrentHashMap<Surface, Int>()
    private val surfaceSizes = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Surface, Pair<Int, Int>>())
    private var activeCameraId: String = "0"

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookCameraManager(lpparam)
        hookImageReader(lpparam)
        hookSubmitCaptureRequest(lpparam)
        hookCaptureCallback(lpparam)
        hookCameraDevice(lpparam)
        Log.d(TAG, "VirtuCam_Hook: Hardened hooks deployed.")
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
        // [3A JITTER] Mimic physical sensor hunting
        val focusJitter = (Math.random() * 0.05).toFloat() - 0.025f
        setCaptureRequestField(request, android.hardware.camera2.CaptureRequest.LENS_FOCUS_DISTANCE, 0.5f + focusJitter)
        val expJitter = (Math.random() * 500000).toLong() - 250000
        setCaptureRequestField(request, android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME, 33333333L + expJitter)
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

    private fun hookCameraDevice(lpparam: XC_LoadPackage.LoadPackageParam) {
        val deviceClass = XposedHelpers.findClassIfExists("android.hardware.camera2.impl.CameraDeviceImpl", lpparam.classLoader) ?: return
        XposedHelpers.findAndHookMethod(deviceClass, "createCaptureSession", List::class.java, android.hardware.camera2.CameraCaptureSession.StateCallback::class.java, Handler::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val outputs = param.args[0] as? MutableList<Surface> ?: return
                val filtered = mutableListOf<Surface>()
                for (s in outputs) {
                    val size = surfaceSizes[s]
                    if (size != null) {
                        val bridge = FormatConverterBridge(size.first, size.second, s)
                        activeBridges.add(bridge)
                        filtered.add(bridge.inputSurface!!)
                    } else filtered.add(s)
                }
                param.args[0] = filtered
            }
        })
    }

    private fun hookCameraManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        val mgrClass = XposedHelpers.findClassIfExists("android.hardware.camera2.CameraManager", lpparam.classLoader) ?: return
        XposedHelpers.findAndHookMethod(mgrClass, "openCamera", String::class.java, android.hardware.camera2.CameraDevice.StateCallback::class.java, Handler::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) { activeCameraId = param.args[0] as String }
        })
    }

    private fun hookImageReader(lpparam: XC_LoadPackage.LoadPackageParam) {
        val irClass = XposedHelpers.findClassIfExists("android.media.ImageReader", lpparam.classLoader) ?: return
        XposedHelpers.findAndHookMethod(irClass, "getSurface", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val reader = param.thisObject as ImageReader
                val s = param.result as Surface
                surfaceSizes[s] = Pair(reader.width, reader.height)
            }
        })
    }
}
