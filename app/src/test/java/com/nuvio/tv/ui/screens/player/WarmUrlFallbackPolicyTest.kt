package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class WarmUrlFallbackPolicyTest {
    @Test fun `eligible status errors fall back only before first frame`() {
        for (status in listOf(401, 403, 404, 410)) {
            assertEquals(true, WarmUrlFallbackPolicy.shouldFallBack(status, false, true, false, false))
            assertEquals(false, WarmUrlFallbackPolicy.shouldFallBack(status, false, true, true, false))
            assertEquals(false, WarmUrlFallbackPolicy.shouldFallBack(status, false, false, false, false))
            assertEquals(false, WarmUrlFallbackPolicy.shouldFallBack(status, false, true, false, true))
        }
    }

    @Test fun `timeout falls back only before first frame`() {
        assertEquals(true, WarmUrlFallbackPolicy.shouldFallBack(null, true, true, false, false))
        assertEquals(false, WarmUrlFallbackPolicy.shouldFallBack(null, true, true, true, false))
        assertEquals(false, WarmUrlFallbackPolicy.shouldFallBack(null, true, false, false, false))
        assertEquals(false, WarmUrlFallbackPolicy.shouldFallBack(null, true, true, false, true))
    }
}
