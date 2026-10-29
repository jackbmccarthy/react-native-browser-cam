package com.webcamstream

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.pedro.encoder.input.sources.video.VideoSource

/** Owns only the Canvas producer; RootEncoder owns the texture and GL consumer. */
class CanvasVideoSource : VideoSource() {
    private val producerLock = Any()
    private var producer: Surface? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val destination = Rect()
    // OpenScoreboard Android GPU publisher output v1.
    private val gpuHandler = Handler(Looper.getMainLooper())
    private var gpuScene: GpuSceneRenderer? = null
    private var gpuOutput: GpuOutput? = null
    private var gpuGeneration = 0L
    private var gpuStopping = false
    private var gpuOnFrame: (() -> Unit)? = null
    private var gpuOnError: ((String) -> Unit)? = null
    private val gpuStopWaiters = mutableListOf<() -> Unit>()

    val usesGpu: Boolean
        get() = synchronized(producerLock) { gpuScene != null }

    internal fun configureGpu(scene: GpuSceneRenderer, onFrame: () -> Unit, onError: (String) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        synchronized(producerLock) {
            check(gpuScene == null && producer == null && !gpuStopping)
            gpuGeneration++
            gpuScene = scene
            gpuOnFrame = onFrame
            gpuOnError = onError
        }
    }

    private fun startGpu(scene: GpuSceneRenderer, generation: Long, surfaceTexture: SurfaceTexture) {
        check(Looper.myLooper() == Looper.getMainLooper())
        synchronized(producerLock) {
            if (generation != gpuGeneration || gpuStopping || gpuScene !== scene) return
            try {
                check(producer == null && gpuOutput == null) { "GPU producer already started" }
                surfaceTexture.setDefaultBufferSize(width, height)
                val surface = Surface(surfaceTexture)
                producer = surface
                gpuOutput = scene.addOutput(surface, width, height, fps, {
                    check(Looper.myLooper() == Looper.getMainLooper())
                    if (generation == gpuGeneration && !gpuStopping) gpuOnFrame?.invoke()
                }, {
                    check(Looper.myLooper() == Looper.getMainLooper())
                    if (generation == gpuGeneration && !gpuStopping) {
                        gpuOnError?.invoke("Native GPU publisher output failed")
                    }
                })
            } catch (error: Exception) {
                gpuOnError?.invoke("Native GPU publisher output failed")
            }
        }
    }

    fun stopGpuAndWait(onDrained: () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        synchronized(producerLock) {
            gpuStopWaiters.add(onDrained)
            if (gpuStopping) return
            gpuStopping = true
            gpuGeneration++ // Cancels queued starts and callbacks before touching the producer.
            val scene = gpuScene
            val output = gpuOutput
            val finish: () -> Unit = {
                check(Looper.myLooper() == Looper.getMainLooper())
                synchronized(producerLock) {
                    // Only our Surface wrapper is owned here, never Root's SurfaceTexture.
                    try {
                        producer?.release()
                        producer = null
                        gpuOutput = null
                        val waiters = gpuStopWaiters.toList()
                        gpuStopWaiters.clear()
                        gpuStopping = false
                        waiters.forEach { it() }
                    } catch (error: Exception) {
                        gpuOnError?.invoke("Native GPU publisher teardown failed")
                    }
                }
            }
            if (scene != null && output != null) {
                try {
                    scene.removeOutput(output) {
                        check(Looper.myLooper() == Looper.getMainLooper())
                        if (gpuStopping && gpuOutput === output) finish()
                    }
                } catch (error: Exception) {
                    // Keep the output, Surface and waiters fenced when drain is unconfirmed.
                    gpuOnError?.invoke("Native GPU publisher teardown failed")
                }
            } else finish()
        }
    }

    override fun create(width: Int, height: Int, fps: Int, rotation: Int): Boolean {
        return width > 0 && height > 0 && fps > 0
    }

    override fun start(surfaceTexture: SurfaceTexture) {
        synchronized(producerLock) {
            val scene = gpuScene
            if (scene != null) {
                val generation = gpuGeneration
                // RootEncoder may start on a worker; renderer registration belongs to Main.
                if (Looper.myLooper() == Looper.getMainLooper()) startGpu(scene, generation, surfaceTexture)
                else gpuHandler.post { startGpu(scene, generation, surfaceTexture) }
                return
            }
            producer?.release()
            surfaceTexture.setDefaultBufferSize(width, height)
            producer = Surface(surfaceTexture)
        }
    }

    // Called exclusively by the serialized writer. Inputs are immutable snapshots.
    fun drawFrame(camera: Bitmap?, overlay: Bitmap?): Boolean {
        return synchronized(producerLock) {
            if (gpuScene != null) return@synchronized false
            val surface = producer ?: return@synchronized false
            if (!surface.isValid) return@synchronized false
            val canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                surface.lockHardwareCanvas()
            } else {
                surface.lockCanvas(null)
            }
            try {
                canvas.drawColor(Color.BLACK)
                destination.set(0, 0, canvas.width, canvas.height)
                camera?.let { canvas.drawBitmap(it, null, destination, paint) }
                overlay?.let { canvas.drawBitmap(it, null, destination, paint) }
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
            true
        }
    }

    override fun stop() {
        synchronized(producerLock) {
            if (gpuScene != null) {
                if (Looper.myLooper() == Looper.getMainLooper()) stopGpuAndWait {}
                else gpuHandler.post { stopGpuAndWait {} }
                return
            }
            producer?.release()
            producer = null
        }
    }

    override fun release() = stop()

    override fun isRunning(): Boolean = synchronized(producerLock) { producer?.isValid == true }
}
