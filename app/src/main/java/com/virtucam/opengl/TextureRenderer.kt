package com.virtucam.opengl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import com.virtucam.hooks.CameraHook

/**
 * Renders an OES texture (from MediaCodec Decoder or SurfaceTexture) onto the current EGL surface.
 * Handles matrix transformations to correct orientation (Relative Rotation).
 * [SURGICAL MERGE] 02875b3 Matrix Engine + Hardening Shaders
 */
class TextureRenderer(private val isVideo: Boolean = true) {

    companion object {
        private const val TAG = "TextureRenderer"
        private const val FLOAT_SIZE_BYTES = 4
        
        private const val VERTEX_SHADER = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
        """

        private val VERTEX_COORDS = floatArrayOf(-1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f)
        private val OES_TEXTURE_COORDS = floatArrayOf(0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f)
        private val IMAGE_TEXTURE_COORDS = floatArrayOf(0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f)
    }

    private var program = 0
    private var muMVPMatrixHandle = 0
    private var muSTMatrixHandle = 0
    private var maPositionHandle = 0
    private var maTextureHandle = 0
    private var muIsBackgroundHandle = 0
    private var muBrightnessHandle = 0
    private var muTimeHandle = 0
    
    // Hardening Uniforms
    private var uTimeLoc = -1
    private var uGyroOffsetLoc = -1
    private var uBrightnessLoc = -1

    internal var textureId = -1
    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(VERTEX_COORDS.size * FLOAT_SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(VERTEX_COORDS).position(0) }
    private val textureBuffer: FloatBuffer
    private val stMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    init {
        val coords = if (isVideo) OES_TEXTURE_COORDS else IMAGE_TEXTURE_COORDS
        textureBuffer = ByteBuffer.allocateDirect(coords.size * FLOAT_SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(coords).position(0) }
        Matrix.setIdentityM(stMatrix, 0)
        Matrix.setIdentityM(mvpMatrix, 0)
    }

    fun init() {
        val fragmentShader = if (isVideo) getOesFragmentShader() else getImageFragmentShader()
        program = createProgram(VERTEX_SHADER, fragmentShader)
        
        maPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        maTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        muMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        muSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        muIsBackgroundHandle = GLES20.glGetUniformLocation(program, "uIsBackground")
        muBrightnessHandle = GLES20.glGetUniformLocation(program, "uBrightness")
        muTimeHandle = GLES20.glGetUniformLocation(program, "uTime")
        
        // Hardening locations
        uTimeLoc = GLES20.glGetUniformLocation(program, "uTime")
        uGyroOffsetLoc = GLES20.glGetUniformLocation(program, "uGyroOffset")
        uBrightnessLoc = GLES20.glGetUniformLocation(program, "uBrightness")

        textureId = IntArray(1).apply { GLES20.glGenTextures(1, this, 0) }[0]
        val target = if (isVideo) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
        GLES20.glBindTexture(target, textureId)
        GLES20.glTexParameterf(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun getOesFragmentShader(): String = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform samplerExternalOES sTexture;
        uniform float uTime;
        uniform vec2 uGyroOffset;
        uniform float uBrightness;

        float rand(vec2 co) {
            return fract(sin(dot(co.xy ,vec2(12.9898,78.233))) * 43758.5453);
        }

        void main() {
            vec2 uv = vTextureCoord + uGyroOffset;
            vec4 color = texture2D(sTexture, uv);
            float noise = (rand(uv + uTime) - 0.5) * 0.015;
            float fpn = (rand(uv * 10.0) - 0.5) * 0.005;
            color.rgb += noise + fpn;
            color.rgb *= uBrightness;
            gl_FragColor = color;
        }
    """.trimIndent()

    private fun getImageFragmentShader(): String = """
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform sampler2D sTexture;
        uniform float uBrightness;
        void main() {
            vec4 color = texture2D(sTexture, vTextureCoord);
            color.rgb *= uBrightness;
            gl_FragColor = color;
        }
    """.trimIndent()

    fun draw(transformMatrix: FloatArray, videoWidth: Int = 0, videoHeight: Int = 0, viewWidth: Int = 0, viewHeight: Int = 0, 
             targetRatio: Float = 0f, hardwareSensorOrientation: Int = 0, userRotation: Int = 0, 
             isMirrored: Boolean = false, zoomFactor: Float = 1.0f, isCapture: Boolean = false,
             compensationFactor: Float = 1.0f, rotationOffset: Int = 0,
             ambientLightMultiplier: Float = 1.0f, timeValue: Float = 0.0f,
             gyroOffsetX: Float = 0f, gyroOffsetY: Float = 0f) {
             
        if (viewWidth > 0 && viewHeight > 0) GLES20.glViewport(0, 0, viewWidth, viewHeight)
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        val target = if (isVideo) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, textureId)
        System.arraycopy(transformMatrix, 0, stMatrix, 0, 16)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(maPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(maPositionHandle)
        textureBuffer.position(0)
        GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)
        GLES20.glEnableVertexAttribArray(maTextureHandle)

        if (videoWidth > 0 && videoHeight > 0 && viewWidth > 0 && viewHeight > 0) {
            val baseRotation = ((hardwareSensorOrientation % 360) + 360) % 360
            val totalRotation = (baseRotation + userRotation + rotationOffset + 360) % 360

            fun drawQuad(isBackground: Boolean) {
                val projMatrix = FloatArray(16)
                val viewRatio = if (targetRatio > 0f) targetRatio else (viewWidth.toFloat() / viewHeight.toFloat())
                Matrix.orthoM(projMatrix, 0, -viewRatio, viewRatio, -1f, 1f, -1f, 1f)

                val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()
                val modelMatrix = FloatArray(16)
                Matrix.setIdentityM(modelMatrix, 0)
                
                val rotatedW = if (totalRotation % 180 != 0) 2.0f else (2.0f * videoRatio)
                val rotatedH = if (totalRotation % 180 != 0) (2.0f * videoRatio) else 2.0f
                val scaleXToFill = (2.0f * viewRatio) / rotatedW
                val scaleYToFill = 2.0f / rotatedH
                val baseScale = if (isBackground) java.lang.Math.max(scaleXToFill, scaleYToFill) else java.lang.Math.min(scaleXToFill, scaleYToFill)

                // 1. HARDENING: Anti-Detection Shake
                Matrix.translateM(modelMatrix, 0, gyroOffsetX, gyroOffsetY, 0f)
                
                // 2. Viewport fitting
                Matrix.scaleM(modelMatrix, 0, baseScale * zoomFactor, baseScale * zoomFactor, 1.0f)
                
                // 3. 02875b3 Baseline Orientation Logic
                if (totalRotation != 0) Matrix.rotateM(modelMatrix, 0, totalRotation.toFloat(), 0f, 0f, 1f)
                Matrix.scaleM(modelMatrix, 0, (if (isMirrored) -1.0f else 1.0f) * compensationFactor, 1.0f, 1.0f)
                Matrix.scaleM(modelMatrix, 0, videoRatio, 1.0f, 1.0f)

                Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, modelMatrix, 0)
                GLES20.glUniform1i(muIsBackgroundHandle, if (isBackground) 1 else 0)
                GLES20.glUniform1f(muBrightnessHandle, ambientLightMultiplier)
                GLES20.glUniform1f(muTimeHandle, timeValue)
                GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, stMatrix, 0)
                GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mvpMatrix, 0)
                
                // Hardening Uniforms
                GLES20.glUniform1f(uTimeLoc, (System.currentTimeMillis() % 100000).toFloat() / 1000f)
                GLES20.glUniform2f(uGyroOffsetLoc, gyroOffsetX, gyroOffsetY)
                GLES20.glUniform1f(uBrightnessLoc, ambientLightMultiplier)

                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }

            drawQuad(true)
            drawQuad(false)
        }
    }

    fun loadBitmap(bitmap: android.graphics.Bitmap) {
        if (isVideo) return
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        return program
    }

    private fun loadShader(shaderType: Int, source: String): Int {
        val shader = GLES20.glCreateShader(shaderType)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    fun release() {
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        if (textureId != -1) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = -1
        }
    }
}
