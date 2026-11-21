package com.nuvio.tv.core.streams

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import com.nuvio.tv.core.player.FrameRateUtils
import com.nuvio.tv.ui.screens.player.ParallelRangeDataSource
import com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking
import com.nuvio.tv.ui.screens.player.PrefetchWindowStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request

@Singleton
class PlaybackLinkWarmup @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun warm(url: String, headers: Map<String, String>, filename: String?, mimeType: String?, frameRateMatchingEnabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return@withContext false
        val lower = url.lowercase()
        if (listOf(".m3u8", ".mpd", "127.0.0.1", "localhost", "magnet:").any(lower::contains)) return@withContext false
        val client = PlayerPlaybackNetworking.playbackHttpClient
        suspend fun checkActive() = currentCoroutineContext().ensureActive()
        checkActive()
        val headRequest = Request.Builder().url(url).header("Range", "bytes=0-262143").apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        val call = client.newCall(headRequest)
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        val cancel = job?.invokeOnCompletion { if (job.isCancelled) call.cancel() }
        val entry: PlaybackWarmupEntry
        val finalUrl: String
        try {
            call.execute().use { response ->
                if (response.code != 206) return@withContext false
                val body = response.body ?: return@withContext false
                val bytes = body.bytes()
                val total = response.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L
                finalUrl = response.request.url.toString()
                if (total < 0 || bytes.isEmpty()) return@withContext false
                entry = PlaybackWarmupEntry(finalUrl, total, System.currentTimeMillis())
                PlaybackWarmupStore.put(url, headers, entry)
                val uri = Uri.parse(finalUrl)
                PrefetchWindowStore.putHead(ParallelRangeDataSource.BootstrapCacheEntry(uri, 0L, uri, bytes.size.toLong(), total, bytes, bytes.size, android.os.SystemClock.uptimeMillis()))
                val isMkv = bytes.take(4).toByteArray().contentEquals(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())) || mimeType?.contains("matroska", true) == true || filename?.endsWith(".mkv", true) == true
                if (isMkv && total > 16L * 1024 * 1024) {
                    checkActive()
                    val start = (total - PrefetchWindowStore.TAIL_WINDOW_BYTES).coerceAtLeast(0)
                    val tailReq = Request.Builder().url(finalUrl).header("Range", "bytes=$start-${total - 1}").apply { headers.forEach { (k,v) -> header(k,v) } }.build()
                    val tailCall = client.newCall(tailReq)
                    val tailCancel = job?.invokeOnCompletion { if (job.isCancelled) tailCall.cancel() }
                    try { tailCall.execute().use { tail -> if (tail.code == 206) { val tailBytes = tail.body?.bytes(); if (tailBytes != null && tailBytes.isNotEmpty()) PrefetchWindowStore.putTail(ParallelRangeDataSource.BootstrapCacheEntry(uri, start, uri, tailBytes.size.toLong(), total, tailBytes, tailBytes.size, android.os.SystemClock.uptimeMillis())) } } } finally { tailCancel?.dispose() }
                    if (frameRateMatchingEnabled) {
                        checkActive()
                        val detection = FrameRateUtils.detectFrameRateWithOkHttpProbe(context, finalUrl, headers, mimeType, filename) { job?.isCancelled == true }
                        if (detection != null) FrameRateUtils.cacheFrameRate(finalUrl, headers, detection, filename)
                    }
                }
                return@withContext true
            }
        } finally { cancel?.dispose() }
    }
}
