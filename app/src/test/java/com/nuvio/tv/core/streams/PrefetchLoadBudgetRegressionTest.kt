package com.nuvio.tv.core.streams

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.domain.repository.StreamRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PrefetchLoadBudgetRegressionTest {
    @Test fun `disabled settings and quick exits do not search`() = runTest {
        val fixture = Fixture(this, PlayerSettings(prefetchMovieOnDetails = false), dwellMs = 1_000)
        repeat(10) { fixture.coordinator.request(fixture.target(it)); testScheduler.advanceTimeBy(900); testScheduler.runCurrent() }
        assertEquals(0, fixture.searches)
    }

    @Test fun `dwell search is reused by play and hourly requests stop at twenty`() = runTest {
        val fixture = Fixture(this)
        fixture.coordinator.request(fixture.target(0))
        testScheduler.advanceTimeBy(2_000); testScheduler.runCurrent()
        fixture.playSearch("movie", "video0")
        assertEquals(1, fixture.searches)

        repeat(24) { index ->
            fixture.coordinator.request(fixture.target(index + 1))
            testScheduler.advanceTimeBy(1_001); testScheduler.runCurrent()
        }
        assertEquals(20, fixture.searches)
    }

    @Test fun `rapid target rerolls search only the final episode`() = runTest {
        val fixture = Fixture(this)
        repeat(3) { index ->
            fixture.coordinator.request(fixture.target(index, "series", PrefetchTarget.Kind.EPISODE_DETAILS))
            testScheduler.advanceTimeBy(300); testScheduler.runCurrent()
        }
        testScheduler.advanceTimeBy(1_000); testScheduler.runCurrent()
        assertEquals(1, fixture.searches)
        verify(exactly = 0) { fixture.repository.getStreamsFromAllAddons("series", "video0", any(), any(), any(), any(), any(), any()) }
        verify(exactly = 1) { fixture.repository.getStreamsFromAllAddons("series", "video2", any(), any(), any(), any(), any(), any()) }
    }

    private class Fixture(
        scope: kotlinx.coroutines.CoroutineScope,
        settings: PlayerSettings = PlayerSettings(),
        dwellMs: Long = 1_000
    ) {
        val repository = mockk<StreamRepository>(relaxed = true)
        private val settingsStore = mockk<PlayerSettingsDataStore>()
        private val profile = mockk<ProfileManager>()
        private val activeId = MutableStateFlow(1)
        private val prefetchedKeys = mutableSetOf<Pair<String, String>>()
        var searches = 0
        init {
            every { settingsStore.playerSettings } returns MutableStateFlow(settings)
            every { profile.activeProfileId } returns activeId
            coEvery { repository.getCachedStreamsFromAllAddons(any(), any(), any(), any()) } returns null
            every { repository.getStreamsFromAllAddons(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                searches++
                prefetchedKeys += firstArg<String>() to secondArg<String>()
                emptyFlow()
            }
        }
        val coordinator = PlaybackPrefetchCoordinator(
            settingsStore, repository, profile, mockk<PlaybackLinkWarmup>(), scope, dwellMs
        ) { testTime }
        private var testTime: Long = 0
        fun target(index: Int, type: String = "movie", kind: PrefetchTarget.Kind = PrefetchTarget.Kind.MOVIE_DETAILS) =
            PrefetchTarget(1, type, "video$index", "content$index", kind = kind)
        suspend fun playSearch(type: String, id: String) {
            if (type to id !in prefetchedKeys) repository.getStreamsFromAllAddons(type, id).collect {}
        }
    }
}
