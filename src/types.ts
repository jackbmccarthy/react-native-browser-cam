/**
 * TypeScript definitions for react-native-browser-cam
 * 
 * Core types and interfaces for the browser-camera compositor and streamer.
 */

// ============================================================================
// Camera Types
// ============================================================================

export type CameraFacing = 'front' | 'back';
export type CameraResolution = '720p' | '1080p' | '4K';
export type CameraOrientation =
  | 'auto'
  | 'portrait'
  | 'portraitUpsideDown'
  | 'landscape'
  | 'landscapeLeft'
  | 'landscapeRight';

export interface CameraConfig {
  /** Camera facing direction */
  facing: CameraFacing;
  /** Native hardware camera identifier. When set, this takes priority over facing. */
  deviceId?: string;
  /** Target frame rate (default: 30) */
  fps?: number;
  /** Capture session resolution preset */
  resolution?: CameraResolution;
  /** Enable/disable camera */
  enabled?: boolean;
  /** Camera orientation. Use `auto` to follow device rotation. */
  orientation?: CameraOrientation;
  /** Preferred native audio input identifier. Empty/default uses the system default. */
  audioInputId?: string;
  /** Enable continuous autofocus. Disable to lock after tap-to-focus. */
  autoFocusEnabled?: boolean;
}

export interface CameraDeviceInfo {
  /** Native unique camera identifier */
  id: string;
  /** Human-readable camera label */
  label: string;
  /** Camera position on the device */
  position: 'front' | 'back' | 'unspecified';
  /** Native camera/device type, when available */
  deviceType?: string;
  /** Whether the camera reports autofocus support */
  supportsAutoFocus?: boolean;
  /** Whether the camera supports tap-to-focus points */
  supportsFocusPoint?: boolean;
}

export interface AudioInputDeviceInfo {
  /** Native audio input identifier */
  id: string;
  /** Human-readable microphone/input label */
  label: string;
  /** Native input port type, when available */
  portType?: string;
  /** Whether this input is currently selected by the OS session */
  isSelected?: boolean;
}

// ============================================================================
// WebView Overlay Types
// ============================================================================

export interface BrowserOverlayConfig {
  /** Stable identifier for UI state */
  id?: string;
  /** URL to load in this overlay */
  url: string;
  /** X position relative to the video frame (0-1) */
  x?: number;
  /** Y position relative to the video frame (0-1) */
  y?: number;
  /** Width relative to the video frame (0-1) */
  width?: number;
  /** Height relative to the video frame (0-1) */
  height?: number;
  /** Browser viewport width in CSS pixels before scaling into the overlay frame */
  viewportWidth?: number;
  /** Browser viewport height in CSS pixels before scaling into the overlay frame */
  viewportHeight?: number;
  /** Content zoom level */
  zoom?: number;
  /** Overlay opacity from 0-1 */
  opacity?: number;
  /** Per-overlay transparency */
  transparent?: boolean;
  /** Whether this overlay is included in the composed output */
  visible?: boolean;
}

export interface WebViewConfig {
  /** URL to load in the primary WebView overlay */
  url?: string;
  /** Enable JavaScript in WebView (default: true) */
  javaScriptEnabled?: boolean;
  /** WebView background transparency (default: true for overlays) */
  transparent?: boolean;
  /** Inject custom CSS/JS into the WebView */
  injectedJavaScript?: string;
  /** WebView update throttle in ms (default: 50ms ~ 20fps) */
  updateThrottleMs?: number;
  /** X position (0-1 or pixels, default: 0) */
  x?: number;
  /** Y position (0-1 or pixels, default: 0) */
  y?: number;
  /** Width (0-1 or pixels, default: 1.0) */
  width?: number;
  /** Height (0-1 or pixels, default: 1.0) */
  height?: number;
  /** Content zoom level (1.0 = 100%, default: 1.0) */
  zoom?: number;
  /** Multiple overlays composited in order from first to last */
  overlays?: BrowserOverlayConfig[];
}

// ============================================================================
// Streaming Types
// ============================================================================

export interface StreamConfig {
  /** RTMP server URL (e.g., rtmp://a.rtmp.youtube.com/live2/...) */
  url?: string;
  /** Stream width in pixels */
  width: number;
  /** Stream height in pixels */
  height: number;
  /** Video bitrate in bits per second (e.g., 4000000 for 4Mbps) */
  bitrate: number;
  /** Encoded output frame rate (default: 15 on Android view capture) */
  fps?: number;
  /** Audio sample rate (default: 44100) */
  audioSampleRate?: number;
  /** Audio bitrate (default: 128000) */
  audioBitrate?: number;
  /** Key frame interval in seconds (default: 2) */
  keyFrameInterval?: number;
  /** Whether to record/stream microphone audio (default: true) */
  audioEnabled?: boolean;
}

export interface RecordingConfig {
  /** Output file path */
  outputPath: string;
  /** Video width */
  width: number;
  /** Video height */
  height: number;
  /** Video bitrate */
  bitrate: number;
  /** Recording duration limit in seconds (optional) */
  maxDuration?: number;
  /** Whether to record microphone audio (default: true) */
  audioEnabled?: boolean;
}

// ============================================================================
// State & Event Types
// ============================================================================

export type StreamState = 'idle' | 'connecting' | 'streaming' | 'error' | 'disconnected';

export type RecordingState = 'idle' | 'recording' | 'paused' | 'processing' | 'error';

export interface StreamStats {
  /** Current bitrate in bps */
  bitrate: number;
  /** Frames per second */
  fps: number;
  /** Dropped frames count */
  droppedFrames: number;
  /** Stream duration in seconds */
  duration: number;
}

export interface AudioLevelEvent {
  /** True when audio sample buffers are currently reaching the native camera service */
  active: boolean;
  /** Normalized input level from 0-1. */
  level: number;
  /** Approximate dBFS input level. This is not calibrated SPL. */
  decibels?: number;
}

export interface StreamEvent {
  type: 'stateChange' | 'stats' | 'error';
  state?: StreamState;
  stats?: StreamStats;
  error?: {
    code: string;
    message: string;
  };
}

// ============================================================================
// Snapshot Types
// ============================================================================

export interface SnapshotOptions {
  /** Quality of the snapshot (0-100, default: 90) */
  quality?: number;
  /** Output format */
  format?: 'jpeg' | 'png';
  /** Include WebView overlay in snapshot (default: true) */
  includeOverlay?: boolean;
}

export interface SnapshotResult {
  /** Base64 encoded image data */
  base64: string;
  /** Image width */
  width: number;
  /** Image height */
  height: number;
  /** Image URI for display */
  uri: string;
}

// ============================================================================
// Component Props
// ============================================================================

export interface BrowserCamViewProps {
  /** React Native style */
  style?: any;
  /** Camera configuration */
  cameraConfig?: CameraConfig;
  /** WebView overlay configuration */
  webViewConfig?: WebViewConfig;
  /** RTMP streaming configuration */
  streamConfig?: StreamConfig;
  /** Recording configuration (mutually exclusive with streamConfig) */
  recordingConfig?: RecordingConfig;
  /** Called when stream state changes */
  onStreamStateChange?: (state: StreamState) => void;
  /** Called periodically with stream statistics */
  onStreamStats?: (stats: StreamStats) => void;
  /** Called periodically when native microphone buffers are flowing */
  onAudioLevel?: (event: AudioLevelEvent) => void;
  /** Called when an error occurs */
  onError?: (error: { code: string; message: string }) => void;
  /** Called when WebView finishes loading */
  onWebViewLoad?: () => void;
  /** Called when WebView encounters an error */
  onWebViewError?: (error: { code: number; description: string }) => void;
  /** Called when camera is ready */
  onCameraReady?: () => void;
  /** Called when local recording state changes */
  onRecordingStateChange?: (state: RecordingState, path?: string) => void;
  /** Called when local recording has fully stopped and the file path is available */
  onRecordingFinished?: (path: string) => void;
  /** Called when a diagnostic log is available */
  onLog?: (event: { message: string }) => void;
  /** Children to render (if any) */
  children?: React.ReactNode;
}

// ============================================================================
// Ref Methods
// ============================================================================

export interface BrowserCamRef {
  /** Start streaming to RTMP server */
  startStreaming: () => Promise<void>;
  /** Stop streaming */
  stopStreaming: () => Promise<void>;
  /** Start recording to file */
  startRecording: () => Promise<void>;
  /** Stop recording and return file path */
  stopRecording: () => Promise<string>;
  /** Take a snapshot */
  snapshot: (options?: SnapshotOptions) => Promise<SnapshotResult>;
  /** Reload WebView content */
  reloadWebView: () => void;
  /** Load a new URL in WebView */
  loadUrl: (url: string) => void;
  /** Inject JavaScript into WebView */
  injectJavaScript: (js: string) => void;
  /** Get current stream state */
  getStreamState: () => StreamState;
  /** Get current stream stats */
  getStreamStats: () => Promise<StreamStats>;
  /** Switch camera facing */
  switchCamera: () => void;
  /** Focus and expose at a point in native view coordinates or normalized capture coordinates. */
  focusAtPoint: (x: number, y: number) => void;
  /** Mute/unmute audio */
  setMuted: (muted: boolean) => void;
}

// ============================================================================
// Native Module Interface
// ============================================================================

export interface NativeBrowserCamModule {
  // Constants
  getConstants: () => {
    STATE_IDLE: StreamState;
    STATE_CONNECTING: StreamState;
    STATE_STREAMING: StreamState;
    STATE_ERROR: StreamState;
    STATE_DISCONNECTED: StreamState;
  };

  // Stream control
  startStreaming: (viewTag: number) => Promise<void>;
  stopStreaming: (viewTag: number) => Promise<void>;

  // Recording control
  startRecording: (viewTag: number) => Promise<void>;
  stopRecording: (viewTag: number) => Promise<string>;

  // Snapshot
  takeSnapshot: (viewTag: number, options: SnapshotOptions) => Promise<SnapshotResult>;

  // WebView control
  reloadWebView: (viewTag: number) => void;
  loadUrl: (viewTag: number, url: string) => void;
  injectJavaScript: (viewTag: number, js: string) => void;

  // Camera control
  switchCamera: (viewTag: number) => void;
  focusAtPoint: (viewTag: number, x: number, y: number) => void;
  getAvailableCameras?: () => Promise<CameraDeviceInfo[]>;
  getAvailableAudioInputs?: () => Promise<AudioInputDeviceInfo[]>;

  // Audio control
  setMuted: (viewTag: number, muted: boolean) => void;

  // State getters
  getStreamState: (viewTag: number) => Promise<StreamState>;
  getStreamStats: (viewTag: number) => Promise<StreamStats>;
}

// ============================================================================
// Utility Types
// ============================================================================

/** Platform-specific configuration overrides */
export interface PlatformConfig {
  ios?: {
    preferredCaptureDevice?: string;
    sessionPreset?: string;
  };
  android?: {
    cameraSelector?: 'default' | 'front' | 'back';
    textureView?: boolean;
  };
}

/** Error codes */
export enum BrowserCamErrorCode {
  CAMERA_PERMISSION_DENIED = 'CAMERA_PERMISSION_DENIED',
  MICROPHONE_PERMISSION_DENIED = 'MICROPHONE_PERMISSION_DENIED',
  CAMERA_INIT_FAILED = 'CAMERA_INIT_FAILED',
  WEBVIEW_LOAD_FAILED = 'WEBVIEW_LOAD_FAILED',
  STREAM_CONNECTION_FAILED = 'STREAM_CONNECTION_FAILED',
  STREAM_DISCONNECTED = 'STREAM_DISCONNECTED',
  ENCODER_ERROR = 'ENCODER_ERROR',
  RECORDING_FAILED = 'RECORDING_FAILED',
  SNAPSHOT_FAILED = 'SNAPSHOT_FAILED',
  INVALID_CONFIG = 'INVALID_CONFIG',
  UNKNOWN_ERROR = 'UNKNOWN_ERROR',
}

// Backward-compatible aliases for the pre-release BrowserCamera naming.
export type BrowserCameraViewProps = BrowserCamViewProps;
export type BrowserCameraRef = BrowserCamRef;
export type NativeBrowserCameraModule = NativeBrowserCamModule;
export const BrowserCameraErrorCode = BrowserCamErrorCode;
