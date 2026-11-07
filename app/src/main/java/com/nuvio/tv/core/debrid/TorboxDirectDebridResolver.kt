package com.nuvio.tv.core.debrid

import android.util.Log
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.TorboxApi
import com.nuvio.tv.data.remote.dto.TorboxCreateTorrentDataDto
import com.nuvio.tv.data.remote.dto.TorboxEnvelopeDto
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TorboxResolver"
private const val MAX_ENVELOPE_TEXT_CHARS = 2_048

@Singleton
class TorboxDirectDebridResolver @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val api: TorboxApi,
    private val fileSelector: TorboxFileSelector
) {
    suspend fun resolve(
        stream: Stream,
        season: Int?,
        episode: Int?
    ): DirectDebridResolveResult {
        val resolve = stream.clientResolve ?: return DirectDebridResolveResult.Error
        val apiKey = dataStore.settings.first().torboxApiKey.trim()
        if (apiKey.isBlank()) return DirectDebridResolveResult.MissingApiKey
        val magnet = resolve.magnetUri?.takeIf { it.isNotBlank() }
            ?: buildMagnetUri(resolve)
            ?: return DirectDebridResolveResult.Stale
        val authorization = "Bearer $apiKey"

        return try {
            Log.d(TAG, "resolve: createTorrent hash=${resolve.infoHash?.take(12)}...")
            val createStartMs = System.currentTimeMillis()
            val create = api.createTorrent(
                authorization = authorization,
                magnet = magnet.toTextPart(),
                addOnlyIfCached = "true".toTextPart(),
                allowZip = "false".toTextPart()
            )
            Log.d(TAG, "resolve: createTorrent done in ${System.currentTimeMillis() - createStartMs}ms code=${create.code()}")
            val torrentId = create.extractTorrentId() ?: return create.toFailureResult("createTorrent")

            Log.d(TAG, "resolve: getTorrent id=$torrentId")
            val getTorrentStartMs = System.currentTimeMillis()
            val torrent = api.getTorrent(
                authorization = authorization,
                id = torrentId,
                bypassCache = true
            )
            Log.d(TAG, "resolve: getTorrent done in ${System.currentTimeMillis() - getTorrentStartMs}ms code=${torrent.code()}")
            if (!torrent.isSuccessful) return torrent.toFailureResult("getTorrent")
            val files = torrent.body()?.data?.files.orEmpty()
            val file = fileSelector.selectFile(files, resolve, season, episode)
                ?: return DirectDebridResolveResult.Stale
            val fileId = file.id ?: return DirectDebridResolveResult.Stale

            Log.d(TAG, "resolve: requestDownloadLink torrentId=$torrentId fileId=$fileId")
            val linkStartMs = System.currentTimeMillis()
            val link = api.requestDownloadLink(
                authorization = authorization,
                token = apiKey,
                torrentId = torrentId,
                fileId = fileId,
                zipLink = false,
                redirect = false,
                appendName = false
            )
            Log.d(TAG, "resolve: requestDownloadLink done in ${System.currentTimeMillis() - linkStartMs}ms code=${link.code()}")
            if (!link.isSuccessful) return link.toFailureResult("requestDownloadLink")
            val url = link.body()?.data?.takeIf { it.isNotBlank() }
                ?: return link.toFailureResult("requestDownloadLink")

            DirectDebridResolveResult.Success(
                url = url,
                filename = file.displayName().takeIf { it.isNotBlank() },
                videoSize = file.size
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w(TAG, "resolve: failed with ${error::class.simpleName}: ${error.message}")
            DirectDebridResolveResult.Error
        }
    }

    private fun Response<TorboxEnvelopeDto<TorboxCreateTorrentDataDto>>.extractTorrentId(): Int? {
        if (!isSuccessful) return null
        val body = body()
        if (body?.success == false) return null
        return body?.data?.resolvedTorrentId()
    }

    private fun buildMagnetUri(resolve: StreamClientResolve): String? {
        val hash = resolve.infoHash?.takeIf { it.isNotBlank() } ?: return null
        return buildString {
            append("magnet:?xt=urn:btih:")
            append(hash)
            resolve.sources
                ?.filter { it.isNotBlank() }
                ?.forEach { source ->
                    append("&tr=")
                    append(java.net.URLEncoder.encode(source, "UTF-8"))
                }
        }
    }

    private fun String.toTextPart(): RequestBody {
        return toRequestBody("text/plain".toMediaType())
    }
}

/** How a TorBox API response that did not yield a usable link should be read. */
internal enum class TorboxFailureKind {
    /** Credentials rejected. Says nothing about the torrent or the delivery link. */
    UNAUTHORIZED,
    RATE_LIMITED,
    /** The torrent is not cached and the request was limited to cached torrents. */
    NOT_CACHED,
    /** The torrent or file is no longer usable; a fresh stream search may find another. */
    STALE,
    /** A server-side failure we cannot attribute to the torrent, the key, or the limits. */
    UNKNOWN
}

/**
 * Classifies a failed TorBox response from its HTTP status and the envelope's error/detail text.
 * Text is read for every status, so a cached-only refusal is recognised whatever status it arrives
 * with; the status alone decides only when the text is silent.
 */
internal fun torboxFailureKind(httpCode: Int, envelopeText: String): TorboxFailureKind {
    val text = envelopeText.lowercase()
    return when {
        httpCode == 401 || httpCode == 403 || text.containsAny(TORBOX_AUTH_MARKERS) -> TorboxFailureKind.UNAUTHORIZED
        httpCode == 429 || text.containsAny(TORBOX_RATE_LIMIT_MARKERS) -> TorboxFailureKind.RATE_LIMITED
        httpCode == 409 || text.containsAny(TORBOX_NOT_CACHED_MARKERS) -> TorboxFailureKind.NOT_CACHED
        httpCode in 500..599 -> TorboxFailureKind.UNKNOWN
        else -> TorboxFailureKind.STALE
    }
}

private fun TorboxFailureKind.toResult(): DirectDebridResolveResult = when (this) {
    TorboxFailureKind.NOT_CACHED -> DirectDebridResolveResult.NotCached
    TorboxFailureKind.STALE -> DirectDebridResolveResult.Stale
    TorboxFailureKind.UNAUTHORIZED,
    TorboxFailureKind.RATE_LIMITED,
    TorboxFailureKind.UNKNOWN -> DirectDebridResolveResult.Error
}

private fun <T> Response<TorboxEnvelopeDto<T>>.toFailureResult(operation: String): DirectDebridResolveResult {
    val kind = torboxFailureKind(httpCode = code(), envelopeText = envelopeFailureText())
    Log.w(TAG, "$operation did not return a link: kind=$kind http=${code()}")
    return kind.toResult()
}

/**
 * The envelope's error and detail text. A success body is read through the typed envelope; an error
 * body is read from its raw JSON, because Retrofit leaves [Response.body] empty for error statuses.
 */
private fun <T> Response<TorboxEnvelopeDto<T>>.envelopeFailureText(): String {
    body()?.let { envelope ->
        return listOfNotNull(envelope.error, envelope.detail).joinToString(" ")
    }
    val raw = runCatching { errorBody()?.use { it.string() } }.getOrNull()
        .orEmpty()
        .take(MAX_ENVELOPE_TEXT_CHARS)
    return TORBOX_ENVELOPE_TEXT_FIELD.findAll(raw).joinToString(" ") { match -> match.groupValues[2] }
}

private val TORBOX_ENVELOPE_TEXT_FIELD = Regex("\"(error|detail)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){0,512})\"")

private val TORBOX_AUTH_MARKERS = listOf(
    "unauthorized",
    "unauthenticated",
    "not authenticated",
    "authentication",
    "auth_error",
    "invalid token",
    "invalid_token",
    "api key",
    "api_key",
    "forbidden"
)

private val TORBOX_RATE_LIMIT_MARKERS = listOf(
    "rate limit",
    "rate_limit",
    "ratelimit",
    "too many requests"
)

private val TORBOX_NOT_CACHED_MARKERS = listOf(
    "not cached",
    "not_cached",
    "notcached",
    "uncached",
    "not in cache",
    "not_in_cache",
    "only_if_cached",
    "only if cached",
    "cached only",
    "cached_only"
)

private fun String.containsAny(markers: List<String>): Boolean = markers.any { contains(it) }

sealed class DirectDebridResolveResult {
    data class Success(
        val url: String,
        val filename: String?,
        val videoSize: Long?
    ) : DirectDebridResolveResult()

    data object MissingApiKey : DirectDebridResolveResult()
    data object NotCached : DirectDebridResolveResult()
    data object Stale : DirectDebridResolveResult()
    data object Error : DirectDebridResolveResult()
}
