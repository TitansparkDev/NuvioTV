package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.TorboxApi
import com.nuvio.tv.data.remote.dto.TorboxCloudItemDto
import com.nuvio.tv.data.remote.dto.TorboxCachedItemDto
import com.nuvio.tv.data.remote.dto.TorboxCheckCachedRequestDto
import com.nuvio.tv.data.remote.dto.TorboxCreateTorrentDataDto
import com.nuvio.tv.data.remote.dto.TorboxDeviceAuthorizationDto
import com.nuvio.tv.data.remote.dto.TorboxDeviceTokenDto
import com.nuvio.tv.data.remote.dto.TorboxDeviceTokenRequestDto
import com.nuvio.tv.data.remote.dto.TorboxEnvelopeDto
import com.nuvio.tv.data.remote.dto.TorboxTorrentDataDto
import com.nuvio.tv.data.remote.dto.TorboxTorrentFileDto
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class TorboxDirectDebridResolverTest {
    @Test
    fun `resolve creates cached torrent then requests selected file link`() = runTest {
        val api = FakeTorboxApi(
            createResponse = Response.success(
                TorboxEnvelopeDto(
                    success = true,
                    data = TorboxCreateTorrentDataDto(torrentId = 44)
                )
            ),
            torrentResponse = Response.success(
                TorboxEnvelopeDto(
                    success = true,
                    data = TorboxTorrentDataDto(
                        id = 44,
                        files = listOf(
                            TorboxTorrentFileDto(id = 1, name = "wrong.mkv", size = 10),
                            TorboxTorrentFileDto(id = 7, name = "right.mkv", size = 20)
                        )
                    )
                )
            ),
            linkResponse = Response.success(TorboxEnvelopeDto(success = true, data = "https://cdn.example/right.mkv"))
        )
        val resolver = resolver(api)

        val result = resolver.resolve(stream(fileIdx = 7), season = null, episode = null)

        assertTrue(result is DirectDebridResolveResult.Success)
        result as DirectDebridResolveResult.Success
        assertEquals("https://cdn.example/right.mkv", result.url)
        assertEquals("right.mkv", result.filename)
        assertEquals(20L, result.videoSize)
        assertEquals(1, api.createCalls)
        assertEquals(1, api.torrentCalls)
        assertEquals(1, api.requestCalls)
        assertEquals(true, api.lastAddOnlyIfCached)
        assertEquals(false, api.lastAllowZip)
        assertEquals(7, api.lastFileId)
    }

    @Test
    fun `resolve treats non cached create response as stale`() = runTest {
        val api = FakeTorboxApi(
            createResponse = Response.error(
                400,
                """{"success":false}""".toResponseBody("application/json".toMediaType())
            )
        )
        val resolver = resolver(api)

        val result = resolver.resolve(stream(fileIdx = 1), season = null, episode = null)

        assertTrue(result is DirectDebridResolveResult.Stale)
        assertEquals(1, api.createCalls)
        assertEquals(0, api.torrentCalls)
        assertEquals(0, api.requestCalls)
    }

    @Test
    fun `PMR episode two requests its provider file id in either file order`() = runTest {
        for (files in listOf(pmrFiles, pmrFiles.reversed())) {
            val api = cachedApi(files)

            val result = resolver(api).resolve(pmrStream(), season = 1, episode = 2)

            assertTrue(result is DirectDebridResolveResult.Success)
            result as DirectDebridResolveResult.Success
            assertEquals(pmrFilename, result.filename)
            assertEquals(4_817_912_734L, result.videoSize)
            assertEquals(4, api.lastFileId)
            assertEquals(1, api.requestCalls)
        }
    }

    @Test
    fun `missing PMR episode does not request another episode link`() = runTest {
        val api = cachedApi(pmrFiles.take(1))

        val result = resolver(api).resolve(pmrStream(), season = 1, episode = 2)

        assertTrue(result is DirectDebridResolveResult.Stale)
        assertEquals(0, api.requestCalls)
    }

    @Test
    fun `torbox api exposes checkcached endpoint for local torrent availability`() {
        val hasCheckCached = TorboxApi::class.java.methods.any {
            it.name.contains("checkcached", ignoreCase = true) ||
                it.name.contains("checkCached", ignoreCase = true)
        }

        assertTrue(hasCheckCached)
    }

    @Test
    fun `failure kinds separate authentication rate limit cached refusal stale and server failures`() {
        assertEquals(TorboxFailureKind.UNAUTHORIZED, torboxFailureKind(401, ""))
        assertEquals(TorboxFailureKind.UNAUTHORIZED, torboxFailureKind(200, "Invalid API key"))
        assertEquals(TorboxFailureKind.RATE_LIMITED, torboxFailureKind(429, ""))
        assertEquals(TorboxFailureKind.RATE_LIMITED, torboxFailureKind(400, "RATE_LIMIT_EXCEEDED"))
        assertEquals(TorboxFailureKind.NOT_CACHED, torboxFailureKind(400, "Torrent is not cached"))
        assertEquals(TorboxFailureKind.NOT_CACHED, torboxFailureKind(409, ""))
        assertEquals(TorboxFailureKind.STALE, torboxFailureKind(400, ""))
        assertEquals(TorboxFailureKind.STALE, torboxFailureKind(404, "File not found"))
        assertEquals(TorboxFailureKind.UNKNOWN, torboxFailureKind(503, ""))
    }

    @Test
    fun `cached only refusal is read from the error envelope whatever its status`() = runTest {
        val api = FakeTorboxApi(
            createResponse = Response.error(
                400,
                """{"success":false,"error":"TORRENT_NOT_CACHED","detail":"Torrent is not cached"}""".toResponseBody(JSON)
            )
        )

        val result = resolver(api).resolve(stream(fileIdx = 1), season = null, episode = null)

        assertTrue(result is DirectDebridResolveResult.NotCached)
        assertEquals(0, api.torrentCalls)
    }

    @Test
    fun `rate limit and rejected api key are errors and never stale links`() = runTest {
        val rateLimited = FakeTorboxApi(createResponse = Response.error(429, "{}".toResponseBody(JSON)))
        assertTrue(resolver(rateLimited).resolve(stream(fileIdx = 1), season = null, episode = null) is DirectDebridResolveResult.Error)

        val keyRejected = cachedApi(pmrFiles).apply {
            linkUrlFor = null
            linkResponse = Response.error(
                401,
                """{"success":false,"error":"UNAUTHORIZED","detail":"Invalid API key"}""".toResponseBody(JSON)
            )
        }
        assertTrue(resolver(keyRejected).resolve(pmrStream(), season = 1, episode = 2) is DirectDebridResolveResult.Error)
    }

    @Test
    fun `prepared link refresh re-resolves only its own episode once`() = runTest {
        val settings = MutableStateFlow(torboxSettings("tb_token"))
        val api = cachedApi(pmrFiles).apply { linkUrlFor = { fileId -> linkSuccess("file-$fileId-v1.mkv") } }
        val resolver = directResolver(api, settings)
        val episodeOne = resolver.resolve(episodeStream(1), season = 1, episode = 1) as DirectDebridResolveResult.Success
        val episodeTwo = resolver.resolve(episodeStream(2), season = 1, episode = 2) as DirectDebridResolveResult.Success
        assertEquals(2, api.createCalls)
        assertEquals(2, api.requestCalls)

        api.linkUrlFor = { fileId -> linkSuccess("file-$fileId-v2.mkv") }
        val refreshed = resolver.refreshPreparedTorboxUrl(episodeOne.url) as DirectDebridResolveResult.Success

        assertEquals("https://cdn.example/file-3-v2.mkv", refreshed.url)
        assertEquals(3, api.createCalls)
        assertEquals(3, api.requestCalls)
        assertEquals(3, api.lastFileId)
        // Episode two keeps its own link and needs no TorBox call to read it back.
        assertEquals(episodeTwo.url, resolver.cachedPlayableStream(episodeStream(2), 1, 2)?.url)
        assertEquals(3, api.createCalls)
    }

    @Test
    fun `refreshed link rejected again is not refreshed a second time`() = runTest {
        val settings = MutableStateFlow(torboxSettings("tb_token"))
        val api = cachedApi(pmrFiles).apply { linkUrlFor = { fileId -> linkSuccess("file-$fileId-v1.mkv") } }
        val resolver = directResolver(api, settings)
        val original = resolver.resolve(episodeStream(1), season = 1, episode = 1) as DirectDebridResolveResult.Success
        api.linkUrlFor = { fileId -> linkSuccess("file-$fileId-v2.mkv") }
        val refreshed = resolver.refreshPreparedTorboxUrl(original.url) as DirectDebridResolveResult.Success
        assertTrue(resolver.hasPreparedTorboxDeliveryUrl(refreshed.url))

        assertNull(resolver.refreshPreparedTorboxUrl(refreshed.url))
        assertEquals(3, api.createCalls)
        assertFalse(resolver.hasPreparedTorboxDeliveryUrl(refreshed.url))

        // The next playback of that file resolves afresh rather than replaying the rejected link.
        api.linkUrlFor = { fileId -> linkSuccess("file-$fileId-v3.mkv") }
        val afresh = resolver.resolve(episodeStream(1), season = 1, episode = 1) as DirectDebridResolveResult.Success
        assertEquals("https://cdn.example/file-3-v3.mkv", afresh.url)
        assertEquals(4, api.createCalls)
    }

    @Test
    fun `refresh returns null for unknown links and for another account`() = runTest {
        val settings = MutableStateFlow(torboxSettings("tb_token"))
        val api = cachedApi(pmrFiles).apply { linkUrlFor = { fileId -> linkSuccess("file-$fileId-v1.mkv") } }
        val resolver = directResolver(api, settings)
        val original = resolver.resolve(episodeStream(1), season = 1, episode = 1) as DirectDebridResolveResult.Success

        assertNull(resolver.refreshPreparedTorboxUrl("https://cdn.example/never-prepared.mkv"))
        assertEquals(1, api.createCalls)

        settings.value = torboxSettings("other_token")
        assertNull(resolver.refreshPreparedTorboxUrl(original.url))
        assertEquals(1, api.createCalls)
        assertEquals(1, api.requestCalls)
        // The other account's entry is left alone, not cleared as a side effect.
        assertTrue(resolver.hasPreparedTorboxDeliveryUrl(original.url))
    }

    @Test
    fun `authentication failure while refreshing is an error and drops only the rejected link`() = runTest {
        val settings = MutableStateFlow(torboxSettings("tb_token"))
        val api = cachedApi(pmrFiles).apply { linkUrlFor = { fileId -> linkSuccess("file-$fileId-v1.mkv") } }
        val resolver = directResolver(api, settings)
        val original = resolver.resolve(episodeStream(1), season = 1, episode = 1) as DirectDebridResolveResult.Success
        api.linkUrlFor = null
        api.linkResponse = Response.error(
            401,
            """{"success":false,"error":"UNAUTHORIZED","detail":"Invalid API key"}""".toResponseBody(JSON)
        )

        val result = resolver.refreshPreparedTorboxUrl(original.url)

        assertTrue(result is DirectDebridResolveResult.Error)
        assertEquals(2, api.createCalls)
        assertFalse(resolver.hasPreparedTorboxDeliveryUrl(original.url))
    }

    private fun torboxSettings(apiKey: String) = DebridSettings(enabled = true, torboxApiKey = apiKey)

    private fun episodeStream(episode: Int): Stream {
        val base = pmrStream()
        // Clear the filename so file selection matches the episode, not the single file named on the stream.
        return base.copy(clientResolve = base.clientResolve?.copy(filename = null, episode = episode))
    }

    private fun linkSuccess(name: String): Response<TorboxEnvelopeDto<String>> =
        Response.success(TorboxEnvelopeDto(success = true, data = "https://cdn.example/$name"))

    private fun directResolver(api: TorboxApi, settings: MutableStateFlow<DebridSettings>): DirectDebridResolver {
        val dataStore = mockk<DebridSettingsDataStore>()
        every { dataStore.settings } returns settings
        return DirectDebridResolver(
            dataStore = dataStore,
            torboxResolver = TorboxDirectDebridResolver(
                dataStore = dataStore,
                api = api,
                fileSelector = TorboxFileSelector()
            ),
            realDebridResolver = mockk(relaxed = true),
            premiumizeResolver = mockk(relaxed = true),
            localDebridService = mockk(relaxed = true)
        )
    }

    private val JSON = "application/json".toMediaType()
    private val pmrFilename = "Sentenced to Be a Hero - S01E02 (BD 1080p x265 Opus) [Dual Audio] [PMR].mkv"
    private val pmrFiles = listOf(
        TorboxTorrentFileDto(
            id = 3,
            name = "Sentenced to Be a Hero - S01E01 (BD 1080p x265 Opus) [Dual Audio] [PMR].mkv",
            size = 12_359_794_201L,
        ),
        TorboxTorrentFileDto(id = 4, name = pmrFilename, size = 4_817_912_734L),
    )

    private fun pmrStream(): Stream {
        val stream = stream(fileIdx = 1)
        return stream.copy(
            clientResolve = stream.clientResolve?.copy(
                infoHash = "439733a4816b34543c33f010c98b098aa916dca2",
                magnetUri = "magnet:?xt=urn:btih:439733a4816b34543c33f010c98b098aa916dca2",
                torrentName = "[PMR] Sentenced to Be a Hero Season 1\n$pmrFilename\nDubbed / Dual Audio",
                filename = pmrFilename,
                mediaType = "series",
                season = 1,
                episode = 2,
            )
        )
    }

    private fun cachedApi(files: List<TorboxTorrentFileDto>) = FakeTorboxApi(
        createResponse = Response.success(
            TorboxEnvelopeDto(success = true, data = TorboxCreateTorrentDataDto(torrentId = 44))
        ),
        torrentResponse = Response.success(
            TorboxEnvelopeDto(success = true, data = TorboxTorrentDataDto(id = 44, files = files))
        ),
        linkResponse = Response.success(
            TorboxEnvelopeDto(success = true, data = "https://cdn.example/episode-two.mkv")
        ),
    )

    private fun resolver(api: TorboxApi): TorboxDirectDebridResolver {
        val dataStore = mockk<DebridSettingsDataStore>()
        every { dataStore.settings } returns flowOf(
            DebridSettings(
                enabled = true,
                torboxApiKey = "tb_token"
            )
        )
        return TorboxDirectDebridResolver(
            dataStore = dataStore,
            api = api,
            fileSelector = TorboxFileSelector()
        )
    }

    private fun stream(fileIdx: Int?): Stream = Stream(
        name = "Direct Debrid",
        title = "Title",
        description = "Description",
        url = null,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = DebridProviders.instantName(DebridProviders.TORBOX_ID),
        addonLogo = null,
        clientResolve = StreamClientResolve(
            type = "debrid",
            infoHash = "abcdef",
            fileIdx = fileIdx,
            magnetUri = "magnet:?xt=urn:btih:abcdef",
            sources = null,
            torrentName = "Torrent",
            filename = "right.mkv",
            mediaType = "movie",
            mediaId = "tt1",
            mediaOnlyId = "tt1",
            title = "Title",
            season = null,
            episode = null,
            service = "torbox",
            serviceIndex = 0,
            serviceExtension = null,
            isCached = true
        )
    )

    private class FakeTorboxApi(
        private val createResponse: Response<TorboxEnvelopeDto<TorboxCreateTorrentDataDto>>,
        private val torrentResponse: Response<TorboxEnvelopeDto<TorboxTorrentDataDto>> = Response.success(
            TorboxEnvelopeDto(success = true, data = TorboxTorrentDataDto(files = emptyList()))
        ),
        var linkResponse: Response<TorboxEnvelopeDto<String>> = Response.success(
            TorboxEnvelopeDto(success = true, data = "")
        )
    ) : TorboxApi {
        var createCalls = 0
        var torrentCalls = 0
        var requestCalls = 0
        var lastAddOnlyIfCached: Boolean? = null
        var lastAllowZip: Boolean? = null
        var lastFileId: Int? = null
        /** When set, answers each link request by file id instead of [linkResponse]. */
        var linkUrlFor: ((Int?) -> Response<TorboxEnvelopeDto<String>>)? = null

        override suspend fun getUser(authorization: String): Response<ResponseBody> {
            return Response.success("{}".toResponseBody("application/json".toMediaType()))
        }

        override suspend fun startDeviceAuthorization(
            appName: String
        ): Response<TorboxEnvelopeDto<TorboxDeviceAuthorizationDto>> {
            return Response.success(TorboxEnvelopeDto(success = true, data = TorboxDeviceAuthorizationDto()))
        }

        override suspend fun redeemDeviceAuthorization(
            body: TorboxDeviceTokenRequestDto
        ): Response<TorboxEnvelopeDto<TorboxDeviceTokenDto>> {
            return Response.success(TorboxEnvelopeDto(success = true, data = TorboxDeviceTokenDto()))
        }

        override suspend fun checkCached(
            authorization: String,
            format: String,
            body: TorboxCheckCachedRequestDto
        ): Response<TorboxEnvelopeDto<Map<String, TorboxCachedItemDto>>> {
            return Response.success(TorboxEnvelopeDto(success = true, data = emptyMap()))
        }

        override suspend fun createTorrent(
            authorization: String,
            magnet: RequestBody,
            addOnlyIfCached: RequestBody,
            allowZip: RequestBody
        ): Response<TorboxEnvelopeDto<TorboxCreateTorrentDataDto>> {
            createCalls++
            lastAddOnlyIfCached = addOnlyIfCached.readUtf8().toBooleanStrictOrNull()
            lastAllowZip = allowZip.readUtf8().toBooleanStrictOrNull()
            return createResponse
        }

        override suspend fun getTorrent(
            authorization: String,
            id: Int,
            bypassCache: Boolean
        ): Response<TorboxEnvelopeDto<TorboxTorrentDataDto>> {
            torrentCalls++
            return torrentResponse
        }

        override suspend fun requestDownloadLink(
            authorization: String,
            token: String,
            torrentId: Int,
            fileId: Int?,
            zipLink: Boolean,
            redirect: Boolean,
            appendName: Boolean
        ): Response<TorboxEnvelopeDto<String>> {
            requestCalls++
            lastFileId = fileId
            return linkUrlFor?.invoke(fileId) ?: linkResponse
        }

        override suspend fun listCloudTorrents(
            authorization: String
        ): Response<TorboxEnvelopeDto<List<TorboxCloudItemDto>>> {
            return Response.success(TorboxEnvelopeDto(success = true, data = emptyList()))
        }

        override suspend fun listCloudUsenet(
            authorization: String
        ): Response<TorboxEnvelopeDto<List<TorboxCloudItemDto>>> {
            return Response.success(TorboxEnvelopeDto(success = true, data = emptyList()))
        }

        override suspend fun listCloudWebDownloads(
            authorization: String
        ): Response<TorboxEnvelopeDto<List<TorboxCloudItemDto>>> {
            return Response.success(TorboxEnvelopeDto(success = true, data = emptyList()))
        }

        override suspend fun requestCloudTorrentDownloadLink(
            authorization: String,
            token: String,
            torrentId: String,
            fileId: String?,
            zipLink: Boolean,
            redirect: Boolean,
            appendName: Boolean
        ): Response<TorboxEnvelopeDto<String>> {
            return Response.success(TorboxEnvelopeDto(success = true, data = ""))
        }

        override suspend fun requestCloudUsenetDownloadLink(
            authorization: String,
            token: String,
            usenetId: String,
            fileId: String?,
            zipLink: Boolean,
            redirect: Boolean,
            appendName: Boolean
        ): Response<TorboxEnvelopeDto<String>> {
            return Response.success(TorboxEnvelopeDto(success = true, data = ""))
        }

        override suspend fun requestCloudWebDownloadLink(
            authorization: String,
            token: String,
            webId: String,
            fileId: String?,
            zipLink: Boolean,
            redirect: Boolean,
            appendName: Boolean
        ): Response<TorboxEnvelopeDto<String>> {
            return Response.success(TorboxEnvelopeDto(success = true, data = ""))
        }

        private fun RequestBody.readUtf8(): String {
            val buffer = okio.Buffer()
            writeTo(buffer)
            return buffer.readUtf8()
        }
    }
}
