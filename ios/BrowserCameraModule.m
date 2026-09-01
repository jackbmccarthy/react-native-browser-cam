//
//  BrowserCameraModule.m
//  react-native-web-cam-stream
//
//  React Native module bridge.
//

#import <React/RCTBridgeModule.h>

@interface RCT_EXTERN_MODULE(BrowserCameraModule, NSObject)

RCT_EXTERN_METHOD(getDocumentDirectory : (RCTPromiseResolveBlock)resolve
                  rejecter : (RCTPromiseRejectBlock)reject)
RCT_EXTERN_METHOD(listRecordings : (RCTPromiseResolveBlock)resolve
                  rejecter : (RCTPromiseRejectBlock)reject)
RCT_EXTERN_METHOD(deleteRecording : (NSString *)uri
                  resolver : (RCTPromiseResolveBlock)resolve
                  rejecter : (RCTPromiseRejectBlock)reject)
RCT_EXTERN_METHOD(getAvailableCameras : (RCTPromiseResolveBlock)resolve
                  rejecter : (RCTPromiseRejectBlock)reject)
RCT_EXTERN_METHOD(getAvailableAudioInputs : (RCTPromiseResolveBlock)resolve
                  rejecter : (RCTPromiseRejectBlock)reject)

@end
