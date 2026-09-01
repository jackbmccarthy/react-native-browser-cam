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
    private var followsDeviceOrientation: Bool = true
    private var orientationMode: String = "auto"
    private var isAudioMuted: Bool = false
    private var lastAudioLevelEmitTime: CFTimeInterval = 0
    
    /// React Native event callbacks
    @objc public var onStreamStateChange: RCTDirectEventBlock?
    @objc public var onStreamStats: RCTDirectEventBlock?
    @objc public var onAudioLevel: RCTDirectEventBlock?
    @objc public var onError: RCTDirectEventBlock?
    @objc public var onWebViewLoad: RCTDirectEventBlock?
    @objc public var onWebViewError: RCTDirectEventBlock?
    @objc public var onCameraReady: RCTDirectEventBlock?
    @objc public var onRecordingStateChange: RCTDirectEventBlock?
    @objc public var onRecordingFinished: RCTDirectEventBlock?
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
        guard followsDeviceOrientation else { return }

        let orientation = orientationMode == "landscape"
            ? currentCaptureOrientation(landscapeOnly: true)
            : currentCaptureOrientation(landscapeOnly: false)

        if let orientation = orientation {
            print("[BrowserCameraView] 📱 Device orientation changed to: \(orientation.rawValue)")
            applyCaptureOrientation(orientation)
        }
    }
    
    private func attachSessionToPreview() {
        guard let session = cameraService?.getCaptureSession() else { return }
        
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }

            print("[BrowserCameraView] Attaching session to preview layer")
            self.previewLayer?.session = session
            self.applyPreviewOrientation()
        }
    }

    private func applyPreviewOrientation() {
        let orientation = cameraService?.orientation ?? .landscapeRight

        if let connection = previewLayer?.connection, connection.isVideoOrientationSupported {
            connection.videoOrientation = orientation
        }

        if let connection = previewLayer?.connection, connection.isVideoMirroringSupported {
            connection.automaticallyAdjustsVideoMirroring = false
            connection.isVideoMirrored = false
        }
    }
    
    private func cleanup() {
        if isRecording {
            _ = streamService?.stopRecording()
        }

        let camera = cameraService
        cameraService = nil
        camera?.delegate = nil
        previewLayer?.session = nil
        camera?.stop()

        webOverlayService?.delegate = nil
        webOverlayService?.stopCapture()
        compositorService?.delegate = nil
        compositorService?.stop()
        streamService?.delegate = nil
        streamService?.disconnect()
        NotificationCenter.default.removeObserver(self)
        UIDevice.current.endGeneratingDeviceOrientationNotifications()
        print("[BrowserCameraView] Cleanup completed")
    }
    
    // MARK: - Configuration Updates
    
    private func updateCameraConfig() {
        guard let config = cameraConfig else { return }
        
        // Parse concrete camera device first, then fall back to facing.
        // JS may send "front"/"back" as fallback ids when native enumeration is unavailable.
        if let deviceId = config["deviceId"] as? String, !deviceId.isEmpty {
            if deviceId == "front" {
                cameraService?.setCameraPosition(.front)
            } else if deviceId == "back" {
                cameraService?.setCameraPosition(.back)
            } else {
                cameraService?.setCameraDevice(deviceId)
            }
        } else if let facing = config["facing"] as? String {
            if facing == "front" {
                cameraService?.setCameraPosition(.front)
            } else if facing == "back" {
                cameraService?.setCameraPosition(.back)
            }
        }

        // Parse capture resolution
        if let resolution = config["resolution"] as? String {
            cameraService?.setSessionPreset(resolution)
        }

        // Parse preferred audio input
        if let audioInputId = config["audioInputId"] as? String {
            cameraService?.setAudioInput(audioInputId)
        }
        
        // Parse orientation
        if let orientationStr = config["orientation"] as? String {
            orientationMode = orientationStr
            followsDeviceOrientation = orientationStr == "auto" || orientationStr == "landscape"
            if let orientation = resolveCaptureOrientation(from: orientationStr) {
                applyCaptureOrientation(orientation)
            }
        } else {
            orientationMode = "auto"
            followsDeviceOrientation = true
            handleOrientationChange()
        }
        
        // Parse frame rate
        if let fps = config["fps"] as? Int {
            cameraService?.setFrameRate(fps)
        }

        // Parse autofocus preference. Default to continuous autofocus for live camera use.
        let autoFocusEnabled = config["autoFocusEnabled"] as? Bool ?? true
        cameraService?.setAutoFocusEnabled(autoFocusEnabled)
        
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
            return currentCaptureOrientation(landscapeOnly: false)
        case "portrait":
            return .portrait
        case "portraitUpsideDown":
            return .portraitUpsideDown
        case "landscapeLeft":
            return .landscapeLeft
        case "landscapeRight":
            return .landscapeRight
        case "landscape":
            return currentCaptureOrientation(landscapeOnly: true) ?? cameraService?.orientation ?? .landscapeRight
        default:
            return nil
        }
    }

    private func currentCaptureOrientation(landscapeOnly: Bool) -> AVCaptureVideoOrientation? {
        if let interfaceOrientation = currentInterfaceOrientation(),
           let captureOrientation = captureOrientation(for: interfaceOrientation),
           !landscapeOnly || captureOrientation == .landscapeLeft || captureOrientation == .landscapeRight {
            return captureOrientation
        }

        if let captureOrientation = captureOrientation(for: UIDevice.current.orientation),
           !landscapeOnly || captureOrientation == .landscapeLeft || captureOrientation == .landscapeRight {
            return captureOrientation
        }

        if landscapeOnly,
           let currentOrientation = cameraService?.orientation,
           currentOrientation == .landscapeLeft || currentOrientation == .landscapeRight {
            return currentOrientation
        }

        return nil
    }

    private func currentInterfaceOrientation() -> UIInterfaceOrientation? {
        if let orientation = window?.windowScene?.interfaceOrientation,
           orientation != .unknown {
            return orientation
        }

        return UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }?
            .interfaceOrientation
    }

    private func captureOrientation(for interfaceOrientation: UIInterfaceOrientation) -> AVCaptureVideoOrientation? {
        switch interfaceOrientation {
        case .portrait:
            return .portrait
        case .portraitUpsideDown:
            return .portraitUpsideDown
        case .landscapeLeft:
            return .landscapeLeft
        case .landscapeRight:
            return .landscapeRight
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
            return .landscapeLeft
        case .landscapeRight:
            return .landscapeRight
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

            if let connection = self?.previewLayer?.connection, connection.isVideoMirroringSupported {
                connection.automaticallyAdjustsVideoMirroring = false
                connection.isVideoMirrored = false
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

        if let audioEnabled = config["audioEnabled"] as? Bool {
            isAudioMuted = !audioEnabled
            streamService?.setMuted(!audioEnabled)
        }
        
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
        applyPreviewOrientation()
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
            let viewportSizes = self.webOverlayService?.overlayViewportSizes ?? []
            let webViews = self.webOverlayService?.webViews ?? []

            for (index, webView) in webViews.enumerated() {
                let rect = index < rects.count ? rects[index] : CGRect(x: 0, y: 0, width: 1, height: 1)
                let displayFrame = CGRect(
                    x: rect.origin.x * parentWidth,
                    y: rect.origin.y * parentHeight,
                    width: rect.size.width * parentWidth,
                    height: rect.size.height * parentHeight
                )
                let viewportSize = index < viewportSizes.count ? viewportSizes[index] : nil
                let browserWidth = max(viewportSize?.width ?? displayFrame.width, 1)
                let browserHeight = max(viewportSize?.height ?? displayFrame.height, 1)
                let scaleX = displayFrame.width / browserWidth
                let scaleY = displayFrame.height / browserHeight

                webView.transform = .identity
                webView.layer.anchorPoint = CGPoint(x: 0, y: 0)
                webView.frame = CGRect(
                    x: displayFrame.origin.x,
                    y: displayFrame.origin.y,
                    width: browserWidth,
                    height: browserHeight
                )
                webView.transform = CGAffineTransform(scaleX: scaleX, y: scaleY)
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

    /// Focus camera/exposure at a view-space point from React Native.
    @objc public func focusAtPoint(_ x: NSNumber, y: NSNumber) {
        let rawPoint = CGPoint(x: CGFloat(truncating: x), y: CGFloat(truncating: y))
        let devicePoint: CGPoint

        if rawPoint.x >= 0, rawPoint.x <= 1, rawPoint.y >= 0, rawPoint.y <= 1 {
            devicePoint = rawPoint
        } else if let previewLayer = previewLayer {
            devicePoint = previewLayer.captureDevicePointConverted(fromLayerPoint: rawPoint)
        } else {
            let safeWidth = max(bounds.width, 1)
            let safeHeight = max(bounds.height, 1)
            devicePoint = CGPoint(x: rawPoint.x / safeWidth, y: rawPoint.y / safeHeight)
        }

        let autoFocusEnabled = cameraConfig?["autoFocusEnabled"] as? Bool ?? true
        cameraService?.focus(at: devicePoint, lockAfterFocus: !autoFocusEnabled)
    }
    
    /// Mute/unmute audio
    @objc public func setMuted(_ muted: Bool) {
        isAudioMuted = muted
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
        if streamService?.isRecording == true {
            isRecording = true
            sendRecordingStateEvent("recording", path: outputPath)
        } else {
            sendRecordingStateEvent("error")
            sendErrorEvent("Unable to start recording.", code: "RECORDING_FAILED")
        }
    }
    
    @objc public func stopRecording() -> String {
        guard isRecording else { return "" }
        
        print("[BrowserCameraView] Stopping local recording")
        isRecording = false
        sendRecordingStateEvent("processing")
        
        // Only stop compositor and capture if we aren't also streaming
        let sState = streamService?.state ?? .idle
        if sState != .streaming && sState != .connecting {
            webOverlayService?.stopCapture()
            compositorService?.stop()
        }
        
        guard let url = streamService?.stopRecording() else {
            sendRecordingStateEvent("error")
            sendErrorEvent("Unable to finish recording.", code: "RECORDING_FAILED")
            return ""
        }
        
        // Save to Photos
        saveToPhotos(url: url)
        sendRecordingStateEvent("idle", path: url.path)
        sendRecordingFinishedEvent(url.path)
        
        return url.path
    }
    
    private func saveToPhotos(url: URL) {
        PHPhotoLibrary.requestAuthorization { status in
            if status == .authorized || status == .limited {
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

    private func sendAudioLevelEvent(level: Float, decibels: Float) {
        let now = CACurrentMediaTime()
        guard now - lastAudioLevelEmitTime >= 0.1 else { return }

        lastAudioLevelEmitTime = now
        let normalizedLevel = max(0, min(1, level))
        let displayDecibels = max(-60, min(0, decibels))
        onAudioLevel?([
            "active": normalizedLevel > 0.02,
            "level": isAudioMuted ? 0.0 : normalizedLevel,
            "decibels": isAudioMuted ? -60.0 : displayDecibels
        ])
    }

    private func audioLevelMetrics(for sampleBuffer: CMSampleBuffer) -> (level: Float, decibels: Float) {
        guard let formatDescription = CMSampleBufferGetFormatDescription(sampleBuffer),
              let streamDescription = CMAudioFormatDescriptionGetStreamBasicDescription(formatDescription) else {
            return (0, -60)
        }

        let asbd = streamDescription.pointee
        let isFloat = (asbd.mFormatFlags & kAudioFormatFlagIsFloat) != 0
        let bitsPerChannel = Int(asbd.mBitsPerChannel)
        var neededSize = 0

        var status = CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sampleBuffer,
            bufferListSizeNeededOut: &neededSize,
            bufferListOut: nil,
            bufferListSize: 0,
            blockBufferAllocator: kCFAllocatorDefault,
            blockBufferMemoryAllocator: kCFAllocatorDefault,
            flags: kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment,
            blockBufferOut: nil
        )

        guard status == noErr, neededSize > 0 else {
            return (0, -60)
        }

        var blockBuffer: CMBlockBuffer?
        var storage = [UInt8](repeating: 0, count: neededSize)
        var sumSquares: Float = 0
        var sampleCount = 0

        storage.withUnsafeMutableBytes { rawBuffer in
            guard let bufferListPointer = rawBuffer.bindMemory(to: AudioBufferList.self).baseAddress else {
                return
            }

            status = CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
                sampleBuffer,
                bufferListSizeNeededOut: nil,
                bufferListOut: bufferListPointer,
                bufferListSize: neededSize,
                blockBufferAllocator: kCFAllocatorDefault,
                blockBufferMemoryAllocator: kCFAllocatorDefault,
                flags: kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment,
                blockBufferOut: &blockBuffer
            )

            guard status == noErr else { return }

            for audioBuffer in UnsafeMutableAudioBufferListPointer(bufferListPointer) {
                guard let data = audioBuffer.mData else { continue }
                let byteCount = Int(audioBuffer.mDataByteSize)

                if isFloat && bitsPerChannel == 32 {
                    let count = byteCount / MemoryLayout<Float>.size
                    let samples = data.assumingMemoryBound(to: Float.self)
                    for index in 0..<count {
                        let sample = samples[index]
                        sumSquares += sample * sample
                    }
                    sampleCount += count
                } else if bitsPerChannel == 16 {
                    let count = byteCount / MemoryLayout<Int16>.size
                    let samples = data.assumingMemoryBound(to: Int16.self)
                    for index in 0..<count {
                        let sample = Float(samples[index]) / Float(Int16.max)
                        sumSquares += sample * sample
                    }
                    sampleCount += count
                } else if bitsPerChannel == 32 {
                    let count = byteCount / MemoryLayout<Int32>.size
                    let samples = data.assumingMemoryBound(to: Int32.self)
                    for index in 0..<count {
                        let sample = Float(samples[index]) / Float(Int32.max)
                        sumSquares += sample * sample
                    }
                    sampleCount += count
                }
            }
        }

        guard status == noErr, sampleCount > 0 else {
            return (0, -60)
        }

        let rms = sqrt(sumSquares / Float(sampleCount))
        let decibels = max(-60, min(0, 20 * log10(max(rms, 0.000_001))))
        let level = max(0, min(1, (decibels + 60) / 60))

        return (level, decibels)
    }
    
    private func sendErrorEvent(_ message: String, code: String = "STREAMING_ERROR") {
        onError?(["message": message, "code": code])
    }

    private func sendRecordingStateEvent(_ state: String, path: String? = nil) {
        var payload: [String: Any] = ["state": state]
        if let path = path {
            payload["path"] = path
        }
        onRecordingStateChange?(payload)
    }

    private func sendRecordingFinishedEvent(_ path: String) {
        onRecordingFinished?(["path": path])
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
        let metrics = audioLevelMetrics(for: sampleBuffer)
        sendAudioLevelEvent(level: metrics.level, decibels: metrics.decibels)

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
