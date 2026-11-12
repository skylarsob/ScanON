# ScanON 3.0.1 — source

Kotlin + Jetpack Compose network diagnostics for users in Iran (package `com.skyler.scanon`, versionName 3.0.1 / versionCode 38).

**📖 راهنمای فارسی و دانلود: https://skylarsob.github.io/ScanON/**
**⬇️ Releases: https://github.com/skylarsob/ScanON/releases**
**🐞 باگ و پیشنهاد: https://github.com/skylarsob/ScanON/issues**

## Build
Requirements: JDK 17, Android SDK with platform `android-37` and build-tools 36+. Create `local.properties`
with `sdk.dir=/path/to/sdk`.

```
./gradlew test                       # 40 JVM unit tests
./gradlew assembleRelease -Pscanon.signingProps=/path/to/signing.properties
```
`signing.properties` holds `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. Without it the release
APK is unsigned. Output: `app/build/outputs/apk/release/ScanON-3.0.1-arm64-v8a.apk` and `-universal.apk`.

On an aarch64 Linux host Google's aapt2 (x86-only) must be replaced: `build.sh` passes
`-Pandroid.aapt2FromMavenOverride=<native aapt2>`; edit the paths at its top.

## Layout
- `engine/` network tests (TCP/HTTP/HTTPS/WS/ICMP, download), DNS wire protocol (UDP/TCP/DoT/DoH), target
  parsing, MMDB reader, GeoIP, diagnostics, statistics and exporters — plain Kotlin, unit-tested.
- `data/` JSON stores (vault, groups, history), settings, backup.
- `ui/vm/` ViewModels with StateFlow state; `ui/screens/` Compose screens; `ui/components/` shared UI.
- `service/` foreground keep-alive + notifications.
- `assets/` GeoLite2 City/ASN databases (MaxMind, CC BY-SA 4.0) — kept uncompressed in the APK and memory-mapped.
