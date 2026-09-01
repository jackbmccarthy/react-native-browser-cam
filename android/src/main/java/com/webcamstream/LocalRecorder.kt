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
    private var audioBytesSubmitted = 0L
    private var audioInputEosQueued = false
    private var audioOutputEos = false
    private var lastAudioLevelEmitMs = 0L

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
            recordAudio = audioEnabled
            if (recordAudio && !hasAudioPermission()) {
                throw IllegalStateException("Microphone permission is required to record audio")
            }

            expectedTrackCount = if (recordAudio) 2 else 1
            addedTrackCount = 0
            videoTrackIndex = -1
            audioTrackIndex = -1
            audioInputBufferSize = 0
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
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = enc.createInputSurface()
            enc.start()
            encoder = enc
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
            if (recordAudio) {
                startAudioLoop()
            }

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

        if (recordAudio) {
            try {
                audioRecord?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping audio input", e)
            }
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

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
            // It may already be stopped during normal shutdown.
        }
        audioRecord?.release()
        audioRecord = null

        try {
            audioEncoder?.stop()
            audioEncoder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Audio encoder cleanup error", e)
        }
        audioEncoder = null
        audioThread = null

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
        if (trackIndex < 0) return
        if (!waitForMuxerStarted()) {
            Log.w(TAG, "Dropping encoded sample because muxer did not start")
            return
        }

        synchronized(muxerLock) {
            if (muxerStarted.get()) {
                muxer?.writeSampleData(trackIndex, outputBuffer, bufferInfo)
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
