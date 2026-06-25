package com.webcamstream

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance local video recorder using MediaCodec + MediaMuxer directly.
 *
 * Performance optimizations:
 *  - Renders directly to the encoder's input Surface (zero-copy for encoding)
 *  - Caches view references to avoid tree walks every frame
 *  - Uses lockHardwareCanvas() on API 26+ for GPU-accelerated rendering
 *  - Uses Choreographer for vsync-aligned frame timing
 *  - Throttles overlay redraws to ~10fps while camera runs at full speed
 *  - Paint with FILTER_BITMAP_FLAG for fast bitmap scaling
 */
class LocalRecorder(
    private val sourceView: View,
    private val width: Int = 720,
    private val height: Int = 1280,
    private val fps: Int = 30,
    private val bitrate: Int = 4_000_000
) {
    companion object {
        private const val TAG = "LocalRecorder"
        private const val MIME_TYPE = "video/avc"
        private const val I_FRAME_INTERVAL = 2
        private const val TIMEOUT_US = 10_000L
        private const val DRAIN_STOP_TIMEOUT_MS = 3_000L
    }

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var videoTrackIndex = -1
    private val muxerStarted = AtomicBoolean(false)
    private val isRecording = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private var drainComplete = CountDownLatch(0)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var drainThread: HandlerThread? = null
    private var drainHandler: Handler? = null
    private var pixelCopyThread: HandlerThread? = null
    private var pixelCopyHandler: Handler? = null

    private var frameCount = 0
    private var outputPath: String = ""

    // Reusable bitmap for overlay (cached between frames)
    private var lastOverlayBitmap: Bitmap? = null

    // Cached view references — found once, reused every frame
    private var cachedCameraView: View? = null
    private var cachedHardwareView: View? = null  // TextureView or SurfaceView inside camera
    private var cachedOverlayView: View? = null
    private var viewsCached = false

    // Reusable paint for fast bitmap scaling
    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // Frame timing
    private val frameIntervalNs = (1_000_000_000L / fps.coerceAtLeast(1))
    private var lastFrameTimeNs = 0L
    private val choreographerCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isRecording.get()) return

            // Throttle to target FPS
            if (frameTimeNanos - lastFrameTimeNs >= frameIntervalNs) {
                lastFrameTimeNs = frameTimeNanos
                captureFrame()
            }

            // Schedule next vsync
            if (isRecording.get()) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    fun start(path: String) {
        if (isRecording.get()) {
            Log.w(TAG, "Already recording")
            return
        }

        outputPath = path
        val parentDir = File(path).parentFile
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                throw IllegalStateException("Unable to create recording directory: ${parentDir.absolutePath}")
            }
        }

        try {
            stopRequested.set(false)
            drainComplete = CountDownLatch(1)

            // 1. Configure encoder
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                // Lower latency profile for smoother encoding
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                }
            }

            val enc = MediaCodec.createEncoderByType(MIME_TYPE)
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = enc.createInputSurface()
            enc.start()
            encoder = enc
            Log.d(TAG, "Encoder started: ${width}x${height} @ ${bitrate}bps, ${fps}fps")

            // 2. Create muxer
            muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            videoTrackIndex = -1
            muxerStarted.set(false)

            // 3. Start drain thread
            drainThread = HandlerThread("LocalRecDrain").also { it.start() }
            drainHandler = Handler(drainThread!!.looper)

            pixelCopyThread = HandlerThread("LocalRecPixelCopy").also { it.start() }
            pixelCopyHandler = Handler(pixelCopyThread!!.looper)

            // 4. Reset state
            frameCount = 0
            lastFrameTimeNs = 0L
            viewsCached = false
            cachedCameraView = null
            cachedHardwareView = null
            cachedOverlayView = null
            isRecording.set(true)

            // 5. Start drain loop
            drainHandler?.post { drainLoop() }

            // 6. Start capture via Choreographer (vsync-aligned)
            Choreographer.getInstance().postFrameCallback(choreographerCallback)

            Log.d(TAG, "Recording started: $path")
        } catch (e: Exception) {
            isRecording.set(false)
            stopRequested.set(true)
            Log.e(TAG, "Failed to start recording", e)
            cleanup()
            drainComplete.countDown()
            throw e
        }
    }

    fun stop(): String {
        if (!isRecording.getAndSet(false)) {
            return outputPath
        }

        Log.d(TAG, "Stopping recording ($frameCount frames captured)")
        stopRequested.set(true)

        // Remove choreographer callbacks
        Choreographer.getInstance().removeFrameCallback(choreographerCallback)
        mainHandler.removeCallbacksAndMessages(null)

        try {
            encoder?.signalEndOfInputStream()
        } catch (e: Exception) {
            Log.w(TAG, "Error signaling EOS", e)
        }

        if (!drainComplete.await(DRAIN_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "Timed out waiting for encoder drain")
        }

        cleanup()

        val file = File(outputPath)
        Log.d(TAG, "Recording stopped. File: $outputPath, size: ${file.length()} bytes")
        if (!file.exists() || file.length() <= 0L) {
            throw IllegalStateException("Recording produced an empty file: $outputPath")
        }
        return outputPath
    }

    private fun cleanup() {
        try {
            if (muxerStarted.getAndSet(false)) {
                muxer?.stop()
            }
            muxer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Muxer cleanup error", e)
        }
        muxer = null

        try {
            encoder?.stop()
            encoder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Encoder cleanup error", e)
        }
        encoder = null

        inputSurface?.release()
        inputSurface = null

        drainThread?.quitSafely()
        drainThread = null
        drainHandler = null

        pixelCopyThread?.quitSafely()
        pixelCopyThread = null
        pixelCopyHandler = null

        lastOverlayBitmap?.recycle()
        lastOverlayBitmap = null

        cachedCameraView = null
        cachedHardwareView = null
        cachedOverlayView = null
        viewsCached = false
    }

    // ========== FRAME CAPTURE ==========

    private fun captureFrame() {
        if (!isRecording.get()) return

        val surface = inputSurface ?: return
        val viewWidth = sourceView.width
        val viewHeight = sourceView.height
        if (viewWidth <= 0 || viewHeight <= 0) return

        // Cache view references (only walk tree once, then reuse)
        if (!viewsCached) {
            cachedCameraView = findViewByType(sourceView, "PreviewView")
            cachedOverlayView = findViewByType(sourceView, "OverlayCanvasView")
                ?: findViewByType(sourceView, "WebView")
            if (cachedCameraView != null) {
                cachedHardwareView = findViewByType(cachedCameraView!!, "TextureView")
                    ?: findViewByType(cachedCameraView!!, "SurfaceView")
            }
            viewsCached = true
            Log.d(TAG, "Cached views: camera=${cachedCameraView?.javaClass?.simpleName}, " +
                "hw=${cachedHardwareView?.javaClass?.simpleName}, " +
                "overlay=${cachedOverlayView?.javaClass?.simpleName}")
        }

        val hwView = cachedHardwareView

        // Fast path: TextureView — render directly to encoder surface
        if (hwView is TextureView) {
            val camBmp = hwView.bitmap ?: return
            renderDirectToEncoder(surface, camBmp, viewWidth, viewHeight)
            return
        }

        // SurfaceView path: use PixelCopy (async)
        if (hwView is SurfaceView && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val sv = hwView.holder.surface
            if (sv != null && sv.isValid) {
                // Create a temp bitmap for PixelCopy target
                val tempBmp = Bitmap.createBitmap(hwView.width, hwView.height, Bitmap.Config.ARGB_8888)
                PixelCopy.request(hwView, tempBmp, { result ->
                    if (!isRecording.get()) {
                        tempBmp.recycle()
                        return@request
                    }
                    if (result == PixelCopy.SUCCESS) {
                        mainHandler.post {
                            renderDirectToEncoder(surface, tempBmp, viewWidth, viewHeight)
                            tempBmp.recycle()
                        }
                    } else {
                        tempBmp.recycle()
                    }
                }, pixelCopyHandler!!)
                return
            }
        }

        // Fallback: draw the whole view (this captures everything including UI)
        renderFallbackToEncoder(surface, viewWidth, viewHeight)
    }

    /**
     * Render camera bitmap + overlay directly to encoder surface.
     * No intermediate full-size bitmap copy needed.
     */
    private fun renderDirectToEncoder(encoderSurface: Surface, cameraBitmap: Bitmap, viewWidth: Int, viewHeight: Int) {
        try {
            // Use hardware canvas on API 26+ for GPU acceleration
            val canvas: Canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                encoderSurface.lockHardwareCanvas()
            } else {
                encoderSurface.lockCanvas(null)
            } ?: return

            // Draw camera scaled to fill encoder dimensions
            canvas.drawBitmap(cameraBitmap, null, Rect(0, 0, width, height), scalePaint)

            // Draw overlay on top (throttled)
            drawOverlayToCanvas(canvas, viewWidth, viewHeight)

            encoderSurface.unlockCanvasAndPost(canvas)

            frameCount++
            if (frameCount % 60 == 0) {
                Log.d(TAG, "Rendered $frameCount frames")
            }
        } catch (e: Exception) {
            Log.e(TAG, "renderDirectToEncoder failed", e)
        }
    }

    /**
     * Fallback: draw the entire source view to the encoder surface.
     */
    private fun renderFallbackToEncoder(encoderSurface: Surface, viewWidth: Int, viewHeight: Int) {
        try {
            val canvas: Canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                encoderSurface.lockHardwareCanvas()
            } else {
                encoderSurface.lockCanvas(null)
            } ?: return

            canvas.drawColor(Color.BLACK)
            // Scale view to encoder dimensions
            val sx = width.toFloat() / viewWidth
            val sy = height.toFloat() / viewHeight
            canvas.save()
            canvas.scale(sx, sy)
            sourceView.draw(canvas)
            canvas.restore()

            encoderSurface.unlockCanvasAndPost(canvas)

            frameCount++
        } catch (e: Exception) {
            Log.e(TAG, "renderFallbackToEncoder failed", e)
        }
    }

    /**
     * Draw the cached overlay bitmap onto the encoder canvas.
     * Only redraws the overlay from the WebView every 3 frames for performance.
     */
    private fun drawOverlayToCanvas(canvas: Canvas, viewWidth: Int, viewHeight: Int) {
        val overlayView = cachedOverlayView ?: return

        val shouldRedraw = frameCount % 3 == 0 || lastOverlayBitmap == null

        if (shouldRedraw) {
            val ovBmp = lastOverlayBitmap?.takeIf { it.width == viewWidth && it.height == viewHeight }
                ?: Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888).also {
                    lastOverlayBitmap?.recycle()
                    lastOverlayBitmap = it
                }

            val ovCanvas = Canvas(ovBmp)
            ovCanvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            try {
                overlayView.draw(ovCanvas)
            } catch (e: Exception) {
                Log.w(TAG, "Overlay draw error", e)
            }
        }

        lastOverlayBitmap?.let {
            canvas.drawBitmap(it, null, Rect(0, 0, width, height), scalePaint)
        }
    }

    // ========== ENCODER DRAIN ==========

    private fun drainLoop() {
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            while (true) {
                val enc = encoder ?: break

                val outputIndex = try {
                    enc.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                } catch (e: Exception) {
                    Log.w(TAG, "dequeueOutputBuffer error", e)
                    break
                }

                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = enc.outputFormat
                        Log.d(TAG, "Encoder output format: $newFormat")
                        val mux = muxer ?: break
                        videoTrackIndex = mux.addTrack(newFormat)
                        mux.start()
                        muxerStarted.set(true)
                        Log.d(TAG, "Muxer started, track=$videoTrackIndex")
                    }

                    outputIndex >= 0 -> {
                        val outputBuffer = enc.getOutputBuffer(outputIndex)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }

                        if (outputBuffer != null && bufferInfo.size > 0 && muxerStarted.get()) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer?.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                        }

                        enc.releaseOutputBuffer(outputIndex, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            Log.d(TAG, "End of stream received")
                            break
                        }
                    }

                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!isRecording.get() && !stopRequested.get()) break
                    }
                }
            }
        } finally {
            drainComplete.countDown()
            Log.d(TAG, "Drain loop ended")
        }
    }

    // ========== VIEW HELPERS ==========

    private fun findViewByType(root: View, typeName: String): View? {
        if (root.javaClass.name.contains(typeName)) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findViewByType(root.getChildAt(i), typeName)
                if (found != null) return found
            }
        }
        return null
    }
}
