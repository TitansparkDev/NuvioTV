package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamDebridCacheState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DirectDebridResolver @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val torboxResolver: TorboxDirectDebridResolver,
    private val realDebridResolver: RealDebridDirectDebridResolver,
    private val premiumizeResolver: PremiumizeDirectDebridResolver,
    private val localDebridService: LocalDebridService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    // Writes hold [mutex]; reads that must not suspend (see [hasPreparedTorboxDeliveryUrl]) rely on this map being concurrent.
    private val resolvedCache = ConcurrentHashMap<String, CachedDirectDebridResolve>()
    private val inFlightResolves = mutableMapOf<String, Deferred<DirectDebridResolveResult>>()

    suspend fun resolve(
        stream: Stream,
        season: Int?,
        episode: Int?
    ): DirectDebridResolveResult = resolveInternal(stream, season, episode, refreshed = false)

    private suspend fun resolveInternal(
        stream: Stream,
        season: Int?,
        episode: Int?,
        refreshed: Boolean
    ): DirectDebridResolveResult {
        if (!shouldResolveToPlayableStream(stream)) {
            return DirectDebridResolveResult.Stale
        }
        val identity = stream.directDebridResolveIdentity(season, episode)
        if (identity == null) {
            return resolveUncached(stream, season, episode)
        }
        val cacheKey = identity.key
        getCachedResult(cacheKey)?.let {
            return it
        }

        var ownsResolve = false
        val newResolve = scope.async(start = CoroutineStart.LAZY) {
            resolveUncached(stream, season, episode)
        }
        val activeResolve = mutex.withLock {
            getCachedResultLocked(cacheKey)?.let { cached ->
                return@withLock null to cached
            }
            val existing = inFlightResolves[cacheKey]
            if (existing != null) {
                existing to null
            } else {
                inFlightResolves[cacheKey] = newResolve
                ownsResolve = true
                newResolve to null
            }
        }
        activeResolve.second?.let {
            newResolve.cancel()
            return it
        }
        val deferred = activeResolve.first ?: return DirectDebridResolveResult.Error
        if (!ownsResolve) newResolve.cancel()
        if (ownsResolve) deferred.start()

        return try {
            val result = deferred.await()
            if (ownsResolve && result is DirectDebridResolveResult.Success) {
                mutex.withLock {
                    resolvedCache[cacheKey] = CachedDirectDebridResolve(
                        result = result,
                        cachedAtMs = System.currentTimeMillis(),
                        original = DirectDebridOriginal(
                            stream = stream,
                            season = season,
                            episode = episode,
                            providerId = identity.providerId,
                            credentialFingerprint = identity.credentialFingerprint
                        ),
                        refreshed = refreshed
                    )
                }
            }
            result
        } finally {
            if (ownsResolve) {
                mutex.withLock {
                    if (inFlightResolves[cacheKey] === deferred) {
                        inFlightResolves.remove(cacheKey)
                    }
                }
            }
        }
    }

    /**
     * Re-resolves the stored original of the prepared TorBox link [url] once, for a link the player
     * saw rejected. Only that entry is invalidated; other episodes and accounts keep their links.
     *
     * Returns null when [url] is not a prepared TorBox link of the current account, and when [url]
     * is itself a refreshed link that was rejected again (its entry is dropped, so the next playback
     * resolves afresh rather than replaying the rejected link).
     */
    suspend fun refreshPreparedTorboxUrl(url: String): DirectDebridResolveResult? {
        val (cacheKey, prepared) = mutex.withLock {
            resolvedCache.entries
                .firstOrNull { (_, cached) -> cached.isPreparedTorboxDelivery(url) }
                ?.let { entry -> entry.key to entry.value }
        } ?: return null

        val original = prepared.original
        val current = original.stream.directDebridResolveIdentity(original.season, original.episode)
        if (current == null ||
            current.key != cacheKey ||
            current.providerId != DebridProviders.TORBOX_ID ||
            current.credentialFingerprint != original.credentialFingerprint
        ) {
            return null
        }

        mutex.withLock {
            resolvedCache.remove(cacheKey, prepared)
        }
        if (prepared.refreshed) return null
        return resolveInternal(original.stream, original.season, original.episode, refreshed = true)
    }

    /**
     * Synchronous check for the player's error path: whether [url] is a TorBox delivery link this
     * resolver prepared in memory. It does not check the account; [refreshPreparedTorboxUrl] does.
     */
    fun hasPreparedTorboxDeliveryUrl(url: String): Boolean =
        resolvedCache.values.any { cached -> cached.isPreparedTorboxDelivery(url) }

    suspend fun cachedPlayableStream(stream: Stream, season: Int?, episode: Int?): Stream? {
        if (!shouldResolveToPlayableStream(stream)) return null
        val cacheKey = stream.directDebridResolveIdentity(season, episode)?.key ?: return null
        return getCachedResult(cacheKey)?.let { result -> stream.withResolvedDebridUrl(result) }
    }

    suspend fun resolveToPlayableStream(
        stream: Stream,
        season: Int?,
        episode: Int?
    ): DirectDebridPlayableResult {
        if (!shouldResolveToPlayableStream(stream)) {
            return DirectDebridPlayableResult.Success(stream)
        }
        return when (val result = resolve(stream, season, episode)) {
            is DirectDebridResolveResult.Success -> DirectDebridPlayableResult.Success(stream.withResolvedDebridUrl(result))
            DirectDebridResolveResult.MissingApiKey -> DirectDebridPlayableResult.MissingApiKey
            DirectDebridResolveResult.NotCached -> DirectDebridPlayableResult.NotCached
            DirectDebridResolveResult.Stale -> DirectDebridPlayableResult.Stale
            DirectDebridResolveResult.Error -> DirectDebridPlayableResult.Error
        }
    }

    suspend fun shouldResolveToPlayableStream(stream: Stream): Boolean {
        val settings = dataStore.settings.first()
        if (!settings.canResolvePlayableLinks) return false
        if (stream.needsLocalDebridResolve()) {
            return localTorrentResolveCredential(settings) != null
        }
        if (!stream.isDirectDebrid() || stream.getStreamUrl() != null) return false
        val providerId = DebridProviders.byId(stream.clientResolve?.service)?.id ?: return false
        return providerId == settings.activeResolverProviderId &&
            settings.apiKeyFor(providerId).isNotBlank()
    }

    private suspend fun getCachedResult(cacheKey: String): DirectDebridResolveResult.Success? =
        mutex.withLock { getCachedResultLocked(cacheKey) }

    private fun getCachedResultLocked(cacheKey: String): DirectDebridResolveResult.Success? {
        val cached = resolvedCache[cacheKey] ?: return null
        val age = System.currentTimeMillis() - cached.cachedAtMs
        return if (age in 0..DIRECT_DEBRID_RESOLVE_CACHE_TTL_MS) {
            cached.result
        } else {
            resolvedCache.remove(cacheKey)
            null
        }
    }

    private suspend fun resolveUncached(
        stream: Stream,
        season: Int?,
        episode: Int?
    ): DirectDebridResolveResult {
        if (stream.needsLocalDebridResolve()) {
            return resolveLocalTorrentStream(stream, season, episode)
        }
        return when (DebridProviders.byId(stream.clientResolve?.service)?.id) {
            DebridProviders.TORBOX_ID -> torboxResolver.resolve(stream, season, episode)
            DebridProviders.PREMIUMIZE_ID -> premiumizeResolver.resolve(stream, season, episode)
            DebridProviders.REAL_DEBRID_ID -> realDebridResolver.resolve(stream, season, episode)
            else -> DirectDebridResolveResult.Error
        }
    }

    /**
     * The cache identity of [this] stream under the current account, or null when it cannot be
     * resolved under that account. [DirectDebridResolveIdentity.key] is the cache key.
     */
    private suspend fun Stream.directDebridResolveIdentity(season: Int?, episode: Int?): DirectDebridResolveIdentity? {
        if (needsLocalDebridResolve()) {
            val settings = dataStore.settings.first()
            val account = localTorrentResolveCredential(settings) ?: return null
            val apiKey = account.apiKey.trim().takeIf { it.isNotBlank() } ?: return null
            val identity = getEffectiveInfoHash() ?: torrentMagnetUri() ?: behaviorHints?.filename ?: return null
            val fingerprint = apiKey.stableFingerprint()
            val key = listOf(
                account.provider.id,
                fingerprint,
                identity.trim().lowercase(),
                getEffectiveFileIdx()?.toString().orEmpty(),
                behaviorHints?.filename.orEmpty().trim().lowercase(),
                season?.toString().orEmpty(),
                episode?.toString().orEmpty()
            ).joinToString("|")
            return DirectDebridResolveIdentity(key, account.provider.id, fingerprint)
        }
        val resolve = clientResolve ?: return null
        val providerId = DebridProviders.byId(resolve.service)?.id ?: return null
        val settings = dataStore.settings.first()
        if (!settings.canResolvePlayableLinks || providerId != settings.activeResolverProviderId) return null
        val apiKey = settings.apiKeyFor(providerId).trim().takeIf { it.isNotBlank() } ?: return null
        val identity = resolve.infoHash
            ?: resolve.magnetUri
            ?: resolve.torrentName
            ?: resolve.filename
            ?: return null
        val fingerprint = apiKey.stableFingerprint()

        val key = listOf(
            providerId,
            fingerprint,
            identity.trim().lowercase(),
            resolve.fileIdx?.toString().orEmpty(),
            (resolve.filename ?: behaviorHints?.filename).orEmpty().trim().lowercase(),
            (season ?: resolve.season)?.toString().orEmpty(),
            (episode ?: resolve.episode)?.toString().orEmpty()
        ).joinToString("|")
        return DirectDebridResolveIdentity(key, providerId, fingerprint)
    }

    private suspend fun resolveLocalTorrentStream(
        stream: Stream,
        season: Int?,
        episode: Int?
    ): DirectDebridResolveResult {
        val settings = dataStore.settings.first()
        val account = localTorrentResolveCredential(settings) ?: return DirectDebridResolveResult.MissingApiKey
        val hash = stream.getEffectiveInfoHash()?.trim()?.lowercase()
        if (stream.debridCacheStatus?.state == StreamDebridCacheState.NOT_CACHED) {
            return DirectDebridResolveResult.NotCached
        }
        if (
            !hash.isNullOrBlank() &&
            stream.debridCacheStatus?.state != StreamDebridCacheState.CACHED &&
            account.provider.supports(DebridProviderCapability.LocalTorrentCacheCheck)
        ) {
            when (localDebridService.isCached(account, hash)) {
                false -> return DirectDebridResolveResult.NotCached
                true, null -> Unit
            }
        }

        val magnet = DebridMagnetBuilder.fromStream(stream)
            ?: return DirectDebridResolveResult.Stale
        val resolveStream = stream.copy(
            clientResolve = StreamClientResolve(
                type = "torrent",
                infoHash = stream.getEffectiveInfoHash(),
                fileIdx = stream.getEffectiveFileIdx(),
                magnetUri = magnet,
                sources = stream.sources,
                torrentName = null,
                filename = stream.behaviorHints?.filename,
                mediaType = null,
                mediaId = null,
                mediaOnlyId = null,
                title = stream.title ?: stream.name,
                season = season,
                episode = episode,
                service = account.provider.id,
                serviceIndex = null,
                serviceExtension = null,
                isCached = stream.debridCacheStatus?.state == StreamDebridCacheState.CACHED,
                stream = null
            )
        )

        return when (account.provider.id) {
            DebridProviders.TORBOX_ID -> torboxResolver.resolve(resolveStream, season, episode)
            DebridProviders.PREMIUMIZE_ID -> premiumizeResolver.resolve(resolveStream, season, episode)
            else -> DirectDebridResolveResult.Error
        }
    }

    private fun localTorrentResolveCredential(
        settings: com.nuvio.tv.domain.model.DebridSettings
    ): DebridServiceCredential? =
        settings.activeResolverCredential
            ?.takeIf { credential -> credential.provider.supports(DebridProviderCapability.LocalTorrentResolve) }
}

private const val DIRECT_DEBRID_RESOLVE_CACHE_TTL_MS = 15L * 60L * 1000L

/** A stream's cache identity under one account: the cache [key], its provider, and a fingerprint of its API key. */
private data class DirectDebridResolveIdentity(
    val key: String,
    val providerId: String,
    val credentialFingerprint: String
)

/** The unresolved stream a prepared link was made from, so it can be resolved again. */
private data class DirectDebridOriginal(
    val stream: Stream,
    val season: Int?,
    val episode: Int?,
    val providerId: String,
    val credentialFingerprint: String
)

private data class CachedDirectDebridResolve(
    val result: DirectDebridResolveResult.Success,
    val cachedAtMs: Long,
    val original: DirectDebridOriginal,
    /** True for a link produced by [DirectDebridResolver.refreshPreparedTorboxUrl]; such a link is not refreshed again. */
    val refreshed: Boolean
)

private fun CachedDirectDebridResolve.isPreparedTorboxDelivery(url: String): Boolean =
    original.providerId == DebridProviders.TORBOX_ID && result.url == url

sealed class DirectDebridPlayableResult {
    data class Success(val stream: Stream) : DirectDebridPlayableResult()
    data object MissingApiKey : DirectDebridPlayableResult()
    data object NotCached : DirectDebridPlayableResult()
    data object Stale : DirectDebridPlayableResult()
    data object Error : DirectDebridPlayableResult()
}

private fun String.stableFingerprint(): String {
    val hash = fold(1125899906842597L) { acc, char -> (acc * 31L) + char.code }
    return hash.toULong().toString(16)
}

private fun Stream.withResolvedDebridUrl(result: DirectDebridResolveResult.Success): Stream =
    copy(
        url = result.url,
        externalUrl = null,
        behaviorHints = behaviorHints.mergeResolvedDebridHints(result)
    )

private fun StreamBehaviorHints?.mergeResolvedDebridHints(
    result: DirectDebridResolveResult.Success
): StreamBehaviorHints {
    val current = this
    if (current != null) {
        return current.copy(
            filename = result.filename ?: current.filename,
            videoSize = result.videoSize ?: current.videoSize
        )
    }
    return StreamBehaviorHints(
        notWebReady = null,
        bingeGroup = null,
        countryWhitelist = null,
        proxyHeaders = null,
        filename = result.filename,
        videoSize = result.videoSize
    )
}
