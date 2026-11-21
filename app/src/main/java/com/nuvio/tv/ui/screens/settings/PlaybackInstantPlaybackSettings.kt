package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.data.local.PlayerSettings

@Composable
internal fun InstantPlaybackControls(settings: PlayerSettings, onUpdate: PlaybackSettingsUpdate) {
    SettingsToggleRow(
        title = stringResource(R.string.instant_playback_movie),
        subtitle = stringResource(R.string.instant_playback_note),
        checked = settings.prefetchMovieOnDetails,
        onToggle = { onUpdate { setPrefetchMovieOnDetails(!settings.prefetchMovieOnDetails) } }
    )
    SettingsToggleRow(
        title = stringResource(R.string.instant_playback_episode),
        subtitle = null,
        checked = settings.prefetchEpisodeOnDetails,
        onToggle = { onUpdate { setPrefetchEpisodeOnDetails(!settings.prefetchEpisodeOnDetails) } }
    )
    SettingsToggleRow(
        title = stringResource(R.string.instant_playback_top_source),
        subtitle = null,
        checked = settings.prefetchPrepareTopSource,
        onToggle = { onUpdate { setPrefetchPrepareTopSource(!settings.prefetchPrepareTopSource) } }
    )
    SettingsToggleRow(
        title = stringResource(R.string.instant_playback_fast_start),
        subtitle = stringResource(R.string.instant_playback_fast_start_note),
        checked = settings.autoPlayFastStart,
        onToggle = { onUpdate { setAutoPlayFastStart(!settings.autoPlayFastStart) } }
    )
    SettingsToggleRow(
        title = stringResource(R.string.instant_playback_continue_watching),
        subtitle = stringResource(R.string.instant_playback_continue_watching_note),
        checked = settings.prefetchContinueWatching,
        onToggle = { onUpdate { setPrefetchContinueWatching(!settings.prefetchContinueWatching) } }
    )
}
