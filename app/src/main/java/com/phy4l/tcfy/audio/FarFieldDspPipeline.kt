package com.phy4l.tcfy.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Optional neural speech enhancer hook (implemented by sherpa-onnx GTCRN).
 */
interface SpeechEnhancer {
    /**
     * Enhances a frame of 16kHz normalized float audio [-1, 1] and returns enhanced samples.
     */
    fun enhance(samples: FloatArray, sampleRate: Int): FloatArray
    fun reset() {}
    fun close() {}
}

/**
 * Software far-field DSP front-end optimized for 3-10m classroom recording on smartphone mics.
 *
 * Processing stages:
 * 1. DC-offset removal + 2nd-order Butterworth High-Pass Filter (~85 Hz) to reject AC hum,
 *    projector fan rumble, and desk thumps.
 * 2. Optional neural speech enhancement (GTCRN) with dry/wet blending to preserve high-frequency
 *    English consonants (/s/, /t/, /f/, /k/) that aggressive denoisers can smear.
 * 3. Speech-preserving Adaptive Gain Control (AGC) that boosts weak distant lecture audio toward
 *    target RMS while freezing gain during silence to avoid pumping room noise floor.
 * 4. Soft tanh limiter to prevent harsh digital clipping from nearby coughs or pen drops.
 */
class FarFieldDspPipeline(
    private val sampleRate: Int = 16000,
    initialConfig: FarFieldDspConfig = AcousticPreset.CLASSROOM_FAR_FIELD.config,
    @Volatile private var speechEnhancer: SpeechEnhancer? = null
) {
    @Volatile
    private var config: FarFieldDspConfig = initialConfig

    // 2nd-order Butterworth HPF biquad coefficients and state
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    // DC blocker state
    private var dcPrevX = 0f
    private var dcPrevY = 0f

    // Adaptive AGC state
    private var currentGainLinear = 1.0f

    @Volatile
    var lastInputDbfs: Float = -90f
        private set

    @Volatile
    var lastOutputDbfs: Float = -90f
        private set

    @Volatile
    var lastGainDb: Float = 0f
        private set

    init {
        recomputeHighPassCoefficients(initialConfig.highPassCutoffHz)
    }

    fun updateConfig(newConfig: FarFieldDspConfig) {
        val cutoffChanged = newConfig.highPassCutoffHz != config.highPassCutoffHz
        config = newConfig
        if (cutoffChanged) {
            recomputeHighPassCoefficients(newConfig.highPassCutoffHz)
        }
    }

    fun setSpeechEnhancer(enhancer: SpeechEnhancer?) {
        speechEnhancer = enhancer
    }

    fun reset() {
        x1 = 0f
        x2 = 0f
        y1 = 0f
        y2 = 0f
        dcPrevX = 0f
        dcPrevY = 0f
        currentGainLinear = 1.0f
        lastInputDbfs = -90f
        lastOutputDbfs = -90f
        lastGainDb = 0f
        speechEnhancer?.reset()
    }

    /**
     * Converts signed 16-bit PCM samples to normalized float [-1, 1] and runs the full DSP chain.
     */
    fun processPcm16(pcm: ShortArray, length: Int = pcm.size): FloatArray {
        val input = FloatArray(length) { i -> pcm[i] / 32768f }
        return processFloat(input)
    }

    /**
     * Processes a frame of normalized float samples [-1, 1] and returns a new enhanced array.
     */
    fun processFloat(input: FloatArray): FloatArray {
        if (input.isEmpty()) return FloatArray(0)

        val cfg = config
        val out = FloatArray(input.size)
        lastInputDbfs = computeRmsDbfs(input)

        // Stage 1: DC blocker + 2nd-order Butterworth HPF
        if (cfg.enableHighPass) {
            val dcAlpha = 0.995f
            for (i in input.indices) {
                val x = input[i]
                val dcRemoved = x - dcPrevX + dcAlpha * dcPrevY
                dcPrevX = x
                dcPrevY = dcRemoved

                val y = b0 * dcRemoved + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1
                x1 = dcRemoved
                y2 = y1
                y1 = y
                out[i] = y
            }
        } else {
            input.copyInto(out)
        }

        // Stage 2: Optional neural speech enhancement with dry/wet mix
        val enhancer = speechEnhancer
        val wetMix = cfg.denoiseLevel.wetMix
        if (enhancer != null && wetMix > 0.01f) {
            val denoised = runCatching { enhancer.enhance(out, sampleRate) }.getOrNull()
            if (denoised != null && denoised.size == out.size) {
                val dryMix = 1f - wetMix
                for (i in out.indices) {
                    out[i] = dryMix * out[i] + wetMix * denoised[i]
                }
            }
        }

        // Stage 3: Speech-preserving Adaptive Gain Control (AGC)
        if (cfg.enableAgc) {
            val frameRms = computeRms(out)
            val frameDbfs = linearToDbfs(frameRms)
            val maxGainLinear = dbToLinear(cfg.maxGainDb)
            val targetRmsLinear = dbToLinear(cfg.targetRmsDbfs)

            // Only update gain upward when frame exceeds silence floor, preventing room noise pumping.
            // Fast attack when audio suddenly gets loud; smooth release when audio is weak.
            if (frameDbfs > cfg.agcSilenceFloorDbfs) {
                val desiredGain = (targetRmsLinear / max(frameRms, 1e-5f)).coerceIn(0.5f, maxGainLinear)
                val smoothing = if (desiredGain < currentGainLinear) 0.35f else 0.08f
                currentGainLinear += smoothing * (desiredGain - currentGainLinear)
            } else {
                // Gently relax extreme gain toward moderate baseline during long silence
                val idleTarget = min(currentGainLinear, dbToLinear(min(cfg.maxGainDb, 10f)))
                currentGainLinear += 0.02f * (idleTarget - currentGainLinear)
            }

            // Stage 4: Apply gain + soft tanh limiter (knee starts around 0.85)
            for (i in out.indices) {
                val amplified = out[i] * currentGainLinear
                out[i] = softLimit(amplified)
            }
            lastGainDb = linearToDb(currentGainLinear)
        } else {
            for (i in out.indices) {
                out[i] = softLimit(out[i])
            }
            lastGainDb = 0f
        }

        lastOutputDbfs = computeRmsDbfs(out)
        return out
    }

    private fun recomputeHighPassCoefficients(cutoffHz: Float) {
        val clampedCutoff = cutoffHz.coerceIn(20f, sampleRate * 0.4f)
        val omega = 2.0 * PI * clampedCutoff / sampleRate
        val cosW = cos(omega)
        val sinW = sin(omega)
        val q = 0.70710678 // Butterworth maximally flat Q = 1/sqrt(2)
        val alpha = sinW / (2.0 * q)

        val a0 = 1.0 + alpha
        b0 = ((1.0 + cosW) / 2.0 / a0).toFloat()
        b1 = (-(1.0 + cosW) / a0).toFloat()
        b2 = ((1.0 + cosW) / 2.0 / a0).toFloat()
        a1 = (-2.0 * cosW / a0).toFloat()
        a2 = ((1.0 - alpha) / a0).toFloat()
    }

    companion object {
        fun computeRms(samples: FloatArray): Float {
            if (samples.isEmpty()) return 0f
            var sumSq = 0.0
            for (s in samples) {
                sumSq += s * s
            }
            return sqrt(sumSq / samples.size).toFloat()
        }

        fun computeRmsDbfs(samples: FloatArray): Float = linearToDbfs(computeRms(samples))

        fun linearToDbfs(rms: Float): Float {
            return if (rms <= 1e-6f) -96f else (20.0 * log10(rms.toDouble())).toFloat().coerceAtLeast(-96f)
        }

        fun linearToDb(linear: Float): Float {
            return if (linear <= 1e-6f) -96f else (20.0 * log10(linear.toDouble())).toFloat()
        }

        fun dbToLinear(db: Float): Float = 10.0.pow(db / 20.0).toFloat()

        fun softLimit(sample: Float): Float {
            val knee = 0.85f
            val absVal = kotlin.math.abs(sample)
            if (absVal <= knee) return sample
            val sign = if (sample >= 0f) 1f else -1f
            val excess = (absVal - knee) / (1f - knee)
            return sign * (knee + (1f - knee) * tanh(excess.toDouble()).toFloat())
        }
    }
}
