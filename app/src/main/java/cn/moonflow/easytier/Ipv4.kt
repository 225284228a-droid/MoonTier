package cn.moonflow.easytier

import java.net.URI
import java.util.regex.Pattern
import kotlin.math.min

object Ipv4 {
    private val nonPublic = listOf(
        "0.0.0.0/8",
        "10.0.0.0/8",
        "100.64.0.0/10",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "172.16.0.0/12",
        "192.0.0.0/24",
        "192.0.2.0/24",
        "192.88.99.0/24",
        "192.168.0.0/16",
        "198.18.0.0/15",
        "198.51.100.0/24",
        "203.0.113.0/24",
        "224.0.0.0/4",
        "240.0.0.0/4"
    ).mapNotNull { cidrToRange(it) }

    fun intToAddress(value: Long): String {
        val v = value and 0xFFFFFFFFL
        return "${(v ushr 24) and 255}.${(v ushr 16) and 255}.${(v ushr 8) and 255}.${v and 255}"
    }

    fun parseAddress(ip: String): Long? {
        val parts = ip.trim().split(".")
        if (parts.size != 4) return null
        var out = 0L
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            out = (out shl 8) or octet.toLong()
        }
        return out
    }

    fun cidrToRange(cidr: String): LongRange? {
        val parts = cidr.split("/")
        if (parts.size != 2) return null
        val base = parseAddress(parts[0]) ?: return null
        val prefix = parts[1].toIntOrNull() ?: return null
        if (prefix !in 0..32) return null
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val start = base and mask
        val end = start or (mask xor 0xFFFFFFFFL)
        return start..end
    }

    fun isPublicIpv4(ip: String): Boolean {
        val value = parseAddress(ip) ?: return false
        return nonPublic.none { value in it }
    }

    fun hostFromUrl(value: String): String {
        val trimmed = value.trim()
        return runCatching { URI(trimmed).host }.getOrNull()
            ?: trimmed.substringAfter("://", trimmed).substringBefore("/").substringBeforeLast(":")
    }

    fun publicRoutes(excludedIps: Set<String>): List<String> {
        val excluded = (nonPublic + excludedIps.mapNotNull { parseAddress(it)?.let { v -> v..v } })
            .sortedBy { it.first }
        val out = ArrayList<String>()
        var cursor = 0L
        excluded.forEach { range ->
            if (cursor < range.first) appendRange(cursor, range.first - 1, out)
            if (cursor <= range.last) cursor = range.last + 1
        }
        if (cursor <= 0xFFFFFFFFL) appendRange(cursor, 0xFFFFFFFFL, out)
        return out
    }

    fun subnetRoute(cidr: String): String? {
        val parts = cidr.split("/")
        if (parts.size != 2) return null
        val range = cidrToRange(cidr) ?: return null
        val prefix = parts[1].toIntOrNull() ?: return null
        return "${intToAddress(range.first)}/$prefix"
    }

    private fun appendRange(start: Long, end: Long, out: MutableList<String>) {
        var current = start
        while (current <= end) {
            var block = if (current == 0L) 1L shl 32 else current and -current
            val remaining = end - current + 1
            while (block > remaining) block = block ushr 1
            var prefix = 32
            var size = block
            while (size > 1) {
                prefix--
                size = size ushr 1
            }
            out += "${intToAddress(current)}/$prefix"
            current += block
        }
    }
}

fun buildVpnConfigJson(
    config: NetworkConfig,
    cidr: String,
    peerProxyCidrs: Set<String>,
    directPublicIps: Set<String>,
    exitNodeAutoRoutes: Boolean,
    exitNodeConfiguredCount: Int = config.exitNodes.count { it.isNotBlank() },
    exitNodeReachableCount: Int = 0
): String {
    val parts = cidr.split("/")
    val address = parts.getOrNull(0).orEmpty().ifBlank { "10.0.0.2" }
    val prefix = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 32) ?: 24
    val routes = LinkedHashSet<String>()

    Ipv4.subnetRoute("$address/$prefix")?.let { routes += it }
    if (config.enableManualRoutes) {
        routes += config.routes.cleanItems()
    } else {
        routes += config.proxyCidrs.cleanItems()
        routes += peerProxyCidrs
    }
    if (exitNodeAutoRoutes) {
        routes += Ipv4.publicRoutes(directPublicIps)
    }
    if (config.enableMagicDns) routes += "100.100.100.101/32"

    return org.json.JSONObject()
        .put("instanceName", config.instanceName)
        .put("address", address)
        .put("prefixLength", prefix)
        .put("mtu", if (config.mtu > 0) config.mtu else 1400)
        .put("dns", if (config.enableMagicDns) "100.100.100.101" else "")
        .put("exitNodeAutoRoutes", exitNodeAutoRoutes)
        .put("exitNodeCount", exitNodeConfiguredCount)
        .put("exitNodeReachableCount", exitNodeReachableCount)
        .put("routes", routes.toList().cleanItems().toJsonArray())
        .toString()
}

fun directPublicIpsFor(config: NetworkConfig, store: ConfigStore): Set<String> {
    val out = LinkedHashSet<String>()
    (config.peerUrls + config.stunServers).forEach { url ->
        val host = Ipv4.hostFromUrl(url)
        Ipv4.parseAddress(host)?.let {
            if (Ipv4.isPublicIpv4(host)) out += host
        } ?: run {
            out += store.resolvePublicIpv4(host)
        }
    }
    return out.take(4).toSet()
}

fun collectDirectPublicIpsForExitRoutes(
    config: NetworkConfig,
    store: ConfigStore,
    runtimeText: String
): Set<String> {
    val out = LinkedHashSet<String>()
    out += directPublicIpsFor(config, store)
    out += extractDirectPublicIpsFromRuntime(runtimeText)
    return out.take(4).toSet()
}

private fun extractDirectPublicIpsFromRuntime(runtimeText: String): Set<String> {
    val out = LinkedHashSet<String>()
    if (runtimeText.isBlank()) return out

    val mappedAddrRe = Pattern.compile("""mapped_addr=Some\(SocketAddr\s*\{[^\n}]*ip:\s*Some\(Ipv4\((\d{1,3}(?:\.\d{1,3}){3})\)\)""")
    val publicIpv4Re = Pattern.compile("""public_ipv4:\s*Some\((\d{1,3}(?:\.\d{1,3}){3})\)""")
    val destAddrRe = Pattern.compile("""dest_addr=Some\(SocketAddr\s*\{[^\n}]*ip:\s*Some\(Ipv4\((\d{1,3}(?:\.\d{1,3}){3})\)\)""")

    val mappedMatcher = mappedAddrRe.matcher(runtimeText)
    var mappedIp = ""
    if (mappedMatcher.find()) mappedIp = mappedMatcher.group(1).orEmpty()

    val publicMatcher = publicIpv4Re.matcher(runtimeText)
    while (publicMatcher.find()) {
        val ip = publicMatcher.group(1).orEmpty()
        if (ip.isNotBlank()) out += ip
    }

    val destMatcher = destAddrRe.matcher(runtimeText)
    while (destMatcher.find()) {
        val ip = destMatcher.group(1).orEmpty()
        if (ip.isNotBlank() && ip != mappedIp) out += ip
    }

    return out
}
