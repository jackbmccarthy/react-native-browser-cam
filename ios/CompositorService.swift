//
//  CompositorService.swift
//  react-native-web-cam-stream
//
//  Composites Camera and WebView frames using CoreImage
//

import Foundation
import CoreImage
import AVFoundation
import UIKit

/// Service for compositing camera and WebView frames
class CompositorService: NSObject {
    
    // MARK: - Properties
    
    /// Core Image context for GPU processing
    private var ciContext: CIContext?
    private let deviceRGBColorSpace = CGColorSpaceCreateDeviceRGB()
    
    /// Pixel buffer pool for efficient buffer reuse
    private var pixelBufferPool: CVPixelBufferPool?
    private let formatDescriptionLock = NSLock()
    private var outputFormatDescription: CMVideoFormatDescription?
    
    /// Output dimensions (current)
    var outputWidth: Int = 1920
    var outputHeight: Int = 1080
    
    /// Target dimensions (from config)
    private var targetWidth: Int = 1920
    private var targetHeight: Int = 1080
    
    /// WebView overlay rect (relative 0..1)
    var webViewRect: CGRect = CGRect(x: 0, y: 0, width: 1, height: 1)
    
    /// Background color for compositing
    var backgroundColor: CIColor = .black
    
    /// Is compositor running
    private(set) var isRunning: Bool = false
    
    /// Delegate for compositor events
    weak var delegate: CompositorServiceDelegate?
    
    // MARK: - Initialization
    
    override init() {
        super.init()
        setupCompositor()
        print("[CompositorService] Initialized")
    }
    
    deinit {
        stop()
    }
    
    // MARK: - Setup
    
    private func setupCompositor() {
        // Create CIContext with Metal for GPU acceleration
        if let metalDevice = MTLCreateSystemDefaultDevice() {
            ciContext = CIContext(mtlDevice: metalDevice, options: [
                .workingColorSpace: deviceRGBColorSpace,
                .highQualityDownsample: true,
                .cacheIntermediates: false
            ])
            print("[CompositorService] CIContext created with Metal")
        } else {
            ciContext = CIContext(options: [
                .useSoftwareRenderer: false
            ])
            print("[CompositorService] CIContext created (CPU fallback)")
        }
        
        // Create initial pixel buffer pool
        createPixelBufferPool()
    }
    
    // MARK: - Public Methods
    
    /// Start the compositor
    func start() {
        print("[CompositorService] Starting...")
        isRunning = true
        delegate?.compositorServiceDidStart(self)
    }
    
    /// Stop the compositor
    func stop() {
        print("[CompositorService] Stopping...")
        isRunning = false
        delegate?.compositorServiceDidStop(self)
    }
    
    /// Set output dimensions
    func setOutputSize(width: Int, height: Int) {
        targetWidth = width
        targetHeight = height
        outputWidth = width
        outputHeight = height
        print("[CompositorService] Target output size set to \(width)x\(height)")
        
        // Recreate pool with new size
        createPixelBufferPool()
    }
    
    /// Update WebView overlay layout
    func setWebViewLayout(x: CGFloat, y: CGFloat, width: CGFloat, height: CGFloat) {
        webViewRect = CGRect(x: x, y: y, width: width, height: height)
        print("[CompositorService] WebView layout updated: \(webViewRect)")
    }
    
    /// Composite camera frame with one or more WebView overlays
    func composite(cameraBuffer: CMSampleBuffer, webFrames: [OverlayRenderFrame]) -> CMSampleBuffer? {
        guard isRunning, let ciContext = ciContext else { return nil }
        
        // 1. Extract CVPixelBuffer from camera sample buffer
        guard let cameraPixelBuffer = CMSampleBufferGetImageBuffer(cameraBuffer) else {
            return cameraBuffer
        }
        
        let cameraWidth = CGFloat(CVPixelBufferGetWidth(cameraPixelBuffer))
        let cameraHeight = CGFloat(CVPixelBufferGetHeight(cameraPixelBuffer))
        
        // 2. Dynamic Orientation Check
        // If the input buffer dimensions are vertical (Portrait) but output is horizontal (Landscape), flip output.
        let isInputPortrait = cameraHeight > cameraWidth
        let isOutputPortrait = outputHeight > outputWidth
        
        if isInputPortrait != isOutputPortrait {
            print("[CompositorService] 🔄 Orientation flip detected! Adjusting output size.")
            let oldW = outputWidth
            let oldH = outputHeight
            outputWidth = oldH
            outputHeight = oldW
            createPixelBufferPool()
        }
        
        // 3. Create CIImage from camera frame
        var cameraCIImage = CIImage(cvPixelBuffer: cameraPixelBuffer)
        
        // Scale camera image to output size (aspect fill style)
        let scaleX = CGFloat(outputWidth) / cameraWidth
        let scaleY = CGFloat(outputHeight) / cameraHeight
        let scale = max(scaleX, scaleY)
        cameraCIImage = cameraCIImage.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        
        // Center crop if needed
        let cropX = (cameraCIImage.extent.width - CGFloat(outputWidth)) / 2
        let cropY = (cameraCIImage.extent.height - CGFloat(outputHeight)) / 2
        cameraCIImage = cameraCIImage.cropped(to: CGRect(x: cameraCIImage.extent.origin.x + cropX, y: cameraCIImage.extent.origin.y + cropY, width: CGFloat(outputWidth), height: CGFloat(outputHeight)))
        // Reset origin to (0,0) after crop for easier compositing
        cameraCIImage = cameraCIImage.transformed(by: CGAffineTransform(translationX: -cameraCIImage.extent.origin.x, y: -cameraCIImage.extent.origin.y))
        
        // 4. Convert overlay UIImages to CIImages and blend in order
        var compositedImage = cameraCIImage

        for overlay in webFrames {
            guard let webCGImage = overlay.image.cgImage else { continue }
            var webCIImage = CIImage(cgImage: webCGImage)

            let destX = overlay.rect.origin.x * CGFloat(outputWidth)
            let destY = (1.0 - overlay.rect.origin.y - overlay.rect.size.height) * CGFloat(outputHeight)
            let destW = overlay.rect.size.width * CGFloat(outputWidth)
            let destH = overlay.rect.size.height * CGFloat(outputHeight)

            guard destW > 0, destH > 0 else { continue }

            let webScaleX = destW / webCIImage.extent.width
            let webScaleY = destH / webCIImage.extent.height
            webCIImage = webCIImage.transformed(by: CGAffineTransform(scaleX: webScaleX, y: webScaleY))
            webCIImage = webCIImage.transformed(by: CGAffineTransform(translationX: destX, y: destY))

            compositedImage = webCIImage.composited(over: compositedImage)
        }
        
        // 5. Render to output pixel buffer
        guard let outputBuffer = getOutputPixelBuffer() else {
            if CFAbsoluteTimeGetCurrent().truncatingRemainder(dividingBy: 5.0) < 0.1 {
                print("[CompositorService] ❌ Failed to get output pixel buffer (pool or standalone)")
            }
            return cameraBuffer
        }
        
        ciContext.render(compositedImage, to: outputBuffer, bounds: CGRect(x: 0, y: 0, width: outputWidth, height: outputHeight), colorSpace: deviceRGBColorSpace)
        
        // 6. Create new CMSampleBuffer from output pixel buffer
        var timing = CMSampleTimingInfo(from: cameraBuffer)
        
        // Force valid duration (30fps) if missing/invalid
        if timing.duration.isValid == false || timing.duration.value == 0 {
            timing.duration = CMTime(value: 1, timescale: 30)
        }
        
        // Force invalid DecodeTimeStamp for raw frames (encoder will handle it)
        timing.decodeTimeStamp = .invalid
        
        if let outputSampleBuffer = createSampleBuffer(from: outputBuffer, timing: timing) {
            return outputSampleBuffer
        }
        
        return cameraBuffer
    }

    /// Backward-compatible single overlay compositing
    func composite(cameraBuffer: CMSampleBuffer, webImage: UIImage?) -> CMSampleBuffer? {
        let frames: [OverlayRenderFrame]
        if let webImage = webImage {
            frames = [OverlayRenderFrame(image: webImage, rect: webViewRect, index: 0)]
        } else {
            frames = []
        }
        return composite(cameraBuffer: cameraBuffer, webFrames: frames)
    }
    
    /// Composite camera frame with WebView overlay (pixel buffer versions)
    func composite(cameraPixelBuffer: CVPixelBuffer, webPixelBuffer: CVPixelBuffer?) -> CVPixelBuffer? {
        guard isRunning, let ciContext = ciContext else { return cameraPixelBuffer }
        
        // 1. Create CIImage from camera buffer
        var cameraCIImage = CIImage(cvPixelBuffer: cameraPixelBuffer)
        
        // Scale to output size
        let cameraWidth = CGFloat(CVPixelBufferGetWidth(cameraPixelBuffer))
        let cameraHeight = CGFloat(CVPixelBufferGetHeight(cameraPixelBuffer))
        let scaleX = CGFloat(outputWidth) / cameraWidth
        let scaleY = CGFloat(outputHeight) / cameraHeight
        cameraCIImage = cameraCIImage.transformed(by: CGAffineTransform(scaleX: scaleX, y: scaleY))
        
        var compositedImage = cameraCIImage
        
        // 2. Blend with web overlay if provided
        if let webPixelBuffer = webPixelBuffer {
            var webCIImage = CIImage(cvPixelBuffer: webPixelBuffer)
            
            let webWidth = CGFloat(CVPixelBufferGetWidth(webPixelBuffer))
            let webHeight = CGFloat(CVPixelBufferGetHeight(webPixelBuffer))
            let webScaleX = CGFloat(outputWidth) / webWidth
            let webScaleY = CGFloat(outputHeight) / webHeight
            webCIImage = webCIImage.transformed(by: CGAffineTransform(scaleX: webScaleX, y: webScaleY))
            
            // 3. Source-over compositing (web overlay on top of camera)
            compositedImage = webCIImage.composited(over: cameraCIImage)
        }
        
        // 4. Render to output buffer
        guard let outputBuffer = getOutputPixelBuffer() else {
            return cameraPixelBuffer
        }
        
        let outputRect = CGRect(x: 0, y: 0, width: outputWidth, height: outputHeight)
        ciContext.render(compositedImage, to: outputBuffer, bounds: outputRect, colorSpace: deviceRGBColorSpace)
        
        return outputBuffer
    }
    
    // MARK: - Private Methods
    
    private func createPixelBufferPool() {
        if let pool = pixelBufferPool,
           let attributes = CVPixelBufferPoolGetPixelBufferAttributes(pool) as? [String: Any],
           let width = attributes[kCVPixelBufferWidthKey as String] as? NSNumber,
           let height = attributes[kCVPixelBufferHeightKey as String] as? NSNumber,
           width.intValue == outputWidth, height.intValue == outputHeight {
            return
        }

        formatDescriptionLock.lock()
        outputFormatDescription = nil
        formatDescriptionLock.unlock()

        // Release existing pool
        pixelBufferPool = nil
        
        let poolAttributes: [CFString: Any] = [
            kCVPixelBufferPoolMinimumBufferCountKey: 3
        ]
        
        let pixelBufferAttributes: [CFString: Any] = [
            kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            kCVPixelBufferWidthKey: outputWidth,
            kCVPixelBufferHeightKey: outputHeight,
            kCVPixelBufferMetalCompatibilityKey: true,
            kCVPixelBufferIOSurfacePropertiesKey: [:]
        ]
        
        var pool: CVPixelBufferPool?
        let status = CVPixelBufferPoolCreate(
            kCFAllocatorDefault,
            poolAttributes as CFDictionary,
            pixelBufferAttributes as CFDictionary,
            &pool
        )
        
        if status == kCVReturnSuccess {
            pixelBufferPool = pool
            print("[CompositorService] Pixel buffer pool created (\(outputWidth)x\(outputHeight) - NV12)")
        } else {
            print("[CompositorService] ❌ Failed to create pixel buffer pool: \(status)")
        }
    }
    
    private func getOutputPixelBuffer() -> CVPixelBuffer? {
        guard let pool = pixelBufferPool else {
            // Fallback: create a standalone buffer
            var pixelBuffer: CVPixelBuffer?
            let attrs: [CFString: Any] = [
                kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
                kCVPixelBufferWidthKey: outputWidth,
                kCVPixelBufferHeightKey: outputHeight,
                kCVPixelBufferMetalCompatibilityKey: true,
                kCVPixelBufferIOSurfacePropertiesKey: [:]
            ]
            CVPixelBufferCreate(kCFAllocatorDefault, outputWidth, outputHeight, kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange, attrs as CFDictionary, &pixelBuffer)
            return pixelBuffer
        }
        
        var pixelBuffer: CVPixelBuffer?
        CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &pixelBuffer)
        return pixelBuffer
    }
    
    private func formatDescription(for pixelBuffer: CVPixelBuffer) -> CMVideoFormatDescription? {
        formatDescriptionLock.lock()
        defer { formatDescriptionLock.unlock() }

        if let format = outputFormatDescription,
           CMVideoFormatDescriptionMatchesImageBuffer(format, imageBuffer: pixelBuffer) {
            return format
        }

        var formatDescription: CMVideoFormatDescription?
        let formatStatus = CMVideoFormatDescriptionCreateForImageBuffer(
            allocator: kCFAllocatorDefault,
            imageBuffer: pixelBuffer,
            formatDescriptionOut: &formatDescription
        )

        guard formatStatus == noErr, let format = formatDescription else {
            print("[CompositorService] Failed to create format description")
            return nil
        }

        outputFormatDescription = format
        return format
    }

    private func createSampleBuffer(from pixelBuffer: CVPixelBuffer, timing: CMSampleTimingInfo? = nil) -> CMSampleBuffer? {
        guard let format = formatDescription(for: pixelBuffer) else { return nil }
        
        // Create timing info
        var sampleTiming: CMSampleTimingInfo
        if let timing = timing {
            sampleTiming = timing
        } else {
            sampleTiming = CMSampleTimingInfo(
                duration: CMTime.invalid,
                presentationTimeStamp: CMClockGetTime(CMClockGetHostTimeClock()),
                decodeTimeStamp: CMTime.invalid
            )
        }
        
        // Create sample buffer
        var sampleBuffer: CMSampleBuffer?
        let sampleStatus = CMSampleBufferCreateForImageBuffer(
            allocator: kCFAllocatorDefault,
            imageBuffer: pixelBuffer,
            dataReady: true,
            makeDataReadyCallback: nil,
            refcon: nil,
            formatDescription: format,
            sampleTiming: &sampleTiming,
            sampleBufferOut: &sampleBuffer
        )
        
        if sampleStatus != noErr {
            print("[CompositorService] Failed to create sample buffer: \(sampleStatus)")
            return nil
        }
        
        return sampleBuffer
    }
}

// MARK: - CMSampleTimingInfo Extension

extension CMSampleTimingInfo {
    init(from sampleBuffer: CMSampleBuffer) {
        var timing = CMSampleTimingInfo()
        CMSampleBufferGetSampleTimingInfo(sampleBuffer, at: 0, timingInfoOut: &timing)
        self = timing
    }
}

// MARK: - Delegate Protocol

protocol CompositorServiceDelegate: AnyObject {
    /// Called when compositor starts
    func compositorServiceDidStart(_ service: CompositorService)
    
    /// Called when compositor stops
    func compositorServiceDidStop(_ service: CompositorService)
    
    /// Called when a composited frame is ready
    func compositorService(_ service: CompositorService, didComposite sampleBuffer: CMSampleBuffer)
}

// Provide default implementations
extension CompositorServiceDelegate {
    func compositorServiceDidStart(_ service: CompositorService) {}
    func compositorServiceDidStop(_ service: CompositorService) {}
    func compositorService(_ service: CompositorService, didComposite sampleBuffer: CMSampleBuffer) {}
}
