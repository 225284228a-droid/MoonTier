package cn.moonflow.easytier

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

class EasyTierController private constructor(private val context: Context) {
    private val store = ConfigStore(context)
    private var service: EasyTierRuntimeService? = null
    private var vpnService: EasyTierVpnService? = null
    private var scope: CoroutineScope? = null
    private var actions = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var initialized = false
    private var foreground = true
    private var refreshQueued = false
    private var serverActive = false
    private var serverUrl = ""
    private var serverGeneration = 0L
    private var persistedSnapshot = ""
    private var configs = emptyList<FfiRuntimeConfig>()
    private val vpnConfigs = mutableMapOf<String, Pair<String, NetworkConfig>>()
    private val endpointIps = mutableMapOf<String, Set<String>>()
    private val resolvingHosts = mutableSetOf<String>()
    private val resolvedAt = mutableMapOf<String, Long>()
    private val gate = VpnSessionGate()
    private var attachedGeneration = -1L
    private var requestedGeneration = -1L
    private var deniedInstanceId = ""
    private var lastNotification = ""
    private var operationError = ""
    private var stopCompletion: CompletableDeferred<Unit>? = null
    private val tunOperations = Mutex()
    private var nativeTunOwner: Pair<String, Long>? = null

    var state by mutableStateOf(RuntimeState())
        private set
    var instances by mutableStateOf(emptyList<FfiInstanceState>())
        private set
    var configServerState by mutableStateOf(RootConfigServerState())
        private set
    var pendingVpnConfig by mutableStateOf<String?>(null)
        private set
    var vpnPermissionRequired by mutableStateOf(false)
        private set

    fun start(config: NetworkConfig) = dispatch(Intent(context, EasyTierRuntimeService::class.java)
        .setAction(EasyTierRuntimeService.ACTION_START)
        .putExtra(EasyTierRuntimeService.EXTRA_CONFIG, config.toJson().toString()))

    fun setInstanceEnabled(id: String, enabled: Boolean) {
        if (id.isBlank()) return
        dispatch(Intent(context, EasyTierRuntimeService::class.java)
            .setAction(if (enabled) EasyTierRuntimeService.ACTION_ENABLE_INSTANCE else EasyTierRuntimeService.ACTION_STOP_INSTANCE)
            .putExtra(EasyTierRuntimeService.EXTRA_INSTANCE_ID, id))
    }

    fun configureConfigServer(url: String) = dispatch(Intent(context, EasyTierRuntimeService::class.java)
        .setAction(EasyTierRuntimeService.ACTION_CONFIG_SERVER)
        .putExtra(EasyTierRuntimeService.EXTRA_SERVER_URL, url.trim()))

    fun forgetConfig(id: String) = dispatch(Intent(context, EasyTierRuntimeService::class.java)
        .setAction(EasyTierRuntimeService.ACTION_DELETE_INSTANCE)
        .putExtra(EasyTierRuntimeService.EXTRA_INSTANCE_ID, id))

    fun attachIfRunning(localConfigs: List<NetworkConfig>) {
        if (store.loadSettings().rootModeEnabled || service != null) return
        val saved = runCatching { FfiRuntimeConfig.parseSnapshot(store.loadFfiRuntime()) }.getOrDefault(emptyList())
        if (saved.any { it.enabled } || store.loadSettings().ffiConfigServerUrl.isNotBlank()) {
            dispatch(Intent(context, EasyTierRuntimeService::class.java).setAction(EasyTierRuntimeService.ACTION_RESTORE))
        } else {
            val localNames = localConfigs.associate { it.id to it.displayName }
            instances = saved.map { entry ->
                FfiInstanceState(entry.instanceId, entry.instanceName,
                    localNames[entry.instanceId] ?: entry.instanceName, entry.source, entry.noTun,
                    enabled = false, running = false)
            }
        }
    }

    private fun dispatch(intent: Intent) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }.onFailure { reportError("无法启动网络服务", it) }
    }

    internal fun onServiceCreated(owner: EasyTierRuntimeService) {
        service = owner
        initialized = false
        actions = Channel(Channel.UNLIMITED)
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = runtimeScope
        NativeEasyTier.setEventListener { requestRefresh() }
        runtimeScope.launch {
            for (action in actions) {
                try { action() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Throwable) { reportError("网络操作失败", error) }
            }
        }
        runtimeScope.launch {
            while (true) {
                delay(if (foreground) 1000L else 5000L)
                requestRefresh()
            }
        }
    }

    internal fun onServiceCommand(intent: Intent?, startId: Int) {
        enqueue {
            if (store.loadSettings().rootModeEnabled) {
                service?.stopSelfResult(startId)
                return@enqueue
            }
            operationError = ""
            initialize()
            when (intent?.action) {
                EasyTierRuntimeService.ACTION_START -> {
                    val config = NetworkConfig.fromJson(JSONObject(intent.getStringExtra(EasyTierRuntimeService.EXTRA_CONFIG).orEmpty()))
                    state = state.copy(starting = true, statusText = "正在启动 ${config.displayName}")
                    deniedInstanceId = ""
                    native { NativeEasyTier.runNetworkInstanceWithSource(TomlCodec.build(config.withRuntimeHostname()), 0) }
                    gate.invalidate()
                }
                EasyTierRuntimeService.ACTION_ENABLE_INSTANCE, EasyTierRuntimeService.ACTION_STOP_INSTANCE -> {
                    deniedInstanceId = ""
                    val id = intent.getStringExtra(EasyTierRuntimeService.EXTRA_INSTANCE_ID).orEmpty()
                    native { NativeEasyTier.setNetworkInstanceEnabled(id, intent.action == EasyTierRuntimeService.ACTION_ENABLE_INSTANCE) }
                }
                EasyTierRuntimeService.ACTION_CONFIG_SERVER ->
                    applyConfigServer(intent.getStringExtra(EasyTierRuntimeService.EXTRA_SERVER_URL).orEmpty(), save = true)
                EasyTierRuntimeService.ACTION_DELETE_INSTANCE -> {
                    val id = intent.getStringExtra(EasyTierRuntimeService.EXTRA_INSTANCE_ID).orEmpty()
                    val entry = FfiRuntimeConfig.parseSnapshot(native { NativeEasyTier.collectRuntimeConfigs() })
                        .firstOrNull { it.instanceId == id }
                    if (entry != null) native { NativeEasyTier.deleteNetworkInstance(entry.instanceName) }
                }
                else -> if (!serverActive) applyConfigServer(store.loadSettings().ffiConfigServerUrl, save = false)
            }
            refresh()
            stopServiceIfIdle(startId)
        }
    }

    private suspend fun initialize() {
        if (initialized) return
        native {
            NativeEasyTier.setLogLevel(store.loadSettings().coreLogLevel)
            NativeEasyTier.setRuntimeEventsEnabled(true)
            val storedIds = FfiRuntimeConfig.parseSnapshot(store.loadFfiRuntime()).map { it.instanceId }.toSet()
            FfiRuntimeConfig.parseSnapshot(NativeEasyTier.collectRuntimeConfigs())
                .filter { !it.enabled && it.instanceId !in storedIds }
                .forEach { NativeEasyTier.deleteNetworkInstance(it.instanceName) }
            if (JSONArray(NativeEasyTier.collectRuntimeConfigs()).length() == 0) {
                NativeEasyTier.restoreRuntimeConfigs(store.loadFfiRuntime())
            }
        }
        initialized = true
        persistedSnapshot = store.loadFfiRuntime()
        configs = FfiRuntimeConfig.parseSnapshot(native { NativeEasyTier.collectRuntimeConfigs() })
    }

    private suspend fun applyConfigServer(url: String, save: Boolean) {
        val desired = url.trim()
        if (serverActive && desired == serverUrl) {
            if (save) store.saveFfiConfigServerUrl(desired)
            return
        }
        val previous = if (serverActive) serverUrl else ""
        configServerState = configServerState.copy(starting = desired.isNotBlank(), stopping = desired.isBlank(), error = "")
        serverGeneration++
        native { NativeEasyTier.detachConfigServerClient() }
        serverActive = false
        try {
            if (desired.isNotBlank()) {
                native { NativeEasyTier.startConfigServerClient(desired, null, store.ffiMachineId(), false, serverGeneration) }
                serverActive = true
            }
            serverUrl = desired
            if (save) store.saveFfiConfigServerUrl(desired)
            configServerState = configServerState.copy(starting = false, stopping = false, running = serverActive, serverUrl = desired)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (previous.isNotBlank()) {
                serverGeneration++
                runCatching { native { NativeEasyTier.startConfigServerClient(previous, null, store.ffiMachineId(), false, serverGeneration) } }
                    .onSuccess { serverActive = true }
            }
            configServerState = configServerState.copy(starting = false, stopping = false, running = serverActive,
                error = error.message ?: "配置服务器连接失败")
            AppDiagnostics.warn("ffi", "config server configuration failed")
        }
    }

    private fun enqueue(action: suspend () -> Unit) {
        check(actions.trySend(action).isSuccess) { "网络服务尚未就绪" }
    }

    private fun requestRefresh() {
        scope?.launch {
            if (!initialized || refreshQueued) return@launch
            refreshQueued = true
            actions.trySend { refreshQueued = false; refresh() }
        }
    }

    private suspend fun refresh() {
        if (!initialized) return
        val snapshot = native { NativeEasyTier.collectRuntimeConfigs() }
        configs = FfiRuntimeConfig.parseSnapshot(snapshot)
        val knownIds = configs.map { it.instanceId }.toSet()
        vpnConfigs.keys.retainAll(knownIds)
        endpointIps.keys.retainAll(knownIds)
        resolvedAt.keys.retainAll(knownIds)
        if (snapshot != persistedSnapshot) {
            withContext(Dispatchers.IO) { store.saveFfiRuntime(snapshot) }
            persistedSnapshot = snapshot
        }
        val infos = JSONObject(native { NativeEasyTier.collectNetworkInfos(maxOf(32, configs.size + 16)) })
        val events = JSONArray(native { NativeEasyTier.drainConfigServerEvents() })
        for (index in 0 until events.length()) {
            val event = events.optJSONObject(index) ?: continue
            val payload = event.optJSONObject("payload") ?: continue
            val eventGeneration = event.optLong("generation")
            if (eventGeneration == -1L) {
                if (payload.stringValue("event") in setOf("instance_exited", "event_lagged")) gate.invalidate()
                continue
            }
            if (eventGeneration != serverGeneration) continue
            // A remote update can replace an instance without changing UUID or routes.
            if (payload.stringValue("event") == "run_network_instance") gate.invalidate()
            if (!payload.optBoolean("success", true)) configServerState = configServerState.copy(error = payload.stringValue("error"))
        }
        val localNames = store.loadConfigs().associate { it.id to it.displayName }
        val tunConfig = JSONObject(native { NativeEasyTier.sharedTunConfig() })
        val acceptedIds = tunConfig.optJSONArray("instance_ids")?.toStringList().orEmpty().toSet()
        val rejected = tunConfig.optJSONArray("rejected") ?: JSONArray()
        val tunErrors = buildMap {
            for (index in 0 until rejected.length()) {
                val item = rejected.optJSONObject(index) ?: continue
                put(item.stringValue("instance_id"), item.stringValue("error"))
            }
        }
        instances = configs.map { entry ->
            val info = infos.optJSONObject(entry.instanceName)
            val error = tunErrors[entry.instanceId] ?: info?.stringValue("error_msg").orEmpty()
            FfiInstanceState(entry.instanceId, entry.instanceName,
                localNames[entry.instanceId] ?: entry.instanceName, entry.source, entry.noTun, entry.enabled,
                entry.enabled && info?.optBoolean("running", true) == true && error.isBlank(),
                activeVpn = entry.instanceId in acceptedIds,
                localCidr = info?.let(::extractLocalCidr).orEmpty(),
                nodes = info?.let(::parseNodes).orEmpty(), error = error)
        }
        val managed = instances.filter { it.source == "web" }.map {
            RootManagedNetworkState(it.instanceId, it.displayName, it.localCidr, it.nodes, it.running, it.noTun, it.error)
        }
        configServerState = configServerState.copy(running = serverActive, starting = false, stopping = false,
            connected = serverActive && native { NativeEasyTier.isConfigServerClientConnected() },
            serverUrl = serverUrl, managedNetworks = managed)
        reconcileVpn(tunConfig, infos)
        if (operationError.isNotBlank()) state = state.copy(statusText = operationError)
        val notification = when {
            vpnPermissionRequired -> "等待 VPN 授权，点击打开应用"
            state.running -> state.statusText
            configServerState.connected -> "配置服务器已连接"
            serverActive -> "正在连接配置服务器"
            instances.any { it.running } -> "无 TUN 网络正在运行"
            else -> "网络服务已停止"
        }
        if (notification != lastNotification) { lastNotification = notification; service?.updateStatus(notification) }
    }

    private suspend fun reconcileVpn(tunConfig: JSONObject, infos: JSONObject) {
        val members = configs.filter { it.instanceId in tunConfig.optJSONArray("instance_ids")?.toStringList().orEmpty() }
        val addresses = tunConfig.optJSONArray("addresses")?.toStringList().orEmpty()
        if (members.isEmpty() || addresses.isEmpty()) {
            closeVpn()
            state = RuntimeState(running = instances.any { it.running },
                statusText = when {
                    instances.any { it.running && it.noTun } -> "无 TUN 网络正在运行"
                    instances.any { it.enabled } -> "等待网络地址"
                    else -> "EasyTier 未运行"
                })
            return
        }
        val directIps = linkedSetOf<String>()
        val ranges = addresses.mapNotNull(Ipv4::cidrToRange)
        var mtu = 1400
        var exitRoutes = false
        for (entry in members) {
            val info = infos.optJSONObject(entry.instanceName) ?: continue
            val cfg = vpnConfig(entry)
            resolveEndpoints(entry, cfg)
            directIps += (endpointIps[entry.instanceId].orEmpty() + collectDynamicCoreIps(info)).filterNot {
                Ipv4.parseAddress(it)?.let { ip -> ranges.any { range -> ip in range } } == true
            }
            if (cfg.mtu > 0) mtu = minOf(mtu, cfg.mtu)
            val nodes = instances.firstOrNull { it.instanceId == entry.instanceId }?.nodes.orEmpty()
            exitRoutes = exitRoutes || (store.loadSettings().exitNodeAutoRoutes && Build.VERSION.SDK_INT >= 33 &&
                cfg.exitNodes.any { exit -> nodes.any { it.ip == exit.substringBefore('/') } })
        }
        val json = sharedVpnRequest(tunConfig, directIps, mtu, exitRoutes)
        state = RuntimeState(running = true, statusText = "${members.size} 个网络共享 VPN",
            localCidr = addresses.joinToString(), nodes = instances.flatMap { it.nodes },
            logs = members.flatMap { infos.optJSONObject(it.instanceName)?.let(::collectLogs).orEmpty() })
        val id = "shared-vpn"
        val token = gate.select(id, json)
        if (attachedGeneration == token) return
        val request = JSONObject(json).put("instanceId", id).put("generation", token).toString()
        if (VpnService.prepare(context) != null) {
            vpnPermissionRequired = true
            state = state.copy(statusText = if (deniedInstanceId == id) "VPN 授权已取消" else "等待 VPN 授权")
            if (deniedInstanceId != id) pendingVpnConfig = request
            return
        }
        vpnPermissionRequired = false
        pendingVpnConfig = null
        if (requestedGeneration != token) {
            requestedGeneration = token
            val intent = Intent(context, EasyTierVpnService::class.java).putExtra(EasyTierVpnService.EXTRA_CONFIG, request)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            } catch (error: Throwable) { onVpnServiceStartFailed(error) }
        }
    }

    private suspend fun vpnConfig(entry: FfiRuntimeConfig): NetworkConfig {
        vpnConfigs[entry.instanceId]?.takeIf { it.first == entry.toml }?.let { return it.second }
        endpointIps.remove(entry.instanceId)
        resolvedAt.remove(entry.instanceId)
        val selector = JSONObject().put("instance", JSONObject().put("instance_selector", JSONObject().put("name", entry.instanceName)))
        val response = JSONObject(native { NativeEasyTier.callJsonRpc("api.config.ConfigRpcService", "get_config", "", selector.toString()) })
        val cfg = response.getJSONObject("config")
        fun value(key: String): Any? = cfg.opt(key) ?: cfg.opt(key.replace(Regex("_([a-z])")) { it.groupValues[1].uppercase() })
        fun bool(key: String) = value(key) == true
        fun strings(key: String) = (value(key) as? JSONArray)?.toStringList().orEmpty()
        val config = NetworkConfig(id = entry.instanceId, instanceName = entry.instanceName,
            hostname = value("hostname") as? String ?: "", networkName = value("network_name") as? String ?: "",
            noTun = entry.noTun, peerUrls = strings("peer_urls"), proxyCidrs = strings("proxy_cidrs"),
            routes = strings("routes"), enableManualRoutes = bool("enable_manual_routes"), exitNodes = strings("exit_nodes"),
            enableMagicDns = bool("enable_magic_dns"), mtu = (value("mtu") as? Number)?.toInt() ?: 0)
        vpnConfigs[entry.instanceId] = entry.toml to config
        return config
    }

    private fun resolveEndpoints(entry: FfiRuntimeConfig, cfg: NetworkConfig) {
        val previous = resolvedAt[entry.instanceId] ?: 0L
        if (SystemClock.elapsedRealtime() - previous < 60_000L || !resolvingHosts.add(entry.instanceId)) return
        scope?.launch {
            try {
                val addresses = withContext(Dispatchers.IO) {
                    cfg.peerUrls.flatMap { value ->
                        val host = runCatching { java.net.URI(value).host }.getOrNull().orEmpty()
                        if (host.isBlank()) emptyList() else store.resolveHostIps(host)
                    }.filter { Ipv4.parseAddress(it) != null }.take(64).toSet()
                }
                if (configs.any { it.instanceId == entry.instanceId && it.toml == entry.toml }) {
                    endpointIps[entry.instanceId] = addresses
                    resolvedAt[entry.instanceId] = SystemClock.elapsedRealtime()
                    requestRefresh()
                }
            } finally {
                resolvingHosts.remove(entry.instanceId)
            }
        }
    }

    private suspend fun closeVpn() {
        if (gate.instanceId.isNotEmpty() || vpnService != null) {
            gate.clear()
            // Android keeps VpnService bound while its interface is open. Stopping
            // the started service alone cannot release that system binding.
            val owner = vpnService
            if (owner != null) owner.stopAndWait()
            else context.stopService(Intent(context, EasyTierVpnService::class.java))
        }
        attachedGeneration = -1
        requestedGeneration = -1
        pendingVpnConfig = null
        vpnPermissionRequired = false
    }

    fun onVpnPermissionGranted() { deniedInstanceId = ""; pendingVpnConfig = null; requestRefresh() }
    fun onVpnPermissionDenied(request: String? = null) {
        val requestedId = request?.let { runCatching { JSONObject(it).stringValue("instanceId") }.getOrNull() }
        if (requestedId != null && requestedId != gate.instanceId) return
        deniedInstanceId = gate.instanceId
        pendingVpnConfig = null
        state = state.copy(statusText = "VPN 授权已取消")
    }
    fun requestVpnAuthorization() { deniedInstanceId = ""; requestRefresh() }
    internal fun acceptsVpnRequest(id: String, generation: Long) = gate.accepts(id, generation)
    internal suspend fun attachTunFd(id: String, generation: Long, fd: Int): Boolean =
        withContext(NonCancellable) {
            tunOperations.withLock {
                if (!gate.accepts(id, generation)) return@withLock false
                native { NativeEasyTier.attachSharedTunFd(fd) }
                nativeTunOwner = id to generation
                true
            }
        }

    internal suspend fun detachTunFd(id: String, generation: Long) = withContext(NonCancellable) {
        tunOperations.withLock {
            val owner = nativeTunOwner ?: return@withLock
            if (owner.first != id || owner.second != generation) return@withLock
            try {
                native { NativeEasyTier.attachSharedTunFd(-1) }
            } catch (error: Exception) {
                // A replaced or stopped instance has already released its native descriptor.
                AppDiagnostics.warn("vpn", "TUN detach: ${error.message}")
            } finally {
                nativeTunOwner = null
            }
        }
    }

    internal fun onVpnServiceCreated(owner: EasyTierVpnService) { vpnService = owner }
    internal fun onVpnServiceDestroyed(owner: EasyTierVpnService) {
        if (vpnService === owner) vpnService = null
    }

    internal fun onVpnAttached(id: String, generation: Long) {
        if (gate.accepts(id, generation)) { attachedGeneration = generation; pendingVpnConfig = null }
    }
    internal fun onVpnClosed(id: String, generation: Long, revoked: Boolean) {
        if (!gate.accepts(id, generation)) return
        attachedGeneration = -1
        requestedGeneration = -1
        if (revoked) deniedInstanceId = id
        requestRefresh()
    }
    fun onVpnServiceStartFailed(error: Throwable) { requestedGeneration = -1; reportError("VPN 接口启动失败", error) }

    suspend fun stopAllAndWait() {
        if (scope == null) { closeVpn(); return }
        val complete = CompletableDeferred<Unit>(scope?.coroutineContext?.get(Job))
        enqueue {
            try {
                native {
                    NativeEasyTier.detachConfigServerClient()
                    FfiRuntimeConfig.parseSnapshot(NativeEasyTier.collectRuntimeConfigs()).filter { it.enabled }.forEach {
                        NativeEasyTier.setNetworkInstanceEnabled(it.instanceId, false)
                    }
                }
                serverActive = false
                serverGeneration++
                closeVpn()
                refresh()
                val owner = service
                if (owner == null) complete.complete(Unit)
                else {
                    stopCompletion = complete
                    owner.stopSelf()
                }
            } catch (error: Throwable) { complete.completeExceptionally(error) }
        }
        withTimeout(30_000L) { complete.await() }
    }

    internal fun onServiceDestroyed(owner: EasyTierRuntimeService) {
        if (service !== owner) return
        service = null
        NativeEasyTier.setEventListener(null)
        NativeEasyTier.setRuntimeEventsEnabled(false)
        actions.close()
        val oldScope = scope
        scope = null
        refreshQueued = false
        initialized = false
        val completedStop = stopCompletion
        stopCompletion = null
        completedStop?.complete(Unit)
        oldScope?.cancel()
    }

    private fun stopServiceIfIdle(startId: Int) {
        if (!serverActive && configs.none { it.enabled }) service?.stopSelfResult(startId)
    }

    @Suppress("UNUSED_PARAMETER")
    fun updatePollingConditions(isForeground: Boolean, expanded: Boolean = false) {
        val resumed = !foreground && isForeground
        foreground = isForeground
        if (resumed) requestRefresh()
    }

    fun updateLogLevel(level: String) {
        if (CoreLogLevel.normalize(level) == CoreLogLevel.OFF) state = state.copy(logs = emptyList())
    }
    fun release() = Unit // The foreground service, not an Activity, owns this controller.

    private fun reportError(title: String, error: Throwable) {
        AppDiagnostics.error("ffi", title, error)
        operationError = "$title: ${error.message ?: "未知错误"}"
        state = state.copy(starting = false, stopping = false, statusText = operationError)
    }

    private suspend fun <T> native(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun NetworkConfig.withRuntimeHostname(): NetworkConfig {
        if (hostname.isNotBlank() && !hostname.equals("localhost", true)) return this
        val deviceName = runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
        return copy(hostname = deviceName?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android-${id.take(8)}")
    }

    private fun extractLocalCidr(info: JSONObject): String {
        val ipv4 = info.optJSONObject("my_node_info")?.optJSONObject("virtual_ipv4") ?: return ""
        val address = ipv4.optJSONObject("address")?.optLong("addr") ?: return ""
        if (address == 0L) return ""
        return "${Ipv4.intToAddress(address)}/${ipv4.optInt("network_length", 24).coerceIn(1, 32)}"
    }

    private fun collectDynamicCoreIps(info: JSONObject): Set<String> = buildSet {
        fun visit(value: Any?, key: String = "") {
            when (value) {
                is JSONObject -> value.keys().forEach { visit(value.opt(it), it) }
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index), key)
                is String -> if (TRANSPORT_ENDPOINT_KEY.containsMatchIn(key)) addAll(Ipv4.addressLiterals(value))
            }
        }
        visit(info)
    }

    private fun collectLogs(info: JSONObject): List<String> {
        val level = CoreLogLevel.normalize(store.loadSettings().coreLogLevel)
        if (level == CoreLogLevel.OFF) return emptyList()
        val events = info.optJSONArray("events") ?: return emptyList()
        return (0 until events.length()).toList().takeLast(40).mapNotNull { index ->
            val raw = events.opt(index)
            val obj = if (raw is JSONObject) raw else runCatching { JSONObject(raw.toString()) }.getOrNull()
            val event = obj?.optJSONObject("event") ?: return@mapNotNull null
            event.stringValue("msg").ifBlank { event.stringValue("message").ifBlank { event.toString() } }
        }.filter { level == CoreLogLevel.DEBUG || IMPORTANT_CORE_LOG.containsMatchIn(it) }
    }

    companion object {
        @Volatile private var instance: EasyTierController? = null
        fun get(context: Context): EasyTierController = instance ?: synchronized(this) {
            instance ?: EasyTierController(context.applicationContext).also { instance = it }
        }
        private val TRANSPORT_ENDPOINT_KEY = Regex("(?:remote|resolved|endpoint|stun|mapped|public).*?(?:addr|address|url|endpoint)?$", RegexOption.IGNORE_CASE)
        private val IMPORTANT_CORE_LOG = Regex("error|warn|fail|panic|closed|stop|disconnect|exit|timeout", RegexOption.IGNORE_CASE)
    }
}
