package com.virtucam

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.virtucam.hooks.CameraHook

/**
 * Xposed Module Entry Point
 * 
 * This class is loaded by the Xposed framework (or LSPatch) when a target app starts.
 * It initializes our camera hooks to intercept and replace the camera feed.
 */
class ModuleMain : IXposedHookLoadPackage {
    
    companion object {
        const val TAG = "VirtuCam"
        
        fun log(message: String) {
            XposedBridge.log("[$TAG] $message")
        }
    }
    
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Skip our own app
            if (lpparam.packageName == "com.virtucam") {
                return
            }
            
            log("Attaching to: ${lpparam.packageName}")
            
            // Initialize camera hooks with defensive catch
            try {
                CameraHook.init(lpparam)
            } catch (hookError: Throwable) {
                log("Hook initialization failed: ${hookError.message}")
            }
            
        } catch (t: Throwable) {
            // Final safety net to prevent any possible crash from bubbling up to the target app
            XposedBridge.log("VirtuCam: Critical failure in handleLoadPackage for ${lpparam.packageName}: ${t.message}")
        }
    }
}
