package com.virtucam.opengl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.util.Log

/**
 * Core EGL state (display, context, config).
 * Designed to construct an EGL context for offscreen rendering, and to
 * bind it to a Surface (provided by target app's CameraCaptureSession).
 */
class EglCore {

    companion object {
        private const val TAG = "EglCore"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null

    init {
        initEGL()
    }

    private fun initEGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay === EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("unable to get EGL14 display")
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("unable to initialize EGL14")
        }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1, // Needed for MediaCodec surfaces
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(
                eglDisplay, configAttribs, 0,
                configs, 0, configs.size, numConfigs, 0
            ) || numConfigs[0] == 0
        ) {
            throw RuntimeException("Unable to find a suitable EGLConfig")
        }
        eglConfig = configs[0]

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        
        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            contextAttribs, 0
        )
        
        if (eglContext === EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("Failed to create EGL context")
        }
    }

    /**
     * Creates an EGL surface for the given Surface or SurfaceTexture.
     */
    fun createWindowSurface(surface: Any): EGLSurface {
        if (surface !is android.view.Surface && surface !is android.graphics.SurfaceTexture) {
            throw RuntimeException("Invalid surface: $surface")
        }

        val surfaceAttribs = intArrayOf(
            EGL14.EGL_NONE
        )
        val eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig, surface,
            surfaceAttribs, 0
        )
        
        if (eglSurface == null || eglSurface === EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("Surface was already released or invalid")
        }
        return eglSurface
    }

    fun makeCurrent(eglSurface: EGLSurface) {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    /**
     * Queries an EGL surface integer value (like EGL_WIDTH or EGL_HEIGHT)
     */
    fun querySurface(eglSurface: EGLSurface, what: Int): Int {
        val value = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, what, value, 0)
        return value[0]
    }

    fun swapBuffers(eglSurface: EGLSurface): Boolean {
        return EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    fun setPresentationTime(eglSurface: EGLSurface, nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
    }

    fun releaseSurface(eglSurface: EGLSurface) {
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
    }

    fun release() {
        if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglConfig = null
    }
}
