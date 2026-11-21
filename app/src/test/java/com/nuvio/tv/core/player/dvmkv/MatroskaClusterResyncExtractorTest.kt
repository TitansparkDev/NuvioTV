package com.nuvio.tv.core.player.dvmkv

import androidx.media3.common.DataReader
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import com.nuvio.tv.core.player.MatroskaClusterResyncExtractor
import org.junit.Assert.assertEquals
import org.junit.Test

class MatroskaClusterResyncExtractorTest {
    @Test fun `seek skips bytes before next cluster`() = assertSkip(1_000, 1_000)
    @Test fun `seek on cluster skips nothing`() = assertSkip(0, 0)
    @Test fun `no cluster in scan window skips nothing`() = assertSkip(-1, 0)
    @Test fun `position zero is not scanned`() = assertSkip(1_000, 0, seekPosition = 0)
    @Test fun `seek before seek map is not scanned`() = assertSkip(1_000, 0, publishMap = false)

    private fun assertSkip(clusterOffset: Int, expectedPosition: Int, seekPosition: Long = 1, publishMap: Boolean = true) {
        val bytes = ByteArray(1_100)
        if (clusterOffset >= 0) {
            val offset = clusterOffset.coerceAtMost(bytes.size - 4)
            bytes[offset] = 0x1f
            bytes[offset + 1] = 0x43
            bytes[offset + 2] = 0xb6.toByte()
            bytes[offset + 3] = 0x75
        }
        val fake = FakeExtractor()
        val extractor = MatroskaClusterResyncExtractor(fake)
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = throw UnsupportedOperationException()
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = Unit
        })
        if (publishMap) fake.output!!.seekMap(EmptySeekMap)
        extractor.seek(seekPosition, 0)
        val input = DefaultExtractorInput(DataReader { buffer, offset, length ->
            if (readPosition >= bytes.size) -1 else {
                val count = minOf(length, bytes.size - readPosition)
                bytes.copyInto(buffer, offset, readPosition, readPosition + count)
                readPosition += count
                count
            }
        }, 0, bytes.size.toLong())
        extractor.read(input, PositionHolder())
        assertEquals(expectedPosition.toLong(), input.position)
    }

    private var readPosition = 0

    private class FakeExtractor : Extractor {
        var output: ExtractorOutput? = null
        override fun init(output: ExtractorOutput) { this.output = output }
        override fun sniff(input: ExtractorInput) = true
        override fun read(input: ExtractorInput, seekPosition: PositionHolder) = RESULT_CONTINUE
        override fun seek(position: Long, timeUs: Long) = Unit
        override fun release() = Unit
    }

    private object EmptySeekMap : SeekMap {
        override fun isSeekable() = true
        override fun getDurationUs() = 0L
        override fun getSeekPoints(timeUs: Long) = SeekMap.SeekPoints(SeekMap.SeekPoint(0, 0))
    }
}
