package cn.moonflow.easytier

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class FfiRuntimeSnapshotTest {
    @Test fun ffiAddressDoesNotChangeRootSettings() {
        val original = AppSettings(configServerUrl = "udp://root.example:22020/root-user",
            configServerHostname = "root-device", configServerSecureMode = true, configServerAutoConnect = true)
        val restored = AppSettings.fromJson(original.copy(ffiConfigServerUrl = "vpn-user").toJson())
        assertEquals(original.configServerUrl, restored.configServerUrl)
        assertEquals(original.configServerHostname, restored.configServerHostname)
        assertTrue(restored.configServerSecureMode)
        assertTrue(restored.configServerAutoConnect)
        assertEquals("vpn-user", restored.ffiConfigServerUrl)
        assertEquals("", AppSettings.fromJson(JSONObject("""{"ffi_config_server_url":null}""")).ffiConfigServerUrl)
    }

    @Test fun disabledRemoteConfigRetainsIdentityAndSource() {
        val records = FfiRuntimeConfig.parseSnapshot("""[
            {"instance_id":"remote-id","instance_name":"office","source":"web","toml_config":"[flags]\nno_tun = false","enabled":false,"no_tun":false},
            {"instance_id":"local-id","instance_name":"proxy","source":"user","toml_config":"raw","enabled":true,"no_tun":true}
        ]""")
        assertEquals(2, records.size)
        assertTrue(records[0].remote)
        assertFalse(records[0].enabled)
        assertEquals("remote-id", records[0].instanceId)
        assertEquals("[flags]\nno_tun = false", records[0].toml)
        assertFalse(records[1].remote)
        assertTrue(records[1].noTun)
    }

    @Test fun staleTunRequestCannotAttachAfterNetworkChange() {
        val gate = VpnSessionGate()
        val first = gate.select("local", "first routes")
        val next = gate.select("remote", "new routes")
        assertFalse(gate.accepts("local", first))
        assertFalse(gate.accepts("remote", first))
        assertTrue(gate.accepts("remote", next))
    }

    @Test fun routeChangeInvalidatesOldRequestButDuplicateRefreshDoesNot() {
        val gate = VpnSessionGate()
        val first = gate.select("one", "routes-a")
        assertEquals(first, gate.select("one", "routes-a"))
        val changed = gate.select("one", "routes-b")
        assertFalse(gate.accepts("one", first))
        assertTrue(gate.accepts("one", changed))
        gate.clear()
        assertFalse(gate.accepts("one", changed))
    }

    @Test fun sharedVpnProjectsMultipleNetworksAndStableMembership() {
        val config = JSONObject("""{"instance_ids":["b","a"],"addresses":["10.2.0.1/24","10.1.0.1/24","2001:db8::1/64"],
            "routes":["10.2.0.0/24","10.1.0.0/24","2001:db8::/64"],"dns":"100.100.100.101"}""")
        val request = sharedVpnRequest(config, setOf("1.2.3.4"), 1300, true)
        val json = JSONObject(request)
        assertEquals(3, json.getJSONArray("addresses").length())
        assertTrue(json.getJSONArray("routes").toStringList().contains("0.0.0.0/0"))
        assertEquals("100.100.100.101", json.getString("dns"))
        assertEquals(1300, json.getInt("mtu"))
        config.put("instance_ids", org.json.JSONArray("[\"a\",\"b\"]"))
        assertEquals(request, sharedVpnRequest(config, setOf("1.2.3.4"), 1300, true))
        val gate = VpnSessionGate()
        val token = gate.select("shared-vpn", request)
        config.put("instance_ids", org.json.JSONArray("[\"a\"]"))
        gate.select("shared-vpn", sharedVpnRequest(config, emptySet(), 1300, true))
        assertFalse(gate.accepts("shared-vpn", token))
    }

    @Test fun replacingInstanceInvalidatesEvenIdenticalRoutesAndMembership() {
        val gate = VpnSessionGate()
        val first = gate.select("shared-vpn", "same projection")
        gate.invalidate()
        val replacement = gate.select("shared-vpn", "same projection")
        assertFalse(gate.accepts("shared-vpn", first))
        assertTrue(gate.accepts("shared-vpn", replacement))
    }
}
