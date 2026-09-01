package com.webcamstream

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.SurfaceView
import android.view.TextureView
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.ReactContext
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.BufferVideoSource
import com.pedro.library.base.recording.RecordController
import com.pedro.library.rtmp.RtmpStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Streams and records the composed BrowserCameraView by feeding view captures
 * into RootEncoder's BufferVideoSource.
 */
class RtmpClient(
    private val context: Context,
    private val sourceView: View
) : ConnectChecker {

    val state: StreamState
        get() = _state.get()

    private val _state = AtomicReference(StreamState.IDLE)
    private var rtmpUrl: String? = null
    private var config: StreamConfig = StreamConfig()
    private var rtmpStream: RtmpStream? = null
    private var videoSource: BufferVideoSource? = null
    private var captureLoop: ViewCaptureLoop? = null
    private var preparedKey: String? = null
    private var lastRecordingPath: String = ""
    private var streamStartedAtMs: Long = 0L
    private var localRecorder: LocalRecorder? = null

    var isMuted: Boolean = true
        private set

    private var stats: StreamStats = StreamStats()

    var onStateChange: ((StreamState) -> Unit)? = null
    var onStatsUpdate: ((StreamStats) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onAudioLevel: ((level: Double, decibels: Double) -> Unit)? = null

    fun configure(config: Map<String, Any?>) {
        rtmpUrl = config["url"] as? String
        this.config = this.config.copy(
            width = (config["width"] as? Number)?.toInt() ?: this.config.width,
            height = (config["height"] as? Number)?.toInt() ?: this.config.height,
            fps = (config["fps"] as? Number)?.toInt() ?: this.config.fps,
            bitrate = (config["bitrate"] as? Number)?.toInt() ?: this.config.bitrate,
            audioBitrate = (config["audioBitrate"] as? Number)?.toInt() ?: this.config.audioBitrate,
            audioSampleRate = (config["audioSampleRate"] as? Number)?.toInt() ?: this.config.audioSampleRate,
            keyFrameInterval = (config["keyFrameInterval"] as? Number)?.toInt() ?: this.config.keyFrameInterval,
            audioEnabled = (config["audioEnabled"] as? Boolean) ?: this.config.audioEnabled
        )
        Log.d(TAG, "Configured: urlPresent=${!rtmpUrl.isNullOrBlank()}, ${this.config.width}x${this.config.height}, bitrate=${this.config.bitrate}")
    }

    fun connect() {
        val url = rtmpUrl
        if (url.isNullOrBlank()) {
            fail("Missing RTMP URL")
            return
        }

        if (state == StreamState.STREAMING) {
            return
        }

        // RootEncoder keeps isStreaming=true when the socket fails. Tear down the
        // stale publisher before preparing a reconnect attempt.
        val staleStream = rtmpStream
        if (staleStream?.isStreaming == true) {
            try {
                staleStream.stopStream()
            } catch (error: Exception) {
                Log.w(TAG, "Failed to reset stale RTMP publisher", error)
            }
            stopCaptureLoop()
            preparedKey = null
            rtmpStream = null
            videoSource = null
        }

        try {
            updateState(StreamState.CONNECTING)
            val stream = ensurePrepared(config)
            stream.startStream(url)
            streamStartedAtMs = System.currentTimeMillis()
            startCaptureLoop()
        } catch (error: Exception) {
            fail("Failed to start RTMP stream: ${error.message}", error)
        }
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting")
        stopPublishing()
        try {
            localRecorder?.stop()
        } catch (error: Exception) {
            Log.w(TAG, "Failed to stop recording during disconnect", error)
        }
        localRecorder = null
        stopCaptureLoop()
        updateState(StreamState.DISCONNECTED)
    }

    fun startPublishing() = connect()

    fun stopPublishing() {
        try {
            rtmpStream?.let { stream ->
                if (stream.isStreaming) {
                    stream.stopStream()
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Failed to stop stream cleanly", error)
        }

        stopCaptureLoop()
        updateState(StreamState.DISCONNECTED)
        // Force recreation next time
        preparedKey = null
        rtmpStream = null
        videoSource = null
    }

    fun startRecording(recordingConfig: Map<String, Any?>?): String {
        val recWidth = (recordingConfig?.get("width") as? Number)?.toInt() ?: config.width
        val recHeight = (recordingConfig?.get("height") as? Number)?.toInt() ?: config.height
        val recBitrate = (recordingConfig?.get("bitrate") as? Number)?.toInt() ?: config.bitrate
        val audioEnabled = (recordingConfig?.get("audioEnabled") as? Boolean) ?: false
        val recAudioBitrate = (recordingConfig?.get("audioBitrate") as? Number)?.toInt() ?: config.audioBitrate
        val recAudioSampleRate = (recordingConfig?.get("audioSampleRate") as? Number)?.toInt() ?: config.audioSampleRate

        var outputPath = (recordingConfig?.get("outputPath") as? String)
            ?.takeIf { it.isNotBlank() }
            ?: createRecordingPath()

        if (outputPath.startsWith("file://")) {
            outputPath = outputPath.substring(7)
        }

        try {
            // Stop any previous recording
            localRecorder?.stop()
            localRecorder = null

            Log.d(TAG, "Starting LOCAL recording to: $outputPath (${recWidth}x${recHeight} @ ${recBitrate}bps)")
            lastRecordingPath = outputPath

            val recorder = LocalRecorder(
                sourceView = sourceView,
                width = recWidth,
                height = recHeight,
                fps = config.fps,
                bitrate = recBitrate,
                audioEnabled = audioEnabled,
                audioBitrate = recAudioBitrate,
                audioSampleRate = recAudioSampleRate,
                onAudioLevel = onAudioLevel
            )
            recorder.start(outputPath)
            localRecorder = recorder
            streamStartedAtMs = System.currentTimeMillis()
            Log.d(TAG, "Recording started successfully: $outputPath")
            return outputPath
        } catch (error: Exception) {
            localRecorder = null
            Log.e(TAG, "Failed to start recording", error)
            throw IllegalStateException("Failed to start recording: ${error.message}", error)
        }
    }

    fun stopRecording(): String {
        try {
            val path = localRecorder?.stop() ?: lastRecordingPath
            lastRecordingPath = path
            localRecorder = null
        } catch (error: Exception) {
            localRecorder = null
            Log.w(TAG, "Failed to stop recording cleanly", error)
            throw IllegalStateException("Failed to stop recording: ${error.message}", error)
        }

        var galleryLocation: String? = null
        if (lastRecordingPath.isNotBlank()) {
            galleryLocation = saveRecordingToGallery(lastRecordingPath)
        }

        Log.d(TAG, "Recording stopped: $lastRecordingPath, gallery=$galleryLocation")
        return lastRecordingPath
    }

    fun sendVideoFrame(data: ByteArray, timestamp: Long) {
        Log.v(TAG, "Ignoring external encoded video frame at $timestamp (${data.size} bytes)")
    }

    fun sendAudioData(data: ByteArray, timestamp: Long) {
        Log.v(TAG, "Ignoring external audio frame at $timestamp (${data.size} bytes)")
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        Log.d(TAG, "Muted: $muted")
    }

    fun getStats(): Map<String, Any> {
        val durationSeconds = if (streamStartedAtMs > 0L) {
            (System.currentTimeMillis() - streamStartedAtMs) / 1000.0
        } else {
            0.0
        }
        stats.duration = durationSeconds
        return mapOf(
            "bitrate" to stats.bitrate,
            "fps" to stats.fps,
            "droppedFrames" to stats.droppedFrames,
            "duration" to stats.duration
        )
    }

    override fun onConnectionStarted(url: String) {
        updateState(StreamState.CONNECTING)
    }

    override fun onConnectionSuccess() {
        updateState(StreamState.STREAMING)
    }

    override fun onConnectionFailed(reason: String) {
        fail("RTMP connection failed: $reason")
    }

    override fun onDisconnect() {
        updateState(StreamState.DISCONNECTED)
    }

    override fun onAuthError() {
        fail("RTMP authentication failed")
    }

    override fun onAuthSuccess() {
        Log.d(TAG, "RTMP authentication succeeded")
    }

    override fun onNewBitrate(bitrate: Long) {
        stats.bitrate = bitrate.toInt()
        onStatsUpdate?.invoke(stats)
    }

    private fun ensurePrepared(nextConfig: StreamConfig): RtmpStream {
        val key = nextConfig.key()
        val existing = rtmpStream
        if (existing != null && preparedKey == key) return existing

        stopCaptureLoop()
        try {
            existing?.let {
                if (it.isStreaming) it.stopStream()
                if (it.isRecording) it.stopRecord()
            }
        } catch (error: Exception) {
            Log.w(TAG, "Failed to stop old stream before reprepare", error)
        }

        val source = BufferVideoSource(BufferVideoSource.Format.ARGB, nextConfig.bitrate)

        var useAudio = nextConfig.audioEnabled
        if (useAudio) {
            val hasMicPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (!hasMicPermission) {
                Log.w(TAG, "Microphone permission not granted, falling back to NoAudioSource")
                useAudio = false
            }
        }

        val audioSource = if (useAudio) MicrophoneSource() else NoAudioSource()
        val stream = RtmpStream(context, this, source, audioSource)
        val videoPrepared = stream.prepareVideo(
            nextConfig.width,
            nextConfig.height,
            nextConfig.bitrate,
            nextConfig.fps,
            nextConfig.keyFrameInterval,
            0
        )

        if (!videoPrepared) {
            throw IllegalStateException("RootEncoder prepare failed (video=$videoPrepared)")
        }

        // BufferVideoSource receives a fully composed landscape bitmap. RootEncoder's
        // rotation=0 setup defaults its GL texture orientation to 270 degrees for raw
        // camera sources, so explicitly restore zero rotation for this precomposed view.
        stream.setOrientation(0)

        if (useAudio) {
            val audioPrepared = try {
                stream.prepareAudio(
                    nextConfig.audioSampleRate,
                    true,
                    nextConfig.audioBitrate
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare audio, falling back to NoAudioSource", e)
                false
            }

            if (!audioPrepared) {
                Log.w(TAG, "Audio preparation failed, re-preparing without audio")
                // Recursively call with audio disabled for this attempt
                return ensurePrepared(nextConfig.copy(audioEnabled = false))
            }
        }

        config = nextConfig
        preparedKey = key
        videoSource = source
        rtmpStream = stream
        return stream
    }

    private fun startCaptureLoop() {
        val source = videoSource ?: return
        if (captureLoop?.isRunning == true) return

        captureLoop = ViewCaptureLoop(sourceView, config.width, config.height, config.fps) { pixels ->
            source.setBuffer(pixels)
            stats.fps = config.fps
        }.also { it.start() }
    }

    private fun stopCaptureLoop() {
        captureLoop?.stop()
        captureLoop = null
    }

    private fun createRecordingPath(): String {
        val directory = File(context.cacheDir, "openscoreboard-recordings")
        if (!directory.exists()) directory.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(directory, "openscoreboard-$stamp.mp4").absolutePath
    }

    private fun saveRecordingToGallery(path: String): String? {
        val sourceFile = File(path)
        if (!sourceFile.exists() || sourceFile.length() <= 0L) {
            Log.w(TAG, "Recording was not saved to gallery because the source file is missing or empty: $path")
            return null
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveRecordingToMediaStore(sourceFile)
            } else {
                saveRecordingToPublicMovies(sourceFile)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Failed to save recording to gallery; scanning source path as fallback", error)
            MediaScannerConnection.scanFile(
                context,
                arrayOf(sourceFile.absolutePath),
                arrayOf("video/mp4"),
                null
            )
            null
        }
    }

    private fun saveRecordingToMediaStore(sourceFile: File): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        val resolver = context.contentResolver
        val displayName = sourceFile.name.ifBlank {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            "openscoreboard-$stamp.mp4"
        }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/OpenScoreboard")
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000L)
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Unable to create gallery video entry")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                sourceFile.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Unable to open gallery video output stream")

            val publishedValues = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            resolver.update(uri, publishedValues, null, null)
            return uri.toString()
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun saveRecordingToPublicMovies(sourceFile: File): String {
        @Suppress("DEPRECATION")
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "OpenScoreboard"
        )
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Unable to create gallery recording directory")
        }

        val destination = uniqueFile(directory, sourceFile.name)
        sourceFile.inputStream().use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        MediaScannerConnection.scanFile(
            context,
            arrayOf(destination.absolutePath),
            arrayOf("video/mp4"),
            null
        )
        return destination.absolutePath
    }

    private fun uniqueFile(directory: File, fileName: String): File {
        val fallbackName = if (fileName.isBlank()) {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            "openscoreboard-$stamp.mp4"
        } else {
            fileName
        }

        var candidate = File(directory, fallbackName)
        if (!candidate.exists()) return candidate

        val baseName = fallbackName.substringBeforeLast(".", fallbackName)
        val extension = fallbackName.substringAfterLast(".", "")
        var index = 1
        while (candidate.exists()) {
            val nextName = if (extension.isBlank()) {
                "$baseName-$index"
            } else {
                "$baseName-$index.$extension"
            }
            candidate = File(directory, nextName)
            index += 1
        }
        return candidate
    }

    private fun updateState(newState: StreamState) {
        val oldState = _state.getAndSet(newState)
        if (oldState != newState) {
            Log.d(TAG, "State changed: $oldState -> $newState")
            onStateChange?.invoke(newState)
        }
    }

    private fun fail(message: String, error: Exception? = null) {
        if (error == null) {
            Log.e(TAG, message)
        } else {
            Log.e(TAG, message, error)
        }
        updateState(StreamState.ERROR)
        onError?.invoke(message)
    }

    private fun StreamConfig.key(): String {
        return "$width:$height:$bitrate:$fps:$audioSampleRate:$audioBitrate:$keyFrameInterval:$audioEnabled"
    }

    data class StreamConfig(
        val width: Int = 720,
        val height: Int = 1280,
        val fps: Int = 15,
        val bitrate: Int = 2500000,
        val audioBitrate: Int = 128000,
        val audioSampleRate: Int = 44100,
        val keyFrameInterval: Int = 2,
        val audioEnabled: Boolean = true
    )

    data class StreamStats(
        var bitrate: Int = 0,
        var fps: Int = 0,
        var droppedFrames: Int = 0,
        var duration: Double = 0.0
    )

    private class ViewCaptureLoop(
        private val view: View,
        private val outputWidth: Int,
        private val outputHeight: Int,
        private val fps: Int,
        private val onFrame: (IntArray) -> Unit
    ) {
        private val mainHandler = Handler(Looper.getMainLooper())
        private val copyThread = HandlerThread("BrowserCamPixelCopy")
        private lateinit var copyHandler: Handler
        private var bitmap: Bitmap? = null
        private var scaledBitmap: Bitmap? = null
        private var pixels = IntArray(outputWidth * outputHeight)

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start() {
            if (isRunning) return
            isRunning = true
            copyThread.start()
            copyHandler = Handler(copyThread.looper)
            mainHandler.post { captureNext() }
        }

        fun stop() {
            isRunning = false
            mainHandler.removeCallbacksAndMessages(null)
            if (::copyHandler.isInitialized) {
                copyHandler.removeCallbacksAndMessages(null)
            }
            bitmap?.recycle()
            scaledBitmap?.recycle()
            bitmap = null
            scaledBitmap = null
            copyThread.quitSafely()
        }

        private var frameCount = 0
        private var lastWebViewBitmap: Bitmap? = null

        private fun captureNext() {
            if (!isRunning) return
            val width = view.width
            val height = view.height
            if (width <= 0 || height <= 0) {
                scheduleNext()
                return
            }

            val target = bitmap?.takeIf { it.width == width && it.height == height }
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    bitmap?.recycle()
                    bitmap = it
                }

            val canvas = Canvas(target)

            // 1. Find Camera and Overlays
            val cameraView = findViewByType(view, "androidx.camera.view.PreviewView")
            val overlayView = findViewByType(view, "OverlayCanvasView") ?: findViewByType(view, "android.webkit.WebView")

            // 2. Hardware Capture (Camera)
            var cameraCaptured = false
            if (cameraView != null) {
                val hardwareView = findViewByTypes(cameraView, listOf("TextureView", "SurfaceView"))

                if (hardwareView is TextureView) {
                    // PreviewView.bitmap applies CameraX's sensor rotation and FILL_CENTER
                    // transformation. TextureView.bitmap returns the raw sensor-oriented frame.
                    val camBitmap = (cameraView as? PreviewView)?.bitmap ?: hardwareView.bitmap
                    if (camBitmap != null) {
                        canvas.drawBitmap(camBitmap, null, android.graphics.Rect(0, 0, width, height), null)
                        cameraCaptured = true
                    }
                } else if (hardwareView is SurfaceView && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val surface = hardwareView.holder.surface
                    if (surface != null && surface.isValid) {
                        PixelCopy.request(surface, target, { result ->
                            if (!isRunning) return@request
                            if (result == PixelCopy.SUCCESS) {
                                finalizeFrameOptimized(target, overlayView)
                                submitBitmap(target)
                            } else {
                                drawFallback(target)
                            }
                            scheduleNext()
                        }, copyHandler)
                        return // Exit early
                    }
                }
            }

            if (!cameraCaptured) {
                canvas.drawColor(Color.BLACK)
            }

            finalizeFrameOptimized(target, overlayView)
            submitBitmap(target)
            scheduleNext()
        }

        private fun finalizeFrameOptimized(target: Bitmap, overlayView: View?) {
            if (overlayView == null) return

            val canvas = Canvas(target)

            // Optimization: Only redraw WebView every 3 frames (~10fps for overlays)
            val shouldRedrawWebView = frameCount % 3 == 0 || lastWebViewBitmap == null

            if (shouldRedrawWebView) {
                val webViewBitmap = lastWebViewBitmap?.takeIf { it.width == target.width && it.height == target.height }
                    ?: Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888).also {
                        lastWebViewBitmap?.recycle()
                        lastWebViewBitmap = it
                    }

                val webCanvas = Canvas(webViewBitmap)
                webCanvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                try {
                    overlayView.draw(webCanvas)
                } catch (e: Exception) {
                    Log.e("RtmpClient", "Overlay draw failed", e)
                }
            }

            lastWebViewBitmap?.let {
                canvas.drawBitmap(it, 0f, 0f, null)
            }
        }

        private fun submitBitmap(source: Bitmap) {
            val output = scaledBitmap?.takeIf { it.width == outputWidth && it.height == outputHeight }
                ?: Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888).also {
                    scaledBitmap?.recycle()
                    scaledBitmap = it
                }
            Canvas(output).drawBitmap(source, null, Rect(0, 0, outputWidth, outputHeight), null)
            output.getPixels(pixels, 0, outputWidth, 0, 0, outputWidth, outputHeight)

            // Debug: Check if the bitmap is all black
            if (frameCount % 60 == 0) {
                var allBlack = true
                for (i in 0 until 100) {
                    if (pixels[i] != Color.BLACK) {
                        allBlack = false
                        break
                    }
                }
                Log.d("RtmpClient", "Frame $frameCount check: allBlack=$allBlack, pixels[0]=${Integer.toHexString(pixels[0])}")
            }

            onFrame(pixels)

            frameCount++
        }

        private fun drawFallback(target: Bitmap) {
            val canvas = Canvas(target)
            canvas.drawColor(Color.BLACK)
            view.draw(canvas)
            submitBitmap(target)
        }

        private fun findViewByTypes(root: View, typeNames: List<String>): View? {
            val className = root.javaClass.name
            for (type in typeNames) {
                if (className.contains(type)) return root
            }
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) {
                    val found = findViewByTypes(root.getChildAt(i), typeNames)
                    if (found != null) return found
                }
            }
            return null
        }

        private fun findViewByType(root: View, typeName: String): View? {
            val className = root.javaClass.name
            if (className.contains(typeName)) return root
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) {
                    val found = findViewByType(root.getChildAt(i), typeName)
                    if (found != null) return found
                }
            }
            return null
        }

        private fun scheduleNext() {
            if (!isRunning) return
            mainHandler.postDelayed({ captureNext() }, (1000L / fps.coerceAtLeast(1)))
        }
    }

    companion object {
        private const val TAG = "RtmpClient"
    }
}

enum class StreamState(val value: String) {
    IDLE("idle"),
    CONNECTING("connecting"),
    STREAMING("streaming"),
    ERROR("error"),
    DISCONNECTED("disconnected")
}
