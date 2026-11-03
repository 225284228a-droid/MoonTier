package cn.moonflow.easytier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLogLevelTest {
    private val statusTarget = "easytier_core::management::full::web_client=info"

    @Test fun shuttingLogsOffStillEmitsTheConnectionStatusTarget() {
        val filter = CoreLogLevel.rustFilter(CoreLogLevel.OFF)
        assertTrue(filter.startsWith("off,"))
        assertTrue(filter.contains(statusTarget))
        // The console filter must admit info; RUST_LOG narrows it to the target.
        assertEquals("info", CoreLogLevel.rustLevel(CoreLogLevel.OFF))
    }

    @Test fun normalLogsKeepWarningsAndAddTheStatusTarget() {
        val filter = CoreLogLevel.rustFilter(CoreLogLevel.NORMAL)
        assertTrue(filter.contains("easytier=warn"))
        assertTrue(filter.contains(statusTarget))
        assertEquals("warn", CoreLogLevel.rustLevel(CoreLogLevel.NORMAL))
    }

    @Test fun debugLogsAlreadyIncludeTheStatusTarget() {
        val filter = CoreLogLevel.rustFilter(CoreLogLevel.DEBUG)
        assertTrue(filter.contains("easytier=debug"))
        assertFalse(filter.startsWith("off,"))
        assertEquals("debug", CoreLogLevel.rustLevel(CoreLogLevel.DEBUG))
    }
}
