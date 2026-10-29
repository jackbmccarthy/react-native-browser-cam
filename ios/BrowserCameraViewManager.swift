//
//  BrowserCameraViewManager.swift
//  react-native-web-cam-stream
//
//  React Native View Manager (Swift)
//

import Foundation
import React

@objc(BrowserCameraViewManager)
class BrowserCameraViewManager: RCTViewManager {
    
    override func view() -> UIView! {
        return BrowserCameraView()
    }
    
    // MARK: - View Tag Helper
    
    private func getViewFromTag(_ tag: NSNumber) -> BrowserCameraView? {
        return self.bridge.uiManager.view(forReactTag: tag) as? BrowserCameraView
    }
    
    // MARK: - Exposed Methods
    
    @objc func configureAndStartStreaming(_ tag: NSNumber, config: NSDictionary, sessionId: String, commandId: String) {
        DispatchQueue.main.async { [weak self] in
            self?.getViewFromTag(tag)?.configureAndStartStreaming(config as? [String: Any] ?? [:], sessionId: sessionId, commandId: commandId)
        }
    }

    @objc func stopStreamingAndWait(_ tag: NSNumber, sessionId: String, commandId: String) {
        DispatchQueue.main.async { [weak self] in
            self?.getViewFromTag(tag)?.stopStreamingAndWait(sessionId, commandId: commandId)
        }
    }

    @objc func startStreaming(_ tag: NSNumber) {
        DispatchQueue.main.async { [weak self] in
            self?.getViewFromTag(tag)?.startStreaming()
        }
    }
    
    @objc func stopStreaming(_ tag: NSNumber) {
        DispatchQueue.main.async { [weak self] in
            self?.getViewFromTag(tag)?.stopStreaming()
        }
    }
    
    @objc func startRecording(_ tag: NSNumber) {
        DispatchQueue.main.async { [weak self] in
            self?.getViewFromTag(tag)?.startRecording()
        }
    }
    
    @objc func stopRecording(_ tag: NSNumber) {
        DispatchQueue.main.async { [weak self] in
            _ = self?.getViewFromTag(tag)?.stopRecording()
        }
    }
    
    @objc func takeSnapshot(_ tag: NSNumber, options: NSDictionary) {
        DispatchQueue.main.async { [weak self] in
            guard let view = self?.getViewFromTag(tag) else {
                return
            }
            _ = view.takeSnapshot(options as? [String: Any] ?? [:])
        }
    }
    
    @objc func reloadWebView(_ tag: NSNumber) {
        DispatchQueue.main.async { [weak self] in
            self?.getViewFromTag(tag)?.reloadWebView()
        }
    }
    
    @objc func loadUrl(_ tag: NSNumber, url: String) {
        DispatchQueue.main.async { [weak self] in
            self?.getViewFromTag(tag)?.loadUrl(url)
        }
    }
    
    @objc func injectJavaScript(_ tag: NSNumber, js: String) {
        DispatchQueue.main.async { [weak self] in
            self?.getViewFromTag(tag)?.injectJavaScript(js)
        }
    }
    
    @objc func switchCamera(_ tag: NSNumber) {
        DispatchQueue.main.async { [weak self] in
            self?.getViewFromTag(tag)?.switchCamera()
        }
    }

    @objc func focusAtPoint(_ tag: NSNumber, x: NSNumber, y: NSNumber) {
        DispatchQueue.main.async { [weak self] in
            self?.getViewFromTag(tag)?.focusAtPoint(x, y: y)
        }
    }
    
    @objc func setMuted(_ tag: NSNumber, muted: Bool) {
        DispatchQueue.main.async { [weak self] in
            self?.getViewFromTag(tag)?.setMuted(muted)
        }
    }
    
    @objc func getStreamState(_ tag: NSNumber, resolver resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
        DispatchQueue.main.async { [weak self] in
            guard let view = self?.getViewFromTag(tag) else {
                reject("VIEW_NOT_FOUND", "View not found", nil)
                return
            }
            resolve(view.getStreamState())
        }
    }
    
    @objc func getStreamStats(_ tag: NSNumber, resolver resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
        DispatchQueue.main.async { [weak self] in
            guard let view = self?.getViewFromTag(tag) else {
                reject("VIEW_NOT_FOUND", "View not found", nil)
                return
            }
            resolve(view.getStreamStats())
        }
    }
    
    // MARK: - React Native Bridge
    
    override class func requiresMainQueueSetup() -> Bool {
        return true
    }
}
