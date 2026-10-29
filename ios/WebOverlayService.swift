//
//  WebOverlayService.swift
//  react-native-browser-cam
//
//  Manages one or more WKWebView overlays for compositing
//

import Foundation
import WebKit
import CoreVideo
import UIKit

struct OverlayRenderFrame {
    let image: UIImage
    let rect: CGRect
    let index: Int
}

private final class OverlayWebViewState {
    let webView: WKWebView
    let overlayID: String
    var currentUrl: URL?
    var reloadRevision: Double?
    var snapshotPending = false
    let snapshotConfiguration: WKSnapshotConfiguration = {
        let configuration = WKSnapshotConfiguration()
        configuration.afterScreenUpdates = false
        return configuration
    }()
    var rect: CGRect
    var viewportSize: CGSize?
    var zoom: CGFloat = 1.0
    var snapshot: UIImage?
    var pendingZoomApplications: [DispatchWorkItem] = []

    init(webView: WKWebView, rect: CGRect, overlayID: String) {
        self.webView = webView
        self.rect = rect
        self.overlayID = overlayID
    }

    func cancelPendingZoomApplications() {
        pendingZoomApplications.forEach { $0.cancel() }
        pendingZoomApplications.removeAll()
    }
}

/// Service for managing WebView overlays
class WebOverlayService: NSObject {

    // MARK: - Properties

    /// Backward-compatible access to the first WebView
    var webView: WKWebView? {
        overlayStates.first?.webView
    }

    /// All active overlay views
    var webViews: [WKWebView] {
        overlayStates.map(\.webView)
    }

    /// Relative overlay layouts (0...1 space)
    var overlayRects: [CGRect] {
        overlayStates.map(\.rect)
    }

    /// Optional browser viewport sizes before each overlay is scaled into its rect.
    var overlayViewportSizes: [CGSize?] {
        overlayStates.map(\.viewportSize)
    }

    /// Update throttle interval in milliseconds
    var updateThrottleMs: Int = 33

    /// Is WebView transparent
    var isTransparent: Bool = true

    /// Delegate for WebView events
    weak var delegate: WebOverlayServiceDelegate?

    /// Snapshot timer for continuous capture
    private var snapshotTimer: DispatchSourceTimer?
    private var captureGeneration: UInt = 0
    private let renderFramesLock = NSLock()
    private var renderFrames: [OverlayRenderFrame] = []

    /// Whether continuous capture is active
    private(set) var isCaptureRunning: Bool = false

    /// Internal overlay states
    private var overlayStates: [OverlayWebViewState] = []

    // MARK: - Initialization

    override init() {
        super.init()
        print("[WebOverlayService] Initialized")
    }

    deinit {
        stopCapture()
        overlayStates.forEach { $0.webView.stopLoading() }
    }

    // MARK: - Setup

    /// Create and configure the initial WKWebView set. Must be called on the main thread.
    func setupWebView(frame: CGRect = .zero) {
        if !Thread.isMainThread {
            DispatchQueue.main.async { [weak self] in
                self?.setupWebView(frame: frame)
            }
            return
        }

        ensureOverlayCount(1, frame: frame)
        print("[WebOverlayService] WebViews created with frame: \(frame)")
    }

    // MARK: - Public Methods

    /// Load a URL in the first WebView for backward compatibility
    func loadUrl(_ urlString: String) {
        if !Thread.isMainThread {
            DispatchQueue.main.async { [weak self] in
                self?.loadUrl(urlString)
            }
            return
        }

        ensureOverlayCount(1, frame: webView?.frame ?? .zero)
        guard let firstState = overlayStates.first else { return }
        loadUrl(urlString, into: firstState)
    }

    /// Reload all active pages
    func reload() {
        if !Thread.isMainThread {
            DispatchQueue.main.async { [weak self] in
                self?.reload()
            }
            return
        }

        print("[WebOverlayService] Reloading overlays...")
        overlayStates.forEach { $0.webView.reload() }
    }

    /// Inject JavaScript into all active overlays
    func injectJavaScript(_ js: String) {
        if !Thread.isMainThread {
            DispatchQueue.main.async { [weak self] in
                self?.injectJavaScript(js)
            }
            return
        }

        print("[WebOverlayService] Injecting JavaScript into overlays...")

        overlayStates.forEach { state in
            state.webView.evaluateJavaScript(js) { _, error in
                if let error = error {
                    print("[WebOverlayService] JS injection error")
                }
            }
        }
    }

    /// Start continuous frame capture at the configured throttle rate
    func startCapture() {
        if !Thread.isMainThread {
            DispatchQueue.main.async { [weak self] in self?.startCapture() }
            return
        }
        guard !isCaptureRunning else { return }
        isCaptureRunning = true
        captureGeneration &+= 1
        let generation = captureGeneration

        let interval = max(Double(updateThrottleMs) / 1000.0, 1.0 / 30.0)
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.main)
        timer.schedule(deadline: .now(), repeating: interval)
        timer.setEventHandler { [weak self] in
            guard let self = self, self.isCaptureRunning,
                  self.captureGeneration == generation else { return }
            self.performSnapshots()
        }
        snapshotTimer = timer
        timer.resume()

        print("[WebOverlayService] Continuous capture started at \(Int(1.0 / interval))fps")
    }

    /// Stop continuous frame capture
    func stopCapture() {
        if !Thread.isMainThread {
            DispatchQueue.main.async { [weak self] in self?.stopCapture() }
            return
        }
        isCaptureRunning = false
        captureGeneration &+= 1
        snapshotTimer?.setEventHandler {}
        snapshotTimer?.cancel()
        snapshotTimer = nil
        // Pending requests keep their per-page slot until WebKit completes them.
        print("[WebOverlayService] Continuous capture stopped")
    }

    /// Backward-compatible capture of the first overlay
    func captureFrame() -> UIImage? {
        captureFrames().first(where: { $0.index == 0 })?.image
    }

    /// Capture all current overlay frames with their layout rects
    func captureFrames() -> [OverlayRenderFrame] {
        renderFramesLock.lock()
        defer { renderFramesLock.unlock() }
        return renderFrames
    }

    // Only the main-thread producer reads mutable WebView state. Camera callbacks
    // retain a copy-on-write array of immutable images and placement values.
    private func publishRenderFrames() {
        let frames = overlayStates.enumerated().compactMap { index, state -> OverlayRenderFrame? in
            guard let image = state.snapshot else { return nil }
            return OverlayRenderFrame(image: image, rect: state.rect, index: index)
        }
        renderFramesLock.lock()
        renderFrames = frames
        renderFramesLock.unlock()
    }

    /// Set transparent background across all overlays
    func setTransparent(_ transparent: Bool) {
        isTransparent = transparent
        overlayStates.forEach { state in
            state.webView.isOpaque = !transparent
            state.webView.backgroundColor = transparent ? .clear : .white
            state.webView.scrollView.backgroundColor = transparent ? .clear : .white
        }
    }

    /// Update overlay configuration
    func updateConfig(_ config: [String: Any]) {
        if !Thread.isMainThread {
            DispatchQueue.main.async { [weak self] in
                self?.updateConfig(config)
            }
            return
        }

        if let throttleMs = config["updateThrottleMs"] as? Int {
            let nextInterval = max(throttleMs, 33)
            let changed = nextInterval != max(updateThrottleMs, 33)
            updateThrottleMs = nextInterval
            if changed, isCaptureRunning {
                stopCapture()
                startCapture()
            }
        }

        if let transparent = config["transparent"] as? Bool {
            setTransparent(transparent)
        }

        let overlayConfigs = parseOverlayConfigs(from: config)
        let frame = webView?.superview?.bounds ?? webView?.frame ?? .zero

        let desiredConfigs = config["overlays"] != nil
            ? overlayConfigs
            : (overlayConfigs.isEmpty ? [config] : overlayConfigs)
        reconcileOverlays(desiredConfigs, frame: frame)

        for (index, overlayConfig) in desiredConfigs.enumerated() {
            applyOverlayConfig(overlayConfig, to: overlayStates[index])
        }
        publishRenderFrames()
    }

    // MARK: - Private Methods

    private func ensureOverlayCount(_ count: Int, frame: CGRect) {
        let safeCount = max(0, count)

        if overlayStates.count > safeCount {
            let removed = Array(overlayStates.suffix(overlayStates.count - safeCount))
            overlayStates.removeLast(overlayStates.count - safeCount)
            removed.forEach { disposeOverlay($0) }
        }

        while overlayStates.count < safeCount {
            let webView = makeWebView(frame: frame)
            overlayStates.append(OverlayWebViewState(webView: webView, rect: CGRect(x: 0, y: 0, width: 1, height: 1), overlayID: "legacy:\(overlayStates.count)"))
        }
        publishRenderFrames()
    }

    private func disposeOverlay(_ state: OverlayWebViewState) {
        state.cancelPendingZoomApplications()
        state.webView.stopLoading()
        state.webView.removeFromSuperview()
    }

    private func reconcileOverlays(_ configs: [[String: Any]], frame: CGRect) {
        var remaining = overlayStates
        var ordered: [OverlayWebViewState] = []
        var occurrences: [String: Int] = [:]

        for (index, config) in configs.enumerated() {
            let overlayID: String
            if let id = config["id"] as? String, !id.isEmpty {
                // Namespaces and occurrence numbers keep legacy/duplicate IDs distinct.
                let occurrence = occurrences[id, default: 0]
                occurrences[id] = occurrence + 1
                overlayID = "id:\(id.utf8.count):\(id):\(occurrence)"
            } else {
                overlayID = "legacy:\(index)"
            }

            if let existing = remaining.firstIndex(where: { $0.overlayID == overlayID }) {
                ordered.append(remaining.remove(at: existing))
            } else {
                ordered.append(OverlayWebViewState(
                    webView: makeWebView(frame: frame),
                    rect: CGRect(x: 0, y: 0, width: 1, height: 1),
                    overlayID: overlayID
                ))
            }
        }

        overlayStates = ordered
        remaining.forEach { disposeOverlay($0) }
    }

    private func makeWebView(frame: CGRect) -> WKWebView {
        let config = WKWebViewConfiguration()
        if #available(iOS 14.0, *) {
            let pagePrefs = WKWebpagePreferences()
            pagePrefs.allowsContentJavaScript = true
            config.defaultWebpagePreferences = pagePrefs
        } else {
            config.preferences.javaScriptEnabled = true
        }
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []

        let webView = WKWebView(frame: frame, configuration: config)
        webView.navigationDelegate = self
        webView.isOpaque = !isTransparent
        webView.backgroundColor = isTransparent ? .clear : .white
        webView.scrollView.backgroundColor = isTransparent ? .clear : .white
        webView.scrollView.isScrollEnabled = false
        if #available(iOS 11.0, *) {
            webView.scrollView.contentInsetAdjustmentBehavior = .never
        }
        webView.isUserInteractionEnabled = false
        webView.clipsToBounds = true

        #if DEBUG
        if #available(iOS 16.4, *) {
            webView.isInspectable = true
        }
        #endif

        return webView
    }

    private func parseOverlayConfigs(from config: [String: Any]) -> [[String: Any]] {
        if let overlays = config["overlays"] as? [[String: Any]], !overlays.isEmpty {
            return overlays
        }

        if let overlays = config["overlays"] as? [Any] {
            return overlays.compactMap { $0 as? [String: Any] }
        }

        if config["url"] != nil {
            return [config]
        }

        return []
    }

    private func applyOverlayConfig(_ config: [String: Any], to state: OverlayWebViewState) {
        let x = (config["x"] as? NSNumber)?.doubleValue ?? 0
        let y = (config["y"] as? NSNumber)?.doubleValue ?? 0
        let width = (config["width"] as? NSNumber)?.doubleValue ?? 1
        let height = (config["height"] as? NSNumber)?.doubleValue ?? 1
        state.rect = CGRect(x: x, y: y, width: width, height: height)

        let viewportWidth = (config["viewportWidth"] as? NSNumber)?.doubleValue
        let viewportHeight = (config["viewportHeight"] as? NSNumber)?.doubleValue
        if let viewportWidth, let viewportHeight, viewportWidth > 0, viewportHeight > 0 {
            state.viewportSize = CGSize(width: viewportWidth, height: viewportHeight)
        } else {
            state.viewportSize = nil
        }

        if let transparent = config["transparent"] as? Bool {
            state.webView.isOpaque = !transparent
            state.webView.backgroundColor = transparent ? .clear : .white
            state.webView.scrollView.backgroundColor = transparent ? .clear : .white
        }

        if let jsEnabled = config["javaScriptEnabled"] as? Bool {
            if #available(iOS 14.0, *) {
                state.webView.configuration.defaultWebpagePreferences.allowsContentJavaScript = jsEnabled
            } else {
                state.webView.configuration.preferences.javaScriptEnabled = jsEnabled
            }
        }

        if let zoom = (config["zoom"] as? NSNumber)?.doubleValue {
            state.zoom = CGFloat(max(0.1, min(5.0, zoom)))
            applyZoom(to: state, scheduleFollowUps: false)
        }

        if let injectedJS = config["injectedJavaScript"] as? String {
            state.webView.evaluateJavaScript(injectedJS, completionHandler: nil)
        }

        var shouldReload = false
        if let revision = (config["reloadRevision"] as? NSNumber)?.doubleValue,
           revision.isFinite, revision >= 0, revision <= 9_007_199_254_740_991,
           revision.rounded(.towardZero) == revision {
            if let previous = state.reloadRevision {
                shouldReload = revision > previous
                state.reloadRevision = max(previous, revision)
            } else {
                state.reloadRevision = revision
            }
        }

        if let urlString = config["url"] as? String,
           URL(string: urlString) != state.currentUrl || state.currentUrl == nil {
            loadUrl(urlString, into: state)
            return
        }

        if shouldReload, state.currentUrl != nil {
            state.webView.reload()
        }
    }

    private func loadUrl(_ urlString: String, into state: OverlayWebViewState) {
        guard let url = URL(string: urlString) else {
            print("[WebOverlayService] Invalid URL")
            delegate?.webOverlayService(self, didFailWithError: "Invalid URL")
            return
        }

        state.currentUrl = url
        print("[WebOverlayService] Loading URL")
        state.webView.load(URLRequest(url: url))
        delegate?.webOverlayServiceDidStartLoad(self)
    }

    private func applyZoom(to state: OverlayWebViewState, scheduleFollowUps: Bool) {
        let apply = { [weak state] in
            guard let state = state else { return }
            state.webView.pageZoom = state.zoom
        }

        state.cancelPendingZoomApplications()
        apply()

        guard scheduleFollowUps else { return }

        [0.05, 0.15, 0.35].forEach { delay in
            let item = DispatchWorkItem(block: apply)
            state.pendingZoomApplications.append(item)
            DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
        }
    }

    private func performSnapshots() {
        guard Thread.isMainThread, isCaptureRunning, !overlayStates.isEmpty else { return }
        let count = overlayStates.count
        let generation = captureGeneration
        for state in overlayStates {
            guard isCaptureRunning, captureGeneration == generation else { return }
            if state.snapshotPending || state.webView.isLoading { continue }
            let bounds = state.webView.bounds
            guard bounds.width.isFinite, bounds.height.isFinite,
                  bounds.width > 0, bounds.height > 0 else { continue }
            // snapshotWidth is in points; WKWebView multiplies it by display scale.
            // Bound raster pixels without changing the browser's CSS viewport or zoom.
            let displayScale = max(1, state.webView.window?.screen.scale ?? UIScreen.main.scale)
            let pixelBudget = min(2_097_152.0, 4_194_304.0 / Double(count))
            let pixelWidth = min(bounds.width * displayScale,
                                 floor(sqrt(pixelBudget * Double(bounds.width / bounds.height))))
            state.snapshotConfiguration.snapshotWidth = NSNumber(value: max(1, pixelWidth) / Double(displayScale))
            state.snapshotPending = true

            state.webView.takeSnapshot(with: state.snapshotConfiguration) { [weak self, weak state] image, error in
                guard let state = state else { return }
                state.snapshotPending = false
                guard let self = self, self.isCaptureRunning,
                      self.captureGeneration == generation,
                      let index = self.overlayStates.firstIndex(where: { $0 === state }) else { return }
                if let error = error {
                    print("[WebOverlayService] Snapshot error")
                    return
                }
                if let image = image {
                    state.snapshot = image
                    self.publishRenderFrames()
                    self.delegate?.webOverlayService(self, didCaptureFrame: image, index: index)
                }
            }
        }
    }
}

// MARK: - WKNavigationDelegate

extension WebOverlayService: WKNavigationDelegate {

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        print("[WebOverlayService] Page loaded")
        if let state = overlayStates.first(where: { $0.webView === webView }) {
            applyZoom(to: state, scheduleFollowUps: true)
        }
        delegate?.webOverlayServiceDidFinishLoad(self)
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        print("[WebOverlayService] Navigation failed")
        delegate?.webOverlayService(self, didFailWithError: error.localizedDescription)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        print("[WebOverlayService] Provisional navigation failed")
        delegate?.webOverlayService(self, didFailWithError: error.localizedDescription)
    }
}

// MARK: - Delegate Protocol

protocol WebOverlayServiceDelegate: AnyObject {
    func webOverlayServiceDidStartLoad(_ service: WebOverlayService)
    func webOverlayServiceDidFinishLoad(_ service: WebOverlayService)
    func webOverlayService(_ service: WebOverlayService, didFailWithError error: String)
    func webOverlayService(_ service: WebOverlayService, didCaptureFrame image: UIImage, index: Int)
}

extension WebOverlayServiceDelegate {
    func webOverlayServiceDidStartLoad(_ service: WebOverlayService) {}
    func webOverlayServiceDidFinishLoad(_ service: WebOverlayService) {}
    func webOverlayService(_ service: WebOverlayService, didFailWithError error: String) {}
    func webOverlayService(_ service: WebOverlayService, didCaptureFrame image: UIImage, index: Int) {}
}
