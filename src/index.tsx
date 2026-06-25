/**
 * react-native-browser-cam
 * 
 * A React Native library for compositing a live WebView over a Camera feed
 * and streaming the result via RTMP or saving to disk.
 * 
 * @packageDocumentation
 */

import React, { forwardRef, useImperativeHandle, useRef, useCallback } from 'react';
import {
  UIManager,
  findNodeHandle,
  NativeModules,
  requireNativeComponent,
  ViewProps,
} from 'react-native';
// codegenNativeComponent is for New Architecture, but since we are troubleshooting 
// we'll use requireNativeComponent which is more robust for Old Arch / Hybrid.

// Type exports
export type {
  CameraFacing,
  CameraConfig,
  WebViewConfig,
  StreamConfig,
  RecordingConfig,
  StreamState,
  RecordingState,
  StreamStats,
  StreamEvent,
  SnapshotOptions,
  SnapshotResult,
  BrowserCamViewProps,
  BrowserCamRef,
  NativeBrowserCamModule,
  BrowserCameraViewProps,
  BrowserCameraRef,
  NativeBrowserCameraModule,
  PlatformConfig,
} from './types';

export {
  BrowserCamErrorCode,
  BrowserCameraErrorCode,
} from './types';

import type {
  BrowserCamViewProps,
  BrowserCamRef,
  RecordingState,
  StreamState,
  StreamStats,
  SnapshotOptions,
  SnapshotResult,
} from './types';

// ============================================================================
// Native Module Setup
// ============================================================================

// Helper to dispatch commands to the native view manager
const dispatchCommand = (tag: number, command: string, args: any[] = []) => {
  const commandId = UIManager.getViewManagerConfig(ComponentName)?.Commands?.[command] ?? command;
  UIManager.dispatchViewManagerCommand(tag, commandId, args);
};

// ============================================================================
// Native Component
// ============================================================================

interface NativeBrowserCamViewProps extends ViewProps {
  cameraConfig?: any;
  webViewConfig?: any;
  streamConfig?: any;
  recordingConfig?: any;
  onStreamStateChange?: (event: any) => void;
  onStreamStats?: (event: any) => void;
  onError?: (event: any) => void;
  onWebViewLoad?: (event: any) => void;
  onWebViewError?: (event: any) => void;
  onCameraReady?: (event: any) => void;
  onRecordingStateChange?: (event: any) => void;
  onRecordingFinished?: (event: any) => void;
  onLog?: (event: any) => void;
  children?: React.ReactNode;
}

const ComponentName = 'BrowserCameraView';
const NativeBrowserCamView = requireNativeComponent<NativeBrowserCamViewProps>(ComponentName);

// ============================================================================
// Main Component
// ============================================================================

/**
 * BrowserCamView - A native view that composites a WebView over a camera feed
 * and supports streaming/recording.
 * 
 * @example
 * ```tsx
 * import { BrowserCamView, BrowserCamRef } from 'react-native-browser-cam';
 * 
 * function LiveStreamApp() {
 *   const ref = useRef<BrowserCamRef>(null);
 * 
 *   return (
 *     <BrowserCamView
 *       ref={ref}
 *       style={{ flex: 1 }}
 *       cameraConfig={{ facing: 'back', fps: 30 }}
 *       webViewConfig={{ url: 'https://overlays.streamlabs.com/...' }}
 *       streamConfig={{
 *         url: 'rtmp://a.rtmp.youtube.com/live2/...',
 *         width: 1080,
 *         height: 1920,
 *         bitrate: 4000000,
 *       }}
 *       onStreamStateChange={(state) => console.log('Stream state:', state)}
 *     />
 *   );
 * }
 * ```
 */
export const BrowserCamView = forwardRef<BrowserCamRef, BrowserCamViewProps>(
  (props, ref) => {
    const {
      style,
      cameraConfig,
      webViewConfig,
      streamConfig,
      recordingConfig,
      onStreamStateChange,
      onStreamStats,
      onError,
      onWebViewLoad,
      onWebViewError,
      onCameraReady,
      onRecordingStateChange,
      onRecordingFinished,
      onLog,
      children,
    } = props;

    const nativeRef = useRef(null);
    const streamStateRef = useRef<StreamState>('idle');
    const recordingStateRef = useRef<RecordingState>('idle');
    const latestRecordingPathRef = useRef('');
    const pendingStopRecordingRef = useRef<{
      resolve: (path: string) => void;
      reject: (error: Error) => void;
      timeout: ReturnType<typeof setTimeout>;
    } | null>(null);

    // Get view tag for native method calls
    const getViewTag = useCallback(() => {
      if (nativeRef.current) {
        return findNodeHandle(nativeRef.current);
      }
      return null;
    }, []);

    // Expose methods via ref
    useImperativeHandle(ref, () => ({
      startStreaming: async () => {
        const tag = getViewTag();
        if (tag) {
          dispatchCommand(tag, 'startStreaming');
        }
      },

      stopStreaming: async () => {
        const tag = getViewTag();
        if (tag) {
          dispatchCommand(tag, 'stopStreaming');
        }
      },

      startRecording: async () => {
        const tag = getViewTag();
        if (tag) {
          dispatchCommand(tag, 'startRecording');
        }
      },

      stopRecording: async () => {
        const tag = getViewTag();
        if (!tag) {
          throw new Error('View not available');
        }

        if (pendingStopRecordingRef.current) {
          throw new Error('Recording stop already pending');
        }

        return new Promise<string>((resolve, reject) => {
          const timeout = setTimeout(() => {
            if (pendingStopRecordingRef.current) {
              pendingStopRecordingRef.current = null;
              resolve(latestRecordingPathRef.current);
            }
          }, 5000);

          pendingStopRecordingRef.current = { resolve, reject, timeout };
          dispatchCommand(tag, 'stopRecording');
        });
      },

      snapshot: async (options?: SnapshotOptions): Promise<SnapshotResult> => {
        const tag = getViewTag();
        if (tag) {
          dispatchCommand(tag, 'takeSnapshot', [options || {}]);
          return {} as SnapshotResult;
        }
        throw new Error('View not available');
      },

      reloadWebView: () => {
        const tag = getViewTag();
        if (tag) {
          dispatchCommand(tag, 'reloadWebView');
        }
      },

      loadUrl: (url: string) => {
        const tag = getViewTag();
        if (tag) {
          dispatchCommand(tag, 'loadUrl', [url]);
        }
      },

      injectJavaScript: (js: string) => {
        const tag = getViewTag();
        if (tag) {
          dispatchCommand(tag, 'injectJavaScript', [js]);
        }
      },

      getStreamState: () => {
        return streamStateRef.current;
      },

      getStreamStats: async (): Promise<StreamStats> => {
        return { bitrate: 0, fps: 0, droppedFrames: 0, duration: 0 };
      },

      switchCamera: () => {
        const tag = getViewTag();
        if (tag) {
          dispatchCommand(tag, 'switchCamera');
        }
      },

      setMuted: (muted: boolean) => {
        const tag = getViewTag();
        if (tag) {
          dispatchCommand(tag, 'setMuted', [muted]);
        }
      },
    }), [getViewTag]);

    const handleStreamStateChange = useCallback((e: any) => {
      const state = e.nativeEvent.state as StreamState;
      streamStateRef.current = state;
      onStreamStateChange?.(state);
    }, [onStreamStateChange]);

    const handleRecordingStateChange = useCallback((e: any) => {
      const state = e.nativeEvent.state as RecordingState;
      const path = e.nativeEvent.path as string | undefined;
      recordingStateRef.current = state;
      if (path) {
        latestRecordingPathRef.current = path;
      }
      onRecordingStateChange?.(state, path);
    }, [onRecordingStateChange]);

    const handleRecordingFinished = useCallback((e: any) => {
      const path = (e.nativeEvent.path as string | undefined) || '';
      latestRecordingPathRef.current = path;
      recordingStateRef.current = 'idle';
      const pending = pendingStopRecordingRef.current;
      if (pending) {
        clearTimeout(pending.timeout);
        pendingStopRecordingRef.current = null;
        pending.resolve(path);
      }
      onRecordingFinished?.(path);
    }, [onRecordingFinished]);

    const handleError = useCallback((e: any) => {
      const error = e.nativeEvent as { code: string; message: string };
      if (error.code === 'RECORDING_FAILED' && pendingStopRecordingRef.current) {
        const pending = pendingStopRecordingRef.current;
        clearTimeout(pending.timeout);
        pendingStopRecordingRef.current = null;
        pending.reject(new Error(error.message));
      }
      onError?.(error);
    }, [onError]);

    React.useEffect(() => {
      return () => {
        const pending = pendingStopRecordingRef.current;
        if (pending) {
          clearTimeout(pending.timeout);
          pendingStopRecordingRef.current = null;
          pending.resolve(latestRecordingPathRef.current);
        }
      };
    }, []);

    return (
      <NativeBrowserCamView
        ref={nativeRef}
        style={style}
        cameraConfig={cameraConfig}
        webViewConfig={webViewConfig}
        streamConfig={streamConfig}
        recordingConfig={recordingConfig}
        onStreamStateChange={handleStreamStateChange}
        onStreamStats={(e) => onStreamStats?.(e.nativeEvent)}
        onError={handleError}
        onWebViewLoad={() => onWebViewLoad?.()}
        onWebViewError={(e) => onWebViewError?.(e.nativeEvent)}
        onCameraReady={() => onCameraReady?.()}
        onRecordingStateChange={handleRecordingStateChange}
        onRecordingFinished={handleRecordingFinished}
        onLog={(e) => onLog?.(e.nativeEvent)}
      >
        {children}
      </NativeBrowserCamView>
    );
  }
);

BrowserCamView.displayName = 'BrowserCamView';

// ============================================================================
// Exports
// ============================================================================

export const BrowserCameraView = BrowserCamView;
export default BrowserCamView;

export const FileSystemUtils = NativeModules.BrowserCameraModule || {
  getDocumentDirectory: async () => '',
  listRecordings: async () => [],
  deleteRecording: async (_uri: string) => false,
};

// Re-export types
export * from './types';
