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
    private var gpuScene: GpuSceneRenderer? = null
    private var gpuBrowsers: GpuBrowserOverlayService? = null
    private var cameraDisplayManager: android.hardware.display.DisplayManager? = null
    private val cameraDisplayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (isAttachedToWindow && display?.displayId == displayId) cameraService?.updateTargetRotation()
        }
    }
    private var reportedAndroidRendererFallback: Boolean = false

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
    private var managedSession: String? = null
    private var managedStartCommand: String? = null
    private var managedStopCommand: String? = null
    private var lastStoppedSession: String? = null
    private val usedStreamSessions = mutableSetOf<String>()
    private var pendingStartRecording: Boolean = false
    private var recordingCallbackGeneration = 0L
    private var recordingStartPending = false
    private var recordingStopPending = false
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
                configureAndroidRenderer(it)
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
        if (managedSession != null) return // A managed command owns its immutable configuration.
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
        service.gpuRenderer = gpuScene
        service.setFacing(config["facing"] as? String)
        (config["fps"] as? Number)?.toInt()?.let { service.setFrameRate(it) }
        android.util.Log.d(TAG, "Camera config updated")
    }

    private fun updateWebViewConfig(config: Map<String, Any?>) {
        android.util.Log.d(TAG, "WebView config updated")
        val scene = gpuScene
        if (scene != null) {
            val browsers = gpuBrowsers ?: GpuBrowserOverlayService(context, scene).also { browser ->
                browser.onLoadFinish = { post { sendEvent(EVENT_WEBVIEW_LOAD) } }
                browser.onLoadError = { code, description -> post {
                    val event = Arguments.createMap().apply {
                        putInt("code", code); putString("description", description)
                    }
                    sendEvent(EVENT_WEBVIEW_ERROR, event)
                } }
                gpuBrowsers = browser
            }
            browsers.updateConfig(config)
            return
        }
        val service = webOverlayService
        if (service == null && !WebOverlayService.hasRenderableContent(config)) {
            return
        }

        (service ?: ensureWebOverlayService()).updateConfig(config)
    }

    private fun updateStreamConfig(config: Map<String, Any?>) {
        android.util.Log.d(TAG, "Stream config updated")
        ensureRtmpClient().configure(config)
    }

    private fun updateRecordingConfig(config: Map<String, Any?>) {
        android.util.Log.d(TAG, "Recording config updated")
    }

    // MARK: - Public Methods (Called from ViewManager)

    private fun streamCommandResult(session: String, command: String, operation: String, error: String? = null, code: String = "STREAM_COMMAND_FAILED") {
        val event = Arguments.createMap().apply {
            putString("sessionId", session)
            putString("commandId", command)
            putString("operation", operation)
            putBoolean("success", error == null)
            if (error != null) {
                putString("code", code)
                putString("message", error)
            }
        }
        sendEvent("topStreamCommandResult", event)
    }

    fun configureAndStartStreaming(config: Map<String, Any?>, session: String, command: String) {
        val url = config["url"] as? String
        val valid = !url.isNullOrBlank() && (url.startsWith("rtmp://") || url.startsWith("rtmps://")) &&
            listOf("width", "height", "bitrate").all { ((config[it] as? Number)?.toDouble() ?: 0.0) > 0.0 }
        if (session.isBlank() || command.isBlank() || !valid || !isCameraEnabled(cameraConfig)) {
            streamCommandResult(session, command, "start", "Valid config, session and enabled camera are required", "INVALID_CONFIG")
            return
        }
        if (managedSession == session && managedStartCommand == command) return
        if (managedSession != null || pendingStartStreaming || getStreamState() in listOf("connecting", "streaming") ||
            rtmpClient?.hasPublisher == true ||
            usedStreamSessions.contains(session) || rtmpClient?.isStopping == true) {
            streamCommandResult(session, command, "start", "Stream busy or session ID reused", "STREAM_BUSY")
            return
        }
        managedSession = session
        managedStartCommand = command
        usedStreamSessions.add(session)
        // Same UI command: props cannot intervene between configure and start.
        streamConfig = config.toMap()
        try {
            updateStreamConfig(config)
            // Start configuration owns the initial mute; later setMuted commands remain mutable.
            ensureRtmpClient().setMuted(!(config["audioEnabled"] as? Boolean ?: true))
            startStreaming(true)
        } catch (error: Exception) {
            managedStartCommand = null
            streamCommandResult(session, command, "start", "Native streaming start failed")
        }
    }

    fun stopStreamingAndWait(session: String, command: String) {
        if (managedSession == null && lastStoppedSession == session) {
            streamCommandResult(session, command, "stop")
            return
        }
        if (managedSession != session || session.isBlank() || command.isBlank()) {
            streamCommandResult(session, command, "stop", "No matching active session")
            return
        }
        if (managedStopCommand != null) {
            if (managedStopCommand != command) streamCommandResult(session, command, "stop", "Stop already pending")
            return
        }
        managedStopCommand = command
        managedStartCommand?.let { streamCommandResult(session, it, "start", "Streaming start cancelled") }
        managedStartCommand = null
        pendingStartStreaming = false
        val finish: (String?) -> Unit = { error ->
            post {
                if (managedSession == session && managedStopCommand == command) {
                    managedStopCommand = null
                    if (error == null) {
                        managedSession = null
                        lastStoppedSession = session
                    }
                    streamCommandResult(session, command, "stop", error)
                }
            }
        }
        val client = rtmpClient
        if (client == null) finish(null) else client.stopPublishingAndWait(finish)
    }

    /** Start RTMP streaming */
    fun startStreaming(managed: Boolean = false) {
        android.util.Log.d(TAG, "startStreaming called")
        if (managedSession != null && !managed) return
        if (managedStopCommand != null || rtmpClient?.isStopping == true) return
        if (pendingStartStreaming || getStreamState() in listOf("connecting", "streaming")) return
        if (!ensureCameraEnabledForOutput("RTMP streaming")) return

        pendingStartStreaming = true
        requestCameraStart()
        startPendingOutputsIfReady()
    }

    /** Stop RTMP streaming */
    fun stopStreaming() {
        android.util.Log.d(TAG, "stopStreaming called")
        managedSession?.let { stopStreamingAndWait(it, managedStopCommand ?: "legacy-stop"); return }
        pendingStartStreaming = false
        rtmpClient?.stopPublishing()
    }

    /** Start recording to file */
    fun startRecording() {
        android.util.Log.d(TAG, "startRecording called")
        if (recordingStopPending || recordingStartPending || pendingStartRecording) return
        if (!ensureCameraEnabledForOutput("recording")) return

        pendingRecordingOverlayWaitStartedAtMs = 0L
        pendingStartRecording = true
        requestCameraStart()
        startPendingOutputsIfReady()
    }

    /** Stop recording and return file path */
    fun stopRecording() {
        android.util.Log.d(TAG, "stopRecording called")
        if (recordingStopPending) return
        recordingStartPending = false
        val generation = ++recordingCallbackGeneration
        if (pendingStartRecording) {
            pendingStartRecording = false
            pendingRecordingOverlayWaitStartedAtMs = 0L
            sendRecordingState("idle")
            sendError("RECORDING_CANCELLED", "Recording was cancelled before it started")
            return
        }
        recordingStopPending = true
        val finish: (String?, String?) -> Unit = { path, error ->
            if (generation == recordingCallbackGeneration) {
                recordingStopPending = false
                if (error == null && !path.isNullOrBlank()) {
                    sendRecordingState("idle", path)
                    sendRecordingFinished(path)
                } else {
                    sendRecordingState("error")
                    sendError("RECORDING_FAILED", error ?: "Recording has no completed local file")
                }
                maybeStartPreviewAudioMonitor()
            }
        }
        val client = rtmpClient
        if (client == null) finish(null, "Recording client is unavailable") else client.stopRecording(finish)
    }

    /** Take a snapshot */
    fun takeSnapshot(options: Map<String, Any?>): Map<String, Any> {
        // TODO: Implement snapshot
        // - quality: Int
        // - format: jpeg/png
        // - includeOverlay: Bool
        android.util.Log.d(TAG, "takeSnapshot called")
        return emptyMap()
    }

    /** Reload WebView content */
    fun reloadWebView() {
        gpuBrowsers?.reload()
        webOverlayService?.reload()
    }

    /** Load a new URL in WebView */
    fun loadUrl(url: String) {
        if (gpuScene != null) {
            if (gpuBrowsers == null) updateWebViewConfig(webViewConfig.orEmpty())
            gpuBrowsers?.loadUrl(url)
            return
        }
        ensureWebOverlayService().loadUrl(url)
    }

    /** Inject JavaScript into WebView */
    fun injectJavaScript(js: String) {
        gpuBrowsers?.injectJavaScript(js)
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
        cameraDisplayManager?.unregisterDisplayListener(cameraDisplayListener)
        cameraDisplayManager = null
        recordingCallbackGeneration++
        recordingStartPending = false
        recordingStopPending = false
        managedSession = null
        managedStartCommand = null
        managedStopCommand = null
        pendingStartStreaming = false
        pendingStartRecording = false
        pendingRecordingOverlayWaitStartedAtMs = 0L
        stopAudioLevelMonitor()
        shutdownCamera()
        rtmpClient?.disconnect()
        webOverlayService?.destroy()
        gpuBrowsers?.destroy()
        gpuBrowsers = null
        gpuScene?.let { scene -> removeView(scene.previewView); scene.close() }
        gpuScene = null
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

    private fun startPendingOutputsIfReady(configsApplied: Boolean = false) {
        if (!isPreviewStreaming || cameraService?.readinessGeneration == null) {
            sendLog("Waiting for camera preview before starting output")
            return
        }

        if (!configsApplied) applyDeferredConfigs()

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
                val generation = ++recordingCallbackGeneration
                recordingStartPending = true
                ensureRtmpClient().startRecording(recordingConfig) { path, error ->
                    if (generation == recordingCallbackGeneration) {
                        recordingStartPending = false
                        if (error == null && path != null) sendRecordingState("recording", path)
                        else {
                            sendRecordingState("error")
                            sendError("RECORDING_FAILED", error ?: "Recording did not start")
                            maybeStartPreviewAudioMonitor()
                        }
                    }
                }
            } catch (error: Exception) {
                recordingStartPending = false
                val message = "Recording could not be started."
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
        cameraDisplayManager?.unregisterDisplayListener(cameraDisplayListener)
        cameraDisplayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
        cameraDisplayManager?.registerDisplayListener(cameraDisplayListener, android.os.Handler(android.os.Looper.getMainLooper()))
        cameraService?.updateTargetRotation()
        if (pendingCameraStart) {
            post { startCameraWhenViewReady() }
        }
    }

    override fun onDetachedFromWindow() {
        cameraDisplayManager?.unregisterDisplayListener(cameraDisplayListener)
        cameraDisplayManager = null
        super.onDetachedFromWindow()
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

    private fun configureAndroidRenderer(config: Map<String, Any?>) {
        val requestedGpu = config["androidRendering"] == "gpu"
        val sdkInt = android.os.Build.VERSION.SDK_INT
        // Select before camera/browser allocation. API <=30 Presentation can be
        // cancelled by VirtualDisplay.resize; never fall back mid-capture.
        val wantsGpu = requestedGpu && sdkInt >= 31
        if (wantsGpu == (gpuScene != null)) {
            reportAndroidRendererFallback(requestedGpu, sdkInt)
            return
        }
        // Permission/provider startup must finish before an idle-preview switch.
        // Running preview stops synchronously below; pending outputs and drains stay fenced.
        if ((cameraService != null && cameraService?.isRunning != true) || pendingCameraStart ||
            pendingStartStreaming || pendingStartRecording || recordingStartPending || recordingStopPending ||
            rtmpClient?.hasLocalRecorder == true || rtmpClient?.hasPublisher == true || rtmpClient?.isStopping == true ||
            getStreamState() in listOf("connecting", "streaming")
        ) {
            sendError("RENDERER_BUSY", "Wait for camera startup and stop all outputs before changing renderers")
            return
        }
        cameraService?.stop()
        cameraService = null
        isPreviewStreaming = false
        gpuBrowsers?.destroy()
        gpuBrowsers = null
        gpuScene?.let { scene -> removeView(scene.previewView); scene.close() }
        gpuScene = null
        if (wantsGpu) {
            webOverlayService?.destroy()
            webOverlayService = null
            val scene = GpuSceneRenderer(context) { message -> post { sendError("GPU_RENDERER_FAILED", message) } }
            gpuScene = scene
            scene.setDiagnostics(config["gpuDiagnostics"] == true)
            addView(scene.previewView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            android.util.Log.i(TAG, "Android renderer: GPU scene (CameraX/OES/browser textures)")
        }
        reportAndroidRendererFallback(requestedGpu, sdkInt)
    }

    private fun reportAndroidRendererFallback(requestedGpu: Boolean, sdkInt: Int) {
        if (!requestedGpu || sdkInt >= 31 || gpuScene != null || reportedAndroidRendererFallback) return
        reportedAndroidRendererFallback = true
        val message = "Android renderer: legacy (GPU requested on API " + sdkInt +
            "; API <=30 Presentation resize is unsupported for beta GPU capture)"
        android.util.Log.i(TAG, message)
        val event = Arguments.createMap().apply {
            putString("message", message)
            putString("code", "ANDROID_RENDERER_FALLBACK")
            putString("requestedRendering", "gpu")
            putString("actualRendering", "legacy")
            putString("reason", "ANDROID_API_30_OR_LOWER")
            putInt("sdkInt", sdkInt)
        }
        sendEvent(EVENT_LOG, event)
    }

    private fun ensureCameraService(): CameraService {
        val preview = previewView ?: recreatePreviewView()
        return cameraService ?: CameraService(context, preview).also {
            it.gpuRenderer = gpuScene
            it.onCameraStarted = {
                val startedCamera = it
                val readyGeneration = startedCamera.readinessGeneration
                post {
                    if (readyGeneration == null || cameraService !== startedCamera ||
                        startedCamera.readinessGeneration != readyGeneration || !isAttachedToWindow
                    ) return@post
                    isPreviewStreaming = true
                    previewStartRetryCount = 0
                    startAudioLevelMonitor()
                    applyDeferredConfigs()
                    sendEvent(EVENT_CAMERA_READY)
                    startPendingOutputsIfReady(configsApplied = true)
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
                val session = managedSession
                val command = managedStartCommand
                post {
                    if (session == managedSession && command == managedStartCommand && command != null && session != null &&
                        state == StreamState.STREAMING && managedStopCommand == null) {
                        managedStartCommand = null
                        streamCommandResult(session, command, "start")
                    }
                    sendStreamState(state)
                }
            }
            it.onStatsUpdate = { stats ->
                post { sendStreamStats(stats) }
            }
            it.onAudioLevel = { level, decibels ->
                post { sendAudioLevel(level, decibels) }
            }
            it.onError = { message ->
                val session = managedSession
                val command = managedStartCommand
                post {
                    if (session == managedSession && command == managedStartCommand) {
                        sendError("STREAM_CONNECTION_FAILED", message)
                    }
                }
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
        if (rtmpClient?.hasLocalRecorder == true) return
        if (rtmpClient?.state == StreamState.STREAMING) return
        startAudioLevelMonitor()
    }

    private fun isRecordingAudioEnabled(): Boolean {
        return recordingConfig?.get("audioEnabled") as? Boolean == true
    }

    private fun shouldWaitForRecordingOverlay(): Boolean {
        val gpu = gpuBrowsers
        if (gpu != null) {
            if (!gpu.hasActiveOverlays() || gpu.isReady) return false
            val now = System.currentTimeMillis()
            if (pendingRecordingOverlayWaitStartedAtMs == 0L) pendingRecordingOverlayWaitStartedAtMs = now
            if (now - pendingRecordingOverlayWaitStartedAtMs >= RECORDING_OVERLAY_READY_TIMEOUT_MS) return false
            postDelayed({ startPendingOutputsIfReady() }, RECORDING_OVERLAY_READY_RETRY_MS)
            return true
        }
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
            putBoolean("active", true)
            putDouble("level", level)
            putDouble("decibels", decibels)
        }
        sendEvent(EVENT_AUDIO_LEVEL, event)
    }

    private fun sendError(code: String, message: String) {
        if (code == "STREAM_CONNECTION_FAILED" || code == "CAMERA_INIT_FAILED") {
            val session = managedSession
            val command = managedStartCommand
            if (session != null && command != null) {
                managedStartCommand = null
                streamCommandResult(session, command, "start", "Native streaming start failed (" + code + ")")
            }
        }
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
