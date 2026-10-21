package cn.moonflow.easytier

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkConfigValidationTest {
    @Test fun validDefaultConfigPassesGuiValidation() {
        assertTrue(NetworkConfigValidation.validate(NetworkConfig()).isValid)
    }

    @Test fun securityDataRequiresEnabledSecureModeAndPrivateKey() {
        val key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val disabled = NetworkConfigValidation.validate(
            NetworkConfig(advanced = AdvancedNetworkConfig(secureMode = false, privateKey = key))
        )
        assertNotNull(disabled.error(ConfigField.SECURE_KEYS))

        val publicOnly = NetworkConfigValidation.validate(
            NetworkConfig(advanced = AdvancedNetworkConfig(secureMode = true, publicKey = key))
        )
        assertNotNull(publicOnly.error(ConfigField.SECURE_KEYS))

        val valid = NetworkConfigValidation.validate(
            NetworkConfig(advanced = AdvancedNetworkConfig(secureMode = true, privateKey = key, publicKey = key))
        )
        assertNull(valid.error(ConfigField.SECURE_KEYS))
    }

    @Test fun invalidAddressesAreReportedAgainstTheirFields() {
        val result = NetworkConfigValidation.validate(
            NetworkConfig(
                dhcp = false,
                ipv4 = "10.0.0.2",
                peerUrls = listOf("not a url"),
                routes = listOf("10.0.0.0/99"),
                exitNodes = listOf("not-an-ip")
            )
        )
        assertNotNull(result.error(ConfigField.IPV4))
        assertNotNull(result.error(ConfigField.PEER_URLS))
        assertNotNull(result.error(ConfigField.ROUTES))
        assertNotNull(result.error(ConfigField.EXIT_NODES))
    }

    @Test fun noTunAndSystemForwardingAreIncompatible() {
        val result = NetworkConfigValidation.validate(
            NetworkConfig(noTun = true, proxyForwardBySystem = true)
        )
        assertFalse(result.isValid)
        assertNotNull(result.error(ConfigField.SYSTEM_FORWARD))
    }

    @Test fun networkCidrsMustBeAddressAligned() {
        val dhcp = NetworkConfigValidation.validate(
            NetworkConfig(dhcp = true, advanced = AdvancedNetworkConfig(dhcpCidr = "10.127.127.8/24"))
        )
        assertNotNull(dhcp.error(ConfigField.DHCP_CIDR))

        val routes = NetworkConfigValidation.validate(
            NetworkConfig(routes = listOf("10.0.0.8/24"))
        )
        assertNotNull(routes.error(ConfigField.ROUTES))

        val aligned = NetworkConfigValidation.validate(
            NetworkConfig(
                dhcp = true,
                routes = listOf("10.0.0.0/24"),
                advanced = AdvancedNetworkConfig(dhcpCidr = "10.127.127.0/24")
            )
        )
        assertNull(aligned.error(ConfigField.DHCP_CIDR))
        assertNull(aligned.error(ConfigField.ROUTES))
    }

    @Test fun hiddenDependentFieldsDoNotBlockSaving() {
        val result = NetworkConfigValidation.validate(
            NetworkConfig(
                disableIpv6 = true,
                multiThread = false,
                advanced = AdvancedNetworkConfig(
                    multiThreadCount = "not-a-number",
                    ipv6 = "also-not-an-ipv6",
                    enableVpnPortal = false,
                    vpnPortalClients = listOf(VpnPortalClient(name = "", virtualIp = "bad"))
                )
            )
        )
        assertTrue(result.isValid)
    }

    @Test fun disabledSecureModeReportsTheSwitchInsteadOfHiddenKeyFields() {
        val result = NetworkConfigValidation.validate(
            NetworkConfig(
                peerUrls = listOf("tcp://example.com:11010"),
                advanced = AdvancedNetworkConfig(
                    secureMode = false,
                    peerKeys = mapOf("tcp://example.com:11010" to "not-a-key")
                )
            )
        )
        assertNotNull(result.error(ConfigField.SECURE_KEYS))
        assertNull(result.error(ConfigField.PEER_KEYS))
    }

    @Test fun enabledSecureModeValidatesVisiblePeerKeys() {
        val key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val result = NetworkConfigValidation.validate(
            NetworkConfig(
                peerUrls = listOf("tcp://example.com:11010"),
                advanced = AdvancedNetworkConfig(
                    secureMode = true,
                    privateKey = key,
                    peerKeys = mapOf("tcp://example.com:11010" to "not-a-key")
                )
            )
        )
        assertNotNull(result.error(ConfigField.PEER_KEYS))
        assertNull(result.error(ConfigField.SECURE_KEYS))
    }
}
