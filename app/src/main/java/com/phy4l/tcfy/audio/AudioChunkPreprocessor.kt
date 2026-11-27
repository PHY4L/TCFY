package com.phy4l.tcfy.audio

import com.phy4l.tcfy.asr.VadSpeechChunk
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure JVM pre/post-processing utilities for VAD speech chunks right before/after offline ASR:
 *
 * 1. Segment-level loudness normalization + 120ms silence padding so soft voices (<-50 dBFS)
 *    reach SenseVoice/Paraformer/Whisper at optimal loudness without losing leading/trailing syllables.
 * 2. Forced-split overlap stitching for fast speakers who talk continuously past VAD maxSpeechSec,
 *    preventing mid-word hard chops.
 * 3. Boundary text deduplication when overlap stitching repeats 1-4 characters/words at the seam.
 */
object AudioChunkPreprocessor {

    /**
     * Normalizes a speech chunk's RMS toward [targetRmsDbfs] (capped by [maxBoostDb] and peak soft-limiting)
     * and adds [padMs] of silence at both ends to prevent CTC/Attention edge truncation.
     */
    fun prepareForAsr(
        samples: FloatArray,
        sampleRate: Int = 16000,
        targetRmsDbfs: Float = -18f,
        maxBoostDb: Float = 32f,
        padMs: Int = 120
    ): FloatArray {
        if (samples.isEmpty()) return samples

        val rms = FarFieldDspPipeline.computeRms(samples)
        val rmsDbfs = FarFieldDspPipeline.linearToDbfs(rms)

        val gain = if (rmsDbfs in -78f..-20f) {
            val targetLin = FarFieldDspPipeline.dbToLinear(targetRmsDbfs)
            val maxLin = FarFieldDspPipeline.dbToLinear(maxBoostDb)
            (targetLin / max(rms, 1e-6f)).coerceIn(1.0f, maxLin)
        } else if (rmsDbfs > -10f) {
            // Gently tame overly hot PA chunks that might clip ASR filterbanks
            val targetLin = FarFieldDspPipeline.dbToLinear(-15f)
            (targetLin / max(rms, 1e-6f)).coerceIn(0.35f, 1.0f)
        } else {
            1.0f
        }

        val padSamples = ((sampleRate * padMs) / 1000).coerceAtLeast(0)
        val out = FloatArray(samples.size + padSamples * 2)
        if (abs(gain - 1.0f) < 0.02f) {
            for (i in samples.indices) {
                out[padSamples + i] = FarFieldDspPipeline.softLimit(samples[i])
            }
        } else {
            for (i in samples.indices) {
                out[padSamples + i] = FarFieldDspPipeline.softLimit(samples[i] * gain)
            }
        }
        return out
    }

    /**
     * Removes short duplicated boundary tokens/characters at the start of [currentText]
     * that already appeared at the end of [previousText] due to forced-split audio overlap.
     */
    fun deduplicateBoundaryOverlap(previousText: String, currentText: String): String {
        val prev = previousText.trim()
        val curr = currentText.trim()
        if (prev.isEmpty() || curr.isEmpty()) return curr

        // Strip trailing punctuation from previous for matching only
        val prevClean = prev.trimEnd('。', '，', ',', '.', '!', '！', '?', '？', ';', '；', ' ')
        if (prevClean.isEmpty()) return curr

        // Check CJK/character suffix-prefix overlap (2..6 chars, avoiding 1-char false trims)
        val maxCharOverlap = min(6, min(prevClean.length, curr.length))
        for (len in maxCharOverlap downTo 2) {
            val suffix = prevClean.substring(prevClean.length - len)
            if (curr.startsWith(suffix, ignoreCase = true)) {
                val remainder = curr.substring(len).trimStart(' ', ',', '，', '、')
                if (remainder.isNotEmpty()) return remainder
            }
        }

        // Check English word-level overlap (1..3 words)
        val prevWords = prevClean.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val currWords = curr.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val maxWordOverlap = min(3, min(prevWords.size, currWords.size))
        for (w in maxWordOverlap downTo 1) {
            val prevSlice = prevWords.takeLast(w).map { it.lowercase().trim(',', '.', ';', '!', '?') }
            val currSlice = currWords.take(w).map { it.lowercase().trim(',', '.', ';', '!', '?') }
            if (prevSlice == currSlice && currWords.size > w) {
                return currWords.drop(w).joinToString(" ")
            }
        }
        return curr
    }
}

/**
 * Stateful overlap stitcher for consecutive VAD segments.
 *
 * When a fast speaker speaks continuously without pausing and hits [maxSpeechDurationSec],
 * VAD forces a hard split mid-sentence. Retaining [overlapMs] from the tail of that forced chunk
 * and prepending it to the next contiguous chunk avoids cutting words in half.
 */
class SpeechChunkOverlapBuffer(
    private val sampleRate: Int = 16000,
    private val overlapMs: Int = 450,
    private val maxContiguousGapMs: Int = 300
) {
    private var pendingTail: FloatArray? = null
    private var pendingEndSampleIndex: Long = -1L

    data class StitchedChunk(
        val chunk: VadSpeechChunk,
        val wasForcedSplit: Boolean,
        val hasPrependedOverlap: Boolean
    )

    fun reset() {
        pendingTail = null
        pendingEndSampleIndex = -1L
    }

    fun process(chunk: VadSpeechChunk, maxSpeechDurationSec: Float): StitchedChunk {
        val overlapSamples = (sampleRate * overlapMs) / 1000
        val maxGapSamples = (sampleRate * maxContiguousGapMs) / 1000L

        val tail = pendingTail
        var hasPrepended = false
        val effectiveChunk = if (
            tail != null &&
            pendingEndSampleIndex >= 0L &&
            chunk.startSampleIndex >= pendingEndSampleIndex - sampleRate / 10 &&
            chunk.startSampleIndex - pendingEndSampleIndex <= maxGapSamples
        ) {
            hasPrepended = true
            val combined = FloatArray(tail.size + chunk.samples.size)
            tail.copyInto(combined, 0)
            chunk.samples.copyInto(combined, tail.size)
            VadSpeechChunk(
                startSampleIndex = (chunk.startSampleIndex - tail.size).coerceAtLeast(0L),
                samples = combined
            )
        } else {
            chunk
        }

        val chunkDurSec = chunk.samples.size.toFloat() / sampleRate
        val forcedSplit = chunkDurSec >= (maxSpeechDurationSec - 0.6f).coerceAtLeast(4.0f)
        if (forcedSplit && chunk.samples.size > overlapSamples) {
            pendingTail = chunk.samples.copyOfRange(chunk.samples.size - overlapSamples, chunk.samples.size)
            pendingEndSampleIndex = chunk.startSampleIndex + chunk.samples.size
        } else {
            pendingTail = null
            pendingEndSampleIndex = -1L
        }

        return StitchedChunk(
            chunk = effectiveChunk,
            wasForcedSplit = forcedSplit,
            hasPrependedOverlap = hasPrepended
        )
    }
}
