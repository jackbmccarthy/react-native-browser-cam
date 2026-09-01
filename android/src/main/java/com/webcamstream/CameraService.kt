package com.webcamstream

import android.Manifest
import android.content.Context
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
        if (isRunning || isStarting) {
            Log.d(TAG, "Camera already ${if (isRunning) "running" else "starting"}")
            return
        }

        Log.d(TAG, "Starting camera...")

        // Check permissions
        if (!hasPermissions()) {
            requestCameraPermission()
            return
        }

        startCameraX()
    }

    /** Stop the camera */
    fun stop() {
        if (!isRunning && !isStarting) {
            return
        }

        Log.d(TAG, "Stopping camera...")
        removeStreamStateObserver()
        cameraProvider?.unbindAll()
        isStarting = false
        isRunning = false
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

        // Rebind with new selector
        if (isRunning) {
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
        val lifecycleOwner = getLifecycleOwner()
        if (lifecycleOwner == null) {
            val message = "Cannot start camera without a lifecycle owner"
            Log.e(TAG, message)
            isStarting = false
            onCameraError?.invoke(message)
            return
        }

        isStarting = true
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                cameraProvider = providerFuture.get()
                setupPreview()
                if (bindToLifecycle(lifecycleOwner)) {
                    isRunning = true
                    waitForPreviewStream(lifecycleOwner)
                    Log.d(TAG, "CameraX bound; waiting for preview stream")
                }
            } catch (e: Exception) {
                val message = e.message ?: "Unknown CameraX startup error"
                Log.e(TAG, "Failed to start CameraX: $message")
                onCameraError?.invoke(message)
            } finally {
                isStarting = false
            }
        }, ContextCompat.getMainExecutor(context))
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

    private fun requestCameraPermission() {
        val activity = (context as? ReactContext)?.currentActivity as? PermissionAwareActivity
        if (activity == null) {
            val message = "Camera permission not granted and no activity is available to request it"
            Log.e(TAG, message)
            onCameraError?.invoke(message)
            return
        }

        activity.requestPermissions(
            arrayOf(Manifest.permission.CAMERA),
            REQUEST_CAMERA_PERMISSION,
            PermissionListener { requestCode, _, grantResults ->
                if (requestCode != REQUEST_CAMERA_PERMISSION) {
                    return@PermissionListener false
                }

                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    start()
                } else {
                    onCameraError?.invoke("Camera permission not granted")
                }
                true
            }
        )
    }

    private fun setupPreview() {
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

    private fun waitForPreviewStream(lifecycleOwner: LifecycleOwner) {
        removeStreamStateObserver()

        val observer = object : Observer<PreviewView.StreamState> {
            override fun onChanged(state: PreviewView.StreamState) {
                Log.d(TAG, "Preview stream state: $state")
                if (state == PreviewView.StreamState.STREAMING) {
                    previewView.previewStreamState.removeObserver(this)
                    streamStateObserver = null
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
        try {
            cameraProvider?.unbindAll()
            camera = cameraProvider?.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview
            )
            return camera != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera: ${e.message}")
            onCameraError?.invoke(e.message ?: "Unknown error")
            return false
        }
    }

    companion object {
        private const val TAG = "CameraService"
        private const val REQUEST_CAMERA_PERMISSION = 43110
    }
}
