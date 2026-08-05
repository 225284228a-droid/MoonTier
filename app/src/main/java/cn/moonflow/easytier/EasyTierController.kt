package cn.moonflow.easytier

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

class EasyTierController(
    private val context: Context,
    private val store: ConfigStore,
    private val requestVpn: (String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollJob: Job? = null
    private var runningConfig: NetworkConfig? = null
    private var vpnSignature = ""
    private var lastLogKey = ""
    private var missedPolls = 0
    private var stalledPolls = 0
    private val _pollIntervalMs = kotlinx.coroutines.flow.MutableStateFlow(30000L)
    private var directPublicIps = emptySet<String>()
    @Volatile
    var configServerRunning: Boolean = false
        private set
    @Volatile
    var configServerPending: Boolean = false
        private set
    @Volatile
    private var configServerAttachedKey: String = ""

    var state by mutableStateOf(RuntimeState())
        private set

    private val configServerCallback = NativeEasyTier.ConfigServerCallback { eventJson ->
        val event = runCatching { JSONObject(eventJson) }.getOrNull() ?: return@ConfigServerCallback
        val instanceName = event.optString("instance_name", event.optString("name", "")).trim()
        if (instanceName.isBlank()) return@ConfigServerCallback
        val runtimeConfig = extractConfigServerRuntimeConfig(event, instanceName)
        val instanceKey = configServerInstanceKey(event, instanceName, runtimeConfig)

        scope.launch {
            if (!configServerRunning) return@launch

            val shouldAttach = configServerPending || configServerAttachedKey != instanceKey || pollJob == null
            if (shouldAttach) {
                if (!EasyTierVpnService.tryAttachFd(instanceName)) return@launch
                configServerPending = false
                configServerAttachedKey = instanceKey
            }

            val changed = runningConfig != runtimeConfig || pollJob == null || vpnSignature.isEmpty()
            if (changed) {
                runningConfig = runtimeConfig
                directPublicIps = withContext(Dispatchers.IO) { directPublicIpsFor(runtimeConfig, store) }
                vpnSignature = ""
                lastLogKey = ""
                missedPolls = 0
                stalledPolls = 0
                pollJob?.cancel()
                pollJob = scope.launch { pollLoop() }
            }

            state = state.copy(
                running = true,
                starting = false,
                stopping = false,
                runningConfigId = runtimeConfig.id,
                statusText = "配置服务器实例运行中: $instanceName",
                logs = (state.logs + "配置服务器实例已挂载: $instanceName").takeLast(240)
            )
        }
    }

    init {
        NativeEasyTier.setConfigServerCallback(configServerCallback)
    }

    fun start(config: NetworkConfig) {
        if (state.starting || state.stopping) return
        state = state.copy(starting = true, statusText = "正在启动 ${config.displayName}")
        scope.launch {
            val previousConfigId = state.runningConfigId
            if (configServerRunning) {
                withContext(Dispatchers.IO) { NativeEasyTier.stopConfigServerClient() }
                configServerRunning = false
                configServerPending = false
                configServerAttachedKey = ""
            }
            if (state.running) stop(wait = true)

            val normalized = config
            val runtimeConfig = normalized.withRuntimeHostname(context)
            runningConfig = runtimeConfig
            directPublicIps = withContext(Dispatchers.IO) { directPublicIpsFor(runtimeConfig, store) }
            vpnSignature = ""
            lastLogKey = ""
            missedPolls = 0
            stalledPolls = 0
            state = RuntimeState(starting = true, statusText = "正在启动 ${normalized.displayName}")

            val toml = TomlCodec.build(runtimeConfig)
            val parseCode = withContext(Dispatchers.IO) { NativeEasyTier.parseConfig(toml) }
            if (parseCode != 0) {
                failStart("配置解析失败: ${NativeEasyTier.getLastError().orEmpty()}")
                return@launch
            }

            val code = withContext(Dispatchers.IO) { NativeEasyTier.runNetworkInstance(toml) }
            if (code != 0) {
                failStart("启动失败: ${NativeEasyTier.getLastError().orEmpty()}")
                return@launch
            }

            state = state.copy(
                running = true,
                starting = false,
                runningConfigId = runtimeConfig.id,
                statusText = "实例运行中: ${normalized.displayName}",
                logs = listOf("EasyTier 日志级别: info", "网络实例启动成功")
            )
            if (previousConfigId.isNotBlank() && previousConfigId != runtimeConfig.id) {
                store.updateConfigRunningState(previousConfigId, false)
            }
            store.updateConfigRunningState(runtimeConfig.id, true)
            pollJob?.cancel()
            pollJob = scope.launch { pollLoop() }
        }
    }

    fun stop() {
        scope.launch { stop(wait = false) }
    }

    fun attachIfRunning(configs: List<NetworkConfig>) {
        if (state.running || state.starting || state.stopping || pollJob != null) return
        scope.launch {
            val raw = withContext(Dispatchers.IO) { NativeEasyTier.collectNetworkInfos(8).orEmpty() }
            val outer = runCatching { JSONObject(raw) }.getOrNull() ?: return@launch
            val config = configs.firstOrNull { outer.optJSONObject(it.instanceName) != null } ?: return@launch
            runningConfig = config
            directPublicIps = withContext(Dispatchers.IO) { directPublicIpsFor(config, store) }
            vpnSignature = ""
            lastLogKey = ""
            missedPolls = 0
            stalledPolls = 0
            state = RuntimeState(
                running = true,
                runningConfigId = config.id,
                statusText = "正在恢复运行状态",
                logs = listOf("已附着到现有 EasyTier 实例")
            )
            store.updateConfigRunningState(config.id, true)
            pollJob = scope.launch { pollLoop() }
        }
    }

    fun onVpnPermissionDenied() {
        scope.launch {
            if (configServerRunning || configServerPending) {
                withContext(Dispatchers.IO) { NativeEasyTier.stopConfigServerClient() }
                configServerRunning = false
                configServerPending = false
                configServerAttachedKey = ""
            }
            stopRuntime(
                statusText = "VPN 授权已取消",
                logMessage = "VPN 授权已取消，网络实例已停止",
                wait = false
            )
        }
    }

    fun connectConfigServer(
        url: String,
        hostname: String,
        machineId: String,
        secureMode: Boolean,
        onResult: (Int, String?) -> Unit
    ) {
        scope.launch {
            val resolvedUrl = resolveConfigServerUrl(url)
            if (resolvedUrl.isBlank()) {
                onResult(-1, "服务器 URL 不能为空。")
                return@launch
            }

            val code = withContext(Dispatchers.IO) {
                NativeEasyTier.startConfigServerClient(
                    resolvedUrl,
                    hostname.trim(),
                    machineId.trim(),
                    secureMode
                )
            }

            if (code != 0) {
                onResult(code, NativeEasyTier.getLastError().orEmpty().ifBlank { "连接失败。" })
                return@launch
            }
            configServerRunning = true
            configServerPending = true
            configServerAttachedKey = ""

            val vpnJson = JSONObject()
                .put("instanceName", "")
                .put("address", "10.0.0.2")
                .put("prefixLength", 24)
                .put("mtu", 1400)
                .put("dns", "")
                .put("routes", JSONArray())
                .put("configServerMode", true)
                .toString()

            requestVpn(vpnJson)
            onResult(0, null)
        }
    }

    fun disconnectConfigServer(onResult: (Int, String?) -> Unit) {
        scope.launch {
            val code = withContext(Dispatchers.IO) {
                NativeEasyTier.stopConfigServerClient()
            }
            if (code != 0) {
                onResult(code, NativeEasyTier.getLastError().orEmpty().ifBlank { "断开失败。" })
            } else {
                configServerRunning = false
                configServerPending = false
                configServerAttachedKey = ""
                onResult(0, null)
            }
        }
    }

    private suspend fun stop(wait: Boolean) {
        if (state.running || state.starting || state.stopping) {
            state = state.copy(stopping = true, statusText = "正在停止")
        }
        stopRuntime(
            statusText = "EasyTier 未运行",
            logMessage = "网络实例已停止",
            wait = wait
        )
    }

    private suspend fun stopRuntime(statusText: String, logMessage: String, wait: Boolean) {
        val stoppedConfigId = state.runningConfigId.ifBlank { runningConfig?.id.orEmpty() }
        val stoppedInstanceName = runningConfig?.instanceName.orEmpty()
        val poll = pollJob
        pollJob = null
        poll?.cancel()
        // 等待轮询循环完全退出，避免与 FFI 互斥锁竞争
        runCatching { kotlinx.coroutines.withTimeout(2000L) { poll?.join() } }
        val stopIntent = Intent(context, EasyTierVpnService::class.java)
            .setAction(EasyTierVpnService.ACTION_STOP)
        runCatching { context.startService(stopIntent) }
        context.stopService(Intent(context, EasyTierVpnService::class.java))
        withContext(NonCancellable) {
            runCatching {
                withTimeout(3000L) {
                    withContext(Dispatchers.IO) { retainOtherInstances(stoppedInstanceName) }
                }
            }
        }
        runningConfig = null
        configServerAttachedKey = ""
        vpnSignature = ""
        missedPolls = 0
        stalledPolls = 0
        directPublicIps = emptySet()
        if (stoppedConfigId.isNotBlank()) {
            store.updateConfigRunningState(stoppedConfigId, false)
        }
        state = RuntimeState(statusText = statusText, logs = (state.logs + logMessage).takeLast(240))
        if (wait) delay(180)
    }

    private fun resolveConfigServerUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.contains("://")) return trimmed
        val token = java.net.URLEncoder.encode(trimmed, Charsets.UTF_8.name()).replace("+", "%20")
        return "tcp://ethd.ruixuan.online:22020/$token"
    }

    private fun extractConfigServerRuntimeConfig(event: JSONObject, instanceName: String): NetworkConfig {
        val candidate = extractConfigServerPayload(event, depth = 0)
        if (candidate != null) {
            return candidate.copy(
                id = candidate.id.ifBlank { configServerCacheId(instanceName) },
                instanceName = instanceName.ifBlank { candidate.instanceName },
                isDefault = false
            )
        }

        val hostname = event.optString("hostname").trim()
        val networkName = event.optString("network_name").trim()
        val networkSecret = event.optString("network_secret").trim()
        val peerUrls = event.optJSONArray("servers")?.toStringList().orEmpty()
            .ifEmpty { event.optJSONArray("peer_urls")?.toStringList().orEmpty() }
        val stunServers = event.optJSONArray("stun_servers")?.toStringList().orEmpty()
        val listenerUrls = event.optJSONArray("listeners")?.toStringList().orEmpty()
            .ifEmpty { event.optJSONArray("listen_addresses")?.toStringList().orEmpty() }
        val proxyCidrs = event.optJSONArray("proxy_networks")?.toStringList().orEmpty()
        val routes = event.optJSONArray("routes")?.toStringList().orEmpty()
            .ifEmpty { event.optJSONArray("custom_routes")?.toStringList().orEmpty() }
        val exitNodes = event.optJSONArray("exit_nodes")?.toStringList().orEmpty()

        return NetworkConfig(
            id = configServerCacheId(instanceName),
            label = event.optString("label").trim().ifBlank { networkName.ifBlank { instanceName.ifBlank { "配置服务器缓存" } } },
            instanceName = instanceName.ifBlank { "MoonTier-ConfigServer" },
            hostname = hostname,
            networkName = networkName.ifBlank { "default" },
            networkSecret = networkSecret,
            peerUrls = peerUrls,
            stunServers = stunServers,
            listenerUrls = listenerUrls,
            proxyCidrs = proxyCidrs,
            enableManualRoutes = routes.isNotEmpty() || event.has("custom_routes"),
            routes = routes,
            exitNodes = exitNodes,
            enableRelayNetworkWhitelist = event.optBoolean("foreign_network_whitelist_enabled", false),
            relayNetworkWhitelist = event.optJSONArray("foreign_network_whitelist")?.toStringList().orEmpty(),
            proxyForwardBySystem = event.optBoolean("system_forwarding", false),
            enableExitNode = event.optBoolean("enable_exit_node", false),
            enableMagicDns = event.optBoolean("accept_dns", false),
            enablePrivateMode = event.optBoolean("private_mode", true),
            enableEncryption = event.optBoolean("enable_encryption", true),
            mtu = event.optInt("mtu", 0),
            defaultProtocol = event.optString("default_protocol", "tcp"),
            encryptionAlgorithm = event.optString("encryption_algorithm", "aes-gcm"),
            aclToml = event.optString("acl_toml", ""),
            isDefault = false
        )
    }

    private fun configServerCacheId(instanceName: String): String =
        "config-server:${instanceName.ifBlank { "default" }}"

    private fun configServerInstanceKey(event: JSONObject, instanceName: String, runtimeConfig: NetworkConfig? = null): String {
        val explicit = listOf(
            event.optString("instance_id"),
            event.optString("inst_id"),
            event.optString("config_id"),
            event.optString("configId")
        ).firstOrNull { it.isNotBlank() }
        if (!explicit.isNullOrBlank()) return explicit.trim()

        val runtimeFingerprint = runtimeConfig?.toJson()?.toString().orEmpty()
        if (runtimeFingerprint.isNotBlank()) {
            return "${instanceName.ifBlank { "default" }}:${runtimeFingerprint.hashCode().toString(16)}"
        }

        val eventFingerprint = listOf(
            event.optJSONObject("config")?.toString(),
            event.optString("config"),
            event.optString("cfg"),
            event.optString("config_toml"),
            event.optString("configToml")
        ).firstOrNull { it?.isNotBlank() == true }.orEmpty()
        if (eventFingerprint.isNotBlank()) {
            return "${instanceName.ifBlank { "default" }}:${eventFingerprint.hashCode().toString(16)}"
        }

        return instanceName.ifBlank { "default" }
    }

    private fun extractConfigServerPayload(value: Any?, depth: Int): NetworkConfig? {
        if (depth > 4 || value == null) return null
        return when (value) {
            is JSONObject -> {
                parseConfigServerObject(value)?.let { return it }
                val keys = listOf(
                    "config",
                    "cfg",
                    "config_json",
                    "configJson",
                    "config_data",
                    "configData",
                    "config_toml",
                    "configToml",
                    "payload_json",
                    "payloadJson",
                    "payload"
                )
                for (key in keys) {
                    val child = value.opt(key) ?: continue
                    extractConfigServerPayload(child, depth + 1)?.let { return it }
                }
                null
            }
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    extractConfigServerPayload(value.opt(i), depth + 1)?.let { return it }
                }
                null
            }
            is String -> {
                val text = value.trim()
                when {
                    text.isBlank() -> null
                    text.startsWith("{") -> runCatching { JSONObject(text) }.getOrNull()
                        ?.let { extractConfigServerPayload(it, depth + 1) }
                    text.startsWith("[") -> runCatching { JSONArray(text) }.getOrNull()
                        ?.let { extractConfigServerPayload(it, depth + 1) }
                    else -> runCatching { TomlCodec.parse(text) }.getOrNull()
                }
            }
            else -> null
        }
    }

    private fun parseConfigServerObject(obj: JSONObject): NetworkConfig? {
        val hasConfigFields = obj.has("network_name") || obj.has("network_secret") || obj.has("exit_nodes") || obj.has("proxy_networks") || obj.has("servers") || obj.has("peer_urls") || obj.has("stun_servers") || obj.has("listeners") || obj.has("listen_addresses") || obj.has("acl_toml") || obj.has("hostname") || obj.has("config_id") || obj.has("instance_id") || obj.has("custom_routes") || obj.has("routes")
        if (!hasConfigFields) return null

        val instanceName = obj.optString("instance_name", obj.optString("name", "")).trim()
        val networkName = obj.optString("network_name").trim()
        val hostname = obj.optString("hostname").trim()
        val peerUrls = obj.optJSONArray("servers")?.toStringList().orEmpty()
            .ifEmpty { obj.optJSONArray("peer_urls")?.toStringList().orEmpty() }
        val stunServers = obj.optJSONArray("stun_servers")?.toStringList().orEmpty()
        val listenerUrls = obj.optJSONArray("listeners")?.toStringList().orEmpty()
            .ifEmpty { obj.optJSONArray("listen_addresses")?.toStringList().orEmpty() }
        val proxyCidrs = obj.optJSONArray("proxy_networks")?.toStringList().orEmpty()
        val routes = obj.optJSONArray("routes")?.toStringList().orEmpty()
            .ifEmpty { obj.optJSONArray("custom_routes")?.toStringList().orEmpty() }
        val exitNodes = obj.optJSONArray("exit_nodes")?.toStringList().orEmpty()
        return NetworkConfig(
            id = obj.optString("config_id", obj.optString("instance_id", "")).trim(),
            label = obj.optString("label").trim().ifBlank { networkName.ifBlank { instanceName.ifBlank { "配置服务器缓存" } } },
            instanceName = instanceName.ifBlank { "MoonTier-ConfigServer" },
            hostname = hostname,
            networkName = networkName.ifBlank { "default" },
            networkSecret = obj.optString("network_secret").trim(),
            peerUrls = peerUrls,
            stunServers = stunServers,
            listenerUrls = listenerUrls,
            proxyCidrs = proxyCidrs,
            enableManualRoutes = routes.isNotEmpty() || obj.has("custom_routes"),
            routes = routes,
            exitNodes = exitNodes,
            enableRelayNetworkWhitelist = obj.optBoolean("foreign_network_whitelist_enabled", false),
            relayNetworkWhitelist = obj.optJSONArray("foreign_network_whitelist")?.toStringList().orEmpty(),
            proxyForwardBySystem = obj.optBoolean("system_forwarding", false),
            enableExitNode = obj.optBoolean("enable_exit_node", false),
            enableMagicDns = obj.optBoolean("accept_dns", false),
            enablePrivateMode = obj.optBoolean("private_mode", true),
            enableEncryption = obj.optBoolean("enable_encryption", true),
            mtu = obj.optInt("mtu", 0),
            defaultProtocol = obj.optString("default_protocol", "tcp"),
            encryptionAlgorithm = obj.optString("encryption_algorithm", "aes-gcm"),
            aclToml = obj.optString("acl_toml", ""),
            isDefault = false
        )
    }

    fun release() {
        pollJob?.cancel()
    }

    private fun failStart(message: String) {
        val configId = runningConfig?.id.orEmpty()
        pollJob?.cancel()
        pollJob = null
        runningConfig = null
        vpnSignature = ""
        missedPolls = 0
        stalledPolls = 0
        if (configId.isNotBlank()) {
            store.updateConfigRunningState(configId, false)
        }
        state = RuntimeState(statusText = "启动失败", logs = listOf(message))
    }

    private suspend fun pollLoop() {
        _pollIntervalMs.collectLatest { interval ->
            while (true) {
                val config = runningConfig ?: return@collectLatest
                pollOnce(config)
                delay(interval)
            }
        }
    }

    private suspend fun pollOnce(config: NetworkConfig) {
        val raw = withContext(Dispatchers.IO) { NativeEasyTier.collectNetworkInfos(8).orEmpty() }
        if (raw.isBlank()) {
            if (configServerRunning) {
                configServerPending = true
                configServerAttachedKey = ""
                missedPolls = 0
                state = state.copy(
                    running = true,
                    starting = false,
                    stopping = false,
                    statusText = "等待配置服务器下发新配置",
                    logs = (state.logs + "配置服务器等待新配置").takeLast(240)
                )
            } else {
                convergeMissingInstance("状态读取为空，网络实例可能已退出")
            }
            return
        }

        val outer = runCatching { JSONObject(raw) }.getOrNull()
        if (outer == null) {
            if (configServerRunning) {
                configServerPending = true
                configServerAttachedKey = ""
                missedPolls = 0
                state = state.copy(
                    running = true,
                    starting = false,
                    stopping = false,
                    statusText = "等待配置服务器下发新配置",
                    logs = (state.logs + "配置服务器状态解析失败，等待新配置").takeLast(240)
                )
            } else {
                convergeMissingInstance("状态 JSON 解析失败，网络实例可能已退出")
            }
            return
        }
        val info = outer.optJSONObject(config.instanceName) ?: firstJsonObject(outer)
        if (info == null) {
            if (configServerRunning) {
                configServerPending = true
                configServerAttachedKey = ""
                missedPolls = 0
                state = state.copy(
                    running = true,
                    starting = false,
                    stopping = false,
                    statusText = "等待配置服务器下发新配置",
                    logs = (state.logs + "当前实例已结束，等待配置服务器新配置").takeLast(240)
                )
            } else {
                convergeMissingInstance("未找到运行实例，网络实例已退出")
            }
            return
        }
        missedPolls = 0
        stalledPolls = 0
        val localCidr = extractLocalCidr(info)
        val proxyCidrs = collectPeerProxyCidrs(info)
        val nodes = parseNodes(info)
        val logs = collectNewLogs(info)

        var next = state.copy(
            running = true,
            starting = false,
            stopping = false,
            runningConfigId = config.id,
            statusText = if (localCidr.isBlank()) "等待虚拟 IP" else "已连接: $localCidr",
            localCidr = localCidr,
            nodes = nodes
        )
        if (logs.isNotEmpty()) next = next.copy(logs = (next.logs + logs).takeLast(240))
        state = next
        if (state.running && localCidr.isBlank() && nodes.isEmpty()) {
            stalledPolls++
            if (stalledPolls >= 3) {
                if (configServerRunning) {
                    stalledPolls = 0
                    configServerPending = true
                    configServerAttachedKey = ""
                    state = state.copy(
                        statusText = "等待配置服务器下发新配置",
                        logs = (state.logs + "配置服务器实例已结束，等待新配置").takeLast(240)
                    )
                } else {
                    convergeMissingInstance("网络实例无响应")
                }
                return
            }
        } else {
            stalledPolls = 0
        }

        if (localCidr.isNotBlank() && !config.noTun) {
            val settings = store.loadSettings()
            val configuredExitNodes = config.exitNodes.cleanItems()
            val reachableExitNodes = configuredExitNodes.filter { it in collectRouteIps(info) }
            val exitNodeRoutesActive = settings.exitNodeAutoRoutes && reachableExitNodes.isNotEmpty()
            val runtimeText = (state.logs + logs + state.statusText).joinToString("\n")
            val nextDirectPublicIps = if (exitNodeRoutesActive) {
                collectDirectPublicIpsForExitRoutes(config, store, runtimeText)
            } else {
                directPublicIpsFor(config, store)
            }
            if (nextDirectPublicIps != directPublicIps) {
                directPublicIps = nextDirectPublicIps
                vpnSignature = ""
            }
            val vpnJson = buildVpnConfigJson(
                config = config,
                cidr = localCidr,
                peerProxyCidrs = proxyCidrs,
                directPublicIps = nextDirectPublicIps,
                exitNodeAutoRoutes = exitNodeRoutesActive,
                exitNodeConfiguredCount = configuredExitNodes.size,
                exitNodeReachableCount = reachableExitNodes.size
            )
            if (vpnJson != vpnSignature) {
                vpnSignature = vpnJson
                val routeCount = runCatching { JSONObject(vpnJson).optJSONArray("routes")?.length() ?: 0 }.getOrDefault(0)
                val exitRouteState = when {
                    exitNodeRoutesActive -> "出口节点自动路由已开启，可达 ${reachableExitNodes.size}/${configuredExitNodes.size}"
                    settings.exitNodeAutoRoutes && configuredExitNodes.isNotEmpty() -> "出口节点未可达，暂不下发公网路由"
                    configuredExitNodes.isNotEmpty() -> "出口节点已配置，自动路由未开启"
                    else -> "未配置出口节点"
                }
                state = state.copy(
                    logs = (state.logs + "VPN 路由下发: $routeCount 条，$exitRouteState").takeLast(240)
                )
                requestVpn(vpnJson)
            }
        }
    }

    private suspend fun convergeMissingInstance(message: String) {
        if (configServerRunning) {
            configServerPending = true
            configServerAttachedKey = ""
            missedPolls = 0
            stalledPolls = 0
            state = state.copy(
                running = true,
                starting = false,
                stopping = false,
                statusText = "等待配置服务器下发新配置",
                logs = (state.logs + message).takeLast(240)
            )
            return
        }
        missedPolls += 1
        if (missedPolls < 2) return
        val stoppedConfig = runningConfig
        val poll = pollJob
        pollJob = null
        runningConfig = null
        vpnSignature = ""
        missedPolls = 0
        stalledPolls = 0
        directPublicIps = emptySet()
        // 停止 VPN 服务
        val stopIntent = Intent(context, EasyTierVpnService::class.java)
            .setAction(EasyTierVpnService.ACTION_STOP)
        runCatching { context.startService(stopIntent) }
        context.stopService(Intent(context, EasyTierVpnService::class.java))
        // 等待轮询循环退出后释放原生实例
        poll?.cancel()
        runCatching { kotlinx.coroutines.withTimeout(2000L) { poll?.join() } }
        withContext(NonCancellable) {
            runCatching {
                withTimeout(3000L) {
                    withContext(Dispatchers.IO) { retainOtherInstances(stoppedConfig?.instanceName.orEmpty()) }
                }
            }
        }
        if (stoppedConfig != null) {
            store.updateConfigRunningState(stoppedConfig.id, false)
        }
        state = RuntimeState(statusText = "实例已退出", logs = (state.logs + message).takeLast(240))
    }

    private fun retainOtherInstances(excludedInstanceName: String) {
        if (excludedInstanceName.isBlank()) {
            NativeEasyTier.stopAllInstances()
            return
        }
        val raw = NativeEasyTier.collectNetworkInfos(16).orEmpty()
        val outer = runCatching { JSONObject(raw) }.getOrNull()
        if (outer == null) {
            NativeEasyTier.stopAllInstances()
            return
        }
        val retain = ArrayList<String>()
        val keys = outer.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            if (name != excludedInstanceName && outer.optJSONObject(name) != null) {
                retain += name
            }
        }
        if (retain.isEmpty()) {
            NativeEasyTier.stopAllInstances()
        } else {
            NativeEasyTier.retainNetworkInstance(retain.toTypedArray())
        }
    }

    private fun firstJsonObject(obj: JSONObject): JSONObject? {
        val keys = obj.keys()
        while (keys.hasNext()) {
            val value = obj.opt(keys.next())
            if (value is JSONObject) return value
        }
        return null
    }

    private fun collectRouteIps(info: JSONObject): Set<String> {
        val out = LinkedHashSet<String>()
        val routes = info.optJSONArray("routes") ?: JSONArray()
        for (i in 0 until routes.length()) {
            val route = routes.optJSONObject(i) ?: continue
            val addr = route.optJSONObject("ipv4_addr")
                ?.optJSONObject("address")
                ?.optLong("addr", 0L) ?: 0L
            if (addr != 0L) out += Ipv4.intToAddress(addr)
        }
        return out
    }

    private fun extractLocalCidr(info: JSONObject): String {
        val ipv4 = info.optJSONObject("my_node_info")
            ?.optJSONObject("virtual_ipv4")
            ?: return ""
        val addr = ipv4.optJSONObject("address")?.optLong("addr", 0L) ?: 0L
        if (addr == 0L) return ""
        val prefix = ipv4.optInt("network_length", 24).coerceIn(1, 32)
        return "${Ipv4.intToAddress(addr)}/$prefix"
    }

    private fun collectPeerProxyCidrs(info: JSONObject): Set<String> {
        val out = LinkedHashSet<String>()
        val routes = info.optJSONArray("routes") ?: JSONArray()
        for (i in 0 until routes.length()) {
            val item = routes.optJSONObject(i) ?: continue
            val cidrs = item.optJSONArray("proxy_cidrs") ?: JSONArray()
            for (j in 0 until cidrs.length()) {
                val cidr = cidrs.optString(j).trim()
                if (cidr.isNotBlank()) out += cidr
            }
        }
        return out
    }

    private fun parseNodes(info: JSONObject): List<NodeInfo> {
        val out = ArrayList<NodeInfo>()
        val my = info.optJSONObject("my_node_info")
        my?.let {
            val ipv4 = extractLocalCidr(info).substringBefore("/")
            out += NodeInfo(
                hostname = it.optString("hostname").ifBlank { "本机" },
                ip = ipv4,
                role = "本机",
                latencyMs = 0,
                local = true
            )
        }

        val routes = info.optJSONArray("routes") ?: JSONArray()
        for (i in 0 until routes.length()) {
            val route = routes.optJSONObject(i) ?: continue
            val addr = route.optJSONObject("ipv4_addr")
                ?.optJSONObject("address")
                ?.optLong("addr", 0L) ?: 0L
            val ip = if (addr == 0L) "" else Ipv4.intToAddress(addr)
            val isServer = route.optJSONObject("feature_flag")?.optBoolean("is_public_server", false) == true
            val cost = route.optInt("cost", 1)
            val connectionType = when {
                isServer -> "服务器"
                cost > 1 -> "中继($cost)"
                else -> "直连"
            }
            out += NodeInfo(
                hostname = route.optString("hostname").ifBlank { if (isServer) "公共服务器" else connectionType },
                ip = ip,
                role = connectionType,
                latencyMs = route.optInt("path_latency", -1),
                local = false
            )
        }
        return out.distinctBy { "${it.local}-${it.ip}-${it.hostname}" }
    }

    private fun collectNewLogs(info: JSONObject): List<String> {
        val events = info.optJSONArray("events") ?: JSONArray()
        val parsed = ArrayList<Pair<String, String>>()
        for (i in 0 until events.length()) {
            val item = events.optString(i)
            val obj = runCatching { JSONObject(item) }.getOrNull() ?: continue
            val time = obj.optString("time")
            val event = obj.optJSONObject("event")
            val message = event?.optString("msg").orEmpty()
                .ifBlank { event?.optString("message").orEmpty() }
                .ifBlank { event?.toString().orEmpty() }
            if (time.isNotBlank() && message.isNotBlank()) parsed += time to message
        }

        val fresh = parsed
            .sortedBy { it.first }
            .filter { lastLogKey.isBlank() || it.first > lastLogKey }
        if (fresh.isNotEmpty()) lastLogKey = fresh.last().first
        return fresh.map { it.second }
    }

    private fun NetworkConfig.withRuntimeHostname(context: Context): NetworkConfig {
        if (hostname.isNotBlank() && !hostname.equals("localhost", ignoreCase = true)) return this
        return copy(hostname = defaultRuntimeHostname(context, id))
    }

    private fun defaultRuntimeHostname(context: Context, id: String): String {
        val candidates = listOf(
            runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull(),
            runCatching { Settings.Secure.getString(context.contentResolver, "bluetooth_name") }.getOrNull(),
            Build.MODEL,
            Build.DEVICE
        )
        return candidates
            .map { it.orEmpty().trim() }
            .firstOrNull { it.isNotBlank() && !it.equals("localhost", ignoreCase = true) }
            ?: "Android-${id.take(8)}"
    }
    fun updatePollingConditions(isForeground: Boolean, expanded: Boolean) {
        _pollIntervalMs.value = when {
            !isForeground -> 300000L
            isForeground && expanded -> 500L
            else -> 30000L
        }
    }

}
