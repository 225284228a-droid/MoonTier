package cn.moonflow.easytier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigServerLogTest {
    @Test fun currentCoreConnectionQuoteMarksConnected() {
        val status = configServerLogStatus(listOf(
            "2026-11-04T00:00:00Z  INFO easytier_core::management::full::web_client: connected to config server connection=Udp(\"1.2.3.4:22020\")"
        ))
        assertTrue(status.connected)
        assertEquals("", status.error)
    }

    @Test fun legacyCoreConnectionWordingIsAccepted() {
        val status = configServerLogStatus(listOf(
            "INFO easytier::web_client: Successfully connected to Udp(\"1.2.3.4:22020\")"
        ))
        assertTrue(status.connected)
    }

    @Test fun retryFailureAfterConnectDisconnects() {
        val status = configServerLogStatus(listOf(
            "INFO connected to config server",
            "WARN failed to connect to config server; retrying"
        ))
        assertFalse(status.connected)
        assertTrue(status.error.contains("failed to connect"))
    }

    @Test fun reconnectClearsThePreviousError() {
        val status = configServerLogStatus(listOf(
            "WARN failed to connect to the server, retrying in 5 seconds...",
            "INFO connected to config server"
        ))
        assertTrue(status.connected)
        assertEquals("", status.error)
    }

    @Test fun secureModeFailuresDisconnect() {
        for (line in listOf(
            "WARN config-server secure handshake failed",
            "WARN failed to reconnect secure config-server tunnel",
            "WARN secure mode requires config-server encryption support",
            "ERROR config-server heartbeat failed"
        )) {
            assertFalse(configServerLogStatus(listOf(line)).connected)
        }
    }

    @Test fun peerFailuresDoNotAffectConfigServerState() {
        val status = configServerLogStatus(listOf(
            "INFO connected to config server",
            "WARN failed to connect to peer 10.0.0.2",
            "WARN peer connection closed"
        ))
        assertTrue(status.connected)
        assertEquals("", status.error)
    }

    @Test fun emptyLogIsDisconnected() {
        val status = configServerLogStatus(emptyList())
        assertFalse(status.connected)
        assertEquals("", status.error)
    }
}
