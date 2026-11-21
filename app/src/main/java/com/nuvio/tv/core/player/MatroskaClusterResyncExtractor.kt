package com.nuvio.tv.core.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.IOException

/** Resynchronizes a post-seek Matroska read to the next Cluster element. */
@UnstableApi
internal class MatroskaClusterResyncExtractor(private val delegate: Extractor) : Extractor {
    private var hasSeekMap = false
    private var scanAfterSeek = false

    override fun init(output: ExtractorOutput) {
        delegate.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = output.track(id, type)
            override fun endTracks() = output.endTracks()
            override fun seekMap(seekMap: SeekMap) {
                hasSeekMap = true
                output.seekMap(seekMap)
            }
        })
    }

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (scanAfterSeek) {
            scanAfterSeek = false
            resync(input)
        }
        return delegate.read(input, seekPosition)
    }

    override fun seek(position: Long, timeUs: Long) {
        scanAfterSeek = position > 0 && hasSeekMap
        delegate.seek(position, timeUs)
    }

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation

    @Throws(IOException::class)
    private fun resync(input: ExtractorInput) {
        val start = input.position
        val limit = minOf(MAX_SCAN_BYTES.toLong(), input.length - start)
        if (limit <= 0) return
        val buffer = ByteArray(limit.toInt())
        val available = input.peekFully(buffer, 0, buffer.size, true)
        input.resetPeekPosition()
        if (!available) return
        for (offset in 0..buffer.size - CLUSTER_ID.size) {
            if (CLUSTER_ID.indices.all { buffer[offset + it] == CLUSTER_ID[it] }) {
                if (offset > 0) input.skipFully(offset)
                return
            }
        }
    }

    private companion object {
        const val MAX_SCAN_BYTES = 1024 * 1024
        val CLUSTER_ID = byteArrayOf(0x1f, 0x43, 0xb6.toByte(), 0x75)
    }
}
