# Astra VPN

Android VPN client powered by sing-box, built with Kotlin.

## Current project status

This repository contains an early Android application scaffold:
- Kotlin Android app and VPN service integration
- sing-box libbox platform adapter
- encrypted local storage for imported server profiles
- GitHub Actions workflow that attempts a debug APK build

**This is an in-development project, not a verified release.** The first successful CI build and real-device VPN tests are still required before the APK can be considered usable.

## Build

The project uses:
- JDK 17
- Gradle 8.9
- Android Gradle Plugin 8.7.3
- Android SDK 35

To build locally with Gradle installed:

```bash
gradle assembleDebug
```

The debug APK, if the build succeeds, is generated at:

```
app/build/outputs/apk/debug/app-debug.apk
```

## Security

Do not commit personal VPN subscription URLs, access tokens, private keys, or server credentials. Imported profiles are intended to be stored locally on the Android device.

## Next milestones

1. Obtain a clean CI build.
2. Resolve any Android/libbox API compatibility issues reported by CI.
3. Test VLESS Reality and other supported protocols with test profiles on a real Android device.
4. Add robust subscription refresh, server selection, and reconnect handling.
5. Produce a signed release APK only after testing.
