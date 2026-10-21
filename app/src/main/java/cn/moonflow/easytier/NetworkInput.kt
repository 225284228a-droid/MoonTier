package cn.moonflow.easytier

import java.net.Inet6Address
import java.net.InetAddress

/**
 * Formatting helpers for address fields. Normalization runs when a field loses
 * focus so typing is never rewritten mid-input.
 */
object NetworkInput {
    fun normalizeCidr(
        value: String,
        defaultPrefix: Int = 24,
        networkAddress: Boolean = false
    ): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return trimmed
        val slash = trimmed.lastIndexOf('/')
        val address = (if (slash >= 0) trimmed.substring(0, slash) else trimmed).trim()
        if (address.isEmpty()) return trimmed
        val prefix = if (slash >= 0) {
            trimmed.substring(slash + 1).trim().toIntOrNull() ?: return trimmed
        } else {
            defaultPrefix
        }
        return if (address.contains(':')) {
            if (prefix !in 0..128) return trimmed
            val bytes = parseIpv6(address) ?: return trimmed
            if (networkAddress) zeroHostBits(bytes, prefix)
            "${formatIpv6(bytes)}/$prefix"
        } else {
            if (prefix !in 0..32) return trimmed
            val parsed = Ipv4.parseAddress(address) ?: return trimmed
            val addressValue = if (networkAddress) parsed and ipv4Mask(prefix) else parsed
            "${Ipv4.intToAddress(addressValue)}/$prefix"
        }
    }

    fun normalizeCidrList(value: String): String =
        normalizeCidr(value, defaultPrefix = if (value.contains(':')) 64 else 24, networkAddress = true)

    fun normalizeConfig(config: NetworkConfig): NetworkConfig = config.copy(
        ipv4 = normalizeCidr(config.ipv4, 24),
        routes = config.routes.map(::normalizeCidrList),
        proxyCidrs = config.proxyCidrs.map(::normalizeCidrList),
        advanced = config.advanced.copy(
            dhcpCidr = normalizeCidr(config.advanced.dhcpCidr, 24, networkAddress = true),
            dhcpv6Cidr = normalizeCidr(config.advanced.dhcpv6Cidr, 64, networkAddress = true),
            ipv6 = normalizeCidr(config.advanced.ipv6, 64),
            ipv6PublicAddrPrefix = normalizeCidr(config.advanced.ipv6PublicAddrPrefix, 64, networkAddress = true),
            proxyMappings = config.advanced.proxyMappings.map {
                it.copy(
                    cidr = normalizeCidrList(it.cidr),
                    mappedCidr = normalizeCidrList(it.mappedCidr)
                )
            },
            vpnPortalClients = config.advanced.vpnPortalClients.map {
                it.copy(virtualIp = normalizeCidr(it.virtualIp, 24))
            },
            managedCredentials = config.advanced.managedCredentials.map {
                it.copy(allowedProxyCidrs = it.allowedProxyCidrs.map(::normalizeCidrList))
            }
        )
    )

    fun isIpv4NetworkCidr(value: String): Boolean {
        val trimmed = value.trim()
        val parts = trimmed.split("/")
        if (parts.size != 2) return false
        val address = Ipv4.parseAddress(parts[0]) ?: return false
        val prefix = parts[1].toIntOrNull() ?: return false
        if (prefix !in 0..32) return false
        return address and ipv4Mask(prefix) == address
    }

    fun isIpv6NetworkCidr(value: String): Boolean {
        val trimmed = value.trim()
        val parts = trimmed.split("/")
        if (parts.size != 2) return false
        val address = parseIpv6(parts[0]) ?: return false
        val prefix = parts[1].toIntOrNull() ?: return false
        if (prefix !in 0..128) return false
        return hostBitsAreZero(address, prefix)
    }

    private fun ipv4Mask(prefix: Int): Long =
        if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL

    private fun parseIpv6(value: String): ByteArray? {
        val trimmed = value.trim().removeSurrounding("[", "]")
        if (!trimmed.contains(':')) return null
        val address = runCatching { InetAddress.getByName(trimmed) }.getOrNull() as? Inet6Address ?: return null
        return address.address
    }

    private fun zeroHostBits(bytes: ByteArray, prefix: Int) {
        for (index in 0 until 16) {
            val bit = index * 8
            bytes[index] = when {
                bit + 8 <= prefix -> bytes[index]
                bit >= prefix -> 0
                else -> (bytes[index].toInt() and (0xFF shl (prefix - bit))).toByte()
            }
        }
    }

    private fun hostBitsAreZero(bytes: ByteArray, prefix: Int): Boolean {
        for (index in 0 until 16) {
            val bit = index * 8
            val value = bytes[index].toInt() and 0xFF
            val hostMask = when {
                bit + 8 <= prefix -> 0
                bit >= prefix -> 0xFF
                else -> 0xFF ushr (prefix - bit)
            }
            if (value and hostMask != 0) return false
        }
        return true
    }

    private fun formatIpv6(bytes: ByteArray): String {
        val groups = IntArray(8) { index ->
            ((bytes[index * 2].toInt() and 0xFF) shl 8) or (bytes[index * 2 + 1].toInt() and 0xFF)
        }
        var bestStart = -1
        var bestLength = 0
        var start = -1
        var length = 0
        for (index in 0..8) {
            if (index < 8 && groups[index] == 0) {
                if (start < 0) start = index
                length++
            } else {
                if (length > bestLength) {
                    bestStart = start
                    bestLength = length
                }
                start = -1
                length = 0
            }
        }
        if (bestLength < 2) return groups.joinToString(":") { it.toString(16) }
        val head = groups.take(bestStart).joinToString(":") { it.toString(16) }
        val tail = groups.drop(bestStart + bestLength).joinToString(":") { it.toString(16) }
        return "$head::$tail"
    }
}
