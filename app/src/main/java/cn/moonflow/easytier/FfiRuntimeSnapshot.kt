package cn.moonflow.easytier

import org.json.JSONArray
import org.json.JSONObject

data class FfiRuntimeConfig(
    val instanceId: String,
    val instanceName: String,
    val source: String,
    val toml: String,
    val enabled: Boolean,
    val noTun: Boolean
) {
    val remote: Boolean get() = source == "web"

    companion object {
        fun parseSnapshot(json: String): List<FfiRuntimeConfig> {
            val array = JSONArray(json)
            return buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.stringValue("instance_id")
                    val name = item.stringValue("instance_name")
                    if (id.isBlank() || name.isBlank()) continue
                    add(FfiRuntimeConfig(id, name, item.stringValue("source"),
                        item.stringValue("toml_config"), item.optBoolean("enabled"), item.optBoolean("no_tun")))
                }
            }
        }
    }
}

internal fun JSONObject.stringValue(key: String): String = (opt(key) as? String).orEmpty()

// An authorization result or service intent may arrive after the selected network changed.
internal class VpnSessionGate {
    var generation: Long = 0
        private set
    var instanceId: String = ""
        private set
    var signature: String = ""
        private set

    fun select(id: String, config: String): Long {
        if (id != instanceId || config != signature) {
            generation++
            instanceId = id
            signature = config
        }
        return generation
    }

    fun clear() {
        generation++
        instanceId = ""
        signature = ""
    }

    fun accepts(id: String, token: Long): Boolean =
        id.isNotEmpty() && instanceId == id && generation == token
}
