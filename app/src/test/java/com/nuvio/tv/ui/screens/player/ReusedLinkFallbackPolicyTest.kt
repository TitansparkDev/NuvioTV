package com.nuvio.tv.ui.screens.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Test

class ReusedLinkFallbackPolicyTest {
    @Test fun searchesForExpiredOrUnavailableReusedLinksBeforeFirstFrame() {
        listOf(401, 403, 404, 410).forEach { status ->
            assertEquals(true, ReusedLinkFallbackPolicy.shouldSearchAgain(status, false, true, false, false, false))
        }
        assertEquals(true, ReusedLinkFallbackPolicy.shouldSearchAgain(null, true, true, false, false, false))
    }

    @Test fun refusesIneligibleOrAlreadyHandledFailures() {
        assertEquals(false, ReusedLinkFallbackPolicy.shouldSearchAgain(null, true, false, false, false, false))
        assertEquals(false, ReusedLinkFallbackPolicy.shouldSearchAgain(null, true, true, true, false, false))
        assertEquals(false, ReusedLinkFallbackPolicy.shouldSearchAgain(null, true, true, false, true, false))
        assertEquals(false, ReusedLinkFallbackPolicy.shouldSearchAgain(null, true, true, false, false, true))
    }
}
