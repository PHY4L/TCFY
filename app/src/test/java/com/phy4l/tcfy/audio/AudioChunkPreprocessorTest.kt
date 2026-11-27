package com.phy4l.tcfy.audio

import com.phy4l.tcfy.asr.VadSpeechChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioChunkPreprocessorTest {

    @Test
    fun prepareForAsrNormalizesUltraQuietChunkAndAddsSilencePadding() {
        // 1 second of -56 dBFS speech
        val quiet = FloatArray(16000) { i -> 0.0022f * sin(2.0 * PI * 750.0 * i / 16000.0).toFloat() }
        val inputDb = FarFieldDspPipeline.computeRmsDbfs(quiet)
        assertTrue(inputDb < -50f)

        val prepared = AudioChunkPreprocessor.prepareForAsr(quiet, sampleRate = 16000, padMs = 120)
        // 120ms padding on both ends = 1920 * 2 = 3840 extra samples
        assertEquals(16000 + 3840, prepared.size)
        // Padding should be silent
        assertEquals(0f, prepared[0], 1e-6f)
        assertEquals(0f, prepared.last(), 1e-6f)

        // Speech core should be lifted into optimal ASR loudness range (>-30 dBFS) without clipping
        val core = prepared.copyOfRange(1920, 1920 + 16000)
        val outDb = FarFieldDspPipeline.computeRmsDbfs(core)
        assertTrue("Expected quiet chunk lifted > -30 dBFS, got $outDb", outDb > -30f)
        assertTrue(core.all { abs(it) <= 1.0f })
    }

    @Test
    fun overlapBufferStitchesForcedSplitForFastSpeakersAndDeduplicatesSeam() {
        val buf = SpeechChunkOverlapBuffer(sampleRate = 16000, overlapMs = 450)

        // Chunk 1 hits maxSpeechDurationSec (9.5s) -> forced split!
        val chunk1 = VadSpeechChunk(startSampleIndex = 0L, samples = FloatArray(16000 * 9 + 4000) { 0.1f })
        val res1 = buf.process(chunk1, maxSpeechDurationSec = 9.5f)
        assertTrue(res1.wasForcedSplit)
        assertFalse(res1.hasPrependedOverlap)

        // Chunk 2 follows immediately (gap < 300ms) -> should prepend 450ms (7200 samples)
        val chunk2 = VadSpeechChunk(
            startSampleIndex = chunk1.samples.size.toLong() + 800L,
            samples = FloatArray(16000 * 3) { 0.2f }
        )
        val res2 = buf.process(chunk2, maxSpeechDurationSec = 9.5f)
        assertTrue(res2.hasPrependedOverlap)
        assertEquals(7200 + 16000 * 3, res2.chunk.samples.size)

        // Boundary text deduplication removes duplicated seam words/chars
        assertEquals(
            "的线性变换性质非常关键",
            AudioChunkPreprocessor.deduplicateBoundaryOverlap(
                "下面我们来看特征向量",
                "特征向量的线性变换性质非常关键"
            )
        )
        assertEquals(
            "converges quadratically near the root",
            AudioChunkPreprocessor.deduplicateBoundaryOverlap(
                "Notice that Newton method",
                "Newton method converges quadratically near the root"
            )
        )
    }
}
