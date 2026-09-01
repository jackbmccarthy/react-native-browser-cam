//
//  BrowserCameraViewManager.m
//  react-native-web-cam-stream
//
//  React Native View Manager Bridge (Objective-C)
//

#import <React/RCTBridgeModule.h>
#import <React/RCTEventEmitter.h>
#import <React/RCTViewManager.h>

@interface RCT_EXTERN_MODULE (BrowserCameraViewManager, RCTViewManager)

// Expose properties to React Native
RCT_EXPORT_VIEW_PROPERTY(cameraConfig, NSDictionary)
RCT_EXPORT_VIEW_PROPERTY(webViewConfig, NSDictionary)
RCT_EXPORT_VIEW_PROPERTY(streamConfig, NSDictionary)
RCT_EXPORT_VIEW_PROPERTY(recordingConfig, NSDictionary)

// Event callbacks
RCT_EXPORT_VIEW_PROPERTY(onStreamStateChange, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onStreamStats, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onAudioLevel, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onError, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onWebViewLoad, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onWebViewError, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onCameraReady, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onRecordingStateChange, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onRecordingFinished, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onLog, RCTDirectEventBlock)

// Expose methods to React Native
RCT_EXTERN_METHOD(startStreaming : (nonnull NSNumber *)reactTag)
RCT_EXTERN_METHOD(stopStreaming : (nonnull NSNumber *)reactTag)
RCT_EXTERN_METHOD(startRecording : (nonnull NSNumber *)reactTag)
RCT_EXTERN_METHOD(stopRecording : (nonnull NSNumber *)reactTag)
RCT_EXTERN_METHOD(takeSnapshot : (nonnull NSNumber *)
                      reactTag options : (NSDictionary *)options)
RCT_EXTERN_METHOD(reloadWebView : (nonnull NSNumber *)reactTag)
RCT_EXTERN_METHOD(loadUrl : (nonnull NSNumber *)reactTag url : (NSString *)url)
RCT_EXTERN_METHOD(injectJavaScript : (nonnull NSNumber *)
                      reactTag js : (NSString *)js)
RCT_EXTERN_METHOD(switchCamera : (nonnull NSNumber *)reactTag)
RCT_EXTERN_METHOD(focusAtPoint : (nonnull NSNumber *)reactTag
                  x : (nonnull NSNumber *)x
                  y : (nonnull NSNumber *)y)
RCT_EXTERN_METHOD(setMuted : (nonnull NSNumber *)reactTag muted : (BOOL)muted)
RCT_EXTERN_METHOD(getStreamState : (nonnull NSNumber *)reactTag resolver : (
    RCTPromiseResolveBlock)resolve rejecter : (RCTPromiseRejectBlock)reject)
RCT_EXTERN_METHOD(getStreamStats : (nonnull NSNumber *)reactTag resolver : (
    RCTPromiseResolveBlock)resolve rejecter : (RCTPromiseRejectBlock)reject)

@end
