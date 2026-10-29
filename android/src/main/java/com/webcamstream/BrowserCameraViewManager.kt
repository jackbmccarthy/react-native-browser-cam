package com.webcamstream

import android.view.View
import com.facebook.react.bridge.*
import com.facebook.react.uimanager.*
import com.facebook.react.uimanager.annotations.ReactProp

/**
 * React Native View Manager for BrowserCameraView.
 * Bridges React Native props and method calls to the native Android view.
 */
class BrowserCameraViewManager : SimpleViewManager<BrowserCameraView>() {

    companion object {
        const val REACT_CLASS = "BrowserCameraView"
        const val COMMAND_START_STREAMING = 1
        const val COMMAND_STOP_STREAMING = 2
        const val COMMAND_START_RECORDING = 3
        const val COMMAND_STOP_RECORDING = 4
        const val COMMAND_TAKE_SNAPSHOT = 5
        const val COMMAND_RELOAD_WEBVIEW = 6
        const val COMMAND_LOAD_URL = 7
        const val COMMAND_INJECT_JS = 8
        const val COMMAND_SWITCH_CAMERA = 9
        const val COMMAND_SET_MUTED = 10
        const val COMMAND_CONFIGURE_START = 11
        const val COMMAND_STOP_WAIT = 12
    }

    override fun getName(): String {
        return REACT_CLASS
    }

    override fun createViewInstance(reactContext: ThemedReactContext): BrowserCameraView {
        return BrowserCameraView(reactContext)
    }

    override fun onDropViewInstance(view: BrowserCameraView) {
        view.cleanup()
        super.onDropViewInstance(view)
    }

    // MARK: - Props

    @ReactProp(name = "cameraConfig")
    fun setCameraConfig(view: BrowserCameraView, config: ReadableMap?) {
        view.setCameraConfig(config?.toHashMap())
    }

    @ReactProp(name = "webViewConfig")
    fun setWebViewConfig(view: BrowserCameraView, config: ReadableMap?) {
        view.setWebViewConfig(config?.toHashMap())
    }

    @ReactProp(name = "streamConfig")
    fun setStreamConfig(view: BrowserCameraView, config: ReadableMap?) {
        view.setStreamConfig(config?.toHashMap())
    }

    @ReactProp(name = "recordingConfig")
    fun setRecordingConfig(view: BrowserCameraView, config: ReadableMap?) {
        view.setRecordingConfig(config?.toHashMap())
    }

    // MARK: - Commands

    override fun getCommandsMap(): Map<String, Int> {
        return mapOf(
            "configureAndStartStreaming" to COMMAND_CONFIGURE_START,
            "stopStreamingAndWait" to COMMAND_STOP_WAIT,
            "startStreaming" to COMMAND_START_STREAMING,
            "stopStreaming" to COMMAND_STOP_STREAMING,
            "startRecording" to COMMAND_START_RECORDING,
            "stopRecording" to COMMAND_STOP_RECORDING,
            "takeSnapshot" to COMMAND_TAKE_SNAPSHOT,
            "reloadWebView" to COMMAND_RELOAD_WEBVIEW,
            "loadUrl" to COMMAND_LOAD_URL,
            "injectJavaScript" to COMMAND_INJECT_JS,
            "switchCamera" to COMMAND_SWITCH_CAMERA,
            "setMuted" to COMMAND_SET_MUTED
        )
    }

    override fun getExportedCustomDirectEventTypeConstants(): Map<String, Any> {
        return mapOf(
            "topStreamCommandResult" to mapOf("registrationName" to "onStreamCommandResult"),
            "topStreamStateChange" to mapOf("registrationName" to "onStreamStateChange"),
            "topStreamStats" to mapOf("registrationName" to "onStreamStats"),
            "topError" to mapOf("registrationName" to "onError"),
            "topWebViewLoad" to mapOf("registrationName" to "onWebViewLoad"),
            "topWebViewError" to mapOf("registrationName" to "onWebViewError"),
            "topCameraReady" to mapOf("registrationName" to "onCameraReady"),
            "topLog" to mapOf("registrationName" to "onLog"),
            "topAudioLevel" to mapOf("registrationName" to "onAudioLevel"),
            "topRecordingStateChange" to mapOf("registrationName" to "onRecordingStateChange"),
            "topRecordingFinished" to mapOf("registrationName" to "onRecordingFinished")
        )
    }

    override fun receiveCommand(root: BrowserCameraView, commandId: String, args: ReadableArray?) {
        val id = getCommandsMap()[commandId] ?: return
        receiveCommand(root, id, args)
    }

    override fun receiveCommand(root: BrowserCameraView, commandId: Int, args: ReadableArray?) {
        when (commandId) {
            COMMAND_CONFIGURE_START -> root.configureAndStartStreaming(
                args?.getMap(0)?.toHashMap() ?: emptyMap(),
                args?.getString(1) ?: "", args?.getString(2) ?: ""
            )
            COMMAND_STOP_WAIT -> root.stopStreamingAndWait(args?.getString(0) ?: "", args?.getString(1) ?: "")
            COMMAND_START_STREAMING -> root.startStreaming()
            COMMAND_STOP_STREAMING -> root.stopStreaming()
            COMMAND_START_RECORDING -> root.startRecording()
            COMMAND_STOP_RECORDING -> root.stopRecording()
            COMMAND_TAKE_SNAPSHOT -> {
                val options = args?.getMap(0)?.toHashMap() ?: emptyMap()
                root.takeSnapshot(options)
            }
            COMMAND_RELOAD_WEBVIEW -> root.reloadWebView()
            COMMAND_LOAD_URL -> {
                val url = args?.getString(0) ?: return
                root.loadUrl(url)
            }
            COMMAND_INJECT_JS -> {
                val js = args?.getString(0) ?: return
                root.injectJavaScript(js)
            }
            COMMAND_SWITCH_CAMERA -> root.switchCamera()
            COMMAND_SET_MUTED -> {
                val muted = args?.getBoolean(0) ?: return
                root.setMuted(muted)
            }
        }
    }

}
