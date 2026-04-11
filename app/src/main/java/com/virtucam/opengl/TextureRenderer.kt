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
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """

        // Fragment shader for 2D texture (Static Image)
        private const val IMAGE_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform sampler2D sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """
        
        private val VERTEX_COORDS = floatArrayOf(
            -1.0f, -1.0f,   // 0 bottom left
             1.0f, -1.0f,   // 1 bottom right
            -1.0f,  1.0f,   // 2 top left
             1.0f,  1.0f    // 3 top right
        )

        // Android's SurfaceTexture getTransformMatrix() automatically handles the vertical flip
        // between OpenGL origin (bottom-left) and Android origin (top-left).
        private val OES_TEXTURE_COORDS = floatArrayOf(
            0.0f, 0.0f,     // 0 bottom left
            1.0f, 0.0f,     // 1 bottom right
            0.0f, 1.0f,     // 2 top left
            1.0f, 1.0f      // 3 top right
        )

        // For static images (2D Texture), we must manually flip the V-axis.
        private val IMAGE_TEXTURE_COORDS = floatArrayOf(
            0.0f, 1.0f,     // 0 bottom left  → sample from top
            1.0f, 1.0f,     // 1 bottom right → sample from top
            0.0f, 0.0f,     // 2 top left     → sample from bottom
            1.0f, 0.0f      // 3 top right    → sample from bottom
        )
    }

    private var program = 0
    private var muMVPMatrixHandle = 0
    private var muSTMatrixHandle = 0
    private var maPositionHandle = 0
    private var maTextureHandle = 0
    
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

    /**
     * Compile shaders and create OpenGL program
     */
    fun init() {
        val fragmentShaderCode = if (isVideo) OES_FRAGMENT_SHADER else IMAGE_FRAGMENT_SHADER
        
        program = createProgram(VERTEX_SHADER, fragmentShaderCode)
        if (program == 0) throw RuntimeException("Failed to create program")

        maPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        maTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        muMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        muSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")

        // Create texture
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

    /**
     * Draw the texture to currently bound frame buffer
     */
    fun draw(transformMatrix: FloatArray, videoWidth: Int = 0, videoHeight: Int = 0, viewWidth: Int = 0, viewHeight: Int = 0, 
             targetRatio: Float = 0f, rotationDegrees: Int = 0, userRotation: Int = 0, 
             isMirrored: Boolean = false, zoomFactor: Float = 1.0f, isCapture: Boolean = false,
             compensationFactor: Float = 1.0f) {
        if (viewWidth > 0 && viewHeight > 0) {
            GLES20.glViewport(0, 0, viewWidth, viewHeight)
        }
        
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        // Set texture target
        val target = if (isVideo) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, textureId)

        // Compute aspect ratio scaling (FIT_CENTER) to prevent stretching
        Matrix.setIdentityM(mvpMatrix, 0)
        
        if (videoWidth > 0 && videoHeight > 0 && viewWidth > 0 && viewHeight > 0) {
            // [Zero-Error Accuracy] 1. Calculate Total Rotation
            val totalRotation = (rotationDegrees + userRotation) % 360
            
            // [Zero-Error Accuracy] 2. Calculate Oriented Viewport (What the user actually sees)
            // If the sensor is rotated 90/270 (Portrait), the user sees the surface's height as width.
            val orientedViewW = if (totalRotation % 180 != 0) viewHeight else viewWidth
            val orientedViewH = if (totalRotation % 180 != 0) viewWidth else viewHeight
            
            val userSeenRatio = (orientedViewW.toFloat() / orientedViewH.toFloat()) * compensationFactor
            
            // [Zero-Error Accuracy] 2.1 Calculate Effective Media Ratio
            // If the media is being rotated 90/270, its width and height are effectively swapped.
            val effectiveMediaRatio = if (totalRotation % 180 != 0) {
                videoHeight.toFloat() / videoWidth.toFloat()
            } else {
                videoWidth.toFloat() / videoHeight.toFloat()
            }
            
            var scaleX: Float
            var scaleY: Float
            
            if (isCapture) {
                // [ABSOLUTE FILL] For high-res captures, we fill the buffer 
                // so that the result is full-frame after rotation.
                if (effectiveMediaRatio > userSeenRatio) {
                    scaleY = 1.0f
                    scaleX = effectiveMediaRatio / userSeenRatio
                } else {
                    scaleX = 1.0f
                    scaleY = userSeenRatio / effectiveMediaRatio
                }
            } else {
                // [PREVIEW FIT] Keep preview letterboxed as the user expects.
                if (effectiveMediaRatio > userSeenRatio) {
                    scaleX = 1.0f
                    scaleY = userSeenRatio / effectiveMediaRatio
                } else {
                    scaleX = effectiveMediaRatio / userSeenRatio
                    scaleY = 1.0f
                }
            }

            // Apply global zoom and compensation (if user still uses the slider)
            scaleX *= zoomFactor
            scaleY *= zoomFactor

            // [Zero-Error Accuracy] 3. Apply Transformations
            // A: Rotate the quad to match sensor/user orientation
            if (totalRotation != 0) {
                Matrix.rotateM(mvpMatrix, 0, totalRotation.toFloat(), 0f, 0f, 1f)
            }

            // B: Mirroring (Flip the axis that is physically horizontal on the screen)
            val flipX = isMirrored && (totalRotation % 180 == 0)
            val flipY = isMirrored && (totalRotation % 180 != 0)

            // C: Scale to fit the orientation system
            // If rotated 90/270, the quad's local X is the screen's Y, and local Y is screen X.
            if (totalRotation % 180 != 0) {
                Matrix.scaleM(mvpMatrix, 0, if (flipX) -scaleY else scaleY, if (flipY) -scaleX else scaleX, 1f)
            } else {
                Matrix.scaleM(mvpMatrix, 0, if (flipX) -scaleX else scaleX, if (flipY) -scaleY else scaleY, 1f)
            }
            
            if (frameCount++ % 60 == 0) {
                Log.d("VirtuCam_Render", "Draw: media=${videoWidth}x${videoHeight} view=${viewWidth}x${viewHeight} rot=$totalRotation scales=${scaleX}x${scaleY}")
            }
        }

        // Copy transform matrix from SurfaceTexture which Android natively encodes with EXIF Video rotators
        System.arraycopy(transformMatrix, 0, stMatrix, 0, 16)

        // Bind attributes/uniforms
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(maPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(maPositionHandle)

        textureBuffer.position(0)
        GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)
        GLES20.glEnableVertexAttribArray(maTextureHandle)

        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, stMatrix, 0)
        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mvpMatrix, 0)

        // Draw quad
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(maPositionHandle)
        GLES20.glDisableVertexAttribArray(maTextureHandle)
        GLES20.glBindTexture(target, 0)
        GLES20.glUseProgram(0)
    }

    /**
     * Upload a Bitmap to the 2D texture (for Static Image Mode)
     */
    fun loadBitmap(bitmap: android.graphics.Bitmap) {
        if (isVideo) return
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
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

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) return 0
        val pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (pixelShader == 0) return 0

        var program = GLES20.glCreateProgram()
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
                program = 0
            }
        }
        return program
    }
}
