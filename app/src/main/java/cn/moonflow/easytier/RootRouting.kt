package cn.moonflow.easytier

import org.json.JSONObject

internal data class RootRoutingPlan(val destinations: List<String>, val exitCidr: String = "")

internal fun rootRoutingPlan(
    instanceIds: Set<String>, infos: JSONObject, configs: List<NetworkConfig>, exitAuto: Boolean
): RootRoutingPlan {
    val networks = instanceIds.sorted().mapNotNull { id ->
        val config = configs.firstOrNull { it.id == id }
        if (config?.noTun == true) return@mapNotNull null
        val info = infos.optJSONObject(id) ?: return@mapNotNull null
        val local = info.optJSONObject("my_node_info") ?: info.optJSONObject("myNodeInfo")
        val ipv4 = local?.optJSONObject("virtual_ipv4") ?: local?.optJSONObject("virtualIpv4") ?: return@mapNotNull null
        val address = ipv4.optJSONObject("address")?.optLong("addr", 0L) ?: 0L
        if (address == 0L) return@mapNotNull null
        val prefix = (if (ipv4.has("network_length")) ipv4.optInt("network_length") else ipv4.optInt("networkLength", 24)).coerceIn(1, 32)
        val subnet = Ipv4.subnetRoute("${Ipv4.intToAddress(address)}/$prefix") ?: return@mapNotNull null
        val peers = if (exitAuto && !config?.exitNodes.isNullOrEmpty()) parseNodes(info).filterNot { it.local } else emptyList()
        val reachable = config?.exitNodes.orEmpty().any { exit -> peers.any { it.ip == exit.trim().substringBefore('/') } }
        subnet to reachable
    }
    return RootRoutingPlan(networks.map { it.first }.distinct().sorted(), networks.firstOrNull { it.second }?.first.orEmpty())
}
