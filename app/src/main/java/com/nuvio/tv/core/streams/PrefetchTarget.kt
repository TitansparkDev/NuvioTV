package com.nuvio.tv.core.streams

data class PrefetchTarget(
    val profileId: Int,
    val type: String,
    val videoId: String,
    val contentId: String?,
    val season: Int? = null,
    val episode: Int? = null,
    val kind: Kind
) {
    enum class Kind { MOVIE_DETAILS, EPISODE_DETAILS, NEXT_EPISODE, CONTINUE_WATCHING }
}
