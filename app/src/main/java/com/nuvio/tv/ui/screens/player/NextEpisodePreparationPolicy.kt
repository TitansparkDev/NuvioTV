package com.nuvio.tv.ui.screens.player

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

    /** A source-only warmup must not downgrade a resolving or banked playable link. */
    fun shouldReusePlayablePreparation(
        current: Target?,
        requested: Target,
        resolving: Boolean,
        preparedVideoId: String?,
    ): Boolean {
        if (!resolving && preparedVideoId != requested.videoId) return false
        return current?.copy(sourceConfiguration = requested.sourceConfiguration) == requested
    }
}
