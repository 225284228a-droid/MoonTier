# MoonTier Android 项目

原生 Android 版 EasyTier 启动器，已从旧 Qt/QML 项目中拆出独立维护。

## 基本信息

- 应用名: MoonTier
- 包名: `cn.moonflow.easytier`
- 技术栈: Kotlin + Jetpack Compose + C++ JNI bridge
- ABI: `arm64-v8a`
- VPN 核心: `app/src/main/jniLibs/arm64-v8a/libeasytier_ffi.so`
- JNI bridge: `app/src/main/cpp/native_bridge.cpp`

## 构建

Release 构建命令:

```powershell
& 'C:\Users\12345a\.gradle\wrapper\dists\gradle-9.3.1-bin\23ovyewtku6u96viwx3xl3oks\gradle-9.3.1\bin\gradle.bat' -p 'C:\Users\12345a\Desktop\moontier-android' assembleRelease --warning-mode summary
```

Release APK:

```text
C:\Users\12345a\Desktop\moontier-android\app\build\outputs\apk\release\app-release.apk
```

签名文件位于项目根目录:

```text
C:\Users\12345a\Desktop\moontier-android\qtet-release.keystore
```

当前签名配置:

- alias: `qtet`
- password: `REMOVED_PRIVATE_SIGNING_PASSWORD`

## 安装

```powershell
& 'C:\Android\Sdk\platform-tools\adb.exe' -s AAXL6R5C19000981 install --no-incremental -r -g 'C:\Users\12345a\Desktop\moontier-android\app\build\outputs\apk\release\app-release.apk'
```

旧 Qt 版包名是 `cn.qtet.qteasytier`，新包名不同，可以和旧版同时安装。

