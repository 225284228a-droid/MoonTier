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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private var directPublicIps = emptySet<String>()

    var state by mutableStateOf(RuntimeState())
        private set

    fun start(config: NetworkConfig) {
        if (state.starting || state.stopping) return
        state = state.copy(starting = true, statusText = "正在启动 ${config.displayName}")
        scope.launch {
            runCatching {
                if (state.running) stop(wait = true)

                val normalized = config
                val runtimeConfig = normalized.withRuntimeHostname(context)
                store.saveConfig(normalized)
                runningConfig = runtimeConfig
                directPublicIps = withContext(Dispatchers.IO) { directPublicIpsFor(runtimeConfig, store) }
                vpnSignature = ""
                lastLogKey = ""
                missedPolls = 0

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
                pollJob?.cancel()
                pollJob = scope.launch { pollLoop(runtimeConfig) }
            }.onFailure {
                failStart("启动异常: ${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    fun stop() {
        scope.launch { stop(wait = false) }
    }

    fun attachIfRunning(configs: List<NetworkConfig>) {
        if (state.running || state.starting || state.stopping || pollJob != null) return
        scope.launch {
            val raw = withContext(Dispatchers.IO) { NativeEasyTier.collectNetworkInfos(32).orEmpty() }
            val outer = runCatching { JSONObject(raw) }.getOrNull() ?: return@launch
            val config = configs.firstOrNull { outer.optJSONObject(it.instanceName) != null } ?: return@launch
            runningConfig = config
            directPublicIps = withContext(Dispatchers.IO) { directPublicIpsFor(config, store) }
            vpnSignature = ""
            lastLogKey = ""
            missedPolls = 0
            state = RuntimeState(
                running = true,
                runningConfigId = config.id,
                statusText = "正在恢复运行状态",
                logs = listOf("已附着到现有 EasyTier 实例")
            )
            pollJob = scope.launch { pollLoop(config) }
        }
    }

    fun onVpnPermissionDenied() {
        scope.launch {
            stopRuntime(
                statusText = "VPN 授权已取消",
                logMessage = "VPN 授权已取消，网络实例已停止",
                wait = false
            )
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
        pollJob?.cancel()
        pollJob = null
        val stopIntent = Intent(context, EasyTierVpnService::class.java)
            .setAction(EasyTierVpnService.ACTION_STOP)
        runCatching { context.startService(stopIntent) }
        context.stopService(Intent(context, EasyTierVpnService::class.java))
        withContext(Dispatchers.IO) { NativeEasyTier.stopAllInstances() }
        runningConfig = null
        vpnSignature = ""
        missedPolls = 0
        directPublicIps = emptySet()
        appendLog(logMessage)
        state = state.copy(statusText = statusText)
        if (wait) delay(180)
    }

    fun release() {
        pollJob?.cancel()
        pollJob = null
        runCatching { NativeEasyTier.stopAllInstances() }
        runningConfig = null
        vpnSignature = ""
        missedPolls = 0
        directPublicIps = emptySet()
    }

    private fun failStart(message: String) {
        pollJob?.cancel()
        pollJob = null
        runningConfig = null
        vpnSignature = ""
        missedPolls = 0
        state = RuntimeState(statusText = "启动失败", logs = listOf(message))
    }
    private fun appendLog(message: String) {
        state = state.copy(logs = (state.logs + message).takeLast(240))
    }


    private suspend fun pollLoop(config: NetworkConfig) {
        while (true) {
            pollOnce(config)
            delay(1800)
        }
    }

    private suspend fun pollOnce(config: NetworkConfig) {
        val raw = withContext(Dispatchers.IO) { NativeEasyTier.collectNetworkInfos(32).orEmpty() }
        if (raw.isBlank()) {
            convergeMissingInstance("状态读取为空，网络实例可能已退出")
            return
        }

        val outer = runCatching { JSONObject(raw) }.getOrNull()
        if (outer == null) {
            convergeMissingInstance("状态 JSON 解析失败，网络实例可能已退出")
            return
        }
        val info = outer.optJSONObject(config.instanceName) ?: firstJsonObject(outer)
        if (info == null) {
            convergeMissingInstance("未找到运行实例，网络实例已退出")
            return
        }
        missedPolls = 0
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

        if (localCidr.isNotBlank() && !config.noTun) {
            val settings = store.loadSettings()
            val configuredExitNodes = config.exitNodes.cleanItems()
            val reachableExitNodes = configuredExitNodes.filter { it in collectRouteIps(info) }
            val exitNodeRoutesActive = settings.exitNodeAutoRoutes && reachableExitNodes.isNotEmpty()
            val vpnJson = buildVpnConfigJson(
                config = config,
                cidr = localCidr,
                peerProxyCidrs = proxyCidrs,
                directPublicIps = directPublicIps,
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
                appendLog("VPN ????: $routeCount ??$exitRouteState")
                requestVpn(vpnJson)
            }
        }
    }

    private suspend fun convergeMissingInstance(message: String) {
        missedPolls += 1
        if (missedPolls < 2) return
        val job = pollJob
        runningConfig = null
        vpnSignature = ""
        missedPolls = 0
        directPublicIps = emptySet()
        withContext(Dispatchers.IO) { NativeEasyTier.stopAllInstances() }
        appendLog(message)
        state = state.copy(statusText = "实例已退出")
        pollJob = null
        job?.cancel()
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
            out += NodeInfo(
                hostname = route.optString("hostname").ifBlank { if (isServer) "公共服务器" else "节点" },
                ip = ip,
                role = if (isServer) "服务器" else "节点",
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
}
