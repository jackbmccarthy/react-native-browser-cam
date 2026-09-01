# Project Overview

`react-native-browser-cam` is a React Native native module for putting live browser
content on top of a camera feed, then sending the composited result to RTMP or
recording it locally.

The simplest mental model is:

```text
React Native screen
  -> BrowserCamView
  -> native camera frames + native WebView snapshots
  -> native compositor
  -> preview, RTMP stream, or local MP4
```

The library now has device-tested iOS and Android capture paths behind the same
React Native API. Platform implementations differ internally, but both can preview,
compose browser overlays, publish RTMP, and record local MP4 output.

## What This Library Is For

Use this library when an app needs a live camera source plus dynamic web-based
overlays in the same video output. Common examples include live shopping graphics,
scoreboards, alerts, browser-hosted layouts, or creator tools that already render
their overlay UI as web content.

This is not a general WebView package, and it is not a replacement for Expo Camera.
It ships custom native code, so Expo Go cannot run it. Expo apps need a development
build or another native build.

## Current Status

| Area | Status |
| --- | --- |
| React Native API | `BrowserCamView` component and TypeScript types exist |
| Expo | Config plugin adds camera, microphone, photo, and network permissions |
| iOS camera preview | Implemented with `AVCaptureSession` and `AVCaptureVideoPreviewLayer` |
| iOS browser overlay | Implemented with one or more `WKWebView` instances |
| iOS compositing | Implemented with Core Image, using Metal-backed rendering when available |
| iOS RTMP streaming | Implemented with HaishinKit and sample-buffer ingest |
| iOS local recording | Implemented with `AVAssetWriter`; recordings are written to a temp MP4 and then saved to Photos when authorized |
| Android camera preview | Implemented with CameraX and `PreviewView` |
| Android browser overlay | Implemented with native WebViews and configurable viewport/zoom |
| Android RTMP streaming | Implemented with RootEncoder, microphone audio, stats, and reconnect support |
| Android local recording | Implemented with MediaCodec/MediaMuxer, composited overlays, and AAC audio |
| Web | Not supported |

## Public API At A Glance

The JavaScript entry point is [src/index.tsx](src/index.tsx). It exports:

- `BrowserCamView`: native React component
- `BrowserCameraView`: backward-compatible alias
- `BrowserCamRef`: imperative control interface
- config and event types from [src/types.ts](src/types.ts)

The main props are:

- `cameraConfig`: camera facing, frame rate, enabled state, and orientation
- `webViewConfig`: overlay URL, transparency, injected JavaScript, throttle, layout, and optional multiple overlays
- `streamConfig`: RTMP URL, output dimensions, video bitrate, and audio settings
- `recordingConfig`: local recording output settings

The main ref methods are:

- `startStreaming()` / `stopStreaming()`
- `startRecording()` / `stopRecording()`
- `snapshot()`
- `switchCamera()`
- `focusAtPoint(x, y)`
- `reloadWebView()` / `loadUrl(url)` / `injectJavaScript(js)`
- `setMuted(muted)`
- `getStreamState()` / `getStreamStats()`

Important bridge note: the native iOS implementation contains methods that return
snapshot, recording, state, and stats values, but the current JavaScript wrapper
dispatches most calls as view-manager commands. That means some result-returning
ref methods still return placeholders from JS until the bridge is tightened.

## Runtime Flow On iOS

1. A React Native screen renders `BrowserCamView`.
2. [ios/BrowserCameraView.swift](ios/BrowserCameraView.swift) creates four services:
   `CameraService`, `WebOverlayService`, `CompositorService`, and `StreamService`.
3. `CameraService` requests camera and microphone permission, starts an
   `AVCaptureSession`, and emits video and audio sample buffers.
4. `WebOverlayService` owns transparent `WKWebView` overlays. It snapshots them on
   a timer controlled by `webViewConfig.updateThrottleMs`.
5. `CompositorService` scales/crops the camera frame, places overlay snapshots using
   their normalized rectangles, and emits a composited `CMSampleBuffer`.
6. `StreamService` either queues sample buffers into HaishinKit for RTMP or writes
   them to an MP4 through `AVAssetWriter`.

The visible preview and the streamed/recorded output are related but not identical:
the preview layer shows the native camera feed with live WebViews visually layered
above it, while streaming and recording use the compositor output.

## Runtime Flow On Android

The Android package mirrors the iOS public surface with platform-specific services:

- [android/src/main/java/com/webcamstream/CameraService.kt](android/src/main/java/com/webcamstream/CameraService.kt) binds CameraX preview and applies rotation/focus configuration.
- [android/src/main/java/com/webcamstream/WebOverlayService.kt](android/src/main/java/com/webcamstream/WebOverlayService.kt) manages native browser overlays without reloading them for layout or zoom changes.
- [android/src/main/java/com/webcamstream/RtmpClient.kt](android/src/main/java/com/webcamstream/RtmpClient.kt) publishes the composed production canvas with RootEncoder.
- [android/src/main/java/com/webcamstream/LocalRecorder.kt](android/src/main/java/com/webcamstream/LocalRecorder.kt) writes composited H.264/AAC MP4 recordings.
- [android/src/main/java/com/webcamstream/AudioLevelMonitor.kt](android/src/main/java/com/webcamstream/AudioLevelMonitor.kt) supplies microphone signal events outside active encoders.

Android has been validated on physical hardware. Add device coverage before a
production release because encoder, camera, and orientation behavior can differ by OEM.

## Repository Map

| Path | Purpose |
| --- | --- |
| [src/index.tsx](src/index.tsx) | React Native component wrapper and exports |
| [src/types.ts](src/types.ts) | Public TypeScript API types |
| [ios/](ios) | iOS native implementation |
| [android/](android) | Android native camera, browser, RTMP, and recording implementation |
| [app.plugin.js](app.plugin.js) | Expo config plugin for native permissions |
| [react-native-browser-cam.podspec](react-native-browser-cam.podspec) | CocoaPods metadata and iOS dependencies |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Native architecture and service responsibilities |
| [DEPENDENCIES.md](DEPENDENCIES.md) | Notes on native streaming dependencies |
| [README.md](README.md) | Consumer-facing install and usage guide |

## Development Workflow

For library code:

```bash
npm install
npm run build
```

For an Expo consumer app:

```bash
npm install react-native-browser-cam
npx expo prebuild
npx expo run:ios
```

End-to-end testing needs physical iOS and Android hardware because simulators do
not provide the same camera, microphone, encoder, and orientation paths. Expo Go
is not enough because this package contains custom native code.

## Where To Start Changing Things

- Public prop or ref shape: start in [src/types.ts](src/types.ts), then update
  [src/index.tsx](src/index.tsx) and both native managers.
- iOS camera behavior: start in [ios/CameraService.swift](ios/CameraService.swift).
- iOS overlay behavior: start in [ios/WebOverlayService.swift](ios/WebOverlayService.swift).
- iOS composition, orientation, output sizing: start in
  [ios/CompositorService.swift](ios/CompositorService.swift).
- iOS RTMP or recording: start in [ios/StreamService.swift](ios/StreamService.swift).
- Expo permissions: start in [app.plugin.js](app.plugin.js).
- Android camera behavior: start in `CameraService.kt` and `BrowserCameraView.kt`.
- Android streaming/recording: start in `RtmpClient.kt` and `LocalRecorder.kt`.

## Known Limitations

- Expo Go is not supported.
- WebView overlays are snapshot-based for compositing, so complex or fast-changing
  web content can be expensive.
- WebView audio is not mixed into the stream; microphone audio is the practical
  V1 path on both platforms.
- Some JavaScript ref methods still need promise-based native return plumbing.
- The iOS simulator is useful for layout and bridge checks, but not for full
  camera/stream validation.

## Glossary

- RTMP: streaming protocol used by services such as YouTube Live.
- Overlay: browser-rendered content composited on top of the camera frame.
- Sample buffer: native video or audio frame container used by AVFoundation.
- Compositor: native code that merges camera pixels with overlay pixels.
- Development build: an Expo build that includes custom native modules.
