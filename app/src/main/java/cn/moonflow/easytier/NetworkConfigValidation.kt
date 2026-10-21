package cn.moonflow.easytier

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

enum class ConfigField {
    LABEL,
    NETWORK_NAME,
    IPV4,
    DHCP_CIDR,
    IPV6,
    DHCPV6_CIDR,
    IPV6_PREFIX,
    PEER_URLS,
    LISTENERS,
    STUN_SERVERS,
    ROUTES,
    PROXY_CIDRS,
    EXIT_NODES,
    SECURE_KEYS,
    PEER_KEYS,
    MANAGED_CREDENTIALS,
    PORT_FORWARDS,
    VPN_PORTAL,
    NUMERIC,
    SYSTEM_FORWARD
}

data class ConfigValidationResult(
    val errors: Map<ConfigField, List<String>> = emptyMap()
) {
    val isValid: Boolean get() = errors.isEmpty()
    val errorCount: Int get() = errors.values.sumOf { it.size }

    fun error(field: ConfigField): String? =
        errors[field]?.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

object NetworkConfigValidation {
    fun validate(config: NetworkConfig): ConfigValidationResult {
        val errors = linkedMapOf<ConfigField, MutableList<String>>()

        fun add(field: ConfigField, message: String) {
            errors.getOrPut(field) { mutableListOf() } += message
        }

        fun invalid(field: ConfigField, label: String, values: Collection<String>, valid: (String) -> Boolean) {
            val bad = values.map { it.trim() }.filter { it.isNotBlank() && !valid(it) }.distinct()
            if (bad.isNotEmpty()) add(field, "无效的$label：${bad.joinToString("、")}")
        }

        if (config.label.isBlank()) add(ConfigField.LABEL, "配置名称不能为空")
        if (config.networkName.isBlank()) add(ConfigField.NETWORK_NAME, "组网名称不能为空")

        if (config.dhcp) {
            invalid(ConfigField.DHCP_CIDR, "IPv4 DHCP 网段（需对齐网段地址，例如 10.126.126.0/24）", listOf(config.advanced.dhcpCidr), ::isIpv4NetworkCidr)
        } else if (config.ipv4.isNotBlank() && !isIpv4Cidr(config.ipv4.trim())) {
            add(ConfigField.IPV4, "虚拟 IPv4 地址必须是有效的 CIDR，例如 10.126.126.2/24")
        }

        if (!config.disableIpv6) {
            if (config.advanced.dhcpv6) {
                invalid(ConfigField.DHCPV6_CIDR, "IPv6 DHCP 网段（需对齐网段地址）", listOf(config.advanced.dhcpv6Cidr), ::isIpv6NetworkCidr)
            } else if (config.advanced.ipv6.isNotBlank() && !isIpv6Cidr(config.advanced.ipv6.trim())) {
                add(ConfigField.IPV6, "虚拟 IPv6 地址必须是有效的 CIDR")
            }
            if (config.advanced.ipv6PublicAddrProvider && config.advanced.ipv6PublicAddrPrefix.isNotBlank() &&
                !isIpv6NetworkCidr(config.advanced.ipv6PublicAddrPrefix.trim())
            ) {
                add(ConfigField.IPV6_PREFIX, "公网 IPv6 前缀必须是有效的网段 CIDR")
            }
        }

        invalid(ConfigField.PEER_URLS, "服务器地址", config.peerUrls, ::isEndpointUrl)
        invalid(ConfigField.LISTENERS, "监听地址", config.listenerUrls, ::isEndpointUrl)
        invalid(ConfigField.LISTENERS, "映射监听地址", config.advanced.mappedListeners, ::isEndpointUrl)
        invalid(
            ConfigField.STUN_SERVERS,
            "STUN 服务器",
            config.stunServers + config.advanced.tcpStunServers + config.advanced.stunServersV6,
            ::isEndpointOrSocket
        )
        invalid(ConfigField.ROUTES, "路由 CIDR（需对齐网段地址）", config.routes, ::isIpv4NetworkCidr)
        invalid(ConfigField.PROXY_CIDRS, "代理网段（需对齐网段地址）", config.proxyCidrs, ::isIpv4NetworkCidr)
        invalid(ConfigField.PROXY_CIDRS, "代理映射网段（需对齐网段地址）", config.advanced.proxyMappings.map { it.mappedCidr }, ::isIpv4NetworkCidr)
        invalid(ConfigField.EXIT_NODES, "出口节点地址", config.exitNodes, ::isIpAddress)

        val configuredPeers = config.peerUrls.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val peerKeys = config.advanced.peerKeys.filterKeys { it in configuredPeers }

        val securityDataPresent =
            config.advanced.privateKey.isNotBlank() ||
                config.advanced.publicKey.isNotBlank() ||
                config.advanced.managedCredentials.isNotEmpty() ||
                peerKeys.values.any { it.isNotBlank() }
        if (!config.advanced.secureMode && securityDataPresent) {
            add(ConfigField.SECURE_KEYS, "安全模式关闭时不能使用密钥或受管凭据；请启用安全模式或清空相关数据")
        }
        if (config.advanced.secureMode) {
            invalid(ConfigField.PEER_KEYS, "服务器公钥", peerKeys.values, ::isX25519Key)
            if (config.advanced.privateKey.isNotBlank() && !isX25519Key(config.advanced.privateKey.trim())) {
                add(ConfigField.SECURE_KEYS, "节点私钥必须是 32 字节 Base64 密钥")
            }
            if (config.advanced.publicKey.isNotBlank() && !isX25519Key(config.advanced.publicKey.trim())) {
                add(ConfigField.SECURE_KEYS, "节点公钥必须是 32 字节 Base64 密钥")
            }
            if (config.advanced.publicKey.isNotBlank() && config.advanced.privateKey.isBlank()) {
                add(ConfigField.SECURE_KEYS, "填写节点公钥时必须同时填写匹配的节点私钥")
            }
            val badCredentials = config.advanced.managedCredentials.filter {
                it.id.isBlank() || !isX25519Key(it.secret.trim())
            }
            if (badCredentials.isNotEmpty()) {
                add(ConfigField.MANAGED_CREDENTIALS, "受管凭据必须填写 ID 和 32 字节 Base64 密钥")
            }
        }

        val badRules = config.advanced.portForwards.filter {
            !isSocketAddress(it.bindAddr) || !isSocketAddress(it.dstAddr) || it.proto.lowercase() !in setOf("tcp", "udp")
        }
        if (badRules.isNotEmpty()) {
            add(ConfigField.PORT_FORWARDS, "端口转发需要填写有效的监听地址、目标地址和 tcp/udp 协议")
        }

        if (config.advanced.enableVpnPortal) {
            val badClients = config.advanced.vpnPortalClients.filter {
                it.name.isBlank() || !isIpv4Cidr(it.virtualIp)
            }
            if (!isSocketAddress(config.advanced.vpnPortalListen) || badClients.isNotEmpty()) {
                add(ConfigField.VPN_PORTAL, "WireGuard 监听地址或客户端虚拟地址无效")
            }
            if (config.advanced.vpnPortalPrivateKey.isNotBlank() && !isX25519Key(config.advanced.vpnPortalPrivateKey.trim())) {
                add(ConfigField.VPN_PORTAL, "WireGuard 私钥必须是 32 字节 Base64 密钥")
            }
        }

        if (config.noTun && config.proxyForwardBySystem) {
            add(ConfigField.SYSTEM_FORWARD, "无 TUN 模式不支持系统转发，请关闭其中一个选项")
        }

        runCatching { config.advanced.validate(config.multiThread, config.advanced.secureMode) }.onFailure {
            add(ConfigField.NUMERIC, it.message ?: "数值配置无效")
        }
        if (config.mtu < 0 || config.mtu > MAX_MTU) {
            add(ConfigField.NUMERIC, "MTU 必须在 0 到 $MAX_MTU 之间")
        }

        return ConfigValidationResult(errors.mapValues { it.value.distinct() })
    }

    private fun isEndpointUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        !uri.scheme.isNullOrBlank() && !uri.rawAuthority.isNullOrBlank() && value.none(Char::isWhitespace)
    }.getOrDefault(false)

    private fun isEndpointOrSocket(value: String): Boolean =
        isEndpointUrl(value) || isSocketAddress(value)

    private fun isIpv4NetworkCidr(value: String): Boolean = NetworkInput.isIpv4NetworkCidr(value)

    private fun isIpv6NetworkCidr(value: String): Boolean = NetworkInput.isIpv6NetworkCidr(value)

    private fun isIpv4Cidr(value: String): Boolean =
        value.contains('/') && Ipv4.cidrToRange(value.trim()) != null

    private fun isIpv6Cidr(value: String): Boolean {
        val trimmed = value.trim()
        val slash = trimmed.lastIndexOf('/')
        if (slash <= 0) return false
        val prefix = trimmed.substring(slash + 1).toIntOrNull() ?: return false
        if (prefix !in 0..128) return false
        return isIpAddress(trimmed.substring(0, slash), ipv6Only = true)
    }

    private fun isIpAddress(value: String, ipv6Only: Boolean = false): Boolean {
        val trimmed = value.trim().removeSurrounding("[", "]")
        if (trimmed.isBlank()) return false
        if (Ipv4.parseAddress(trimmed) != null) return !ipv6Only
        // Only feed literals with a colon to InetAddress; hostnames would trigger a
        // DNS lookup on the composition thread.
        if (!trimmed.contains(':')) return false
        return runCatching { InetAddress.getByName(trimmed) is Inet6Address }.getOrDefault(false)
    }

    private fun isSocketAddress(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return false
        val portIndex = trimmed.lastIndexOf(':')
        if (portIndex <= 0 || portIndex == trimmed.lastIndex) return false
        val port = trimmed.substring(portIndex + 1).toIntOrNull() ?: return false
        if (port !in 1..65535) return false
        val host = trimmed.substring(0, portIndex).removeSurrounding("[", "]")
        return host.isNotBlank() && isIpAddress(host) || host.matches(HOSTNAME_REGEX)
    }

    private fun isX25519Key(value: String): Boolean =
        value.matches(X25519_KEY_REGEX)

    private const val MAX_MTU = 1380
    private val HOSTNAME_REGEX = Regex("^[A-Za-z0-9.-]+$")
    private val X25519_KEY_REGEX = Regex("^[A-Za-z0-9+/]{43}=$")
}
