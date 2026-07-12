# SyncVault

Minimal Android app: manually scan an image folder, encrypt new images with
AES-256-GCM (key held in the Android Keystore), and copy the encrypted
copies into a chosen "DriveSync" folder. Everything is triggered by a single
Scan button — no background service, no scheduling, no network.

## Project layout

```
SyncVault/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/syncvault/app/
│       │   ├── MainActivity.kt
│       │   ├── MainScreen.kt   (single Compose screen: pickers, scan, log)
│       │   ├── SyncEngine.kt   (scan/hash/encrypt/copy logic)
│       │   └── Crypto.kt       (AES-256-GCM via Android Keystore)
│       └── res/values/ (strings.xml, themes.xml)
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
└── gradle/wrapper/gradle-wrapper.properties (Gradle 8.4)
```

Package name (`com.syncvault.app`) is consistent across the manifest,
`namespace`/`applicationId` in `app/build.gradle.kts`, and every Kotlin file.

## One manual step: the wrapper JAR

Every file needed to build is included **except one binary**:
`gradle/wrapper/gradle-wrapper.jar`. It's a small compiled bootstrap JAR
that Gradle publishes as a binary release artifact, not source — I don't
have network access in this environment to download it, and I won't
hand-fabricate binary bytes and pass them off as the real thing, since a
corrupted jar would just break your build in a more confusing way than a
missing file does.

You have two easy options, pick whichever is faster for you:

**Option A — let Android Studio create it (recommended)**
Open the project in Android Studio. If it detects the missing wrapper jar
it will offer to regenerate it automatically using its bundled Gradle. If
it doesn't prompt, open the "Gradle" tool window → the elephant/wrapper
icon → "Generate Gradle Wrapper", or just run Option B in the built-in
terminal.

**Option B — one command, if you have Gradle installed anywhere**
```bash
gradle wrapper --gradle-version 8.4
```
Run it from the `SyncVault/` root. This creates
`gradle/wrapper/gradle-wrapper.jar` matching the `gradle-wrapper.properties`
already in the project. After that, `./gradlew assembleDebug` works exactly
as normal on any machine, with no further setup.

You can verify the jar afterward against Gradle's published checksums at
https://gradle.org/release-checksums/ if you want to be sure it's untampered.

## Building

```bash
./gradlew assembleDebug
```

Requires an Android SDK on the machine (SDK 34 / build-tools) and either an
`ANDROID_HOME`/`ANDROID_SDK_ROOT` environment variable or a `local.properties`
file at the project root containing:
```
sdk.dir=/path/to/Android/sdk
```
(`local.properties` is machine-specific and intentionally not checked in —
see `.gitignore`.)

Output APK: `app/build/outputs/apk/debug/app-debug.apk`
