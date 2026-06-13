# MoonTier Android Audit Findings

审计时间: 2026-06-13

范围:

- Kotlin / Compose 业务逻辑
- VPN Service 生命周期
- JNI / native bridge 边界
- 配置导入导出与持久化
- 构建与发布配置

说明:

- 本文包含本轮静态审计中发现的全部问题。
- 按你的要求，已排除“配置文件未加密”和“签名泄露风险”这两类问题。
- 本轮额外执行了 `assembleRelease`，构建成功，APK 仍可产出。

## Critical

当前无。

## High

### 1. JNI `collectNetworkInfos()` 存在越界读取和非法释放风险

- 文件: [app/src/main/cpp/native_bridge.cpp](C:/Users/12345a/Desktop/moontier-android/app/src/main/cpp/native_bridge.cpp:108)
- 位置:
  - [app/src/main/cpp/native_bridge.cpp:108](C:/Users/12345a/Desktop/moontier-android/app/src/main/cpp/native_bridge.cpp:108)
  - [app/src/main/cpp/native_bridge.cpp:116](C:/Users/12345a/Desktop/moontier-android/app/src/main/cpp/native_bridge.cpp:116)
  - [app/src/main/cpp/native_bridge.cpp:130](C:/Users/12345a/Desktop/moontier-android/app/src/main/cpp/native_bridge.cpp:130)
- 问题:
  - Kotlin 侧传入 `maxLength` 后，JNI 只按这个长度分配 `std::vector<KeyValuePair>`。
  - 但后续 JSON 拼装和 `free_string()` 都直接按 native 返回的 `count` 遍历，没有对 `count` 做上限裁剪。
- 风险:
  - 如果 `collect_network_infos()` 返回值大于传入长度，会直接越界读取，甚至对无效指针调用 `free_string()`。
  - 这是明确的 native 崩溃 / 内存破坏路径。

### 2. VPN 授权被拒绝后，native 实例不会回滚，且后续可能不再申请权限

- 文件:
  - [app/src/main/java/cn/moonflow/easytier/EasyTierController.kt](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/EasyTierController.kt:48)
  - [app/src/main/java/cn/moonflow/easytier/MainActivity.kt](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/MainActivity.kt:120)
- 位置:
  - [EasyTierController.kt:48](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/EasyTierController.kt:48)
  - [EasyTierController.kt:151](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/EasyTierController.kt:151)
  - [MainActivity.kt:120](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/MainActivity.kt:120)
- 问题:
  - `start()` 会先启动 native 网络实例。
  - 真正的 VPN 权限申请是在拿到 CIDR 后才触发。
  - 如果用户拒绝系统 VPN 授权，回调只会清空 `pendingVpnConfig`，不会停止实例，也不会清掉 `vpnSignature`。
- 风险:
  - native 可能继续运行，但 TUN 永远未建立。
  - 因为 `vpnSignature` 已缓存，后续签名不变时不会再次触发 `requestVpn()`。
  - 这是明确的状态卡死和权限语义错误。

### 3. Always-on VPN 恢复路径错误

- 文件:
  - [app/src/main/AndroidManifest.xml](C:/Users/12345a/Desktop/moontier-android/app/src/main/AndroidManifest.xml:28)
  - [app/src/main/java/cn/moonflow/easytier/EasyTierVpnService.kt](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/EasyTierVpnService.kt:24)
  - [app/src/main/java/cn/moonflow/easytier/MainActivity.kt](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/MainActivity.kt:186)
- 位置:
  - [AndroidManifest.xml:28](C:/Users/12345a/Desktop/moontier-android/app/src/main/AndroidManifest.xml:28)
  - [EasyTierVpnService.kt:24](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/EasyTierVpnService.kt:24)
  - [MainActivity.kt:186](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/MainActivity.kt:186)
- 问题:
  - Service 没有显式关闭 `SUPPORTS_ALWAYS_ON`。
  - 但实现依赖 Activity 注入配置和人工授权。
  - 系统重建 VPN Service 时，空 intent / 空配置会直接 shutdown。
- 风险:
  - 在 Always-on 或 Lockdown 场景下，重启、升级、进程重建后无法恢复 VPN。

### 4. 运行态与真实后台状态容易严重漂移

- 文件:
  - [app/src/main/java/cn/moonflow/easytier/EasyTierController.kt](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/EasyTierController.kt:110)
  - [app/src/main/java/cn/moonflow/easytier/MainActivity.kt](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/MainActivity.kt:181)
  - [app/src/main/java/cn/moonflow/easytier/EasyTierVpnService.kt](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/EasyTierVpnService.kt:59)
- 位置:
  - [EasyTierController.kt:110](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/EasyTierController.kt:110)
  - [MainActivity.kt:181](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/MainActivity.kt:181)
  - [EasyTierVpnService.kt:59](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/EasyTierVpnService.kt:59)
- 问题:
  - `pollOnce()` 对 native 返回空串只直接返回，不会把 `running` 拉回 false。
  - Activity 销毁只取消轮询，不会重新附着已有实例，也不会主动停掉它。
  - Service 被 revoke / destroy 时会直接 `stopAllInstances()`。
- 风险:
  - 可能出现 UI 显示“运行中”但后台早已停止。
  - 也可能出现 UI 显示“未运行”但 Service / native 仍在跑。

## Medium

### 5. FFI 调用面缺少明确串行化保护，存在竞态风险

- 文件:
  - [app/src/main/cpp/native_bridge.cpp](C:/Users/12345a/Desktop/moontier-android/app/src/main/cpp/native_bridge.cpp:13)
  - [app/src/main/java/cn/moonflow/easytier/EasyTierController.kt](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/EasyTierController.kt:49)
  - [app/src/main/java/cn/moonflow/easytier/EasyTierVpnService.kt](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/EasyTierVpnService.kt:47)
- 问题:
  - native API 是全局式、无 handle 的接口。
  - Kotlin 侧会从轮询、停止流程、VPN service 等多路径调用这些接口。
- 风险:
  - 如果 `libeasytier_ffi.so` 内部没有完整同步，可能出现数据竞争、状态串线、UAF 或随机崩溃。
- 备注:
  - 这是基于 API 形态和调用路径的高可信推断，未反编译 `.so` 验证内部锁实现。

### 6. 启动任意配置会隐式改写默认配置

- 文件: [app/src/main/java/cn/moonflow/easytier/EasyTierController.kt](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/EasyTierController.kt:39)
- 位置:
  - [EasyTierController.kt:39](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/EasyTierController.kt:39)
- 问题:
  - `start()` 中会无条件 `saveConfig(normalized)`，并调用 `setDefaultConfig(normalized.id)`。
- 风险:
  - “启动某个配置”会被永久持久化成“设为默认配置”。
  - 用户数据被隐式篡改，行为与 UI 语义不一致。

### 7. `instanceName` 没有唯一性约束，可能导致实例状态串线

- 文件:
  - [app/src/main/java/cn/moonflow/easytier/Models.kt](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/Models.kt:10)
  - [app/src/main/java/cn/moonflow/easytier/MainActivity.kt](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/MainActivity.kt:1161)
  - [app/src/main/java/cn/moonflow/easytier/EasyTierController.kt](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/EasyTierController.kt:115)
  - [app/src/main/java/cn/moonflow/easytier/EasyTierVpnService.kt](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/EasyTierVpnService.kt:46)
- 问题:
  - `instanceName` 可以由用户自由编辑，存储层只保证非空，不保证唯一。
  - controller 用它作为 native 返回信息的 key。
  - VPN service 也用它做 `setTunFd()` 的实例标识。
- 风险:
  - 两个配置实例名重复时，可能出现日志、状态、TUN 绑定打到错误实例上的问题。

### 8. 配置持久化不是原子写入，损坏后还会被静默覆盖

- 文件: [app/src/main/java/cn/moonflow/easytier/ConfigStore.kt](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/ConfigStore.kt:40)
- 位置:
  - [ConfigStore.kt:40](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/ConfigStore.kt:40)
  - [ConfigStore.kt:74](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/ConfigStore.kt:74)
  - [ConfigStore.kt:141](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/ConfigStore.kt:141)
  - [ConfigStore.kt:30](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/ConfigStore.kt:30)
- 问题:
  - 所有 JSON 都直接 `writeText()` 覆盖。
  - 读取失败时又回退到默认值或空列表。
- 风险:
  - 一次中断写入或文件截断，会表现成配置丢失。
  - 后续任何一次保存都会把坏状态正式写回磁盘。

### 9. 主线程 I/O 过多，存在卡顿和 ANR 风险

- 文件:
  - [app/src/main/java/cn/moonflow/easytier/MainActivity.kt](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/MainActivity.kt:264)
  - [app/src/main/java/cn/moonflow/easytier/MainActivity.kt](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/MainActivity.kt:324)
  - [app/src/main/java/cn/moonflow/easytier/MainActivity.kt](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/MainActivity.kt:129)
  - [app/src/main/java/cn/moonflow/easytier/EasyTierController.kt](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/EasyTierController.kt:138)
- 问题:
  - Compose 初始化和 `reloadAll()` 直接同步读盘。
  - 多个 UI 事件处理直接同步写盘。
  - TOML 导入导出也在回调里直接读写文档流。
  - 轮询过程中还会同步读取设置文件。
- 风险:
  - 数据量稍大或文档 provider 较慢时，会直接阻塞 UI 线程。

### 10. 用户服务器编辑页存在确定的数据丢失和状态不同步

- 文件:
  - [app/src/main/java/cn/moonflow/easytier/MainActivity.kt](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/MainActivity.kt:722)
  - [app/src/main/java/cn/moonflow/easytier/ConfigStore.kt](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/ConfigStore.kt:141)
- 位置:
  - [MainActivity.kt:722](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/MainActivity.kt:722)
  - [MainActivity.kt:741](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/MainActivity.kt:741)
  - [ConfigStore.kt:141](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/ConfigStore.kt:141)
- 问题:
  - 新增空白收藏后会立刻保存。
  - 存储层又会过滤空地址。
  - 之后 `reloadAll()` 再把界面状态覆盖回磁盘状态。
- 风险:
  - 新增空白项会立刻消失。
  - 清空地址后修改会被吞。

### 11. 允许通过明文 HTTP 拉取服务器列表，存在中间人篡改风险

- 文件:
  - [app/src/main/AndroidManifest.xml](C:/Users/12345a/Desktop/moontier-android/app/src/main/AndroidManifest.xml:16)
  - [app/src/main/java/cn/moonflow/easytier/ConfigStore.kt](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/ConfigStore.kt:168)
- 位置:
  - [AndroidManifest.xml:16](C:/Users/12345a/Desktop/moontier-android/app/src/main/AndroidManifest.xml:16)
  - [ConfigStore.kt:168](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/ConfigStore.kt:168)
  - [ConfigStore.kt:233](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/ConfigStore.kt:233)
- 问题:
  - Manifest 开启了 `usesCleartextTraffic="true"`。
  - 服务器同步逻辑允许直接读取任意 `http://` URL。
- 风险:
  - 如果用户填入明文源，服务器列表可以被链路中间人篡改。

## Low

### 12. TOML 往返会丢失配置标签

- 文件: [app/src/main/java/cn/moonflow/easytier/TomlCodec.kt](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/TomlCodec.kt:4)
- 位置:
  - [TomlCodec.kt:4](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/TomlCodec.kt:4)
  - [TomlCodec.kt:110](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/TomlCodec.kt:110)
- 问题:
  - 导出时不写 `network_label`。
  - 导入时却优先读 `network_label`。
- 风险:
  - 导出再导入后，用户自定义配置名称会静默变成 `network_name`。

### 13. DNS TXT 同步失败时，错误聚合信息拼接有 bug

- 文件: [app/src/main/java/cn/moonflow/easytier/ConfigStore.kt](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/ConfigStore.kt:196)
- 位置:
  - [ConfigStore.kt:196](C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/ConfigStore.kt:196)
- 问题:
  - 这里本意是把多个 provider 的失败原因拼进异常消息。
  - 但字符串插值没有正确生效。
- 风险:
  - 用户实际看到的错误信息不完整，排障效率会明显下降。

### 14. Gradle 构建脚本使用了即将被移除的 Groovy DSL 写法

- 文件: [app/build.gradle](C:/Users/12345a/Desktop/moontier-android/app/build.gradle:7)
- 位置:
  - [app/build.gradle:7](C:/Users/12345a/Desktop/moontier-android/app/build.gradle:7)
  - [app/build.gradle:9](C:/Users/12345a/Desktop/moontier-android/app/build.gradle:9)
  - [app/build.gradle:40](C:/Users/12345a/Desktop/moontier-android/app/build.gradle:40)
  - [app/build.gradle:43](C:/Users/12345a/Desktop/moontier-android/app/build.gradle:43)
  - [app/build.gradle:54](C:/Users/12345a/Desktop/moontier-android/app/build.gradle:54)
  - [app/build.gradle:65](C:/Users/12345a/Desktop/moontier-android/app/build.gradle:65)
- 问题:
  - `Gradle 9.3.1` 已对这些空格赋值 DSL 报弃用警告。
- 风险:
  - 到 `Gradle 10` 时，这些写法会直接成为构建阻断项。

## Build Result

- 已执行:
  - `assembleRelease --warning-mode all`
- 结果:
  - 构建成功
  - APK 路径: [app-release.apk](/C:/Users/12345a/Desktop/moontier-android/app/build/outputs/apk/release/app-release.apk)
- 补充:
  - 当前未发现新的 lint 问题
  - 但发现了 Gradle DSL 弃用警告，见上文第 14 条

## Recommended Fix Order

1. 修复 JNI `count` 越界问题
2. 修复 VPN 拒权后的回滚与重试逻辑
3. 明确关闭 Always-on 支持，或补全真正的恢复路径
4. 修复运行态收敛逻辑，避免 UI / 后台状态漂移
5. 处理 `instanceName` 唯一性和默认配置被隐式改写的问题
6. 将配置读写和文档导入导出移出主线程
7. 修复服务器编辑页的数据丢失
8. 改成原子写入并增加损坏恢复策略
9. 限制或移除明文 HTTP 服务器源
10. 清理 TOML 往返与错误提示等低优先级问题
