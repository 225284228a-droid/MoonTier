package cn.moonflow.easytier

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URLEncoder
import java.net.URL
import java.util.UUID
import java.util.regex.Pattern

class ConfigStore(private val context: Context) {
    private val configsFile = File(context.filesDir, "network_configs.json")
    private val userServersFile = File(context.filesDir, "servers.json")
    private val officialServersFile = File(context.filesDir, "official_servers.json")
    private val settingsFile = File(context.filesDir, "settings.json")

    @Synchronized
    fun loadConfigs(): List<NetworkConfig> {
        if (!configsFile.exists()) {
            val initial = listOf(NetworkConfig.defaultConfig())
            saveConfigs(initial)
            return initial
        }

        val array = runCatching { JSONArray(configsFile.readText()) }.getOrNull() ?: JSONArray()
        val configs = ArrayList<NetworkConfig>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            configs.add(NetworkConfig.fromJson(obj))
        }
        return normalizeConfigs(configs)
    }

    @Synchronized
    fun saveConfigs(configs: List<NetworkConfig>) {
        val normalized = normalizeConfigs(configs)
        val array = JSONArray()
        normalized.forEach { array.put(it.toJson()) }
        configsFile.writeText(array.toString(2))
    }

    @Synchronized
    fun saveConfig(config: NetworkConfig) {
        val existing = loadConfigs().filterNot { it.id == config.id }
        saveConfigs(existing + config)
    }

    @Synchronized
    fun deleteConfig(id: String) {
        val current = loadConfigs().filterNot { it.id == id }
        saveConfigs(if (current.isEmpty()) listOf(NetworkConfig.defaultConfig()) else current)
    }

    @Synchronized
    fun setDefaultConfig(id: String) {
        saveConfigs(loadConfigs().map { it.copy(isDefault = it.id == id) })
    }

    fun loadDefaultConfig(): NetworkConfig =
        loadConfigs().firstOrNull { it.isDefault } ?: loadConfigs().first()

    @Synchronized
    fun loadSettings(): AppSettings {
        if (!settingsFile.exists()) return AppSettings()
        val obj = runCatching { JSONObject(settingsFile.readText()) }.getOrNull() ?: JSONObject()
        return AppSettings.fromJson(obj)
    }

    @Synchronized
    fun saveSettings(settings: AppSettings) {
        settingsFile.writeText(settings.toJson().toString(2))
    }

    @Synchronized
    fun loadUserServers(): List<ServerEntry> = loadServerFile(userServersFile)

    @Synchronized
    fun saveUserServers(servers: List<ServerEntry>) = saveServerFile(userServersFile, servers)

    @Synchronized
    fun loadOfficialServers(): List<ServerEntry> = loadServerFile(officialServersFile)

    @Synchronized
    fun saveOfficialServers(servers: List<ServerEntry>) = saveServerFile(officialServersFile, servers)

    suspend fun syncOfficialServers(source: String = OFFICIAL_SOURCE): SyncResult =
        withContext(Dispatchers.IO) {
            val text = fetchServerText(source)
            val addresses = parseServerAddresses(text)
            if (addresses.isEmpty()) throw IllegalStateException("官方源没有返回服务器地址，已保留本地缓存")
            val servers = addresses.map { ServerEntry(serverNameFromAddress(it), it) }
            saveOfficialServers(servers)
            SyncResult(true, servers.size, if (servers.isEmpty()) "官方源没有返回服务器" else "官方服务器列表已刷新")
        }

    suspend fun downloadUserServers(source: String): SyncResult =
        withContext(Dispatchers.IO) {
            val text = fetchServerText(source)
            val addresses = parseServerAddresses(text)
            val current = loadUserServers()
            val existing = current.map { it.address.lowercase() }.toHashSet()
            val usedNames = current.map { it.name }.toMutableList()
            val added = addresses
                .filter { existing.add(it.lowercase()) }
                .map {
                    val name = nextServerNameFromNames(usedNames, serverNameFromAddress(it))
                    usedNames.add(name)
                    ServerEntry(name, it)
                }
            saveUserServers(current + added)
            SyncResult(true, added.size, if (added.isEmpty()) "没有发现新的用户收藏服务器" else "已添加 ${added.size} 个服务器")
        }

    private fun normalizeConfigs(configs: List<NetworkConfig>): List<NetworkConfig> {
        val list = if (configs.isEmpty()) listOf(NetworkConfig.defaultConfig()) else configs
        val defaultId = list.firstOrNull { it.isDefault }?.id ?: list.first().id
        return list.mapIndexed { index, cfg ->
            val label = cfg.label.ifBlank { cfg.networkName.ifBlank { "配置 ${index + 1}" } }
            val instance = cfg.instanceName.ifBlank { "MoonTier-${cfg.id.take(8)}" }
            cfg.copy(label = label, instanceName = instance, isDefault = cfg.id == defaultId)
        }
    }

    private fun loadServerFile(file: File): List<ServerEntry> {
        if (!file.exists()) return emptyList()
        val array = runCatching { JSONArray(file.readText()) }.getOrNull() ?: return emptyList()
        val out = ArrayList<ServerEntry>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val server = ServerEntry.fromJson(item)
            if (server.address.isNotBlank()) out.add(server)
        }
        return out.distinctBy { it.address.lowercase() }
    }

    private fun saveServerFile(file: File, servers: List<ServerEntry>) {
        val array = JSONArray()
        servers
            .filter { it.address.isNotBlank() }
            .distinctBy { it.address.lowercase() }
            .forEach { array.put(it.toJson()) }
        file.writeText(array.toString(2))
    }

    private fun parseServerAddresses(text: String): List<String> {
        val pattern = Pattern.compile("(?i)((?:tcp|udp|ws|wss|wg|quic)://[^\\s,'\"<>]+)")
        val matcher = pattern.matcher(text)
        val out = ArrayList<String>()
        while (matcher.find()) {
            val cleaned = matcher.group(1)
                ?.trim()
                ?.trimEnd(',', ';', ')', '}')
                .orEmpty()
            if (cleaned.isNotBlank()) out.add(cleaned)
        }
        return out.cleanItems()
    }

    private fun fetchServerText(source: String): String {
        val trimmed = source.trim()
        require(trimmed.isNotEmpty()) { "请输入 TXT 记录域名或 URL" }

        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            val body = httpGet(trimmed, accept = "text/plain,*/*")
            if (parseServerAddresses(body).isNotEmpty()) return body
        } else {
            val dnsBody = fetchTxtByDnsJsonProviders(trimmed)
            if (parseServerAddresses(dnsBody).isNotEmpty()) return dnsBody
        }

        throw IllegalStateException("没有从 $source 读取到服务器地址")
    }

    private fun fetchTxtByDnsJsonProviders(domain: String): String {
        val encoded = URLEncoder.encode(domain.trim().trimEnd('.'), "UTF-8")
        val providers = listOf(
            "https://dns.alidns.com/resolve?name=$encoded&type=TXT",
            "https://doh.pub/dns-query?name=$encoded&type=TXT",
            "https://cloudflare-dns.com/dns-query?name=$encoded&type=TXT"
        )
        val errors = ArrayList<String>()
        for (provider in providers) {
            val response = runCatching { httpGet(provider, accept = "application/dns-json") }
                .onFailure { errors.add("${URL(provider).host}: ${it.message ?: it.javaClass.simpleName}") }
                .getOrNull()
                ?: continue
            val text = parseDnsJsonTxtResponse(response)
            if (parseServerAddresses(text).isNotEmpty()) return text
            errors.add("${URL(provider).host}: TXT 记录为空")
        }
        throw IllegalStateException("TXT 解析失败：${errors.joinToString("；")}")
    }

    private fun parseDnsJsonTxtResponse(response: String): String {
        val answer = JSONObject(response).optJSONArray("Answer") ?: JSONArray()
        val parts = ArrayList<String>()
        for (i in 0 until answer.length()) {
            val data = answer.optJSONObject(i)?.optString("data").orEmpty()
            if (data.isNotBlank()) parts.add(decodeDnsTxtData(data))
        }
        return parts.joinToString("\n")
    }

    private fun decodeDnsTxtData(raw: String): String {
        val text = raw.trim().trim('"')
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (text[i] == '\\' && i + 3 < text.length) {
                val code = text.substring(i + 1, i + 4).toIntOrNull()
                if (code != null) {
                    out.append(code.toChar())
                    i += 4
                    continue
                }
            }
            if (text[i] == '"' && i + 2 < text.length && text[i + 1] == ' ' && text[i + 2] == '"') {
                out.append('\n')
                i += 3
                continue
            }
            out.append(text[i])
            i++
        }
        return out.toString()
    }

    private fun httpGet(target: String, accept: String): String {
        val connection = URL(target).openConnection() as HttpURLConnection
        connection.connectTimeout = 6000
        connection.readTimeout = 8000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", accept)
        connection.instanceFollowRedirects = true
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    fun resolvePublicIpv4(host: String): List<String> = runCatching {
        InetAddress.getAllByName(host)
            .mapNotNull { it.hostAddress }
            .filter { Ipv4.isPublicIpv4(it) }
            .distinct()
    }.getOrDefault(emptyList())

    companion object {
        const val OFFICIAL_SOURCE = "etserverlist.225284.xyz"
    }
}

data class SyncResult(
    val success: Boolean,
    val count: Int,
    val message: String
)
