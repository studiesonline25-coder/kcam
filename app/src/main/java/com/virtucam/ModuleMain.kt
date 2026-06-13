package com.virtucam

import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.virtucam.hooks.CameraHook
import com.virtucam.hooks.ScreenColorDetector

/**
 * Xposed Module Entry Point
 * 
 * This class is loaded by the Xposed framework (or LSPatch) when a target app starts.
 * It initializes our camera hooks to intercept and replace the camera feed.
 */
class ModuleMain : IXposedHookLoadPackage {
    
    companion object {
        const val TAG = "VirtuCam_Main"
        
        init {
            Log.d(TAG, "VirtuCam_Main: ENTRY POINT LOADED INTO MEMORY")
        }
    }
    
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Skip our own app
        if (lpparam.packageName == "com.kcam") return
        
        Log.e("DIAGNOSTIC_VIRTUCAM", "VirtuCam_Main: handleLoadPackage called for ${lpparam.packageName}")
        
        try {
            // Initialize camera hooks with defensive catch
            CameraHook.init(lpparam)
            Log.e("DIAGNOSTIC_VIRTUCAM", "VirtuCam_Main: CameraHook.init() successfully called for ${lpparam.packageName}.")
            
            // Initialize screen color detection for color flash challenges
            ScreenColorDetector.getInstance().hookViewDrawing(lpparam)
            Log.e("DIAGNOSTIC_VIRTUCAM", "VirtuCam_Main: ScreenColorDetector hooks initialized for ${lpparam.packageName}.")
        } catch (t: Throwable) {
            Log.e(TAG, "VirtuCam_Main: FAILED to initialize hooks for ${lpparam.packageName}", t)
        }
    }
}
