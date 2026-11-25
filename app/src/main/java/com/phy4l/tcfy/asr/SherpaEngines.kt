package com.phy4l.tcfy.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.phy4l.tcfy.audio.SpeechEnhancer
import java.io.File

/**
 * SenseVoice-Small int8 offline ASR engine via sherpa-onnx.
 *
 * Tuned for Redmi Note 11 5G (Dimensity 810: 2x Cortex-A76 + 6x Cortex-A55):
 * default 4 CPU threads delivers ~0.15-0.22 RTF with native ZH/EN code-switching and ITN punctuation.
 */
class SherpaSenseVoiceEngine(
    modelFile: File,
    tokensFile: File,
    languageMode: AsrLanguageMode = AsrLanguageMode.AUTO,
    numThreads: Int = 4
) : OfflineAsrEngine {

    override val engineName: String = "SenseVoice-Small int8"

    @Volatile
    private var recognizer: OfflineRecognizer?

    override val isReady: Boolean
        get() = recognizer != null

    init {
        require(modelFile.exists() && tokensFile.exists()) {
            "SenseVoice 模型文件不存在: ${modelFile.absolutePath}"
        }
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = modelFile.absolutePath,
                    language = languageMode.code,
                    useInverseTextNormalization = true
                ),
                tokens = tokensFile.absolutePath,
                numThreads = numThreads.coerceIn(1, 6),
                provider = "cpu",
                debug = false
            )
        )
        recognizer = OfflineRecognizer(assetManager = null, config = config)
    }

    @Synchronized
    override fun transcribe(samples: FloatArray, sampleRate: Int): AsrRecognitionResult {
        val engine = recognizer ?: return AsrRecognitionResult("")
        if (samples.isEmpty()) return AsrRecognitionResult("")
        val t0 = System.nanoTime()
        val stream = engine.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate)
            engine.decode(stream)
            val result = engine.getResult(stream)
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000L
            val cleanedText = cleanSenseVoiceTags(result.text)
            val lang = result.lang.trim().removePrefix("<|").removeSuffix("|>")
            AsrRecognitionResult(
                text = cleanedText,
                detectedLanguage = lang,
                inferenceTimeMs = elapsedMs
            )
        } finally {
            stream.release()
        }
    }

    @Synchronized
    override fun close() {
        recognizer?.release()
        recognizer = null
    }

    companion object {
        private val SENSEVOICE_TAG_REGEX = Regex("<\\|[^|]+\\|>")
        private val PUNCT_AND_SPACE_REGEX = Regex("[\\p{P}\\p{S}\\s]+")

        fun cleanSenseVoiceTags(raw: String): String {
            return raw.replace(SENSEVOICE_TAG_REGEX, "")
                .replace(Regex("\\s{2,}"), " ")
                .trim()
        }

        /**
         * Rejects pure-punctuation hallucinations (e.g. ".", "?") and stray 1-char Hangul/Kana
         * noise hallucinations that SenseVoice sometimes emits in noisy cafeterias under auto mode.
         */
        fun isMeaningfulSpeech(
            text: String,
            detectedLang: String = "",
            requestedMode: AsrLanguageMode = AsrLanguageMode.AUTO
        ): Boolean {
            val contentOnly = text.replace(PUNCT_AND_SPACE_REGEX, "")
            if (contentOnly.isEmpty()) return false
            // Drop single-character non-ZH/EN noise artifacts (e.g. Korean "그." or single "I.")
            if (requestedMode == AsrLanguageMode.AUTO && contentOnly.length == 1) {
                val ch = contentOnly[0]
                val isHangul = ch.code in 0xAC00..0xD7AF || ch.code in 0x1100..0x11FF
                val isKana = ch.code in 0x3040..0x30FF
                if (isHangul || isKana || detectedLang.equals("ko", ignoreCase = true) || detectedLang.equals("ja", ignoreCase = true)) {
                    return false
                }
            }
            return true
        }
    }
}

/**
 * Silero VAD segmenter via sherpa-onnx, configured with far-field sensitivity and speech-tail safety.
 */
class SherpaSileroVadEngine(
    sileroModelFile: File,
    threshold: Float = 0.35f,
    minSilenceDurationSec: Float = 0.65f,
    minSpeechDurationSec: Float = 0.25f,
    maxSpeechDurationSec: Float = 14.0f,
    sampleRate: Int = 16000
) : VadSegmenter {

    @Volatile
    private var vad: Vad?

    init {
        require(sileroModelFile.exists()) { "Silero VAD 模型不存在: ${sileroModelFile.absolutePath}" }
        val config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = sileroModelFile.absolutePath,
                threshold = threshold.coerceIn(0.15f, 0.85f),
                minSilenceDuration = minSilenceDurationSec.coerceIn(0.2f, 2.5f),
                minSpeechDuration = minSpeechDurationSec.coerceIn(0.1f, 1.5f),
                windowSize = 512,
                maxSpeechDuration = maxSpeechDurationSec.coerceIn(5.0f, 30.0f)
            ),
            sampleRate = sampleRate,
            numThreads = 1,
            provider = "cpu",
            debug = false
        )
        vad = Vad(assetManager = null, config = config)
    }

    @Synchronized
    override fun acceptSamples(samples: FloatArray) {
        vad?.acceptWaveform(samples)
    }

    @Synchronized
    override fun isSpeechDetected(): Boolean {
        return vad?.isSpeechDetected() == true
    }

    @Synchronized
    override fun pollSegment(): VadSpeechChunk? {
        val engine = vad ?: return null
        if (engine.empty()) return null
        val segment = engine.front()
        engine.pop()
        return VadSpeechChunk(
            startSampleIndex = segment.start,
            samples = segment.samples
        )
    }

    @Synchronized
    override fun flush(): List<VadSpeechChunk> {
        val engine = vad ?: return emptyList()
        engine.flush()
        val out = mutableListOf<VadSpeechChunk>()
        while (!engine.empty()) {
            val seg = engine.front()
            engine.pop()
            out.add(VadSpeechChunk(seg.start, seg.samples))
        }
        return out
    }

    @Synchronized
    override fun reset() {
        vad?.reset()
    }

    @Synchronized
    override fun close() {
        vad?.release()
        vad = null
    }
}

/**
 * Streaming GTCRN neural speech enhancement via sherpa-onnx (~500KB model, <5% CPU on Dimensity 810).
 */
class SherpaGtcrnEnhancer(
    gtcrnModelFile: File
) : SpeechEnhancer {

    @Volatile
    private var denoiser: OnlineSpeechDenoiser?

    init {
        require(gtcrnModelFile.exists()) { "GTCRN 模型不存在: ${gtcrnModelFile.absolutePath}" }
        val config = OnlineSpeechDenoiserConfig(
            model = OfflineSpeechDenoiserModelConfig(
                gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(
                    model = gtcrnModelFile.absolutePath
                ),
                numThreads = 1,
                provider = "cpu",
                debug = false
            )
        )
        denoiser = OnlineSpeechDenoiser(assetManager = null, config = config)
    }

    @Synchronized
    override fun enhance(samples: FloatArray, sampleRate: Int): FloatArray {
        val engine = denoiser ?: return samples
        if (samples.isEmpty()) return samples
        return engine.run(samples, sampleRate).samples
    }

    @Synchronized
    override fun reset() {
        denoiser?.reset()
    }

    @Synchronized
    override fun close() {
        denoiser?.release()
        denoiser = null
    }
}
