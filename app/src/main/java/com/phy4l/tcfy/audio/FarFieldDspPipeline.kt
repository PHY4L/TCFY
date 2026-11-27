package com.phy4l.tcfy.audio

import kotlin.math.PI
import kotlin.math.abs
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
 * 1. DC-offset removal + 2nd-order Butterworth High-Pass Filter (~85 Hz normal / 125 Hz PA) to reject
 *    AC hum, projector fan rumble, PA cabinet boom, and desk thumps.
 * 1.5 Optional PA Anti-Resonance EQ (280 Hz de-mud cut + 2.6 kHz consonant presence boost) to improve
 *    intelligibility of reverberant lecture-hall PA loudspeakers.
 * 2. Optional neural speech enhancement (GTCRN) with ultra-soft-voice pre-leveling and dry/wet blending
 *    so voices below -50 dBFS are NOT swallowed as background noise and high-frequency consonants stay crisp.
 * 3. Adaptive Noise-Floor Speech-Preserving AGC (up to +40 dB) that tracks classroom ambient floor
 *    dynamically rather than hard-gating speakers below -54 dBFS.
 * 4. Optional soft-knee PA compressor + soft tanh limiter to prevent harsh digital clipping.
 */
class FarFieldDspPipeline(
    private val sampleRate: Int = 16000,
    initialConfig: FarFieldDspConfig = AcousticPreset.CLASSROOM_FAR_FIELD.config,
    @Volatile private var speechEnhancer: SpeechEnhancer? = null
) {
    @Volatile
    private var config: FarFieldDspConfig = initialConfig

    // 2nd-order Butterworth HPF biquad coefficients and state
    private var hpfB0 = 1f
    private var hpfB1 = 0f
    private var hpfB2 = 0f
    private var hpfA1 = 0f
    private var hpfA2 = 0f
    private var hpfX1 = 0f
    private var hpfX2 = 0f
    private var hpfY1 = 0f
    private var hpfY2 = 0f

    // PA Anti-Resonance Biquad 1: 280 Hz de-mud cut (-3.5 dB, Q=1.2)
    private var mudB0 = 1f
    private var mudB1 = 0f
    private var mudB2 = 0f
    private var mudA1 = 0f
    private var mudA2 = 0f
    private var mudX1 = 0f
    private var mudX2 = 0f
    private var mudY1 = 0f
    private var mudY2 = 0f

    // PA Anti-Resonance Biquad 2: 2600 Hz consonant presence boost (+2.2 dB, Q=1.1)
    private var presB0 = 1f
    private var presB1 = 0f
    private var presB2 = 0f
    private var presA1 = 0f
    private var presA2 = 0f
    private var presX1 = 0f
    private var presX2 = 0f
    private var presY1 = 0f
    private var presY2 = 0f

    // DC blocker state
    private var dcPrevX = 0f
    private var dcPrevY = 0f

    // Adaptive AGC & noise-floor tracker state
    private var currentGainLinear = 1.0f
    private var noiseFloorDbfsState = (initialConfig.agcSilenceFloorDbfs - 4f).coerceIn(-78f, -42f)
    private var processedFrames = 0

    @Volatile
    var lastInputDbfs: Float = -90f
        private set

    @Volatile
    var lastOutputDbfs: Float = -90f
        private set

    @Volatile
    var lastGainDb: Float = 0f
        private set

    @Volatile
    var lastNoiseFloorDbfs: Float = noiseFloorDbfsState
        private set

    @Volatile
    var lastClipRatio: Float = 0f
        private set

    init {
        recomputeHighPassCoefficients(initialConfig.highPassCutoffHz)
        recomputePaEqCoefficients()
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
        hpfX1 = 0f; hpfX2 = 0f; hpfY1 = 0f; hpfY2 = 0f
        mudX1 = 0f; mudX2 = 0f; mudY1 = 0f; mudY2 = 0f
        presX1 = 0f; presX2 = 0f; presY1 = 0f; presY2 = 0f
        dcPrevX = 0f; dcPrevY = 0f
        currentGainLinear = 1.0f
        noiseFloorDbfsState = (config.agcSilenceFloorDbfs - 4f).coerceIn(-78f, -42f)
        processedFrames = 0
        lastInputDbfs = -90f
        lastOutputDbfs = -90f
        lastGainDb = 0f
        lastNoiseFloorDbfs = noiseFloorDbfsState
        lastClipRatio = 0f
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

        var clippedCount = 0
        for (s in input) {
            if (abs(s) >= 0.92f) clippedCount++
        }
        lastClipRatio = clippedCount.toFloat() / input.size

        // Stage 1: DC blocker + 2nd-order Butterworth HPF
        if (cfg.enableHighPass) {
            val dcAlpha = 0.995f
            for (i in input.indices) {
                val x = input[i]
                val dcRemoved = x - dcPrevX + dcAlpha * dcPrevY
                dcPrevX = x
                dcPrevY = dcRemoved

                val y = hpfB0 * dcRemoved + hpfB1 * hpfX1 + hpfB2 * hpfX2 - hpfA1 * hpfY1 - hpfA2 * hpfY2
                hpfX2 = hpfX1
                hpfX1 = dcRemoved
                hpfY2 = hpfY1
                hpfY1 = y
                out[i] = y
            }
        } else {
            input.copyInto(out)
        }

        // Stage 1.5: PA / Loudspeaker Anti-Resonance EQ (de-mud 280 Hz + presence 2.6 kHz)
        if (cfg.paAntiResonance) {
            for (i in out.indices) {
                val x = out[i]
                val m = mudB0 * x + mudB1 * mudX1 + mudB2 * mudX2 - mudA1 * mudY1 - mudA2 * mudY2
                mudX2 = mudX1; mudX1 = x
                mudY2 = mudY1; mudY1 = m

                val p = presB0 * m + presB1 * presX1 + presB2 * presX2 - presA1 * presY1 - presA2 * presY2
                presX2 = presX1; presX1 = m
                presY2 = presY1; presY1 = p

                out[i] = p
            }
        }

        // Track adaptive room noise floor on HPF-cleaned audio (ignoring dead-zero frames)
        val preEnhancerRms = computeRms(out)
        val preEnhancerDbfs = linearToDbfs(preEnhancerRms)
        updateNoiseFloor(preEnhancerDbfs, cfg.agcSilenceFloorDbfs)

        // Stage 2: Optional neural speech enhancement (GTCRN) with soft-voice pre-leveling
        val enhancer = speechEnhancer
        val configuredWetMix = cfg.denoiseLevel.wetMix
        if (enhancer != null && configuredWetMix > 0.01f) {
            // Bring very soft voices (<-34 dBFS) into GTCRN's effective dynamic range so GTCRN
            // does not misclassify quiet speech below -50 dBFS as noise and suppress it!
            val preGainDb = if (preEnhancerDbfs in -76f..-34f) {
                (-24f - preEnhancerDbfs).coerceIn(0f, min(26f, cfg.maxGainDb))
            } else {
                0f
            }
            val preGainLin = if (preGainDb > 0.5f) dbToLinear(preGainDb) else 1f
            val enhancerInput = if (preGainLin > 1.01f) {
                FloatArray(out.size) { i -> softLimit(out[i] * preGainLin) }
            } else {
                out
            }

            val denoised = runCatching { enhancer.enhance(enhancerInput, sampleRate) }.getOrNull()
            if (denoised != null && denoised.size == out.size) {
                // Soften wet mix slightly on ultra-quiet or fast-speech frames to preserve fragile consonants
                val effectiveWetMix = when {
                    preEnhancerDbfs < -52f -> configuredWetMix * 0.55f
                    cfg.fastSpeechMode -> min(configuredWetMix, 0.42f)
                    else -> configuredWetMix
                }
                val dryMix = 1f - effectiveWetMix
                val invPreGain = 1f / preGainLin
                for (i in out.indices) {
                    val denoisedScaled = denoised[i] * invPreGain
                    out[i] = dryMix * out[i] + effectiveWetMix * denoisedScaled
                }
            }
        }

        // Stage 3: Adaptive Noise-Floor Speech-Preserving AGC
        if (cfg.enableAgc) {
            val frameRms = computeRms(out)
            val frameDbfs = linearToDbfs(frameRms)
            val maxGainLinear = dbToLinear(cfg.maxGainDb.coerceIn(0f, 42f))
            val targetRmsLinear = dbToLinear(cfg.targetRmsDbfs)

            // Adaptive gate: lifts voices even below -50 dBFS whenever they rise above room floor
            val dynamicGateDbfs = max(cfg.agcSilenceFloorDbfs, noiseFloorDbfsState + 3.0f).coerceAtMost(-38f)

            if (frameDbfs > dynamicGateDbfs) {
                val desiredGain = (targetRmsLinear / max(frameRms, 1e-6f)).coerceIn(0.45f, maxGainLinear)
                val smoothing = when {
                    desiredGain < currentGainLinear -> 0.40f // Fast gain reduction on loud burst
                    frameDbfs < -44f -> 0.16f // Faster lift for very soft speakers so opening syllables survive
                    else -> 0.10f
                }
                currentGainLinear += smoothing * (desiredGain - currentGainLinear)
            } else {
                // Gently relax extreme gain toward moderate standby baseline during long silence
                val idleCapDb = min(cfg.maxGainDb, if (cfg.maxGainDb >= 34f) 16f else 10f)
                val idleTarget = min(currentGainLinear, dbToLinear(idleCapDb))
                currentGainLinear += 0.02f * (idleTarget - currentGainLinear)
            }

            // Stage 4: Apply gain + optional PA soft-knee compressor + soft tanh limiter
            for (i in out.indices) {
                var sample = out[i] * currentGainLinear
                if (cfg.paAntiResonance) {
                    sample = softKneeCompress(sample)
                }
                out[i] = softLimit(sample)
            }
            lastGainDb = linearToDb(currentGainLinear)
        } else {
            for (i in out.indices) {
                var sample = out[i]
                if (cfg.paAntiResonance) {
                    sample = softKneeCompress(sample)
                }
                out[i] = softLimit(sample)
            }
            lastGainDb = 0f
        }

        lastOutputDbfs = computeRmsDbfs(out)
        processedFrames++
        return out
    }

    private fun updateNoiseFloor(frameDbfs: Float, configuredFloorDbfs: Float) {
        if (frameDbfs <= -92f) return
        val minBound = min(configuredFloorDbfs - 8f, -78f)
        val clamped = frameDbfs.coerceIn(minBound, -36f)
        noiseFloorDbfsState = if (clamped < noiseFloorDbfsState) {
            // Track quiet drops quickly so soft speech immediately stands above floor
            noiseFloorDbfsState + 0.18f * (clamped - noiseFloorDbfsState)
        } else if (clamped < noiseFloorDbfsState + 5f) {
            // Very slow upward drift only when close to ambient noise (never chase speech peaks)
            noiseFloorDbfsState + 0.008f * (clamped - noiseFloorDbfsState)
        } else {
            noiseFloorDbfsState
        }
        lastNoiseFloorDbfs = noiseFloorDbfsState
    }

    private fun recomputeHighPassCoefficients(cutoffHz: Float) {
        val clampedCutoff = cutoffHz.coerceIn(20f, sampleRate * 0.4f)
        val omega = 2.0 * PI * clampedCutoff / sampleRate
        val cosW = cos(omega)
        val sinW = sin(omega)
        val q = 0.70710678 // Butterworth maximally flat Q = 1/sqrt(2)
        val alpha = sinW / (2.0 * q)

        val a0 = 1.0 + alpha
        hpfB0 = ((1.0 + cosW) / 2.0 / a0).toFloat()
        hpfB1 = (-(1.0 + cosW) / a0).toFloat()
        hpfB2 = ((1.0 + cosW) / 2.0 / a0).toFloat()
        hpfA1 = (-2.0 * cosW / a0).toFloat()
        hpfA2 = ((1.0 - alpha) / a0).toFloat()
    }

    private fun recomputePaEqCoefficients() {
        // Peaking EQ 1: 280 Hz, -3.5 dB, Q = 1.2 (cut boxy PA boominess)
        val mud = computePeakingBiquad(freqHz = 280.0, gainDb = -3.5, q = 1.2)
        mudB0 = mud[0]; mudB1 = mud[1]; mudB2 = mud[2]; mudA1 = mud[3]; mudA2 = mud[4]

        // Peaking EQ 2: 2600 Hz, +2.2 dB, Q = 1.1 (boost consonant clarity through room reverb)
        val pres = computePeakingBiquad(freqHz = 2600.0, gainDb = 2.2, q = 1.1)
        presB0 = pres[0]; presB1 = pres[1]; presB2 = pres[2]; presA1 = pres[3]; presA2 = pres[4]
    }

    private fun computePeakingBiquad(freqHz: Double, gainDb: Double, q: Double): FloatArray {
        val a = 10.0.pow(gainDb / 40.0)
        val omega = 2.0 * PI * freqHz / sampleRate
        val cosW = cos(omega)
        val sinW = sin(omega)
        val alpha = sinW / (2.0 * q)

        val b0 = 1.0 + alpha * a
        val b1 = -2.0 * cosW
        val b2 = 1.0 - alpha * a
        val a0 = 1.0 + alpha / a
        val a1 = -2.0 * cosW
        val a2 = 1.0 - alpha / a

        return floatArrayOf(
            (b0 / a0).toFloat(),
            (b1 / a0).toFloat(),
            (b2 / a0).toFloat(),
            (a1 / a0).toFloat(),
            (a2 / a0).toFloat()
        )
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

        /**
         * Gentle soft-knee dynamic range compression for reverberant/peaky PA microphones.
         * Starts compressing smoothly above 0.35 (~ -9 dBFS) with ~3:1 ratio to protect ASR from harsh peaks.
         */
        fun softKneeCompress(sample: Float): Float {
            val threshold = 0.35f
            val absVal = abs(sample)
            if (absVal <= threshold) return sample
            val sign = if (sample >= 0f) 1f else -1f
            val excess = absVal - threshold
            val compressed = threshold + excess / (1f + excess * 2.2f)
            return sign * compressed
        }

        fun softLimit(sample: Float): Float {
            val knee = 0.85f
            val absVal = abs(sample)
            if (absVal <= knee) return sample
            val sign = if (sample >= 0f) 1f else -1f
            val excess = (absVal - knee) / (1f - knee)
            return sign * (knee + (1f - knee) * tanh(excess.toDouble()).toFloat())
        }
    }
}
