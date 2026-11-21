package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.domain.model.Stream

/** Identity and eligibility for the one background preparation owned by a player. */
internal object NextEpisodePreparationPolicy {
    data class Target(
        val profileId: Int,
        val contentId: String,
        val videoId: String,
        val season: Int?,
        val episode: Int?,
        val sourceConfiguration: String,
        val bingeGroup: String?,
    )

    fun shouldPrepare(
        enabled: Boolean,
        hasAired: Boolean,
        cloud: Boolean,
        partyLocked: Boolean,
        manualPickerRequired: Boolean,
    ): Boolean = enabled && hasAired && !cloud && !partyLocked && !manualPickerRequired

    fun shouldWarm(stream: Stream, settings: PlayerSettings, alreadyWarmed: Boolean): Boolean {
        val url = stream.url?.trim().orEmpty()
        return settings.prefetchPrepareTopSource && !alreadyWarmed &&
            (url.startsWith("http://", true) || url.startsWith("https://", true)) &&
            !stream.isExternal()
    }
}
