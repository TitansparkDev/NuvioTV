package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.streams.PrefetchTarget

internal fun continueWatchingPrefetchTarget(profileId: Int, item: ContinueWatchingItem): PrefetchTarget = when (item) {
    is ContinueWatchingItem.InProgress -> PrefetchTarget(
        profileId, item.progress.contentType, item.progress.videoId, item.progress.contentId,
        item.progress.season, item.progress.episode, PrefetchTarget.Kind.CONTINUE_WATCHING
    )
    is ContinueWatchingItem.NextUp -> PrefetchTarget(
        profileId, item.info.contentType, item.info.videoId, item.info.contentId,
        item.info.season, item.info.episode, PrefetchTarget.Kind.CONTINUE_WATCHING
    )
}
