package cn.moonflow.easytier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkInputTest {
    @Test fun appendsDefaultIpv4Prefix() {
        assertEquals("10.0.0.0/24", NetworkInput.normalizeCidr("10.0.0.0", 24, networkAddress = true))
    }

    @Test fun normalizesHostBitsForNetworkCidr() {
        assertEquals("10.127.127.0/24", NetworkInput.normalizeCidr("10.127.127.8/24", 24, networkAddress = true))
    }

    @Test fun keepsHostBitsForInterfaceAddress() {
        assertEquals("10.127.127.8/24", NetworkInput.normalizeCidr("10.127.127.8", 24))
    }

    @Test fun appendsIpv6PrefixAndNormalizesNetworkAddress() {
        assertEquals("fd12:3456::/64", NetworkInput.normalizeCidr("fd12:3456::", 64, networkAddress = true))
        assertEquals("fd12:3456::/64", NetworkInput.normalizeCidr("fd12:3456::8/64", 64, networkAddress = true))
    }

    @Test fun networkAlignmentChecks() {
        assertTrue(NetworkInput.isIpv4NetworkCidr("10.126.126.0/24"))
        assertFalse(NetworkInput.isIpv4NetworkCidr("10.126.126.8/24"))
        assertTrue(NetworkInput.isIpv6NetworkCidr("fd12:3456::/64"))
        assertFalse(NetworkInput.isIpv6NetworkCidr("fd12:3456::8/64"))
        assertFalse(NetworkInput.isIpv6NetworkCidr("fd12:3456::/129"))
    }

    @Test fun malformedValuesAreLeftUntouched() {
        assertEquals("not-a-cidr", NetworkInput.normalizeCidr("not-a-cidr", 24, networkAddress = true))
        assertEquals("10.0.0.0/99", NetworkInput.normalizeCidr("10.0.0.0/99", 24, networkAddress = true))
    }

    @Test fun savingNormalizesAllNetworkFields() {
        val config = NetworkInput.normalizeConfig(
            NetworkConfig(
                ipv4 = "10.0.0.2",
                routes = listOf("10.1.0.0", "10.2.0.9/16"),
                proxyCidrs = listOf("192.168.1.0"),
                advanced = AdvancedNetworkConfig(
                    dhcpCidr = "10.127.127.8/24",
                    dhcpv6Cidr = "fd12:3456::8/64",
                    ipv6 = "fd12:3456::2",
                    ipv6PublicAddrPrefix = "2001:db8:1234::8/64",
                    proxyMappings = listOf(ProxyMapping("192.168.2.0", "10.168.2.9/24")),
                    vpnPortalClients = listOf(VpnPortalClient("phone", "10.30.0.10")),
                    managedCredentials = listOf(
                        ManagedCredential(id = "guest", secret = "key", allowedProxyCidrs = listOf("192.168.3.0"))
                    )
                )
            )
        )
        assertEquals("10.0.0.2/24", config.ipv4)
        assertEquals(listOf("10.1.0.0/24", "10.2.0.0/16"), config.routes)
        assertEquals(listOf("192.168.1.0/24"), config.proxyCidrs)
        assertEquals("10.127.127.0/24", config.advanced.dhcpCidr)
        assertEquals("fd12:3456::/64", config.advanced.dhcpv6Cidr)
        assertEquals("fd12:3456::2/64", config.advanced.ipv6)
        assertEquals("2001:db8:1234::/64", config.advanced.ipv6PublicAddrPrefix)
        assertEquals("10.168.2.0/24", config.advanced.proxyMappings.single().mappedCidr)
        assertEquals("10.30.0.10/24", config.advanced.vpnPortalClients.single().virtualIp)
        assertEquals(listOf("192.168.3.0/24"), config.advanced.managedCredentials.single().allowedProxyCidrs)
    }
}
