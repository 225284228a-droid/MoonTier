package cn.moonflow.easytier

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

internal fun parseNodes(info: JSONObject): List<NodeInfo> {
    val nodes = LinkedHashMap<String, NodeInfo>()
    val local = info.objectField("my_node_info", "myNodeInfo")
    if (local != null) {
        nodes["local"] = NodeInfo(
            hostname = local.optString("hostname").ifBlank { "本机" },
            ip = ipv4Cidr(local.objectField("virtual_ipv4", "virtualIpv4")).substringBefore('/'),
            role = "本机",
            latencyMs = 0,
            local = true
        )
    }

    val peers = HashMap<String, JSONObject>()
    info.optJSONArray("peers")?.forEachObject { peer ->
        val id = peer.valueString("peer_id", "peerId")
        if (id.isNotBlank()) peers[id] = peer
    }

    val pairs = info.arrayField("peer_route_pairs", "peerRoutePairs")
    var routeIndex = 0
    fun addRoute(route: JSONObject, peer: JSONObject?) {
        val id = route.valueString("peer_id", "peerId")
        nodes[if (id.isNotBlank()) "peer:$id" else "route:${routeIndex++}"] = routeNode(route, peer)
    }
    if (pairs != null && pairs.length() > 0) {
        pairs.forEachObject { pair ->
            val route = pair.optJSONObject("route") ?: return@forEachObject
            val peer = pair.optJSONObject("peer")
                ?: peers[route.valueString("peer_id", "peerId")]
            addRoute(route, peer)
        }
    } else {
        info.optJSONArray("routes")?.forEachObject { route ->
            addRoute(route, peers[route.valueString("peer_id", "peerId")])
        }
    }
    return nodes.values.toList()
}

private fun routeNode(route: JSONObject, peer: JSONObject?): NodeInfo {
    val cost = route.optInt("cost", 0)
    val connection = when {
        cost > 1 -> "中继($cost)"
        cost == 1 -> "直连"
        else -> "未知"
    }
    val isServer = route.objectField("feature_flag", "featureFlag")
        ?.let { it.optBoolean("is_public_server", it.optBoolean("isPublicServer")) } == true
    val role = if (isServer) "服务器 · $connection" else connection
    return NodeInfo(
        hostname = route.optString("hostname").ifBlank { if (isServer) "公共服务器" else connection },
        ip = ipv4Cidr(route.objectField("ipv4_addr", "ipv4Addr")).substringBefore('/'),
        role = role,
        latencyMs = peerLatencyMs(peer),
        local = false
    )
}

private fun peerLatencyMs(peer: JSONObject?): Int {
    if (peer == null) return -1
    val defaultValue = peer.opt("default_conn_id").takeUnless { it == null || it == JSONObject.NULL }
        ?: peer.opt("defaultConnId")
    val defaultId = when (defaultValue) {
        is String -> defaultValue
        is JSONObject -> defaultValue.uuidString()
        else -> ""
    }
    var minimum: BigDecimal? = null
    var preferred: BigDecimal? = null
    peer.optJSONArray("conns")?.forEachObject { conn ->
        if (conn.optBoolean("is_closed", conn.optBoolean("isClosed"))) return@forEachObject
        val value = conn.optJSONObject("stats")?.opt("latency_us")
            ?: conn.optJSONObject("stats")?.opt("latencyUs")
        val latency = when (value) {
            is Number, is String -> value.toString().toBigDecimalOrNull()
            else -> null
        }?.takeIf { it > BigDecimal.ZERO } ?: return@forEachObject
        if (minimum == null || latency < minimum) minimum = latency
        if (defaultId.isNotBlank() && conn.valueString("conn_id", "connId") == defaultId) {
            preferred = latency
        }
    }
    val micros = preferred ?: minimum ?: return -1
    val millis = micros.divide(BigDecimal(1000), 0, RoundingMode.CEILING)
    return millis.min(BigDecimal(Int.MAX_VALUE)).toInt()
}

private fun JSONObject.uuidString(): String {
    val parts = (1..4).map { optLong("part$it", 0L) }
    if (parts.all { it == 0L }) return ""
    val hex = parts.joinToString("") { (it and 0xffffffffL).toString(16).padStart(8, '0') }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
        "${hex.substring(16, 20)}-${hex.substring(20)}"
}

private fun JSONObject.objectField(primary: String, fallback: String): JSONObject? =
    optJSONObject(primary) ?: optJSONObject(fallback)

private fun JSONObject.arrayField(primary: String, fallback: String): JSONArray? =
    optJSONArray(primary) ?: optJSONArray(fallback)

private fun JSONObject.valueString(primary: String, fallback: String): String {
    val value = opt(primary).takeUnless { it == null || it == JSONObject.NULL } ?: opt(fallback)
    return when (value) {
        is String, is Number -> value.toString()
        else -> ""
    }
}

private inline fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
    for (index in 0 until length()) optJSONObject(index)?.let(block)
}

private fun ipv4Cidr(obj: JSONObject?): String {
    if (obj == null) return ""
    val address = obj.optJSONObject("address")?.optLong("addr", 0L) ?: 0L
    if (address == 0L) return ""
    val prefix = obj.optInt("network_length", obj.optInt("networkLength", 24)).coerceIn(1, 32)
    return "${Ipv4.intToAddress(address)}/$prefix"
}
