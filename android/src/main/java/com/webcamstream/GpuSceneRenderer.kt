package com.webcamstream

import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal data class GpuTextureInput(val id: String, val surface: Surface)
internal class GpuOutput internal constructor() { internal val active = AtomicBoolean(true) }

/** A single EGL owner for the app scene. RootEncoder retains its downstream GL consumer. */
internal class GpuSceneRenderer(
    context: Context,
    private val onError: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("BrowserCamGpuScene").also { it.start() }
    private val gl = Handler(thread.looper)
    val previewView = GpuSceneView(context, this)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var scratch: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var positionLocation = -1
    private var coordinateLocation = -1
    private var transformLocation = -1
    private var opacityLocation = -1
    private var samplerLocation = -1
    private var failed = false
    private var closing = false
    @Volatile private var closed = false
    private val inputs = linkedMapOf<String, Input>()
    private val outputs = linkedMapOf<GpuOutput, Output>()
    private var layers = emptyList<GpuBrowserLayer>()
    private var camera: Camera? = null
    private var preview: Output? = null
    private var previewVisible = true
    private var tickPosted = false
    private val vertices = buffer()
    private val coordinates = buffer()
    private var lastMetricsNs = 0L
    private var sceneFrames = 0
    private var outputFrames = 0
    private var diagnostics = false
    private val tick = Runnable { renderTick() }

    private class Input(val id: String, val texture: Int, val consumer: SurfaceTexture,
                        val surface: Surface, var width: Int, var height: Int) {
        var dirty = false
        var ready = false
        var probed = false
        val createdNs = System.nanoTime()
        val matrix = FloatArray(16)
        var surfaceReleased = false
        var consumerReleased = false
    }
    private class Output(val surface: Surface, val ownsSurface: Boolean, var width: Int,
                         var height: Int, val fps: Int, val onFrame: () -> Unit,
                         val onError: (String) -> Unit) {
        var egl = EGL14.EGL_NO_SURFACE
        var deadlineNs = 0.0
    }
    private class Camera(val id: String, val rotation: Int, val textureRotation: Int, val crop: Rect,
                         val mirrored: Boolean, val onFirstFrame: () -> Unit) {
        var notified = false
    }

    init {
        if (!gl.post {
            try { initialize() }
            catch (error: Exception) { report(error) }
        }) main.post { onError("GPU renderer could not start") }
    }

    fun createInput(id: String, width: Int, height: Int,
                    onFailure: (String) -> Unit = { onError(it) }, callback: (GpuTextureInput) -> Unit) {
        if (!gl.post {
            if (closing || failed) {
                main.post { onFailure("GPU input is not available") }
                return@post
            }
            var createdTexture = 0
            var createdConsumer: SurfaceTexture? = null
            var createdSurface: Surface? = null
            var committed = false
            try {
                require(width > 0 && height > 0)
                check(!inputs.containsKey(id)) { "GPU input identity already exists" }
                makeCurrent(scratch)
                val maximum = IntArray(1)
                GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maximum, 0)
                require(width <= maximum[0] && height <= maximum[0]) { "Browser viewport exceeds GPU texture limits" }
                val textures = IntArray(1)
                GLES20.glGenTextures(1, textures, 0)
                createdTexture = textures[0]
                check(createdTexture != 0) { "GPU texture allocation failed" }
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                val consumer = SurfaceTexture(textures[0])
                createdConsumer = consumer
                consumer.setDefaultBufferSize(width, height)
                val surface = Surface(consumer)
                createdSurface = surface
                val input = Input(id, textures[0], consumer, surface, width, height)
                Log.i(TAG, "GPU input allocated: $id ${width}x${height}")
                consumer.setOnFrameAvailableListener({
                    if (inputs[id] !== input || closed) return@setOnFrameAvailableListener
                    input.dirty = true
                    requestTick()
                }, gl)
                inputs[id] = input
                committed = true
                main.post { callback(GpuTextureInput(id, input.surface)) }
            } catch (error: Exception) {
                if (!committed) {
                    // No producer has received these resources yet.
                    try { createdSurface?.release(); createdConsumer?.release() }
                    catch (cleanupError: Exception) { Log.w(TAG, "GPU input allocation cleanup failed", cleanupError) }
                    if (createdTexture != 0 && EGL14.eglGetCurrentContext() == eglContext) {
                        GLES20.glDeleteTextures(1, intArrayOf(createdTexture), 0)
                    }
                }
                report(error)
                main.post { onFailure("GPU input could not be allocated") }
            }
        }) main.post { onFailure("GPU input renderer has closed") }
    }

    fun resizeInput(id: String, width: Int, height: Int) {
        gl.post {
            if (failed || closing) return@post
            val input = inputs[id] ?: return@post
            try {
                require(width > 0 && height > 0)
                input.consumer.setDefaultBufferSize(width, height)
                input.width = width; input.height = height
            } catch (error: Exception) { report(error) }
        }
    }

    fun releaseInput(id: String) {
        gl.post {
            val input = inputs[id] ?: return@post
            if (camera?.id == id) camera = null
            try {
                // CameraX/display producer has finished. Surface/consumer cleanup
                // does not require a live EGL context; retain the texture until GL confirms cleanup.
                if (!input.consumerReleased) input.consumer.setOnFrameAvailableListener(null)
                if (!input.surfaceReleased) { input.surface.release(); input.surfaceReleased = true }
                if (!input.consumerReleased) { input.consumer.release(); input.consumerReleased = true }
                makeCurrent(scratch)
                GLES20.glDeleteTextures(1, intArrayOf(input.texture), 0)
                inputs.remove(id)
                finishCloseIfIdle()
            } catch (error: Exception) { report(error) }
        }
    }

    fun setLayers(value: List<GpuBrowserLayer>) {
        val snapshot = value.toList()
        gl.post { layers = snapshot; requestTick() }
    }

    fun setCamera(id: String, rotationDegrees: Int, cropRect: Rect, mirrored: Boolean,
                  textureRotationDegrees: Int = rotationDegrees,
                  onFirstFrame: () -> Unit) {
        val crop = Rect(cropRect)
        gl.post {
            val previous = camera
            camera = Camera(id, rotationDegrees, textureRotationDegrees, crop, mirrored, onFirstFrame).also {
                if (previous?.id == id) it.notified = previous.notified
            }
            requestTick()
        }
    }

    fun clearCamera() { gl.post { camera = null } }
    fun setDiagnostics(enabled: Boolean) { gl.post { diagnostics = enabled } }

    fun addOutput(surface: Surface, width: Int, height: Int, fps: Int,
                  onFrame: () -> Unit, onError: (String) -> Unit): GpuOutput {
        val token = GpuOutput()
        gl.post {
            if (closing || failed || !token.active.get()) {
                main.post { onError("GPU renderer is not available") }
                return@post
            }
            try {
                val output = Output(surface, false, width, height, fps.coerceIn(1, 120), onFrame, onError)
                output.egl = windowSurface(surface)
                outputs[token] = output
                requestTick()
            } catch (error: Exception) {
                main.post { onError("GPU output could not be created") }
                Log.e(TAG, "GPU output failed", error)
            }
        }
        return token
    }

    fun removeOutput(token: GpuOutput, onDrained: () -> Unit) {
        token.active.set(false)
        if (!gl.post {
            try {
                // No producer may write to an encoder surface after this acknowledgement.
                if (outputs.containsKey(token)) {
                    makeCurrent(scratch)
                    GLES20.glFinish()
                    outputs[token]?.let { output ->
                        releaseOutput(output)
                        outputs.remove(token)
                    }
                }
                main.post(onDrained)
                finishCloseIfIdle()
            } catch (error: Exception) { report(error) }
        }) {
            if (closed) main.post(onDrained)
            else main.post { onError("GPU output drain could not be confirmed") }
        }
    }

    fun attachPreview(texture: SurfaceTexture, width: Int, height: Int) {
        gl.post {
            if (closing || failed) return@post
            try {
                preview?.let { releaseOutput(it) }
                val next = Output(Surface(texture), true, width, height, 30, {}, onError)
                next.egl = windowSurface(next.surface)
                preview = next
                requestTick()
            } catch (error: Exception) { report(error) }
        }
    }

    fun resizePreview(width: Int, height: Int) {
        gl.post { preview?.let { it.width = width; it.height = height } }
    }

    fun detachPreview(onDetached: () -> Unit) {
        if (!gl.post {
            try {
                preview?.let {
                    makeCurrent(scratch); GLES20.glFinish(); releaseOutput(it)
                    preview = null
                }
                main.post(onDetached)
                finishCloseIfIdle()
            } catch (error: Exception) { report(error) }
        }) {
            if (closed) main.post(onDetached)
            else main.post { onError("GPU preview detach could not be confirmed") }
        }
    }

    fun setPreviewVisible(visible: Boolean) {
        gl.post { previewVisible = visible; if (visible) requestTick() }
    }

    fun close() {
        gl.post {
            closing = true
            try {
                preview?.let { releaseOutput(it); preview = null }
                finishCloseIfIdle()
            } catch (error: Exception) { report(error) }
        }
    }

    private fun initialize() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY)
        val versions = IntArray(2)
        check(EGL14.eglInitialize(display, versions, 0, versions, 1))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attrs = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            0x3142, 1, EGL14.EGL_NONE)
        check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0)
        config = configs[0]
        eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT)
        scratch = EGL14.eglCreatePbufferSurface(display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(scratch != EGL14.EGL_NO_SURFACE)
        makeCurrent(scratch)
        val vertex = shader(GLES20.GL_VERTEX_SHADER, """
            attribute vec2 aPosition;
            attribute vec2 aCoordinate;
            uniform mat4 uTransform;
            varying vec2 vCoordinate;
            void main() { gl_Position = vec4(aPosition, 0.0, 1.0);
                vCoordinate = (uTransform * vec4(aCoordinate, 0.0, 1.0)).xy; }
        """.trimIndent())
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER, """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            uniform float uOpacity;
            varying vec2 vCoordinate;
            void main() { gl_FragColor = texture2D(uTexture, vCoordinate) * uOpacity; }
        """.trimIndent())
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { GLES20.glGetProgramInfoLog(program) }
        positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        coordinateLocation = GLES20.glGetAttribLocation(program, "aCoordinate")
        transformLocation = GLES20.glGetUniformLocation(program, "uTransform")
        opacityLocation = GLES20.glGetUniformLocation(program, "uOpacity")
        samplerLocation = GLES20.glGetUniformLocation(program, "uTexture")
        check(listOf(positionLocation, coordinateLocation, transformLocation, opacityLocation, samplerLocation).all { it >= 0 })
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
        Log.i(TAG, "GPU scene initialized: OES inputs, native texture composition, no bitmap readback")
    }

    private fun requestTick() {
        if (closed || failed || tickPosted || (outputs.isEmpty() && (!previewVisible || preview == null))) return
        tickPosted = true
        gl.post(tick)
    }

    private fun renderTick() {
        tickPosted = false
        if (closed || failed) return
        try {
            makeCurrent(scratch)
            inputs.values.forEach { input ->
                if (input.dirty) {
                    input.dirty = false
                    input.consumer.updateTexImage()
                    input.consumer.getTransformMatrix(input.matrix)
                    input.ready = true
                }
            }
            if (diagnostics) inputs.values.filter { it.id.startsWith("browser:") && it.ready &&
                !it.probed && System.nanoTime() - it.createdNs > 1_000_000_000L }.forEach { probeAlpha(it) }
            val currentCamera = camera
            if (currentCamera != null && inputs[currentCamera.id]?.ready == true && !currentCamera.notified) {
                currentCamera.notified = true
                main.post(currentCamera.onFirstFrame)
            }
            val now = System.nanoTime()
            var next = Double.POSITIVE_INFINITY
            val targets = outputs.entries.filter { it.key.active.get() }.map { it.value } +
                listOfNotNull(preview?.takeIf { previewVisible })
            for (target in targets) {
                if (target.width <= 0 || target.height <= 0) continue
                if (now >= target.deadlineNs) {
                    render(target, now)
                    val interval = 1_000_000_000.0 / target.fps
                    if (target.deadlineNs == 0.0) target.deadlineNs = now.toDouble()
                    target.deadlineNs += interval
                    if (target.deadlineNs <= now) target.deadlineNs = now + interval
                    if (outputs.values.any { it === target }) { outputFrames++; main.post(target.onFrame) }
                }
                next = min(next, target.deadlineNs)
            }
            sceneFrames++
            if (now - lastMetricsNs >= 10_000_000_000L) {
                Log.d(TAG, "sceneTicks=$sceneFrames outputFrames=$outputFrames browserTextures=${layers.size}")
                sceneFrames = 0; outputFrames = 0; lastMetricsNs = now
            }
            if (next.isFinite() && !tickPosted) {
                tickPosted = true
                gl.postDelayed(tick, max(1L, ceil((next - System.nanoTime()) / 1_000_000.0).toLong()))
            }
        } catch (error: Exception) { report(error) }
    }

    private fun render(output: Output, now: Long) {
        makeCurrent(output.egl)
        GLES20.glViewport(0, 0, output.width, output.height)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        camera?.let { item -> inputs[item.id]?.takeIf { it.ready }?.let { input ->
            draw(input, 0f, 0f, output.width.toFloat(), output.height.toFloat(), output,
                cameraCoordinates(input, item, output), 1f)
        } }
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        layers.forEach { layer ->
            val input = inputs[layer.id]?.takeIf { it.ready } ?: return@forEach
            val left = position(layer.x, output.width)
            val top = position(layer.y, output.height)
            val width = size(layer.width, output.width)
            val height = size(layer.height, output.height)
            val x0 = max(0, floor(left).toInt()); val x1 = min(output.width, ceil(left + width).toInt())
            val y0 = max(0, floor(top).toInt()); val y1 = min(output.height, ceil(top + height).toInt())
            if (x1 <= x0 || y1 <= y0 || width <= 0 || height <= 0) return@forEach
            GLES20.glScissor(x0, output.height - y1, x1 - x0, y1 - y0)
            val fit = min(width / layer.viewportWidth, height / layer.viewportHeight) * layer.zoom
            draw(input, left, top, layer.viewportWidth * fit, layer.viewportHeight * fit, output,
                floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), layer.opacity)
        }
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        if (!output.ownsSurface) EGLExt.eglPresentationTimeANDROID(display, output.egl, now)
        check(EGL14.eglSwapBuffers(display, output.egl)) { "GPU surface submission failed" }
    }

    private fun cameraCoordinates(input: Input, value: Camera, output: Output): FloatArray {
        val crop = value.crop.takeIf { it.width() > 0 && it.height() > 0 } ?: Rect(0, 0, input.width, input.height)
        val quarterTurn = value.rotation % 180 != 0
        val sourceAspect = if (quarterTurn) crop.height().toFloat() / crop.width() else crop.width().toFloat() / crop.height()
        val destinationAspect = output.width.toFloat() / output.height
        val cropU = min(1f, destinationAspect / sourceAspect)
        val cropV = min(1f, sourceAspect / destinationAspect)
        return floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f).also { result ->
            for (index in 0 until 4) {
                var u = 0.5f + (result[index * 2] - 0.5f) * cropU
                val v = 0.5f + (result[index * 2 + 1] - 0.5f) * cropV
                if (value.mirrored) u = 1f - u
                // Aspect/crop uses buffer rotation; OES sampling uses only the
                // correction not already carried by SurfaceTexture's matrix.
                val rotated = when ((value.textureRotation % 360 + 360) % 360) {
                    90 -> floatArrayOf(v, 1f - u)
                    180 -> floatArrayOf(1f - u, 1f - v)
                    270 -> floatArrayOf(1f - v, u)
                    else -> floatArrayOf(u, v)
                }
                result[index * 2] = (crop.left + rotated[0] * crop.width()) / input.width
                result[index * 2 + 1] = (crop.top + rotated[1] * crop.height()) / input.height
            }
        }
    }

    // Opt-in diagnostic only: two 1-pixel reads prove alpha survives the native
    // display producer. Production rendering never reads images back to the CPU.
    private fun probeAlpha(input: Input) {
        val target = preview ?: outputs.values.firstOrNull() ?: return
        makeCurrent(scratch)
        GLES20.glViewport(0, 0, 1, 1)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        input.probed = true
        for (v in floatArrayOf(0.2f, 0.8f)) {
            GLES20.glClearColor(0f, 0f, 0f, 0f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            draw(input, 0f, 0f, target.width.toFloat(), target.height.toFloat(), target,
                floatArrayOf(0.5f, v, 0.5f, v, 0.5f, v, 0.5f, v), 1f)
            val pixel = ByteBuffer.allocateDirect(4)
            GLES20.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixel)
            Log.i(TAG, "GPU_ALPHA_PROBE id=${input.id} v=$v rgba=${pixel.get(0).toInt() and 255},${pixel.get(1).toInt() and 255},${pixel.get(2).toInt() and 255},${pixel.get(3).toInt() and 255}")
        }
    }

    private fun draw(input: Input, left: Float, top: Float, width: Float, height: Float,
                     output: Output, uv: FloatArray, opacity: Float) {
        val x0 = left / output.width * 2f - 1f; val x1 = (left + width) / output.width * 2f - 1f
        val y0 = 1f - (top + height) / output.height * 2f; val y1 = 1f - top / output.height * 2f
        vertices.clear(); vertices.put(floatArrayOf(x0, y0, x1, y0, x0, y1, x1, y1)); vertices.position(0)
        coordinates.clear(); coordinates.put(uv); coordinates.position(0)
        GLES20.glUseProgram(program)
        val position = positionLocation
        val coordinate = coordinateLocation
        GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(coordinate)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 0, coordinates)
        GLES20.glUniformMatrix4fv(transformLocation, 1, false, input.matrix, 0)
        GLES20.glUniform1f(opacityLocation, opacity.coerceIn(0f, 1f))
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, input.texture)
        GLES20.glUniform1i(samplerLocation, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun windowSurface(surface: Surface): EGLSurface {
        check(surface.isValid)
        return EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0).also {
            check(it != EGL14.EGL_NO_SURFACE) { "GPU window surface could not be created" }
        }
    }
    private fun makeCurrent(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, surface, surface, eglContext)) { "GPU context is unavailable" }
    }
    private fun releaseOutput(value: Output) {
        makeCurrent(scratch)
        check(EGL14.eglDestroySurface(display, value.egl)) { "GPU surface release could not be confirmed" }
        if (value.ownsSurface) value.surface.release()
    }
    private fun report(error: Exception) {
        failed = true; gl.removeCallbacks(tick); tickPosted = false
        Log.e(TAG, "GPU scene failed", error)
        main.post { onError("Native GPU rendering failed: ${error.message ?: "unknown error"}") }
        outputs.values.forEach { output -> main.post { output.onError("Native GPU output failed") } }
    }
    private fun finishCloseIfIdle() {
        if (!closing || outputs.isNotEmpty() || inputs.isNotEmpty() || closed) return
        gl.removeCallbacks(tick)
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            if (scratch != EGL14.EGL_NO_SURFACE) {
                makeCurrent(scratch); GLES20.glFinish(); GLES20.glDeleteProgram(program)
            }
            check(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT))
            if (scratch != EGL14.EGL_NO_SURFACE) {
                check(EGL14.eglDestroySurface(display, scratch))
                scratch = EGL14.EGL_NO_SURFACE
            }
            check(EGL14.eglDestroyContext(display, eglContext))
            eglContext = EGL14.EGL_NO_CONTEXT
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            check(EGL14.eglTerminate(display))
            display = EGL14.EGL_NO_DISPLAY
        }
        closed = true
        thread.quitSafely()
    }
    private fun shader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader)
        val status = IntArray(1); GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] != 0) { GLES20.glGetShaderInfoLog(shader) }
        return shader
    }
    companion object {
        private const val TAG = "BrowserCamGpu"
        private fun buffer() = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private fun size(value: Float, base: Int) = if (value in 0f..1f) value * base else value
        private fun position(value: Float, base: Int) = if (value in -1f..1f) value * base else value
        fun fromView(view: View): GpuSceneRenderer? {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (view is GpuSceneView) return view.renderer
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                fromView(view.getChildAt(index))?.let { return it }
            }
            return null
        }
    }
}

internal class GpuSceneView(context: Context, val renderer: GpuSceneRenderer) : TextureView(context), TextureView.SurfaceTextureListener {
    init { isOpaque = true; surfaceTextureListener = this }
    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        renderer.attachPreview(texture, width, height)
    }
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        renderer.resizePreview(width, height)
    }
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        renderer.detachPreview { texture.release() }
        return false
    }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (isAvailable) renderer.setPreviewVisible(isShown && visibility == View.VISIBLE)
    }
}
