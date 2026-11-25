package com.phy4l.tcfy.asr

import kotlinx.serialization.Serializable

/**
 * Language hint for SenseVoice / multilingual offline ASR.
 */
@Serializable
enum class AsrLanguageMode(val code: String, val displayName: String) {
    AUTO("auto", "中英自动混杂 (推荐)"),
    ZH("zh", "中文为主"),
    EN("en", "英文为主"),
    JA("ja", "日语"),
    KO("ko", "韩语"),
    YUE("yue", "粤语")
}

data class AsrRecognitionResult(
    val text: String,
    val detectedLanguage: String = "",
    val inferenceTimeMs: Long = 0L
)

/**
 * Abstract offline ASR engine interface so SenseVoice, Whisper, or Paraformer can be swapped cleanly.
 */
interface OfflineAsrEngine : AutoCloseable {
    val engineName: String
    val isReady: Boolean
    fun transcribe(samples: FloatArray, sampleRate: Int = 16000): AsrRecognitionResult
}

data class VadSpeechChunk(
    val startSampleIndex: Long,
    val samples: FloatArray
)

/**
 * Abstract Voice Activity Detection segmenter for far-field speech chunking.
 */
interface VadSegmenter : AutoCloseable {
    fun acceptSamples(samples: FloatArray)
    fun isSpeechDetected(): Boolean
    fun pollSegment(): VadSpeechChunk?
    fun flush(): List<VadSpeechChunk>
    fun reset()
}
