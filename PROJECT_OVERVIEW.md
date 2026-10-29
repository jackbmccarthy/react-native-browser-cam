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

Both native pipelines are implemented. The collected Android and iOS sources still
need native build and physical-device qualification before a release; portable
JavaScript tests are not evidence that those native paths work on a device.

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
| Android | CameraX, optional GPU browser/camera composition, RootEncoder RTMP, and local MP4/audio recording are implemented |
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
- `configureAndStartStreaming(config, sessionId)` / `stopStreamingAndWait(sessionId)`
- `startRecording()` / `stopRecording()`
- `snapshot()`
- `switchCamera()`
- `reloadWebView()` / `loadUrl(url)` / `injectJavaScript(js)`
- `setMuted(muted)`
- `getStreamState()` / `getStreamStats()`

Hardware device-ID/input selection and tap-to-focus are currently iOS-only.
Android uses facing selection and the default microphone; device utility results
there are fallback descriptors rather than physical hardware enumeration.

Important bridge note: the native iOS implementation contains methods that return
snapshot, recording, state, and stats values. The managed streaming methods wait
for matching native session/command results; `stopRecording` waits for its native
completion event. `snapshot()` and `getStreamStats()` still return JS placeholders.

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

CameraX supplies capture. The opt-in API 31+ path combines camera and browser
surfaces in `GpuSceneRenderer`, with `GpuBrowserOverlayService` hosting hardware
WebViews. It feeds preview and encoder surfaces without a per-frame JavaScript
image transfer. Legacy software browser capture remains available.

`RtmpClient` integrates RootEncoder for RTMP and local recording, microphone
samples, managed command acknowledgments, and native save completion. GPU mode
does not move audio encoding, networking, or all WebView work onto the GPU.

## Repository Map

| Path | Purpose |
| --- | --- |
| [src/index.tsx](src/index.tsx) | React Native component wrapper and exports |
| [src/types.ts](src/types.ts) | Public TypeScript API types |
| [ios/](ios) | iOS native implementation |
| [android/](android) | CameraX, browser rendering, GPU composition, RTMP, and recording |
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
- Android implementation: start in `CameraService.kt`, `GpuSceneRenderer.kt`,
  `GpuBrowserOverlayService.kt`, and `RtmpClient.kt`.

## Known Limitations

- The exact contributed snapshot still needs Android/iOS native builds and device tests.
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
