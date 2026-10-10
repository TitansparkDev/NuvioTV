package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.data.local.PlayerSettings

/**
 * Fork-only autoplay controls kept outside the upstream settings implementation.
 * This reduces the surface area touched by future upstream refactors.
 */
@Composable
internal fun ForkPlaybackAutoPlayControls(
    settings: PlayerSettings,
    onUpdate: PlaybackSettingsUpdate
) {
    SliderSettingsItem(
        title = stringResource(R.string.autoplay_prefetch_lead_title),
        subtitle = stringResource(R.string.autoplay_prefetch_lead_sub),
        value = settings.nextEpisodePrefetchLeadSeconds,
        valueText = if (settings.nextEpisodePrefetchLeadSeconds == 0) {
            stringResource(R.string.autoplay_prefetch_lead_off)
        } else {
            "${settings.nextEpisodePrefetchLeadSeconds}s"
        },
        minValue = PlayerSettings.MIN_NEXT_EPISODE_PREFETCH_LEAD_SECONDS,
        maxValue = PlayerSettings.MAX_NEXT_EPISODE_PREFETCH_LEAD_SECONDS,
        step = 5,
        onValueChange = { seconds -> onUpdate { setNextEpisodePrefetchLeadSeconds(seconds) } }
    )
    SettingsToggleRow(
        title = stringResource(R.string.autoplay_silent_title),
        subtitle = stringResource(R.string.autoplay_silent_sub),
        checked = settings.nextEpisodeSilentAutoPlay,
        onToggle = {
            onUpdate { setNextEpisodeSilentAutoPlay(!settings.nextEpisodeSilentAutoPlay) }
        }
    )
}
