//
//  BrowserCameraView.swift
//  react-native-web-cam-stream
//
//  Main UIView for iOS - Composites WebView over Camera feed
//

import UIKit
import AVFoundation
import WebKit
import React
import Photos

/// Main view that composites a WebView over a camera feed
/// and handles streaming/recording
@objc(BrowserCameraView)
public class BrowserCameraView: UIView {
    
    // MARK: - Properties
    
    /// Camera service for capturing video frames
    private var cameraService: CameraService?
    
    /// WebView overlay service
    private var webOverlayService: WebOverlayService?
    
    /// Compositor for merging camera and WebView frames
    private var compositorService: CompositorService?
    
    /// RTMP streaming service
    private var streamService: StreamService?
    
    /// Preview layer for displaying the camera feed
    private var previewLayer: AVCaptureVideoPreviewLayer?
    
    /// Container for the WebView overlay
    private var webViewContainer: UIView?
    
    /// Recording writer
    private var assetWriter: AVAssetWriter?
    private var videoWriterInput: AVAssetWriterInput?
    private var audioWriterInput: AVAssetWriterInput?
    private var pixelBufferAdaptor: AVAssetWriterInputPixelBufferAdaptor?
    private var isRecording: Bool = false
    private var recordingStartTime: CMTime?
    private var recordingFilePath: String?
    
    /// Diagnostic counters
    private var cameraFrameCount: Int = 0
    private var compositeFrameCount: Int = 0
    
    /// React Native event callbacks
    @objc public var onStreamStateChange: RCTDirectEventBlock?
    @objc public var onStreamStats: RCTDirectEventBlock?
    @objc public var onError: RCTDirectEventBlock?
    @objc public var onWebViewLoad: RCTDirectEventBlock?
    @objc public var onWebViewError: RCTDirectEventBlock?
    @objc public var onCameraReady: RCTDirectEventBlock?
    @objc public var onLog: RCTDirectEventBlock?
    
    // MARK: - Configuration Properties (set from React Native)
    
    @objc public var cameraConfig: [String: Any]? {
        didSet {
            updateCameraConfig()
        }
    }
    
    @objc public var webViewConfig: [String: Any]? {
        didSet {
            updateWebViewConfig()
        }
    }
    
    @objc public var streamConfig: [String: Any]? {
        didSet {
            updateStreamConfig()
        }
    }
    
    @objc public var recordingConfig: [String: Any]? {
        didSet {
            updateRecordingConfig()
        }
    }
    
    // MARK: - Initialization
    
    public override init(frame: CGRect) {
        super.init(frame: frame)
        setupView()
    }
    
    required init?(coder: NSCoder) {
        super.init(coder: coder)
        setupView()
    }
    
    deinit {
        cleanup()
    }
    
    // MARK: - Setup
    
    private func setupView() {
        backgroundColor = .black
        clipsToBounds = true
        
        // 1. Create CameraService
        let camera = CameraService()
        camera.delegate = self
        cameraService = camera
        
        // 2. Create WebOverlayService
        let webOverlay = WebOverlayService()
        webOverlay.delegate = self
        webOverlayService = webOverlay
        
        // 3. Create CompositorService
        let compositor = CompositorService()
        compositor.delegate = self
        compositorService = compositor
        
        // 4. Create StreamService
        let stream = StreamService()
        stream.delegate = self
        streamService = stream
        
        // 5. Setup WebView on main thread (must be on main)
        webOverlay.setupWebView(frame: bounds)
        
        // 6. Setup Preview Layer
        let layer = AVCaptureVideoPreviewLayer()
        layer.videoGravity = .resizeAspectFill
        layer.frame = bounds
        self.layer.insertSublayer(layer, at: 0)
        previewLayer = layer
        
        // 7. Add WebView as a visual overlay (transparent, on top of preview)
        let container = UIView(frame: bounds)
        container.backgroundColor = .clear
        container.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        container.isUserInteractionEnabled = false
        webViewContainer = container
        addSubview(container)
        syncVisualWebViews()
        
        print("[BrowserCameraView] View initialized - All services created and preview layer added")
        
        // 8. Listen for device orientation changes
        UIDevice.current.beginGeneratingDeviceOrientationNotifications()
        NotificationCenter.default.addObserver(self, selector: #selector(handleOrientationChange), name: UIDevice.orientationDidChangeNotification, object: nil)
        
        // 9. Initial orientation check
        handleOrientationChange()
    }
    
    @objc private func handleOrientationChange() {
        let deviceOrientation = UIDevice.current.orientation

        if let orientation = captureOrientation(for: deviceOrientation) {
            print("[BrowserCameraView] 📱 Device orientation changed to: \(orientation.rawValue)")
            applyCaptureOrientation(orientation)
        }
    }
    
    private func attachSessionToPreview() {
        guard let session = cameraService?.getCaptureSession() else { return }
        
        DispatchQueue.main.async { [weak self] in
            print("[BrowserCameraView] Attaching session to preview layer")
            self?.previewLayer?.session = session
        }
    }
    
    private func cleanup() {
        if isRecording {
            _ = streamService?.stopRecording()
        }
        cameraService?.stop()
        webOverlayService?.stopCapture()
        compositorService?.stop()
        streamService?.disconnect()
        NotificationCenter.default.removeObserver(self)
        UIDevice.current.endGeneratingDeviceOrientationNotifications()
        print("[BrowserCameraView] Cleanup completed")
    }
    
    // MARK: - Configuration Updates
    
    private func updateCameraConfig() {
        guard let config = cameraConfig else { return }
        
        // Parse camera facing
        if let facing = config["facing"] as? String {
            if (facing == "front" && cameraService != nil) {
                // Will switch on next start if not already front
            } else if (facing == "back" && cameraService != nil) {
                // Will switch on next start if not already back
            }
        }
        
        // Parse orientation
        if let orientationStr = config["orientation"] as? String {
            if let orientation = resolveCaptureOrientation(from: orientationStr) {
                applyCaptureOrientation(orientation)
            }
        } else {
            // No explicit orientation in config - handleOrientationChange will handle it based on device
        }
        
        // Parse frame rate
        if let fps = config["fps"] as? Int {
            cameraService?.setFrameRate(fps)
        }
        
        // Parse enabled state (default to true if config is set, unless explicitly false)
        let enabled = config["enabled"] as? Bool ?? true
        if enabled {
            if !(cameraService?.isRunning ?? false) {
                cameraService?.start()
            }
            // Ensure compositor starts if camera is enabled (so frames can flow)
            compositorService?.start()
        } else {
            if isRecording {
                _ = stopRecording()
            }
            streamService?.stopPublishing()
            streamService?.disconnect()
            webOverlayService?.stopCapture()
            cameraService?.stop()
            compositorService?.stop()
            DispatchQueue.main.async { [weak self] in
                self?.previewLayer?.session = nil
            }
        }
        
        print("[BrowserCameraView] Camera config updated: \(config)")
    }

    private func resolveCaptureOrientation(from orientation: String) -> AVCaptureVideoOrientation? {
        switch orientation {
        case "auto":
            return captureOrientation(for: UIDevice.current.orientation)
        case "portrait":
            return .portrait
        case "portraitUpsideDown":
            return .portraitUpsideDown
        case "landscapeLeft":
            return .landscapeLeft
        case "landscapeRight":
            return .landscapeRight
        case "landscape":
            return captureOrientation(for: UIDevice.current.orientation) ?? cameraService?.orientation ?? .landscapeRight
        default:
            return nil
        }
    }

    private func captureOrientation(for deviceOrientation: UIDeviceOrientation) -> AVCaptureVideoOrientation? {
        switch deviceOrientation {
        case .portrait:
            return .portrait
        case .portraitUpsideDown:
            return .portraitUpsideDown
        case .landscapeLeft:
            // UIDevice and AVCapture use opposite left/right semantics.
            return .landscapeRight
        case .landscapeRight:
            return .landscapeLeft
        default:
            return nil
        }
    }

    private func applyCaptureOrientation(_ orientation: AVCaptureVideoOrientation) {
        cameraService?.setOrientation(orientation)

        DispatchQueue.main.async { [weak self] in
            if let connection = self?.previewLayer?.connection, connection.isVideoOrientationSupported {
                connection.videoOrientation = orientation
            }
        }
    }
    
    private func updateWebViewConfig() {
        guard let config = webViewConfig else { return }

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }

            self.webOverlayService?.updateConfig(config)
            let rects = self.webOverlayService?.overlayRects ?? []
            if let firstRect = rects.first {
                self.compositorService?.setWebViewLayout(
                    x: firstRect.origin.x,
                    y: firstRect.origin.y,
                    width: firstRect.size.width,
                    height: firstRect.size.height
                )
            }
            self.syncVisualWebViews()
            print("[BrowserCameraView] WebView config updated with \(rects.count) overlay(s)")
        }
    }
    
    private func updateStreamConfig() {
        guard let config = streamConfig else { return }
        
        streamService?.configure(config: config)
        
        // Update compositor output size if specified
        if let width = config["width"] as? Int, let height = config["height"] as? Int {
            compositorService?.setOutputSize(width: width, height: height)
        }
        
        print("[BrowserCameraView] Stream config updated: \(config)")
    }
    
    private func updateRecordingConfig() {
        guard let config = recordingConfig else { return }
        
        if let outputPath = config["outputPath"] as? String {
            recordingFilePath = outputPath
        }
        
        print("[BrowserCameraView] Recording config updated: \(config)")
    }
    
    // MARK: - Layout
    
    public override func layoutSubviews() {
        super.layoutSubviews()
        previewLayer?.frame = bounds
        syncVisualWebViews()
    }

    private func syncVisualWebViews() {
        guard let container = webViewContainer else { return }

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            container.frame = self.bounds
            container.subviews.forEach { $0.removeFromSuperview() }

            let parentWidth = self.bounds.width
            let parentHeight = self.bounds.height
            let rects = self.webOverlayService?.overlayRects ?? []
            let webViews = self.webOverlayService?.webViews ?? []

            for (index, webView) in webViews.enumerated() {
                let rect = index < rects.count ? rects[index] : CGRect(x: 0, y: 0, width: 1, height: 1)
                let frame = CGRect(
                    x: rect.origin.x * parentWidth,
                    y: rect.origin.y * parentHeight,
                    width: rect.size.width * parentWidth,
                    height: rect.size.height * parentHeight
                )
                webView.frame = frame
                webView.autoresizingMask = []
                container.addSubview(webView)
            }
        }
    }
    
    // MARK: - Public Methods (Called from ViewManager)
    
    /// Start RTMP streaming
    @objc public func startStreaming() {
        print("[BrowserCameraView] startStreaming called")
        
        // 1. Start camera if not running
        if !(cameraService?.isRunning ?? false) {
            cameraService?.start()
        }
        
        // 2. Start compositor
        compositorService?.start()
        
        // 3. Start WebView frame capture
        webOverlayService?.startCapture()
        
        // 4. Connect to RTMP server
        streamService?.connect()
    }
    
    /// Stop RTMP streaming
    @objc public func stopStreaming() {
        print("[BrowserCameraView] stopStreaming called")
        
        // 1. Stop publishing
        streamService?.stopPublishing()
        
        // 2. Disconnect
        streamService?.disconnect()
        
        // 3. Stop WebView capture
        webOverlayService?.stopCapture()
        
        // 4. Stop compositor
        compositorService?.stop()
    }
    
    /// Start recording to file
    // @objc public func startRecording() -> Implemented below
    
    /// Stop recording and return file path
    // @objc public func stopRecording() -> Implemented below
    
    /// Take a snapshot
    @objc public func takeSnapshot(_ options: [String: Any]) -> [String: Any] {
        print("[BrowserCameraView] takeSnapshot called with options: \(options)")
        
        let quality = options["quality"] as? Int ?? 85
        let format = options["format"] as? String ?? "jpeg"
        let includeOverlay = options["includeOverlay"] as? Bool ?? true
        
        // Capture the current view hierarchy as an image
        let renderer = UIGraphicsImageRenderer(bounds: bounds)
        let image = renderer.image { context in
            // Render preview layer
            previewLayer?.render(in: context.cgContext)
            
            // Render WebView overlay if requested
            if includeOverlay {
                webOverlayService?.webViews.forEach { $0.layer.render(in: context.cgContext) }
            }
        }
        
        // Save to temp file
        let fileName = "snapshot_\(Int(Date().timeIntervalSince1970)).\(format)"
        let tempDir = NSTemporaryDirectory()
        let filePath = (tempDir as NSString).appendingPathComponent(fileName)
        
        var imageData: Data?
        if format == "png" {
            imageData = image.pngData()
        } else {
            imageData = image.jpegData(compressionQuality: CGFloat(quality) / 100.0)
        }
        
        if let data = imageData {
            do {
                try data.write(to: URL(fileURLWithPath: filePath))
                return [
                    "uri": filePath,
                    "width": Int(image.size.width * image.scale),
                    "height": Int(image.size.height * image.scale),
                    "format": format
                ]
            } catch {
                print("[BrowserCameraView] Failed to save snapshot: \(error)")
                return ["error": error.localizedDescription]
            }
        }
        
        return ["error": "Failed to create image data"]
    }
    
    /// Reload WebView content
    @objc public func reloadWebView() {
        webOverlayService?.reload()
    }
    
    /// Load a new URL in WebView
    @objc public func loadUrl(_ url: String) {
        webOverlayService?.loadUrl(url)
    }
    
    /// Inject JavaScript into WebView
    @objc public func injectJavaScript(_ js: String) {
        webOverlayService?.injectJavaScript(js)
    }
    
    /// Switch camera facing
    @objc public func switchCamera() {
        cameraService?.switchCamera()
    }
    
    /// Mute/unmute audio
    @objc public func setMuted(_ muted: Bool) {
        streamService?.setMuted(muted)
    }
    
    /// Get current stream state
    @objc public func getStreamState() -> String {
        return streamService?.state.rawValue ?? "idle"
    }
    
    /// Get current stream stats
    @objc public func getStreamStats() -> [String: Any] {
        return streamService?.getStats() ?? [:]
    }
    
    // MARK: - Recording Methods
    
    @objc public func startRecording() {
        print("[BrowserCameraView] startRecording called")
        
        // 1. Start camera if not running
        if !(cameraService?.isRunning ?? false) {
            cameraService?.start()
        }
        
        // 2. Start compositor
        compositorService?.start()
        
        // 3. Start WebView frame capture
        webOverlayService?.startCapture()
        
        // Ensure microphone permission
        AVCaptureDevice.requestAccess(for: .audio) { granted in
            if !granted {
                print("[BrowserCameraView] ❌ Microphone permission denied for recording")
            }
        }
        
        let tempDir = NSTemporaryDirectory()
        let fileName = "recording_\(Int(Date().timeIntervalSince1970)).mp4"
        let outputPath = (tempDir as NSString).appendingPathComponent(fileName)
        let outputURL = URL(fileURLWithPath: outputPath)
        
        print("[BrowserCameraView] Starting local recording to \(outputPath)")
        streamService?.startRecording(to: outputURL)
        isRecording = true
    }
    
    @objc public func stopRecording() -> String {
        guard isRecording else { return "" }
        
        print("[BrowserCameraView] Stopping local recording")
        isRecording = false
        
        // Only stop compositor and capture if we aren't also streaming
        let sState = streamService?.state ?? .idle
        if sState != .streaming && sState != .connecting {
            webOverlayService?.stopCapture()
            compositorService?.stop()
        }
        
        guard let url = streamService?.stopRecording() else {
            return ""
        }
        
        // Save to Photos
        saveToPhotos(url: url)
        
        return url.path
    }
    
    private func saveToPhotos(url: URL) {
        PHPhotoLibrary.requestAuthorization { status in
            if status == .authorized {
                PHPhotoLibrary.shared().performChanges({
                    PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: url)
                }) { success, error in
                    if success {
                        print("[BrowserCameraView] ✅ Video saved to Photos")
                    } else {
                        print("[BrowserCameraView] ❌ Error saving video to Photos: \(String(describing: error))")
                    }
                }
            } else {
                print("[BrowserCameraView] ❌ Photo Library access denied")
            }
        }
    }
    
    // MARK: - Event Helpers
    
    private func sendStreamStateEvent(_ state: String) {
        onStreamStateChange?(["state": state])
    }
    
    private func sendStreamStatsEvent(_ stats: [String: Any]) {
        onStreamStats?(stats)
    }
    
    private func sendErrorEvent(_ message: String) {
        onError?(["message": message, "code": "STREAMING_ERROR"])
    }
}

// MARK: - CameraServiceDelegate

extension BrowserCameraView: CameraServiceDelegate {
    
    func cameraServiceDidStart(_ service: CameraService) {
        print("[BrowserCameraView] Camera service started")
        attachSessionToPreview()
        onCameraReady?([:])
    }
    
    func cameraServiceDidStop(_ service: CameraService) {
        print("[BrowserCameraView] Camera stopped")
        DispatchQueue.main.async { [weak self] in
            self?.previewLayer?.session = nil
        }
    }
    
    func cameraService(_ service: CameraService, didOutput sampleBuffer: CMSampleBuffer) {
        cameraFrameCount += 1
        
        // Forward camera frames through compositor
        if compositorService?.isRunning != true {
            // Self-healing: If we are supposed to be streaming or recording, start the compositor
            let sState = streamService?.state ?? .idle
            if sState == .streaming || sState == .connecting || isRecording {
                print("[BrowserCameraView] 🔄 Self-healing: Starting compositor (Stream State: \(sState.rawValue))")
                compositorService?.start()
            } else {
                return
            }
        }
        
        // Get current WebView snapshot for compositing
        let webFrames = webOverlayService?.captureFrames() ?? []
        
        // Composite camera + web overlays
        if let compositedBuffer = compositorService?.composite(cameraBuffer: sampleBuffer, webFrames: webFrames) {
            compositeFrameCount += 1
            
            if compositeFrameCount % 100 == 0 {
                let msg = "[BrowserCameraView] 🔄 Compositor Heartbeat - Proc: \(cameraFrameCount), Out: \(compositeFrameCount)"
                print(msg)
                onLog?(["message": msg])
            }
            
            // Send to stream
            streamService?.sendVideoFrame(compositedBuffer)
            
            // Write to recording (StreamService handles this now)
            if let service = streamService, service.isRecording {
                service.recordVideoFrame(compositedBuffer)
            }
        }
    }
    
    func cameraService(_ service: CameraService, didOutputAudio sampleBuffer: CMSampleBuffer) {
        guard let service = streamService else { return }
        
        // Forward audio buffers directly to stream
        if service.state == .streaming || service.state == .connecting {
             service.sendAudioBuffer(sampleBuffer)
        }
        
        // Write to recording if active
        if service.isRecording {
            service.recordAudioFrame(sampleBuffer)
        }
    }
    
    func cameraServiceDidSwitchCamera(_ service: CameraService, position: AVCaptureDevice.Position) {
        print("[BrowserCameraView] Camera switched to \(position == .front ? "front" : "back")")
    }
}

// MARK: - WebOverlayServiceDelegate

extension BrowserCameraView: WebOverlayServiceDelegate {
    
    func webOverlayServiceDidStartLoad(_ service: WebOverlayService) {
        print("[BrowserCameraView] WebView started loading")
    }
    
    func webOverlayServiceDidFinishLoad(_ service: WebOverlayService) {
        print("[BrowserCameraView] WebView finished loading")
        onWebViewLoad?([:])
    }
    
    func webOverlayService(_ service: WebOverlayService, didFailWithError error: String) {
        print("[BrowserCameraView] WebView error: \(error)")
        onWebViewError?(["error": error])
    }
    
    func webOverlayService(_ service: WebOverlayService, didCaptureFrame image: UIImage, index: Int) {
        // Frame captured — will be used on next camera frame composite
    }
}

// MARK: - CompositorServiceDelegate

extension BrowserCameraView: CompositorServiceDelegate {
    
    func compositorServiceDidStart(_ service: CompositorService) {
        print("[BrowserCameraView] Compositor started")
    }
    
    func compositorServiceDidStop(_ service: CompositorService) {
        print("[BrowserCameraView] Compositor stopped")
    }
    
    func compositorService(_ service: CompositorService, didComposite sampleBuffer: CMSampleBuffer) {
        // Additional composited frame handling if needed
    }
}

// MARK: - StreamServiceDelegate

extension BrowserCameraView: StreamServiceDelegate {
    
    func streamService(_ service: StreamService, didChangeState state: StreamState) {
        print("[BrowserCameraView] Stream state changed: \(state.rawValue)")
        if onStreamStateChange == nil {
            print("[BrowserCameraView] ⚠️ onStreamStateChange is NIL!")
        }
        sendStreamStateEvent(state.rawValue)
    }
    
    func streamService(_ service: StreamService, didUpdateStats stats: StreamStats) {
        sendStreamStatsEvent([
            "bitrate": stats.bitrate,
            "fps": stats.fps,
            "droppedFrames": stats.droppedFrames,
            "duration": stats.duration
        ])
    }
    
    func streamService(_ service: StreamService, didFailWithError error: Error) {
        print("[BrowserCameraView] Stream error: \(error)")
        sendErrorEvent(error.localizedDescription)
    }
    
    func streamServiceDidConnect(_ service: StreamService) {
        print("[BrowserCameraView] Stream connected (publish handled by connect flow)")
    }
    
    func streamServiceDidDisconnect(_ service: StreamService) {
        print("[BrowserCameraView] Stream disconnected")
    }
    
    func streamService(_ service: StreamService, didLog message: String) {
        if onLog == nil {
            // print("[BrowserCameraView] ⚠️ onLog is NIL!")
        }
        onLog?(["message": message])
    }
}
