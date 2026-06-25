/**
 * Expo Config Plugin for react-native-browser-cam
 * 
 * This plugin automatically configures native permissions for:
 * - Camera access
 * - Microphone access (for streaming audio)
 * - Network access for RTMP streaming
 */

const { withAndroidManifest, withInfoPlist } = require('@expo/config-plugins');

const ANDROID_PERMISSIONS = [
  'android.permission.CAMERA',
  'android.permission.RECORD_AUDIO',
  'android.permission.INTERNET',
  'android.permission.ACCESS_NETWORK_STATE',
];

const IOS_PERMISSIONS = {
  NSCameraUsageDescription: 'This app needs camera access to stream live video.',
  NSMicrophoneUsageDescription: 'This app needs microphone access to stream audio with your video.',
  NSPhotoLibraryAddUsageDescription: 'This app needs access to save recorded videos to your photo library.',
  NSPhotoLibraryUsageDescription: 'This app needs access to your photo library to select videos.',
};

/**
 * Add Android permissions to AndroidManifest.xml
 */
function withAndroidPermissions(config) {
  return withAndroidManifest(config, (config) => {
    // Add uses-permission elements
    ANDROID_PERMISSIONS.forEach((permission) => {
      if (!config.modResults.manifest['uses-permission']) {
        config.modResults.manifest['uses-permission'] = [];
      }
      
      const hasPermission = config.modResults.manifest['uses-permission'].some(
        (p) => p.$['android:name'] === permission
      );
      
      if (!hasPermission) {
        config.modResults.manifest['uses-permission'].push({
          $: { 'android:name': permission },
        });
      }
    });
    
    // Add uses-feature for camera
    if (!config.modResults.manifest['uses-feature']) {
      config.modResults.manifest['uses-feature'] = [];
    }
    
    const hasCameraFeature = config.modResults.manifest['uses-feature'].some(
      (f) => f.$['android:name'] === 'android.hardware.camera'
    );
    
    if (!hasCameraFeature) {
      config.modResults.manifest['uses-feature'].push({
        $: {
          'android:name': 'android.hardware.camera',
          'android:required': 'false',
        },
      });
    }
    
    return config;
  });
}

/**
 * Add iOS usage descriptions to Info.plist
 */
function withIOSPermissions(config, props = {}) {
  return withInfoPlist(config, (config) => {
    // Merge user-provided descriptions with defaults
    const descriptions = { ...IOS_PERMISSIONS, ...props };
    
    Object.entries(descriptions).forEach(([key, value]) => {
      config.modResults[key] = value;
    });
    
    return config;
  });
}

/**
 * Main plugin export
 */
module.exports = function withWebCamStream(config, props = {}) {
  config = withAndroidPermissions(config);
  config = withIOSPermissions(config, props.ios);
  return config;
};

// Export individual functions for advanced usage
module.exports.withAndroidPermissions = withAndroidPermissions;
module.exports.withIOSPermissions = withIOSPermissions;
