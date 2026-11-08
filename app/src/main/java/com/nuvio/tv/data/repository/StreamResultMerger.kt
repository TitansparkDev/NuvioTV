package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream

/** Retains incremental source batches both in the UI and while availability checks wait. */
internal object StreamResultMerger {
    fun merge(existing: AddonStreams?, incoming: AddonStreams): AddonStreams {
        if (existing == null) return incoming
        val streamsByKey = LinkedHashMap<String, Stream>()
        existing.streams.forEach { streamsByKey[it.dedupKey()] = it }
        incoming.streams.forEach { streamsByKey[it.dedupKey()] = it }
        return existing.copy(streams = streamsByKey.values.toList())
    }

    private fun Stream.dedupKey(): String {
        val base = infoHash?.lowercase()?.let { hash -> "$hash:${fileIdx ?: ""}" }
            ?: clientResolve?.infoHash?.lowercase()?.let { hash -> "$hash:${clientResolve.fileIdx}" }
            ?: url
            ?: externalUrl
            ?: ytId
            ?: serverTarget?.key()
            ?: "${addonName}:${name}:${title}"
        val nameSuffix = if (base == url) {
            name?.takeIf { it.isNotBlank() }?.let { "|$it" } ?: ""
        } else ""
        return "$base$nameSuffix"
    }
}
