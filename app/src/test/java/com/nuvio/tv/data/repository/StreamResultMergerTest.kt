package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamResultMergerTest {
    private fun stream(hash: String, file: Int = 0, description: String = "checking") = Stream(
        name = "source", title = null, description = description, url = null,
        ytId = null, infoHash = hash, fileIdx = file, externalUrl = null,
        behaviorHints = null, addonName = "source", addonLogo = null
    )

    private fun group(vararg streams: Stream) = AddonStreams("source", null, streams.toList())

    @Test
    fun `waiting availability batches retain earlier torrents and distinct files`() {
        var pending: AddonStreams? = null
        pending = StreamResultMerger.merge(pending, group(stream("a")))
        pending = StreamResultMerger.merge(pending, group(stream("b")))
        pending = StreamResultMerger.merge(pending, group(stream("a", file = 1)))
        assertEquals(listOf(stream("a"), stream("b"), stream("a", file = 1)), pending.streams)
    }

    @Test
    fun `later availability replaces duplicate without dropping other streams`() {
        val checked = stream("ABC", description = "cached")
        val merged = StreamResultMerger.merge(group(stream("abc"), stream("other")), group(checked))
        assertEquals(listOf(checked, stream("other")), merged.streams)
    }
}
