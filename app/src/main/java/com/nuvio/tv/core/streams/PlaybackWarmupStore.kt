package com.nuvio.tv.core.streams

import java.security.MessageDigest
import java.util.LinkedHashMap

data class PlaybackWarmupEntry(val finalUrl: String, val totalLength: Long, val createdAtMs: Long)

object PlaybackWarmupStore {
    private const val MAX_ENTRIES = 16
    private const val TTL_MS = 10 * 60 * 1000L
    internal var nowMs: () -> Long = System::currentTimeMillis
    private val entries = object : LinkedHashMap<String, PlaybackWarmupEntry>(MAX_ENTRIES, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PlaybackWarmupEntry>?) = size > MAX_ENTRIES
    }

    private fun key(url: String, headers: Map<String, String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        headers.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (name, value) ->
            digest.update(name.toByteArray()); digest.update(0); digest.update(value.toByteArray()); digest.update(0)
        }
        return "$url|${digest.digest().joinToString("") { "%02x".format(it) }}"
    }

    @Synchronized fun put(originalUrl: String, headers: Map<String, String>, entry: PlaybackWarmupEntry) { entries[key(originalUrl, headers)] = entry }
    @Synchronized fun effectiveUrl(originalUrl: String, headers: Map<String, String>): String {
        val k = key(originalUrl, headers); val entry = entries[k] ?: return originalUrl
        if (nowMs() - entry.createdAtMs >= TTL_MS) { entries.remove(k); return originalUrl }
        return entry.finalUrl
    }
    @Synchronized fun invalidate(originalUrl: String, headers: Map<String, String>) { entries.remove(key(originalUrl, headers)) }
    @Synchronized fun clear() = entries.clear()
}
