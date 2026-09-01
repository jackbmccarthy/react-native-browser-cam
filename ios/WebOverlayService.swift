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
    var currentUrl: URL?
    var rect: CGRect
    var viewportSize: CGSize?
    var zoom: CGFloat = 1.0
    var snapshot: UIImage?
    var pendingZoomApplications: [DispatchWorkItem] = []

    init(webView: WKWebView, rect: CGRect) {
        self.webView = webView
        self.rect = rect
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
    var updateThrottleMs: Int = 50

    /// Is WebView transparent
    var isTransparent: Bool = true

    /// Delegate for WebView events
    weak var delegate: WebOverlayServiceDelegate?

    /// Snapshot timer for continuous capture
    private var snapshotTimer: DispatchSourceTimer?

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
                    print("[WebOverlayService] JS injection error: \(error)")
                }
            }
        }
    }

    /// Start continuous frame capture at the configured throttle rate
    func startCapture() {
        guard !isCaptureRunning else { return }
        isCaptureRunning = true

        let intervalMs = max(updateThrottleMs, 16)
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.main)
        timer.schedule(deadline: .now(), repeating: .milliseconds(intervalMs))
        timer.setEventHandler { [weak self] in
            self?.performSnapshots()
        }
        timer.resume()
        snapshotTimer = timer

        print("[WebOverlayService] Continuous capture started at \(1000 / intervalMs)fps")
    }

    /// Stop continuous frame capture
    func stopCapture() {
        snapshotTimer?.cancel()
        snapshotTimer = nil
        isCaptureRunning = false
        print("[WebOverlayService] Continuous capture stopped")
    }

    /// Backward-compatible capture of the first overlay
    func captureFrame() -> UIImage? {
        overlayStates.first?.snapshot
    }

    /// Capture all current overlay frames with their layout rects
    func captureFrames() -> [OverlayRenderFrame] {
        overlayStates.enumerated().compactMap { index, state in
            guard let image = state.snapshot else { return nil }
            return OverlayRenderFrame(image: image, rect: state.rect, index: index)
        }
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
            updateThrottleMs = throttleMs
            if isCaptureRunning {
                stopCapture()
                startCapture()
            }
        }

        if let transparent = config["transparent"] as? Bool {
            setTransparent(transparent)
        }

        let overlayConfigs = parseOverlayConfigs(from: config)
        let frame = webView?.superview?.bounds ?? webView?.frame ?? .zero

        if config["overlays"] != nil && overlayConfigs.isEmpty {
            ensureOverlayCount(0, frame: frame)
            return
        }

        ensureOverlayCount(max(overlayConfigs.count, 1), frame: frame)

        for (index, overlayConfig) in overlayConfigs.enumerated() {
            applyOverlayConfig(overlayConfig, to: overlayStates[index])
        }

        if overlayConfigs.isEmpty, let firstState = overlayStates.first {
            applyOverlayConfig(config, to: firstState)
        }
    }

    // MARK: - Private Methods

    private func ensureOverlayCount(_ count: Int, frame: CGRect) {
        let safeCount = max(0, count)

        if overlayStates.count > safeCount {
            let removed = Array(overlayStates.suffix(overlayStates.count - safeCount))
            overlayStates.removeLast(overlayStates.count - safeCount)
            removed.forEach {
                $0.cancelPendingZoomApplications()
                $0.webView.stopLoading()
                $0.webView.removeFromSuperview()
            }
        }

        while overlayStates.count < safeCount {
            let webView = makeWebView(frame: frame)
            overlayStates.append(OverlayWebViewState(webView: webView, rect: CGRect(x: 0, y: 0, width: 1, height: 1)))
        }
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

        if let urlString = config["url"] as? String,
           urlString != state.currentUrl?.absoluteString {
            loadUrl(urlString, into: state)
        }
    }

    private func loadUrl(_ urlString: String, into state: OverlayWebViewState) {
        guard let url = URL(string: urlString) else {
            print("[WebOverlayService] Invalid URL: \(urlString)")
            delegate?.webOverlayService(self, didFailWithError: "Invalid URL")
            return
        }

        state.currentUrl = url
        print("[WebOverlayService] Loading URL: \(urlString)")
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
        for (index, state) in overlayStates.enumerated() {
            let config = WKSnapshotConfiguration()
            config.afterScreenUpdates = false

            state.webView.takeSnapshot(with: config) { [weak self] image, error in
                guard let self = self else { return }
                if let error = error {
                    print("[WebOverlayService] Snapshot error: \(error)")
                    return
                }
                if let image = image {
                    state.snapshot = image
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
        print("[WebOverlayService] Navigation failed: \(error)")
        delegate?.webOverlayService(self, didFailWithError: error.localizedDescription)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        print("[WebOverlayService] Provisional navigation failed: \(error)")
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
