//
//  CameraService.swift
//  react-native-web-cam-stream
//
//  Manages AVCaptureSession for camera input
//

import Foundation
import AVFoundation

/// Stream state enum
enum StreamState: String {
    case idle = "idle"
    case connecting = "connecting"
    case streaming = "streaming"
    case error = "error"
    case disconnected = "disconnected"
}

/// Service for managing camera capture
class CameraService: NSObject {
    
    // MARK: - Properties
    
    /// Capture session
    private var captureSession: AVCaptureSession?
    
    /// Video data output for frame processing
    private var videoDataOutput: AVCaptureVideoDataOutput?
    
    /// Audio data output for frame processing
    private var audioDataOutput: AVCaptureAudioDataOutput?
    
    /// Current camera device input
    private var currentDeviceInput: AVCaptureDeviceInput?

    /// Current audio device input
    private var currentAudioDeviceInput: AVCaptureDeviceInput?
    
    /// Current camera position
    private var currentPosition: AVCaptureDevice.Position = .back

    /// Current concrete camera device, when selected by unique ID
    private var currentDeviceUniqueID: String?

    /// Current preferred AVAudioSession input UID
    private var currentAudioInputUID: String?

    /// Current capture resolution preset
    private var sessionPreset: AVCaptureSession.Preset = .hd1920x1080
    
    /// Delegate for receiving frames
    weak var delegate: CameraServiceDelegate?
    
    /// Target frame rate
    var targetFrameRate: Int = 30

    /// Whether the active camera should continuously adjust focus.
    private var autoFocusEnabled: Bool = true

    /// Default autofocus/exposure target when the user has not tapped a point.
    private let defaultFocusPoint = CGPoint(x: 0.5, y: 0.5)
    
    /// Target orientation
    var orientation: AVCaptureVideoOrientation = .landscapeRight
    
    /// Is camera running
    private(set) var isRunning: Bool = false
    
    /// Dedicated queue for video frame processing
    private let videoQueue = DispatchQueue(label: "com.webcamstream.camera.video", qos: .userInteractive)
    
    /// Dedicated queue for audio buffer processing
    private let audioQueue = DispatchQueue(label: "com.webcamstream.camera.audio", qos: .userInteractive)
    
    /// Dedicated queue for session configuration
    private let sessionQueue = DispatchQueue(label: "com.webcamstream.camera.session")
    
    // MARK: - Initialization
    
    override init() {
        super.init()
        print("[CameraService] Initialized")
    }
    
    deinit {
        videoDataOutput?.setSampleBufferDelegate(nil, queue: nil)
        audioDataOutput?.setSampleBufferDelegate(nil, queue: nil)

        // `deinit` must not call `stop()`: its asynchronous `[weak self]`
        // capture would try to form a weak reference after deallocation began.
        if let session = captureSession, session.isRunning {
            sessionQueue.async {
                session.stopRunning()
            }
        }
    }
    
    // MARK: - Public Methods
    
    /// Start the camera capture session
    func start() {
        print("[CameraService] Starting camera...")
        
        requestPermissions { [weak self] granted in
            guard let self = self, granted else {
                print("[CameraService] Camera permission denied")
                return
            }
            
            // Activate audio session
            do {
                let session = AVAudioSession.sharedInstance()
                try session.setCategory(.playAndRecord, mode: .videoRecording, options: [.defaultToSpeaker, .allowBluetoothHFP])
                try session.setActive(true)
                print("[CameraService] AVAudioSession activated")
            } catch {
                print("[CameraService] Failed to activate AVAudioSession: \(error)")
            }
            
            self.sessionQueue.async {
                self.setupCaptureSession()
                self.configureDeviceInput()
                self.configureAudioInput()
                self.configureVideoOutput()
                self.configureAudioOutput()
                self.applyFrameRate(self.targetFrameRate)
                
                self.captureSession?.startRunning()
                self.isRunning = true
                self.applyAutoFocus()
                
                DispatchQueue.main.async {
                    self.delegate?.cameraServiceDidStart(self)
                }
                
                print("[CameraService] Camera started successfully")
            }
        }
    }
    
    /// Stop the camera capture session
    func stop() {
        print("[CameraService] Stopping camera...")
        
        sessionQueue.async { [self] in
            videoDataOutput?.setSampleBufferDelegate(nil, queue: nil)
            audioDataOutput?.setSampleBufferDelegate(nil, queue: nil)
            captureSession?.stopRunning()
            captureSession = nil
            currentDeviceInput = nil
            currentAudioDeviceInput = nil
            videoDataOutput = nil
            audioDataOutput = nil
            isRunning = false
            
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                self.delegate?.cameraServiceDidStop(self)
            }
        }
    }
    
    /// Switch between front and back camera
    func switchCamera() {
        print("[CameraService] Switching camera...")

        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            let newPosition: AVCaptureDevice.Position = self.currentPosition == .back ? .front : .back
            self.currentDeviceUniqueID = nil
            self.configureCameraPosition(newPosition)
        }
    }

    /// Set front or back camera explicitly
    func setCameraPosition(_ position: AVCaptureDevice.Position) {
        print("[CameraService] Camera position requested: \(position == .front ? "front" : "back")")

        sessionQueue.async { [weak self] in
            self?.currentDeviceUniqueID = nil
            self?.configureCameraPosition(position)
        }
    }

    /// Set a concrete camera device explicitly
    func setCameraDevice(_ uniqueID: String) {
        print("[CameraService] Camera device requested: \(uniqueID)")

        sessionQueue.async { [weak self] in
            self?.configureCameraDevice(uniqueID)
        }
    }

    /// Set the preferred microphone input. Empty/default uses the system route.
    func setAudioInput(_ uid: String?) {
        let normalizedUID = (uid?.isEmpty == false && uid != "default") ? uid : nil
        print("[CameraService] Audio input requested: \(normalizedUID ?? "default")")

        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            self.currentAudioInputUID = normalizedUID
            self.applyPreferredAudioInput()

            guard let session = self.captureSession else {
                print("[CameraService] Audio input queued for next camera start")
                return
            }

            session.beginConfiguration()

            if let currentAudioDeviceInput = self.currentAudioDeviceInput {
                session.removeInput(currentAudioDeviceInput)
                self.currentAudioDeviceInput = nil
            }

            self.configureAudioInput()
            session.commitConfiguration()
        }
    }

    /// Set capture session resolution preset
    func setSessionPreset(_ presetName: String) {
        let nextPreset = resolveSessionPreset(presetName)
        print("[CameraService] Session preset requested: \(presetName)")

        sessionQueue.async { [weak self] in
            guard let self = self else { return }

            if self.sessionPreset == nextPreset && self.captureSession?.sessionPreset == nextPreset {
                return
            }

            self.sessionPreset = nextPreset

            guard let session = self.captureSession else {
                print("[CameraService] Session preset queued for next camera start")
                return
            }

            session.beginConfiguration()
            if session.canSetSessionPreset(nextPreset) {
                session.sessionPreset = nextPreset
                print("[CameraService] Session preset applied: \(nextPreset.rawValue)")
            } else {
                print("[CameraService] Session preset unsupported: \(nextPreset.rawValue)")
            }
            session.commitConfiguration()
            self.applyFrameRate(self.targetFrameRate)
        }
    }
    
    /// Set orientation
    func setOrientation(_ orientation: AVCaptureVideoOrientation) {
        self.orientation = orientation
        print("[CameraService] Orientation set to \(orientation.rawValue)")
        
        sessionQueue.async { [weak self] in
            self?.applyVideoConnectionSettings()
        }
    }
    
    /// Set frame rate
    func setFrameRate(_ fps: Int) {
        targetFrameRate = fps
        print("[CameraService] Frame rate set to \(fps)")
        
        sessionQueue.async { [weak self] in
            self?.applyFrameRate(fps)
        }
    }

    /// Enable or disable continuous autofocus. When disabled, tap focus locks the lens at that point.
    func setAutoFocusEnabled(_ enabled: Bool) {
        autoFocusEnabled = enabled
        print("[CameraService] Auto focus set to \(enabled ? "enabled" : "locked")")

        sessionQueue.async { [weak self] in
            self?.applyAutoFocus()
        }
    }

    /// Focus and expose at a normalized capture-device point.
    func focus(at normalizedPoint: CGPoint, lockAfterFocus: Bool) {
        let point = CGPoint(
            x: max(0, min(1, normalizedPoint.x)),
            y: max(0, min(1, normalizedPoint.y))
        )

        sessionQueue.async { [weak self] in
            guard let self = self, let device = self.currentDeviceInput?.device else { return }

            do {
                try device.lockForConfiguration()
                self.applyFocusPointLocked(point, device: device, lockAfterFocus: lockAfterFocus)
                device.unlockForConfiguration()
                print("[CameraService] Focus point applied: \(point.x), \(point.y), lock: \(lockAfterFocus)")
            } catch {
                print("[CameraService] Error applying focus point: \(error)")
            }
        }
    }
    
    /// Get the capture session (for preview layer)
    func getCaptureSession() -> AVCaptureSession? {
        return captureSession
    }
    
    // MARK: - Private Methods
    
    private func requestPermissions(completion: @escaping (Bool) -> Void) {
        let videoStatus = AVCaptureDevice.authorizationStatus(for: .video)
        let audioStatus = AVCaptureDevice.authorizationStatus(for: .audio)
        
        if videoStatus == .authorized && audioStatus == .authorized {
            completion(true)
            return
        }
        
        let group = DispatchGroup()
        var videoGranted = videoStatus == .authorized
        var audioGranted = audioStatus == .authorized
        
        if videoStatus == .notDetermined {
            group.enter()
            AVCaptureDevice.requestAccess(for: .video) { granted in
                videoGranted = granted
                group.leave()
            }
        }
        
        if audioStatus == .notDetermined {
            group.enter()
            AVCaptureDevice.requestAccess(for: .audio) { granted in
                audioGranted = granted
                group.leave()
            }
        }
        
        group.notify(queue: .main) {
            print("[CameraService] Permissions - Video: \(videoGranted), Audio: \(audioGranted)")
            completion(videoGranted && audioGranted)
        }
    }
    
    private func setupCaptureSession() {
        let session = AVCaptureSession()
        if session.canSetSessionPreset(sessionPreset) {
            session.sessionPreset = sessionPreset
        } else {
            session.sessionPreset = .hd1920x1080
        }
        captureSession = session
        print("[CameraService] Capture session created with preset \(session.sessionPreset.rawValue)")
    }
    
    private func configureDeviceInput() {
        guard let session = captureSession,
              let device = getSelectedCamera() else {
            print("[CameraService] Failed to get camera device")
            return
        }
        
        do {
            let input = try AVCaptureDeviceInput(device: device)
            if session.canAddInput(input) {
                session.addInput(input)
                currentDeviceInput = input
                currentDeviceUniqueID = device.uniqueID
                currentPosition = device.position
                print("[CameraService] Device input added for position: \(currentPosition == .back ? "back" : "front")")
            }
        } catch {
            print("[CameraService] Error creating device input: \(error)")
        }
    }
    
    private func configureAudioInput() {
        applyPreferredAudioInput()

        guard let session = captureSession,
              let device = AVCaptureDevice.default(for: .audio) else {
            print("[CameraService] Failed to get audio device")
            return
        }
        
        do {
            let input = try AVCaptureDeviceInput(device: device)
            if session.canAddInput(input) {
                session.addInput(input)
                currentAudioDeviceInput = input
                print("[CameraService] Audio input added")
            }
        } catch {
            print("[CameraService] Error creating audio input: \(error)")
        }
    }

    private func applyPreferredAudioInput() {
        let session = AVAudioSession.sharedInstance()

        do {
            try session.setCategory(.playAndRecord, mode: .videoRecording, options: [.defaultToSpeaker, .allowBluetoothHFP])
            try session.setActive(true)

            guard let uid = currentAudioInputUID,
                  let input = session.availableInputs?.first(where: { $0.uid == uid }) else {
                try session.setPreferredInput(nil)
                return
            }

            try session.setPreferredInput(input)
            print("[CameraService] Preferred audio input set: \(input.portName)")
        } catch {
            print("[CameraService] Failed to apply preferred audio input: \(error)")
        }
    }
    
    private func configureVideoOutput() {
        guard let session = captureSession else { return }
        
        let output = AVCaptureVideoDataOutput()
        output.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
        ]
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: videoQueue)
        
        if session.canAddOutput(output) {
            session.addOutput(output)
            videoDataOutput = output
            
            applyVideoConnectionSettings()
            
            print("[CameraService] Video data output configured")
        }
    }
    
    private func configureAudioOutput() {
        guard let session = captureSession else { return }
        
        let output = AVCaptureAudioDataOutput()
        output.setSampleBufferDelegate(self, queue: audioQueue)
        
        if session.canAddOutput(output) {
            session.addOutput(output)
            audioDataOutput = output
            print("[CameraService] Audio data output configured")
        }
    }

    private func configureCameraPosition(_ position: AVCaptureDevice.Position) {
        if currentPosition == position && currentDeviceInput != nil {
            applyVideoConnectionSettings()
            applyAutoFocus()
            return
        }

        guard let session = captureSession else {
            currentPosition = position
            print("[CameraService] Camera position queued for next start")
            return
        }

        let previousInput = currentDeviceInput
        let previousPosition = currentPosition

        session.beginConfiguration()

        if let previousInput = previousInput {
            session.removeInput(previousInput)
        }

        guard let newDevice = getCamera(for: position),
              let newInput = try? AVCaptureDeviceInput(device: newDevice),
              session.canAddInput(newInput) else {
            if let previousInput = previousInput, session.canAddInput(previousInput) {
                session.addInput(previousInput)
            }
            currentPosition = previousPosition
            session.commitConfiguration()
            print("[CameraService] Failed to configure requested camera position")
            return
        }

        session.addInput(newInput)
        currentDeviceInput = newInput
        currentPosition = position
        applyVideoConnectionSettings()
        session.commitConfiguration()
        applyFrameRate(targetFrameRate)

        DispatchQueue.main.async {
            self.delegate?.cameraServiceDidSwitchCamera(self, position: self.currentPosition)
        }

        print("[CameraService] Camera position configured: \(position == .front ? "front" : "back")")
    }

    private func configureCameraDevice(_ uniqueID: String) {
        guard let newDevice = findCamera(uniqueID: uniqueID) else {
            if uniqueID == "front" {
                currentDeviceUniqueID = nil
                configureCameraPosition(.front)
                return
            }

            if uniqueID == "back" {
                currentDeviceUniqueID = nil
                configureCameraPosition(.back)
                return
            }

            currentDeviceUniqueID = nil
            applyVideoConnectionSettings()
            print("[CameraService] Requested camera device not found: \(uniqueID)")
            return
        }

        if currentDeviceInput?.device.uniqueID == uniqueID {
            currentDeviceUniqueID = uniqueID
            currentPosition = newDevice.position
            applyVideoConnectionSettings()
            applyAutoFocus()
            return
        }

        guard let session = captureSession else {
            currentDeviceUniqueID = uniqueID
            currentPosition = newDevice.position
            print("[CameraService] Camera device queued for next start")
            return
        }

        let previousInput = currentDeviceInput
        let previousPosition = currentPosition
        let previousDeviceID = currentDeviceUniqueID

        session.beginConfiguration()

        if let previousInput = previousInput {
            session.removeInput(previousInput)
        }

        guard let newInput = try? AVCaptureDeviceInput(device: newDevice),
              session.canAddInput(newInput) else {
            if let previousInput = previousInput, session.canAddInput(previousInput) {
                session.addInput(previousInput)
            }
            currentPosition = previousPosition
            currentDeviceUniqueID = previousDeviceID
            session.commitConfiguration()
            print("[CameraService] Failed to configure requested camera device")
            return
        }

        session.addInput(newInput)
        currentDeviceInput = newInput
        currentPosition = newDevice.position
        currentDeviceUniqueID = uniqueID
        applyVideoConnectionSettings()
        session.commitConfiguration()
        applyFrameRate(targetFrameRate)

        DispatchQueue.main.async {
            self.delegate?.cameraServiceDidSwitchCamera(self, position: self.currentPosition)
        }

        print("[CameraService] Camera device configured: \(newDevice.localizedName)")
    }

    private func applyVideoConnectionSettings() {
        guard let connection = videoDataOutput?.connection(with: .video) else { return }

        if connection.isVideoOrientationSupported {
            connection.videoOrientation = orientation
        }
        if connection.isVideoMirroringSupported {
            connection.automaticallyAdjustsVideoMirroring = false
            connection.isVideoMirrored = false
        }
    }

    private func resolveSessionPreset(_ presetName: String) -> AVCaptureSession.Preset {
        switch presetName {
        case "720p":
            return .hd1280x720
        case "4K":
            return .hd4K3840x2160
        default:
            return .hd1920x1080
        }
    }
    
    private func applyFrameRate(_ fps: Int) {
        guard let device = currentDeviceInput?.device else { return }
        let clampedFPS = max(1, min(fps, 240))
        let desiredDuration = CMTimeMake(value: 1, timescale: Int32(clampedFPS))
        
        do {
            try device.lockForConfiguration()

            if !format(device.activeFormat, supportsFrameRate: clampedFPS),
               let format = bestFrameRateFormat(for: device, fps: clampedFPS) {
                device.activeFormat = format
            }

            if format(device.activeFormat, supportsFrameRate: clampedFPS) {
                device.activeVideoMinFrameDuration = desiredDuration
                device.activeVideoMaxFrameDuration = desiredDuration
            } else {
                print("[CameraService] Active format does not support \(clampedFPS)fps; keeping current frame timing")
            }

            applyAutoFocusLocked(device)
            device.unlockForConfiguration()
            
            print("[CameraService] Frame rate configured to \(clampedFPS)fps")
        } catch {
            print("[CameraService] Error setting frame rate: \(error)")
        }
    }

    private func format(_ format: AVCaptureDevice.Format, supportsFrameRate fps: Int) -> Bool {
        return format.videoSupportedFrameRateRanges.contains { range in
            range.minFrameRate <= Double(fps) && range.maxFrameRate >= Double(fps)
        }
    }

    private func bestFrameRateFormat(for device: AVCaptureDevice, fps: Int) -> AVCaptureDevice.Format? {
        let activeDimensions = CMVideoFormatDescriptionGetDimensions(device.activeFormat.formatDescription)

        return device.formats
            .filter { format($0, supportsFrameRate: fps) }
            .min { lhs, rhs in
                let lhsScore = formatDistance(from: activeDimensions, to: lhs)
                let rhsScore = formatDistance(from: activeDimensions, to: rhs)

                if lhsScore != rhsScore {
                    return lhsScore < rhsScore
                }

                return maxFrameRate(for: lhs) < maxFrameRate(for: rhs)
            }
    }

    private func formatDistance(
        from reference: CMVideoDimensions,
        to format: AVCaptureDevice.Format
    ) -> Int {
        let dimensions = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
        return abs(Int(dimensions.width) - Int(reference.width))
            + abs(Int(dimensions.height) - Int(reference.height))
    }

    private func maxFrameRate(for format: AVCaptureDevice.Format) -> Double {
        return format.videoSupportedFrameRateRanges
            .map(\.maxFrameRate)
            .max() ?? 0
    }

    private func applyAutoFocus() {
        guard let device = currentDeviceInput?.device else { return }

        do {
            try device.lockForConfiguration()
            applyAutoFocusLocked(device)
            device.unlockForConfiguration()
        } catch {
            print("[CameraService] Error setting auto focus: \(error)")
        }
    }

    private func applyAutoFocusLocked(_ device: AVCaptureDevice) {
        if device.isSmoothAutoFocusSupported {
            device.isSmoothAutoFocusEnabled = true
        }

        if autoFocusEnabled {
            if device.isFocusPointOfInterestSupported {
                device.focusPointOfInterest = defaultFocusPoint
            }

            if device.isFocusModeSupported(.continuousAutoFocus) {
                device.focusMode = .continuousAutoFocus
            } else if device.isFocusModeSupported(.autoFocus) {
                device.focusMode = .autoFocus
            }

            if device.isExposurePointOfInterestSupported {
                device.exposurePointOfInterest = defaultFocusPoint
            }

            if device.isExposureModeSupported(.continuousAutoExposure) {
                device.exposureMode = .continuousAutoExposure
            } else if device.isExposureModeSupported(.autoExpose) {
                device.exposureMode = .autoExpose
            }

            if device.isWhiteBalanceModeSupported(.continuousAutoWhiteBalance) {
                device.whiteBalanceMode = .continuousAutoWhiteBalance
            }
        } else {
            if device.isFocusModeSupported(.locked) {
                device.focusMode = .locked
            }

            if device.isExposureModeSupported(.locked) {
                device.exposureMode = .locked
            }
        }

        device.isSubjectAreaChangeMonitoringEnabled = autoFocusEnabled
        logFocusState(for: device)
    }

    private func logFocusState(for device: AVCaptureDevice) {
        print(
            "[CameraService] Focus state device=\(device.localizedName) type=\(device.deviceType.rawValue) autoFocus=\(autoFocusEnabled) focusMode=\(device.focusMode.rawValue) supportsContinuous=\(device.isFocusModeSupported(.continuousAutoFocus)) supportsPoint=\(device.isFocusPointOfInterestSupported)"
        )
    }

    private func applyFocusPointLocked(
        _ point: CGPoint,
        device: AVCaptureDevice,
        lockAfterFocus: Bool
    ) {
        if device.isSmoothAutoFocusSupported {
            device.isSmoothAutoFocusEnabled = true
        }

        if device.isFocusPointOfInterestSupported {
            device.focusPointOfInterest = point

            if lockAfterFocus {
                if device.isFocusModeSupported(.autoFocus) {
                    device.focusMode = .autoFocus
                }
            } else if device.isFocusModeSupported(.continuousAutoFocus) {
                device.focusMode = .continuousAutoFocus
            } else if device.isFocusModeSupported(.autoFocus) {
                device.focusMode = .autoFocus
            }
        }

        if device.isExposurePointOfInterestSupported {
            device.exposurePointOfInterest = point

            if lockAfterFocus {
                if device.isExposureModeSupported(.autoExpose) {
                    device.exposureMode = .autoExpose
                }
            } else if device.isExposureModeSupported(.continuousAutoExposure) {
                device.exposureMode = .continuousAutoExposure
            } else if device.isExposureModeSupported(.autoExpose) {
                device.exposureMode = .autoExpose
            }
        }

        if device.isWhiteBalanceModeSupported(.continuousAutoWhiteBalance) {
            device.whiteBalanceMode = .continuousAutoWhiteBalance
        }

        device.isSubjectAreaChangeMonitoringEnabled = !lockAfterFocus && autoFocusEnabled
    }
    
    private func getCamera(for position: AVCaptureDevice.Position) -> AVCaptureDevice? {
        // Try to get a wide-angle camera first
        if let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position) {
            return device
        }
        // Fallback to default
        return AVCaptureDevice.default(for: .video)
    }

    private func getSelectedCamera() -> AVCaptureDevice? {
        if let currentDeviceUniqueID = currentDeviceUniqueID,
           let device = findCamera(uniqueID: currentDeviceUniqueID) {
            return device
        }

        return getCamera(for: currentPosition)
    }

    private func findCamera(uniqueID: String) -> AVCaptureDevice? {
        return CameraService.availableCameraDevices().first { $0.uniqueID == uniqueID }
    }

    static func availableCameraDevices() -> [AVCaptureDevice] {
        let discovery = AVCaptureDevice.DiscoverySession(
            deviceTypes: [
                .builtInWideAngleCamera,
                .builtInUltraWideCamera,
                .builtInTelephotoCamera,
                .builtInTrueDepthCamera
            ],
            mediaType: .video,
            position: .unspecified
        )

        return discovery.devices.sorted { lhs, rhs in
            let lhsPriority = cameraSortPriority(lhs)
            let rhsPriority = cameraSortPriority(rhs)

            if lhsPriority != rhsPriority {
                return lhsPriority < rhsPriority
            }

            return lhs.localizedName.localizedCaseInsensitiveCompare(rhs.localizedName) == .orderedAscending
        }
    }

    private static func cameraSortPriority(_ device: AVCaptureDevice) -> Int {
        switch (device.position, device.deviceType) {
        case (.back, .builtInWideAngleCamera):
            return 0
        case (.back, .builtInTelephotoCamera):
            return 1
        case (.back, .builtInUltraWideCamera):
            return 2
        case (.front, .builtInTrueDepthCamera):
            return 3
        case (.front, .builtInWideAngleCamera):
            return 4
        case (.back, _):
            return 5
        case (.front, _):
            return 6
        default:
            return 7
        }
    }
}

// MARK: - Sample Buffer Delegates

extension CameraService: AVCaptureVideoDataOutputSampleBufferDelegate, AVCaptureAudioDataOutputSampleBufferDelegate {
    
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        if output is AVCaptureVideoDataOutput {
            delegate?.cameraService(self, didOutput: sampleBuffer)
        } else if output is AVCaptureAudioDataOutput {
            delegate?.cameraService(self, didOutputAudio: sampleBuffer)
        }
    }
    
    func captureOutput(_ output: AVCaptureOutput, didDrop sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        // Handle dropped frames
        print("[CameraService] Frame dropped")
    }
}

// MARK: - Delegate Protocol

protocol CameraServiceDelegate: AnyObject {
    /// Called when camera starts
    func cameraServiceDidStart(_ service: CameraService)
    
    /// Called when camera stops
    func cameraServiceDidStop(_ service: CameraService)
    
    /// Called when a new video frame is available
    func cameraService(_ service: CameraService, didOutput sampleBuffer: CMSampleBuffer)
    
    /// Called when a new audio buffer is available
    func cameraService(_ service: CameraService, didOutputAudio sampleBuffer: CMSampleBuffer)
    
    /// Called when camera position changes
    func cameraServiceDidSwitchCamera(_ service: CameraService, position: AVCaptureDevice.Position)
}

// Provide default implementations
extension CameraServiceDelegate {
    func cameraServiceDidStart(_ service: CameraService) {}
    func cameraServiceDidStop(_ service: CameraService) {}
    func cameraService(_ service: CameraService, didOutput sampleBuffer: CMSampleBuffer) {}
    func cameraService(_ service: CameraService, didOutputAudio sampleBuffer: CMSampleBuffer) {}
    func cameraServiceDidSwitchCamera(_ service: CameraService, position: AVCaptureDevice.Position) {}
}
