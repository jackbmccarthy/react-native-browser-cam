//
//  StreamService.swift
//  react-native-web-cam-stream
//
//  RTMP streaming service using HaishinKit 2.0.9 (Async/Actor API)
//

import Foundation
import AVFoundation
import HaishinKit
import Network
import VideoToolbox

// Single-consumer, generation-owned queue. Only video may be dropped under pressure.
private final class BoundedFrameQueue<Element>: @unchecked Sendable {
    enum Kind {
        case video
        case audio(duration: Double, bytes: Int)
    }

    enum Failure: Error, LocalizedError, Equatable {
        case audioCapacityExceeded
        case invalidAudioMetadata

        var errorDescription: String? {
            switch self {
            case .audioCapacityExceeded:
                return "Streaming audio backlog exceeded its bounded capacity; publisher stopped to avoid silent audio loss."
            case .invalidAudioMetadata:
                return "Streaming audio has invalid duration or byte size; publisher stopped to avoid unbounded buffering."
            }
        }
    }

    enum Admission: Equatable {
        case accepted, droppedVideo, closed
        case failed(Failure)
    }

    private struct Entry {
        let element: Element
        let kind: Kind
    }

    private let lock = NSLock()
    private var pending: [Entry] = []
    private var waiter: CheckedContinuation<Element?, Never>?
    private var finished = false
    private var videoCount = 0
    private var audioCount = 0
    private var audioDuration = 0.0
    private var audioBytes = 0
    private var videoDrops = 0
    // Pending budgets exclude the one frame currently being appended to the mixer.
    private let maxVideoCount = 2
    private let maxAudioDuration = 0.5
    private let maxAudioBytes = 256 * 1024
    private let maxAudioCount = 128

    var hasVideoCapacity: Bool {
        lock.lock()
        defer { lock.unlock() }
        return !finished && videoCount < maxVideoCount
    }

    // Call once for a candidate frame, only when a false result actually skips its render.
    // This is not a reservation; enqueue must recheck after composition.
    func shouldRenderVideoFrame() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard !finished else { return false }
        guard videoCount < maxVideoCount else {
            if videoDrops < Int.max { videoDrops += 1 }
            return false
        }
        return true
    }

    var droppedVideoCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return videoDrops
    }

    @discardableResult
    func enqueue(_ element: Element, kind: Kind) -> Admission {
        lock.lock()
        defer { lock.unlock() }
        guard !finished else { return .closed }

        switch kind {
        case .video:
            guard videoCount < maxVideoCount else {
                if videoDrops < Int.max { videoDrops += 1 }
                return .droppedVideo
            }
        case .audio(let duration, let bytes):
            guard duration.isFinite, duration > 0, bytes > 0 else {
                finishLocked()
                return .failed(.invalidAudioMetadata)
            }
            // Subtraction avoids integer overflow for hostile/invalid buffer sizes.
            guard duration <= maxAudioDuration - audioDuration,
                  bytes <= maxAudioBytes - audioBytes,
                  audioCount < maxAudioCount else {
                finishLocked()
                return .failed(.audioCapacityExceeded)
            }
        }

        if let continuation = waiter {
            waiter = nil
            continuation.resume(returning: element)
        } else {
            switch kind {
            case .video: videoCount += 1
            case .audio(let duration, let bytes):
                audioCount += 1
                audioDuration += duration
                audioBytes += bytes
            }
            // FIFO admission preserves interleaving and original timestamps. Dropping
            // incoming video never reorders retained audio or retained video.
            pending.append(Entry(element: element, kind: kind))
        }
        return .accepted
    }

    func next() async -> Element? {
        await withTaskCancellationHandler(operation: {
            await withCheckedContinuation { takeOrWait($0) }
        }, onCancel: { self.finish() })
    }

    private func takeOrWait(_ continuation: CheckedContinuation<Element?, Never>) {
        lock.lock()
        defer { lock.unlock() }
        guard !finished else {
            continuation.resume(returning: nil)
            return
        }
        guard !pending.isEmpty else {
            precondition(waiter == nil, "BoundedFrameQueue requires exactly one consumer")
            waiter = continuation
            return
        }
        let entry = pending.removeFirst()
        switch entry.kind {
        case .video: videoCount -= 1
        case .audio(let duration, let bytes):
            audioCount -= 1
            audioBytes -= bytes
            audioDuration = audioCount == 0 ? 0 : max(0, audioDuration - duration)
        }
        continuation.resume(returning: entry.element)
    }

    func finish() {
        lock.lock()
        defer { lock.unlock() }
        finishLocked()
    }

    private func finishLocked() {
        finished = true
        pending.removeAll(keepingCapacity: false)
        videoCount = 0
        audioCount = 0
        audioBytes = 0
        audioDuration = 0
        let continuation = waiter
        waiter = nil
        continuation?.resume(returning: nil)
    }
}

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
    
    /// AVAssetWriter for local recording; owned by recordingQueue after setup.
    private var assetWriter: AVAssetWriter?
    private var assetWriterVideoInput: AVAssetWriterInput?
    private var assetWriterAudioInput: AVAssetWriterInput?
    private var assetWriterInputAdaptor: AVAssetWriterInputPixelBufferAdaptor?
    private var recordingStartTime: CMTime = .invalid
    private var recordingUrl: URL?
    private var recordingFailure: Error?
    private var recordingVideoFrameCount = 0
    private let recordingQueue = DispatchQueue(label: "com.webcamstream.recording")
    // Only admission flags and stop subscribers use this short-held lock, never AV calls.
    private let recordingStateLock = NSLock()
    private var recordingActive = false
    private var recordingProcessing = false
    private var recordingGeneration = 0
    private var recordingPendingVideo = 0
    private var recordingPendingAudio = 0
    private var recordingPendingAudioBytes = 0
    private var recordingAdmissionFailed = false
    private var recordingStopCompletions: [(Result<URL, Error>) -> Void] = []
    var onRecordingFailure: ((Error) -> Void)?
    var isRecording: Bool {
        recordingStateLock.lock()
        defer { recordingStateLock.unlock() }
        return recordingActive
    }
    var isRecordingProcessing: Bool {
        recordingStateLock.lock()
        defer { recordingStateLock.unlock() }
        return recordingProcessing
    }
    
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
    private var connectTask: Task<Void, Never>?
    private var teardownTask: Task<Void, Error>?
    private var streamingGeneration = 0
    var hasPublisher: Bool { rtmpConnection != nil || rtmpStream != nil || mediaMixer != nil }
    private(set) var isDisconnecting = false
    
    enum FrameData {
        case video(CMSampleBuffer)
        case audio(CMSampleBuffer)
    }
    
    /// Producer callbacks use only a locked, generation-local queue snapshot.
    private let frameQueueLock = NSLock()
    private var frameQueue: BoundedFrameQueue<FrameData>?
    
    /// Dedicated task to sequentially process frames and feed them to the encoder
    private var frameProcessingTask: Task<Void, Never>?

    /// Whether the user still intends the RTMP stream to be live.
    private var shouldMaintainConnection: Bool = false

    /// Network route monitor used to recover from Wi-Fi changes and disconnects.
    private let networkMonitor = NWPathMonitor()
    private let networkMonitorQueue = DispatchQueue(label: "com.webcamstream.network.monitor")
    private var networkPathSatisfied: Bool = true
    private var networkPathSignature: String?
    private var reconnectWorkItem: DispatchWorkItem?
    private var reconnectAttempts: Int = 0
    
    // MARK: - Initialization
    
    override init() {
        super.init()
        startNetworkMonitoring()
        print("[StreamService] Initialized")
    }
    
    deinit {
        // Explicit owner cleanup performs/join async teardown. Deinit must not
        // create a Task that retains an object already being deallocated.
        networkMonitor.cancel()
        reconnectWorkItem?.cancel()
        statsTimer?.cancel()
        connectTask?.cancel()
        statusTask?.cancel()
        finishFrameQueue()
        frameProcessingTask?.cancel()
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
    func connect(reconnect: Bool = false) {
        guard !isDisconnecting else { return }
        if state == .streaming || (!reconnect && state == .connecting) {
            return
        }

        shouldMaintainConnection = true
        cancelReconnect()
        if !reconnect {
            reconnectAttempts = 0
        }

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

        guard networkPathSatisfied else {
            let msg = "[StreamService] Network unavailable; waiting to reconnect..."
            print(msg)
            delegate?.streamService(self, didLog: msg)
            state = .disconnected
            delegate?.streamService(self, didChangeState: state)
            return
        }
        
        let connectUrl = serverUrl ?? url
        let connectMsg = "[StreamService] Connecting to configured RTMP server..."
        print(connectMsg)
        delegate?.streamService(self, didLog: connectMsg)
        
        state = .connecting
        delegate?.streamService(self, didChangeState: state)
        
        // A new publisher waits for every previous publisher's teardown.
        let previousTeardown = teardownTask
        let generation = streamingGeneration
        connectTask = Task { @MainActor in
            do {
                if let previousTeardown = previousTeardown { try await previousTeardown.value }
                guard generation == self.streamingGeneration, !Task.isCancelled else { return }
                // Install a generation-owned bounded queue only after publish succeeds.
                
                // Step 1: Create RTMP connection
                let connection = RTMPConnection(requestTimeout: 15000)
                rtmpConnection = connection
                
                // Status listener for LOGGING only (not for driving state)
                statusTask = Task { @MainActor in
                    for await status in await connection.status {
                        guard generation == self.streamingGeneration, !Task.isCancelled else { return }
                        let msg = "[StreamService] 📡 RTMP status: \(status.code)"
                        print(msg)
                        await MainActor.run {
                            self.delegate?.streamService(self, didLog: msg)
                        }

                        if self.isRecoverableRTMPStatus(status.code) {
                            await MainActor.run {
                guard generation == self.streamingGeneration, !Task.isCancelled else { return }
                                self.handleConnectionInterrupted(reason: status.code)
                            }
                        }
                    }
                }
                
                // Step 2: Connect to RTMP server
                print("[StreamService] Step 1/5: Connecting to configured RTMP server...")
                _ = try await connection.connect(connectUrl)
                guard generation == self.streamingGeneration, !Task.isCancelled else { return }
                print("[StreamService] Step 1/5: ✅ Connected!")
                
                await MainActor.run {
                guard generation == self.streamingGeneration, !Task.isCancelled else { return }
                    self.delegate?.streamService(self, didLog: "[StreamService] ✅ Connected to RTMP server")
                    self.delegate?.streamServiceDidConnect(self)
                }
                
                // Step 3: Create RTMP stream
                print("[StreamService] Step 2/5: Creating stream + mixer...")
                guard generation == self.streamingGeneration, !Task.isCancelled else { return }
                let stream = RTMPStream(connection: connection)
                rtmpStream = stream
                let mixer = MediaMixer()
                mediaMixer = mixer
                await mixer.addOutput(stream)
                guard generation == self.streamingGeneration, !Task.isCancelled else { return }
                await mixer.startRunning()
                guard generation == self.streamingGeneration, !Task.isCancelled else { return }
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
                guard generation == self.streamingGeneration, !Task.isCancelled else { return }
                // Bound HaishinKit's raw video encoder input before publish creates its stream.
                await stream.setVideoInputBufferCounts(2)
                guard generation == self.streamingGeneration, !Task.isCancelled else { return }
                
                var audioSettings = AudioCodecSettings()
                audioSettings.bitRate = audioBitrate
                audioSettings.format = .aac
                await stream.setAudioSettings(audioSettings)
                guard generation == self.streamingGeneration, !Task.isCancelled else { return }
                print("[StreamService] Step 3/5: ✅ Codecs configured (video: \(outputWidth)x\(outputHeight) @ \(videoBitrate)bps)")
                
                // Step 5: Wait for createStream() to finish (RTMPStream.init fires it async)
                print("[StreamService] Step 4/5: Waiting for server stream ID...")
                let maxWait = 50 // 50 x 100ms = 5 seconds
                var waited = 0
                while await stream.readyState != .idle && waited < maxWait {
                    try await Task.sleep(nanoseconds: 100_000_000) // 100ms
                guard generation == self.streamingGeneration, !Task.isCancelled else { return }
                    waited += 1
                }
                
                let readyState = await stream.readyState
                guard generation == self.streamingGeneration, !Task.isCancelled else { return }
                print("[StreamService] Step 4/5: readyState=\(readyState), waited \(waited * 100)ms")
                
                await MainActor.run {
                guard generation == self.streamingGeneration, !Task.isCancelled else { return }
                    self.delegate?.streamService(self, didLog: "[StreamService] Stream ready: state=\(readyState)")
                }
                
                guard readyState == .idle else {
                    let errMsg = "[StreamService] ❌ Stream never became ready (stuck at \(readyState))"
                    print(errMsg)
                    await MainActor.run {
                guard generation == self.streamingGeneration, !Task.isCancelled else { return }
                        self.delegate?.streamService(self, didLog: errMsg)
                        self.state = .error
                        self.delegate?.streamService(self, didChangeState: .error)
                    }
                    return
                }
                
                // Step 6: PUBLISH!
                print("[StreamService] Step 5/5: Publishing with configured stream key...")
                await MainActor.run {
                guard generation == self.streamingGeneration, !Task.isCancelled else { return }
                    self.delegate?.streamService(self, didLog: "[StreamService] 🚀 Publishing...")
                }
                
                guard generation == self.streamingGeneration, !Task.isCancelled else { return }
                _ = try await stream.publish(key)
                guard generation == self.streamingGeneration, !Task.isCancelled else { return }
                print("[StreamService] Publish succeeded")
                
                // publish() returns AFTER NetStream.Publish.Start
                // → outgoing.startRunning() has been called
                // → encoder is ready for frames
                await MainActor.run {
                guard generation == self.streamingGeneration, !Task.isCancelled else { return }
                    self.startFrameQueue(stream: stream, mixer: mixer, generation: generation)
                    self.state = .streaming
                    self.streamStartTime = Date()
                    self.startStatsTimer()
                    self.delegate?.streamService(self, didLog: "[StreamService] 🎉 LIVE — encoder running, accepting frames!")
                    self.delegate?.streamService(self, didChangeState: .streaming)
                }
                
            } catch {
                guard generation == self.streamingGeneration, !Task.isCancelled else { return }
                let errorMsg: String
                if let rtmpError = error as? RTMPStream.Error {
                    switch rtmpError {
                    case .invalidState:
                        errorMsg = "[StreamService] ❌ Failed: invalidState"
                    case .requestTimedOut:
                        errorMsg = "[StreamService] ❌ Failed: requestTimedOut (15s)"
                    case .requestFailed:
                        errorMsg = "[StreamService] Failed: requestFailed"
                    }
                } else {
                    errorMsg = "[StreamService] Streaming operation failed"
                }
                print(errorMsg)
                await MainActor.run {
                guard generation == self.streamingGeneration, !Task.isCancelled else { return }
                    self.delegate?.streamService(self, didLog: errorMsg)
                    self.state = .error
                    self.delegate?.streamService(self, didFailWithError: error)
                    self.delegate?.streamService(self, didChangeState: .error)
                    if self.shouldMaintainConnection, self.reconnectAttempts > 0 {
                        self.scheduleReconnect(reason: "Connect failed")
                    }
                }
            }
        }
    }
    
    /// Disconnect from RTMP server
    func disconnect(completion: ((Error?) -> Void)? = nil) {
        shouldMaintainConnection = false
        reconnectAttempts = 0
        cancelReconnect()
        isDisconnecting = true
        let teardown = tearDownStreamingResources()
        let generation = streamingGeneration
        Task { @MainActor in
            do {
                try await teardown.value
                guard generation == self.streamingGeneration else {
                    completion?(NSError(domain: "StreamService", code: -4, userInfo: [NSLocalizedDescriptionKey: "Teardown superseded"]))
                    return
                }
                isDisconnecting = false
                state = .disconnected
                streamStartTime = nil
                totalFrameCount = 0
                totalAudioBufferCount = 0
                delegate?.streamService(self, didChangeState: state)
                delegate?.streamServiceDidDisconnect(self)
                completion?(nil)
            } catch {
                // Failed closure stays fenced. Never acknowledge a timeout or failed close as teardown.
                completion?(error)
            }
        }
    }
    
    // MARK: - Recording Methods
    
    /// Start local recording. The view/manager owns this setup on Main.
    @objc func startRecording(to fileUrl: URL) {
        guard !isRecording, !isRecordingProcessing else { return }
        do {
            if FileManager.default.fileExists(atPath: fileUrl.path) {
                try FileManager.default.removeItem(at: fileUrl)
            }
            let writer = try AVAssetWriter(outputURL: fileUrl, fileType: .mp4)
            let videoSettings: [String: Any] = [
                AVVideoCodecKey: AVVideoCodecType.h264,
                AVVideoWidthKey: outputWidth,
                AVVideoHeightKey: outputHeight
            ]
            let videoInput = AVAssetWriterInput(mediaType: .video, outputSettings: videoSettings)
            videoInput.expectsMediaDataInRealTime = true
            let adaptor = AVAssetWriterInputPixelBufferAdaptor(
                assetWriterInput: videoInput,
                sourcePixelBufferAttributes: [
                    kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
                    kCVPixelBufferWidthKey as String: outputWidth,
                    kCVPixelBufferHeightKey as String: outputHeight
                ]
            )
            let audioInput = AVAssetWriterInput(mediaType: .audio, outputSettings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVNumberOfChannelsKey: 1,
                AVSampleRateKey: 44100,
                AVEncoderBitRateKey: 128000
            ])
            audioInput.expectsMediaDataInRealTime = true
            guard writer.canAdd(videoInput), writer.canAdd(audioInput) else {
                recordingQueue.async { writer.cancelWriting() }
                return
            }
            writer.add(videoInput)
            writer.add(audioInput)
            guard writer.startWriting(), writer.status == .writing else {
                if writer.status == .writing || writer.status == .unknown {
                    recordingQueue.async { writer.cancelWriting() }
                }
                return
            }
            assetWriter = writer
            assetWriterVideoInput = videoInput
            assetWriterAudioInput = audioInput
            assetWriterInputAdaptor = adaptor
            recordingUrl = fileUrl
            recordingStartTime = .invalid
            recordingFailure = nil
            recordingVideoFrameCount = 0
            recordingStateLock.lock()
            recordingGeneration += 1
            recordingAdmissionFailed = false
            recordingActive = true
            recordingStateLock.unlock()
        } catch {
            // The view reports one sanitized RECORDING_FAILED start event.
            return
        }
    }

    /// Kept for native selector compatibility; the URL is now delivered asynchronously.
    @objc func stopRecording() -> URL? {
        stopRecordingAsync { _ in }
        return nil
    }

    func stopRecordingAsync(completion: @escaping (Result<URL, Error>) -> Void) {
        recordingStateLock.lock()
        guard recordingActive || recordingProcessing else {
            recordingStateLock.unlock()
            DispatchQueue.main.async { completion(.failure(self.recordingError("No active recording"))) }
            return
        }
        recordingStopCompletions.append(completion)
        if recordingProcessing {
            recordingStateLock.unlock()
            return
        }
        recordingActive = false
        recordingProcessing = true
        // Enqueue under the admission lock, after every previously admitted frame.
        recordingQueue.async { self.finalizeRecording() }
        recordingStateLock.unlock()
    }

    private func recordingError(_ message: String) -> Error {
        NSError(domain: "StreamService.Recording", code: -1, userInfo: [NSLocalizedDescriptionKey: message])
    }

    private func failRecording(_ error: Error) {
        guard recordingFailure == nil else { return }
        recordingFailure = error
        recordingStateLock.lock()
        let generation = recordingGeneration
        recordingStateLock.unlock()
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.recordingStateLock.lock()
            let current = self.recordingActive && self.recordingGeneration == generation
            self.recordingStateLock.unlock()
            guard current else { return }
            self.onRecordingFailure?(error)
        }
    }

    private func finalizeRecording() {
        guard let writer = assetWriter else {
            completeRecording(writer: nil, result: .failure(recordingError("Recording writer unavailable")))
            return
        }
        if recordingFailure == nil && (writer.status != .writing || recordingVideoFrameCount == 0) {
            recordingFailure = writer.error ?? recordingError("Recording contains no writable video session")
        }
        if let failure = recordingFailure {
            if writer.status == .writing || writer.status == .unknown { writer.cancelWriting() }
            completeRecording(writer: writer, result: .failure(failure))
            return
        }
        assetWriterVideoInput?.markAsFinished()
        assetWriterAudioInput?.markAsFinished()
        let url = recordingUrl
        writer.finishWriting {
            self.recordingQueue.async {
                // AV completion may be delayed or duplicated; only its original writer owns cleanup.
                guard self.assetWriter === writer else { return }
                let result: Result<URL, Error>
                if writer.status == .completed, writer.error == nil, let url = url {
                    result = .success(url)
                } else {
                    result = .failure(writer.error ?? self.recordingError("Recording finalization failed"))
                }
                self.completeRecording(writer: writer, result: result)
            }
        }
    }

    private func completeRecording(writer: AVAssetWriter?, result: Result<URL, Error>) {
        guard assetWriter === writer else { return }
        assetWriter = nil
        assetWriterVideoInput = nil
        assetWriterAudioInput = nil
        assetWriterInputAdaptor = nil
        recordingUrl = nil
        recordingStartTime = .invalid
        recordingFailure = nil
        recordingVideoFrameCount = 0
        recordingStateLock.lock()
        let completions = recordingStopCompletions
        recordingStopCompletions.removeAll()
        recordingProcessing = false
        recordingAdmissionFailed = false
        recordingStateLock.unlock()
        DispatchQueue.main.async { completions.forEach { $0(result) } }
    }

    @objc func recordVideoFrame(_ sampleBuffer: CMSampleBuffer) {
        recordingStateLock.lock()
        defer { recordingStateLock.unlock() }
        // Include the in-flight append: at most one additional video buffer is retained.
        guard recordingActive, !recordingAdmissionFailed, recordingPendingVideo < 2 else { return }
        recordingPendingVideo += 1
        recordingQueue.async {
            self.appendRecordingVideoFrame(sampleBuffer)
            self.recordingStateLock.lock()
            self.recordingPendingVideo -= 1
            self.recordingStateLock.unlock()
        }
    }

    private func appendRecordingVideoFrame(_ sampleBuffer: CMSampleBuffer) {
        guard recordingFailure == nil, let writer = assetWriter,
              let input = assetWriterVideoInput, let adaptor = assetWriterInputAdaptor else { return }
        guard writer.status == .writing else {
            failRecording(writer.error ?? recordingError("Recording writer stopped accepting video"))
            return
        }
        guard input.isReadyForMoreMediaData else { return }
        if recordingStartTime == .invalid {
            writer.startSession(atSourceTime: sampleBuffer.presentationTimeStamp)
            recordingStartTime = sampleBuffer.presentationTimeStamp
        }
        let appended: Bool
        if let image = CMSampleBufferGetImageBuffer(sampleBuffer) {
            appended = adaptor.append(image, withPresentationTime: sampleBuffer.presentationTimeStamp)
        } else {
            appended = input.append(sampleBuffer)
        }
        guard appended, writer.status == .writing else {
            failRecording(writer.error ?? recordingError("Recording video append failed"))
            return
        }
        recordingVideoFrameCount += 1
    }

    @objc func recordAudioFrame(_ sampleBuffer: CMSampleBuffer) {
        recordingStateLock.lock()
        defer { recordingStateLock.unlock() }
        guard recordingActive, !recordingAdmissionFailed else { return }
        let bytes = CMSampleBufferGetTotalSampleSize(sampleBuffer)
        // Audio is never silently dropped on overload. Fence all further admission
        // and report one failure after already-admitted buffers have been processed.
        guard bytes > 0, recordingPendingAudio < 128,
              bytes <= 256 * 1024 - recordingPendingAudioBytes else {
            recordingAdmissionFailed = true
            recordingQueue.async {
                self.failRecording(self.recordingError("Recording audio backlog exceeded its bounded capacity"))
            }
            return
        }
        recordingPendingAudio += 1
        recordingPendingAudioBytes += bytes
        recordingQueue.async {
            self.appendRecordingAudioFrame(sampleBuffer)
            self.recordingStateLock.lock()
            self.recordingPendingAudio -= 1
            self.recordingPendingAudioBytes -= bytes
            self.recordingStateLock.unlock()
        }
    }

    private func appendRecordingAudioFrame(_ sampleBuffer: CMSampleBuffer) {
        guard recordingFailure == nil, let writer = assetWriter, let input = assetWriterAudioInput else { return }
        guard writer.status == .writing else {
            failRecording(writer.error ?? recordingError("Recording writer stopped accepting audio"))
            return
        }
        guard recordingStartTime != .invalid, input.isReadyForMoreMediaData else { return }
        guard input.append(sampleBuffer), writer.status == .writing else {
            failRecording(writer.error ?? recordingError("Recording audio append failed"))
            return
        }
    }
    
    /// Stop publishing stream
    func stopPublishing() {
        disconnect()
    }
    
    /// Advisory only: enqueue still checks atomically after potentially expensive composition.
    var canAcceptVideoFrame: Bool { currentFrameQueue()?.hasVideoCapacity ?? false }

    /// Call once when deciding whether to skip a non-recording frame's composition.
    func shouldRenderVideoFrame() -> Bool { currentFrameQueue()?.shouldRenderVideoFrame() ?? false }

    @MainActor
    private func startFrameQueue(stream: RTMPStream, mixer: MediaMixer, generation: Int) {
        let queue = BoundedFrameQueue<FrameData>()
        frameQueueLock.lock()
        precondition(frameQueue == nil, "Previous publisher queue must be finished before replacement")
        frameQueue = queue
        frameQueueLock.unlock()

        // Capture this publisher's stream and mixer; never fetch replacements after an await.
        frameProcessingTask = Task { @MainActor [weak self] in
            while let frame = await queue.next() {
                guard let self = self, generation == self.streamingGeneration,
                      !Task.isCancelled else { break }
                switch frame {
                case .video(let buffer): await stream.append(buffer)
                case .audio(let buffer): await mixer.append(buffer)
                }
                guard generation == self.streamingGeneration, !Task.isCancelled else { break }
                // Counters reflect encoder/mixer admission, not actual encoded delivery.
                // HaishinKit's bounded video queue can drop frames internally.
                switch frame {
                case .video:
                    self.totalFrameCount += 1
                    self.statsFrameCount += 1
                case .audio:
                    self.totalAudioBufferCount += 1
                }
            }
            queue.finish()
        }
    }

    private func currentFrameQueue(audio: Bool = false) -> BoundedFrameQueue<FrameData>? {
        frameQueueLock.lock()
        defer { frameQueueLock.unlock() }
        return audio && isMuted ? nil : frameQueue
    }

    private func isCurrentFrameQueue(_ queue: BoundedFrameQueue<FrameData>) -> Bool {
        frameQueueLock.lock()
        defer { frameQueueLock.unlock() }
        return frameQueue === queue
    }

    private func finishFrameQueue() {
        frameQueueLock.lock()
        let queue = frameQueue
        frameQueue = nil
        // Finish under the admission-pointer lock so no callback can acquire a new
        // generation while this one still accepts work. Producer snapshots remain local.
        queue?.finish()
        frameQueueLock.unlock()
    }

    /// Send video frame to stream. Admission is open only after publish succeeds.
    func sendVideoFrame(_ sampleBuffer: CMSampleBuffer) {
        guard let queue = currentFrameQueue() else { return }
        queue.enqueue(.video(sampleBuffer), kind: .video)
    }

    /// Audio is never evicted to make room for video. A bounded overload is a failure.
    func sendAudioBuffer(_ sampleBuffer: CMSampleBuffer) {
        guard let queue = currentFrameQueue(audio: true) else { return }
        var duration = sampleBuffer.duration.seconds
        if let format = CMSampleBufferGetFormatDescription(sampleBuffer),
           let description = CMAudioFormatDescriptionGetStreamBasicDescription(format) {
            let rate = description.pointee.mSampleRate
            let samples = CMSampleBufferGetNumSamples(sampleBuffer)
            if rate.isFinite, rate > 0, samples > 0 { duration = Double(samples) / rate }
        }
        let bytes = CMSampleBufferGetTotalSampleSize(sampleBuffer)
        if case .failed(let failure) = queue.enqueue(.audio(sampleBuffer), kind: .audio(duration: duration, bytes: bytes)) {
            Task { @MainActor [weak self] in
                guard let self = self, self.isCurrentFrameQueue(queue) else { return }
                await self.failFrameQueue(queue, failure: failure)
            }
        }
    }

    @MainActor
    private func failFrameQueue(_ queue: BoundedFrameQueue<FrameData>, failure: BoundedFrameQueue<FrameData>.Failure) async {
        guard isCurrentFrameQueue(queue) else { return }
        // Controlled failure: join the normal teardown, but do not run disconnect's
        // success transition, which would hide this error with a later .disconnected.
        shouldMaintainConnection = false
        reconnectAttempts = 0
        cancelReconnect()
        isDisconnecting = true
        let teardown = tearDownStreamingResources()
        let generation = streamingGeneration
        state = .error
        delegate?.streamService(self, didFailWithError: failure)
        delegate?.streamService(self, didChangeState: .error)

        do {
            try await teardown.value
            guard generation == streamingGeneration else { return }
            isDisconnecting = false
            streamStartTime = nil
            totalFrameCount = 0
            totalAudioBufferCount = 0
            delegate?.streamServiceDidDisconnect(self)
        } catch {
            guard generation == streamingGeneration else { return }
            // A failed close remains fenced; the original queue failure stays visible.
            delegate?.streamService(self, didLog: "[StreamService] Frame queue teardown failed")
        }
    }

    /// Mute/unmute audio
    func setMuted(_ muted: Bool) {
        frameQueueLock.lock()
        isMuted = muted
        frameQueueLock.unlock()
        print("[StreamService] Muted: \(muted)")
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
        print("[StreamService] Update config")
        
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

    private func startNetworkMonitoring() {
        networkMonitor.pathUpdateHandler = { [weak self] path in
            guard let self = self else { return }

            let signature = self.networkSignature(for: path)
            let previousSignature = self.networkPathSignature
            let routeChanged = previousSignature != nil && previousSignature != signature
            self.networkPathSignature = signature
            self.networkPathSatisfied = path.status == .satisfied

            DispatchQueue.main.async { [weak self] in
                guard let self = self, self.shouldMaintainConnection else { return }

                if path.status != .satisfied {
                    self.handleConnectionInterrupted(reason: "Network unavailable")
                    return
                }

                if routeChanged && (self.state == .streaming || self.state == .connecting) {
                    self.handleConnectionInterrupted(reason: "Network route changed")
                } else if self.state == .disconnected || self.state == .error {
                    self.scheduleReconnect(reason: "Network available")
                }
            }
        }
        networkMonitor.start(queue: networkMonitorQueue)
    }

    private func networkSignature(for path: NWPath) -> String {
        var interfaces: [String] = []
        if path.usesInterfaceType(.wifi) {
            interfaces.append("wifi")
        }
        if path.usesInterfaceType(.cellular) {
            interfaces.append("cellular")
        }
        if path.usesInterfaceType(.wiredEthernet) {
            interfaces.append("wired")
        }
        if path.usesInterfaceType(.loopback) {
            interfaces.append("loopback")
        }
        if path.usesInterfaceType(.other) {
            interfaces.append("other")
        }
        return "\(path.status)-\(interfaces.joined(separator: ","))"
    }

    private func isRecoverableRTMPStatus(_ code: String) -> Bool {
        return code == RTMPConnection.Code.connectClosed.rawValue
            || code == RTMPConnection.Code.connectFailed.rawValue
    }

    private func handleConnectionInterrupted(reason: String) {
        guard shouldMaintainConnection else { return }
        guard state == .streaming || state == .connecting else { return }

        let msg = "[StreamService] Network interruption detected (\(reason)); reconnecting..."
        print(msg)
        delegate?.streamService(self, didLog: msg)

        tearDownStreamingResources()
        state = .disconnected
        delegate?.streamService(self, didChangeState: state)
        scheduleReconnect(reason: reason)
    }

    private func scheduleReconnect(reason: String) {
        guard shouldMaintainConnection, networkPathSatisfied else { return }
        guard reconnectWorkItem == nil else { return }
        guard state != .streaming && state != .connecting else { return }

        reconnectAttempts += 1
        let delay = min(pow(2.0, Double(max(0, reconnectAttempts - 1))), 10.0)
        let msg = "[StreamService] Scheduling RTMP reconnect attempt \(reconnectAttempts) in \(String(format: "%.1f", delay))s (\(reason))"
        print(msg)
        delegate?.streamService(self, didLog: msg)
        state = .connecting
        delegate?.streamService(self, didChangeState: state)

        let workItem = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            self.reconnectWorkItem = nil
            guard self.shouldMaintainConnection else { return }
            self.connect(reconnect: true)
        }

        reconnectWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: workItem)
    }

    private func cancelReconnect() {
        reconnectWorkItem?.cancel()
        reconnectWorkItem = nil
    }

    @discardableResult
    private func tearDownStreamingResources() -> Task<Void, Error> {
        streamingGeneration += 1
        stopStatsTimer()
        let connecting = connectTask
        connectTask = nil
        connecting?.cancel()
        let status = statusTask
        statusTask = nil
        status?.cancel()
        let frames = frameProcessingTask
        finishFrameQueue()
        frameProcessingTask = nil
        frames?.cancel()
        let previous = teardownTask

        let task = Task { @MainActor in
            var failure: Error?
            if let previous = previous {
                do { try await previous.value } catch { failure = error }
            }
            // Cancellation alone does not stop HaishinKit continuations. Join the connect flow
            // before taking ownership of its final resource set.
            await connecting?.value
            await frames?.value
            let connection = self.rtmpConnection
            let stream = self.rtmpStream
            let mixer = self.mediaMixer
            self.rtmpStream = nil
            self.rtmpConnection = nil
            self.mediaMixer = nil
            if let mixer = mixer {
                if let stream = stream { await mixer.removeOutput(stream) }
                await mixer.stopRunning()
            }
            if let stream = stream {
                let ready = await stream.readyState
                if ready == .publishing || ready == .playing {
                    do { _ = try await stream.close() } catch { failure = error }
                }
            }
            // Even when unpublish fails, close the transport. An invalid-state error is
            // conservatively a failed acknowledgment, not proof that the socket is closed.
            if let connection = connection {
                do { try await connection.close() } catch { failure = error }
            }
            _ = try? await status?.value
            if let failure = failure { throw failure }
        }
        teardownTask = task
        return task
    }
    
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
        if let queue = currentFrameQueue() { stats.droppedFrames = queue.droppedVideoCount }
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
