//
//  BrowserCameraModule.swift
//  react-native-web-cam-stream
//
//  React Native module for non-view camera utilities.
//

import Foundation
import AVFoundation
import React

@objc(BrowserCameraModule)
class BrowserCameraModule: NSObject {

    @objc static func requiresMainQueueSetup() -> Bool {
        return false
    }

    @objc func getDocumentDirectory(
        _ resolve: RCTPromiseResolveBlock,
        rejecter reject: RCTPromiseRejectBlock
    ) {
        let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
        resolve(directory?.absoluteString ?? "")
    }

    @objc func listRecordings(
        _ resolve: RCTPromiseResolveBlock,
        rejecter reject: RCTPromiseRejectBlock
    ) {
        do {
            guard let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else {
                resolve([])
                return
            }

            let files = try FileManager.default.contentsOfDirectory(
                at: directory,
                includingPropertiesForKeys: [.contentModificationDateKey],
                options: [.skipsHiddenFiles]
            )
            let recordings = files
                .filter { $0.pathExtension.lowercased() == "mp4" }
                .sorted { lhs, rhs in
                    let lhsDate = (try? lhs.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                    let rhsDate = (try? rhs.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                    return lhsDate > rhsDate
                }
                .map { $0.absoluteString }

            resolve(recordings)
        } catch {
            reject("E_LIST_FAILED", "Unable to list recordings", error)
        }
    }

    @objc func deleteRecording(
        _ uri: String,
        resolver resolve: RCTPromiseResolveBlock,
        rejecter reject: RCTPromiseRejectBlock
    ) {
        guard let url = URL(string: uri) else {
            resolve(false)
            return
        }

        do {
            if FileManager.default.fileExists(atPath: url.path) {
                try FileManager.default.removeItem(at: url)
                resolve(true)
            } else {
                resolve(false)
            }
        } catch {
            reject("E_DELETE_FAILED", "Unable to delete recording", error)
        }
    }

    @objc func getAvailableCameras(
        _ resolve: RCTPromiseResolveBlock,
        rejecter reject: RCTPromiseRejectBlock
    ) {
        let cameras = CameraService.availableCameraDevices().enumerated().map { index, device in
            let deviceId = nonEmptyString(device.uniqueID, fallback: "camera-\(index)")
            let deviceLabel = nonEmptyString(cameraLabel(for: device), fallback: "Camera \(index + 1)")
            let position = nonEmptyString(cameraPosition(for: device.position), fallback: "unspecified")
            let deviceType = nonEmptyString(device.deviceType.rawValue, fallback: "unknown")

            return [
                "id": deviceId,
                "label": deviceLabel,
                "position": position,
                "deviceType": deviceType,
                "supportsAutoFocus": device.isFocusModeSupported(.continuousAutoFocus) || device.isFocusModeSupported(.autoFocus),
                "supportsFocusPoint": device.isFocusPointOfInterestSupported
            ] as [String: Any]
        }

        resolve(cameras)
    }

    @objc func getAvailableAudioInputs(
        _ resolve: RCTPromiseResolveBlock,
        rejecter reject: RCTPromiseRejectBlock
    ) {
        let session = AVAudioSession.sharedInstance()

        do {
            try session.setCategory(.playAndRecord, mode: .videoRecording, options: [.defaultToSpeaker, .allowBluetoothHFP])
            try session.setActive(true)
        } catch {
            print("[BrowserCameraModule] Unable to activate audio session for input enumeration: \(error)")
        }

        let selectedUID = nonEmptyString(session.preferredInput?.uid ?? session.currentRoute.inputs.first?.uid, fallback: "")
        let inputs = (session.availableInputs ?? []).enumerated().map { index, input in
            let portType = nonEmptyString(input.portType.rawValue, fallback: "unknown")
            let inputId = nonEmptyString(input.uid, fallback: "\(portType)-\(index)")
            let inputLabel = nonEmptyString(input.portName, fallback: "Microphone \(index + 1)")

            return [
                "id": inputId,
                "label": inputLabel,
                "portType": portType,
                "isSelected": inputId == selectedUID
            ] as [String: Any]
        }

        if inputs.isEmpty {
            resolve([[
                "id": "default",
                "label": "Default Microphone",
                "portType": "default",
                "isSelected": true
            ]])
            return
        }

        resolve(inputs)
    }

    private func cameraPosition(for position: AVCaptureDevice.Position) -> String {
        switch position {
        case .front:
            return "front"
        case .back:
            return "back"
        default:
            return "unspecified"
        }
    }

    private func cameraLabel(for device: AVCaptureDevice) -> String {
        let position = cameraPosition(for: device.position)
        let prefix = position == "front" ? "Front" : position == "back" ? "Back" : "Camera"
        let typeLabel: String

        switch device.deviceType {
        case .builtInUltraWideCamera:
            typeLabel = "Ultra Wide"
        case .builtInWideAngleCamera:
            typeLabel = "Wide"
        case .builtInTelephotoCamera:
            typeLabel = "Telephoto"
        case .builtInDualCamera:
            typeLabel = "Dual"
        case .builtInDualWideCamera:
            typeLabel = "Dual Wide"
        case .builtInTripleCamera:
            typeLabel = "Triple"
        case .builtInTrueDepthCamera:
            typeLabel = "TrueDepth"
        default:
            typeLabel = device.localizedName
        }

        return "\(prefix) \(typeLabel)"
    }

    private func nonEmptyString(_ value: String?, fallback: String) -> String {
        guard let value else {
            return fallback
        }

        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return fallback
        }

        return trimmed
    }
}
