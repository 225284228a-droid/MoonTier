package cn.moonflow.easytier

internal data class ConfigServerLogStatus(
    val connected: Boolean = false,
    val error: String = ""
)

private const val MAX_ERROR_CHARS = 200

// The standalone core only exposes config-server connectivity through logs.
// Entries are ordered oldest-first; the newest marker wins. Both the current
// ("connected to config server") and the pre-2.7 ("Successfully connected")
// wordings are recognized so downloaded stable/nightly cores behave the same.
private val CONNECTED_LOG = Regex(
    "connected to config server|successfully connected",
    RegexOption.IGNORE_CASE
)
private val FAILED_LOG = Regex(
    "failed to connect (?:to )?(?:the )?(?:config[- ]server|server)" +
        "|failed to reconnect secure" +
        "|config[- ]server secure handshake failed" +
        "|noise handshake failed" +
        "|secure[- ]mode enabled but" +
        "|secure mode requires (?:web|config[- ]server)" +
        "|config[- ]server heartbeat (?:failed|timeout)",
    RegexOption.IGNORE_CASE
)

internal fun configServerLogStatus(lines: List<String>): ConfigServerLogStatus {
    var connected = false
    var error = ""
    for (line in lines) {
        when {
            CONNECTED_LOG.containsMatchIn(line) -> {
                connected = true
                error = ""
            }
            FAILED_LOG.containsMatchIn(line) -> {
                connected = false
                error = line.take(MAX_ERROR_CHARS)
            }
        }
    }
    return ConfigServerLogStatus(connected, error)
}
