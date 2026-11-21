package com.nuvio.tv.ui.screens.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.SocketTimeoutException
import io.mockk.every
import io.mockk.mockk

class WarmUrlFallbackPolicyTest {
    @Test fun `eligible status errors fall back only before first frame`() {
        for (status in listOf(401, 403, 404, 410)) {
            val cause = mockk<HttpDataSource.InvalidResponseCodeException>()
            every { cause.responseCode } returns status
            val error = PlaybackException("http", cause, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
            assertEquals(true, WarmUrlFallbackPolicy.shouldFallBack(error, true, false, false))
            assertEquals(false, WarmUrlFallbackPolicy.shouldFallBack(error, true, true, false))
            assertEquals(false, WarmUrlFallbackPolicy.shouldFallBack(error, false, false, false))
            assertEquals(false, WarmUrlFallbackPolicy.shouldFallBack(error, true, false, true))
        }
    }

    @Test fun `timeout falls back only before first frame`() {
        val error = PlaybackException("timeout", SocketTimeoutException(), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        assertEquals(true, WarmUrlFallbackPolicy.shouldFallBack(error, true, false, false))
        assertEquals(false, WarmUrlFallbackPolicy.shouldFallBack(error, true, true, false))
        assertEquals(false, WarmUrlFallbackPolicy.shouldFallBack(error, false, false, false))
        assertEquals(false, WarmUrlFallbackPolicy.shouldFallBack(error, true, false, true))
    }
}
