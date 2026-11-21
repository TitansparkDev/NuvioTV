package com.nuvio.tv.core.streams

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.domain.repository.StreamRepository

/** Owns speculative detail-page searches; callers replace the pending target as focus changes. */
@Singleton
class PlaybackPrefetchCoordinator @Inject constructor(
    private val playerSettings: PlayerSettingsDataStore,
    private val streamRepository: StreamRepository,
    private val profileManager: ProfileManager,
    private val linkWarmup: PlaybackLinkWarmup
) {
    internal constructor(
        playerSettings: PlayerSettingsDataStore,
        streamRepository: StreamRepository,
        profileManager: ProfileManager,
        linkWarmup: PlaybackLinkWarmup,
        scope: CoroutineScope,
        dwellMs: Long = 1_000L
    ) : this(playerSettings, streamRepository, profileManager, linkWarmup) {
        this.scope = scope
        this.dwellMs = dwellMs
    }

    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var dwellMs: Long = 1_000L
    private var pending: Job? = null

    fun request(target: PrefetchTarget) {
        if (target.kind == PrefetchTarget.Kind.NEXT_EPISODE) return
        pending?.cancel()
        pending = scope.launch {
            val settings = playerSettings.playerSettings.first()
            val enabled = when (target.kind) {
                PrefetchTarget.Kind.MOVIE_DETAILS -> settings.prefetchMovieOnDetails
                PrefetchTarget.Kind.EPISODE_DETAILS -> settings.prefetchEpisodeOnDetails
                PrefetchTarget.Kind.CONTINUE_WATCHING -> settings.prefetchContinueWatching
                PrefetchTarget.Kind.NEXT_EPISODE -> false
            }
            if (!enabled || target.profileId != profileManager.activeProfileId.value) return@launch
            delay(dwellMs)
            val cached = streamRepository.getCachedStreamsFromAllAddons(target.type, target.videoId, target.season, target.episode)
            if (cached != null) return@launch
            streamRepository.getStreamsFromAllAddons(target.type, target.videoId, target.season, target.episode)
                .collect {}
        }
    }

    fun warmCachedLink(target: PrefetchTarget, url: String, headers: Map<String, String>, filename: String?) {
        pending?.cancel()
        pending = scope.launch {
            if (target.profileId != profileManager.activeProfileId.value) return@launch
            val settings = playerSettings.playerSettings.first()
            if (!settings.prefetchContinueWatching) return@launch
            delay(dwellMs)
            linkWarmup.warm(url, headers, filename, null, false)
        }
    }

    fun cancelPending(target: PrefetchTarget) {
        if (pending?.isActive == true) pending?.cancel()
    }

    fun onProfileChanged() {
        pending?.cancel()
        pending = null
    }
}
