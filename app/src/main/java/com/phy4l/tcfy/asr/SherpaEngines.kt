package com.phy4l.tcfy.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OfflineWenetCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.phy4l.tcfy.audio.SpeechEnhancer
import java.io.File

/**
 * Unified factory creating the right sherpa-onnx OfflineAsrEngine for any supported ASR model
 * (SenseVoice multilingual, Paraformer-ZH, Whisper English, WenetSpeech-Yue Cantonese).
 */
object SherpaOfflineAsrFactory {

    fun create(
        descriptor: ModelDescriptor,
        modelDir: File,
        languageMode: AsrLanguageMode = AsrLanguageMode.AUTO,
        numThreads: Int = 4
    ): OfflineAsrEngine {
        val primary = File(modelDir, descriptor.primaryFileName)
        val tokens = File(modelDir, descriptor.tokensFileName)
        return when (descriptor.type) {
            ModelType.ASR_SENSEVOICE -> SherpaSenseVoiceEngine(
                modelFile = primary,
                tokensFile = tokens,
                languageMode = languageMode,
                numThreads = numThreads
            )
            ModelType.ASR_PARAFORMER_ZH -> SherpaParaformerEngine(
                modelFile = primary,
                tokensFile = tokens,
                numThreads = numThreads,
                displayName = descriptor.displayName
            )
            ModelType.ASR_WHISPER_EN -> {
                val decoderName = descriptor.extraRequiredFiles.firstOrNull { it.endsWith(".onnx") }
                    ?: error("Whisper 模型缺少 decoder 文件配置")
                SherpaWhisperEngine(
                    encoderFile = primary,
                    decoderFile = File(modelDir, decoderName),
                    tokensFile = tokens,
                    language = "en",
                    numThreads = numThreads,
                    displayName = descriptor.displayName
                )
            }
            ModelType.ASR_WENET_YUE -> SherpaWenetCtcEngine(
                modelFile = primary,
                tokensFile = tokens,
                numThreads = numThreads,
                displayName = descriptor.displayName
            )
            else -> throw IllegalArgumentException("不是离线语音识别模型: ${descriptor.id}")
        }
    }
}

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
         * Rejects pure-punctuation hallucinations (e.g. ".", "?") and stray 1-2 char Hangul/Kana
         * noise hallucinations that SenseVoice sometimes emits in noisy cafeterias or on PA reverb
         * and accented Mandarin under auto mode.
         */
        fun isMeaningfulSpeech(
            text: String,
            detectedLang: String = "",
            requestedMode: AsrLanguageMode = AsrLanguageMode.AUTO
        ): Boolean {
            val contentOnly = text.replace(PUNCT_AND_SPACE_REGEX, "")
            if (contentOnly.isEmpty()) return false
            // Drop short non-ZH/EN Hangul/Kana noise artifacts under AUTO mode (e.g. "그.", "あの")
            if (requestedMode == AsrLanguageMode.AUTO && contentOnly.length <= 2) {
                val allHangulOrKana = contentOnly.all { ch ->
                    ch.code in 0xAC00..0xD7AF || ch.code in 0x1100..0x11FF || ch.code in 0x3040..0x30FF
                }
                if (allHangulOrKana ||
                    (contentOnly.length == 1 &&
                        (detectedLang.equals("ko", ignoreCase = true) || detectedLang.equals("ja", ignoreCase = true)))
                ) {
                    return false
                }
            }
            return true
        }
    }
}

/**
 * Lightweight Chinese-only Paraformer int8 offline ASR engine via sherpa-onnx.
 */
class SherpaParaformerEngine(
    modelFile: File,
    tokensFile: File,
    numThreads: Int = 4,
    displayName: String = "Paraformer-ZH int8"
) : OfflineAsrEngine {

    override val engineName: String = displayName

    @Volatile
    private var recognizer: OfflineRecognizer?

    override val isReady: Boolean
        get() = recognizer != null

    init {
        require(modelFile.exists() && tokensFile.exists()) {
            "Paraformer 模型文件不存在: ${modelFile.absolutePath}"
        }
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(model = modelFile.absolutePath),
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
            AsrRecognitionResult(
                text = result.text.trim(),
                detectedLanguage = "zh",
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
}

/**
 * English-only Whisper int8 offline ASR engine via sherpa-onnx (strong on accented/fast English).
 */
class SherpaWhisperEngine(
    encoderFile: File,
    decoderFile: File,
    tokensFile: File,
    language: String = "en",
    numThreads: Int = 4,
    displayName: String = "Whisper-EN int8"
) : OfflineAsrEngine {

    override val engineName: String = displayName

    @Volatile
    private var recognizer: OfflineRecognizer?

    override val isReady: Boolean
        get() = recognizer != null

    init {
        require(encoderFile.exists() && decoderFile.exists() && tokensFile.exists()) {
            "Whisper 模型文件不完整: ${encoderFile.parent}"
        }
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = encoderFile.absolutePath,
                    decoder = decoderFile.absolutePath,
                    language = language,
                    task = "transcribe"
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
            AsrRecognitionResult(
                text = result.text.trim(),
                detectedLanguage = "en",
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
}

/**
 * Cantonese / ZH-EN-YUE WenetSpeech Conformer CTC int8 offline ASR engine via sherpa-onnx.
 */
class SherpaWenetCtcEngine(
    modelFile: File,
    tokensFile: File,
    numThreads: Int = 4,
    displayName: String = "WenetSpeech-Yue int8"
) : OfflineAsrEngine {

    override val engineName: String = displayName

    @Volatile
    private var recognizer: OfflineRecognizer?

    override val isReady: Boolean
        get() = recognizer != null

    init {
        require(modelFile.exists() && tokensFile.exists()) {
            "WenetSpeech-Yue 模型文件不存在: ${modelFile.absolutePath}"
        }
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                wenetCtc = OfflineWenetCtcModelConfig(model = modelFile.absolutePath),
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
            AsrRecognitionResult(
                text = result.text.trim(),
                detectedLanguage = "yue",
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
}

/**
 * Silero VAD segmenter via sherpa-onnx, configured with far-field sensitivity and speech-tail safety.
 */
class SherpaSileroVadEngine(
    sileroModelFile: File,
    threshold: Float = 0.24f,
    minSilenceDurationSec: Float = 0.55f,
    minSpeechDurationSec: Float = 0.20f,
    maxSpeechDurationSec: Float = 13.0f,
    sampleRate: Int = 16000
) : VadSegmenter {

    @Volatile
    private var vad: Vad?

    init {
        require(sileroModelFile.exists()) { "Silero VAD 模型不存在: ${sileroModelFile.absolutePath}" }
        val config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = sileroModelFile.absolutePath,
                threshold = threshold.coerceIn(0.10f, 0.85f),
                minSilenceDuration = minSilenceDurationSec.coerceIn(0.20f, 2.5f),
                minSpeechDuration = minSpeechDurationSec.coerceIn(0.10f, 1.5f),
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
 * Streaming GTCRN neural speech enhancement via sherpa-onnx (~524KB model, <5% CPU on Dimensity 810).
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
