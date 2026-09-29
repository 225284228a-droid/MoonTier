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
}
