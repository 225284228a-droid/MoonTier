# MoonTier Android

Native Android EasyTier launcher: Kotlin, Jetpack Compose and C++ JNI, arm64-v8a.
Package: `cn.moonflow.easytier`.

## Build

Use JDK 17+, Android SDK 36, NDK 27.2.12479018 and Gradle 9.3.1.
Run `gradle assembleDebug` or `gradle assembleRelease` from the repository root.
Build outputs are under `app/build/outputs/apk/`.

Release signing is optional and configured in local `keystore.properties`;
see `keystore.properties.example`. Never commit signing keys or passwords.

Use Git when appropriate and commit completed changes.
