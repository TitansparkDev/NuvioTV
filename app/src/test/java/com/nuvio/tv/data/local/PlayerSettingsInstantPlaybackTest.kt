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
        assertEquals(true, settings.parallelNetworkEnabled)
        assertEquals(4, settings.parallelConnectionCount)
        assertEquals(4096, settings.parallelChunkSizeKb)
    }

    @Test fun `migration defaults are returned only before migration is marked done`() {
        assertEquals(PlayerSettings.fastStreamingDefaults(), PlayerSettings.shouldApplyFastStreamingDefaults(false))
        assertNull(PlayerSettings.shouldApplyFastStreamingDefaults(true))
    }
}
