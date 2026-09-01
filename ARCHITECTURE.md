# Architecture

This document describes the current architecture of `react-native-browser-cam`.
For a first-time reader orientation, start with [PROJECT_OVERVIEW.md](PROJECT_OVERVIEW.md).

## Purpose

The library exposes a single React Native view, `BrowserCamView`, that combines:

- a live native camera feed
- one or more native browser overlays
- native compositing
- RTMP streaming or local MP4 recording

The implementation is intentionally native-heavy because compositing camera frames
and browser pixels in JavaScript would not be fast or reliable enough for live video.

## Platform Reality

| Platform | Architecture state |
| --- | --- |
| iOS | Primary implementation. Camera, WebView overlay capture, compositing, RTMP streaming, and local recording are implemented. |
| Android | CameraX preview, browser overlays, RTMP publishing, local recording with audio, audio levels, and React event callbacks are implemented and device-tested. |
| Expo | Supported through the config plugin and development builds. Expo Go is not supported. |
| Web | Not supported. |

## High-Level Pipeline

```text
React Native props/ref commands
          |
          v
BrowserCameraView native view
          |
          +--> CameraService ---- video CMSampleBuffer ----+
          |                                                |
          +--> WebOverlayService ---- WKWebView images ----+--> CompositorService
          |                                                |        |
          +--> visible preview layer + live WebViews       |        v
                                                           composited CMSampleBuffer
                                                                    |
                                                                    +--> StreamService -> RTMP
                                                                    |
                                                                    +--> StreamService -> MP4 recording
```

The visible iOS preview is built from an `AVCaptureVideoPreviewLayer` plus actual
`WKWebView` subviews. The stream and recording path uses WebView snapshots blended
with camera sample buffers. Android uses CameraX `PreviewView` output with native
WebViews, then captures that composed production canvas for RootEncoder RTMP and
the local MediaCodec/MediaMuxer recorder.

## React Native Layer

[src/index.tsx](src/index.tsx) defines `BrowserCamView` with `forwardRef`. The
component renders the native `BrowserCameraView` and dispatches view-manager
commands through `UIManager.dispatchViewManagerCommand`.

[src/types.ts](src/types.ts) defines the public API:

- camera configuration
- WebView overlay configuration
- RTMP stream configuration
- recording configuration
- stream state, stats, snapshot, and error types
- imperative ref methods

The component exports `BrowserCameraView` as a backward-compatible alias.

Current bridge limitation: command-style methods are easy to dispatch but do not
carry native return values back to JavaScript. The native iOS view can create
snapshot and recording paths, while the JavaScript wrapper currently returns
placeholders for some of those methods.

## iOS Native Layer

The iOS implementation is centered on [ios/BrowserCameraView.swift](ios/BrowserCameraView.swift).
It owns service instances, receives React props, handles view layout, forwards
commands, and emits React Native events.

| Service | File | Responsibility |
| --- | --- | --- |
| `CameraService` | [ios/CameraService.swift](ios/CameraService.swift) | Requests camera/microphone permission, configures `AVCaptureSession`, emits video and audio sample buffers, switches camera, applies frame rate and orientation. |
| `WebOverlayService` | [ios/WebOverlayService.swift](ios/WebOverlayService.swift) | Creates transparent `WKWebView` overlays, loads URLs, injects JavaScript, controls overlay layout, snapshots overlays on a throttle. |
| `CompositorService` | [ios/CompositorService.swift](ios/CompositorService.swift) | Uses Core Image, and Metal when available, to scale/crop camera frames and alpha-blend overlay snapshots into output sample buffers. |
| `StreamService` | [ios/StreamService.swift](ios/StreamService.swift) | Parses RTMP URLs, connects/publishes with HaishinKit, queues audio/video frames, tracks stats, mutes audio, records MP4 files through `AVAssetWriter`. |

### iOS Streaming Flow

1. `startStreaming()` starts the camera if needed.
2. The compositor and WebView snapshot loop start.
3. `StreamService.connect()` parses the RTMP URL into server URL and stream key.
4. HaishinKit creates an `RTMPConnection`, `RTMPStream`, and `MediaMixer`.
5. Once publishing is active, composited video frames and microphone audio buffers
   are appended to the mixer in timestamp order.
6. Stream state and stats are sent back through React Native direct events.

### iOS Recording Flow

1. `startRecording()` starts the camera, compositor, and WebView snapshot loop.
2. `StreamService.startRecording(to:)` creates an MP4 `AVAssetWriter` with H.264
   video and AAC audio inputs.
3. Composited video frames and audio buffers are appended while recording is active.
4. `stopRecording()` finishes the writer and returns the local file path natively.
5. `BrowserCameraView` attempts to save the MP4 to Photos if authorization allows it.

### iOS Overlay Flow

`webViewConfig` can describe a single overlay or an `overlays` array. Each overlay
uses normalized `x`, `y`, `width`, and `height` values relative to the output frame.
The same WebViews are also mounted visually over the preview layer so users see
the live overlay while composing.

For compositing, `WebOverlayService` uses `WKWebView.takeSnapshot` at the configured
`updateThrottleMs`. The default is 50 ms, or about 20 overlay updates per second.

## Android Native Layer

The Android implementation mirrors the iOS public shape and is coordinated by
`BrowserCameraView`:

| Class | File | Current state |
| --- | --- | --- |
| `BrowserCameraView` | [android/src/main/java/com/webcamstream/BrowserCameraView.kt](android/src/main/java/com/webcamstream/BrowserCameraView.kt) | Owns the preview and native services, defers output startup until the camera/overlay are ready, dispatches commands, and emits React events. |
| `CameraService` | [android/src/main/java/com/webcamstream/CameraService.kt](android/src/main/java/com/webcamstream/CameraService.kt) | Binds CameraX preview, selects hardware cameras, applies target rotation and focus behavior, and manages lifecycle. |
| `WebOverlayService` | [android/src/main/java/com/webcamstream/WebOverlayService.kt](android/src/main/java/com/webcamstream/WebOverlayService.kt) | Owns native WebViews, requested URLs, browser viewport sizing, fixed-origin document zoom, and overlay frame capture. |
| `RtmpClient` | [android/src/main/java/com/webcamstream/RtmpClient.kt](android/src/main/java/com/webcamstream/RtmpClient.kt) | Uses RootEncoder to publish the composed camera/WebView canvas, carries microphone audio, reports stats, and resets stale publishers for reconnects. |
| `LocalRecorder` | [android/src/main/java/com/webcamstream/LocalRecorder.kt](android/src/main/java/com/webcamstream/LocalRecorder.kt) | Encodes the production canvas and microphone audio with MediaCodec and writes MP4 output with MediaMuxer. |
| `AudioLevelMonitor` | [android/src/main/java/com/webcamstream/AudioLevelMonitor.kt](android/src/main/java/com/webcamstream/AudioLevelMonitor.kt) | Samples microphone input for normalized and dBFS level events when an encoder does not own the audio source. |
| `BrowserCameraViewManager` | [android/src/main/java/com/webcamstream/BrowserCameraViewManager.kt](android/src/main/java/com/webcamstream/BrowserCameraViewManager.kt) | Bridges props, commands, and native direct events. |

Android streaming and recording are working on tested hardware. Keep validating
orientation, capture resolution, autofocus, audio ownership, and reconnect behavior
across additional manufacturers because CameraX and encoder behavior vary by device.

## Expo Integration

[app.plugin.js](app.plugin.js) is the Expo config plugin. It adds:

- iOS camera, microphone, and photo-library usage descriptions
- Android camera, microphone, internet, and network-state permissions
- Android camera hardware feature metadata

Because this package contains native code, Expo consumers must use development
builds and run `expo prebuild` before native builds.

## Native Dependencies

The iOS podspec depends on:

- `React-Core`
- `HaishinKit`
- AVFoundation, Photos, VideoToolbox, CoreImage, and Metal frameworks

Dependency research and version notes live in [DEPENDENCIES.md](DEPENDENCIES.md).

## Design Constraints

- WebView content is expensive to rasterize. Keep overlays simple, throttle updates,
  and avoid unnecessary animation in browser content.
- Overlay layout uses normalized rectangles so the same config can adapt to
  different stream resolutions.
- The stream URL should be treated as a secret at the app level. Pass it at runtime;
  do not hard-code stream keys into reusable components.
- The iOS simulator cannot prove the full camera/RTMP path. Use a physical device
  for end-to-end validation.
- Android emulator output cannot prove hardware encoder, camera, microphone, or
  orientation behavior. Use physical devices for end-to-end validation.

## Known Architecture Gaps

- Promise-returning native methods need tighter JavaScript bridge plumbing.
- WebView audio is not mixed into the output; microphone audio is the current path.
- Automated tests are light for a project with this much native behavior.
