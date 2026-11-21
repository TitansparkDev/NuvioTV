package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.core.streams.PlaybackAvailability
import com.nuvio.tv.core.streams.PrefetchTarget
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.NextToWatch
import com.nuvio.tv.domain.model.ScraperInfo
import com.nuvio.tv.domain.model.Video
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetailsPrefetchTargetTest {
    private val episodes = (1..3).map { Video("ep$it", "Episode $it", null, null, season = 1, episode = it, overview = null) }
    private val meta = mockk<Meta> {
        every { id } returns "show"
        every { apiType } returns "series"
        every { type } returns ContentType.SERIES
        every { videos } returns episodes
        every { behaviorHints } returns null
    }
    private val available = PlaybackAvailability(scrapers = listOf(
        ScraperInfo("s", "Scraper", "", "1", "", listOf("series", "movie"), true, true, null, emptyList(), "r", null)
    ), isLoaded = true)

    @Test fun `movie target uses movie id`() {
        every { meta.type } returns ContentType.MOVIE
        every { meta.apiType } returns "movie"
        every { meta.videos } returns emptyList()
        assertEquals(PrefetchTarget(7, "movie", "show", "show", kind = PrefetchTarget.Kind.MOVIE_DETAILS),
            target(meta = meta))
    }

    @Test fun `resume and next up target their episode`() {
        assertEquals("ep2", target(next = NextToWatch(null, true, "ep2", 1, 2, "Resume"))?.videoId)
        assertEquals("ep3", target(next = NextToWatch(null, false, "ep3", 1, 3, "Next"))?.videoId)
    }

    @Test fun `pre picked shuffle episode is used and rolling again changes target`() {
        val first = target(next = NextToWatch(null, false, "ep1", 1, 1, "Shuffle"))
        val again = target(next = NextToWatch(null, false, "ep3", 1, 3, "Shuffle"))
        assertEquals("ep1", first?.videoId)
        assertEquals("ep3", again?.videoId)
        assertEquals(PrefetchTarget.Kind.EPISODE_DETAILS, again?.kind)
    }

    @Test fun `unavailable target and disabled toggle return null`() {
        assertNull(detailsPrefetchTarget(7, meta, null, episodes,
            PlayerSettings(), PlaybackAvailability(isLoaded = true)))
        assertNull(target(settings = PlayerSettings(prefetchEpisodeOnDetails = false)))
    }

    private fun target(
        meta: Meta = this.meta,
        next: NextToWatch? = null,
        settings: PlayerSettings = PlayerSettings(),
    ) = detailsPrefetchTarget(7, meta, next, episodes, settings, available)
}
