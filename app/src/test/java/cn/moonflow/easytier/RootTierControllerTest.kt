package cn.moonflow.easytier

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RootTierControllerTest {
    @Test
    fun nullAndMissingErrorsAreEmptyButFallbackIsRetained() {
        assertEquals("", JSONObject("""{"error_msg":null}""").optStringCompat("error_msg", "errorMsg"))
        assertEquals("", JSONObject().optStringCompat("error_msg", "errorMsg"))
        assertEquals("real error", JSONObject("""{"error_msg":"real error"}""").optStringCompat("error_msg", "errorMsg"))
        assertEquals(
            "actual failure",
            JSONObject("""{"error_msg":null,"errorMsg":"actual failure"}""")
                .optStringCompat("error_msg", "errorMsg")
        )
    }

    @Test
    fun literalNullStringIsNotDiscarded() {
        assertEquals(
            "null",
            JSONObject("""{"error_msg":"null","errorMsg":"other"}""")
                .optStringCompat("error_msg", "errorMsg")
        )
    }

    @Test fun routesAllNetworksAndKeepsExitReachabilityWithinItsOwner() {
        fun info(ip: String, peer: String? = null): JSONObject {
            val result = JSONObject().put("my_node_info", JSONObject().put("virtual_ipv4", JSONObject()
                .put("address", JSONObject().put("addr", Ipv4.parseAddress(ip)))
                .put("network_length", 24)))
            if (peer != null) result.put("routes", org.json.JSONArray().put(JSONObject().put("peer_id", 1)
                .put("ipv4_addr", JSONObject().put("address", JSONObject().put("addr", Ipv4.parseAddress(peer))))))
            return result
        }
        val a = NetworkConfig(id = "a", exitNodes = listOf("10.2.0.2"))
        val b = NetworkConfig(id = "b")
        val proxy = NetworkConfig(id = "proxy", noTun = true)
        val infos = JSONObject().put("a", info("10.1.0.5")).put("b", info("10.2.0.5", "10.2.0.2"))
            .put("proxy", info("10.3.0.5"))
        val first = rootRoutingPlan(setOf("a", "b", "proxy"), infos, listOf(a, b, proxy), true)
        assertEquals(listOf("10.1.0.0/24", "10.2.0.0/24"), first.destinations)
        assertEquals("", first.exitCidr)
        val ownExit = b.copy(exitNodes = listOf("10.2.0.2"))
        assertEquals("10.2.0.0/24", rootRoutingPlan(setOf("a", "b"), infos, listOf(a, ownExit), true).exitCidr)
        assertEquals("", rootRoutingPlan(setOf("a", "b"), infos, listOf(a, ownExit), false).exitCidr)
        assertEquals(listOf("10.2.0.0/24"), rootRoutingPlan(setOf("b"), infos, listOf(b), false).destinations)
        assertEquals(emptyList<String>(), rootRoutingPlan(emptySet(), infos, listOf(a, b), true).destinations)
    }
}
