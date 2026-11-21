package com.nuvio.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerSettingsInstantPlaybackTest {
    @Test fun `fast streaming defaults enable instant playback and parallel downloads`() {
        assertEquals(FastStreamingDefaults(), PlayerSettings.fastStreamingDefaults())
        val settings = PlayerSettings()
        assertEquals(true, settings.prefetchMovieOnDetails)
        assertEquals(true, settings.prefetchEpisodeOnDetails)
        assertEquals(true, settings.prefetchPrepareTopSource)
        assertEquals(true, settings.autoPlayFastStart)
        assertEquals(true, settings.prefetchContinueWatching)
        assertEquals(true, settings.streamReuseLastLinkEnabled)
        assertEquals(24, settings.streamReuseLastLinkCacheHours)
        assertEquals(true, settings.parallelNetworkEnabled)
        assertEquals(4, settings.parallelConnectionCount)
        assertEquals(4096, settings.parallelChunkSizeKb)
    }

    @Test fun `migration defaults are returned only before migration is marked done`() {
        assertEquals(PlayerSettings.fastStreamingDefaults(), PlayerSettings.shouldApplyFastStreamingDefaults(false))
        assertNull(PlayerSettings.shouldApplyFastStreamingDefaults(true))
    }

    @Test fun `reuse last link migration runs once`() {
        assertEquals(true, PlayerSettings.shouldEnableReuseLastLink(false))
        assertEquals(false, PlayerSettings.shouldEnableReuseLastLink(true))
    }
}
