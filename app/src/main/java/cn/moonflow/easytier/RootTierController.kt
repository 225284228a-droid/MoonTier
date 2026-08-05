package cn.moonflow.easytier

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class RootTierController(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val coreManager = RootCoreManager(context)
    private val rootDir = File(context.filesDir, "root")
    private val scriptFile = File(rootDir, "moontier_root.sh")
    private val configsDir = File(rootDir, "configs")
    private val logsDir = File(rootDir, "logs")
    private val pidsDir = File(rootDir, "pids")
    private val metaDir = File(rootDir, "meta")
    private var pollJob: Job? = null

    var state by mutableStateOf(RootTierState())
        private set

    init {
        ensureDirs()
        ensureScript()
        refreshCoreState()
        scope.launch {
            attach()
            pollJob = scope.launch { pollLoop() }
        }
    }

    fun release() {
        pollJob?.cancel()
        scope.cancel()
    }

    fun checkCoreUpdate() {
        scope.launch {
            state = state.copy(core = state.core.copy(checking = true, message = ""))
            val latest = withContext(Dispatchers.IO) { coreManager.checkLatest() }
            state = state.copy(
                core = state.core.copy(
                    checking = false,
                    latestVersion = latest ?: state.core.latestVersion,
                    message = if (latest == null) "检查最新版本失败" else "已获取最新版本 $latest"
                )
            )
        }
    }

    fun installCore() {
        scope.launch {
            state = state.copy(core = state.core.copy(installing = true, progress = 0, message = ""))
            try {
                val tag = withContext(Dispatchers.IO) {
                    coreManager.installLatest { progress, _ ->
                        state = state.copy(core = state.core.copy(progress = progress))
                    }
                }
                state = state.copy(
                    core = RootCoreState(
                        ready = true,
                        installedVersion = tag,
                        latestVersion = tag,
                        progress = 100,
                        message = "已安装官方核心 $tag"
                    )
                )
            } catch (e: Exception) {
                state = state.copy(
                    core = state.core.copy(
                        installing = false,
                        message = e.message ?: "下载核心失败"
                    )
                )
            }
        }
    }

    fun start(config: NetworkConfig) {
        val existing = state.instances.firstOrNull { it.configId == config.id }
        if (existing?.running == true || existing?.starting == true) return
        val starting = RootInstanceState(
            configId = config.id,
            displayName = config.displayName,
            instanceName = config.instanceName,
            starting = true,
            logs = listOf("正在启动 root 核心")
        )
        state = state.copy(instances = state.instances.filterNot { it.configId == config.id } + starting)
        scope.launch {
            val result = runCatching { startBlocking(config) }
            result.onSuccess { instance ->
                state = state.copy(instances = state.instances.map { if (it.configId == config.id) instance else it })
            }.onFailure { error ->
                state = state.copy(
                    instances = state.instances.map {
                        if (it.configId == config.id) {
                            it.copy(starting = false, running = false, error = error.message ?: "启动失败")
                        } else {
                            it
                        }
                    }
                )
            }
        }
    }

    fun stop(configId: String) {
        val instance = state.instances.firstOrNull { it.configId == configId } ?: return
        state = state.copy(
            instances = state.instances.map { if (it.configId == configId) it.copy(stopping = true) else it }
        )
        scope.launch {
            withContext(Dispatchers.IO) {
                stopBlocking(configId)
            }
            state = state.copy(instances = state.instances.filterNot { it.configId == configId })
        }
    }

    fun stopAll() {
        state.instances.map { it.configId }.forEach { stop(it) }
    }

    private fun refreshCoreState() {
        state = state.copy(
            core = RootCoreState(
                ready = coreManager.isReady(),
                installedVersion = coreManager.installedVersion()
            )
        )
    }

    private fun startBlocking(config: NetworkConfig): RootInstanceState {
        val root = RootManager.probe()
        if (!root.available) throw IllegalStateException("未检测到 root，请先在 KernelSU 中授权")
        if (!coreManager.isReady()) throw IllegalStateException("尚未下载官方 easytier-core")

        val safeId = safeId(config.id)
        val port = rpcPort(config.id)
        var runtimeConfig = config.copy(
            hostname = effectiveHostname(config),
            rpcPortal = "127.0.0.1:$port"
        )
        val settings = ConfigStore(context).loadSettings()
        if (settings.exitNodeAutoRoutes && runtimeConfig.exitNodes.cleanItems().isNotEmpty()) {
            runtimeConfig = runtimeConfig.copy(
                proxyCidrs = (runtimeConfig.proxyCidrs + "0.0.0.0/0").cleanItems()
            )
        }
        val configFile = File(configsDir, "$safeId.toml")
        val logFile = File(logsDir, "$safeId.log")
        val pidFile = File(pidsDir, "$safeId.pid")
        val metaFile = File(metaDir, "$safeId.json")

        configFile.writeText(TomlCodec.build(runtimeConfig))
        pidFile.delete()
        metaFile.writeText(
            JSONObject()
                .put("config_id", config.id)
                .put("display_name", config.displayName)
                .put("instance_name", config.instanceName)
                .put("rpc_port", port)
                .toString()
        )

        val command = buildString {
            append("sh ")
            append(shq(scriptFile.absolutePath))
            append(" start ")
            append(shq(coreManager.coreFile.absolutePath))
            append(" ")
            append(shq(configFile.absolutePath))
            append(" ")
            append(shq(logFile.absolutePath))
            append(" ")
            append(shq(pidFile.absolutePath))
        }
        val result = RootManager.su(command, timeoutMs = 20000)
        if (!result.success) {
            val tail = readLogTail(logFile)
            val detail = tail.lastOrNull() ?: result.output.ifBlank { "核心启动失败" }
            throw IllegalStateException(detail)
        }

        val pid = runCatching { pidFile.readText().trim().toLong() }.getOrDefault(0)
        return RootInstanceState(
            configId = config.id,
            displayName = config.displayName,
            instanceName = config.instanceName,
            pid = pid,
            running = pid > 0,
            logs = readLogTail(logFile).ifEmpty { listOf("核心已启动") }
        )
    }

    private fun stopBlocking(configId: String) {
        val safeId = safeId(configId)
        val command = buildString {
            append("sh ")
            append(shq(scriptFile.absolutePath))
            append(" stop ")
            append(shq(coreManager.coreFile.absolutePath))
            append(" ")
            append(shq(File(configsDir, "$safeId.toml").absolutePath))
            append(" ")
            append(shq(File(logsDir, "$safeId.log").absolutePath))
            append(" ")
            append(shq(File(pidsDir, "$safeId.pid").absolutePath))
        }
        RootManager.su(command, timeoutMs = 12000)
        File(pidsDir, "$safeId.pid").delete()
        File(metaDir, "$safeId.json").delete()
    }

    private suspend fun pollLoop() {
        while (true) {
            refreshStates()
            delay(3000)
        }
    }

    private suspend fun refreshStates() {
        val current = state.instances
        if (current.isEmpty()) return
        val refreshed = withContext(Dispatchers.IO) {
            current.mapNotNull { refreshInstance(it) }
        }
        state = state.copy(instances = refreshed)
    }

    private fun refreshInstance(instance: RootInstanceState): RootInstanceState? {
        val alive = isAlive(instance.pid)
        if (instance.stopping) {
            return if (alive) instance.copy(stopping = true) else null
        }
        if (!alive) {
            if (instance.starting) {
                return instance.copy(
                    starting = false,
                    running = false,
                    error = instance.error.ifBlank { "核心进程启动后退出" }
                )
            }
            return null
        }

        val logTail = readLogTail(File(logsDir, "${safeId(instance.configId)}.log"))
        val (cidr, nodes) = queryStatus(instance.configId)
        return instance.copy(
            running = true,
            starting = false,
            stopping = false,
            localCidr = cidr,
            nodes = nodes,
            logs = logTail.ifEmpty { instance.logs }
        )
    }

    private fun attach() {
        metaDir.listFiles()?.forEach { meta ->
            val obj = runCatching { JSONObject(meta.readText()) }.getOrNull() ?: return@forEach
            val configId = obj.optString("config_id")
            if (configId.isBlank()) return@forEach
            val safeId = safeId(configId)
            val pidFile = File(pidsDir, "$safeId.pid")
            val pid = runCatching { pidFile.readText().trim().toLong() }.getOrDefault(0)
            if (pid > 0 && isAlive(pid)) {
                val instance = RootInstanceState(
                    configId = configId,
                    displayName = obj.optString("display_name"),
                    instanceName = obj.optString("instance_name"),
                    pid = pid,
                    running = true
                )
                state = state.copy(instances = state.instances + instance)
            } else {
                pidFile.delete()
                meta.delete()
            }
        }
    }

    private fun queryStatus(configId: String): Pair<String, List<NodeInfo>> {
        val port = rpcPort(configId)
        val nodeOutput = runCli(port, "node", "info").output
        var cidr = ""
        var hostname = ""
        runCatching { JSONObject(nodeOutput) }.getOrNull()?.let { obj ->
            cidr = obj.optString("ipv4_addr").ifBlank {
                obj.optJSONObject("my_node_info")?.optString("virtual_ipv4").orEmpty()
            }
            hostname = obj.optString("hostname")
        }

        val nodes = ArrayList<NodeInfo>()
        if (cidr.isNotBlank()) {
            nodes += NodeInfo(
                hostname = hostname.ifBlank { "本机" },
                ip = cidr.substringBefore("/"),
                role = "本机",
                latencyMs = 0,
                local = true
            )
        }
        nodes += parsePeerNodes(runCli(port, "peer", "list").output)
        return cidr to nodes
    }

    private fun parsePeerNodes(raw: String): List<NodeInfo> {
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<NodeInfo>()
        val array = runCatching { org.json.JSONArray(raw) }.getOrNull()
            ?: runCatching { JSONObject(raw).optJSONArray("peers") }.getOrNull()
            ?: return emptyList()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val cidr = obj.optString("cidr", obj.optString("ipv4", obj.optString("ipv4_addr", "")))
            val hostname = obj.optString("hostname")
            val cost = obj.optString("cost", "1")
            val latText = obj.optString("lat_ms")
            val latency = latText.toDoubleOrNull()?.toInt() ?: -1
            val role = when {
                cost.equals("Local", ignoreCase = true) -> "本机"
                cost.toIntOrNull()?.let { it > 1 } == true -> "中继($cost)"
                else -> "直连"
            }
            out += NodeInfo(
                hostname = hostname.ifBlank { role },
                ip = cidr.substringBefore("/"),
                role = role,
                latencyMs = latency,
                local = false
            )
        }
        return out.distinctBy { "${it.local}-${it.ip}-${it.hostname}" }
    }

    private fun runCli(port: Int, vararg args: String): ShellResult {
        return try {
            val cmd = listOf(
                coreManager.cliFile.absolutePath,
                "-p",
                "127.0.0.1:$port",
                "-o",
                "json",
                *args
            )
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val finished = process.waitFor(4000, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                ShellResult(-1, "")
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText().trim() }
                ShellResult(process.exitValue(), output)
            }
        } catch (e: Exception) {
            ShellResult(-1, e.message.orEmpty())
        }
    }

    private fun isAlive(pid: Long): Boolean {
        if (pid <= 0) return false
        return RootManager.su("kill -0 $pid", timeoutMs = 3000).success
    }

    private fun readLogTail(file: File): List<String> {
        if (!file.exists()) return emptyList()
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return text.lineSequence()
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(80)
    }

    private fun ensureDirs() {
        configsDir.mkdirs()
        logsDir.mkdirs()
        pidsDir.mkdirs()
        metaDir.mkdirs()
        coreManager.ensureDirectories()
    }

    private fun ensureScript() {
        if (scriptFile.exists()) return
        scriptFile.parentFile?.mkdirs()
        runCatching {
            context.assets.open("root/moontier_root.sh").use { input ->
                scriptFile.outputStream().use { output -> input.copyTo(output) }
            }
            scriptFile.setExecutable(true, false)
        }
    }

    private fun effectiveHostname(config: NetworkConfig): String {
        if (config.hostname.isNotBlank() && !config.hostname.equals("localhost", ignoreCase = true)) {
            return config.hostname
        }
        val candidates = listOf(
            runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull(),
            runCatching { Settings.Secure.getString(context.contentResolver, "bluetooth_name") }.getOrNull(),
            Build.MODEL,
            Build.DEVICE
        )
        return candidates
            .map { it.orEmpty().trim() }
            .firstOrNull { it.isNotBlank() && !it.equals("localhost", ignoreCase = true) }
            ?: "Android-${config.id.take(8)}"
    }

    private fun rpcPort(configId: String): Int =
        15000 + ((configId.hashCode() and 0x7fffffff) % 8000)

    private fun safeId(id: String): String =
        id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64).ifBlank { "config" }

    private fun shq(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
