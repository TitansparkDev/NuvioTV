package com.nuvio.tv.core.player

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState

/** Decides whether an answered autoplay candidate is safe to start before the search ends. */
object AutoPlayEarlyDecision {
    fun decide(
        orderedSources: List<String>,
        finishedSources: Set<String>,
        candidate: Stream?,
        elapsedMs: Long,
        fastStart: Boolean
    ): Boolean {
        if (candidate == null || candidate.debridCacheStatus?.state in setOf(
                StreamDebridCacheState.CHECKING,
                StreamDebridCacheState.UNKNOWN,
                StreamDebridCacheState.NOT_CACHED
            )
        ) return false
        if (fastStart && elapsedMs >= 3_000L) return true
        val candidateSource = candidate.addonName
        val index = orderedSources.indexOf(candidateSource)
        return index >= 0 && orderedSources.take(index).all { it in finishedSources }
    }
}
