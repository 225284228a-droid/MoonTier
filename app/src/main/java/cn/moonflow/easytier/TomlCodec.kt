package cn.moonflow.easytier

import android.util.Log

object TomlCodec {
    fun build(config: NetworkConfig): String {
        val out = ArrayList<String>()
        val advanced = config.advanced
        advanced.validate()
        fun text(key: String, value: String) { if (value.isNotBlank()) out += "$key = \"${esc(value)}\"" }
        fun list(key: String, values: List<String>) { if (values.isNotEmpty()) out += "$key = ${array(values)}" }
        out += "instance_id = \"${esc(config.id)}\""
        out += "network_label = \"${esc(config.label)}\""
        out += "instance_name = \"${esc(config.instanceName)}\""
        if (config.rpcPortal.isNotBlank()) out += "rpc_portal = \"${esc(config.rpcPortal)}\""
        if (config.hostname.isNotBlank()) out += "hostname = \"${esc(config.hostname)}\""
        out += "dhcp = ${if (config.dhcp && advanced.dhcpCidr.isNotBlank()) "\"${esc(advanced.dhcpCidr.trim())}\"" else config.dhcp.toString()}"
        out += "dhcpv6 = ${if (advanced.dhcpv6 && advanced.dhcpv6Cidr.isNotBlank()) "\"${esc(advanced.dhcpv6Cidr.trim())}\"" else advanced.dhcpv6.toString()}"
        if (!advanced.dhcpv6) text("ipv6", advanced.ipv6)
        out += "ipv6_public_addr_provider = ${advanced.ipv6PublicAddrProvider}"
        out += "ipv6_public_addr_auto = ${advanced.ipv6PublicAddrAuto}"
        text("ipv6_public_addr_prefix", advanced.ipv6PublicAddrPrefix)
        text("socks5_proxy", advanced.socks5Proxy)
        list("mapped_listeners", advanced.mappedListeners)
        list("tcp_stun_servers", advanced.tcpStunServers)
        list("stun_servers_v6", advanced.stunServersV6)
        list("tcp_whitelist", advanced.tcpWhitelist)
        list("udp_whitelist", advanced.udpWhitelist)
        if (advanced.extraRootToml.isNotBlank()) out += advanced.extraRootToml
        if (!config.dhcp && config.ipv4.isNotBlank()) out += "ipv4 = \"${esc(config.ipv4)}\""
        if (config.listenerUrls.isNotEmpty()) out += "listeners = ${array(config.listenerUrls)}"
        if (config.stunServers.isNotEmpty()) out += "stun_servers = ${array(config.stunServers)}"
        if (config.enableManualRoutes) out += "routes = ${array(config.routes)}"
        if (config.exitNodes.isNotEmpty()) out += "exit_nodes = ${array(config.exitNodes)}"

        out += ""
        out += "[network_identity]"
        out += "network_name = \"${esc(config.networkName)}\""
        out += "network_secret = \"${esc(config.networkSecret)}\""

        out += ""
        out += "[flags]"
        text("dev_name", advanced.devName)
        text("data_compress_algo", when (advanced.dataCompressAlgo.lowercase()) { "none", "1" -> "None"; "zstd", "2" -> "Zstd"; else -> advanced.dataCompressAlgo })
        if (advanced.multiThreadCount.isNotBlank()) out += "multi_thread_count = ${advanced.multiThreadCount.trim()}"
        unsigned64("instance_recv_bps_limit", advanced.instanceRecvBpsLimit, out)
        unsigned64("foreign_relay_bps_limit", advanced.foreignRelayBpsLimit, out)
        text("tld_dns_zone", advanced.tldDnsZone)
        if (advanced.socketMark.isNotBlank()) out += "socket_mark = ${advanced.socketMark.trim()}"
        out += "disable_relay_data = ${advanced.disableRelayData}"
        out += "enable_udp_broadcast_relay = ${advanced.enableUdpBroadcastRelay}"
        out += "prefer_peer_relay = ${advanced.preferPeerRelay}"
        if (advanced.extraFlagsToml.isNotBlank()) out += advanced.extraFlagsToml
        out += "enable_encryption = ${config.enableEncryption}"
        out += "enable_ipv6 = ${!config.disableIpv6}"
        out += "latency_first = ${config.latencyFirst}"
        out += "enable_exit_node = ${config.enableExitNode}"
        out += "no_tun = ${config.noTun}"
        out += "use_smoltcp = ${config.useSmoltcp}"
        out += "enable_kcp_proxy = ${config.enableKcpProxy}"
        out += "disable_kcp_input = ${config.disableKcpInput}"
        out += "enable_quic_proxy = ${config.enableQuicProxy}"
        out += "disable_quic_input = ${config.disableQuicInput}"
        out += "disable_relay_kcp = ${config.disableRelayKcp}"
        out += "disable_relay_quic = ${config.disableRelayQuic}"
        out += "enable_relay_foreign_network_kcp = ${config.enableRelayForeignNetworkKcp}"
        out += "enable_relay_foreign_network_quic = ${config.enableRelayForeignNetworkQuic}"
        out += "bind_device = ${config.bindDevice}"
        out += "private_mode = ${config.enablePrivateMode}"
        out += "disable_p2p = ${config.disableP2p}"
        out += "need_p2p = ${config.needP2p}"
        out += "lazy_p2p = ${config.lazyP2p}"
        out += "p2p_only = ${config.p2pOnly}"
        out += "multi_thread = ${config.multiThread}"
        out += "accept_dns = ${config.enableMagicDns}"
        out += "disable_sym_hole_punching = ${config.disableSymHolePunching}"
        out += "relay_all_peer_rpc = ${config.relayAllPeerRpc}"
        out += "disable_udp_hole_punching = ${config.disableUdpHolePunching}"
        out += "disable_tcp_hole_punching = ${config.disableTcpHolePunching}"
        out += "disable_upnp = ${config.disableUpnp}"
        out += "proxy_forward_by_system = ${config.proxyForwardBySystem}"
        if (config.mtu > 0) out += "mtu = ${config.mtu}"
        if (config.enableRelayNetworkWhitelist) {
            out += "relay_network_whitelist = \"${esc(config.relayNetworkWhitelist.cleanItems().joinToString(" "))}\""
        }
        out += "default_protocol = \"${esc(config.defaultProtocol)}\""
        out += "encryption_algorithm = \"${esc(config.encryptionAlgorithm)}\""

        config.peerUrls.cleanItems().forEach {
            out += ""
            out += "[[peer]]"
            out += "uri = \"${esc(it)}\""
            text("peer_public_key", advanced.peerKeys[it].orEmpty())
        }

        config.proxyCidrs.cleanItems().forEach { cidr ->
            out += ""
            out += "[[proxy_network]]"
            text("cidr", cidr)
            advanced.proxyMappings.firstOrNull { it.cidr == cidr }?.let { mapping ->
                text("mapped_cidr", mapping.mappedCidr)
                mapping.allow?.let { out += "allow = ${array(it)}" }
            }
        }
        if (advanced.secureMode || advanced.privateKey.isNotBlank() || advanced.publicKey.isNotBlank()) {
            out += ""
            out += "[secure_mode]"
            out += "enabled = ${advanced.secureMode}"
            text("local_private_key", advanced.privateKey)
            text("local_public_key", advanced.publicKey)
        }
        advanced.managedCredentials.forEach { credential ->
            out += ""
            out += "[[managed_credentials]]"
            text("credential_id", credential.id)
            text("credential_secret", credential.secret)
            list("groups", credential.groups)
            out += "allow_relay = ${credential.allowRelay}"
            list("allowed_proxy_cidrs", credential.allowedProxyCidrs)
            out += "expiry_unix = ${credential.expiryUnix.ifBlank { "0" }}"
            out += "reusable = ${credential.reusable}"
        }
        advanced.portForwards.forEach { rule ->
            out += ""
            out += "[[port_forward]]"
            text("bind_addr", rule.bindAddr)
            text("dst_addr", rule.dstAddr)
            text("proto", rule.proto)
        }
        if (advanced.enableVpnPortal || advanced.vpnPortalClients.isNotEmpty() || advanced.vpnPortalPrivateKey.isNotBlank()) {
            out += ""
            out += "[vpn_portal_config]"
            out += "enabled = ${advanced.enableVpnPortal}"
            text("wireguard_listen", advanced.vpnPortalListen)
            text("wireguard_private_key", advanced.vpnPortalPrivateKey)
            advanced.vpnPortalClients.forEach { client ->
                out += ""
                out += "[[vpn_portal_config.clients]]"
                text("name", client.name)
                text("virtual_ip", client.virtualIp)
                list("groups", client.groups)
            }
        }
        if (advanced.extraSectionsToml.isNotBlank()) {
            out += ""
            out += advanced.extraSectionsToml
        }
        val aclToml = config.aclToml.trim()
        if (aclToml.isNotBlank()) {
            out += ""
            out += aclToml
        }
        return out.joinToString("\n")
    }

    fun parse(text: String): NetworkConfig {
        var currentSection = ""
        val root = LinkedHashMap<String, String>()
        val network = LinkedHashMap<String, String>()
        val flags = LinkedHashMap<String, String>()
        val proxyEntries = ArrayList<MutableMap<String, String>>()
        val peerEntries = ArrayList<MutableMap<String, String>>()
        val forwardEntries = ArrayList<MutableMap<String, String>>()
        val credentialEntries = ArrayList<MutableMap<String, String>>()
        val clientEntries = ArrayList<MutableMap<String, String>>()
        val secure = LinkedHashMap<String, String>()
        val portal = LinkedHashMap<String, String>()
        val aclToml = extractAclToml(text)

        logicalLines(text).forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("[[") && line.endsWith("]]")) {
                currentSection = line.removePrefix("[[").removeSuffix("]]").trim()
                when (currentSection) {
                    "managed_credentials" -> credentialEntries.add(linkedMapOf())
                    "peer" -> peerEntries.add(linkedMapOf())
                    "proxy_network" -> proxyEntries.add(linkedMapOf())
                    "port_forward" -> forwardEntries.add(linkedMapOf())
                    "vpn_portal_config.clients" -> clientEntries.add(linkedMapOf())
                }
                return@forEach
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.removePrefix("[").removeSuffix("]").trim()
                return@forEach
            }

            val index = line.indexOf('=')
            if (index <= 0) return@forEach
            val key = line.substring(0, index).trim()
            val value = line.substring(index + 1).trim()
            when (currentSection) {
                "" -> root[key] = value
                "network_identity" -> network[key] = value
                "flags" -> flags[key] = value
                "managed_credentials" -> credentialEntries.lastOrNull()?.set(key, value)
                "peer" -> peerEntries.lastOrNull()?.set(key, value)
                "proxy_network" -> proxyEntries.lastOrNull()?.set(key, value)
                "port_forward" -> forwardEntries.lastOrNull()?.set(key, value)
                "vpn_portal_config.clients" -> clientEntries.lastOrNull()?.set(key, value)
                "secure_mode" -> secure[key] = value
                "vpn_portal_config" -> portal[key] = value
            }
        }

        val id = scalar(root["instance_id"].orEmpty()).ifBlank { java.util.UUID.randomUUID().toString() }
        val name = scalar(network["network_name"].orEmpty()).ifBlank { scalar(root["network"].orEmpty()).ifBlank { "default" } }
        val relayWhitelistRaw = flags["relay_network_whitelist"]?.let { scalar(it) }
        val relayWhitelist = relayWhitelistRaw.orEmpty()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it != "*" }

        return NetworkConfig(
            id = id,
            label = scalar(root["network_label"].orEmpty()).ifBlank { name.ifBlank { "导入配置" } },
            instanceName = scalar(root["instance_name"].orEmpty()).ifBlank { "MoonTier-${id.take(8)}" },
            hostname = scalar(root["hostname"].orEmpty()),
            networkName = name,
            networkSecret = scalar(network["network_secret"].orEmpty()),
            dhcp = dhcpEnabled(root["dhcp"], !root.containsKey("ipv4")),
            ipv4 = scalar(root["ipv4"].orEmpty()),
            peerUrls = peerEntries.map { scalar(it["uri"].orEmpty()) }.cleanItems(),
            stunServers = strings(root["stun_servers"]),
            listenerUrls = strings(root["listeners"]),
            proxyCidrs = proxyEntries.map { scalar(it["cidr"].orEmpty()) }.cleanItems(),
            enableManualRoutes = root.containsKey("routes"),
            routes = strings(root["routes"]),
            exitNodes = strings(root["exit_nodes"]),
            enableRelayNetworkWhitelist = relayWhitelistRaw != null && relayWhitelistRaw != "*",
            relayNetworkWhitelist = relayWhitelist.cleanItems(),
            proxyForwardBySystem = bool(flags["proxy_forward_by_system"], false),
            enableExitNode = bool(flags["enable_exit_node"], false),
            enableMagicDns = bool(flags["accept_dns"], false),
            enablePrivateMode = bool(flags["private_mode"], true),
            enableEncryption = bool(flags["enable_encryption"], true),
            enableKcpProxy = bool(flags["enable_kcp_proxy"], false),
            disableKcpInput = bool(flags["disable_kcp_input"], false),
            enableQuicProxy = bool(flags["enable_quic_proxy"], false),
            disableQuicInput = bool(flags["disable_quic_input"], false),
            disableRelayKcp = bool(flags["disable_relay_kcp"], false),
            disableRelayQuic = bool(flags["disable_relay_quic"], false),
            enableRelayForeignNetworkKcp = bool(flags["enable_relay_foreign_network_kcp"], false),
            enableRelayForeignNetworkQuic = bool(flags["enable_relay_foreign_network_quic"], false),
            latencyFirst = bool(flags["latency_first"], false),
            noTun = bool(flags["no_tun"], false),
            useSmoltcp = bool(flags["use_smoltcp"], false),
            bindDevice = bool(flags["bind_device"], true),
            multiThread = bool(flags["multi_thread"], true),
            disableUdpHolePunching = bool(flags["disable_udp_hole_punching"], false),
            disableTcpHolePunching = bool(flags["disable_tcp_hole_punching"], false),
            disableUpnp = bool(flags["disable_upnp"], false),
            needP2p = bool(flags["need_p2p"], false),
            lazyP2p = bool(flags["lazy_p2p"], false),
            p2pOnly = bool(flags["p2p_only"], false),
            disableP2p = bool(flags["disable_p2p"], false),
            disableSymHolePunching = bool(flags["disable_sym_hole_punching"], false),
            disableIpv6 = !bool(flags["enable_ipv6"], true),
            relayAllPeerRpc = bool(flags["relay_all_peer_rpc"], false),
            mtu = scalar(flags["mtu"].orEmpty()).toIntOrNull() ?: 0,
            rpcPortal = scalar(root["rpc_portal"].orEmpty()),
            defaultProtocol = scalar(flags["default_protocol"].orEmpty()).ifBlank { "tcp" },
            encryptionAlgorithm = scalar(flags["encryption_algorithm"].orEmpty()).ifBlank { "aes-gcm" },
            advanced = AdvancedNetworkConfig(
                dhcpCidr = dhcpCidr(root["dhcp"]),
                dhcpv6 = dhcpEnabled(root["dhcpv6"], false),
                dhcpv6Cidr = dhcpCidr(root["dhcpv6"]),
                ipv6 = scalar(root["ipv6"].orEmpty()),
                secureMode = bool(secure["enabled"], false),
                privateKey = scalar(secure["local_private_key"].orEmpty()),
                publicKey = scalar(secure["local_public_key"].orEmpty()),
                ipv6PublicAddrProvider = bool(root["ipv6_public_addr_provider"], false),
                ipv6PublicAddrAuto = bool(root["ipv6_public_addr_auto"], false),
                ipv6PublicAddrPrefix = scalar(root["ipv6_public_addr_prefix"].orEmpty()),
                socks5Proxy = scalar(root["socks5_proxy"].orEmpty()),
                mappedListeners = strings(root["mapped_listeners"]),
                tcpStunServers = strings(root["tcp_stun_servers"]),
                stunServersV6 = strings(root["stun_servers_v6"]),
                tcpWhitelist = strings(root["tcp_whitelist"]),
                udpWhitelist = strings(root["udp_whitelist"]),
                devName = scalar(flags["dev_name"].orEmpty()),
                dataCompressAlgo = scalar(flags["data_compress_algo"].orEmpty()).lowercase().let { when (it) { "", "1" -> "none"; "2" -> "zstd"; else -> it } },
                multiThreadCount = scalar(flags["multi_thread_count"].orEmpty()),
                instanceRecvBpsLimit = scalar(flags["instance_recv_bps_limit"].orEmpty()),
                foreignRelayBpsLimit = scalar(flags["foreign_relay_bps_limit"].orEmpty()),
                tldDnsZone = scalar(flags["tld_dns_zone"].orEmpty()),
                socketMark = scalar(flags["socket_mark"].orEmpty()),
                disableRelayData = bool(flags["disable_relay_data"], false),
                enableUdpBroadcastRelay = bool(flags["enable_udp_broadcast_relay"], false),
                preferPeerRelay = bool(flags["prefer_peer_relay"], false),
                enableVpnPortal = if (portal.isEmpty()) false else bool(portal["enabled"], true),
                vpnPortalListen = scalar(portal["wireguard_listen"].orEmpty()).ifBlank { "0.0.0.0:22022" },
                vpnPortalPrivateKey = scalar(portal["wireguard_private_key"].orEmpty()),
                managedCredentials = credentialEntries.map { ManagedCredential(
                    scalar(it["credential_id"].orEmpty()), scalar(it["credential_secret"].orEmpty()), strings(it["groups"]),
                    bool(it["allow_relay"], false), strings(it["allowed_proxy_cidrs"]), scalar(it["expiry_unix"].orEmpty()).ifBlank { "0" }, bool(it["reusable"], true)
                ) },
                portForwards = forwardEntries.map { PortForwardRule(scalar(it["bind_addr"].orEmpty()), scalar(it["dst_addr"].orEmpty()), scalar(it["proto"].orEmpty()).ifBlank { "tcp" }) },
                vpnPortalClients = clientEntries.map { VpnPortalClient(scalar(it["name"].orEmpty()), scalar(it["virtual_ip"].orEmpty()), strings(it["groups"])) },
                proxyMappings = proxyEntries.filter { it.containsKey("mapped_cidr") || it.containsKey("allow") }.map { ProxyMapping(scalar(it["cidr"].orEmpty()), scalar(it["mapped_cidr"].orEmpty()), it["allow"]?.let(::strings)) },
                peerKeys = peerEntries.filter { it.containsKey("peer_public_key") }.associate { scalar(it["uri"].orEmpty()) to scalar(it["peer_public_key"].orEmpty()) },
                extraRootToml = root.filterKeys { it !in knownRootKeys }.entries.joinToString("\n") { "${it.key} = ${it.value}" },
                extraFlagsToml = flags.filterKeys { it !in knownFlagKeys }.entries.joinToString("\n") { "${it.key} = ${it.value}" },
                extraSectionsToml = extractExtraSections(text)
            ),
            aclToml = aclToml,
            isDefault = false
        )
    }

    private val knownRootKeys = setOf(
        "dhcp", "dhcpv6", "exit_nodes", "hostname", "instance_id", "instance_name", "ipv4", "ipv6",
        "ipv6_public_addr_auto", "ipv6_public_addr_prefix", "ipv6_public_addr_provider", "listeners",
        "mapped_listeners", "network", "network_label", "routes", "rpc_portal", "socks5_proxy",
        "stun_servers", "stun_servers_v6", "tcp_stun_servers", "tcp_whitelist", "udp_whitelist"
    )

    private val knownFlagKeys = setOf(
        "accept_dns", "bind_device", "data_compress_algo", "default_protocol", "dev_name",
        "disable_kcp_input", "disable_p2p", "disable_quic_input", "disable_relay_data",
        "disable_relay_kcp", "disable_relay_quic", "disable_sym_hole_punching",
        "disable_tcp_hole_punching", "disable_udp_hole_punching", "disable_upnp", "enable_encryption",
        "enable_exit_node", "enable_ipv6", "enable_kcp_proxy", "enable_quic_proxy",
        "enable_relay_foreign_network_kcp", "enable_relay_foreign_network_quic",
        "enable_udp_broadcast_relay", "encryption_algorithm", "foreign_relay_bps_limit",
        "instance_recv_bps_limit", "latency_first", "lazy_p2p", "mtu", "multi_thread",
        "multi_thread_count", "need_p2p", "no_tun", "p2p_only", "prefer_peer_relay", "private_mode",
        "proxy_forward_by_system", "relay_all_peer_rpc", "relay_network_whitelist", "socket_mark",
        "tld_dns_zone", "use_smoltcp"
    )

    // TOML integers are signed 64-bit. Protobuf JSON accepts strings for larger uint64 values.
    private fun unsigned64(key: String, value: String, out: MutableList<String>) {
        if (value.isBlank()) return
        val number = value.trim()
        out += if (number.toULong() > Long.MAX_VALUE.toULong()) "$key = \"$number\"" else "$key = $number"
    }

    private fun esc(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    private fun array(values: List<String>): String =
        values.cleanItems().joinToString(prefix = "[", postfix = "]") { "\"${esc(it)}\"" }

    private fun scalar(value: String): String {
        var text = value.trim()
        if (text.startsWith("'") && text.endsWith("'") && text.length >= 2) return text.substring(1, text.length - 1)
        if (text.startsWith("\"") && text.endsWith("\"") && text.length >= 2) {
            text = text.substring(1, text.length - 1)
        }
        val out = StringBuilder()
        var escaped = false
        text.forEach { ch ->
            if (escaped) {
                out.append(when (ch) { 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; else -> ch })
                escaped = false
            } else if (ch == '\\') escaped = true else out.append(ch)
        }
        if (escaped) out.append('\\')
        return out.toString()
    }

    private fun dhcpEnabled(value: String?, default: Boolean): Boolean =
        if (dhcpCidr(value).isNotEmpty()) true else bool(value, default)

    private fun dhcpCidr(value: String?): String = scalar(value.orEmpty()).takeIf { '/' in it }.orEmpty()

    private fun extractExtraSections(text: String): String {
        val known = setOf("managed_credentials", "network_identity", "flags", "peer", "proxy_network", "secure_mode", "port_forward", "vpn_portal_config", "vpn_portal_config.clients")
        var capturing = false
        return text.lineSequence().filter { raw ->
            val line = stripComment(raw).trim()
            if (isSectionHeader(line)) {
                val name = line.trim('[', ']').trim()
                capturing = name !in known && !isAclSectionHeader(line)
            }
            capturing
        }.joinToString("\n").trim()
    }

    private fun bool(value: String?, default: Boolean): Boolean =
        value?.trim()?.lowercase()?.let {
            when (it) {
                "true" -> true
                "false" -> false
                else -> {
                    Log.w("TomlCodec", "Unexpected bool value: \"$value\", falling back to $default")
                    default
                }
            }
        } ?: default

    private fun strings(value: String?): List<String> {
        val raw = value?.trim().orEmpty()
        if (!raw.startsWith("[") || !raw.endsWith("]")) return emptyList()
        val out = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        raw.substring(1, raw.length - 1).forEach { ch ->
            if (escaped) {
                current.append(when (ch) { 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; else -> ch })
                escaped = false
            } else if (ch == '\\' && quote == '"') {
                escaped = true
            } else if (quote != null) {
                if (ch == quote) { out += current.toString(); current.clear(); quote = null }
                else current.append(ch)
            } else if (ch == '"' || ch == '\'') {
                quote = ch
            }
        }
        return out.cleanItems()
    }

    private fun logicalLines(text: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var brackets = 0
        text.lineSequence().forEach { raw ->
            val line = stripComment(raw).trim()
            if (line.isEmpty()) return@forEach
            if (current.isNotEmpty()) current.append(' ')
            current.append(line)
            var quote: Char? = null
            var escaped = false
            line.forEach { ch ->
                if (escaped) escaped = false
                else if (ch == '\\' && quote == '"') escaped = true
                else if (quote != null) { if (ch == quote) quote = null }
                else when (ch) {
                    '"', '\'' -> quote = ch
                    '[' -> brackets++
                    ']' -> brackets--
                }
            }
            if (brackets <= 0) {
                out += current.toString()
                current.clear()
                brackets = 0
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    private fun extractAclToml(text: String): String {
        val out = ArrayList<String>()
        var capturing = false
        text.lineSequence().forEach { raw ->
            val trimmed = raw.trim()
            if (isSectionHeader(trimmed)) {
                capturing = isAclSectionHeader(trimmed)
            }
            if (capturing) out += raw.trimEnd()
        }
        return out
            .dropWhile { it.isBlank() }
            .dropLastWhile { it.isBlank() }
            .joinToString("\n")
    }

    private fun isSectionHeader(line: String): Boolean =
        (line.startsWith("[[") && line.endsWith("]]")) || (line.startsWith("[") && line.endsWith("]"))

    private fun isAclSectionHeader(line: String): Boolean {
        if (!isSectionHeader(line)) return false
        val section = if (line.startsWith("[[")) {
            line.removePrefix("[[").removeSuffix("]]").trim()
        } else {
            line.removePrefix("[").removeSuffix("]").trim()
        }
        return section.startsWith("acl", ignoreCase = true)
    }

    private fun stripComment(line: String): String {
        var quote: Char? = null
        var escaped = false
        line.forEachIndexed { index, ch ->
            if (escaped) escaped = false
            else if (ch == '\\' && quote == '"') escaped = true
            else if (quote != null) { if (ch == quote) quote = null }
            else when (ch) {
                '"', '\'' -> quote = ch
                '#' -> return line.substring(0, index)
            }
        }
        return line
    }
}
