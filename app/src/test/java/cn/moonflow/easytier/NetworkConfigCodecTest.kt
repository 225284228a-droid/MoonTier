package cn.moonflow.easytier

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NetworkConfigCodecTest {
    private val fixture = """
        instance_id = "c4390599-eac2-4587-b39a-f4f23f8e3101"
        dhcp = '10.30.0.0/24'
        dhcpv6 = "fd12:3456::/64"
        ipv6_public_addr_provider = true
        ipv6_public_addr_auto = true
        ipv6_public_addr_prefix = "2001:db8:1::/64"
        mapped_listeners = ['tcp://example.com:11010']
        tcp_stun_servers = ["stun.example.com:3478"]
        stun_servers_v6 = ['[2001:db8::1]:3478']
        tcp_whitelist = ['22', '8000-9000']
        udp_whitelist = ["53"]
        socks5_proxy = "socks5://127.0.0.1:1080"
        [network_identity]
        network_name = "secure-network"
        network_secret = ""
        [secure_mode]
        enabled = true
        local_private_key = "test-private-key"
        local_public_key = "test-public-key"
        [flags]
        data_compress_algo = "zstd"
        dev_name = "et-test"
        multi_thread_count = 4
        instance_recv_bps_limit = "18446744073709551615"
        foreign_relay_bps_limit = 1048576
        socket_mark = 0
        tld_dns_zone = "mesh.example."
        disable_relay_data = true
        enable_udp_broadcast_relay = true
        prefer_peer_relay = true
        [[peer]]
        uri = "tcp://example.com:11010"
        peer_public_key = "peer-key"
        [[proxy_network]]
        cidr = "192.168.1.0/24"
        mapped_cidr = "10.168.1.0/24"
        allow = []
        [[proxy_network]]
        cidr = "192.168.2.0/24"
        allow = ["trusted"]
        [[port_forward]]
        bind_addr = "127.0.0.1:8080"
        dst_addr = "10.30.0.2:80"
        proto = "tcp"
        [[managed_credentials]]
        credential_id = "guest"
        credential_secret = "guest-key"
        groups = ["trusted"]
        allow_relay = true
        allowed_proxy_cidrs = ["192.168.1.0/24"]
        expiry_unix = 1900000000
        reusable = false
        [vpn_portal_config]
        enabled = true
        wireguard_listen = "0.0.0.0:22022"
        wireguard_private_key = "wg-key"
        [[vpn_portal_config.clients]]
        name = "phone"
        virtual_ip = "10.30.0.10/24"
        groups = ["trusted"]
        [acl]
        default_action = "accept"
    """.trimIndent()

    @Test fun advancedSettingsSurviveTomlAndStorageRoundTrips() {
        val config = TomlCodec.parse(fixture)
        assertTrue(config.dhcp)
        assertTrue(config.advanced.dhcpv6)
        assertEquals("10.30.0.0/24", config.advanced.dhcpCidr)
        assertEquals("fd12:3456::/64", config.advanced.dhcpv6Cidr)
        assertEquals("18446744073709551615", config.advanced.instanceRecvBpsLimit)
        assertEquals("0", config.advanced.socketMark)
        assertEquals(emptyList<String>(), config.advanced.proxyMappings[0].allow)
        assertEquals(listOf("trusted"), config.advanced.proxyMappings[1].allow)
        assertFalse(config.advanced.managedCredentials.single().reusable)
        assertEquals(config, NetworkConfig.fromJson(JSONObject(config.toJson().toString())))
        val toml = TomlCodec.build(config)
        assertTrue(toml.contains("instance_recv_bps_limit = \"18446744073709551615\""))
        assertTrue(toml.contains("data_compress_algo = \"Zstd\""))
        assertEquals(config, TomlCodec.parse(toml))
    }

    @Test fun staticIpv4ImportDoesNotAccidentallyEnableDhcp() {
        val config = TomlCodec.parse("ipv4 = '10.30.0.2/24'\nipv6 = 'fd12::2/64'")
        assertFalse(config.dhcp)
        assertFalse(config.advanced.dhcpv6)
        val restored = TomlCodec.parse(TomlCodec.build(config))
        assertEquals("10.30.0.2/24", restored.ipv4)
        assertEquals("fd12::2/64", restored.advanced.ipv6)
    }

    @Test fun olderSavedProfilesKeepTheirDefaults() {
        val config = NetworkConfig.fromJson(JSONObject().put("ipv4", "10.30.0.2").put("dhcp", false))
        assertFalse(config.advanced.secureMode)
        assertFalse(config.advanced.dhcpv6)
        assertTrue(config.advanced.portForwards.isEmpty())
        assertEquals("none", config.advanced.dataCompressAlgo)
        val toml = TomlCodec.build(config)
        assertFalse(toml.contains("[secure_mode]"))
        assertTrue(toml.contains("data_compress_algo = \"None\""))
    }

    @Test fun extensionSectionsAndUnknownFlagsAreRetainedWhenEditing() {
        val original = """
            credential_file = '/data/local/credentials.json'
            [flags]
            future_flag = 42
            [source]
            url = "udp://example.com/admin#label"
            [acl]
            default_action = "accept"
        """.trimIndent()
        val config = TomlCodec.parse(original)
        val restored = TomlCodec.parse(TomlCodec.build(config.copy(hostname = "edited")))
        assertEquals(config.advanced, restored.advanced)
        assertEquals(config.aclToml, restored.aclToml)
        assertTrue(restored.advanced.extraSectionsToml.contains("admin#label"))
    }

    @Test fun stringsWithCommentsBracketsAndEscapesStayIntact() {
        val config = NetworkConfig(networkSecret = "line1\nline2\t# [\"quoted\"] \\path", isDefault = false,
            advanced = AdvancedNetworkConfig(stunServersV6 = listOf("[2001:db8::1]:3478")))
        val restored = TomlCodec.parse(TomlCodec.build(config))
        assertEquals(config.networkSecret, restored.networkSecret)
        assertEquals(config.advanced.stunServersV6, restored.advanced.stunServersV6)
    }

    @Test fun invalidNumericInputIsRejectedBeforeBuildingCoreConfig() {
        for (value in listOf("-1", "18446744073709551616", "0\nno_tun = true")) {
            assertThrows(IllegalArgumentException::class.java) {
                TomlCodec.build(NetworkConfig(advanced = AdvancedNetworkConfig(instanceRecvBpsLimit = value)))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            TomlCodec.build(NetworkConfig(advanced = AdvancedNetworkConfig(multiThreadCount = "0")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TomlCodec.build(NetworkConfig(advanced = AdvancedNetworkConfig(socketMark = "4294967296")))
        }
    }
}
