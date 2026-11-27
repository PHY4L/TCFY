package com.phy4l.tcfy.audio

import kotlin.math.abs
import kotlin.math.roundToInt

enum class DiagnosedAcousticScene(val displayName: String) {
    ULTRA_SOFT_FAR_FIELD("极轻声远场"),
    PA_REVERBERANT("扩音器/高混响"),
    FAST_OR_SLURRED_SPEECH("快语速/音粘连"),
    NOISY_LOW_SNR("高噪低信噪比"),
    BALANCED_CLASSROOM("标准清晰教室")
}

data class AcousticTelemetrySnapshot(
    val frameCount: Int,
    val p90InputDbfs: Float,
    val medianNoiseFloorDbfs: Float,
    val snrDb: Float,
    val clipRate: Float,
    val chunkCount: Int,
    val forcedSplitRate: Float,
    val emptyAsrRate: Float,
    val avgCharsPerSec: Float
)

data class AutoTuningDecision(
    val recommendedConfig: FarFieldDspConfig,
    val scene: DiagnosedAcousticScene,
    val summaryReason: String,
    val needsVadRebuild: Boolean,
    val telemetry: AcousticTelemetrySnapshot
)

/**
 * Pure JVM adaptive classroom acoustic & ASR parameter tuner.
 *
 * Observes rolling ~3 minutes of acoustic telemetry (input RMS distribution, room noise floor,
 * clipping rate) and ASR/VAD metrics (forced-split rate, empty recognition ratio, speaking speed)
 * to automatically optimize DSP + VAD parameters during live lectures or on 1-tap user request.
 */
class LiveAcousticAutoTuner(
    private val maxFrames: Int = 1800, // ~3 min at 100ms sampling interval
    private val maxChunks: Int = 48
) {
    private data class FrameSample(
        val inputDbfs: Float,
        val noiseFloorDbfs: Float,
        val clipRatio: Float,
        val isSpeech: Boolean
    )

    private data class ChunkSample(
        val durationSec: Float,
        val wasForcedSplit: Boolean,
        val meaningfulCharCount: Int
    )

    private val frames = ArrayDeque<FrameSample>(maxFrames + 1)
    private val chunks = ArrayDeque<ChunkSample>(maxChunks + 1)

    @Synchronized
    fun reset() {
        frames.clear()
        chunks.clear()
    }

    @Synchronized
    fun recordFrame(
        inputDbfs: Float,
        noiseFloorDbfs: Float,
        clipRatio: Float,
        isSpeech: Boolean
    ) {
        if (!inputDbfs.isFinite() || !noiseFloorDbfs.isFinite()) return
        if (frames.size >= maxFrames) frames.removeFirst()
        frames.addLast(
            FrameSample(
                inputDbfs = inputDbfs.coerceIn(-96f, 0f),
                noiseFloorDbfs = noiseFloorDbfs.coerceIn(-90f, -20f),
                clipRatio = clipRatio.coerceIn(0f, 1f),
                isSpeech = isSpeech
            )
        )
    }

    @Synchronized
    fun recordAsrChunk(
        durationSec: Float,
        wasForcedSplit: Boolean,
        acceptedText: String?
    ) {
        if (chunks.size >= maxChunks) chunks.removeFirst()
        val cleanCount = acceptedText
            ?.replace(Regex("[\\p{P}\\p{S}\\s]+"), "")
            ?.length ?: 0
        chunks.addLast(
            ChunkSample(
                durationSec = durationSec.coerceAtLeast(0.1f),
                wasForcedSplit = wasForcedSplit,
                meaningfulCharCount = cleanCount
            )
        )
    }

    @Synchronized
    fun snapshot(): AcousticTelemetrySnapshot {
        if (frames.isEmpty()) {
            return AcousticTelemetrySnapshot(
                frameCount = 0,
                p90InputDbfs = -90f,
                medianNoiseFloorDbfs = -65f,
                snrDb = 0f,
                clipRate = 0f,
                chunkCount = 0,
                forcedSplitRate = 0f,
                emptyAsrRate = 0f,
                avgCharsPerSec = 0f
            )
        }

        val sortedInputs = frames.map { it.inputDbfs }.sorted()
        val p90Idx = ((sortedInputs.size - 1) * 0.90f).roundToInt().coerceIn(0, sortedInputs.lastIndex)
        val p90Input = sortedInputs[p90Idx]

        val sortedFloors = frames.map { it.noiseFloorDbfs }.sorted()
        val medianFloor = sortedFloors[sortedFloors.size / 2]

        val snr = (p90Input - medianFloor).coerceAtLeast(0f)
        val clipFrames = frames.count { it.clipRatio >= 0.01f }
        val clipRate = clipFrames.toFloat() / frames.size

        val chunkCount = chunks.size
        val forcedSplitRate = if (chunkCount > 0) {
            chunks.count { it.wasForcedSplit }.toFloat() / chunkCount
        } else 0f
        val emptyAsrRate = if (chunkCount > 0) {
            chunks.count { it.meaningfulCharCount == 0 }.toFloat() / chunkCount
        } else 0f

        val nonEmptyChunks = chunks.filter { it.meaningfulCharCount > 0 }
        val avgCharsPerSec = if (nonEmptyChunks.isNotEmpty()) {
            val totalChars = nonEmptyChunks.sumOf { it.meaningfulCharCount }
            val totalSec = nonEmptyChunks.sumOf { it.durationSec.toDouble() }.toFloat().coerceAtLeast(0.5f)
            totalChars / totalSec
        } else 0f

        return AcousticTelemetrySnapshot(
            frameCount = frames.size,
            p90InputDbfs = p90Input,
            medianNoiseFloorDbfs = medianFloor,
            snrDb = snr,
            clipRate = clipRate,
            chunkCount = chunkCount,
            forcedSplitRate = forcedSplitRate,
            emptyAsrRate = emptyAsrRate,
            avgCharsPerSec = avgCharsPerSec
        )
    }

    /**
     * Evaluates recent acoustic + recognition metrics and recommends an updated [FarFieldDspConfig].
     * Returns null if fewer than [minFrames] (~[minFrames]*100ms) have been collected.
     */
    fun evaluateAndTune(
        currentConfig: FarFieldDspConfig,
        minFrames: Int = 50
    ): AutoTuningDecision? {
        val snap = snapshot()
        if (snap.frameCount < minFrames) return null

        val isPaClippingOrBoomy = snap.clipRate >= 0.015f ||
            (snap.p90InputDbfs >= -18f && snap.medianNoiseFloorDbfs >= -48f)
        val isUltraSoft = snap.p90InputDbfs <= -45f ||
            (snap.p90InputDbfs <= -40f && snap.chunkCount >= 3 && snap.emptyAsrRate >= 0.30f)
        val isFastSpeech = snap.chunkCount >= 3 &&
            (snap.forcedSplitRate >= 0.25f || snap.avgCharsPerSec >= 5.2f)
        val isNoisyLowSnr = snap.snrDb < 8.5f && snap.medianNoiseFloorDbfs > -48f &&
            (snap.chunkCount >= 3 && snap.emptyAsrRate >= 0.35f)

        val (scene, tuned, reason) = when {
            isPaClippingOrBoomy -> {
                val next = currentConfig.copy(
                    highPassCutoffHz = 125f,
                    enableHighPass = true,
                    enableAgc = true,
                    targetRmsDbfs = -19f,
                    maxGainDb = if (snap.clipRate >= 0.03f) 10f else 14f,
                    agcSilenceFloorDbfs = (snap.medianNoiseFloorDbfs + 2f).coerceIn(-58f, -46f),
                    denoiseLevel = DenoiseLevel.LIGHT,
                    vadThreshold = if (snap.emptyAsrRate >= 0.35f) 0.38f else 0.34f,
                    vadMinSilenceSec = if (isFastSpeech) 0.42f else 0.52f,
                    vadMaxSpeechSec = if (isFastSpeech) 10.0f else 12.5f,
                    paAntiResonance = true,
                    fastSpeechMode = isFastSpeech
                )
                Triple(
                    DiagnosedAcousticScene.PA_REVERBERANT,
                    next,
                    "检测到扩音器/强混响（峰值 ${snap.p90InputDbfs.roundToInt()}dB），已开启抗箱体轰鸣均衡并下调增益至 +${next.maxGainDb.roundToInt()}dB"
                )
            }

            isUltraSoft -> {
                val ultraQuiet = snap.p90InputDbfs <= -50f
                val next = currentConfig.copy(
                    highPassCutoffHz = 80f,
                    enableHighPass = true,
                    enableAgc = true,
                    targetRmsDbfs = -17f,
                    maxGainDb = if (ultraQuiet) 38f else 34f,
                    agcSilenceFloorDbfs = (snap.medianNoiseFloorDbfs + 2f).coerceIn(-74f, -62f),
                    denoiseLevel = DenoiseLevel.LIGHT,
                    vadThreshold = if (ultraQuiet) 0.16f else 0.20f,
                    vadMinSilenceSec = if (isFastSpeech) 0.42f else 0.55f,
                    vadMaxSpeechSec = if (isFastSpeech) 10.0f else 12.0f,
                    paAntiResonance = false,
                    fastSpeechMode = isFastSpeech
                )
                Triple(
                    DiagnosedAcousticScene.ULTRA_SOFT_FAR_FIELD,
                    next,
                    "检测到轻声远场（音量 ${snap.p90InputDbfs.roundToInt()}dBFS），已提升增益至 +${next.maxGainDb.roundToInt()}dB、降低 VAD 门限至 ${"%.2f".format(next.vadThreshold)}"
                )
            }

            isFastSpeech -> {
                val neededGain = (-18f - snap.p90InputDbfs).coerceIn(14f, 34f)
                val next = currentConfig.copy(
                    highPassCutoffHz = 90f,
                    enableHighPass = true,
                    enableAgc = true,
                    maxGainDb = neededGain,
                    agcSilenceFloorDbfs = (snap.medianNoiseFloorDbfs + 2f).coerceIn(-68f, -52f),
                    denoiseLevel = DenoiseLevel.LIGHT,
                    vadThreshold = if (snap.p90InputDbfs < -35f) 0.22f else 0.28f,
                    vadMinSilenceSec = 0.38f,
                    vadMaxSpeechSec = 9.5f,
                    paAntiResonance = false,
                    fastSpeechMode = true
                )
                Triple(
                    DiagnosedAcousticScene.FAST_OR_SLURRED_SPEECH,
                    next,
                    "检测到快语速/连续讲话，已启用短停顿切分 (0.38s) 与 450ms 重叠防截词"
                )
            }

            isNoisyLowSnr -> {
                val next = currentConfig.copy(
                    highPassCutoffHz = 110f,
                    enableHighPass = true,
                    enableAgc = true,
                    maxGainDb = (-18f - snap.p90InputDbfs).coerceIn(14f, 26f),
                    agcSilenceFloorDbfs = (snap.medianNoiseFloorDbfs + 3f).coerceIn(-56f, -44f),
                    denoiseLevel = DenoiseLevel.STANDARD,
                    vadThreshold = (currentConfig.vadThreshold + 0.06f).coerceIn(0.30f, 0.48f),
                    paAntiResonance = false
                )
                Triple(
                    DiagnosedAcousticScene.NOISY_LOW_SNR,
                    next,
                    "检测到较高环境底噪（${snap.medianNoiseFloorDbfs.roundToInt()}dBFS），已增强降噪并收紧人声门限"
                )
            }

            else -> {
                val idealGain = (-18f - snap.p90InputDbfs).coerceIn(14f, 32f)
                val idealVad = when {
                    snap.p90InputDbfs < -38f -> 0.22f
                    snap.p90InputDbfs > -24f -> 0.34f
                    else -> 0.26f
                }
                val next = currentConfig.copy(
                    highPassCutoffHz = 85f,
                    enableHighPass = true,
                    enableAgc = true,
                    maxGainDb = idealGain,
                    agcSilenceFloorDbfs = (snap.medianNoiseFloorDbfs + 3f).coerceIn(-68f, -52f),
                    denoiseLevel = if (snap.p90InputDbfs > -22f && snap.snrDb > 18f) DenoiseLevel.OFF else DenoiseLevel.LIGHT,
                    vadThreshold = idealVad,
                    vadMinSilenceSec = 0.52f,
                    vadMaxSpeechSec = 12.5f,
                    paAntiResonance = false,
                    fastSpeechMode = false
                )
                Triple(
                    DiagnosedAcousticScene.BALANCED_CLASSROOM,
                    next,
                    "教室声学均衡（人声 ${snap.p90InputDbfs.roundToInt()}dBFS），已微调增益至 +${next.maxGainDb.roundToInt()}dB"
                )
            }
        }

        val needsVadRebuild = abs(tuned.vadThreshold - currentConfig.vadThreshold) >= 0.04f ||
            abs(tuned.vadMinSilenceSec - currentConfig.vadMinSilenceSec) >= 0.08f ||
            abs(tuned.vadMaxSpeechSec - currentConfig.vadMaxSpeechSec) >= 1.5f

        return AutoTuningDecision(
            recommendedConfig = tuned,
            scene = scene,
            summaryReason = reason,
            needsVadRebuild = needsVadRebuild,
            telemetry = snap
        )
    }
}
