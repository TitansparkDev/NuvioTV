package com.nuvio.tv.ui.screens.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.DataSpec
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test

class ReusedLinkFallbackPolicyTest {
    @Test fun searchesForExpiredOrUnavailableReusedLinksBeforeFirstFrame() {
        listOf(401, 403, 404, 410).forEach { status ->
            val cause = HttpDataSource.InvalidResponseCodeException(status, "", null, emptyMap(), DataSpec(Uri.parse("https://example.invalid/video")), ByteArray(0))
            val error = PlaybackException("http", cause, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
            assertEquals(true, ReusedLinkFallbackPolicy.shouldSearchAgain(error, true, false, false, false))
        }
        val timeout = PlaybackException("timeout", java.net.SocketTimeoutException(), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        assertEquals(true, ReusedLinkFallbackPolicy.shouldSearchAgain(timeout, true, false, false, false))
    }

    @Test fun refusesIneligibleOrAlreadyHandledFailures() {
        val error = PlaybackException("http", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        assertEquals(false, ReusedLinkFallbackPolicy.shouldSearchAgain(error, false, false, false, false))
        assertEquals(false, ReusedLinkFallbackPolicy.shouldSearchAgain(error, true, true, false, false))
        assertEquals(false, ReusedLinkFallbackPolicy.shouldSearchAgain(error, true, false, true, false))
        assertEquals(false, ReusedLinkFallbackPolicy.shouldSearchAgain(error, true, false, false, true))
    }
}
