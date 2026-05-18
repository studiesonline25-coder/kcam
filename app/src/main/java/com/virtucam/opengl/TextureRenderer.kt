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
 * Handles matrix transformations to correct orientation (Relative Rotation).
 * [SURGICAL MERGE] 02875b3 Matrix Engine + a55de38 Hardening Shaders
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
            const float blurSize = 0.02;
            
            float gaussianNoise(vec2 p) {
                float u1 = fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
                float u2 = fract(sin(dot(p, vec2(269.5, 183.3))) * 43758.5453);
                return sqrt(-2.0 * log(u1 + 0.00001)) * cos(6.2831853 * u2);
            }
            
            float fixedPatternNoise(vec2 p) {
                float n = fract(sin(dot(p, vec2(41.1, 289.3))) * 43758.5453);
                return step(0.9998, n) * 0.012;  // 0.02% hot pixel coverage (was 0.08%, realistic for flagship cameras)
            }
            
            void main() {
                if (uIsBackground == 1) {
                    vec4 sum = vec4(0.0);
                    sum += texture2D(sTexture, vec2(vTextureCoord.x - blurSize, vTextureCoord.y - blurSize));
                    sum += texture2D(sTexture, vec2(vTextureCoord.x, vTextureCoord.y - blurSize)) * 2.0;
                    sum += texture2D(sTexture, vec2(vTextureCoord.x + blurSize, vTextureCoord.y - blurSize));
                    sum += texture2D(sTexture, vec2(vTextureCoord.x - blurSize, vTextureCoord.y)) * 2.0;
                    sum += texture2D(sTexture, vec2(vTextureCoord.x, vTextureCoord.y)) * 4.0;
                    sum += texture2D(sTexture, vec2(vTextureCoord.x + blurSize, vTextureCoord.y)) * 2.0;
                    sum += texture2D(sTexture, vec2(vTextureCoord.x - blurSize, vTextureCoord.y + blurSize));
                    sum += texture2D(sTexture, vec2(vTextureCoord.x, vTextureCoord.y + blurSize)) * 2.0;
                    sum += texture2D(sTexture, vec2(vTextureCoord.x + blurSize, vTextureCoord.y + blurSize));
                    gl_FragColor = vec4((sum / 16.0).rgb * 0.4 * uBrightness, 1.0);
                } else {
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
            }
        """

        private const val IMAGE_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform sampler2D sTexture;
            uniform int uIsBackground;
            uniform float uBrightness;
            uniform float uTime;
            const float blurSize = 0.02;
            
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
    private var muIsBackgroundHandle = 0
    private var muBrightnessHandle = 0
    private var muTimeHandle = 0
    internal var textureId = -1
    private var frameCount = 0
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
        program = createProgram(VERTEX_SHADER, if (isVideo) OES_FRAGMENT_SHADER else IMAGE_FRAGMENT_SHADER)
        maPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        maTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        muMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        muSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        muIsBackgroundHandle = GLES20.glGetUniformLocation(program, "uIsBackground")
        muBrightnessHandle = GLES20.glGetUniformLocation(program, "uBrightness")
        muTimeHandle = GLES20.glGetUniformLocation(program, "uTime")
        textureId = IntArray(1).apply { GLES20.glGenTextures(1, this, 0) }[0]
        val target = if (isVideo) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
        GLES20.glBindTexture(target, textureId)
        GLES20.glTexParameterf(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    fun draw(transformMatrix: FloatArray, videoWidth: Int = 0, videoHeight: Int = 0, viewWidth: Int = 0, viewHeight: Int = 0, 
             targetRatio: Float = 0f, hardwareSensorOrientation: Int = 0, userRotation: Int = 0, 
             isMirrored: Boolean = false, zoomFactor: Float = 1.0f, isCapture: Boolean = false,
             compensationFactor: Float = 1.0f, rotationOffset: Int = 0,
             brightnessMultiplier: Float = 1.0f, timeValue: Float = 0.0f,
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

                // 1. HARDENING: Anti-Detection Shake (Applied at the VERY end of the stack)
                Matrix.translateM(modelMatrix, 0, gyroOffsetX, gyroOffsetY, 0f)
                
                // 2. Viewport fitting
                Matrix.scaleM(modelMatrix, 0, baseScale * zoomFactor, baseScale * zoomFactor, 1.0f)
                
                // 3. 02875b3 Baseline Orientation Logic
                if (totalRotation != 0) Matrix.rotateM(modelMatrix, 0, totalRotation.toFloat(), 0f, 0f, 1f)
                Matrix.scaleM(modelMatrix, 0, (if (isMirrored) -1.0f else 1.0f) * compensationFactor, 1.0f, 1.0f)
                Matrix.scaleM(modelMatrix, 0, videoRatio, 1.0f, 1.0f)

                Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, modelMatrix, 0)
                GLES20.glUniform1i(muIsBackgroundHandle, if (isBackground) 1 else 0)
                GLES20.glUniform1f(muBrightnessHandle, brightnessMultiplier)
                GLES20.glUniform1f(muTimeHandle, timeValue)
                GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, stMatrix, 0)
                GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mvpMatrix, 0)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }

            drawQuad(true)
            drawQuad(false)
        }
    }

    fun loadBitmap(bitmap: android.graphics.Bitmap) {
        if (isVideo) return
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    fun release() {
        if (program != 0) GLES20.glDeleteProgram(program)
        if (textureId != -1) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
    }

    private fun createProgram(vs: String, fs: String): Int {
        val vShader = loadShader(GLES20.GL_VERTEX_SHADER, vs)
        val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fs)
        return GLES20.glCreateProgram().apply {
            GLES20.glAttachShader(this, vShader)
            GLES20.glAttachShader(this, fShader)
            GLES20.glLinkProgram(this)
        }
    }

    private fun loadShader(type: Int, src: String): Int = GLES20.glCreateShader(type).apply {
        GLES20.glShaderSource(this, src)
        GLES20.glCompileShader(this)
    }
}
