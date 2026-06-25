# Dependencies - Native Streaming Libraries

This document tracks the native dependencies required for RTMP streaming on iOS and Android platforms.

## HaishinKit - RTMP Streaming Library

HaishinKit is the core native library for camera capture and RTMP streaming.

### iOS / Swift (HaishinKit.swift)

**Latest Version:** `2.2.4`

**Repository:** https://github.com/HaishinKit/HaishinKit.swift

**Requirements:**
- Xcode 16.4+ (for v2.1.0+) or Xcode 26.0+ (for v2.2.0+)
- Swift 6.0+
- iOS 15.0+ | tvOS 15.0+ | macOS 12.0+ | visionOS 1.0+

**Installation (Swift Package Manager):**

In Xcode:
1. File → Add Package Dependencies
2. Enter URL: `https://github.com/HaishinKit/HaishinKit.swift`

Or in `Package.swift`:
```swift
dependencies: [
    .package(url: "https://github.com/HaishinKit/HaishinKit.swift", from: "2.2.4")
]
```

**Available Modules:**
- `HaishinKit` - Base module
- `RTMPHaishinKit` - RTMP streaming
- `SRTHaishinKit` - SRT streaming (not available for Mac Catalyst)
- `RTCHaishinKit` - WHEP/WHIP streaming (alpha)

**Info.plist Keys Required:**
```xml
<key>NSCameraUsageDescription</key>
<string>Your camera usage description</string>
<key>NSMicrophoneUsageDescription</key>
<string>Your microphone usage description</string>
```

**Official Documentation:** https://docs.haishinkit.com/swift/latest/documentation/

**Migration Guide:** https://github.com/HaishinKit/HaishinKit.swift/wiki

---

### Android / Kotlin (HaishinKit.kt)

**Latest Version:** `0.18.0`

**Repository:** https://github.com/HaishinKit/HaishinKit.kt

**Requirements:**
- minSdk 21+
- Android 5.0+ (Lollipop)

**Installation (Gradle via JitPack):**

1. Add JitPack repository in `settings.gradle` or `settings.gradle.kts`:

**Groovy DSL (settings.gradle):**
```groovy
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

**Kotlin DSL (settings.gradle.kts):**
```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

2. Add dependencies in `app/build.gradle`:

```groovy
dependencies {
    // Core module (required)
    implementation 'com.github.HaishinKit.HaishinKit~kt:haishinkit:0.18.0'
    
    // RTMP streaming (required)
    implementation 'com.github.HaishinKit.HaishinKit~kt:rtmp:0.18.0'
    
    // Optional: Jetpack Compose components
    implementation 'com.github.HaishinKit.HaishinKit~kt:compose:0.18.0'
    
    // Optional: Lottie animation embedding
    implementation 'com.github.HaishinKit.HaishinKit~kt:lottie:0.18.0'
}
```

**⚠️ IMPORTANT:** The JitPack artifact uses `~kt` not `.kt`:
- ✅ Correct: `com.github.HaishinKit.HaishinKit~kt`
- ❌ Wrong: `com.github.HaishinKit.HaishinKit.kt`

**AndroidManifest.xml Permissions:**
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

**Official Documentation:** https://docs.haishinkit.com/kt/latest/

---

## Version History

| Date | iOS Version | Android Version | Notes |
|------|-------------|-----------------|-------|
| 2026-02-13 | 2.2.4 | 0.18.0 | Current latest |

---

## Related Projects

- **Flutter:** [HaishinKit.dart](https://github.com/HaishinKit/HaishinKit.dart) - Flutter version

---

## License

HaishinKit is licensed under **BSD-3-Clause**.

## Support

- **Free:** GitHub Issues/Discussions (community support only)
- **Priority Support:** [$50/month sponsorship](https://github.com/sponsors/shogo4405) includes priority response for technical issues
