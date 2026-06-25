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
    
    /// Current camera position
    private var currentPosition: AVCaptureDevice.Position = .back
    
    /// Delegate for receiving frames
    weak var delegate: CameraServiceDelegate?
    
    /// Target frame rate
    var targetFrameRate: Int = 30
    
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
        stop()
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
        
        sessionQueue.async { [weak self] in
            self?.captureSession?.stopRunning()
            self?.captureSession = nil
            self?.currentDeviceInput = nil
            self?.videoDataOutput = nil
            self?.audioDataOutput = nil
            self?.isRunning = false
            
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.delegate?.cameraServiceDidStop(self)
            }
        }
    }
    
    /// Switch between front and back camera
    func switchCamera() {
        print("[CameraService] Switching camera...")
        
        sessionQueue.async { [weak self] in
            guard let self = self,
                  let session = self.captureSession else { return }
            
            session.beginConfiguration()
            
            // Remove current input
            if let currentInput = self.currentDeviceInput {
                session.removeInput(currentInput)
            }
            
            // Toggle position
            self.currentPosition = self.currentPosition == .back ? .front : .back
            
            // Add new input
            guard let newDevice = self.getCamera(for: self.currentPosition),
                  let newInput = try? AVCaptureDeviceInput(device: newDevice) else {
                print("[CameraService] Failed to create input for new camera position")
                session.commitConfiguration()
                return
            }
            
            if session.canAddInput(newInput) {
                session.addInput(newInput)
                self.currentDeviceInput = newInput
                
                // Mirror front camera
                if let connection = self.videoDataOutput?.connection(with: .video) {
                    connection.isVideoMirrored = self.currentPosition == .front
                    if connection.isVideoOrientationSupported {
                        connection.videoOrientation = self.orientation
                    }
                }
            }
            
            session.commitConfiguration()
            self.applyFrameRate(self.targetFrameRate)
            
            DispatchQueue.main.async {
                self.delegate?.cameraServiceDidSwitchCamera(self, position: self.currentPosition)
            }
        }
    }
    
    /// Set orientation
    func setOrientation(_ orientation: AVCaptureVideoOrientation) {
        self.orientation = orientation
        print("[CameraService] Orientation set to \(orientation.rawValue)")
        
        sessionQueue.async { [weak self] in
            guard let self = self,
                  let connection = self.videoDataOutput?.connection(with: .video),
                  connection.isVideoOrientationSupported else { return }
            
            connection.videoOrientation = orientation
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
        session.sessionPreset = .hd1920x1080
        captureSession = session
        print("[CameraService] Capture session created with preset hd1920x1080")
    }
    
    private func configureDeviceInput() {
        guard let session = captureSession,
              let device = getCamera(for: currentPosition) else {
            print("[CameraService] Failed to get camera device")
            return
        }
        
        do {
            let input = try AVCaptureDeviceInput(device: device)
            if session.canAddInput(input) {
                session.addInput(input)
                currentDeviceInput = input
                print("[CameraService] Device input added for position: \(currentPosition == .back ? "back" : "front")")
            }
        } catch {
            print("[CameraService] Error creating device input: \(error)")
        }
    }
    
    private func configureAudioInput() {
        guard let session = captureSession,
              let device = AVCaptureDevice.default(for: .audio) else {
            print("[CameraService] Failed to get audio device")
            return
        }
        
        do {
            let input = try AVCaptureDeviceInput(device: device)
            if session.canAddInput(input) {
                session.addInput(input)
                print("[CameraService] Audio input added")
            }
        } catch {
            print("[CameraService] Error creating audio input: \(error)")
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
            
            // Set orientation and mirroring
            if let connection = output.connection(with: .video) {
                if connection.isVideoOrientationSupported {
                    connection.videoOrientation = orientation
                }
                connection.isVideoMirrored = currentPosition == .front
            }
            
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
    
    private func applyFrameRate(_ fps: Int) {
        guard let device = currentDeviceInput?.device else { return }
        
        let desiredDuration = CMTimeMake(value: 1, timescale: Int32(fps))
        
        do {
            try device.lockForConfiguration()
            
            // Find a format that supports the desired frame rate
            var bestFormat: AVCaptureDevice.Format?
            var bestRange: AVFrameRateRange?
            
            for format in device.formats {
                for range in format.videoSupportedFrameRateRanges {
                    if range.minFrameRate <= Double(fps) && range.maxFrameRate >= Double(fps) {
                        if bestRange == nil || range.maxFrameRate > bestRange!.maxFrameRate {
                            bestFormat = format
                            bestRange = range
                        }
                    }
                }
            }
            
            if let format = bestFormat {
                device.activeFormat = format
            }
            
            device.activeVideoMinFrameDuration = desiredDuration
            device.activeVideoMaxFrameDuration = desiredDuration
            device.unlockForConfiguration()
            
            print("[CameraService] Frame rate configured to \(fps)fps")
        } catch {
            print("[CameraService] Error setting frame rate: \(error)")
        }
    }
    
    private func getCamera(for position: AVCaptureDevice.Position) -> AVCaptureDevice? {
        // Try to get a wide-angle camera first
        if let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position) {
            return device
        }
        // Fallback to default
        return AVCaptureDevice.default(for: .video)
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
