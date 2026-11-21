package com.nuvio.tv.core.streams

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackLinkWarmupTest {
    @After fun reset() { PlaybackWarmupStore.clear(); PlaybackWarmupStore.nowMs = System::currentTimeMillis }

    @Test fun expiredEntryReturnsOriginalUrl() {
        var now = 1_000L
        PlaybackWarmupStore.nowMs = { now }
        PlaybackWarmupStore.put("https://source/video", emptyMap(), PlaybackWarmupEntry("https://cdn/video", 42, now))
        assertEquals("https://cdn/video", PlaybackWarmupStore.effectiveUrl("https://source/video", emptyMap()))
        now += 10 * 60 * 1000L
        assertEquals("https://source/video", PlaybackWarmupStore.effectiveUrl("https://source/video", emptyMap()))
    }

    @Test fun headersHaveIndependentEntries() {
        PlaybackWarmupStore.put("https://source/video", mapOf("Authorization" to "one"), PlaybackWarmupEntry("https://cdn/one", 42, System.currentTimeMillis()))
        assertEquals("https://cdn/one", PlaybackWarmupStore.effectiveUrl("https://source/video", mapOf("Authorization" to "one")))
        assertEquals("https://source/video", PlaybackWarmupStore.effectiveUrl("https://source/video", mapOf("Authorization" to "two")))
    }
}
