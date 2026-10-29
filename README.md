# React Native Browser Cam

React Native library for compositing a live WebView overlay on top of a camera feed, then streaming the result to RTMP or recording it locally.

## Start Here

New to the project? Read [PROJECT_OVERVIEW.md](PROJECT_OVERVIEW.md) first for the
mental model, current platform status, repository map, and known limitations.
Then use [ARCHITECTURE.md](ARCHITECTURE.md) for native implementation details and
[DEPENDENCIES.md](DEPENDENCIES.md) for native streaming dependency notes.

## Current Status

| Platform | Status |
| --- | --- |
| iOS | Capture, overlays, RTMP, and recording implemented; this contribution still needs native rebuild/device qualification |
| Android | CameraX, GPU composition, RTMP, audio, and recording implemented; this contribution still needs native rebuild/device qualification |
| Expo | Supported through config plugin + development builds |
| Expo Go | Not supported |
| Web | Not supported |

## What It Does

- Composites `WKWebView` / native web overlay content over live camera video
- Streams the composited output to RTMP endpoints such as YouTube
- Records the composited output with microphone audio on iOS and Android
- Exposes a single `BrowserCamView` component for React Native and Expo apps
- Provides session-correlated streaming start/stop acknowledgments and native audio-level events

Hardware camera IDs, input-device selection, and tap-to-focus are currently iOS-only.
Android uses facing selection, its default microphone, and fallback device descriptors.

See [the contribution notes](docs/CONTRIBUTION_NOTES.md) for the collected changes,
verification boundaries, and intentionally excluded app-specific capture protocols.

## Expo Integration

This package works in Expo projects, but it is a custom native module. That means:

- use an Expo development build, not Expo Go
- run `expo prebuild` before native builds
- rebuild the app after installing or changing the library

Example `app.json`:

```json
{
  "expo": {
    "plugins": ["react-native-browser-cam"]
  }
}
```

Install and run:

```bash
npm install react-native-browser-cam
npx expo prebuild
npx expo run:ios
```

## Device Notes

- iOS Simulator does not provide a real camera capture path for this library, so end-to-end camera/stream testing still needs a physical iPhone or iPad.
- Expo Camera can work in Expo Go because it is part of the Expo SDK. This library cannot, because it ships custom native code.
- Android's GPU path is opt-in on supported devices; the legacy renderer remains available. Neither a passing JavaScript test nor a successful TypeScript build qualifies native capture, streaming, orientation, or recording on a device.

## React Native CLI

```bash
npm install react-native-browser-cam
cd ios && pod install
```

## Basic Usage

```tsx
import React, { useRef, useState } from 'react';
import { View, StyleSheet, TouchableOpacity, Text } from 'react-native';
import {
  BrowserCamView,
  BrowserCamRef,
  CameraConfig,
  StreamConfig,
  WebViewConfig,
} from 'react-native-browser-cam';

type LiveComposerProps = {
  cameraConfig: CameraConfig;
  webViewConfig: WebViewConfig;
  streamConfig: StreamConfig;
};

export function LiveComposer({
  cameraConfig,
  webViewConfig,
  streamConfig,
}: LiveComposerProps) {
  const cameraRef = useRef<BrowserCamRef>(null);
  const [isStreaming, setIsStreaming] = useState(false);

  return (
    <View style={styles.container}>
      <BrowserCamView
        ref={cameraRef}
        style={styles.cameraView}
        cameraConfig={cameraConfig}
        webViewConfig={webViewConfig}
        streamConfig={streamConfig}
        onStreamStateChange={(state) => console.log('stream', state)}
        onError={(error) => console.error(error)}
      />

      <TouchableOpacity
        style={[styles.button, isStreaming && styles.buttonActive]}
        onPress={async () => {
          if (isStreaming) {
            await cameraRef.current?.stopStreaming();
            setIsStreaming(false);
          } else {
            await cameraRef.current?.startStreaming();
            setIsStreaming(true);
          }
        }}
      >
        <Text style={styles.buttonText}>{isStreaming ? 'Stop' : 'Go Live'}</Text>
      </TouchableOpacity>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: 'black' },
  cameraView: { flex: 1 },
  button: {
    position: 'absolute',
    bottom: 32,
    alignSelf: 'center',
    backgroundColor: '#333',
    paddingVertical: 14,
    paddingHorizontal: 24,
    borderRadius: 999,
  },
  buttonActive: {
    backgroundColor: '#d22',
  },
  buttonText: {
    color: 'white',
    fontWeight: '600',
  },
});
```

## Example: Passing Config From Your Screen

```tsx
import React from 'react';
import { LiveComposer } from './LiveComposer';

export default function StreamScreen() {
  const overlayUrl = process.env.EXPO_PUBLIC_OVERLAY_URL ?? '';
  const streamUrl = process.env.EXPO_PUBLIC_RTMP_URL ?? '';

  return (
    <LiveComposer
      cameraConfig={{
        facing: 'back',
        fps: 30,
        orientation: 'portrait',
      }}
      webViewConfig={{
        url: overlayUrl,
        transparent: true,
        x: 0,
        y: 0,
        width: 1,
        height: 1,
        zoom: 1,
      }}
      streamConfig={{
        url: streamUrl,
        width: 1080,
        height: 1920,
        bitrate: 4_500_000,
        audioBitrate: 128_000,
        audioSampleRate: 44_100,
        keyFrameInterval: 2,
      }}
    />
  );
}
```

## Example: Reusable Wrapper Component

```tsx
import React, { useRef } from 'react';
import { View } from 'react-native';
import {
  BrowserCamView,
  BrowserCamRef,
  BrowserCamViewProps,
} from 'react-native-browser-cam';

type CameraComposerProps = BrowserCamViewProps & {
  autoStart?: boolean;
};

export function CameraComposer({
  autoStart = false,
  cameraConfig,
  webViewConfig,
  streamConfig,
  recordingConfig,
  ...rest
}: CameraComposerProps) {
  const ref = useRef<BrowserCamRef>(null);

  return (
    <View style={{ flex: 1 }}>
      <BrowserCamView
        ref={ref}
        style={{ flex: 1 }}
        cameraConfig={cameraConfig}
        webViewConfig={webViewConfig}
        streamConfig={streamConfig}
        recordingConfig={recordingConfig}
        {...rest}
      />
    </View>
  );
}
```

## Key Props

- `cameraConfig`: camera facing, frame rate, enabled state, orientation
- `webViewConfig`: overlay URL, transparency, injected JS, layout rectangle
- `streamConfig`: RTMP URL, dimensions, video bitrate, audio bitrate
- `recordingConfig`: output settings for local recording

All runtime values should be passed in through these config props. Avoid hard-coding stream URLs, stream keys, or overlay URLs inside your shared component.

## Ref Methods

- `startStreaming()`
- `stopStreaming()`
- `startRecording()`
- `stopRecording()`
- `snapshot()`
- `switchCamera()`
- `reloadWebView()`
- `loadUrl(url)`
- `injectJavaScript(js)`
- `setMuted(muted)`

Some result-returning methods still need tighter native-to-JS promise plumbing.
`snapshot()` and `getStreamStats()` still return JavaScript placeholders.
`stopRecording()` instead waits for a nonempty native completion path and rejects
on failure, unmount, or timeout. A local file path is not a Photos/gallery-save receipt.

## Permissions

The config plugin adds these permissions automatically:

- iOS: camera, microphone, photo-library add access
- Android: camera, microphone, internet, network state

Android hosts must also obtain camera and microphone runtime permissions before
mounting the view. Recording export requirements vary by Android version.

## Release Guidance

This contribution is a review candidate, not a native-qualified release. Keep the
current package version until Android and iOS build/device checks have been repeated
against the exact merged sources. No npm release is part of this contribution.

The existing iOS 15.1 and Android API 24 floors are unchanged. Android's GPU path
requires API 31+ and uses `cameraConfig.androidRendering: 'gpu'`; older devices use
the legacy path. Native SDK dependency versions are unchanged.

## License

MIT
