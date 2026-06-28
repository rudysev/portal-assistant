package com.portal.assistant.gemini

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure JVM tests for the HTTP-status → verdict mapping (the I/O in [GeminiKeyCheck.validate] is not tested). */
class GeminiKeyCheckTest {

    @Test fun success2xxIsValid() {
        assertEquals(GeminiKeyCheck.Result.Valid, GeminiKeyCheck.classify(200))
        assertEquals(GeminiKeyCheck.Result.Valid, GeminiKeyCheck.classify(204))
    }

    @Test fun authAndBadRequestAreInvalid() {
        assertEquals(GeminiKeyCheck.Result.Invalid, GeminiKeyCheck.classify(400))
        assertEquals(GeminiKeyCheck.Result.Invalid, GeminiKeyCheck.classify(401))
        assertEquals(GeminiKeyCheck.Result.Invalid, GeminiKeyCheck.classify(403))
    }

    @Test fun serverAndUnexpectedAreNetworkError() {
        // A flaky server (5xx) or anything unexpected must NOT discard a possibly-good key.
        assertEquals(GeminiKeyCheck.Result.NetworkError, GeminiKeyCheck.classify(429))
        assertEquals(GeminiKeyCheck.Result.NetworkError, GeminiKeyCheck.classify(500))
        assertEquals(GeminiKeyCheck.Result.NetworkError, GeminiKeyCheck.classify(503))
    }

    @Test fun blankKeyIsInvalidWithoutNetwork() {
        assertEquals(GeminiKeyCheck.Result.Invalid, GeminiKeyCheck.validate("   "))
    }
}
