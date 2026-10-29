package com.webcamstream

import android.Manifest
import android.content.Context
import android.graphics.Rect
import android.util.Range
import java.util.UUID
import android.content.pm.PackageManager
import android.util.Size
import android.util.Log
import android.view.Surface
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.facebook.react.bridge.ReactContext
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener

/**
 * Camera service using CameraX for capturing video frames.
 * Provides camera preview frames as OpenGL textures for compositing.
 */
class CameraService(
    private val context: Context,
    private val previewView: PreviewView
) {

    // MARK: - Properties

    /// CameraX provider
    private var cameraProvider: ProcessCameraProvider? = null

    /// Camera selector (front/back)
    private var cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var cameraFacing: String = "back"

    /// Preview use case
    private var preview: Preview? = null

    /// Image analysis use case for frame processing
    private var imageAnalysis: ImageAnalysis? = null

    /// Current camera
    private var camera: Camera? = null

    private var streamStateObserver: Observer<PreviewView.StreamState>? = null

    /// Is camera running
    var isRunning: Boolean = false
        private set

    private var isStarting: Boolean = false
    // Main owns intent, provider admission and preview observer identity.
    private var cameraRequested: Boolean = false
    private var cameraGeneration: Long = 0
    private var previewReadyGeneration: Long? = null
    internal var gpuRenderer: GpuSceneRenderer? = null
    private var gpuCameraRenderer: GpuSceneRenderer? = null
    private var gpuCameraRequest: SurfaceRequest? = null
    // Main-thread readiness lease. A running/bound camera alone does not mean a
    // replacement preview has reached STREAMING or belongs to an older callback.
    val readinessGeneration: Long?
        get() = if (cameraRequested && isRunning && !isStarting &&
            previewReadyGeneration == cameraGeneration
        ) cameraGeneration else null

    /// Target frame rate
    var targetFrameRate: Int = 30

    /// Frame callback
    var onFrameAvailable: ((ImageProxy) -> Unit)? = null

    /// State callbacks
    var onCameraStarted: (() -> Unit)? = null
    var onCameraStopped: (() -> Unit)? = null
    var onCameraError: ((String) -> Unit)? = null

    // MARK: - Public Methods

    /** Start the camera */
    fun start() {
        if (cameraRequested || isRunning || isStarting) {
            Log.d(TAG, "Camera already ${if (isRunning) "running" else "starting"}")
            return
        }

        Log.d(TAG, "Starting camera...")

        cameraRequested = true
        val generation = ++cameraGeneration
        // A permission reply is part of this start, not authority to restart after stop.
        if (!hasPermissions()) {
            requestCameraPermission(generation)
            return
        }

        startCameraX()
    }

    /** Stop the camera */
    fun stop() {
        val wasActive = cameraRequested || isRunning || isStarting
        cameraRequested = false
        cameraGeneration++
        clearGpuCamera()
        removeStreamStateObserver()
        isStarting = false
        isRunning = false
        camera = null
        preview = null
        if (!wasActive) return

        Log.d(TAG, "Stopping camera...")
        cameraProvider?.unbindAll()
        onCameraStopped?.invoke()
    }

    /** Switch between front and back camera */
    fun switchCamera() {
        Log.d(TAG, "Switching camera...")
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        cameraFacing = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) "front" else "back"
        // A pending provider callback reads the latest desired selector; do not queue another.
        if (isRunning && !isStarting) {
            stop()
            start()
        }
    }

    /** Set frame rate */
    fun setFrameRate(fps: Int) {
        if (targetFrameRate == fps) {
            return
        }

        targetFrameRate = fps
        Log.d(TAG, "Frame rate set to $fps")

        // TODO: Update camera configuration
    }

    /** Keep CameraX sensor output aligned with the current locked display orientation. */
    fun updateTargetRotation() {
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        if (preview?.targetRotation != rotation) {
            preview?.targetRotation = rotation
            Log.d(TAG, "Preview target rotation updated to $rotation")
        }
    }

    /** Set camera facing direction from JS config */
    fun setFacing(facing: String?) {
        val nextFacing = when (facing) {
            "front" -> CameraSelector.DEFAULT_FRONT_CAMERA
            else -> CameraSelector.DEFAULT_BACK_CAMERA
        }
        val nextFacingName = if (facing == "front") "front" else "back"
        if (cameraFacing == nextFacingName) {
            return
        }

        cameraFacing = nextFacingName
        cameraSelector = nextFacing
        Log.d(TAG, "Camera facing set to $cameraFacing")

        if (isRunning) {
            startCameraX()
        }
    }

    // MARK: - Private Methods

    private fun startCameraX() {
        if (!cameraRequested || isStarting) return
        val generation = ++cameraGeneration
        clearGpuCamera()
        removeStreamStateObserver()
        isStarting = true
        try {
            val lifecycleOwner = getLifecycleOwner()
                ?: throw IllegalStateException("Cannot start camera without a lifecycle owner")
            // ProcessCameraProvider's future is shared: fence callbacks, never cancel it.
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener({
                if (generation != cameraGeneration || !cameraRequested) return@addListener
                try {
                    cameraProvider = providerFuture.get()
                    setupPreview()
                    // Facing changes coalesce while this callback is pending.
                    if (!bindToLifecycle(lifecycleOwner)) {
                        throw IllegalStateException("Failed to bind camera")
                    }
                    isRunning = true
                    isStarting = false
                    waitForPreviewStream(lifecycleOwner, generation)
                    Log.d(TAG, "CameraX bound; waiting for preview stream")
                } catch (error: Exception) {
                    failCameraStart(generation, error)
                } finally {
                    // Error/ready callbacks may synchronously stop and start a new generation.
                    if (generation == cameraGeneration) isStarting = false
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (error: Exception) {
            failCameraStart(generation, error)
        }
    }

    private fun failCameraStart(generation: Long, error: Exception) {
        if (generation != cameraGeneration || !cameraRequested) return
        cameraRequested = false
        clearGpuCamera()
        removeStreamStateObserver()
        isStarting = false
        isRunning = false
        camera = null
        preview = null
        try {
            cameraProvider?.unbindAll()
        } catch (cleanupError: Exception) {
            Log.w(TAG, "Failed to unbind camera after startup error", cleanupError)
        }
        val message = error.message ?: "Unknown CameraX startup error"
        Log.e(TAG, "Failed to start CameraX: $message")
        onCameraError?.invoke(message)
    }

    private fun getLifecycleOwner(): LifecycleOwner? {
        return (context as? ReactContext)?.currentActivity as? LifecycleOwner
    }

    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission(generation: Long) {
        val activity = (context as? ReactContext)?.currentActivity as? PermissionAwareActivity
        if (activity == null) {
            failCameraStart(generation, IllegalStateException("Camera permission not granted and no activity is available to request it"))
            return
        }

        try {
            activity.requestPermissions(
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA_PERMISSION,
                PermissionListener { requestCode, _, grantResults ->
                    if (requestCode != REQUEST_CAMERA_PERMISSION) {
                        return@PermissionListener false
                    }

                    ContextCompat.getMainExecutor(context).execute {
                        if (generation != cameraGeneration || !cameraRequested) return@execute
                        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                            // Recheck permission through the normal start path on Main.
                            cameraRequested = false
                            start()
                        } else {
                            failCameraStart(generation, IllegalStateException("Camera permission not granted"))
                        }
                    }
                    true
                }
            )
        } catch (error: Exception) {
            failCameraStart(generation, error)
        }
    }

    private fun setupPreview() {
        gpuRenderer?.let {
            setupGpuPreview(it, cameraGeneration)
            return
        }
        val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
        Log.d(TAG, "Setting up preview use case with targetRotation=$targetRotation")
        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .setTargetRotation(targetRotation)
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
    }

    private fun clearGpuCamera() {
        gpuCameraRequest?.clearTransformationInfoListener()
        gpuCameraRequest = null
        previewReadyGeneration = null
        gpuCameraRenderer?.clearCamera()
        gpuCameraRenderer = null
        // A provided producer belongs to CameraX until its result callback.
    }

    private fun setupGpuPreview(renderer: GpuSceneRenderer, generation: Long) {
        gpuCameraRenderer = renderer
        val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val fps = targetFrameRate.coerceAtLeast(1)
        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .setTargetRotation(targetRotation)
            .setTargetFrameRate(Range(fps, fps))
            .build()
            .also {
                it.setSurfaceProvider(ContextCompat.getMainExecutor(context)) { request ->
                    if (generation != cameraGeneration || !cameraRequested || gpuCameraRenderer !== renderer) {
                        request.willNotProvideSurface()
                    } else {
                        provideGpuCameraSurface(renderer, request, generation)
                    }
                }
            }
    }

    private fun provideGpuCameraSurface(renderer: GpuSceneRenderer, request: SurfaceRequest, generation: Long) {
        gpuCameraRequest?.clearTransformationInfoListener()
        gpuCameraRequest = request
        previewReadyGeneration = null
        renderer.clearCamera()
        val executor = ContextCompat.getMainExecutor(context)
        val id = "camera-" + UUID.randomUUID().toString()
        var cancelled = false
        var finished = false
        var inputCreated = false
        var surfaceProvided = false
        var inputReleased = false
        var transformation: SurfaceRequest.TransformationInfo? = null

        fun isCurrent(): Boolean = !cancelled && !finished &&
            generation == cameraGeneration && cameraRequested &&
            gpuCameraRenderer === renderer && gpuCameraRequest === request

        fun releaseInput() {
            if (inputCreated && !inputReleased) {
                inputReleased = true
                renderer.releaseInput(id)
            }
        }

        fun clearCurrentRequest() {
            request.clearTransformationInfoListener()
            // Completion/cancellation of an old request cannot clear its replacement.
            if (generation == cameraGeneration && gpuCameraRenderer === renderer && gpuCameraRequest === request) {
                gpuCameraRequest = null
                previewReadyGeneration = null
                renderer.clearCamera()
            }
        }

        fun publishCamera() {
            val info = transformation ?: return
            if (!inputCreated || !isCurrent()) return
            // Direct CameraX producers already encode sensor orientation in
            // SurfaceTexture. Only display rotation remains for OES sampling.
            val target = preview?.targetRotation ?: previewView.display?.rotation ?: Surface.ROTATION_0
            val textureRotation = if (info.hasCameraTransform()) when (target) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            } else info.rotationDegrees
            Log.d(TAG, "GPU camera rotation buffer=" + info.rotationDegrees + " texture=" + textureRotation + " target=" + target + " cameraTransform=" + info.hasCameraTransform())
            renderer.setCamera(id, info.rotationDegrees, Rect(info.cropRect), info.isMirroring,
                textureRotationDegrees = textureRotation) {
                executor.execute {
                    if (isCurrent() && surfaceProvided && isRunning && !isStarting && previewReadyGeneration != generation) {
                        previewReadyGeneration = generation
                        onCameraStarted?.invoke()
                    }
                }
            }
        }

        request.addRequestCancellationListener(executor) {
            cancelled = true
            clearCurrentRequest()
            if (!surfaceProvided) releaseInput()
        }
        request.setTransformationInfoListener(executor) { info ->
            if (isCurrent()) {
                transformation = info
                publishCamera()
            }
        }
        try {
            renderer.createInput(id, request.resolution.width, request.resolution.height, onFailure = { message ->
                request.willNotProvideSurface()
                if (isCurrent()) failCameraStart(generation, IllegalStateException(message))
            }) { input ->
                inputCreated = true
                if (!isCurrent()) {
                    request.willNotProvideSurface()
                    releaseInput()
                } else {
                    try {
                        publishCamera()
                        surfaceProvided = true
                        request.provideSurface(input.surface, executor) {
                            finished = true
                            clearCurrentRequest()
                            releaseInput()
                        }
                    } catch (error: Exception) {
                        // Refusal succeeds only if CameraX never accepted this surface.
                        if (request.willNotProvideSurface()) {
                            surfaceProvided = false
                            releaseInput()
                        }
                        if (isCurrent()) failCameraStart(generation, error)
                    }
                }
            }
        } catch (error: Exception) {
            request.willNotProvideSurface()
            if (!surfaceProvided) releaseInput()
            if (isCurrent()) failCameraStart(generation, error)
        }
    }

    private fun setupImageAnalysis() {
        // TODO: Configure ImageAnalysis for frame processing
        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis?.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
            onFrameAvailable?.invoke(imageProxy)
            imageProxy.close()
        }
    }

    private fun waitForPreviewStream(lifecycleOwner: LifecycleOwner, generation: Long) {
        if (gpuCameraRenderer != null) return
        if (generation != cameraGeneration || !cameraRequested || !isRunning) return
        removeStreamStateObserver()

        val observer = object : Observer<PreviewView.StreamState> {
            override fun onChanged(state: PreviewView.StreamState) {
                if (generation != cameraGeneration || !cameraRequested || !isRunning || streamStateObserver !== this) return
                Log.d(TAG, "Preview stream state: $state")
                if (state == PreviewView.StreamState.STREAMING) {
                    previewView.previewStreamState.removeObserver(this)
                    streamStateObserver = null
                    previewReadyGeneration = generation
                    onCameraStarted?.invoke()
                    Log.d(TAG, "CameraX preview streaming")
                }
            }
        }

        streamStateObserver = observer
        previewView.previewStreamState.observe(lifecycleOwner, observer)
        previewView.previewStreamState.value?.let { observer.onChanged(it) }
    }

    private fun removeStreamStateObserver() {
        streamStateObserver?.let { previewView.previewStreamState.removeObserver(it) }
        streamStateObserver = null
    }

    private fun setupVideoCapture() {
        // TODO: Configure VideoCapture for streaming
    }

    private fun bindToLifecycle(lifecycleOwner: LifecycleOwner): Boolean {
        val provider = cameraProvider ?: return false
        isRunning = false
        camera = null
        provider.unbindAll()
        camera = provider.bindToLifecycle(
            lifecycleOwner,
            cameraSelector,
            preview
        )
        return camera != null
    }

    companion object {
        private const val TAG = "CameraService"
        private const val REQUEST_CAMERA_PERMISSION = 43110
    }
}
