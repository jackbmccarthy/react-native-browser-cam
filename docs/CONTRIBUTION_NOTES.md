# Camera And Streaming Contribution

This branch collects reusable work from the author's integration application onto
public `main` (`4ef149020bf453d398f8430cc8120ded4ff25920`). It does not merge the old
private repository history or publish an npm version.

## Collected Work

- Android CameraX lifecycle, facing selection, and orientation; iOS native camera/audio device discovery and focus controls.
- Optional API 31+ GPU camera/browser composition, shared preview/encoder surfaces,
  and independent animated overlay cadence. The default legacy path is retained.
- CSS browser viewport dimensions independent of the overlay's destination frame;
  zoom/layout updates preserve page identity instead of forcing navigations.
- Microphone samples in local recordings, audio-level events, and recording
  timestamp/finalization handling.
- Session-correlated atomic stream configuration/start and acknowledged stop;
  canceled starts and late native callbacks cannot acknowledge another session.
- iOS capture/compositor/snapshot/stream queue and resource-lifetime improvements.
- JS/ref/type exports matching those APIs, including camera/audio device helpers.
- Portable regression tests, diagnostic-data redaction, and generic library output names.

No dependency version or platform floor is raised: iOS remains 15.1, Android
remains API 24, CameraX remains 1.4.2, RootEncoder remains 2.5.9, and HaishinKit
remains `~> 2.0`. GPU mode requires API 31+ and must be selected explicitly.

## Managed Streaming

```tsx
const sessionId = 'a-new-unique-id-for-this-start';
await ref.current?.configureAndStartStreaming({
  url: 'rtmps://your-ingest-host/live/your-runtime-key',
  width: 1280,
  height: 720,
  bitrate: 4000000,
  fps: 30,
}, sessionId);
// Later, retaining the same session ID:
await ref.current?.stopStreamingAndWait(sessionId);
```

Do not reuse a session ID. A command timeout or view unmount is not a successful
native teardown acknowledgment. Legacy `startStreaming`/`stopStreaming` remain,
but callers needing acknowledged lifecycle ordering should use the managed API.
Keep RTMP URLs and stream keys runtime-only; do not print configurations or native
error payloads containing credentials.

## Browser Geometry

```tsx
webViewConfig={{
  updateThrottleMs: 33,
  overlays: [{
    id: 'score', url: overlayURL,
    x: 0.05, y: 0.05, width: 0.6, height: 0.2,
    viewportWidth: 1920, viewportHeight: 1080,
    zoom: 0.5, visible: true,
  }],
}}
cameraConfig={{ facing: 'back', orientation: 'auto', androidRendering: 'gpu' }}
```

The viewport is the browser's CSS layout size; the normalized frame is where the
browser is composed into video. A requested 33ms interval is best-effort, not a
guaranteed frame rate. GPU mode does not eliminate CPU WebView work, audio encoding,
transport, or legacy snapshot/readback costs.

Imperative `loadUrl` targets the active renderer. In GPU mode it updates the
primary browser without creating a second legacy host or resetting its geometry.
The preview audio meter uses a generation-bound, worker-owned recorder with
`finally` cleanup. Its `stop()` requests asynchronous cleanup; it is not a native
resource-release acknowledgment or process-wide microphone-availability proof.

## Recording Results

`stopRecording()` resolves only after a nonempty native completion path. It
rejects on failure, unmount, or its existing 60-second timeout rather than
returning an earlier recording's path. A synchronous bridge-dispatch failure
also releases its own pending slot/timer so an explicit retry is possible.
A local path is not proof of Photos/gallery export or durable server storage.
If native cancels a queued recording start before capture begins, Stop rejects
promptly with `RECORDING_CANCELLED` rather than waiting for an uncertain timeout.
Android's default output names are library-branded; the integration application's
existing files/settings are not migrated or changed by this contribution.

## Deliberately Excluded

The integration app has additional terminal capture-owner release receipts and
Android process-wide quarantine/admission recovery. Copying that later Android
stage would make ordinary library views inert without an app-issued admission.
Those app-owned protocols were reversed from the collection using their exact
reviewed before/after source hashes; they are not enabled by this package.

Also excluded: photo capture/import/edit brokers, protected photo leases/originals,
studio recovery, opt-in iOS browser-media denial, account/lifecycle/server APIs,
watch controls, Firebase configuration, and app-specific UI. These are separate
integration work, not silently activated public library features.

## Verification And Remaining Gates

```sh
npm ci --ignore-scripts
npm test
npm run lint
npm run typecheck
npm run build
npm pack --dry-run --ignore-scripts
```

The Node tests use actual TypeScript controller/component sources with controlled
React/native command ports, fake timers, and static native-source guards. They do
not initialize an SDK, camera, WebView, RTMP connection, photo library, or account.

`lint` runs TypeScript checks and the native source-policy guards. It replaces
the previously unconfigured ESLint command without adding a dependency; it does
not claim general Swift/Kotlin formatting or semantic native-code analysis.

The JavaScript checks were repeated after a clean `npm ci --ignore-scripts` using
this repository's unchanged lockfile (Node 22.13.1, TypeScript 5.9.3, React 18.2.0,
React Native 0.74.1). Existing dependency deprecation warnings were not resolved
by changing versions. This is not native-platform certification. Before release, repeat:

1. A clean dependency install and the commands above using this repository's lockfile.
2. Android and iOS native builds at supported deployment targets.
3. Front/back camera orientation, microphone/mute/device selection, and local MP4
   audio/video synchronization, timing, file and gallery-save behavior.
4. Animated overlay resize/zoom/page persistence and preview/output agreement.
5. A private RTMP stream, interruptions/reconnection, repeated start/stop, teardown,
   background/foreground cycles, and sustained performance/thermal testing.

Hardware `deviceId`, audio-input choice, capture-preset selection, continuous
autofocus settings, and tap-to-focus currently apply to iOS. Android uses facing,
its default audio input, and configured stream/recording output dimensions; its
device utilities return fallback descriptors. `focusAtPoint` deliberately does
not dispatch an unsupported Android native command.

`snapshot()` and `getStreamStats()` still have JS placeholder return values. This
contribution does not claim native/device qualification, durable mutation recovery,
global permission guarantees, or a production-ready release.
