package com.webcamstream

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.ReactContext
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.library.rtmp.RtmpStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Streams and records the composed BrowserCameraView by feeding view captures
 * directly into RootEncoder's GL input Surface using immutable UI snapshots.
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
    private var videoSource: CanvasVideoSource? = null
    private var captureLoop: ViewCaptureLoop? = null
    private var preparedKey: String? = null
    private var lastRecordingPath: String = ""
    private var gallerySavedSourcePath: String? = null
    private var gallerySavedLocation: String? = null
    private var streamStartedAtMs: Long = 0L
    private var localRecorder: LocalRecorder? = null
    private var recordingBusy = false
    private var recordingClosed = false
    private var recordingGeneration = 0L
    val hasLocalRecorder: Boolean
        get() = recordingBusy || localRecorder?.isFinalized == false
    val hasPublisher: Boolean
        get() = rtmpStream != null
    private var publisherGeneration = 0L
    var isStopping: Boolean = false
        private set
    private var socketStopped = false
    private var sourcesStopped = false
    private var captureStopped = false
    private val stopWaiters = mutableListOf<(String?) -> Unit>()
    private val publisherHandler = Handler(Looper.getMainLooper())
    private var reconnectPending = false
    private var stopFailure: String? = null
    private val stopWatchdog = Runnable {
        if (isStopping) reportPublisherStopFailure("Native publisher teardown timed out")
    }

    private var configuredAudioEnabled: Boolean? = null

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
        // Repeated deferred/pinned configs must not undo a newer runtime mute command.
        if (configuredAudioEnabled != this.config.audioEnabled) {
            configuredAudioEnabled = this.config.audioEnabled
            setMuted(!this.config.audioEnabled)
        }
        Log.d(TAG, "Configured: urlPresent=${!rtmpUrl.isNullOrBlank()}, ${this.config.width}x${this.config.height}, bitrate=${this.config.bitrate}")
    }

    fun connect() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            publisherHandler.post { connect() }
            return
        }
        if (isStopping || state == StreamState.CONNECTING) return
        val url = rtmpUrl
        if (url.isNullOrBlank()) {
            fail("Missing RTMP URL")
            return
        }

        if (state == StreamState.STREAMING) {
            return
        }

        // Reconnect/reconfigure uses the same asynchronous drain and teardown fence.
        val existing = rtmpStream
        if (existing != null && (existing.isStreaming || state == StreamState.ERROR || preparedKey != config.key())) {
            reconnectPending = true
            drainPublisher { error ->
                if (error != null) fail(error)
                else if (reconnectPending) {
                    reconnectPending = false
                    connect()
                }
            }
            return
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
        recordingClosed = true
        recordingGeneration++
        val recorder = localRecorder
        recorder?.stop { _, error ->
            if (error != null) Log.w(TAG, "Failed to stop recording during disconnect")
            if (!recorder.isFinalizing && localRecorder === recorder) localRecorder = null
        }
        // Publisher state changes only after the capture/socket/source barriers close.
    }

    fun startPublishing() = connect()

    fun stopPublishing() {
        stopPublishingAndWait { error ->
            if (error != null) Log.w(TAG, "Failed to stop publishing")
        }
    }

    fun stopPublishingAndWait(completion: (String?) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            publisherHandler.post { stopPublishingAndWait(completion) }
            return
        }
        reconnectPending = false
        drainPublisher(completion)
    }

    private fun drainPublisher(completion: (String?) -> Unit) {
        if (isStopping && stopFailure != null) {
            completion(stopFailure)
            return
        }
        stopWaiters.add(completion)
        if (isStopping) return
        isStopping = true
        socketStopped = rtmpStream?.isStreaming != true
        sourcesStopped = false
        captureStopped = captureLoop == null && videoSource?.usesGpu != true
        publisherHandler.postDelayed(stopWatchdog, 30_000L)
        val loop = captureLoop
        val drained: () -> Unit = {
            captureLoop = null
            captureStopped = true
            stopRootPublisherAfterDrain()
        }
        // Keep RootEncoder alive until either the CPU writer or GPU GL barrier drains.
        if (loop != null) loop.stop(drained)
        else {
            val source = videoSource
            if (source?.usesGpu == true) source.stopGpuAndWait(drained)
            else drained()
        }
    }

    private fun stopRootPublisherAfterDrain() {
        check(captureStopped)
        // Snapshot the publisher; only the handler owns its state and admission fence.
        val rtmpStream = this.rtmpStream
        val generation = publisherGeneration
        Thread {
            val error = try {
                // Codec shutdown can block; the UI remains responsive while all barriers stay fenced.
                rtmpStream?.stopStream()
                rtmpStream?.release()
                null
            } catch (error: Exception) {
                "Native publisher teardown failed"
            }
            publisherHandler.post {
                if (generation != publisherGeneration || !isStopping) return@post
                if (error == null) {
                    sourcesStopped = true
                    finishPublisherStop()
                } else {
                    reportPublisherStopFailure(error)
                }
            }
        }.start()
    }

    private fun reportPublisherStopFailure(error: String) {
        if (!isStopping) return
        if (stopFailure == null) stopFailure = error
        reconnectPending = false
        val failure = stopFailure
        val waiters = stopWaiters.toList()
        stopWaiters.clear()
        // Never force-release a producer that may still be inside a native canvas call.
        waiters.forEach { it(failure) }
    }

    private fun finishPublisherStop() {
        if (!isStopping || !socketStopped || !sourcesStopped || !captureStopped) return
        publisherGeneration += 1 // Late callbacks from this discarded publisher are inert.
        preparedKey = null
        rtmpStream = null
        videoSource = null
        publisherHandler.removeCallbacks(stopWatchdog)
        stopFailure = null
        isStopping = false
        updateState(StreamState.DISCONNECTED)
        val waiters = stopWaiters.toList()
        stopWaiters.clear()
        waiters.forEach { it(null) }
    }

    fun startRecording(recordingConfig: Map<String, Any?>?, completion: (String?, String?) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (recordingClosed || recordingBusy) {
            completion(null, "Recording command is busy or the view is closed")
            return
        }
        recordingBusy = true
        val generation = ++recordingGeneration
        val pinnedConfig = recordingConfig?.toMap()
        val begin: () -> Unit = {
            if (generation != recordingGeneration || recordingClosed) {
                completion(null, "Recording start cancelled")
            } else {
                val result = try {
                    startRecordingNow(pinnedConfig) to null
                } catch (error: Exception) {
                    Log.w(TAG, "Recording start failed")
                    null to "Recording could not be started."
                }
                if (result.first != null) {
                    recordingBusy = false
                    completion(result.first, null)
                } else {
                    val failed = localRecorder
                    val finishFailure: () -> Unit = {
                        if (generation != recordingGeneration || recordingClosed) {
                            completion(null, "Recording start cancelled")
                        } else {
                            recordingBusy = false
                            if (failed?.isFinalized == true && localRecorder === failed) localRecorder = null
                            completion(null, result.second)
                        }
                    }
                    if (failed == null) finishFailure() else failed.stop { _, _ -> finishFailure() }
                }
            }
        }
        val previous = localRecorder
        if (previous == null || previous.isFinalized) {
            if (localRecorder === previous) localRecorder = null
            begin()
        } else previous.stop { _, error ->
            if (error == null) {
                if (localRecorder === previous) localRecorder = null
                begin()
            } else {
                if (!previous.isFinalizing && localRecorder === previous) {
                    localRecorder = null
                    lastRecordingPath = ""
                    gallerySavedSourcePath = null
                    gallerySavedLocation = null
                }
                if (generation == recordingGeneration) recordingBusy = false
                completion(null, error)
            }
        }
    }

    private fun startRecordingNow(recordingConfig: Map<String, Any?>?): String {
        // A new attempt invalidates the prior file even if configuration/path preparation throws.
        lastRecordingPath = ""
        gallerySavedSourcePath = null
        gallerySavedLocation = null
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
            Log.d(TAG, "Starting LOCAL recording to: $outputPath (${recWidth}x${recHeight} @ ${recBitrate}bps)")
            lastRecordingPath = outputPath
            gallerySavedSourcePath = null
            gallerySavedLocation = null

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
            // Startup failure can itself be finalizing; retain the recorder until its fence closes.
            localRecorder = recorder
            recorder.start(outputPath)
            streamStartedAtMs = System.currentTimeMillis()
            Log.d(TAG, "Recording started successfully: $outputPath")
            return outputPath
        } catch (error: Exception) {
            lastRecordingPath = ""
            Log.e(TAG, "Failed to start recording")
            throw IllegalStateException("Recording could not be started.", error)
        }
    }

    fun stopRecording(completion: (String?, String?) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (recordingClosed) {
            completion(null, "Recording view is closed")
            return
        }
        // A second Stop during gallery export cannot race its retry bookkeeping.
        if (recordingBusy && localRecorder == null) {
            completion(null, "Recording finalization is busy")
            return
        }
        val generation = ++recordingGeneration
        recordingBusy = true
        val recorder = localRecorder
        val finish: (String?, String?) -> Unit = { path, error ->
            if (generation != recordingGeneration || recordingClosed) {
                completion(null, "Recording stop cancelled")
            } else if (error != null || path.isNullOrBlank()) {
                recordingBusy = false
                if (recorder != null && !recorder.isFinalizing && localRecorder === recorder) {
                    localRecorder = null
                    lastRecordingPath = ""
                }
                completion(null, error ?: "Recording has no completed local file")
            } else {
                lastRecordingPath = path
                if (localRecorder === recorder) localRecorder = null
                exportRecording(path, generation, completion)
            }
        }
        if (recorder == null) finish(lastRecordingPath, null) else recorder.stop(finish)
    }

    private fun exportRecording(path: String, generation: Long, completion: (String?, String?) -> Unit) {
        if (gallerySavedSourcePath == path && !gallerySavedLocation.isNullOrBlank()) {
            recordingBusy = false
            completion(path, null)
            return
        }
        Thread({
            val saved = try {
                saveRecordingToGallery(path)?.takeIf { it.isNotBlank() }
            } catch (error: Exception) {
                Log.w(TAG, "Gallery save failed")
                null
            }
            publisherHandler.post {
                if (generation != recordingGeneration || recordingClosed) {
                    completion(null, "Recording export cancelled")
                } else {
                    recordingBusy = false
                    if (saved != null) {
                        gallerySavedSourcePath = path
                        gallerySavedLocation = saved
                        completion(path, null)
                    } else completion(null, "Recording remains available locally, but gallery save failed; retry Stop")
                }
            }
        }, "LocalRecGallery").start()
    }

    fun sendVideoFrame(data: ByteArray, timestamp: Long) {
        Log.v(TAG, "Ignoring external encoded video frame at $timestamp (${data.size} bytes)")
    }

    fun sendAudioData(data: ByteArray, timestamp: Long) {
        Log.v(TAG, "Ignoring external audio frame at $timestamp (${data.size} bytes)")
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        rtmpStream?.let { applyMuteTo(it) }
        Log.d(TAG, "Muted: $muted")
    }

    private fun applyMuteTo(stream: RtmpStream) {
        val microphone = stream.audioSource as? MicrophoneSource ?: return
        // Keep the AAC clock/encoder live; RootEncoder substitutes silent PCM while muted.
        if (isMuted) microphone.mute() else microphone.unMute()
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

    private fun ensurePrepared(nextConfig: StreamConfig, allowMicrophone: Boolean = true): RtmpStream {
        val key = nextConfig.key()
        val existing = rtmpStream
        if (existing != null && preparedKey == key) return existing

        check(existing == null && captureLoop == null) { "Publisher must drain before reprepare" }
        val source = CanvasVideoSource()

        // audioEnabled is a mutable mute preference, not permission to discard the pipeline.
        val hasMicPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val useAudio = allowMicrophone && hasMicPermission
        if (allowMicrophone && !hasMicPermission) {
            Log.w(TAG, "Microphone permission not granted, falling back to NoAudioSource")
        }

        val audioSource = if (useAudio) MicrophoneSource() else NoAudioSource()
        val generation = ++publisherGeneration
        // Discovery is Main-only; callbacks capture the source, never access worker Views.
        check(Looper.myLooper() == Looper.getMainLooper())
        val scene = GpuSceneRenderer.fromView(sourceView)
        if (scene != null) {
            source.configureGpu(scene, {
                check(Looper.myLooper() == Looper.getMainLooper())
                if (generation == publisherGeneration && videoSource === source && !isStopping) {
                    stats.fps = nextConfig.fps
                }
            }, {
                check(Looper.myLooper() == Looper.getMainLooper())
                if (generation == publisherGeneration && videoSource === source) {
                    if (isStopping) reportPublisherStopFailure("Native GPU publisher teardown failed")
                    else {
                        fail("Native GPU publisher output failed")
                        stopPublishingAndWait { error -> if (error != null) fail(error) }
                    }
                }
            })
        }
        val checker = object : ConnectChecker {
            override fun onConnectionStarted(url: String) {
                publisherHandler.post {
                    if (generation == publisherGeneration && !isStopping) this@RtmpClient.onConnectionStarted(url)
                }
            }
            override fun onConnectionSuccess() {
                publisherHandler.post {
                    if (generation == publisherGeneration && !isStopping) this@RtmpClient.onConnectionSuccess()
                }
            }
            override fun onConnectionFailed(reason: String) {
                publisherHandler.post {
                    if (generation == publisherGeneration && !isStopping) this@RtmpClient.onConnectionFailed(reason)
                }
            }
            override fun onDisconnect() {
                publisherHandler.post {
                    if (generation != publisherGeneration) return@post
                    if (isStopping) {
                        socketStopped = true
                        finishPublisherStop()
                    } else this@RtmpClient.onDisconnect()
                }
            }
            override fun onAuthError() {
                publisherHandler.post {
                    if (generation == publisherGeneration && !isStopping) this@RtmpClient.onAuthError()
                }
            }
            override fun onAuthSuccess() {
                publisherHandler.post {
                    if (generation == publisherGeneration && !isStopping) this@RtmpClient.onAuthSuccess()
                }
            }
            override fun onNewBitrate(bitrate: Long) {
                publisherHandler.post {
                    if (generation == publisherGeneration && !isStopping) this@RtmpClient.onNewBitrate(bitrate)
                }
            }
        }
        val stream = RtmpStream(context, checker, source, audioSource)
        val videoPrepared = stream.prepareVideo(
            nextConfig.width,
            nextConfig.height,
            nextConfig.bitrate,
            nextConfig.fps,
            nextConfig.keyFrameInterval,
            0
        )

        if (!videoPrepared) {
            stream.release()
            throw IllegalStateException("RootEncoder prepare failed (video=$videoPrepared)")
        }

        // The surface contains the already-transformed preview; keep GL orientation zero.
        stream.setOrientation(0)

        if (useAudio) {
            val audioPrepared = try {
                stream.prepareAudio(
                    nextConfig.audioSampleRate,
                    true,
                    nextConfig.audioBitrate
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare audio, falling back to NoAudioSource")
                false
            }

            if (!audioPrepared) {
                Log.w(TAG, "Audio preparation failed, re-preparing without audio")
                // Only a hardware/codec failure disables the microphone for this attempt.
                // Release this prepared source before retrying; do not change the user's mute preference.
                stream.release()
                return ensurePrepared(nextConfig, allowMicrophone = false)
            }
        }

        config = nextConfig
        preparedKey = key
        videoSource = source
        rtmpStream = stream
        // Apply before startStream starts capture, including reconnect and initially-muted starts.
        applyMuteTo(stream)
        return stream
    }

    private fun startCaptureLoop() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val source = videoSource ?: return
        if (source.usesGpu) return
        if (captureLoop?.isRunning == true) return

        captureLoop = ViewCaptureLoop(sourceView, source, config.fps) {
            stats.fps = config.fps
        }.also { it.start() }
    }

    // Capture shutdown is exclusively owned by stopPublishingAndWait.

    private fun createRecordingPath(): String {
        val directory = File(context.cacheDir, "browsercam-recordings")
        if (!directory.exists()) directory.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(directory, "browsercam-$stamp.mp4").absolutePath
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
            Log.w(TAG, "Failed to save recording to gallery; scanning source path as fallback")
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
            "browsercam-$stamp.mp4"
        }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/BrowserCam")
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
            if (resolver.update(uri, publishedValues, null, null) != 1) {
                throw IllegalStateException("Unable to publish gallery video entry")
            }
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
            "BrowserCam"
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
            "browsercam-$stamp.mp4"
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
            Log.e(TAG, "RTMP operation failed")
        } else {
            Log.e(TAG, "RTMP operation failed; cause present")
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
        private val source: CanvasVideoSource,
        private val fps: Int,
        private val onFrame: () -> Unit
    ) {
        private val mainHandler = Handler(Looper.getMainLooper())
        private val copyThread = HandlerThread("BrowserCamSurfaceWriter")
        private lateinit var writerHandler: Handler
        private val intervalMs = 1000.0 / fps.coerceIn(1, 1000)
        private var nextDeadlineMs = 0.0
        private var frameInFlight = false
        private var overlayBitmap: Bitmap? = null
        private var overlayOwner: View? = null
        private var overlayRevision = -1L
        private var overlayFrameSource: BrowserOverlayFrameSource? = null
        private var stopRequested = false
        private var writerStopped = false
        private val stopWaiters = mutableListOf<() -> Unit>()
        private val capture = Runnable { captureNext() }

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start() {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (isRunning || stopRequested) return
            isRunning = true
            copyThread.start()
            writerHandler = Handler(copyThread.looper)
            nextDeadlineMs = android.os.SystemClock.uptimeMillis().toDouble()
            mainHandler.post(capture)
        }

        fun stop(onStopped: () -> Unit = {}) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post { stop(onStopped) }
                return
            }
            if (writerStopped) {
                onStopped()
                return
            }
            stopWaiters.add(onStopped)
            if (stopRequested) return
            stopRequested = true
            isRunning = false
            mainHandler.removeCallbacks(capture)
            // Published snapshots may still be GPU-visible. Drop references, never recycle.
            overlayBitmap = null
            overlayOwner = null
            overlayRevision = -1L
            overlayFrameSource?.releaseBrowserOutput()
            overlayFrameSource = null
            copyThread.quitSafely()
            // The writer never waits for UI work; UI never joins the writer.
            Thread {
                copyThread.join()
                mainHandler.post {
                    writerStopped = true
                    frameInFlight = false
                    val waiters = stopWaiters.toList()
                    stopWaiters.clear()
                    waiters.forEach { it() }
                }
            }.start()
        }

        private fun captureNext() {
            if (!isRunning || frameInFlight) return
            if (!source.isRunning()) {
                scheduleNext()
                return
            }
            val width = view.width
            val height = view.height
            if (width <= 0 || height <= 0) {
                scheduleNext()
                return
            }
            frameInFlight = true
            try {
                // PreviewView.bitmap includes CameraX rotation and FILL_CENTER transforms.
                val camera = (findViewByType(view, "androidx.camera.view.PreviewView") as? PreviewView)?.bitmap
                val overlayView = findViewByType(view, "OverlayCanvasView")
                    ?: findViewByType(view, "android.webkit.WebView")
                val frameSource = overlayView as? BrowserOverlayFrameSource
                if (overlayFrameSource !== frameSource) {
                    overlayFrameSource?.releaseBrowserOutput()
                    overlayFrameSource = frameSource
                    frameSource?.retainBrowserOutput()
                }
                val revision = frameSource?.browserFrameRevision ?: -1L
                if (overlayView == null || frameSource?.hasBrowserFrames == false) {
                    overlayBitmap = null
                    overlayOwner = null
                    overlayRevision = -1L
                } else if (frameSource == null || overlayOwner !== overlayView || overlayRevision != revision ||
                    overlayBitmap?.width != width || overlayBitmap?.height != height) {
                    // Reuse an immutable composite until the browser cache revision or geometry changes.
                    // Never redraw into a published bitmap, even after the CPU writer finishes.
                    val snapshot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(snapshot)
                    canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                    overlayView.draw(canvas)
                    overlayBitmap = snapshot
                    overlayOwner = overlayView
                    overlayRevision = revision
                }
                val overlay = overlayBitmap
                // Only immutable bitmap references cross the thread boundary, never Views.
                writerHandler.post {
                    var submitted = false
                    try {
                        if (isRunning) {
                            submitted = source.drawFrame(camera, overlay)
                        }
                    } catch (error: Exception) {
                        Log.w(TAG, "Surface frame draw failed")
                    } finally {
                        val delivered = submitted
                        mainHandler.post {
                            frameInFlight = false
                            if (isRunning) {
                                if (delivered) onFrame()
                                scheduleNext()
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Preview snapshot failed")
                frameInFlight = false
                scheduleNext()
            }
        }

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

        private fun scheduleNext() {
            if (!isRunning) return
            nextDeadlineMs += intervalMs
            val now = android.os.SystemClock.uptimeMillis()
            if (nextDeadlineMs <= now) {
                // Skip missed slots without a catch-up burst or fractional drift.
                nextDeadlineMs += (kotlin.math.floor((now - nextDeadlineMs) / intervalMs) + 1.0) * intervalMs
            }
            mainHandler.postAtTime(capture, kotlin.math.ceil(nextDeadlineMs).toLong())
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
