package com.webcamstream

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
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
import androidx.core.content.ContextCompat
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

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
    private val bitrate: Int = 4_000_000,
    private val audioEnabled: Boolean = false,
    private val audioBitrate: Int = 128_000,
    private val audioSampleRate: Int = 44_100,
    private val onAudioLevel: ((level: Double, decibels: Double) -> Unit)? = null
) {
    companion object {
        private const val TAG = "LocalRecorder"
        private const val VIDEO_MIME_TYPE = "video/avc"
        private const val AUDIO_MIME_TYPE = "audio/mp4a-latm"
        private const val AUDIO_CHANNEL_COUNT = 1
        private const val AUDIO_BYTES_PER_SAMPLE = 2
        private const val I_FRAME_INTERVAL = 2
        private const val TIMEOUT_US = 10_000L
        private const val DRAIN_STOP_TIMEOUT_MS = 3_000L
        private const val MUXER_START_WAIT_MS = 1_500L
        private const val AUDIO_EOS_MAX_ATTEMPTS = 200
        private const val AUDIO_LEVEL_INTERVAL_MS = 80L
        private const val MIN_VALID_AUDIO_LEVEL = 0.000_5
        private const val MIN_DECIBELS = -60.0
    }

    private var encoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private val muxerStarted = AtomicBoolean(false)
    private val muxerLock = Object()
    private val isRecording = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private var drainComplete = CountDownLatch(0)
    private var expectedTrackCount = 1
    private var addedTrackCount = 0
    private var recordAudio = false
    private var audioInputBufferSize = 0
    // OpenScoreboard recording clock patch v1
    private var videoPtsOriginUs: Long? = null
    private var audioBytesSubmitted = 0L
    private var audioInputEosQueued = false
    private var audioOutputEos = false
    private var lastAudioLevelEmitMs = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var drainThread: HandlerThread? = null
    private var drainHandler: Handler? = null
    private var pixelCopyThread: HandlerThread? = null
    private var pixelCopyHandler: Handler? = null
    // OpenScoreboard recording capture bounds patch v8
    // Capture state is owned by Main; PixelCopy completions only post to Main.
    private var pixelCopyPending = false
    private var captureGeneration = 0L
    // OpenScoreboard Android GPU recording output v1; Main owns output admission.
    private var gpuScene: GpuSceneRenderer? = null
    private var gpuOutput: GpuOutput? = null
    private var frameInFlight = false
    private var overlayRevision = -1L
    private var overlayFrameSource: BrowserOverlayFrameSource? = null
    private var overlayOwner: View? = null
    private var writerThread: HandlerThread? = null
    private var writerHandler: Handler? = null
    private var videoDrainStarted = false
    private var finalizing = false
    private var finalized = false
    private var finalError: String? = null
    private var stopFailure: String? = null
    private val stopWaiters = mutableListOf<(String?, String?) -> Unit>()
    private val stopWatchdog = Runnable {
        if (finalizing) reportStopFailure("Recording finalization timed out; resources remain fenced")
    }
    val isFinalizing: Boolean
        get() = finalizing
    val isFinalized: Boolean
        get() = finalized

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
    private val frameIntervalNs = 1_000_000_000.0 / fps.coerceAtLeast(1)
    private var nextFrameDeadlineNs = 0.0
    private val choreographerCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isRecording.get()) return

            // Preserve configured FPS; a busy writer never queues another frame.
            if (!frameInFlight && frameTimeNanos >= kotlin.math.round(nextFrameDeadlineNs).toLong()) {
                captureFrame()
                if (frameInFlight) {
                    if (nextFrameDeadlineNs == 0.0) nextFrameDeadlineNs = frameTimeNanos.toDouble()
                    nextFrameDeadlineNs += frameIntervalNs
                    if (kotlin.math.round(nextFrameDeadlineNs).toLong() <= frameTimeNanos) {
                        nextFrameDeadlineNs += (kotlin.math.floor((frameTimeNanos - nextFrameDeadlineNs) / frameIntervalNs).coerceAtLeast(0.0) + 1.0) * frameIntervalNs
                        // Floating-point accumulation can land within half a nanosecond of this tick.
                        if (kotlin.math.round(nextFrameDeadlineNs).toLong() <= frameTimeNanos) nextFrameDeadlineNs += frameIntervalNs
                    }
                }
            }

            // Schedule next vsync
            if (isRecording.get()) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    fun start(path: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!finalizing && !finalized) { "Recorder is finalizing or already finalized; use a new instance" }
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
            captureGeneration++
            pixelCopyPending = false
            frameInFlight = false
            videoDrainStarted = false
            overlayRevision = -1L
            overlayOwner = null
            lastOverlayBitmap = null
            stopRequested.set(false)
            recordAudio = audioEnabled
            if (recordAudio && !hasAudioPermission()) {
                throw IllegalStateException("Microphone permission is required to record audio")
            }

            expectedTrackCount = if (recordAudio) 2 else 1
            addedTrackCount = 0
            videoTrackIndex = -1
            audioTrackIndex = -1
            audioInputBufferSize = 0
            videoPtsOriginUs = null
            audioBytesSubmitted = 0L
            audioInputEosQueued = false
            audioOutputEos = false
            lastAudioLevelEmitMs = 0L
            drainComplete = CountDownLatch(expectedTrackCount)

            // 1. Configure encoder
            val format = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, width, height).apply {
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

            val enc = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE)
            encoder = enc
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = enc.createInputSurface()
            enc.start()
            Log.d(TAG, "Encoder started: ${width}x${height} @ ${bitrate}bps, ${fps}fps")

            if (recordAudio) {
                audioRecord = createAudioRecord()
                audioEncoder = createAudioEncoder()
                audioRecord?.startRecording()
                Log.d(TAG, "Audio encoder started: ${audioSampleRate}Hz @ ${audioBitrate}bps")
            }

            // 2. Create muxer
            muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxerStarted.set(false)

            // 3. Start drain thread
            drainThread = HandlerThread("LocalRecDrain").also { it.start() }
            drainHandler = Handler(drainThread!!.looper)

            val scene = GpuSceneRenderer.fromView(sourceView)
            gpuScene = scene
            if (scene != null) {
                val hardwareAcceleration = if (Build.VERSION.SDK_INT >= 29) {
                    enc.codecInfo.isHardwareAccelerated.toString()
                } else "unknown"
                Log.d(TAG, "GPU recording encoder: ${enc.name}, hardwareAccelerated=$hardwareAcceleration")
            }
            if (scene == null) {
                pixelCopyThread = HandlerThread("LocalRecPixelCopy").also { it.start() }
                pixelCopyHandler = Handler(pixelCopyThread!!.looper)

                writerThread = HandlerThread("LocalRecWriter").also { it.start() }
                writerHandler = Handler(writerThread!!.looper)
            }

            // 4. Reset state
            frameCount = 0
            nextFrameDeadlineNs = 0.0
            viewsCached = false
            cachedCameraView = null
            cachedHardwareView = null
            cachedOverlayView = null
            isRecording.set(true)

            // 5. Start drain loop
            videoDrainStarted = drainHandler?.post { drainLoop() } == true
            check(videoDrainStarted) { "Unable to start video drain" }
            if (recordAudio) {
                startAudioLoop()
            }

            // GPU outputs consume renderer textures directly, never preview snapshots.
            if (scene != null) {
                val generation = captureGeneration
                gpuOutput = scene.addOutput(checkNotNull(inputSurface), width, height, fps, {
                    check(Looper.myLooper() == Looper.getMainLooper())
                    if (generation == captureGeneration && gpuScene === scene && isRecording.get()) {
                        frameCount++
                    }
                }, {
                    check(Looper.myLooper() == Looper.getMainLooper())
                    if (generation == captureGeneration && gpuScene === scene && isRecording.get()) {
                        finalError = "Recording could not be saved."
                        stop { _, error -> if (error != null) Log.w(TAG, error) }
                    }
                })
            } else {
                // 6. Start capture via Choreographer (vsync-aligned)
                Choreographer.getInstance().postFrameCallback(choreographerCallback)
            }

            Log.d(TAG, "Recording started: $path")
        } catch (e: Exception) {
            isRecording.set(false)
            stopRequested.set(true)
            Log.e(TAG, "Failed to start recording", e)
            // Count only workers that could not start; live workers retain their EOS duties.
            if (!videoDrainStarted) drainComplete.countDown()
            if (recordAudio && audioThread == null) drainComplete.countDown()
            finalError = "Recording could not be started."
            stop { _, error -> if (error != null) Log.w(TAG, error) }
            throw e
        }
    }

    fun stop(completion: (String?, String?) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (finalized) {
            completion(if (finalError == null) outputPath else null, finalError)
            return
        }
        if (finalizing && stopFailure != null) {
            completion(null, stopFailure)
            return
        }
        stopWaiters.add(completion)
        if (finalizing) return
        finalizing = true
        isRecording.set(false)
        stopRequested.set(true)
        captureGeneration++
        pixelCopyPending = false
        Choreographer.getInstance().removeFrameCallback(choreographerCallback)
        // Queued PixelCopy/writer completions must run and reject their stale generation.
        mainHandler.postDelayed(stopWatchdog, 30_000L)
        lastOverlayBitmap = null
        overlayOwner = null
        overlayRevision = -1L
        overlayFrameSource?.releaseBrowserOutput()
        overlayFrameSource = null
        cachedCameraView = null
        cachedHardwareView = null
        cachedOverlayView = null
        viewsCached = false
        val writer = writerThread
        writer?.quitSafely()
        val scene = gpuScene
        val output = gpuOutput
        if (scene != null && output != null) {
            try {
                // removeOutput acknowledges only after the GL detach/glFinish barrier.
                scene.removeOutput(output) {
                    check(Looper.myLooper() == Looper.getMainLooper())
                    if (gpuOutput === output && finalizing) {
                        gpuOutput = null
                        gpuScene = null
                        Thread({ finalizeRecording(writer) }, "LocalRecFinalize").start()
                    }
                }
            } catch (error: Exception) {
                // No confirmed drain means no EOS, release, or reopening of admission.
                reportStopFailure("Recording could not finish stopping.")
            }
        } else {
            gpuScene = null
            Thread({ finalizeRecording(writer) }, "LocalRecFinalize").start()
        }
    }

    private fun reportStopFailure(error: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!finalizing) return
        if (stopFailure == null) stopFailure = error
        val waiters = stopWaiters.toList()
        stopWaiters.clear()
        waiters.forEach { it(null, stopFailure) }
    }

    private fun finalizeRecording(writer: HandlerThread?) {
        try {
            var audioStopError: String? = null
            // Stop microphone input even if video submission is blocked; keep its EOS worker alive.
            if (recordAudio) {
                try {
                    audioRecord?.stop()
                } catch (e: Exception) {
                    audioStopError = "Recording could not be saved."
                    Log.w(TAG, "Error stopping audio input", e)
                }
            }
            // No timeout authorizes release. A blocked native writer keeps its consumer alive.
            writer?.join()
            var eosError: String? = null
            try {
                encoder?.signalEndOfInputStream()
            } catch (e: Exception) {
                eosError = "Recording could not be finalized."
                Log.w(TAG, "Error signaling EOS", e)
                // Activate the existing drain loop's cooperative non-recording exit branch.
                stopRequested.set(false)
            }
            drainComplete.await()
            audioThread?.join()
            drainThread?.quitSafely()
            drainThread?.join()
            pixelCopyThread?.quitSafely()
            pixelCopyThread?.join()
            // Workers have actually exited, not merely decremented the drain latch.
            val (fileError, releaseError) = cleanupResources()
            if (releaseError != null) {
                mainHandler.post {
                    mainHandler.removeCallbacks(stopWatchdog)
                    writerThread = null
                    writerHandler = null
                    frameInFlight = false
                    // V4 retains failed-release handles and keeps recording admission closed.
                    reportStopFailure(releaseError)
                }
                return
            }
            val file = File(outputPath)
            val validationError = try {
                if (!file.exists() || file.length() <= 0L) "Recording could not be saved." else null
            } catch (e: Exception) {
                Log.w(TAG, "Recording file validation failed", e)
                "Recording could not be saved."
            }
            val error = finalError ?: eosError ?: audioStopError ?: fileError ?: validationError
            mainHandler.post {
                mainHandler.removeCallbacks(stopWatchdog)
                writerThread = null
                writerHandler = null
                frameInFlight = false
                finalError = error
                stopFailure = null
                finalized = true
                finalizing = false
                val waiters = stopWaiters.toList()
                stopWaiters.clear()
                waiters.forEach { it(if (error == null) outputPath else null, error) }
            }
        } catch (e: Exception) {
            // Join/interruption failures retain resources and the admission fence.
            mainHandler.post { reportStopFailure("Recording could not finish stopping.") }
        }
    }

    private fun cleanupResources(): Pair<String?, String?> {
        check(Looper.myLooper() != Looper.getMainLooper())
        var fileError: String? = null
        var releaseError: String? = null
        fun stopStep(action: () -> Unit) {
            try {
                action()
            } catch (e: Exception) {
                if (fileError == null) fileError = "Recording could not be saved."
                Log.w(TAG, "Recording stop error", e)
            }
        }
        fun releaseStep(action: () -> Unit): Boolean {
            return try {
                action()
                true
            } catch (e: Exception) {
                if (releaseError == null) releaseError = "Recording could not finish stopping."
                Log.w(TAG, "Recording resource release unconfirmed", e)
                false
            }
        }
        synchronized(muxerLock) {
            val activeMuxer = muxer
            if (activeMuxer != null) {
                if (muxerStarted.getAndSet(false)) stopStep { activeMuxer.stop() }
                if (releaseStep { activeMuxer.release() }) muxer = null
            }
        }
        val videoCodec = encoder
        if (videoCodec != null) {
            stopStep { videoCodec.stop() }
            if (releaseStep { videoCodec.release() }) encoder = null
        }
        val microphone = audioRecord
        if (microphone != null && releaseStep { microphone.release() }) audioRecord = null
        val audioCodec = audioEncoder
        if (audioCodec != null) {
            stopStep { audioCodec.stop() }
            if (releaseStep { audioCodec.release() }) audioEncoder = null
        }
        audioThread = null
        val surface = inputSurface
        if (surface != null && releaseStep { surface.release() }) inputSurface = null
        drainThread = null
        drainHandler = null
        pixelCopyThread = null
        pixelCopyHandler = null
        return fileError to releaseError
    }

    // ========== FRAME CAPTURE ==========

    private fun captureFrame() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!isRecording.get() || stopRequested.get() || frameInFlight) return
        val surface = inputSurface ?: return
        val viewWidth = sourceView.width
        val viewHeight = sourceView.height
        if (viewWidth <= 0 || viewHeight <= 0) return
        if (!viewsCached) {
            cachedCameraView = findViewByType(sourceView, "PreviewView")
            cachedOverlayView = findViewByType(sourceView, "OverlayCanvasView")
                ?: findViewByType(sourceView, "WebView")
            if (cachedCameraView != null) {
                cachedHardwareView = findViewByType(cachedCameraView!!, "TextureView")
                    ?: findViewByType(cachedCameraView!!, "SurfaceView")
            }
            viewsCached = true
        }
        val generation = captureGeneration
        frameInFlight = true
        try {
            val hwView = cachedHardwareView
            // Readback and all View drawing stay on Main, including fallback snapshots.
            if (hwView is TextureView) {
                val camera = hwView.bitmap
                if (camera == null) {
                    frameInFlight = false
                    return
                }
                submitFrame(surface, camera, snapshotOverlay(viewWidth, viewHeight), generation)
                return
            }
            if (hwView is SurfaceView && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val sv = hwView.holder.surface
                if (sv != null && sv.isValid && hwView.width > 0 && hwView.height > 0) {
                    val handler = pixelCopyHandler
                    if (handler == null) {
                        frameInFlight = false
                        return
                    }
                    val bitmap = Bitmap.createBitmap(hwView.width, hwView.height, Bitmap.Config.ARGB_8888)
                    pixelCopyPending = true
                    try {
                        PixelCopy.request(hwView, bitmap, { result ->
                            mainHandler.post {
                                if (generation != captureGeneration) {
                                    bitmap.recycle()
                                    return@post
                                }
                                pixelCopyPending = false
                                if (!isRecording.get() || stopRequested.get() || inputSurface !== surface || result != PixelCopy.SUCCESS) {
                                    frameInFlight = false
                                    bitmap.recycle()
                                    return@post
                                }
                                try {
                                    submitFrame(surface, bitmap, snapshotOverlay(viewWidth, viewHeight), generation)
                                } catch (e: Exception) {
                                    frameInFlight = false
                                    Log.w(TAG, "Recording snapshot failed", e)
                                }
                            }
                        }, handler)
                    } catch (e: Exception) {
                        pixelCopyPending = false
                        frameInFlight = false
                        bitmap.recycle()
                        Log.w(TAG, "PixelCopy request failed", e)
                    }
                    return
                }
            }
            val snapshot = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(snapshot)
            canvas.drawColor(Color.BLACK)
            sourceView.draw(canvas)
            submitFrame(surface, snapshot, null, generation)
        } catch (e: Exception) {
            frameInFlight = false
            Log.w(TAG, "Recording capture failed", e)
        }
    }

    private fun snapshotOverlay(viewWidth: Int, viewHeight: Int): Bitmap? {
        check(Looper.myLooper() == Looper.getMainLooper())
        val overlay = cachedOverlayView
        val frameSource = overlay as? BrowserOverlayFrameSource
        if (overlayFrameSource !== frameSource) {
            overlayFrameSource?.releaseBrowserOutput()
            overlayFrameSource = frameSource
            frameSource?.retainBrowserOutput()
        }
        if (overlay == null || frameSource?.hasBrowserFrames == false) {
            lastOverlayBitmap = null
            overlayOwner = null
            overlayRevision = -1L
            return null
        }
        val revision = frameSource?.browserFrameRevision ?: -1L
        // Raw WebView fallback has no revision contract and must redraw every admitted frame.
        if (frameSource == null || overlayOwner !== overlay || overlayRevision != revision ||
            lastOverlayBitmap?.width != viewWidth || lastOverlayBitmap?.height != viewHeight) {
            val bitmap = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            try {
                overlay.draw(canvas)
            } catch (e: Exception) {
                Log.w(TAG, "Overlay draw error", e)
            }
            // Published snapshots are immutable, even after the CPU writer returns.
            lastOverlayBitmap = bitmap
            overlayOwner = overlay
            overlayRevision = revision
        }
        return lastOverlayBitmap
    }

    private fun submitFrame(surface: Surface, camera: Bitmap, overlay: Bitmap?, generation: Long) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val accepted = writerHandler?.post {
            val delivered = renderSnapshot(surface, camera, overlay)
            mainHandler.post completion@ {
                if (generation != captureGeneration) return@completion
                frameInFlight = false
                if (delivered) frameCount++
            }
        } == true
        if (!accepted) frameInFlight = false
    }

    private fun renderSnapshot(surface: Surface, camera: Bitmap, overlay: Bitmap?): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper())
        try {
            val canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                surface.lockHardwareCanvas()
            } else {
                surface.lockCanvas(null)
            } ?: return false
            try {
                canvas.drawBitmap(camera, null, Rect(0, 0, width, height), scalePaint)
                overlay?.let { canvas.drawBitmap(it, null, Rect(0, 0, width, height), scalePaint) }
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Recording surface submission failed", e)
            return false
        }
    }

    // ========== AUDIO CAPTURE ==========

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            sourceView.context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord {
        val minBufferSize = AudioRecord.getMinBufferSize(
            audioSampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) {
            throw IllegalStateException("Microphone is unavailable for local recording")
        }

        audioInputBufferSize = (minBufferSize * 2).coerceAtLeast(audioSampleRate / 5)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            audioSampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            audioInputBufferSize
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("Microphone could not be initialized for local recording")
        }

        return recorder
    }

    private fun createAudioEncoder(): MediaCodec {
        val format = MediaFormat.createAudioFormat(
            AUDIO_MIME_TYPE,
            audioSampleRate,
            AUDIO_CHANNEL_COUNT
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, audioInputBufferSize)
        }

        return MediaCodec.createEncoderByType(AUDIO_MIME_TYPE).also { codec ->
            audioEncoder = codec
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        }
    }

    private fun startAudioLoop() {
        audioThread = Thread({ audioLoop() }, "LocalRecAudio").also { it.start() }
    }

    private fun audioLoop() {
        val recorder = audioRecord
        val codec = audioEncoder
        if (recorder == null || codec == null) {
            drainComplete.countDown()
            return
        }

        val inputBuffer = ByteArray(audioInputBufferSize)

        try {
            while (isRecording.get()) {
                val bytesRead = try {
                    recorder.read(inputBuffer, 0, inputBuffer.size)
                } catch (error: Exception) {
                    Log.w(TAG, "Audio read failed", error)
                    break
                }

                if (bytesRead > 0) {
                    queueAudioInput(inputBuffer, bytesRead)
                    emitAudioLevel(inputBuffer, bytesRead)
                }

                drainAudioEncoder(waitForOutput = false)
            }

            queueAudioEndOfStream()
            var drainAttempts = 0
            while (!audioOutputEos && drainAttempts < AUDIO_EOS_MAX_ATTEMPTS) {
                drainAudioEncoder(waitForOutput = true)
                drainAttempts += 1
            }
            if (!audioOutputEos) {
                Log.w(TAG, "Audio encoder did not signal EOS before timeout")
            }
        } catch (error: Exception) {
            Log.w(TAG, "Audio loop failed", error)
        } finally {
            drainComplete.countDown()
            Log.d(TAG, "Audio loop ended")
        }
    }

    private fun queueAudioInput(buffer: ByteArray, size: Int) {
        val codec = audioEncoder ?: return

        while (isRecording.get()) {
            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                val codecBuffer = codec.getInputBuffer(inputIndex) ?: return
                codecBuffer.clear()
                codecBuffer.put(buffer, 0, size)
                val presentationTimeUs = nextAudioPresentationTimeUs(size)
                codec.queueInputBuffer(inputIndex, 0, size, presentationTimeUs, 0)
                return
            }

            drainAudioEncoder(waitForOutput = false)
        }
    }

    private fun queueAudioEndOfStream() {
        if (audioInputEosQueued) return
        val codec = audioEncoder ?: return

        var attempts = 0
        while (!audioInputEosQueued && attempts < AUDIO_EOS_MAX_ATTEMPTS) {
            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                val presentationTimeUs = nextAudioPresentationTimeUs(0)
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    presentationTimeUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                audioInputEosQueued = true
            } else {
                drainAudioEncoder(waitForOutput = false)
                attempts += 1
            }
        }
        if (!audioInputEosQueued) {
            Log.w(TAG, "Audio encoder did not accept EOS input before timeout")
        }
    }

    private fun drainAudioEncoder(waitForOutput: Boolean) {
        val codec = audioEncoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, if (waitForOutput) TIMEOUT_US else 0L)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (audioTrackIndex < 0) {
                        val newFormat = codec.outputFormat
                        Log.d(TAG, "Audio output format: $newFormat")
                        audioTrackIndex = addMuxerTrack(newFormat, "audio")
                    }
                }

                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }

                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        writeEncodedSample(audioTrackIndex, outputBuffer, bufferInfo)
                    }

                    codec.releaseOutputBuffer(outputIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        audioOutputEos = true
                        return
                    }
                }

                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
            }
        }
    }

    private fun nextAudioPresentationTimeUs(byteCount: Int): Long {
        val submittedSamples = audioBytesSubmitted / (AUDIO_BYTES_PER_SAMPLE * AUDIO_CHANNEL_COUNT)
        val presentationTimeUs = submittedSamples * 1_000_000L / audioSampleRate
        audioBytesSubmitted += byteCount
        return presentationTimeUs
    }

    private fun emitAudioLevel(buffer: ByteArray, size: Int) {
        val now = System.currentTimeMillis()
        if (now - lastAudioLevelEmitMs < AUDIO_LEVEL_INTERVAL_MS) return
        lastAudioLevelEmitMs = now

        var sumSquares = 0.0
        var sampleCount = 0
        var index = 0
        while (index + 1 < size) {
            val low = buffer[index].toInt() and 0xff
            val high = buffer[index + 1].toInt()
            val sample = ((high shl 8) or low).toShort()
            val normalized = sample / Short.MAX_VALUE.toDouble()
            sumSquares += normalized * normalized
            sampleCount += 1
            index += AUDIO_BYTES_PER_SAMPLE
        }

        if (sampleCount <= 0) return

        val rms = sqrt(sumSquares / sampleCount)
        if (rms <= MIN_VALID_AUDIO_LEVEL) return

        val level = rms.coerceIn(0.0, 1.0)
        val decibels = (20.0 * log10(level)).coerceIn(MIN_DECIBELS, 0.0)
        onAudioLevel?.invoke(level, decibels)
    }

    // ========== MUXER COORDINATION ==========

    private fun addMuxerTrack(format: MediaFormat, label: String): Int {
        synchronized(muxerLock) {
            val mux = muxer ?: throw IllegalStateException("Muxer is not available")
            val trackIndex = mux.addTrack(format)
            addedTrackCount += 1

            if (addedTrackCount >= expectedTrackCount && !muxerStarted.get()) {
                mux.start()
                muxerStarted.set(true)
                muxerLock.notifyAll()
                Log.d(TAG, "Muxer started with $addedTrackCount tracks")
            }

            Log.d(TAG, "Added $label track=$trackIndex")
            return trackIndex
        }
    }

    private fun writeEncodedSample(trackIndex: Int, outputBuffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        if (
            trackIndex < 0 || bufferInfo.size <= 0 ||
            bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        ) return
        if (!waitForMuxerStarted()) {
            Log.w(TAG, "Dropping encoded sample because muxer did not start")
            return
        }

        synchronized(muxerLock) {
            if (muxerStarted.get()) {
                val activeMuxer = muxer ?: return
                if (trackIndex == videoTrackIndex) {
                    // Surface PTS use a monotonic epoch; AAC PTS already start at zero.
                    val origin = videoPtsOriginUs ?: bufferInfo.presentationTimeUs
                    videoPtsOriginUs = origin
                    bufferInfo.presentationTimeUs -= origin
                }
                activeMuxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
            }
        }
    }

    private fun waitForMuxerStarted(): Boolean {
        if (muxerStarted.get()) return true

        synchronized(muxerLock) {
            if (!muxerStarted.get()) {
                try {
                    muxerLock.wait(MUXER_START_WAIT_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            return muxerStarted.get()
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
                        if (videoTrackIndex < 0) {
                            videoTrackIndex = addMuxerTrack(newFormat, "video")
                        }
                    }

                    outputIndex >= 0 -> {
                        val outputBuffer = enc.getOutputBuffer(outputIndex)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }

                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            writeEncodedSample(videoTrackIndex, outputBuffer, bufferInfo)
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
