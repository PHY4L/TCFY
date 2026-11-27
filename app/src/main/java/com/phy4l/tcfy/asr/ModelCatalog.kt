package com.phy4l.tcfy.asr

enum class ModelType(val isAsrModel: Boolean) {
    ASR_SENSEVOICE(true),
    ASR_PARAFORMER_ZH(true),
    ASR_WHISPER_EN(true),
    ASR_WENET_YUE(true),
    VAD_SILERO(false),
    DENOISE_GTCRN(false)
}

enum class DownloadFormat {
    SINGLE_ONNX,
    TAR_BZ2_ARCHIVE,
    DIRECT_MULTI_FILES
}

data class RemoteFileEntry(
    val relativePath: String,
    val directUrl: String,
    val mirrorUrl: String
)

data class ModelDescriptor(
    val id: String,
    val type: ModelType,
    val displayName: String,
    val description: String,
    val approxSizeMb: Int,
    val required: Boolean,
    val format: DownloadFormat,
    val folderName: String,
    val primaryFileName: String,
    val extraRequiredFiles: List<String> = emptyList(),
    val tokensFileName: String = "tokens.txt",
    val languageBadge: String = "",
    val bundledAssetPath: String? = null,
    val archiveDirectUrl: String? = null,
    val archiveMirrorUrl: String? = null,
    val directFiles: List<RemoteFileEntry> = emptyList()
)

object ModelCatalog {

    // -------------------------------------------------------------------------
    // Offline ASR Models (choose multilingual core or specialized single-lang)
    // -------------------------------------------------------------------------

    val SENSEVOICE_INT8 = ModelDescriptor(
        id = "sensevoice_small_int8",
        type = ModelType.ASR_SENSEVOICE,
        displayName = "SenseVoice-Small (中英多语全能)",
        description = "中/英/日/韩/粤混杂识别与自动标点，天玑 810 约 0.18x 实时速度（推荐默认）",
        approxSizeMb = 228,
        required = false,
        format = DownloadFormat.DIRECT_MULTI_FILES,
        folderName = "sensevoice-small-int8",
        primaryFileName = "model.int8.onnx",
        extraRequiredFiles = listOf("tokens.txt"),
        tokensFileName = "tokens.txt",
        languageBadge = "中/英/粤/日/韩",
        archiveDirectUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
        archiveMirrorUrl = "https://gh-proxy.org/https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
        directFiles = listOf(
            RemoteFileEntry(
                relativePath = "model.int8.onnx",
                directUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx",
                mirrorUrl = "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx"
            ),
            RemoteFileEntry(
                relativePath = "tokens.txt",
                directUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt",
                mirrorUrl = "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt"
            )
        )
    )

    val PARAFORMER_ZH_SMALL_INT8 = ModelDescriptor(
        id = "paraformer_zh_small_int8",
        type = ModelType.ASR_PARAFORMER_ZH,
        displayName = "Paraformer-ZH Small (纯中文轻量高速)",
        description = "阿里 FunASR 轻量中文专用模型，仅 ~78MB、速度极快，适合纯中文专业课",
        approxSizeMb = 78,
        required = false,
        format = DownloadFormat.DIRECT_MULTI_FILES,
        folderName = "paraformer-zh-small-int8",
        primaryFileName = "model.int8.onnx",
        extraRequiredFiles = listOf("tokens.txt"),
        tokensFileName = "tokens.txt",
        languageBadge = "纯中文",
        archiveDirectUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2",
        archiveMirrorUrl = "https://gh-proxy.org/https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2",
        directFiles = listOf(
            RemoteFileEntry(
                relativePath = "model.int8.onnx",
                directUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09/resolve/main/model.int8.onnx",
                mirrorUrl = "https://hf-mirror.com/csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09/resolve/main/model.int8.onnx"
            ),
            RemoteFileEntry(
                relativePath = "tokens.txt",
                directUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09/resolve/main/tokens.txt",
                mirrorUrl = "https://hf-mirror.com/csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09/resolve/main/tokens.txt"
            )
        )
    )

    val WHISPER_TINY_EN_INT8 = ModelDescriptor(
        id = "whisper_tiny_en_int8",
        type = ModelType.ASR_WHISPER_EN,
        displayName = "Whisper-Tiny.en (纯英文超轻量)",
        description = "仅 ~98MB，专为全英文讲座/听力设计，内存占用低、响应快",
        approxSizeMb = 98,
        required = false,
        format = DownloadFormat.DIRECT_MULTI_FILES,
        folderName = "whisper-tiny-en-int8",
        primaryFileName = "tiny.en-encoder.int8.onnx",
        extraRequiredFiles = listOf("tiny.en-decoder.int8.onnx", "tiny.en-tokens.txt"),
        tokensFileName = "tiny.en-tokens.txt",
        languageBadge = "纯英文",
        archiveDirectUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2",
        archiveMirrorUrl = "https://gh-proxy.org/https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2",
        directFiles = listOf(
            RemoteFileEntry(
                relativePath = "tiny.en-encoder.int8.onnx",
                directUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/tiny.en-encoder.int8.onnx",
                mirrorUrl = "https://hf-mirror.com/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/tiny.en-encoder.int8.onnx"
            ),
            RemoteFileEntry(
                relativePath = "tiny.en-decoder.int8.onnx",
                directUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/tiny.en-decoder.int8.onnx",
                mirrorUrl = "https://hf-mirror.com/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/tiny.en-decoder.int8.onnx"
            ),
            RemoteFileEntry(
                relativePath = "tiny.en-tokens.txt",
                directUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/tiny.en-tokens.txt",
                mirrorUrl = "https://hf-mirror.com/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/tiny.en-tokens.txt"
            )
        )
    )

    val WHISPER_BASE_EN_INT8 = ModelDescriptor(
        id = "whisper_base_en_int8",
        type = ModelType.ASR_WHISPER_EN,
        displayName = "Whisper-Base.en (纯英文高精度 · 抗口音)",
        description = "约 153MB，针对外教/多国口音英文与快语速连读有显著识别优势",
        approxSizeMb = 153,
        required = false,
        format = DownloadFormat.DIRECT_MULTI_FILES,
        folderName = "whisper-base-en-int8",
        primaryFileName = "base.en-encoder.int8.onnx",
        extraRequiredFiles = listOf("base.en-decoder.int8.onnx", "base.en-tokens.txt"),
        tokensFileName = "base.en-tokens.txt",
        languageBadge = "纯英文 · 抗口音",
        archiveDirectUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.en.tar.bz2",
        archiveMirrorUrl = "https://gh-proxy.org/https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.en.tar.bz2",
        directFiles = listOf(
            RemoteFileEntry(
                relativePath = "base.en-encoder.int8.onnx",
                directUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base.en/resolve/main/base.en-encoder.int8.onnx",
                mirrorUrl = "https://hf-mirror.com/csukuangfj/sherpa-onnx-whisper-base.en/resolve/main/base.en-encoder.int8.onnx"
            ),
            RemoteFileEntry(
                relativePath = "base.en-decoder.int8.onnx",
                directUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base.en/resolve/main/base.en-decoder.int8.onnx",
                mirrorUrl = "https://hf-mirror.com/csukuangfj/sherpa-onnx-whisper-base.en/resolve/main/base.en-decoder.int8.onnx"
            ),
            RemoteFileEntry(
                relativePath = "base.en-tokens.txt",
                directUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base.en/resolve/main/base.en-tokens.txt",
                mirrorUrl = "https://hf-mirror.com/csukuangfj/sherpa-onnx-whisper-base.en/resolve/main/base.en-tokens.txt"
            )
        )
    )

    val WENET_YUE_INT8 = ModelDescriptor(
        id = "wenet_yue_int8",
        type = ModelType.ASR_WENET_YUE,
        displayName = "WenetSpeech-Yue (粤语 / 中英粤专用)",
        description = "约 128MB，针对粤语授课、港澳/广式口音及中英粤夹杂场景深度优化",
        approxSizeMb = 128,
        required = false,
        format = DownloadFormat.DIRECT_MULTI_FILES,
        folderName = "wenet-yue-int8",
        primaryFileName = "model.int8.onnx",
        extraRequiredFiles = listOf("tokens.txt"),
        tokensFileName = "tokens.txt",
        languageBadge = "粤语 / 中英粤",
        archiveDirectUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-wenetspeech-yue-u2pp-conformer-ctc-zh-en-cantonese-int8-2025-09-10.tar.bz2",
        archiveMirrorUrl = "https://gh-proxy.org/https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-wenetspeech-yue-u2pp-conformer-ctc-zh-en-cantonese-int8-2025-09-10.tar.bz2",
        directFiles = listOf(
            RemoteFileEntry(
                relativePath = "model.int8.onnx",
                directUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-wenetspeech-yue-u2pp-conformer-ctc-zh-en-cantonese-int8-2025-09-10/resolve/main/model.int8.onnx",
                mirrorUrl = "https://hf-mirror.com/csukuangfj/sherpa-onnx-wenetspeech-yue-u2pp-conformer-ctc-zh-en-cantonese-int8-2025-09-10/resolve/main/model.int8.onnx"
            ),
            RemoteFileEntry(
                relativePath = "tokens.txt",
                directUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-wenetspeech-yue-u2pp-conformer-ctc-zh-en-cantonese-int8-2025-09-10/resolve/main/tokens.txt",
                mirrorUrl = "https://hf-mirror.com/csukuangfj/sherpa-onnx-wenetspeech-yue-u2pp-conformer-ctc-zh-en-cantonese-int8-2025-09-10/resolve/main/tokens.txt"
            )
        )
    )

    // -------------------------------------------------------------------------
    // Built-in (<1MB) Support Models (bundled inside APK assets, zero download!)
    // -------------------------------------------------------------------------

    val SILERO_VAD = ModelDescriptor(
        id = "silero_vad",
        type = ModelType.VAD_SILERO,
        displayName = "Silero VAD (远场智能语音断句 · 已内置)",
        description = "仅 629KB，已内置于安装包开箱即用；精准检测教室远场人声起止",
        approxSizeMb = 1,
        required = true,
        format = DownloadFormat.SINGLE_ONNX,
        folderName = "silero-vad",
        primaryFileName = "silero_vad.onnx",
        languageBadge = "内置",
        bundledAssetPath = "models/silero-vad/silero_vad.onnx",
        directFiles = listOf(
            RemoteFileEntry(
                relativePath = "silero_vad.onnx",
                directUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
                mirrorUrl = "https://hf-mirror.com/csukuangfj/sherpa-onnx-models/resolve/main/silero_vad.onnx"
            )
        )
    )

    val GTCRN_DENOISER = ModelDescriptor(
        id = "gtcrn_denoiser",
        type = ModelType.DENOISE_GTCRN,
        displayName = "GTCRN 神经语音增强 (去混响/降噪 · 已内置)",
        description = "仅 524KB，已内置于安装包开箱即用；显著提升教室后排信噪比，CPU 占用 <5%",
        approxSizeMb = 1,
        required = false,
        format = DownloadFormat.SINGLE_ONNX,
        folderName = "gtcrn-denoiser",
        primaryFileName = "gtcrn_simple.onnx",
        languageBadge = "内置",
        bundledAssetPath = "models/gtcrn-denoiser/gtcrn_simple.onnx",
        directFiles = listOf(
            RemoteFileEntry(
                relativePath = "gtcrn_simple.onnx",
                directUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/gtcrn_simple.onnx",
                mirrorUrl = "https://hf-mirror.com/csukuangfj/sherpa-onnx-models/resolve/main/gtcrn_simple.onnx"
            )
        )
    )

    val ASR_MODELS: List<ModelDescriptor> = listOf(
        SENSEVOICE_INT8,
        PARAFORMER_ZH_SMALL_INT8,
        WHISPER_TINY_EN_INT8,
        WHISPER_BASE_EN_INT8,
        WENET_YUE_INT8
    )

    val SUPPORT_MODELS: List<ModelDescriptor> = listOf(
        SILERO_VAD,
        GTCRN_DENOISER
    )

    val ALL_MODELS: List<ModelDescriptor> = ASR_MODELS + SUPPORT_MODELS

    fun findById(id: String): ModelDescriptor? = ALL_MODELS.firstOrNull { it.id == id }
}
