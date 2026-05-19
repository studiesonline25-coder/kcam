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
            uniform vec3 uColorTint;      // RGB color tint (0-1 range)
            uniform float uColorIntensity; // How strong the tint is (0-1)
            const float blurSize = 0.02;
            
            // High-quality hash function for better noise distribution
            float hash(vec2 p) {
                vec3 p3 = fract(vec3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }
            
            // Improved Gaussian noise using Box-Muller transform
            float gaussianNoise(vec2 p) {
                float u1 = hash(p) + 0.00001;
                float u2 = hash(p + vec2(127.1, 311.7));
                return sqrt(-2.0 * log(u1)) * cos(6.2831853 * u2);
            }
            
            // Multi-octave noise for natural texture variation
            float fbmNoise(vec2 p, float time) {
                float value = 0.0;
                float amplitude = 0.5;
                float frequency = 1.0;
                for (int i = 0; i < 3; i++) {
                    value += amplitude * gaussianNoise(p * frequency + time * 50.0);
                    frequency *= 2.0;
                    amplitude *= 0.5;
                }
                return value;
            }
            
            // CMOS sensor noise model: shot noise + read noise + temporal variation
            vec3 sensorNoise(vec2 coord, float time, vec3 signal) {
                // Unique seed per frame (ensures no identical frames)
                vec2 frameSeed = coord + vec2(time * 1000.0, time * 700.0);
                
                // Shot noise: proportional to sqrt(signal) - Poisson approximation
                float luminance = dot(signal, vec3(0.299, 0.587, 0.114));
                float shotNoiseScale = 0.003 * sqrt(luminance + 0.01);
                
                // Read noise: constant Gaussian (sensor electronics)
                float readNoiseScale = 0.0015;
                
                // Temporal variation: ensures every frame is unique
                float temporalJitter = hash(frameSeed) * 0.001;
                
                // Per-channel noise (color channels have slightly different noise)
                vec3 noise;
                noise.r = gaussianNoise(frameSeed) * (shotNoiseScale + readNoiseScale) + temporalJitter;
                noise.g = gaussianNoise(frameSeed + vec2(17.3, 41.7)) * (shotNoiseScale + readNoiseScale) + temporalJitter;
                noise.b = gaussianNoise(frameSeed + vec2(59.1, 23.9)) * (shotNoiseScale + readNoiseScale) + temporalJitter;
                
                return noise;
            }
            
            // Texture detail enhancement (counteracts H.264 smoothing)
            vec3 enhanceDetail(vec3 color, vec2 coord, float time) {
                // Sample neighbors for edge detection
                vec2 texelSize = vec2(1.0 / 720.0, 1.0 / 720.0);
                vec3 n = texture2D(sTexture, coord + vec2(0.0, -texelSize.y)).rgb;
                vec3 s = texture2D(sTexture, coord + vec2(0.0, texelSize.y)).rgb;
                vec3 e = texture2D(sTexture, coord + vec2(texelSize.x, 0.0)).rgb;
                vec3 w = texture2D(sTexture, coord + vec2(-texelSize.x, 0.0)).rgb;
                
                // Laplacian for edge detection
                vec3 laplacian = 4.0 * color - n - s - e - w;
                
                // Add subtle sharpening (restores edge detail lost to compression)
                float sharpAmount = 0.15;
                vec3 sharpened = color + laplacian * sharpAmount;
                
                // Add micro-texture variation (simulates skin pores, fabric texture)
                float microTexture = fbmNoise(coord * 500.0, time) * 0.008;
                
                return sharpened + microTexture;
            }
            
            float fixedPatternNoise(vec2 p) {
                float n = fract(sin(dot(p, vec2(41.1, 289.3))) * 43758.5453);
                return step(0.9998, n) * 0.012;
            }
            
            // Apply color tint like ambient light reflection on skin
            vec3 applyAmbientTint(vec3 color, vec3 tint, float intensity) {
                float luminance = dot(color, vec3(0.299, 0.587, 0.114));
                
                float highlightReflection = smoothstep(0.6, 0.85, luminance) * 0.8;
                float midtoneReflection = smoothstep(0.15, 0.4, luminance) * smoothstep(0.85, 0.55, luminance);
                float skinReflection = max(highlightReflection, midtoneReflection);
                
                float subsurface = luminance * 0.15 * (tint.r * 0.5 + 0.5);
                float microVariation = 1.0 + gaussianNoise(gl_FragCoord.xy * 0.02 + uTime * 10.0) * 0.08;
                
                float totalReflection = (skinReflection + subsurface) * microVariation;
                float effectiveIntensity = intensity * totalReflection * 0.7;
                
                vec3 directReflection = tint * effectiveIntensity * 0.35;
                vec3 ambientTint = mix(vec3(1.0), 1.0 + tint * 0.4, effectiveIntensity);
                vec3 shadowShift = mix(vec3(0.0), tint * 0.1, (1.0 - luminance) * effectiveIntensity);
                
                vec3 tintedColor = (color + directReflection + shadowShift) * ambientTint;
                vec3 preservedColor = mix(tintedColor, color, 0.15);
                
                return clamp(preservedColor, 0.0, 1.0);
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
                    // Chromatic aberration (lens imperfection)
                    vec2 caOffset = (vTextureCoord - 0.5) * 0.0012;
                    float r = texture2D(sTexture, vTextureCoord + caOffset).r;
                    float g = texture2D(sTexture, vTextureCoord).g;
                    float b = texture2D(sTexture, vTextureCoord - caOffset).b;
                    vec3 baseColor = vec3(r, g, b);
                    
                    // ANTI-DETECTION: Enhance texture detail (counteracts H.264 smoothing)
                    vec3 detailedColor = enhanceDetail(baseColor, vTextureCoord, uTime);
                    
                    // Apply ambient color tint (simulates screen light reflection on face)
                    vec3 tintedColor = applyAmbientTint(detailedColor, uColorTint, uColorIntensity);
                    
                    // ANTI-DETECTION: Add realistic CMOS sensor noise (ensures unique frames)
                    vec3 noise = sensorNoise(gl_FragCoord.xy, uTime, tintedColor);
                    
                    // Fixed pattern noise (hot pixels)
                    float fpn = fixedPatternNoise(gl_FragCoord.xy);
                    
                    // Final output with all anti-detection measures
                    gl_FragColor = vec4(tintedColor * uBrightness + noise + fpn, 1.0);
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
            uniform vec3 uColorTint;      // RGB color tint (0-1 range)
            uniform float uColorIntensity; // How strong the tint is (0-1)
            const float blurSize = 0.02;
            
            // High-quality hash function for better noise distribution
            float hash(vec2 p) {
                vec3 p3 = fract(vec3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }
            
            // Improved Gaussian noise using Box-Muller transform
            float gaussianNoise(vec2 p) {
                float u1 = hash(p) + 0.00001;
                float u2 = hash(p + vec2(127.1, 311.7));
                return sqrt(-2.0 * log(u1)) * cos(6.2831853 * u2);
            }
            
            // Multi-octave noise for natural texture variation
            float fbmNoise(vec2 p, float time) {
                float value = 0.0;
                float amplitude = 0.5;
                float frequency = 1.0;
                for (int i = 0; i < 3; i++) {
                    value += amplitude * gaussianNoise(p * frequency + time * 50.0);
                    frequency *= 2.0;
                    amplitude *= 0.5;
                }
                return value;
            }
            
            // CMOS sensor noise model: shot noise + read noise + temporal variation
            vec3 sensorNoise(vec2 coord, float time, vec3 signal) {
                vec2 frameSeed = coord + vec2(time * 1000.0, time * 700.0);
                float luminance = dot(signal, vec3(0.299, 0.587, 0.114));
                float shotNoiseScale = 0.003 * sqrt(luminance + 0.01);
                float readNoiseScale = 0.0015;
                float temporalJitter = hash(frameSeed) * 0.001;
                
                vec3 noise;
                noise.r = gaussianNoise(frameSeed) * (shotNoiseScale + readNoiseScale) + temporalJitter;
                noise.g = gaussianNoise(frameSeed + vec2(17.3, 41.7)) * (shotNoiseScale + readNoiseScale) + temporalJitter;
                noise.b = gaussianNoise(frameSeed + vec2(59.1, 23.9)) * (shotNoiseScale + readNoiseScale) + temporalJitter;
                
                return noise;
            }
            
            // Texture detail enhancement (counteracts compression smoothing)
            vec3 enhanceDetail(vec3 color, vec2 coord, float time) {
                vec2 texelSize = vec2(1.0 / 720.0, 1.0 / 720.0);
                vec3 n = texture2D(sTexture, coord + vec2(0.0, -texelSize.y)).rgb;
                vec3 s = texture2D(sTexture, coord + vec2(0.0, texelSize.y)).rgb;
                vec3 e = texture2D(sTexture, coord + vec2(texelSize.x, 0.0)).rgb;
                vec3 w = texture2D(sTexture, coord + vec2(-texelSize.x, 0.0)).rgb;
                
                vec3 laplacian = 4.0 * color - n - s - e - w;
                float sharpAmount = 0.15;
                vec3 sharpened = color + laplacian * sharpAmount;
                
                float microTexture = fbmNoise(coord * 500.0, time) * 0.008;
                return sharpened + microTexture;
            }
            
            // Apply color tint like ambient light reflection on skin
            vec3 applyAmbientTint(vec3 color, vec3 tint, float intensity) {
                float luminance = dot(color, vec3(0.299, 0.587, 0.114));
                
                float highlightReflection = smoothstep(0.6, 0.85, luminance) * 0.8;
                float midtoneReflection = smoothstep(0.15, 0.4, luminance) * smoothstep(0.85, 0.55, luminance);
                float skinReflection = max(highlightReflection, midtoneReflection);
                
                float subsurface = luminance * 0.15 * (tint.r * 0.5 + 0.5);
                float microVariation = 1.0 + gaussianNoise(gl_FragCoord.xy * 0.02 + uTime * 10.0) * 0.08;
                
                float totalReflection = (skinReflection + subsurface) * microVariation;
                float effectiveIntensity = intensity * totalReflection * 0.7;
                
                vec3 directReflection = tint * effectiveIntensity * 0.35;
                vec3 ambientTint = mix(vec3(1.0), 1.0 + tint * 0.4, effectiveIntensity);
                vec3 shadowShift = mix(vec3(0.0), tint * 0.1, (1.0 - luminance) * effectiveIntensity);
                
                vec3 tintedColor = (color + directReflection + shadowShift) * ambientTint;
                vec3 preservedColor = mix(tintedColor, color, 0.15);
                
                return clamp(preservedColor, 0.0, 1.0);
            }
            
            void main() {
                vec4 tc = texture2D(sTexture, vTextureCoord);
                vec3 baseColor = tc.rgb;
                
                // ANTI-DETECTION: Enhance texture detail
                vec3 detailedColor = enhanceDetail(baseColor, vTextureCoord, uTime);
                
                // Apply ambient color tint (simulates screen light reflection on face)
                vec3 tintedColor = applyAmbientTint(detailedColor, uColorTint, uColorIntensity);
                
                // ANTI-DETECTION: Add realistic CMOS sensor noise (ensures unique frames)
                vec3 noise = sensorNoise(gl_FragCoord.xy, uTime, tintedColor);
                
                gl_FragColor = vec4(tintedColor * uBrightness + noise, 1.0);
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
    private var muColorTintHandle = 0
    private var muColorIntensityHandle = 0
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
        muColorTintHandle = GLES20.glGetUniformLocation(program, "uColorTint")
        muColorIntensityHandle = GLES20.glGetUniformLocation(program, "uColorIntensity")
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
             gyroOffsetX: Float = 0f, gyroOffsetY: Float = 0f,
             colorTintR: Float = 0f, colorTintG: Float = 0f, colorTintB: Float = 0f,
             colorIntensity: Float = 0f) {
             
        if (viewWidth > 0 && viewHeight > 0) GLES20.glViewport(0, 0, viewWidth, viewHeight)
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        
        // Set color tint uniforms for ambient light simulation
        GLES20.glUniform3f(muColorTintHandle, colorTintR, colorTintG, colorTintB)
        GLES20.glUniform1f(muColorIntensityHandle, colorIntensity)
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

                // 1. HARDENING: Anti-Detection Gyro Tilt (realistic camera rotation, not translation)
                // Apply gyro as rotation around X and Y axes to mimic real camera sensor tilt
                if (gyroOffsetX != 0f || gyroOffsetY != 0f) {
                    Matrix.translateM(modelMatrix, 0, 0.5f, 0.5f, 0f)  // Rotate around center
                    Matrix.rotateM(modelMatrix, 0, gyroOffsetY, 1f, 0f, 0f)  // Pitch (X-axis rotation)
                    Matrix.rotateM(modelMatrix, 0, gyroOffsetX, 0f, 1f, 0f)  // Roll (Y-axis rotation)
                    Matrix.translateM(modelMatrix, 0, -0.5f, -0.5f, 0f)
                }
                
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
