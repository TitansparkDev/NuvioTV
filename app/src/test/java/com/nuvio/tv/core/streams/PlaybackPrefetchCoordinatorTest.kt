package com.nuvio.tv.core.streams

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPrefetchCoordinatorTest {
    @Test fun `continue watching has a dedicated prefetch kind`() {
        assertEquals(PrefetchTarget.Kind.CONTINUE_WATCHING, PrefetchTarget.Kind.valueOf("CONTINUE_WATCHING"))
    }
}
