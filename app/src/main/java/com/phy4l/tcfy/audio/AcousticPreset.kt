package com.phy4l.tcfy.audio

import kotlinx.serialization.Serializable

/**
 * Preferred Android AudioSource strategy.
 *
 * Commercial apps often use VOICE_COMMUNICATION / VOICE_RECOGNITION whose vendor
 * near-field noise gate suppresses lecturer voices 3-10m away in reverberant classrooms.
 */
@Serializable
enum class AudioSourceMode(val displayName: String, val description: String) {
    FAR_FIELD_AUTO(
        displayName = "远场智能 (推荐)",
        description = "优先无处理原始麦，不支持则切换顶部远场麦 (CAMCORDER)"
    ),
    UNPROCESSED(
        displayName = "原始无处理 (UNPROCESSED)",
        description = "绕过系统近场降噪与门限，交由软件远场 DSP 处理"
    ),
    CAMCORDER(
        displayName = "顶部远场麦 (CAMCORDER)",
        description = "调用红米顶部远场定向麦，适合 5-10 米后排听课"
    ),
    MIC(
        displayName = "标准麦克风 (MIC)",
        description = "系统默认通用采集模式"
    )
}

@Serializable
enum class DenoiseLevel(val displayName: String, val wetMix: Float) {
    OFF("关闭", 0.0f),
    LIGHT("轻度 (保辅音)", 0.55f),
    STANDARD("标准降噪", 0.78f)
}

@Serializable
data class FarFieldDspConfig(
    val highPassCutoffHz: Float = 85f,
    val enableHighPass: Boolean = true,
    val enableAgc: Boolean = true,
    val targetRmsDbfs: Float = -18f,
    val maxGainDb: Float = 24f,
    val agcSilenceFloorDbfs: Float = -54f,
    val denoiseLevel: DenoiseLevel = DenoiseLevel.LIGHT,
    val vadThreshold: Float = 0.35f,
    val vadMinSilenceSec: Float = 0.65f,
    val vadMaxSpeechSec: Float = 14.0f,
    val audioSourceMode: AudioSourceMode = AudioSourceMode.FAR_FIELD_AUTO
)

enum class AcousticPreset(
    val displayName: String,
    val shortHint: String,
    val config: FarFieldDspConfig
) {
    CLASSROOM_FAR_FIELD(
        displayName = "教室远场 (3-10m)",
        shortHint = "高增益 + 轻度神经降噪 + 敏感 VAD",
        config = FarFieldDspConfig(
            highPassCutoffHz = 85f,
            enableHighPass = true,
            enableAgc = true,
            targetRmsDbfs = -18f,
            maxGainDb = 24f,
            agcSilenceFloorDbfs = -54f,
            denoiseLevel = DenoiseLevel.LIGHT,
            vadThreshold = 0.32f,
            vadMinSilenceSec = 0.65f,
            vadMaxSpeechSec = 14.0f,
            audioSourceMode = AudioSourceMode.FAR_FIELD_AUTO
        )
    ),
    LECTURE_HALL_PA(
        displayName = "大教室有扩音器",
        shortHint = "中等增益 + 标准降噪抗混响",
        config = FarFieldDspConfig(
            highPassCutoffHz = 95f,
            enableHighPass = true,
            enableAgc = true,
            targetRmsDbfs = -19f,
            maxGainDb = 16f,
            agcSilenceFloorDbfs = -50f,
            denoiseLevel = DenoiseLevel.STANDARD,
            vadThreshold = 0.38f,
            vadMinSilenceSec = 0.60f,
            vadMaxSpeechSec = 14.0f,
            audioSourceMode = AudioSourceMode.FAR_FIELD_AUTO
        )
    ),
    NEAR_FIELD(
        displayName = "前排 / 近距离",
        shortHint = "温和增益 + 保留原声细节",
        config = FarFieldDspConfig(
            highPassCutoffHz = 75f,
            enableHighPass = true,
            enableAgc = true,
            targetRmsDbfs = -20f,
            maxGainDb = 10f,
            agcSilenceFloorDbfs = -52f,
            denoiseLevel = DenoiseLevel.OFF,
            vadThreshold = 0.42f,
            vadMinSilenceSec = 0.55f,
            vadMaxSpeechSec = 14.0f,
            audioSourceMode = AudioSourceMode.MIC
        )
    )
}
