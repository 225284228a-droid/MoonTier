# MoonTier Android 代码审计报告

**审计日期**: 2026-06-13  
**项目**: MoonTier (cn.moonflow.easytier)  
**技术栈**: Kotlin + Jetpack Compose + C++ JNI bridge

> 本次为完全重新审计，不基于之前报告。

---

## P0 — 必然触发错误

### 1. ConfigStore.httpGet — 错误消息字符串插值使用 % 而非 $

**文件**: [app/src/main/java/cn/moonflow/easytier/ConfigStore.kt](/C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/ConfigStore.kt:277)

`kotlin
throw IllegalStateException("HTTP %responseCode: %errorBody".trimIndent())
`

Kotlin 字符串插值语法是 ${} 或 $，Java 才是 %。这行代码会产生字面量 "HTTP %responseCode: %errorBody"，永远不会显示实际的 HTTP 状态码和错误体。对排查网络问题完全无用。

---

## P1 — 可能导致不可预期行为

### 2. C++ collectNetworkInfos — JSON 值字段不转义，依赖调用约定

**文件**: [app/src/main/cpp/native_bridge.cpp](/C:/Users/12345a/Desktop/moontier-android/app/src/main/cpp/native_bridge.cpp:135)

`cpp
json << "\"" << jsonEscape(infos[...].key) << "\":";  // key 有转义
if (infos[static_cast<size_t>(i)].value)
    json << infos[static_cast<size_t>(i)].value;        // value 无转义，直接拼接
`

键名调用了 jsonEscape 做转义，但值部分直接原样拼入 JSON。代码依赖 collect_network_infos 的返回值已经是一个合法 JSON 片段（Object 或 Array 或 string literal）。如果 native 端返回的值字符串包含未转义的双引号或反斜杠，生成的 JSON 会被破坏，Kotlin 端 JSONObject(raw) 解析失败 → pollOnce 中 outer 为 null → 误认为实例退出 → 两轮后停止实例。这会把 JSON 格式问题误判为网络实例崩溃。

**更具防御性的写法**：值用 "..." 包裹并调用 jsonEscape，或者让 collect_network_infos 明确返回转义后的字符串。

### 3. EasyTierVpnService.shutdownVpn() — 双重 shutdown 调用链

**文件**: [app/src/main/java/cn/moonflow/easytier/EasyTierVpnService.kt](/C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/EasyTierVpnService.kt)

当 setTunFd 失败时调用链：

`
onStartCommand (setTunFd failed)
  → disconnect() + stopAllInstances() + stopSelf()
  → onDestroy()
    → shutdownVpn()
      → disconnect() + stopAllInstances() + stopForeground()
`

stopAllInstances() 被调用了两次。当前 native 实现大概率是幂等的，但如果后续版本中 stopAllInstances 有副作用（如日志计数、资源计数器），就会出错。

同样的双重调用也发生在 EasyTierController.stopRuntime()：

`kotlin
val stopIntent = Intent(context, EasyTierVpnService::class.java)
    .setAction(EasyTierVpnService.ACTION_STOP)
runCatching { context.startService(stopIntent) }    // 触发 onStartCommand → shutdownVpn
context.stopService(...)                             // 触发 onDestroy → shutdownVpn
`

**建议**: 在 shutdownVpn() 开头检查一个 "已 shutdown" 标志避免重复执行。至少确保 shutdownVpn 本身幂等。

---

## P2 — 资源浪费和代码积

### 4. DisposableEffect 空 onDispose

**文件**: [app/src/main/java/cn/moonflow/easytier/MainActivity.kt](/C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/MainActivity.kt:265)

`kotlin
DisposableEffect(Unit) {
    bindImportHandler(...)
    onDispose { }    // 空块，无意义
}
`

如果不需要清理逻辑，这应该用 LaunchedEffect(Unit) 或 SideEffect。当前写法让人误以为遗漏了清理代码。

### 5. 未使用的 import 语句

**文件**: [app/src/main/java/cn/moonflow/easytier/MainActivity.kt](/C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/MainActivity.kt)

- import androidx.compose.animation.Crossfade — 无任何使用点
- import androidx.compose.ui.text.style.TextAlign — 无任何使用点

### 6. ConfigStore.normalizeConfigs 静默将空配置列表转为默认配置

**文件**: [app/src/main/java/cn/moonflow/easytier/ConfigStore.kt](/C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/ConfigStore.kt:122)

`kotlin
val list = if (configs.isEmpty()) listOf(NetworkConfig.defaultConfig()) else configs
`

这意味着 saveConfigs(emptyList()) 会静默创建并持久化一个新的默认配置。调用方如果意图清空配置会被无视。上一版中 deleteConfig 也有相同守卫（已移除），但 
ormalizeConfigs 仍然保留了这个行为。建议要么做显式参数控制，要么把守卫完全去掉并让调用方自己保证非空。

---

## P3 — 轻微问题

### 7. Ipv4.appendRange — 1L shl 32 依赖 Long 不溢出

**文件**: [app/src/main/java/cn/moonflow/easytier/Ipv4.kt](/C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/Ipv4.kt:92)

`kotlin
var block = if (current == 0L) 1L shl 32 else current and -current
`

1L shl 32 结果是 4294967296L (2^32)，超出 32-bit 的 Int 范围，但 Kotlin shl 的语义是 Long.shl(Int): Long，返回值仍然在 Long 范围内。这不会崩溃，但意图不清晰——1L shl 32 表示的是 2^32（整个 IPv4 地址空间大小），建议用常量表达。

### 8. NetworkConfig.defaultConfig() 每次调用生成新的 UUID

**文件**: [app/src/main/java/cn/moonflow/easytier/Models.kt](/C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/Models.kt)

NetworkConfig 主构造函数中 id 默认为 UUID.randomUUID().toString()，且 defaultConfig() 不传 id 参数。每次 NetworkConfig() 调用都会生成新 UUID。在 
ormalizeConfigs 创建兜底默认配置时，这个 UUID 和后续任何通过 NetworkConfig.defaultConfig() 创建的配置 ID 都不同。虽然功能上无影响，但调试时两个"默认配置"拥有不同的 ID 会让日志追踪变得混乱。

### 9. EasyTierVpnService 通知图标使用系统 ic_menu_share

**文件**: [app/src/main/java/cn/moonflow/easytier/EasyTierVpnService.kt](/C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/EasyTierVpnService.kt:118)

`kotlin
.setSmallIcon(android.R.drawable.ic_menu_share)
`

持续运行的 VPN 前台服务通知使用"分享"图标不合适，且 Google Play 可能因此拒绝上架（前台服务通知图标应与应用功能相关）。Android 14+ 对前台服务通知特别严格。

### 10. ConfigStore.resolvePublicIpv4 — IP 地址解析无超时

**文件**: [app/src/main/java/cn/moonflow/easytier/ConfigStore.kt](/C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/ConfigStore.kt:278)

`kotlin
InetAddress.getAllByName(host)
`

getAllByName 执行 DNS 查询，没有设置超时且无法取消。如果 DNS 服务器不可达，这个调用会阻塞调用线程（虽然是 IO dispatcher）。已经在 withContext(Dispatchers.IO) 中运行，不会阻塞主线程，但会占用一个 IO 线程到系统默认超时。

### 11. TomlCodec.logicalLines — brackets 计数可能为负

**文件**: [app/src/main/java/cn/moonflow/easytier/TomlCodec.kt](/C:/Users/12345a/Desktop/moontier-android/app/src/main/java/cn/moonflow/easytier/TomlCodec.kt:213)

`kotlin
brackets += line.count { it == '[' } - line.count { it == ']' }
if (brackets <= 0) {
    out += current.toString()
    current.clear()
    brackets = 0
}
`

如果 TOML 文件中括号不匹配（例如只有一个 ] 而没有 [），brackets 会变为负数。此时该行立即被输出但 current 可能尚未构建完整内容。恶意构造的输入可能导致解析出错误的 section 归属。在生产环境中不太可能触发（TOML 来自官方或可信来源），但导入用户手动编辑的 TOML 时可能出现。

---

## 已修复项（与首次审计对比）

| 原问题 | 状态 |
|--------|------|
| launchIntent null → NPE | 已修复：增加 null 检查 |
| httpGet 连接未关闭 | 已修复：	ry-finally + disconnect() |
| EasyTierController.start() 无异常捕获 | 已修复：unCatching 包裹 |
| TomlCodec.bool() 静默吞值 | 已修复：Log.w warning |
| ConfigEditorScreen 死代码 | 已删除 |
| Color.toArgbInt() 无调用方 | 现已在 ApplySystemBars 中使用 |
| import android.view.View 未使用 | 现已在 ApplySystemBars 中使用 |
| start() 内重复 state 初始化 | 已移除冗余调用 |
| deleteConfig 双重重置守卫 | 已移除，仅保留 
ormalizeConfigs 一处 |
| 日志 	akeLast(240) 散落多处 | 已抽取为 ppendLog() 方法 |

---

## 总结

| 级别 | 数量 | 说明 |
|------|------|------|
| **P0** | 1 | % 替换 $ 导致错误消息无意义 |
| **P1** | 2 | JSON 值不转义 + 双重 shutdown |
| **P2** | 3 | 空 onDispose + 未使用 import + 静默默认配置 |
| **P3** | 5 | 常量清晰度 + 非固定 UUID + 通知图标 + DNS 无超时 + TOML bracket 边界 |

相比上次审计，代码质量有显著提升：5 个 P0/P1 已修复，日志重复已被抽取，死代码已清理。当前剩余问题集中在 httpGet 错误消息的格式化错误和 C++ JSON 构造的脆弱性上。