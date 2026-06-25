package com.webcamstream

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * OpenGL ES compositor for merging camera and WebView textures.
 * Uses shaders to blend textures in real-time on the GPU.
 */
class GlCompositor {

    // MARK: - Properties

    /// Camera texture ID (OES_TEXTURE)
    private var cameraTextureId: Int = -1

    /// WebView texture ID (TEXTURE_2D)
    private var webTextureId: Int = -1

    /// Output texture/framebuffer
    private var outputFramebufferId: Int = -1
    private var outputTextureId: Int = -1

    /// Shader program
    private var shaderProgram: Int = -1

    /// Vertex buffer
    private var vertexBuffer: FloatBuffer? = null

    /// Texture coordinate buffer
    private var texCoordBuffer: FloatBuffer? = null

    /// Output dimensions
    var outputWidth: Int = 1080
    var outputHeight: Int = 1920

    /// Is compositor initialized
    private var isInitialized: Boolean = false

    // MARK: - Shader Source

    private val vertexShaderSource = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """

    private val fragmentShaderSource = """
        precision mediump float;
        
        uniform sampler2D uCameraTexture;
        uniform sampler2D uWebTexture;
        uniform float uWebOpacity;
        
        varying vec2 vTexCoord;
        
        void main() {
            vec4 cameraColor = texture2D(uCameraTexture, vTexCoord);
            vec4 webColor = texture2D(uWebTexture, vTexCoord);
            
            // Alpha blend WebView over camera
            float alpha = webColor.a * uWebOpacity;
            vec4 color = mix(cameraColor, webColor, alpha);
            
            gl_FragColor = color;
        }
    """

    // MARK: - Initialization

    fun initialize() {
        if (isInitialized) return

        Log.d(TAG, "Initializing OpenGL compositor...")

        // TODO: Setup OpenGL resources
        // 1. Create shader program
        // 2. Create vertex buffers
        // 3. Create textures
        // 4. Create framebuffer

        createShaderProgram()
        createBuffers()
        createTextures()

        isInitialized = true
        Log.d(TAG, "OpenGL compositor initialized")
    }

    // MARK: - Public Methods

    /** Set the camera texture ID (from CameraX) */
    fun setCameraTexture(textureId: Int) {
        cameraTextureId = textureId
        Log.d(TAG, "Camera texture set: $textureId")
    }

    /** Update WebView texture from bitmap */
    fun updateWebTexture(bitmap: Bitmap) {
        if (webTextureId == -1) {
            createWebTexture()
        }

        // TODO: Upload bitmap to texture
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, webTextureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    /** Composite camera and WebView textures */
    fun composite(): Int {
        if (!isInitialized) {
            initialize()
        }

        // TODO: Perform compositing
        // 1. Bind output framebuffer
        // 2. Set viewport
        // 3. Use shader program
        // 4. Bind textures
        // 5. Draw quad
        // 6. Return output texture

        Log.d(TAG, "Compositing frames...")

        return outputTextureId
    }

    /** Read composite output to byte buffer */
    fun readOutput(): ByteBuffer? {
        // TODO: Read pixels from framebuffer
        val buffer = ByteBuffer.allocateDirect(outputWidth * outputHeight * 4)
            .order(ByteOrder.nativeOrder())

        GLES20.glReadPixels(
            0, 0,
            outputWidth, outputHeight,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            buffer
        )

        return buffer
    }

    /** Cleanup OpenGL resources */
    fun cleanup() {
        Log.d(TAG, "Cleaning up...")

        if (cameraTextureId != -1) {
            GLES20.glDeleteTextures(1, intArrayOf(cameraTextureId), 0)
        }
        if (webTextureId != -1) {
            GLES20.glDeleteTextures(1, intArrayOf(webTextureId), 0)
        }
        if (outputTextureId != -1) {
            GLES20.glDeleteTextures(1, intArrayOf(outputTextureId), 0)
        }
        if (outputFramebufferId != -1) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(outputFramebufferId), 0)
        }
        if (shaderProgram != -1) {
            GLES20.glDeleteProgram(shaderProgram)
        }

        isInitialized = false
    }

    // MARK: - Private Methods

    private fun createShaderProgram() {
        // TODO: Compile and link shaders
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexShaderSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderSource)

        shaderProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(shaderProgram, vertexShader)
        GLES20.glAttachShader(shaderProgram, fragmentShader)
        GLES20.glLinkProgram(shaderProgram)

        Log.d(TAG, "Shader program created: $shaderProgram")
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    private fun createBuffers() {
        // Vertex coordinates (full-screen quad)
        val vertices = floatArrayOf(
            -1f, -1f,  // bottom-left
            1f, -1f,   // bottom-right
            -1f, 1f,   // top-left
            1f, 1f     // top-right
        )

        // Texture coordinates
        val texCoords = floatArrayOf(
            0f, 0f,  // bottom-left
            1f, 0f,  // bottom-right
            0f, 1f,  // top-left
            1f, 1f   // top-right
        )

        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
        vertexBuffer?.position(0)

        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(texCoords)
        texCoordBuffer?.position(0)
    }

    private fun createTextures() {
        // Create output texture and framebuffer
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        outputTextureId = textures[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, outputTextureId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            outputWidth, outputHeight, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        // Create framebuffer
        val framebuffers = IntArray(1)
        GLES20.glGenFramebuffers(1, framebuffers, 0)
        outputFramebufferId = framebuffers[0]

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFramebufferId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            outputTextureId, 0
        )

        Log.d(TAG, "Textures created: camera=$cameraTextureId, web=$webTextureId, output=$outputTextureId")
    }

    private fun createWebTexture() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        webTextureId = textures[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, webTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    companion object {
        private const val TAG = "GlCompositor"
    }
}
