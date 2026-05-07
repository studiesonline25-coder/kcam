package com.virtucam.opengl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders an OES texture (from MediaCodec Decoder or SurfaceTexture) onto the current EGL surface.
 * [MERGED VERSION] Combines 02875b3 Orientation Logic with a55de38 Hardening.
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

        private const val OES_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            uniform int uIsBackground;
            uniform float uBrightness;
            uniform float uTime;
            
            float gaussianNoise(vec2 p) {
                float u1 = fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
                float u2 = fract(sin(dot(p, vec2(269.5, 183.3))) * 43758.5453);
                return sqrt(-2.0 * log(u1 + 0.00001)) * cos(6.2831853 * u2);
            }
            
            float fixedPatternNoise(vec2 p) {
                float n = fract(sin(dot(p, vec2(41.1, 289.3))) * 43758.5453);
                return step(0.9992, n) * 0.012; 
            }
            
            void main() {
                vec2 caOffset = (vTextureCoord - 0.5) * 0.0012;
                float r = texture2D(sTexture, vTextureCoord + caOffset).r;
                float g = texture2D(sTexture, vTextureCoord).g;
                float b = texture2D(sTexture, vTextureCoord - caOffset).b;
                vec3 baseColor = vec3(r, g, b);
                
                float noiseScale = 0.0025 + (uBrightness - 1.0) * 0.005;
                float gNoise = gaussianNoise(gl_FragCoord.xy + vec2(uTime * 100.0, uTime * 70.0)) * noiseScale;
                float fpn = fixedPatternNoise(gl_FragCoord.xy);
                
                gl_FragColor = vec4(baseColor * uBrightness + gNoise + fpn, 1.0);
            }
        """

        private const val IMAGE_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform sampler2D sTexture;
            uniform int uIsBackground;
            uniform float uBrightness;
            uniform float uTime;

            float gaussianNoise(vec2 p) {
                float u1 = fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
                float u2 = fract(sin(dot(p, vec2(269.5, 183.3))) * 43758.5453);
                return sqrt(-2.0 * log(u1 + 0.00001)) * cos(6.2831853 * u2);
            }
            
            void main() {
                vec4 tc = texture2D(sTexture, vTextureCoord);
                float noiseScale = 0.0025;
                float gNoise = gaussianNoise(gl_FragCoord.xy + vec2(uTime * 100.0, uTime * 70.0)) * noiseScale;
                gl_FragColor = vec4(tc.rgb * uBrightness + gNoise, 1.0);
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
    private var muBrightnessHandle = 0
    private var muTimeHandle = 0
    internal var textureId = -1
    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(VERTEX_COORDS.size * FLOAT_SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(VERTEX_COORDS).position(0) }
    private val textureBuffer: FloatBuffer
    private val stMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    init {
        val coords = if (isVideo) OES_TEXTURE_COORDS else IMAGE_TEXTURE_COORDS
        textureBuffer = ByteBuffer.allocateDirect(coords.size * FLOAT_SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(coords).position(0) }
    }

    fun init() {
        program = createProgram(VERTEX_SHADER, if (isVideo) OES_FRAGMENT_SHADER else IMAGE_FRAGMENT_SHADER)
        maPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        maTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        muMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        muSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        muBrightnessHandle = GLES20.glGetUniformLocation(program, "uBrightness")
        muTimeHandle = GLES20.glGetUniformLocation(program, "uTime")
        val textures = IntArray(1).apply { GLES20.glGenTextures(1, this, 0) }
        textureId = textures[0]
        val target = if (isVideo) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
        GLES20.glBindTexture(target, textureId)
        GLES20.glTexParameterf(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
    }

    /**
     * [RESTORED] Full 16-parameter draw call from 02875b3 with integrated a55de38 hardening.
     */
    fun draw(transformMatrix: FloatArray, videoWidth: Int, videoHeight: Int, viewWidth: Int, viewHeight: Int, 
             targetRatio: Float, hardwareSensorOrientation: Int, userRotation: Int, 
             isMirrored: Boolean, zoomFactor: Float, isCapture: Boolean,
             compensationFactor: Float, rotationOffset: Int,
             brightnessMultiplier: Float, timeValue: Float,
             shakeX: Float, shakeY: Float) {

        GLES20.glUseProgram(program)
        Matrix.setIdentityM(mvpMatrix, 0)

        // 1. Apply Orientation Logic (Restored from 02875b3)
        val finalRotation = (hardwareSensorOrientation + userRotation + rotationOffset) % 360
        Matrix.rotateM(mvpMatrix, 0, -finalRotation.toFloat(), 0f, 0f, 1f)
        
        // 2. Apply Mirroring
        if (isMirrored) {
            Matrix.scaleM(mvpMatrix, 0, -1f, 1f, 1f)
        }

        // 3. Apply Aspect Fitting & Zoom
        val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()
        val scaleX = if (videoRatio > targetRatio) videoRatio / targetRatio else 1.0f
        val scaleY = if (videoRatio < targetRatio) targetRatio / videoRatio else 1.0f
        Matrix.scaleM(mvpMatrix, 0, scaleX * zoomFactor, scaleY * zoomFactor, 1.0f)

        // 4. Apply Virtual Shake (Hardening)
        Matrix.translateM(mvpMatrix, 0, shakeX, shakeY, 0f)

        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, transformMatrix, 0)
        GLES20.glUniform1f(muBrightnessHandle, brightnessMultiplier)
        GLES20.glUniform1f(muTimeHandle, timeValue)

        GLES20.glVertexAttribPointer(maPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(maPositionHandle)
        GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)
        GLES20.glEnableVertexAttribArray(maTextureHandle)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun loadBitmap(bitmap: android.graphics.Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    fun release() {
        if (textureId != -1) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        if (program != 0) GLES20.glDeleteProgram(program)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        return GLES20.glCreateProgram().apply {
            GLES20.glAttachShader(this, vs)
            GLES20.glAttachShader(this, fs)
            GLES20.glLinkProgram(this)
        }
    }

    private fun loadShader(type: Int, source: String): Int = GLES20.glCreateShader(type).apply {
        GLES20.glShaderSource(this, source)
        GLES20.glCompileShader(this)
    }
}
