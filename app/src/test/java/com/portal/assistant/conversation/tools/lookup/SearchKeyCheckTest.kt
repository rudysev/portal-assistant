package com.portal.assistant.conversation.tools.lookup

import com.portal.assistant.util.KeyVerdict
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure JVM tests for the Search key check's HTTP-status → verdict mapping (the I/O in [SearchKeyCheck.validate]
 *  is not tested). */
class SearchKeyCheckTest {

    @Test fun success2xxIsValid() {
        assertEquals(KeyVerdict.Valid, SearchKeyCheck.classify(200))
        assertEquals(KeyVerdict.Valid, SearchKeyCheck.classify(204))
    }

    @Test fun unauthorizedAndForbiddenAreInvalid() {
        assertEquals(KeyVerdict.Invalid, SearchKeyCheck.classify(401))
        assertEquals(KeyVerdict.Invalid, SearchKeyCheck.classify(403))
    }

    @Test fun rateAndPlanLimitsAreNotABadKey() {
        // 429 (rate limit) and 432/433 (plan / pay-as-you-go limit) mean the key is real but spent or throttled:
        // never tell the user their key is wrong.
        assertEquals(KeyVerdict.NetworkError, SearchKeyCheck.classify(429))
        assertEquals(KeyVerdict.NetworkError, SearchKeyCheck.classify(432))
        assertEquals(KeyVerdict.NetworkError, SearchKeyCheck.classify(433))
    }

    @Test fun serverAndUnexpectedAreNetworkError() {
        assertEquals(KeyVerdict.NetworkError, SearchKeyCheck.classify(400))
        assertEquals(KeyVerdict.NetworkError, SearchKeyCheck.classify(500))
        assertEquals(KeyVerdict.NetworkError, SearchKeyCheck.classify(503))
    }

    @Test fun blankKeyIsInvalidWithoutNetwork() {
        assertEquals(KeyVerdict.Invalid, SearchKeyCheck.validate("   "))
    }
}
