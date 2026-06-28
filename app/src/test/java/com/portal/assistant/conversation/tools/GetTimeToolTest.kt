package com.portal.assistant.conversation.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM — no Android, no I/O. Verifies response shape; exact values are inherently runtime. */
class GetTimeToolTest {
    @Test fun returnsDatetime() {
        val result = GetTimeTool.invoke(org.json.JSONObject())
        assertTrue(result.has("datetime"))
        assertFalse(result.has("error"))
    }

    @Test fun datetimeIsNonEmpty() {
        val result = GetTimeTool.invoke(org.json.JSONObject())
        assertTrue(result.getString("datetime").isNotEmpty())
    }

    // The raw IANA zone id must NOT be returned — the model would read it aloud verbatim
    // ("America/Los_Angeles") instead of using the human location from its static context.
    @Test fun omitsRawZoneId() {
        val result = GetTimeTool.invoke(org.json.JSONObject())
        assertFalse(result.has("zone"))
    }
}
