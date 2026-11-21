package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.core.streams.PlaybackAvailability
import com.nuvio.tv.core.streams.PrefetchTarget
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.NextToWatch
import com.nuvio.tv.domain.model.Video

internal fun detailsPrefetchTarget(
    profileId: Int,
    meta: Meta,
    nextToWatch: NextToWatch?,
    episodesForSeason: List<Video>,
    settings: PlayerSettings,
    availability: PlaybackAvailability
): PrefetchTarget? {
    val video = resolveHeroPlaybackVideo(meta, nextToWatch, episodesForSeason)
    val enabled = if (video == null) settings.prefetchMovieOnDetails else settings.prefetchEpisodeOnDetails
    if (!enabled) return null
    val videoId = video?.id ?: meta.id
    if (!availability.canStream(meta.apiType, videoId, meta.id, video)) return null
    return if (video != null) {
        PrefetchTarget(profileId, meta.apiType, video.id, meta.id, video.season, video.episode,
            PrefetchTarget.Kind.EPISODE_DETAILS)
    } else {
        PrefetchTarget(profileId, meta.apiType, meta.id, meta.id, kind = PrefetchTarget.Kind.MOVIE_DETAILS)
    }
}
