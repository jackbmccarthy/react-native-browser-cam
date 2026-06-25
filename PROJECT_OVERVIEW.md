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

This project is iOS-first today. Android has the same public API shape and native
scaffolding, but the Android camera/compositor/RTMP path is not production-ready yet.

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
| Android | API and service scaffolding exist; RTMP streaming and recording are not implemented |
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

The Android package mirrors the iOS class names and public surface, but most heavy
lifting is still marked as TODO:

- [android/src/main/java/com/webcamstream/CameraService.kt](android/src/main/java/com/webcamstream/CameraService.kt) plans to use CameraX.
- [android/src/main/java/com/webcamstream/WebOverlayService.kt](android/src/main/java/com/webcamstream/WebOverlayService.kt) creates a WebView and can draw it into a bitmap.
- [android/src/main/java/com/webcamstream/GlCompositor.kt](android/src/main/java/com/webcamstream/GlCompositor.kt) contains shader and texture scaffolding.
- [android/src/main/java/com/webcamstream/RtmpClient.kt](android/src/main/java/com/webcamstream/RtmpClient.kt) intentionally reports that Android RTMP is not implemented.

Treat Android as an API scaffold and future implementation lane, not as a working
streaming platform.

## Repository Map

| Path | Purpose |
| --- | --- |
| [src/index.tsx](src/index.tsx) | React Native component wrapper and exports |
| [src/types.ts](src/types.ts) | Public TypeScript API types |
| [ios/](ios) | iOS native implementation |
| [android/](android) | Android native scaffold |
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

End-to-end iOS testing needs a physical iPhone or iPad because the simulator does
not provide the same camera capture path. Expo Go is not enough because this package
contains custom native code.

## Where To Start Changing Things

- Public prop or ref shape: start in [src/types.ts](src/types.ts), then update
  [src/index.tsx](src/index.tsx) and both native managers.
- iOS camera behavior: start in [ios/CameraService.swift](ios/CameraService.swift).
- iOS overlay behavior: start in [ios/WebOverlayService.swift](ios/WebOverlayService.swift).
- iOS composition, orientation, output sizing: start in
  [ios/CompositorService.swift](ios/CompositorService.swift).
- iOS RTMP or recording: start in [ios/StreamService.swift](ios/StreamService.swift).
- Expo permissions: start in [app.plugin.js](app.plugin.js).
- Android implementation: start by replacing the TODO scaffolding in
  `CameraService.kt`, `GlCompositor.kt`, and `RtmpClient.kt`.

## Known Limitations

- Android cannot stream or record yet.
- Expo Go is not supported.
- WebView overlays are snapshot-based for compositing, so complex or fast-changing
  web content can be expensive.
- iOS WebView audio is not mixed into the stream; microphone audio is the practical
  V1 path.
- Some JavaScript ref methods still need promise-based native return plumbing.
- The iOS simulator is useful for layout and bridge checks, but not for full
  camera/stream validation.

## Glossary

- RTMP: streaming protocol used by services such as YouTube Live.
- Overlay: browser-rendered content composited on top of the camera frame.
- Sample buffer: native video or audio frame container used by AVFoundation.
- Compositor: native code that merges camera pixels with overlay pixels.
- Development build: an Expo build that includes custom native modules.
