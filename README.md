# MoonTier

基于 EasyTier 的原生 Android 组网客户端，使用 Kotlin、Jetpack Compose 和 C++ JNI 构建。

- Android 7.0（API 24）及以上，支持 arm64-v8a。
- VPN 模式：通过 Android VpnService 和 EasyTier FFI 运行，支持配置服务器下发网络。
- Root 模式：管理独立 EasyTier 核心，支持下载安装与本地导入。
- 提供网络配置、配置服务器、节点状态、路由及诊断日志界面。
- 应用包名：`cn.moonflow.easytier`。

## 构建

需要 JDK 17 或更高版本、Gradle 9.3.1、Android SDK 36、NDK 27.2.12479018，以及 Android SDK 的 CMake 工具。
在本地 `local.properties` 中设置 `sdk.dir`，或配置 Android SDK 环境。

```sh
gradle assembleDebug
gradle assembleRelease
```

本仓库暂未提供 Gradle Wrapper，请使用本机安装的 Gradle。
APK 输出目录为 `app/build/outputs/apk/`。

如需签名发行版，将 `keystore.properties.example` 复制为 `keystore.properties`，
填入自己的密钥路径和签名参数。不配置时，debug 使用默认调试签名，release 生成未签名 APK；
配置后 debug 和 release 使用所配置的签名。请勿提交私钥和密码。

Root manager 的源码与构建说明见 [tools/root-manager-client](tools/root-manager-client/README.md)。
FFI 核心的固定源码版本、Android 适配补丁及构建步骤见 [tools/ffi-runtime](tools/ffi-runtime/README.md)。

```sh
gradle testDebugUnitTest lintDebug assembleDebug
```

本次自动化验证结果与待完成的实机验收见 [运行时与界面验收记录](docs/runtime-ui-validation.md)。

## 开源许可与致谢

MoonTier 项目代码采用 [GNU LGPL v3.0](LICENSE)。LGPL 引用的 GPL v3 正文见 [COPYING](COPYING)。
第三方组件保留各自的版权和许可证，详情见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

感谢 [EasyTier](https://github.com/EasyTier/EasyTier) 项目。本项目是独立 Android 客户端，不是 EasyTier 官方客户端。
