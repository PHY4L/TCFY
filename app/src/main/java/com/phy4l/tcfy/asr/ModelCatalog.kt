package com.phy4l.tcfy.asr

enum class ModelType {
    ASR_SENSEVOICE,
    VAD_SILERO,
    DENOISE_GTCRN
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
    val archiveDirectUrl: String? = null,
    val archiveMirrorUrl: String? = null,
    val directFiles: List<RemoteFileEntry> = emptyList()
)

object ModelCatalog {

    val SENSEVOICE_INT8 = ModelDescriptor(
        id = "sensevoice_small_int8",
        type = ModelType.ASR_SENSEVOICE,
        displayName = "SenseVoice-Small (中英多语离线核心)",
        description = "支持中/英/日/韩/粤混杂识别与自动标点，天玑 810 实测约 0.18x 实时速度（必装）",
        approxSizeMb = 228,
        required = true,
        format = DownloadFormat.DIRECT_MULTI_FILES,
        folderName = "sensevoice-small-int8",
        primaryFileName = "model.int8.onnx",
        extraRequiredFiles = listOf("tokens.txt"),
        archiveDirectUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2",
        archiveMirrorUrl = "https://gh-proxy.com/https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2",
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

    val SILERO_VAD = ModelDescriptor(
        id = "silero_vad",
        type = ModelType.VAD_SILERO,
        displayName = "Silero VAD (远场智能语音断句)",
        description = "精准检测教室 3-10m 讲师人声起止，过滤静音省电防漏词（必装）",
        approxSizeMb = 1,
        required = true,
        format = DownloadFormat.SINGLE_ONNX,
        folderName = "silero-vad",
        primaryFileName = "silero_vad.onnx",
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
        displayName = "GTCRN 神经语音增强 (教室去混响/降噪)",
        description = "超轻量级神经网络去噪，显著提升教室后排信噪比，CPU 占用低于 5%（强烈推荐）",
        approxSizeMb = 1,
        required = false,
        format = DownloadFormat.SINGLE_ONNX,
        folderName = "gtcrn-denoiser",
        primaryFileName = "gtcrn_simple.onnx",
        directFiles = listOf(
            RemoteFileEntry(
                relativePath = "gtcrn_simple.onnx",
                directUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/gtcrn_simple.onnx",
                mirrorUrl = "https://hf-mirror.com/csukuangfj/sherpa-onnx-models/resolve/main/gtcrn_simple.onnx"
            )
        )
    )

    val ALL_MODELS: List<ModelDescriptor> = listOf(
        SENSEVOICE_INT8,
        SILERO_VAD,
        GTCRN_DENOISER
    )
}
