package cn.moonflow.easytier

import org.json.JSONArray
import org.json.JSONObject

/** Optional core settings. Blank numeric values retain the core defaults. */
data class AdvancedNetworkConfig(
    val dhcpCidr: String = "",
    val dhcpv6: Boolean = false,
    val dhcpv6Cidr: String = "",
    val ipv6: String = "",
    val ipv6PublicAddrProvider: Boolean = false,
    val ipv6PublicAddrAuto: Boolean = false,
    val ipv6PublicAddrPrefix: String = "",
    val secureMode: Boolean = false,
    val privateKey: String = "",
    val publicKey: String = "",
    val mappedListeners: List<String> = emptyList(),
    val tcpStunServers: List<String> = emptyList(),
    val stunServersV6: List<String> = emptyList(),
    val tcpWhitelist: List<String> = emptyList(),
    val udpWhitelist: List<String> = emptyList(),
    val socks5Proxy: String = "",
    val devName: String = "",
    val dataCompressAlgo: String = "none",
    val multiThreadCount: String = "",
    val instanceRecvBpsLimit: String = "",
    val foreignRelayBpsLimit: String = "",
    val tldDnsZone: String = "",
    val socketMark: String = "",
    val disableRelayData: Boolean = false,
    val enableUdpBroadcastRelay: Boolean = false,
    val preferPeerRelay: Boolean = false,
    val enableVpnPortal: Boolean = false,
    val vpnPortalListen: String = "0.0.0.0:22022",
    val vpnPortalPrivateKey: String = "",
    val extraRootToml: String = "",
    val extraFlagsToml: String = "",
    val extraSectionsToml: String = "",
    val managedCredentials: List<ManagedCredential> = emptyList(),
    val portForwards: List<PortForwardRule> = emptyList(),
    val vpnPortalClients: List<VpnPortalClient> = emptyList(),
    val proxyMappings: List<ProxyMapping> = emptyList(),
    val peerKeys: Map<String, String> = emptyMap()
) {
    fun validate() {
        fun unsigned(value: String, label: String, max: ULong = ULong.MAX_VALUE, nonZero: Boolean = false) {
            if (value.isBlank()) return
            val number = value.trim().toULongOrNull()
            require(number != null && number <= max && (!nonZero || number > 0u)) { "$label 必须是有效的非负整数${if (nonZero) "且大于 0" else ""}" }
        }
        unsigned(multiThreadCount, "工作线程数", UInt.MAX_VALUE.toULong(), true)
        unsigned(socketMark, "Socket Mark", UInt.MAX_VALUE.toULong())
        unsigned(instanceRecvBpsLimit, "接收限速")
        unsigned(foreignRelayBpsLimit, "中继限速")
        managedCredentials.forEach {
            require(it.expiryUnix.ifBlank { "0" }.toLongOrNull()?.let { value -> value >= 0 } == true) { "凭据过期时间必须是有效的 Unix 秒数" }
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("dhcp_cidr", dhcpCidr)
        .put("dhcpv6", dhcpv6)
        .put("dhcpv6_cidr", dhcpv6Cidr)
        .put("ipv6", ipv6)
        .put("ipv6_public_addr_provider", ipv6PublicAddrProvider)
        .put("ipv6_public_addr_auto", ipv6PublicAddrAuto)
        .put("ipv6_public_addr_prefix", ipv6PublicAddrPrefix)
        .put("secure_mode", secureMode)
        .put("local_private_key", privateKey)
        .put("local_public_key", publicKey)
        .put("mapped_listeners", mappedListeners.toJsonArray())
        .put("tcp_stun_servers", tcpStunServers.toJsonArray())
        .put("stun_servers_v6", stunServersV6.toJsonArray())
        .put("tcp_whitelist", tcpWhitelist.toJsonArray())
        .put("udp_whitelist", udpWhitelist.toJsonArray())
        .put("socks5_proxy", socks5Proxy)
        .put("dev_name", devName)
        .put("data_compress_algo", dataCompressAlgo)
        .put("multi_thread_count", multiThreadCount)
        .put("instance_recv_bps_limit", instanceRecvBpsLimit)
        .put("foreign_relay_bps_limit", foreignRelayBpsLimit)
        .put("tld_dns_zone", tldDnsZone)
        .put("socket_mark", socketMark)
        .put("disable_relay_data", disableRelayData)
        .put("enable_udp_broadcast_relay", enableUdpBroadcastRelay)
        .put("prefer_peer_relay", preferPeerRelay)
        .put("enable_vpn_portal", enableVpnPortal)
        .put("vpn_portal_listen", vpnPortalListen)
        .put("vpn_portal_private_key", vpnPortalPrivateKey)
        .put("extra_root_toml", extraRootToml)
        .put("extra_flags_toml", extraFlagsToml)
        .put("extra_sections_toml", extraSectionsToml)
        .put("managedCredentials", JSONArray().also { arr -> managedCredentials.forEach { arr.put(it.toJson()) } })
        .put("portForwards", JSONArray().also { arr -> portForwards.forEach { arr.put(it.toJson()) } })
        .put("vpnPortalClients", JSONArray().also { arr -> vpnPortalClients.forEach { arr.put(it.toJson()) } })
        .put("proxyMappings", JSONArray().also { arr -> proxyMappings.forEach { arr.put(it.toJson()) } })
        .put("peerKeys", JSONObject(peerKeys))

    companion object {
        fun fromJson(obj: JSONObject): AdvancedNetworkConfig = AdvancedNetworkConfig(
            dhcpCidr = obj.optString("dhcp_cidr", ""),
            dhcpv6 = obj.optBoolean("dhcpv6", false),
            dhcpv6Cidr = obj.optString("dhcpv6_cidr", ""),
            ipv6 = obj.optString("ipv6", ""),
            ipv6PublicAddrProvider = obj.optBoolean("ipv6_public_addr_provider", false),
            ipv6PublicAddrAuto = obj.optBoolean("ipv6_public_addr_auto", false),
            ipv6PublicAddrPrefix = obj.optString("ipv6_public_addr_prefix", ""),
            secureMode = obj.optBoolean("secure_mode", false),
            privateKey = obj.optString("local_private_key", ""),
            publicKey = obj.optString("local_public_key", ""),
            mappedListeners = obj.optStringArray("mapped_listeners"),
            tcpStunServers = obj.optStringArray("tcp_stun_servers"),
            stunServersV6 = obj.optStringArray("stun_servers_v6"),
            tcpWhitelist = obj.optStringArray("tcp_whitelist"),
            udpWhitelist = obj.optStringArray("udp_whitelist"),
            socks5Proxy = obj.optString("socks5_proxy", ""),
            devName = obj.optString("dev_name", ""),
            dataCompressAlgo = obj.optString("data_compress_algo", "none"),
            multiThreadCount = obj.optString("multi_thread_count", ""),
            instanceRecvBpsLimit = obj.optString("instance_recv_bps_limit", ""),
            foreignRelayBpsLimit = obj.optString("foreign_relay_bps_limit", ""),
            tldDnsZone = obj.optString("tld_dns_zone", ""),
            socketMark = obj.optString("socket_mark", ""),
            disableRelayData = obj.optBoolean("disable_relay_data", false),
            enableUdpBroadcastRelay = obj.optBoolean("enable_udp_broadcast_relay", false),
            preferPeerRelay = obj.optBoolean("prefer_peer_relay", false),
            enableVpnPortal = obj.optBoolean("enable_vpn_portal", false),
            vpnPortalListen = obj.optString("vpn_portal_listen", "0.0.0.0:22022"),
            vpnPortalPrivateKey = obj.optString("vpn_portal_private_key", ""),
            extraRootToml = obj.optString("extra_root_toml", ""),
            extraFlagsToml = obj.optString("extra_flags_toml", ""),
            extraSectionsToml = obj.optString("extra_sections_toml", ""),
            managedCredentials = obj.objects("managedCredentials").map { ManagedCredential.fromJson(it) },
            portForwards = obj.objects("portForwards").map { PortForwardRule(it.optString("bind_addr"), it.optString("dst_addr"), it.optString("proto", "tcp")) },
            vpnPortalClients = obj.objects("vpnPortalClients").map { VpnPortalClient(it.optString("name"), it.optString("virtual_ip"), it.optStringArray("groups")) },
            proxyMappings = obj.objects("proxyMappings").map { ProxyMapping(it.optString("cidr"), it.optString("mapped_cidr"), if (it.has("allow")) it.optStringArray("allow") else null) },
            peerKeys = obj.optJSONObject("peerKeys")?.let { keys -> keys.keys().asSequence().associateWith { keys.getString(it) } }.orEmpty()
        )
    }
}

data class PortForwardRule(val bindAddr: String = "", val dstAddr: String = "", val proto: String = "tcp") {
    fun toJson(): JSONObject = JSONObject().put("bind_addr", bindAddr).put("dst_addr", dstAddr).put("proto", proto)
}

data class VpnPortalClient(val name: String = "", val virtualIp: String = "", val groups: List<String> = emptyList()) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("virtual_ip", virtualIp).put("groups", groups.toJsonArray())
}

// Null allow means unrestricted; an explicitly empty list blocks all groups.
data class ProxyMapping(val cidr: String = "", val mappedCidr: String = "", val allow: List<String>? = null) {
    fun toJson(): JSONObject = JSONObject().put("cidr", cidr).put("mapped_cidr", mappedCidr).also { obj -> allow?.let { obj.put("allow", it.toJsonArray()) } }
}

private fun JSONObject.objects(key: String): List<JSONObject> = optJSONArray(key)?.let { arr ->
    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
}.orEmpty()

/** Administrator-issued node credentials; secrets stay local like network passwords. */
data class ManagedCredential(
    val id: String = "",
    val secret: String = "",
    val groups: List<String> = emptyList(),
    val allowRelay: Boolean = false,
    val allowedProxyCidrs: List<String> = emptyList(),
    val expiryUnix: String = "0",
    val reusable: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().put("credential_id", id).put("credential_secret", secret)
        .put("groups", groups.toJsonArray()).put("allow_relay", allowRelay)
        .put("allowed_proxy_cidrs", allowedProxyCidrs.toJsonArray()).put("expiry_unix", expiryUnix).put("reusable", reusable)
    companion object {
        fun fromJson(obj: JSONObject): ManagedCredential = ManagedCredential(
            obj.optString("credential_id"), obj.optString("credential_secret"), obj.optStringArray("groups"),
            obj.optBoolean("allow_relay", false), obj.optStringArray("allowed_proxy_cidrs"),
            obj.optString("expiry_unix", "0"), obj.optBoolean("reusable", true)
        )
    }
}
