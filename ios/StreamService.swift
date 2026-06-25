//
//  StreamService.swift
//  react-native-web-cam-stream
//
//  RTMP streaming service using HaishinKit 2.0.9 (Async/Actor API)
//

import Foundation
import AVFoundation
import HaishinKit
import VideoToolbox

/// Service for RTMP streaming
class StreamService: NSObject {
    
    // MARK: - Properties
    
    /// Current stream state
    private(set) var state: StreamState = .idle
    
    /// RTMP URL (full URL including stream key)
    private var rtmpUrl: String?
    
    /// RTMP server URL (without stream key)
    private var serverUrl: String?
    
    /// Stream key
    private var streamKey: String?
    
    /// Stream configuration
    var streamConfig: [String: Any]?
    
    /// Video bitrate
    private var videoBitrate: Int = 4_000_000  // 4 Mbps default
    
    /// Audio bitrate
    private var audioBitrate: Int = 128_000  // 128 kbps default
    
    /// Output width
    private var outputWidth: Int = 1920
    
    /// Output height
    private var outputHeight: Int = 1080
    
    /// Audio sample rate
    private var audioSampleRate: Int = 44100
    
    /// Is audio muted
    private(set) var isMuted: Bool = false
    
    /// Stream statistics
    private(set) var stats: StreamStats = StreamStats()
    
    /// Delegate for stream events
    weak var delegate: StreamServiceDelegate?
    
    /// Stats update timer
    private var statsTimer: DispatchSourceTimer?
    
    /// Stream start time
    private var streamStartTime: Date?
    
    /// Total frames sent (RTMP)
    private var totalFrameCount: Int = 0
    
    /// Total audio buffers sent (RTMP)
    private var totalAudioBufferCount: Int = 0
    
    // MARK: - Recording Properties
    
    /// AVAssetWriter for local recording
    private var assetWriter: AVAssetWriter?
    
    /// Video input for asset writer
    private var assetWriterVideoInput: AVAssetWriterInput?
    
    /// Audio input for asset writer
    private var assetWriterAudioInput: AVAssetWriterInput?
    
    /// Pixel buffer adaptor for video input
    private var assetWriterInputAdaptor: AVAssetWriterInputPixelBufferAdaptor?
    
    /// Is recording active
    private(set) var isRecording: Bool = false
    
    /// Recording start time
    private var recordingStartTime: CMTime = .invalid
    
    /// Output file URL
    private var recordingUrl: URL?
    
    /// Frames sent since last stats update (for FPS)
    private var statsFrameCount: Int = 0
    
    /// Frame rate calculation
    private var lastFrameCountTime: Date = Date()
    
    /// HaishinKit RTMP connection
    private var rtmpConnection: RTMPConnection?
    
    /// HaishinKit RTMP stream
    private var rtmpStream: RTMPStream?

    /// HaishinKit media mixer used for offscreen sample-buffer ingest
    private var mediaMixer: MediaMixer?
    
    /// Status listener task
    private var statusTask: Task<Void, Error>?
    
    enum FrameData {
        case video(CMSampleBuffer)
        case audio(CMSampleBuffer)
    }
    
    /// Continuation for queueing frames strictly in order
    private var frameContinuation: AsyncStream<FrameData>.Continuation?
    
    /// Dedicated task to sequentially process frames and feed them to the encoder
    private var frameProcessingTask: Task<Void, Never>?
    
    // MARK: - Initialization
    
    override init() {
        super.init()
        print("[StreamService] Initialized")
    }
    
    deinit {
        disconnect()
    }
    
    // MARK: - Public Methods
    
    /// Configure the stream
    func configure(config: [String: Any]) {
        streamConfig = config
        
        if let url = config["url"] as? String {
            rtmpUrl = url
            parseRtmpUrl(url)
        }
        
        if let bitrate = config["bitrate"] as? Int {
            videoBitrate = bitrate
        }
        
        if let width = config["width"] as? Int {
            outputWidth = width
        }
        
        if let height = config["height"] as? Int {
            outputHeight = height
        }
        
        if let sampleRate = config["audioSampleRate"] as? Int {
            audioSampleRate = sampleRate
        }
        
        if let aBitrate = config["audioBitrate"] as? Int {
            audioBitrate = aBitrate
        }
        
        let hasStreamUrl = rtmpUrl?.isEmpty == false
        let configMsg = "[StreamService] Configured - urlPresent: \(hasStreamUrl), bitrate: \(videoBitrate), size: \(outputWidth)x\(outputHeight)"
        print(configMsg)
        delegate?.streamService(self, didLog: configMsg)
        let hasStreamKey = streamKey?.isEmpty == false
        let parseMsg = "[StreamService] Parsed RTMP URL - serverPresent: \(serverUrl?.isEmpty == false), streamKeyPresent: \(hasStreamKey)"
        print(parseMsg)
        delegate?.streamService(self, didLog: parseMsg)
        
        // If we are already streaming, apply changes live
        if rtmpStream != nil {
            updateConfig(config)
        }
    }
    
    /// Connect to RTMP server and start streaming
    func connect() {
        guard let url = rtmpUrl else {
            print("[StreamService] No RTMP URL configured")
            let error = NSError(domain: "StreamService", code: -1, userInfo: [NSLocalizedDescriptionKey: "No RTMP URL configured"])
            delegate?.streamService(self, didFailWithError: error)
            return
        }
        
        guard let key = streamKey else {
            print("[StreamService] ❌ No stream key parsed from URL")
            let error = NSError(domain: "StreamService", code: -1, userInfo: [NSLocalizedDescriptionKey: "No stream key found in URL"])
            delegate?.streamService(self, didFailWithError: error)
            return
        }
        
        let connectUrl = serverUrl ?? url
        let connectMsg = "[StreamService] Connecting to configured RTMP server..."
        print(connectMsg)
        delegate?.streamService(self, didLog: connectMsg)
        
        state = .connecting
        delegate?.streamService(self, didChangeState: state)
        
        // Single sequential Task for the ENTIRE connect → publish flow
        Task {
            do {
                // Set up strict frame sequencing to prevent out-of-order timestamps
                var continuation: AsyncStream<FrameData>.Continuation?
                let frameStream = AsyncStream<FrameData> { cont in
                    continuation = cont
                }
                self.frameContinuation = continuation
                
                self.frameProcessingTask = Task {
                    for await frame in frameStream {
                        guard let mediaMixer = self.mediaMixer else { continue }
                        // Only feed frames after publish succeeds and the encoder is live.
                        let currentState = self.state
                        if currentState == .streaming {
                            switch frame {
                            case .video(let buffer):
                                await mediaMixer.append(buffer)
                            case .audio(let buffer):
                                await mediaMixer.append(buffer)
                            }
                        }
                    }
                }
                
                // Step 1: Create RTMP connection
                let connection = RTMPConnection(requestTimeout: 15000)
                rtmpConnection = connection
                
                // Status listener for LOGGING only (not for driving state)
                statusTask = Task {
                    for await status in await connection.status {
                        let msg = "[StreamService] 📡 RTMP status: \(status.code)"
                        print(msg)
                        await MainActor.run {
                            self.delegate?.streamService(self, didLog: msg)
                        }
                    }
                }
                
                // Step 2: Connect to RTMP server
                print("[StreamService] Step 1/5: Connecting to \(connectUrl)...")
                _ = try await connection.connect(connectUrl)
                print("[StreamService] Step 1/5: ✅ Connected!")
                
                await MainActor.run {
                    self.delegate?.streamService(self, didLog: "[StreamService] ✅ Connected to RTMP server")
                    self.delegate?.streamServiceDidConnect(self)
                }
                
                // Step 3: Create RTMP stream
                print("[StreamService] Step 2/5: Creating stream + mixer...")
                let stream = RTMPStream(connection: connection)
                rtmpStream = stream
                let mixer = MediaMixer()
                mediaMixer = mixer
                await mixer.addOutput(stream)
                await mixer.startRunning()
                print("[StreamService] Step 2/5: ✅ Stream object created and MediaMixer started")
                
                // Step 4: Configure codec settings
                print("[StreamService] Step 3/5: Configuring codecs...")
                var videoSettings = VideoCodecSettings()
                videoSettings.videoSize = .init(width: CGFloat(outputWidth), height: CGFloat(outputHeight))
                videoSettings.bitRate = videoBitrate
                videoSettings.maxKeyFrameIntervalDuration = 2
                videoSettings.profileLevel = kVTProfileLevel_H264_Main_AutoLevel as String
                videoSettings.isHardwareEncoderEnabled = true
                await stream.setVideoSettings(videoSettings)
                
                var audioSettings = AudioCodecSettings()
                audioSettings.bitRate = audioBitrate
                audioSettings.format = .aac
                await stream.setAudioSettings(audioSettings)
                print("[StreamService] Step 3/5: ✅ Codecs configured (video: \(outputWidth)x\(outputHeight) @ \(videoBitrate)bps)")
                
                // Step 5: Wait for createStream() to finish (RTMPStream.init fires it async)
                print("[StreamService] Step 4/5: Waiting for server stream ID...")
                let maxWait = 50 // 50 x 100ms = 5 seconds
                var waited = 0
                while await stream.readyState != .idle && waited < maxWait {
                    try await Task.sleep(nanoseconds: 100_000_000) // 100ms
                    waited += 1
                }
                
                let readyState = await stream.readyState
                print("[StreamService] Step 4/5: readyState=\(readyState), waited \(waited * 100)ms")
                
                await MainActor.run {
                    self.delegate?.streamService(self, didLog: "[StreamService] Stream ready: state=\(readyState)")
                }
                
                guard readyState == .idle else {
                    let errMsg = "[StreamService] ❌ Stream never became ready (stuck at \(readyState))"
                    print(errMsg)
                    await MainActor.run {
                        self.delegate?.streamService(self, didLog: errMsg)
                        self.state = .error
                        self.delegate?.streamService(self, didChangeState: .error)
                    }
                    return
                }
                
                // Step 6: PUBLISH!
                print("[StreamService] Step 5/5: Publishing with configured stream key...")
                await MainActor.run {
                    self.delegate?.streamService(self, didLog: "[StreamService] 🚀 Publishing...")
                }
                
                let response = try await stream.publish(key)
                print("[StreamService] 🎉 PUBLISH SUCCEEDED! Response: \(response)")
                
                // publish() returns AFTER NetStream.Publish.Start
                // → outgoing.startRunning() has been called
                // → encoder is ready for frames
                await MainActor.run {
                    self.state = .streaming
                    self.streamStartTime = Date()
                    self.startStatsTimer()
                    self.delegate?.streamService(self, didLog: "[StreamService] 🎉 LIVE — encoder running, accepting frames!")
                    self.delegate?.streamService(self, didChangeState: .streaming)
                }
                
            } catch {
                let errorMsg: String
                if let rtmpError = error as? RTMPStream.Error {
                    switch rtmpError {
                    case .invalidState:
                        errorMsg = "[StreamService] ❌ Failed: invalidState"
                    case .requestTimedOut:
                        errorMsg = "[StreamService] ❌ Failed: requestTimedOut (15s)"
                    case .requestFailed(let response):
                        errorMsg = "[StreamService] ❌ Failed: requestFailed - \(response)"
                    }
                } else {
                    errorMsg = "[StreamService] ❌ Failed: \(error)"
                }
                print(errorMsg)
                await MainActor.run {
                    self.delegate?.streamService(self, didLog: errorMsg)
                    self.state = .error
                    self.delegate?.streamService(self, didFailWithError: error)
                    self.delegate?.streamService(self, didChangeState: .error)
                }
            }
        }
    }
    
    /// Disconnect from RTMP server
    func disconnect() {
        print("[StreamService] Disconnecting...")
        
        stopStatsTimer()
        statusTask?.cancel()
        statusTask = nil
        
        frameContinuation?.finish()
        frameContinuation = nil
        frameProcessingTask?.cancel()
        frameProcessingTask = nil
        
        let connection = rtmpConnection
        let stream = rtmpStream
        let mixer = mediaMixer
        
        rtmpStream = nil
        rtmpConnection = nil
        mediaMixer = nil
        
        Task {
            if let mixer = mixer, let stream = stream {
                await mixer.removeOutput(stream)
                await mixer.stopRunning()
            } else if let mixer = mixer {
                await mixer.stopRunning()
            }
            if let stream = stream {
                _ = try? await stream.close()
            }
            if let connection = connection {
                try? await connection.close()
            }
        }
        
        state = .disconnected
        streamStartTime = nil
        totalFrameCount = 0
        totalAudioBufferCount = 0
        delegate?.streamService(self, didChangeState: state)
        delegate?.streamServiceDidDisconnect(self)
    }
    
    // MARK: - Recording Methods
    
    /// Start local recording
    @objc func startRecording(to fileUrl: URL) {
        guard !isRecording else { return }
        
        do {
            // Remove existing file
            if FileManager.default.fileExists(atPath: fileUrl.path) {
                try FileManager.default.removeItem(at: fileUrl)
            }
            
            let writer = try AVAssetWriter(outputURL: fileUrl, fileType: .mp4)
            
            // Video Input
            let videoSettings: [String: Any] = [
                AVVideoCodecKey: AVVideoCodecType.h264,
                AVVideoWidthKey: outputWidth,
                AVVideoHeightKey: outputHeight
            ]
            let videoInput = AVAssetWriterInput(mediaType: .video, outputSettings: videoSettings)
            videoInput.expectsMediaDataInRealTime = true
            
            // Pixel Buffer Adaptor (for better performance/timing)
            let sourcePixelBufferAttributes: [String: Any] = [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
                kCVPixelBufferWidthKey as String: outputWidth,
                kCVPixelBufferHeightKey as String: outputHeight
            ]
            let adaptor = AVAssetWriterInputPixelBufferAdaptor(
                assetWriterInput: videoInput,
                sourcePixelBufferAttributes: sourcePixelBufferAttributes
            )
            
            // Audio Input
            let audioSettings: [String: Any] = [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVNumberOfChannelsKey: 1, // Mono
                AVSampleRateKey: 44100,
                AVEncoderBitRateKey: 128000
            ]
            let audioInput = AVAssetWriterInput(mediaType: .audio, outputSettings: audioSettings)
            audioInput.expectsMediaDataInRealTime = true
            
            if writer.canAdd(videoInput) {
                writer.add(videoInput)
            }
            if writer.canAdd(audioInput) {
                writer.add(audioInput)
            }
            
            if writer.startWriting() {
                self.assetWriter = writer
                self.assetWriterVideoInput = videoInput
                self.assetWriterAudioInput = audioInput
                self.assetWriterInputAdaptor = adaptor
                self.isRecording = true
                self.recordingStartTime = .invalid
                self.recordingUrl = fileUrl
                print("[StreamService] 🔴 Recording started: \(fileUrl.lastPathComponent)")
            } else {
                print("[StreamService] ❌ Failed to start writing: \(String(describing: writer.error))")
            }
            
        } catch {
            print("[StreamService] ❌ Failed to start recording: \(error)")
        }
    }
    
    /// Stop local recording
    @objc func stopRecording() -> URL? {
        guard isRecording, let writer = assetWriter else { return nil }
        
        isRecording = false
        let url = recordingUrl
        
        // Finish writing
        let semaphore = DispatchSemaphore(value: 0)
        
        writer.finishWriting {
            semaphore.signal()
        }
        
        semaphore.wait()
        print("[StreamService] ⏹️ Recording stopped")
        
        // Cleanup
        assetWriter = nil
        assetWriterVideoInput = nil
        assetWriterAudioInput = nil
        assetWriterInputAdaptor = nil
        
        return url
    }
    
    @objc func recordVideoFrame(_ sampleBuffer: CMSampleBuffer) {
        guard isRecording,
              let writer = assetWriter,
              let videoInput = assetWriterVideoInput,
              let adaptor = assetWriterInputAdaptor,
              writer.status == .writing else { return }
        
        if recordingStartTime == .invalid {
            writer.startSession(atSourceTime: sampleBuffer.presentationTimeStamp)
            recordingStartTime = sampleBuffer.presentationTimeStamp
        }
        
        if videoInput.isReadyForMoreMediaData {
            // Need to append PixelBuffer via adaptor for reliability with AVAssetWriter
            if let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) {
                adaptor.append(pixelBuffer, withPresentationTime: sampleBuffer.presentationTimeStamp)
            } else {
                videoInput.append(sampleBuffer)
            }
        }
    }
    
    @objc func recordAudioFrame(_ sampleBuffer: CMSampleBuffer) {
        guard isRecording,
              let writer = assetWriter,
              let audioInput = assetWriterAudioInput,
              writer.status == .writing,
              recordingStartTime != .invalid else { return } // Wait for video to start session
        
        if audioInput.isReadyForMoreMediaData {
            audioInput.append(sampleBuffer)
        }
    }
    
    /// Stop publishing stream
    func stopPublishing() {
        print("[StreamService] Stop publishing...")
        let stream = rtmpStream
        Task {
            _ = try? await stream?.close()
        }
    }
    
    /// Send video frame to stream
    func sendVideoFrame(_ sampleBuffer: CMSampleBuffer) {
        guard state == .streaming else {
            if CFAbsoluteTimeGetCurrent().truncatingRemainder(dividingBy: 5.0) < 0.1 {
                print("[StreamService] ✋ Video frame rejected - State is \(state.rawValue)")
            }
            return
        }

        guard mediaMixer != nil else {
            if CFAbsoluteTimeGetCurrent().truncatingRemainder(dividingBy: 5.0) < 0.1 {
                print("[StreamService] ❌ Video frame rejected - mediaMixer is NIL")
            }
            return
        }

        totalFrameCount += 1
        statsFrameCount += 1
        
        if totalFrameCount % 100 == 0 {
            let pts = sampleBuffer.presentationTimeStamp.seconds
            let duration = sampleBuffer.duration.seconds
            let msg = "[StreamService] 📤 Queued 100 video frames to MediaMixer (Total: \(totalFrameCount), PTS: \(String(format: "%.3f", pts)), Dur: \(String(format: "%.3f", duration)))"
            print(msg)
            Task { @MainActor in
                self.delegate?.streamService(self, didLog: msg)
            }
        }

        frameContinuation?.yield(.video(sampleBuffer))
    }
    
    /// Send audio buffer to stream
    func sendAudioBuffer(_ sampleBuffer: CMSampleBuffer) {
        guard state == .streaming, !isMuted, mediaMixer != nil else { return }
        
        totalAudioBufferCount += 1
        if totalAudioBufferCount % 100 == 0 {
            let pts = sampleBuffer.presentationTimeStamp.seconds
            let duration = sampleBuffer.duration.seconds
            let msg = "[StreamService] 🎤 Queued 100 audio buffers to MediaMixer (Total: \(totalAudioBufferCount), PTS: \(String(format: "%.3f", pts)), Dur: \(String(format: "%.3f", duration)))"
            print(msg)
            Task { @MainActor in
                self.delegate?.streamService(self, didLog: msg)
            }
        }
        
        frameContinuation?.yield(.audio(sampleBuffer))
    }
    
    /// Mute/unmute audio
    func setMuted(_ muted: Bool) {
        isMuted = muted
        print("[StreamService] Muted: \(muted)")
        // In HK 2.0.9, we simply check !isMuted in sendAudioBuffer
    }
    
    /// Get current stream statistics (async-to-sync bridge)
    func getStats() -> [String: Any] {
        // Return existing stats object (updated by timer)
        return [
            "bitrate": stats.bitrate,
            "fps": stats.fps,
            "droppedFrames": stats.droppedFrames,
            "duration": stats.duration
        ]
    }
    
    /// Update stream configuration dynamically
    func updateConfig(_ config: [String: Any]) {
        print("[StreamService] Update config: \(config)")
        
        let stream = rtmpStream
        
        if let bitrate = config["bitrate"] as? Int {
            videoBitrate = bitrate
            Task {
                if let stream = stream {
                    var settings = await stream.videoSettings
                    settings.bitRate = bitrate
                    await stream.setVideoSettings(settings)
                }
            }
        }
        
        if let width = config["width"] as? Int {
            outputWidth = width
            Task {
                if let stream = rtmpStream {
                    var settings = await stream.videoSettings
                    settings.videoSize = .init(width: CGFloat(width), height: CGFloat(outputHeight))
                    await stream.setVideoSettings(settings)
                }
            }
        }
        
        if let height = config["height"] as? Int {
            outputHeight = height
            Task {
                if let stream = rtmpStream {
                    var settings = await stream.videoSettings
                    settings.videoSize = .init(width: CGFloat(outputWidth), height: CGFloat(height))
                    await stream.setVideoSettings(settings)
                }
            }
        }
        
        if let aBitrate = config["audioBitrate"] as? Int {
            audioBitrate = aBitrate
            Task {
                if let stream = stream {
                    var settings = await stream.audioSettings
                    settings.bitRate = aBitrate
                    await stream.setAudioSettings(settings)
                }
            }
        }
    }
    
    // MARK: - Private Methods
    
    /// Parse RTMP URL into server URL and stream key
    private func parseRtmpUrl(_ url: String) {
        guard let urlObj = URL(string: url) else { return }
        
        let pathComponents = urlObj.pathComponents.filter { $0 != "/" }
        if pathComponents.count >= 2 {
            streamKey = pathComponents.last
            var components = URLComponents(url: urlObj, resolvingAgainstBaseURL: false)
            let appPath = "/" + pathComponents.dropLast().joined(separator: "/")
            components?.path = appPath
            serverUrl = components?.url?.absoluteString
        } else {
            serverUrl = url
            streamKey = nil
        }
        
        print("[StreamService] RTMP URL parsed - serverPresent: \(serverUrl?.isEmpty == false), streamKeyPresent: \(streamKey?.isEmpty == false)")
    }
    
    /// Start the stats update timer
    private func startStatsTimer() {
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.main)
        timer.schedule(deadline: .now() + 1, repeating: 2.0)
        timer.setEventHandler { [weak self] in
            guard let self = self else { return }
            self.updateStats()
        }
        timer.resume()
        statsTimer = timer
    }
    
    /// Stop the stats update timer
    private func stopStatsTimer() {
        statsTimer?.cancel()
        statsTimer = nil
    }
    
    /// Update stream statistics
    private func updateStats() {
        let now = Date()
        let elapsed = now.timeIntervalSince(lastFrameCountTime)
        if elapsed >= 1.0 {
            stats.fps = Int(Double(statsFrameCount) / elapsed)
            statsFrameCount = 0
            lastFrameCountTime = now
        }
        
        if let startTime = streamStartTime {
            stats.duration = now.timeIntervalSince(startTime)
        }
        
        // Pull values from actor
        let stream = rtmpStream
        Task {
            if let info = await stream?.info {
                _ = info // Suppress unused warning while placeholder is active
                // In HK 2.0 info properties moved/renamed. Let's use duration if available
                // and fall back to our calculation if needed.
                self.stats.bitrate = self.videoBitrate // Placeholder until we find new byte count
            }
            self.delegate?.streamService(self, didUpdateStats: self.stats)
        }
    }
    
    /// Internal status handler called from AsyncStream listener
    private func handleRTMPStatus(_ status: RTMPStatus) async {
        let msg = "[StreamService] 📡 RTMP status: \(status.code)"
        print(msg)
        
        let code = status.code
        
        await MainActor.run {
            self.delegate?.streamService(self, didLog: msg)
            
            switch code {
            case RTMPConnection.Code.connectSuccess.rawValue:
                print("[StreamService] ✅ RTMP connection successful (status handler, logging only)")
                self.delegate?.streamService(self, didLog: "[StreamService] ✅ Connect success (status)")
                self.delegate?.streamServiceDidConnect(self)
                
            case RTMPConnection.Code.connectFailed.rawValue:
                print("[StreamService] ❌ RTMP connection failed")
                self.state = .error
                let error = NSError(domain: "StreamService", code: -2, userInfo: [NSLocalizedDescriptionKey: "RTMP connection failed"])
                self.delegate?.streamService(self, didFailWithError: error)
                self.delegate?.streamService(self, didChangeState: .error)
                
            case RTMPConnection.Code.connectRejected.rawValue:
                print("[StreamService] ❌ RTMP connection rejected - check URL/Firewall")
                self.state = .error
                let error = NSError(domain: "StreamService", code: -3, userInfo: [NSLocalizedDescriptionKey: "RTMP connection rejected"])
                self.delegate?.streamService(self, didFailWithError: error)
                
            case RTMPConnection.Code.connectClosed.rawValue:
                print("[StreamService] 🛑 RTMP connection closed")
                self.state = .disconnected
                self.stopStatsTimer()
                self.delegate?.streamService(self, didChangeState: .disconnected)
                self.delegate?.streamServiceDidDisconnect(self)
                
            case "NetStream.Publish.Start":
                // THIS is when the encoder is actually running and ready for frames
                print("[StreamService] 🎉 Stream started publishing — encoder is LIVE!")
                self.state = .streaming
                self.streamStartTime = Date()
                self.startStatsTimer()
                self.delegate?.streamService(self, didLog: "[StreamService] 🎉 Publish started — now accepting video frames")
                self.delegate?.streamService(self, didChangeState: .streaming)
                
            case "NetStream.Publish.BadName":
                print("[StreamService] ❌ Stream publish failed: BadName (Check Stream Key)")
                self.state = .error
                let error = NSError(domain: "StreamService", code: -4, userInfo: [NSLocalizedDescriptionKey: "Bad stream name/key"])
                self.delegate?.streamService(self, didFailWithError: error)
                self.delegate?.streamService(self, didChangeState: .error)
                
            case "NetStream.Publish.Idle":
                print("[StreamService] ⚠️ Stream publish idle")
                
            default:
                print("[StreamService] ℹ️ Unhandled status code: \(code)")
            }
        }
    }
}

// MARK: - Stream Stats Model

struct StreamStats {
    var bitrate: Int = 0
    var fps: Int = 0
    var droppedFrames: Int = 0
    var duration: Double = 0
}

// MARK: - Delegate Protocol

protocol StreamServiceDelegate: AnyObject {
    func streamService(_ service: StreamService, didChangeState state: StreamState)
    func streamService(_ service: StreamService, didUpdateStats stats: StreamStats)
    func streamService(_ service: StreamService, didFailWithError error: Error)
    func streamServiceDidConnect(_ service: StreamService)
    func streamServiceDidDisconnect(_ service: StreamService)
    func streamService(_ service: StreamService, didLog message: String)
}

extension StreamServiceDelegate {
    func streamService(_ service: StreamService, didChangeState state: StreamState) {}
    func streamService(_ service: StreamService, didUpdateStats stats: StreamStats) {}
    func streamService(_ service: StreamService, didFailWithError error: Error) {}
    func streamServiceDidConnect(_ service: StreamService) {}
    func streamServiceDidDisconnect(_ service: StreamService) {}
    func streamService(_ service: StreamService, didLog message: String) {}
}
