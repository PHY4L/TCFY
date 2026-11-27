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
    LIGHT("轻度 (保辅音)", 0.48f),
    STANDARD("标准降噪", 0.72f)
}

@Serializable
data class FarFieldDspConfig(
    val highPassCutoffHz: Float = 85f,
    val enableHighPass: Boolean = true,
    val enableAgc: Boolean = true,
    val targetRmsDbfs: Float = -18f,
    val maxGainDb: Float = 32f,
    val agcSilenceFloorDbfs: Float = -66f,
    val denoiseLevel: DenoiseLevel = DenoiseLevel.LIGHT,
    val vadThreshold: Float = 0.24f,
    val vadMinSilenceSec: Float = 0.55f,
    val vadMaxSpeechSec: Float = 13.0f,
    val audioSourceMode: AudioSourceMode = AudioSourceMode.FAR_FIELD_AUTO,
    val paAntiResonance: Boolean = false,
    val fastSpeechMode: Boolean = false
)

enum class AcousticPreset(
    val displayName: String,
    val shortHint: String,
    val config: FarFieldDspConfig
) {
    AUTO(
        displayName = "自动智能",
        shortHint = "录音前几分钟自动分析音量/混响/语速并动态调参",
        config = FarFieldDspConfig(
            highPassCutoffHz = 85f,
            enableHighPass = true,
            enableAgc = true,
            targetRmsDbfs = -18f,
            maxGainDb = 32f,
            agcSilenceFloorDbfs = -66f,
            denoiseLevel = DenoiseLevel.LIGHT,
            vadThreshold = 0.24f,
            vadMinSilenceSec = 0.55f,
            vadMaxSpeechSec = 13.0f,
            audioSourceMode = AudioSourceMode.FAR_FIELD_AUTO,
            paAntiResonance = false,
            fastSpeechMode = false
        )
    ),
    CLASSROOM_FAR_FIELD(
        displayName = "教室远场 (3-10m)",
        shortHint = "高增益 + 动态噪声门 + 敏感 VAD",
        config = FarFieldDspConfig(
            highPassCutoffHz = 85f,
            enableHighPass = true,
            enableAgc = true,
            targetRmsDbfs = -18f,
            maxGainDb = 32f,
            agcSilenceFloorDbfs = -66f,
            denoiseLevel = DenoiseLevel.LIGHT,
            vadThreshold = 0.24f,
            vadMinSilenceSec = 0.55f,
            vadMaxSpeechSec = 13.0f,
            audioSourceMode = AudioSourceMode.FAR_FIELD_AUTO,
            paAntiResonance = false,
            fastSpeechMode = false
        )
    ),
    SOFT_VOICE_ULTRA(
        displayName = "轻声 / 超远场",
        shortHint = "最高 +38dB 增益，专治 -50dB 以下轻声汇报",
        config = FarFieldDspConfig(
            highPassCutoffHz = 80f,
            enableHighPass = true,
            enableAgc = true,
            targetRmsDbfs = -17f,
            maxGainDb = 38f,
            agcSilenceFloorDbfs = -72f,
            denoiseLevel = DenoiseLevel.LIGHT,
            vadThreshold = 0.16f,
            vadMinSilenceSec = 0.55f,
            vadMaxSpeechSec = 12.0f,
            audioSourceMode = AudioSourceMode.FAR_FIELD_AUTO,
            paAntiResonance = false,
            fastSpeechMode = false
        )
    ),
    LECTURE_HALL_PA(
        displayName = "大教室有扩音器",
        shortHint = "抗箱体轰鸣均衡 + 防爆音压缩 + 轻降噪抗混响",
        config = FarFieldDspConfig(
            highPassCutoffHz = 125f,
            enableHighPass = true,
            enableAgc = true,
            targetRmsDbfs = -19f,
            maxGainDb = 16f,
            agcSilenceFloorDbfs = -56f,
            denoiseLevel = DenoiseLevel.LIGHT,
            vadThreshold = 0.34f,
            vadMinSilenceSec = 0.52f,
            vadMaxSpeechSec = 12.5f,
            audioSourceMode = AudioSourceMode.FAR_FIELD_AUTO,
            paAntiResonance = true,
            fastSpeechMode = false
        )
    ),
    FAST_ACCENT(
        displayName = "快语速 / 口音",
        shortHint = "短停顿分句 + 边界重叠防截词 + 保辅音清晰度",
        config = FarFieldDspConfig(
            highPassCutoffHz = 90f,
            enableHighPass = true,
            enableAgc = true,
            targetRmsDbfs = -18f,
            maxGainDb = 28f,
            agcSilenceFloorDbfs = -64f,
            denoiseLevel = DenoiseLevel.LIGHT,
            vadThreshold = 0.24f,
            vadMinSilenceSec = 0.38f,
            vadMaxSpeechSec = 9.5f,
            audioSourceMode = AudioSourceMode.FAR_FIELD_AUTO,
            paAntiResonance = false,
            fastSpeechMode = true
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
            maxGainDb = 12f,
            agcSilenceFloorDbfs = -58f,
            denoiseLevel = DenoiseLevel.OFF,
            vadThreshold = 0.38f,
            vadMinSilenceSec = 0.50f,
            vadMaxSpeechSec = 13.0f,
            audioSourceMode = AudioSourceMode.MIC,
            paAntiResonance = false,
            fastSpeechMode = false
        )
    )
}
