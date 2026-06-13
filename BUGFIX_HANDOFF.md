# MoonTier Bugfix Handoff

交接时间: 2026-06-13

项目目录:

```text
C:\Users\12345a\Desktop\moontier-android
```

旧 Qt 项目目录:

```text
C:\Users\12345a\Desktop\qtet-android-master
```

当前原生版包名:

```text
cn.moonflow.easytier
```

Release APK:

```text
C:\Users\12345a\Desktop\moontier-android\app\build\outputs\apk\release\app-release.apk
```

## 当前状态

- 项目已从旧 Qt 目录拆出到 `moontier-android`。
- 旧 Qt 版包名 `cn.qtet.qteasytier`，新原生版包名 `cn.moonflow.easytier`，可以共存。
- 新版当前版本号为 `1.0.0` / `versionCode 1`。
- 新版 release 已多次成功编译并安装。
- 当前目录已初始化为 Git 仓库，基线提交为 `204e7f5 Initial MoonTier Android baseline`。
- 最近一次 `assembleRelease` 在修复弹窗/导航样式后成功过。

## 本轮已完成修复

- 已修复 JNI `collectNetworkInfos()` count 越界读取 / 非法释放风险。
- 已为 JNI bridge 全部 FFI 调用增加同一把 mutex 串行化保护。
- VPN 授权被拒绝时会回滚 native 实例、清空 VPN 签名并更新运行态。
- 当前实现已显式声明不支持 Always-on VPN 自动恢复。
- 运行态轮询连续读不到实例时会收敛为“实例已退出”，Activity 重建时会尝试附着已有 native 实例。
- 启动配置不再隐式改写默认配置。
- `instanceName` 在配置 normalize 阶段会自动去重。
- 配置、设置和服务器列表写入已改用 `AtomicFile`。
- 用户收藏服务器新增空白项不再立刻持久化，避免被存储层过滤后消失。
- 明文 `http://` 服务器列表源已禁用，manifest 已关闭 cleartext traffic。
- TOML 导出已补写 `network_label`。
- `app/build.gradle` 已清理 Gradle 9 空格赋值弃用写法。
- 本轮 release 构建成功；ADB 当前未检测到设备，尚未执行安装和手机侧验证。

## 已完成的重要改动

- 包名从 `cn.qtet.qteasytier` 改为 `cn.moonflow.easytier`。
- 应用名改为 `MoonTier`。
- Launcher 图标已去色。
- 底部导航外框已去掉，只保留顶部一条分界线。
- 服务器官方同步已从单一 Cloudflare DoH 改为多源兜底:
  - `https://dns.alidns.com/resolve`
  - `https://doh.pub/dns-query`
  - `https://cloudflare-dns.com/dns-query`
- 官方服务器同步在手机上实测成功，官方列表显示 2 个:
  - `225284.xyz`
  - `us01.225284.xyz`
- “TXT 下载到用户收藏”的输入框默认空白，不再预填官方源。
- 配置编辑页系统返回/右滑返回已改为保存当前配置。
- “从收藏 / 从官方”已改成多选弹窗，不再点一下直接填整组。
- 列表添加/删除加了 `animateContentSize`。
- 新增收藏服务器名称已支持自动去重，例如 `EasyTier 服务器 1`。

## 审计文档

审计结果在:

```text
C:\Users\12345a\Desktop\moontier-android\AUDIT_FINDINGS.md
```

这份文档是下一轮修复的主要输入。

## 需要优先修复的问题

### 已完成: JNI `collectNetworkInfos()` 越界与 FFI 串行化

文件:

```text
app/src/main/cpp/native_bridge.cpp
```

问题:

- `collect_network_infos()` 返回的 `count` 可能大于传入 buffer 长度。
- 当前 JSON 拼装和 `free_string()` 遍历都按 `count` 走，存在越界读取和非法释放风险。
- FFI 是全局状态接口，建议所有 FFI 调用共享一把 `std::mutex`。

建议修法:

- `#include <algorithm>`
- `#include <mutex>`
- namespace 内加 `std::mutex ffiMutex;`
- `parse_config`、`run_network_instance`、`retain_network_instance`、`collect_network_infos`、`set_tun_fd`、`get_error_msg` 都用同一把锁。
- `collect_network_infos` 后:

```cpp
const int boundedCount = std::min(count, maxLength);
```

后续 JSON 拼装和释放都只遍历 `boundedCount`。

注意:

- 上一轮我尝试打这个补丁时因为工具 cwd 仍在旧 Qt 目录，补丁没有落下。
- 需要在 `C:\Users\12345a\Desktop\moontier-android` 下改。

### 已完成: VPN 授权拒绝后回滚

文件:

```text
app/src/main/java/cn/moonflow/easytier/MainActivity.kt
app/src/main/java/cn/moonflow/easytier/EasyTierController.kt
```

问题:

- native 实例启动后才申请 VPN 权限。
- 用户拒绝 VPN 授权时，目前只清空 `pendingVpnConfig`，不会停止 native，也不会清掉 `vpnSignature`。

建议修法:

- `EasyTierController` 增加公开方法，例如:

```kotlin
fun onVpnPermissionDenied()
```

内部执行停止流程或至少:

- cancel poll
- `NativeEasyTier.stopAllInstances()`
- 清空 `runningConfig`
- 清空 `vpnSignature`
- `state = RuntimeState(statusText = "VPN 授权已取消", logs = ...)`

- `MainActivity` 的 `vpnPermissionLauncher` 在非 OK 时调用该方法。

### 已完成: Always-on VPN 短期处理

文件:

```text
app/src/main/AndroidManifest.xml
app/src/main/java/cn/moonflow/easytier/EasyTierVpnService.kt
```

当前实现不支持 Always-on 恢复，因为 Service 依赖 Activity 注入配置。

建议短期修法:

- 明确声明不支持 Always-on，避免系统尝试恢复空 intent。
- Android `VpnService` 支持的 manifest meta-data 可参考官方文档，常见写法是加入 `android.net.VpnService.SUPPORTS_ALWAYS_ON=false` 的 meta-data。修前请核对当前 Android SDK 常量名。

长期方案:

- 持久化最后运行配置和 VPN JSON，Service 被系统拉起时能恢复。但这轮建议先显式禁用。

### 已完成: 运行态与真实后台状态漂移

文件:

```text
app/src/main/java/cn/moonflow/easytier/EasyTierController.kt
app/src/main/java/cn/moonflow/easytier/EasyTierVpnService.kt
```

建议修法:

- `pollOnce()` 遇到 raw blank 或找不到实例时，不要只 return，应收敛到未运行或计数后收敛。
- Service `onRevoke()` / `onDestroy()` 停掉 native 后，Activity UI 需要通过轮询收敛。
- `release()` 只取消轮询可以保留，但下次 Activity 创建时应尝试重新附着已有 native 状态。

### 已完成: 启动配置不应暗改默认配置

文件:

```text
app/src/main/java/cn/moonflow/easytier/EasyTierController.kt
```

当前:

```kotlin
val normalized = config.copy(isDefault = true)
store.saveConfig(normalized)
store.setDefaultConfig(normalized.id)
```

问题:

- 点击运行会永久把该配置设为默认。

建议修法:

- 启动时不要改 `isDefault`。
- 不要调用 `setDefaultConfig()`。
- 如需保存运行前的配置，只保存原配置内容，不改变默认标志。

### 已完成: `instanceName` 唯一性

文件:

```text
app/src/main/java/cn/moonflow/easytier/ConfigStore.kt
app/src/main/java/cn/moonflow/easytier/Models.kt
```

建议修法:

- `normalizeConfigs()` 中按配置列表去重 `instanceName`。
- 空实例名使用 `MoonTier-${cfg.id.take(8)}`。
- 重名时追加 `-1`、`-2` 等。
- 保存配置和导入 TOML 都走 `saveConfigs()/normalizeConfigs()`。

### 已完成: 用户服务器编辑页数据丢失

文件:

```text
app/src/main/java/cn/moonflow/easytier/MainActivity.kt
app/src/main/java/cn/moonflow/easytier/ConfigStore.kt
```

当前问题:

- “新增收藏”会立刻保存空地址。
- `saveServerFile()` 会过滤空地址。
- `reloadAll()` 后空项消失。

建议修法:

- 服务器页维护本地编辑态。
- 新增空项只更新本地态，不立刻持久化。
- 地址非空时或离开页面/点击保存时再写盘。
- 或者在服务器页增加明确“保存收藏”按钮。

### 已完成: 原子写入

文件:

```text
app/src/main/java/cn/moonflow/easytier/ConfigStore.kt
```

建议修法:

- 新增 helper:

```kotlin
private fun writeTextAtomic(file: File, text: String)
```

流程:

- 写入同目录 `.tmp`
- flush/fsync
- rename/replace 到目标文件

读取失败时:

- 不要立即覆盖目标文件。
- 可返回内存默认值，但保存时要谨慎，避免坏文件被静默清空。

### 已完成: 明文 HTTP 源

文件:

```text
app/src/main/AndroidManifest.xml
app/src/main/java/cn/moonflow/easytier/ConfigStore.kt
```

建议:

- 禁用 `http://` 下载服务器列表。
- `fetchServerText()` 只允许:
  - `https://...`
  - 普通域名 TXT
- Manifest 可把 `usesCleartextTraffic` 改为 `false`。

### 已完成: TOML 往返丢配置标签

文件:

```text
app/src/main/java/cn/moonflow/easytier/TomlCodec.kt
```

建议:

- 导出时写:

```toml
network_label = "..."
```

- 导入时已经优先读 `network_label`，补导出即可。

### 已完成: Gradle DSL 弃用

文件:

```text
app/build.gradle
```

当前 Groovy DSL 仍可构建，但 Gradle 10 会不兼容。

可最后清理，优先级低。

## 构建命令

```powershell
& 'C:\Users\12345a\.gradle\wrapper\dists\gradle-9.3.1-bin\23ovyewtku6u96viwx3xl3oks\gradle-9.3.1\bin\gradle.bat' -p 'C:\Users\12345a\Desktop\moontier-android' assembleRelease --warning-mode summary
```

## 安装命令

```powershell
& 'C:\Android\Sdk\platform-tools\adb.exe' -s AAXL6R5C19000981 install --no-incremental -r -g 'C:\Users\12345a\Desktop\moontier-android\app\build\outputs\apk\release\app-release.apk'
```

如果因 `versionCode` 降级失败:

```powershell
& 'C:\Android\Sdk\platform-tools\adb.exe' -s AAXL6R5C19000981 uninstall cn.moonflow.easytier
& 'C:\Android\Sdk\platform-tools\adb.exe' -s AAXL6R5C19000981 install --no-incremental -r -g 'C:\Users\12345a\Desktop\moontier-android\app\build\outputs\apk\release\app-release.apk'
```

不要卸载旧 Qt 包 `cn.qtet.qteasytier`，除非用户明确要求。

## 设备与验证命令

设备:

```text
AAXL6R5C19000981
```

ADB:

```text
C:\Android\Sdk\platform-tools\adb.exe
```

查看包:

```powershell
& 'C:\Android\Sdk\platform-tools\adb.exe' -s AAXL6R5C19000981 shell "pm list packages | grep -E 'cn\.(qtet\.qteasytier|moonflow\.easytier)'"
```

启动:

```powershell
& 'C:\Android\Sdk\platform-tools\adb.exe' -s AAXL6R5C19000981 shell "am start -n cn.moonflow.easytier/.MainActivity"
```

检查 VPN/tun 残留:

```powershell
& 'C:\Android\Sdk\platform-tools\adb.exe' -s AAXL6R5C19000981 shell "pidof cn.moonflow.easytier; ip addr show tun0 2>/dev/null; ip route show table 1043 2>/dev/null | head -40; ip route show table 1044 2>/dev/null | head -40; dumpsys connectivity | grep -A6 -i 'VPN CONNECTED\|VPN:cn.moonflow.easytier' | head -80"
```

日志:

```powershell
& 'C:\Android\Sdk\platform-tools\adb.exe' -s AAXL6R5C19000981 logcat -d -v time | Select-String -Pattern 'MoonTierVpn|cn.moonflow.easytier|AndroidRuntime|FATAL|UnsatisfiedLinkError' | Select-Object -Last 120
```

截图:

```powershell
& 'C:\Android\Sdk\platform-tools\adb.exe' -s AAXL6R5C19000981 shell screencap -p /sdcard/moontier_check.png
& 'C:\Android\Sdk\platform-tools\adb.exe' -s AAXL6R5C19000981 pull /sdcard/moontier_check.png C:\Users\12345a\Desktop\moontier-android\captures\moontier_check.png
```

## 近期 UI 验证结果

- 底部导航现在只有顶部一条分界线，没有外框。
- 服务器页官方列表能显示 2 个服务器。
- 手动“立即同步公共服务器”能弹出“同步完成”。
- TXT 下载输入框默认空白。
- 配置编辑页已能展开“服务器”区域。
- “从收藏 / 从官方”应继续验证弹窗多选流程。

## 注意事项

- 当前项目已是 Git 仓库，可通过提交回滚本轮改动。
- 旧 Qt 项目仍在 `qtet-android-master`，不要把新原生代码再搬回旧目录。
- `native-android` 旧副本已从 Qt 项目中删除。
- 用户更看重实际手机体验，修完后要 release 编译安装，不要只停在构建成功。
- 用户明确要求 release 包，因为 debug 包签名/安装不可靠。
