package com.webcamstream

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.view.PreviewView
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.events.RCTEventEmitter

/**
 * Main View that composites a WebView over a camera feed
 * and handles streaming/recording on Android.
 */
class BrowserCameraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // MARK: - Services

    /// Camera service for capturing video frames
    private var cameraService: CameraService? = null

    /// Native CameraX preview surface
    private var previewView: PreviewView? = null

    /// WebView overlay service
    private var webOverlayService: WebOverlayService? = null

    /// OpenGL compositor for merging frames
    private var glCompositor: GlCompositor? = null

    /// RTMP client for streaming
    private var rtmpClient: RtmpClient? = null

    /// Microphone level monitor for the React Native audio meter
    private var audioLevelMonitor: AudioLevelMonitor? = null

    // MARK: - Configuration Properties

    private var cameraConfig: Map<String, Any?>? = null
    private var webViewConfig: Map<String, Any?>? = null
    private var streamConfig: Map<String, Any?>? = null
    private var recordingConfig: Map<String, Any?>? = null
    private var isPreviewStreaming: Boolean = false
    private var pendingCameraStart: Boolean = false
    private var pendingStartStreaming: Boolean = false
    private var pendingStartRecording: Boolean = false
    private var pendingRecordingOverlayWaitStartedAtMs: Long = 0L
    private var previewStartRetryCount: Int = 0

    // MARK: - Initialization

    init {
        setupView()
    }

    private fun setupView() {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        recreatePreviewView()

        android.util.Log.d(TAG, "View initialized")
    }

    // MARK: - Configuration Setters (Called from ViewManager)

    fun setCameraConfig(config: Map<String, Any?>?) {
        cameraConfig = config
        config?.let {
            if (isCameraEnabled(it)) {
                val service = ensureCameraService()
                updateCameraConfig(it)
                if (service.isRunning) {
                    if (isPreviewStreaming) {
                        applyDeferredConfigs()
                    }
                } else {
                    isPreviewStreaming = false
                    webOverlayService?.resetForCameraStart()
                    requestCameraStart()
                }
            } else {
                shutdownCamera()
            }
        }
    }

    fun setWebViewConfig(config: Map<String, Any?>?) {
        webViewConfig = config
        if (isCameraEnabled(cameraConfig) && isPreviewStreaming) {
            config?.let { updateWebViewConfig(it) }
        }
    }

    fun setStreamConfig(config: Map<String, Any?>?) {
        streamConfig = config
        if (isCameraEnabled(cameraConfig)) {
            config?.let { updateStreamConfig(it) }
        }
    }

    fun setRecordingConfig(config: Map<String, Any?>?) {
        recordingConfig = config
        config?.let { updateRecordingConfig(it) }
    }

    // MARK: - Configuration Updates

    private fun updateCameraConfig(config: Map<String, Any?>) {
        val service = ensureCameraService()
        service.setFacing(config["facing"] as? String)
        (config["fps"] as? Number)?.toInt()?.let { service.setFrameRate(it) }
        android.util.Log.d(TAG, "Camera config updated: $config")
    }

    private fun updateWebViewConfig(config: Map<String, Any?>) {
        android.util.Log.d(TAG, "WebView config updated: $config")
        val service = webOverlayService
        if (service == null && !WebOverlayService.hasRenderableContent(config)) {
            return
        }

        (service ?: ensureWebOverlayService()).updateConfig(config)
    }

    private fun updateStreamConfig(config: Map<String, Any?>) {
        android.util.Log.d(TAG, "Stream config updated: $config")
        ensureRtmpClient().configure(config)
    }

    private fun updateRecordingConfig(config: Map<String, Any?>) {
        android.util.Log.d(TAG, "Recording config updated: $config")
    }

    // MARK: - Public Methods (Called from ViewManager)

    /** Start RTMP streaming */
    fun startStreaming() {
        android.util.Log.d(TAG, "startStreaming called")
        if (!ensureCameraEnabledForOutput("RTMP streaming")) return

        pendingStartStreaming = true
        requestCameraStart()
        startPendingOutputsIfReady()
    }

    /** Stop RTMP streaming */
    fun stopStreaming() {
        android.util.Log.d(TAG, "stopStreaming called")
        pendingStartStreaming = false
        rtmpClient?.stopPublishing()
    }

    /** Start recording to file */
    fun startRecording() {
        android.util.Log.d(TAG, "startRecording called")
        if (!ensureCameraEnabledForOutput("recording")) return

        pendingRecordingOverlayWaitStartedAtMs = 0L
        pendingStartRecording = true
        requestCameraStart()
        startPendingOutputsIfReady()
    }

    /** Stop recording and return file path */
    fun stopRecording(): String {
        android.util.Log.d(TAG, "stopRecording called")
        if (pendingStartRecording) {
            pendingStartRecording = false
            pendingRecordingOverlayWaitStartedAtMs = 0L
            sendRecordingState("idle")
            return ""
        }

        return try {
            val path = rtmpClient?.stopRecording() ?: ""
            sendRecordingState("idle", path)
            sendRecordingFinished(path)
            maybeStartPreviewAudioMonitor()
            path
        } catch (error: Exception) {
            val message = error.message ?: "Failed to stop recording"
            sendRecordingState("error")
            sendError("RECORDING_FAILED", message)
            maybeStartPreviewAudioMonitor()
            ""
        }
    }

    /** Take a snapshot */
    fun takeSnapshot(options: Map<String, Any?>): Map<String, Any> {
        // TODO: Implement snapshot
        // - quality: Int
        // - format: jpeg/png
        // - includeOverlay: Bool
        android.util.Log.d(TAG, "takeSnapshot called with options: $options")
        return emptyMap()
    }

    /** Reload WebView content */
    fun reloadWebView() {
        webOverlayService?.reload()
    }

    /** Load a new URL in WebView */
    fun loadUrl(url: String) {
        ensureWebOverlayService().loadUrl(url)
    }

    /** Inject JavaScript into WebView */
    fun injectJavaScript(js: String) {
        webOverlayService?.injectJavaScript(js)
    }

    /** Switch camera facing */
    fun switchCamera() {
        ensureCameraService().switchCamera()
    }

    /** Mute/unmute audio */
    fun setMuted(muted: Boolean) {
        ensureRtmpClient().setMuted(muted)
    }

    /** Get current stream state */
    fun getStreamState(): String {
        return rtmpClient?.state?.value ?: "idle"
    }

    /** Get current stream stats */
    fun getStreamStats(): Map<String, Any> {
        return rtmpClient?.getStats() ?: emptyMap()
    }

    // MARK: - Cleanup

    fun cleanup() {
        pendingStartStreaming = false
        pendingStartRecording = false
        pendingRecordingOverlayWaitStartedAtMs = 0L
        stopAudioLevelMonitor()
        shutdownCamera()
        rtmpClient?.disconnect()
        webOverlayService?.destroy()
        rtmpClient = null
        webOverlayService = null
        glCompositor?.cleanup()
        glCompositor = null
        android.util.Log.d(TAG, "Cleanup completed")
    }

    private fun applyDeferredConfigs() {
        webViewConfig?.let { updateWebViewConfig(it) }
        streamConfig?.let { updateStreamConfig(it) }
        recordingConfig?.let { updateRecordingConfig(it) }
    }

    private fun startPendingOutputsIfReady() {
        if (!isPreviewStreaming) {
            sendLog("Waiting for camera preview before starting output")
            return
        }

        applyDeferredConfigs()

        if (pendingStartStreaming) {
            pendingStartStreaming = false
            try {
                ensureRtmpClient().connect()
            } catch (error: Exception) {
                val message = error.message ?: "Failed to start RTMP stream"
                sendError("STREAM_CONNECTION_FAILED", message)
            }
        }

        if (pendingStartRecording) {
            if (shouldWaitForRecordingOverlay()) {
                return
            }

            pendingStartRecording = false
            pendingRecordingOverlayWaitStartedAtMs = 0L
            try {
                if (isRecordingAudioEnabled()) {
                    stopAudioLevelMonitor()
                }
                val path = ensureRtmpClient().startRecording(recordingConfig)
                sendRecordingState("recording", path)
            } catch (error: Exception) {
                val message = error.message ?: "Failed to start recording"
                sendRecordingState("error")
                sendError("RECORDING_FAILED", message)
                maybeStartPreviewAudioMonitor()
            }
        }
    }

    private fun requestCameraStart() {
        pendingCameraStart = true
        post { startCameraWhenViewReady() }
    }

    private fun startCameraWhenViewReady() {
        if (!pendingCameraStart) return

        val previewWidth = previewView?.width ?: 0
        val previewHeight = previewView?.height ?: 0
        if (!isAttachedToWindow || width <= 0 || height <= 0) {
            postDelayed({ startCameraWhenViewReady() }, CAMERA_START_RETRY_MS)
            return
        }

        if (previewWidth <= 0 || previewHeight <= 0) {
            layoutPreviewToParentBounds()
        }

        pendingCameraStart = false
        ensureCameraService().start()
        schedulePreviewStartWatchdog()
    }

    private fun layoutPreviewToParentBounds() {
        val preview = previewView ?: return
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        preview.measure(widthSpec, heightSpec)
        preview.layout(0, 0, width, height)
    }

    private fun schedulePreviewStartWatchdog() {
        postDelayed({
            val service = cameraService ?: return@postDelayed
            if (!isCameraEnabled(cameraConfig) || isPreviewStreaming || !service.isRunning) {
                return@postDelayed
            }

            if (previewStartRetryCount >= MAX_PREVIEW_START_RETRIES) {
                val message = "Camera preview did not start after retry"
                android.util.Log.w(TAG, message)
                pendingStartStreaming = false
                pendingStartRecording = false
                sendError("CAMERA_INIT_FAILED", message)
                return@postDelayed
            }

            previewStartRetryCount += 1
            android.util.Log.w(TAG, "Camera preview did not reach streaming; restarting CameraX")
            service.stop()
            cameraService = null
            isPreviewStreaming = false
            recreatePreviewView()
            postDelayed({ requestCameraStart() }, CAMERA_RESTART_DELAY_MS)
        }, PREVIEW_START_TIMEOUT_MS)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (pendingCameraStart) {
            post { startCameraWhenViewReady() }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val exactWidth = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val exactHeight = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) {
            getChildAt(i).measure(exactWidth, exactHeight)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val w = right - left
        val h = bottom - top
        for (i in 0 until childCount) {
            getChildAt(i).layout(0, 0, w, h)
        }
    }

    override fun requestLayout() {
        super.requestLayout()
        post {
            measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )
            layout(left, top, right, bottom)
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        cameraService?.updateTargetRotation()
        webOverlayService?.setSize(width, height)
        if (pendingCameraStart && width > 0 && height > 0) {
            post { startCameraWhenViewReady() }
        }
    }

    private fun ensureCameraService(): CameraService {
        val preview = previewView ?: recreatePreviewView()
        return cameraService ?: CameraService(context, preview).also {
            it.onCameraStarted = {
                post {
                    isPreviewStreaming = true
                    previewStartRetryCount = 0
                    startAudioLevelMonitor()
                    applyDeferredConfigs()
                    sendEvent(EVENT_CAMERA_READY)
                    startPendingOutputsIfReady()
                }
            }
            it.onCameraStopped = {
                post {
                    isPreviewStreaming = false
                }
            }
            it.onCameraError = { message ->
                post {
                    pendingStartStreaming = false
                    pendingStartRecording = false
                    sendError("CAMERA_INIT_FAILED", message)
                }
            }
            cameraService = it
        }
    }

    private fun shutdownCamera() {
        val hadCamera = cameraService != null
        pendingCameraStart = false
        pendingStartStreaming = false
        pendingStartRecording = false
        pendingRecordingOverlayWaitStartedAtMs = 0L
        isPreviewStreaming = false
        previewStartRetryCount = 0
        stopAudioLevelMonitor()
        cameraService?.stop()
        cameraService = null
        if (hadCamera || previewView == null) {
            recreatePreviewView()
        }
    }

    private fun recreatePreviewView(): PreviewView {
        val oldPreview = previewView
        if (oldPreview?.parent === this) {
            removeView(oldPreview)
        }

        val nextPreview = PreviewView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }

        previewView = nextPreview
        val insertIndex = if (webOverlayService != null && childCount > 0) {
            1.coerceAtMost(childCount)
        } else {
            0
        }
        addView(nextPreview, insertIndex)
        nextPreview.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (pendingCameraStart) {
                post { startCameraWhenViewReady() }
            }
        }
        webOverlayService?.bringToFront()
        return nextPreview
    }

    private fun ensureWebOverlayService(): WebOverlayService {
        return webOverlayService ?: WebOverlayService(context).also {
            it.onLoadFinish = {
                post { sendEvent(EVENT_WEBVIEW_LOAD) }
            }
            it.onLoadError = { code, description ->
                post {
                    val event = Arguments.createMap().apply {
                        putInt("code", code)
                        putString("description", description)
                    }
                    sendEvent(EVENT_WEBVIEW_ERROR, event)
                }
            }
            it.attachTo(this)
            webOverlayService = it
        }
    }

    private fun ensureRtmpClient(): RtmpClient {
        return rtmpClient ?: RtmpClient(context, this).also {
            it.onStateChange = { state ->
                post { sendStreamState(state) }
            }
            it.onStatsUpdate = { stats ->
                post { sendStreamStats(stats) }
            }
            it.onAudioLevel = { level, decibels ->
                post { sendAudioLevel(level, decibels) }
            }
            it.onError = { message ->
                post { sendError("STREAM_CONNECTION_FAILED", message) }
            }
            rtmpClient = it
        }
    }

    private fun startAudioLevelMonitor() {
        val monitor = audioLevelMonitor ?: AudioLevelMonitor(context) { level, decibels ->
            post { sendAudioLevel(level, decibels) }
        }.also {
            audioLevelMonitor = it
        }
        monitor.start()
    }

    private fun stopAudioLevelMonitor() {
        audioLevelMonitor?.stop()
        audioLevelMonitor = null
    }

    private fun maybeStartPreviewAudioMonitor() {
        if (!isPreviewStreaming) return
        if (rtmpClient?.state == StreamState.STREAMING) return
        startAudioLevelMonitor()
    }

    private fun isRecordingAudioEnabled(): Boolean {
        return recordingConfig?.get("audioEnabled") as? Boolean == true
    }

    private fun shouldWaitForRecordingOverlay(): Boolean {
        val service = webOverlayService ?: return false
        if (!service.hasActiveOverlays()) return false

        if (service.isReady) {
            pendingRecordingOverlayWaitStartedAtMs = 0L
            return false
        }

        val now = System.currentTimeMillis()
        if (pendingRecordingOverlayWaitStartedAtMs == 0L) {
            pendingRecordingOverlayWaitStartedAtMs = now
            sendLog("Waiting for browser overlays before recording")
        }

        if (now - pendingRecordingOverlayWaitStartedAtMs >= RECORDING_OVERLAY_READY_TIMEOUT_MS) {
            pendingRecordingOverlayWaitStartedAtMs = 0L
            sendLog("Starting recording before browser overlays reported ready")
            return false
        }

        postDelayed({ startPendingOutputsIfReady() }, RECORDING_OVERLAY_READY_RETRY_MS)
        return true
    }

    private fun ensureCameraEnabledForOutput(outputName: String): Boolean {
        if (isCameraEnabled(cameraConfig)) return true

        val message = "Camera must be enabled before starting $outputName"
        android.util.Log.w(TAG, message)
        sendError("CAMERA_INIT_FAILED", message)
        return false
    }

    private fun sendStreamState(state: StreamState) {
        val event = Arguments.createMap().apply {
            putString("state", state.value)
        }
        sendEvent(EVENT_STREAM_STATE_CHANGE, event)
    }

    private fun sendStreamStats(stats: RtmpClient.StreamStats) {
        val event = Arguments.createMap().apply {
            putInt("bitrate", stats.bitrate)
            putInt("fps", stats.fps)
            putInt("droppedFrames", stats.droppedFrames)
            putDouble("duration", stats.duration)
        }
        sendEvent(EVENT_STREAM_STATS, event)
    }

    private fun sendRecordingState(state: String, path: String? = null) {
        val event = Arguments.createMap().apply {
            putString("state", state)
            path?.let { putString("path", it) }
        }
        sendEvent(EVENT_RECORDING_STATE_CHANGE, event)
    }

    private fun sendRecordingFinished(path: String) {
        val event = Arguments.createMap().apply {
            putString("path", path)
        }
        sendEvent(EVENT_RECORDING_FINISHED, event)
    }

    private fun sendAudioLevel(level: Double, decibels: Double) {
        val event = Arguments.createMap().apply {
            putDouble("level", level)
            putDouble("decibels", decibels)
        }
        sendEvent(EVENT_AUDIO_LEVEL, event)
    }

    private fun sendError(code: String, message: String) {
        val event = Arguments.createMap().apply {
            putString("code", code)
            putString("message", message)
        }
        sendEvent(EVENT_ERROR, event)
    }

    private fun sendLog(message: String) {
        val event = Arguments.createMap().apply {
            putString("message", message)
        }
        sendEvent(EVENT_LOG, event)
    }

    private fun sendEvent(name: String, event: WritableMap = Arguments.createMap()) {
        val reactContext = context as? ReactContext ?: return
        reactContext
            .getJSModule(RCTEventEmitter::class.java)
            .receiveEvent(id, name, event)
    }

    private fun isCameraEnabled(config: Map<String, Any?>?): Boolean {
        return config?.get("enabled") as? Boolean == true
    }

    companion object {
        private const val TAG = "BrowserCameraView"
        private const val CAMERA_START_RETRY_MS = 200L
        private const val CAMERA_RESTART_DELAY_MS = 1000L
        private const val PREVIEW_START_TIMEOUT_MS = 8000L
        private const val MAX_PREVIEW_START_RETRIES = 3
        private const val RECORDING_OVERLAY_READY_RETRY_MS = 120L
        private const val RECORDING_OVERLAY_READY_TIMEOUT_MS = 2500L
        private const val EVENT_STREAM_STATE_CHANGE = "topStreamStateChange"
        private const val EVENT_STREAM_STATS = "topStreamStats"
        private const val EVENT_ERROR = "topError"
        private const val EVENT_WEBVIEW_LOAD = "topWebViewLoad"
        private const val EVENT_WEBVIEW_ERROR = "topWebViewError"
        private const val EVENT_CAMERA_READY = "topCameraReady"
        private const val EVENT_LOG = "topLog"
        private const val EVENT_AUDIO_LEVEL = "topAudioLevel"
        private const val EVENT_RECORDING_STATE_CHANGE = "topRecordingStateChange"
        private const val EVENT_RECORDING_FINISHED = "topRecordingFinished"
    }
}
