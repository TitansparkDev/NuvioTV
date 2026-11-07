package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.remote.api.PremiumizeApi
import com.nuvio.tv.data.remote.api.TorboxApi
import com.nuvio.tv.data.remote.dto.TorboxCheckCachedRequestDto
import android.os.SystemClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

data class LocalDebridCachedItem(
    val name: String?,
    val size: Long?
)

@Singleton
class LocalDebridService @Inject constructor(
    private val torboxApi: TorboxApi,
    private val premiumizeApi: PremiumizeApi
) {
    internal var nowMs: () -> Long = SystemClock::elapsedRealtime

    private data class CacheEntry(val item: LocalDebridCachedItem?, val expiresAt: Long)
    private val cacheMutex = Mutex()
    private val torboxMutex = Mutex()
    private val answers = LinkedHashMap<String, CacheEntry>(16, 0.75f, true)

    suspend fun checkCached(
        account: DebridServiceCredential,
        hashes: List<String>
    ): Map<String, LocalDebridCachedItem>? =
        when (account.provider.id) {
            DebridProviders.TORBOX_ID -> checkTorboxCached(account.apiKey, hashes)
            DebridProviders.PREMIUMIZE_ID -> checkPremiumizeCached(account.apiKey, hashes)
            else -> null
        }

    suspend fun isCached(account: DebridServiceCredential, hash: String): Boolean? {
        val normalizedHash = hash.trim().lowercase().takeIf { it.isNotBlank() } ?: return null
        return checkCached(account, listOf(normalizedHash))?.containsKey(normalizedHash)
    }

    private suspend fun checkTorboxCached(
        apiKey: String,
        hashes: List<String>
    ): Map<String, LocalDebridCachedItem>? {
        val normalized = hashes.normalizedHashes()
        if (normalized.isEmpty()) return emptyMap()
        val fingerprint = apiKey.trim()
        val result = LinkedHashMap<String, LocalDebridCachedItem>()
        val unknown = mutableListOf<String>()
        cacheMutex.withLock {
            normalized.forEach { hash ->
                val key = "$fingerprint:$hash"
                val entry = answers[key]
                if (entry != null && entry.expiresAt > nowMs()) {
                    entry.item?.let { result[hash] = it }
                } else {
                    answers.remove(key)
                    unknown += hash
                }
            }
        }
        for (batch in unknown.chunked(100)) {
            val fresh = torboxMutex.withLock {
                val needs = cacheMutex.withLock {
                    batch.filter { hash ->
                        val key = "$fingerprint:$hash"
                        val entry = answers[key]
                        if (entry != null && entry.expiresAt > nowMs()) {
                            entry.item?.let { result[hash] = it }
                            false
                        } else { answers.remove(key); true }
                    }
                }
                if (needs.isEmpty()) emptyMap() else fetchTorbox(apiKey, needs)?.also { found ->
                    cacheMutex.withLock {
                        val now = nowMs()
                        needs.forEach { hash ->
                            val item = found[hash]
                            answers["$fingerprint:$hash"] = CacheEntry(item, now + if (item == null) 30_000 else 120_000)
                        }
                        while (answers.size > 2048) answers.remove(answers.entries.first().key)
                    }
                }
            } ?: return null
            result.putAll(fresh)
        }
        return result
    }

    private suspend fun fetchTorbox(apiKey: String, hashes: List<String>): Map<String, LocalDebridCachedItem>? = try {
        val response = torboxApi.checkCached("Bearer ${apiKey.trim()}", body = TorboxCheckCachedRequestDto(hashes))
        val body = response.body()
        if (!response.isSuccessful || body?.success != true || body.data == null) null
        else body.data.mapKeys { it.key.trim().lowercase() }.mapValues { (_, value) ->
            LocalDebridCachedItem(value.name, value.size)
        }
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        null
    }

    private suspend fun checkPremiumizeCached(
        apiKey: String,
        hashes: List<String>
    ): Map<String, LocalDebridCachedItem>? =
        try {
            val normalizedHashes = hashes.normalizedHashes()
            if (normalizedHashes.isEmpty()) return emptyMap()
            val response = premiumizeApi.checkCache(
                authorization = "Bearer ${apiKey.trim()}",
                items = normalizedHashes.map { hash -> "magnet:?xt=urn:btih:$hash" }
            )
            val body = response.body()
            if (!response.isSuccessful || body?.status.equals("error", ignoreCase = true)) {
                null
            } else {
                normalizedHashes.mapIndexedNotNull { index, hash ->
                    if (body?.response?.getOrNull(index) != true) return@mapIndexedNotNull null
                    hash to LocalDebridCachedItem(
                        name = body.filename?.getOrNull(index),
                        size = body.filesize?.getOrNull(index)
                    )
                }.toMap()
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }

    private fun List<String>.normalizedHashes(): List<String> =
        map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
}
