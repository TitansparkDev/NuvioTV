package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.streams.PrefetchTarget
import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWatchingPrefetchTargetTest {
    @Test fun `in progress target uses current episode identity`() {
        val progress = WatchProgress("tt1", "series", "Show", null, null, null, "tt1:1:2", 1, 2, null, 1, 10, 0)
        val target = continueWatchingPrefetchTarget(7, ContinueWatchingItem.InProgress(progress))
        assertEquals(PrefetchTarget(7, "series", "tt1:1:2", "tt1", 1, 2, PrefetchTarget.Kind.CONTINUE_WATCHING), target)
    }

    @Test fun `next up target uses next episode identity`() {
        val info = NextUpInfo("tt2", "series", "Show", null, null, null, "tt2:2:3", 2, 3,
            "Next", thumbnail = null, lastWatched = 0, sortTimestamp = 0)
        val target = continueWatchingPrefetchTarget(7, ContinueWatchingItem.NextUp(info))
        assertEquals(PrefetchTarget(7, "series", "tt2:2:3", "tt2", 2, 3, PrefetchTarget.Kind.CONTINUE_WATCHING), target)
    }
}
