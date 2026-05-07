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
 */
class TextureRenderer(private val isVideo: Boolean = true) {

    companion object {
        private const val TAG = "TextureRenderer"

        private const val FLOAT_SIZE_BYTES = 4
        
        // Simple vertex shader
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

        // Fragment shader for external OES texture (Video via SurfaceTexture)
        private const val OES_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            uniform int uIsBackground;
            uniform float uBrightness;
            uniform float uTime;
            const float blurSize = 0.02;
            
            // Box-Muller transform for Gaussian distributed thermal noise
            float gaussianNoise(vec2 p) {
                float u1 = fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
                float u2 = fract(sin(dot(p, vec2(269.5, 183.3))) * 43758.5453);
                return sqrt(-2.0 * log(u1 + 0.00001)) * cos(6.2831853 * u2);
            }
            
            // Fixed Pattern Noise (Hot pixels / Sensor impurities)
            float fixedPatternNoise(vec2 p) {
                float n = fract(sin(dot(p, vec2(41.1, 289.3))) * 43758.5453);
                return step(0.9992, n) * 0.012; 
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
                    // Feature: Chromatic Aberration (Simulates lens imperfections)
                    vec2 caOffset = (vTextureCoord - 0.5) * 0.0012;
                    float r = texture2D(sTexture, vTextureCoord + caOffset).r;
                    float g = texture2D(sTexture, vTextureCoord).g;
                    float b = texture2D(sTexture, vTextureCoord - caOffset).b;
                    vec3 baseColor = vec3(r, g, b);
                    
                    // Soft Gaussian thermal noise (scales with brightness/gain simulation)
                    float noiseScale = 0.0025 + (uBrightness - 1.0) * 0.005;
                    float gNoise = gaussianNoise(gl_FragCoord.xy + vec2(uTime * 100.0, uTime * 70.0)) * noiseScale;
                    float fpn = fixedPatternNoise(gl_FragCoord.xy);
                    
                    gl_FragColor = vec4(baseColor * uBrightness + gNoise + fpn, 1.0);
                }
            }
        """

        // Fragment shader for 2D texture (Static Image)
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
            
            float fixedPatternNoise(vec2 p) {
                float n = fract(sin(dot(p, vec2(41.1, 289.3))) * 43758.5453);
                return step(0.9992, n) * 0.012;
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
        
        private val VERTEX_COORDS = floatArrayOf(
            -1.0f, -1.0f,   // 0 bottom left
             1.0f, -1.0f,   // 1 bottom right
            -1.0f,  1.0f,   // 2 top left
             1.0f,  1.0f    // 3 top right
        )

        private val OES_TEXTURE_COORDS = floatArrayOf(
            0.0f, 0.0f,     // 0 bottom left
            1.0f, 0.0f,     // 1 bottom right
            0.0f, 1.0f,     // 2 top left
            1.0f, 1.0f      // 3 top right
        )

        private val IMAGE_TEXTURE_COORDS = floatArrayOf(
            0.0f, 1.0f,     // 0 bottom left 
            1.0f, 1.0f,     // 1 bottom right
            0.0f, 0.0f,     // 2 top left 
            1.0f, 0.0f      // 3 top right 
        )
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
    
    private val vertexBuffer: FloatBuffer
    private val textureBuffer: FloatBuffer

    private val mvpMatrix = FloatArray(16)
    private val stMatrix = FloatArray(16)

    init {
        vertexBuffer = ByteBuffer.allocateDirect(VERTEX_COORDS.size * FLOAT_SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        vertexBuffer.put(VERTEX_COORDS).position(0)

        val coords = if (isVideo) OES_TEXTURE_COORDS else IMAGE_TEXTURE_COORDS
        textureBuffer = ByteBuffer.allocateDirect(coords.size * FLOAT_SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        textureBuffer.put(coords).position(0)

        Matrix.setIdentityM(stMatrix, 0)
        Matrix.setIdentityM(mvpMatrix, 0)
    }

    fun init() {
        val fragmentShaderCode = if (isVideo) OES_FRAGMENT_SHADER else IMAGE_FRAGMENT_SHADER
        
        program = createProgram(VERTEX_SHADER, fragmentShaderCode)
        if (program == 0) throw RuntimeException("Failed to create program")

        maPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        maTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        muMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        muSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        muIsBackgroundHandle = GLES20.glGetUniformLocation(program, "uIsBackground")
        muBrightnessHandle = GLES20.glGetUniformLocation(program, "uBrightness")
        muTimeHandle = GLES20.glGetUniformLocation(program, "uTime")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        
        val target = if (isVideo) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
        
        GLES20.glBindTexture(target, textureId)
        GLES20.glTexParameterf(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    fun draw(transformMatrix: FloatArray, viewWidth: Int = 0, viewHeight: Int = 0, 
             brightnessMultiplier: Float = 1.0f, timeValue: Float = 0.0f,
             shakeX: Float = 0f, shakeY: Float = 0f) {
        
        if (viewWidth > 0 && viewHeight > 0) {
            GLES20.glViewport(0, 0, viewWidth, viewHeight)
        }
        
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        val target = if (isVideo) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, textureId)

        Matrix.setIdentityM(mvpMatrix, 0)
        // Apply Virtual Shake to the Matrix
        Matrix.translateM(mvpMatrix, 0, shakeX, shakeY, 0f)

        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, transformMatrix, 0)
        GLES20.glUniform1i(muIsBackgroundHandle, 0)
        GLES20.glUniform1f(muBrightnessHandle, brightnessMultiplier)
        GLES20.glUniform1f(muTimeHandle, timeValue)

        GLES20.glVertexAttribPointer(maPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(maPositionHandle)
        GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)
        GLES20.glEnableVertexAttribArray(maTextureHandle)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) return 0
        val pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (pixelShader == 0) return 0

        val program = GLES20.glCreateProgram()
        if (program != 0) {
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, pixelShader)
            GLES20.glLinkProgram(program)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "Could not link program: ")
                Log.e(TAG, GLES20.glGetProgramInfoLog(program))
                GLES20.glDeleteProgram(program)
                return 0
            }
        }
        return program
    }

    private fun loadShader(shaderType: Int, source: String): Int {
        var shader = GLES20.glCreateShader(shaderType)
        if (shader != 0) {
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                Log.e(TAG, "Could not compile shader $shaderType:")
                Log.e(TAG, GLES20.glGetShaderInfoLog(shader))
                GLES20.glDeleteShader(shader)
                shader = 0
            }
        }
        return shader
    }
}
