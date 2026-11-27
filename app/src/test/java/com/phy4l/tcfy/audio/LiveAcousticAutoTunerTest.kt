package com.phy4l.tcfy.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAcousticAutoTunerTest {

    @Test
    fun diagnosesUltraSoftVoiceBelowMinus50DbfsAndBoostsGainAndVadSensitivity() {
        val tuner = LiveAcousticAutoTuner()
        repeat(80) { i ->
            val level = if (i % 3 == 0) -53f else -68f
            tuner.recordFrame(inputDbfs = level, noiseFloorDbfs = -70f, clipRatio = 0f, isSpeech = i % 3 == 0)
        }
        val decision = tuner.evaluateAndTune(AcousticPreset.CLASSROOM_FAR_FIELD.config, minFrames = 40)
        assertNotNull(decision)
        assertEquals(DiagnosedAcousticScene.ULTRA_SOFT_FAR_FIELD, decision!!.scene)
        assertTrue("Expected maxGainDb >= 36 dB, got ${decision.recommendedConfig.maxGainDb}", decision.recommendedConfig.maxGainDb >= 36f)
        assertTrue("Expected sensitive VAD <= 0.18, got ${decision.recommendedConfig.vadThreshold}", decision.recommendedConfig.vadThreshold <= 0.18f)
        assertTrue(decision.needsVadRebuild)
    }

    @Test
    fun diagnosesPaSpeakerClippingAndEnablesAntiResonanceWithLowerGain() {
        val tuner = LiveAcousticAutoTuner()
        repeat(80) { i ->
            val clipping = i % 10 == 0
            tuner.recordFrame(
                inputDbfs = if (clipping) -8f else -24f,
                noiseFloorDbfs = -44f,
                clipRatio = if (clipping) 0.08f else 0f,
                isSpeech = true
            )
        }
        val decision = tuner.evaluateAndTune(AcousticPreset.CLASSROOM_FAR_FIELD.config, minFrames = 40)
        assertNotNull(decision)
        assertEquals(DiagnosedAcousticScene.PA_REVERBERANT, decision!!.scene)
        assertTrue(decision.recommendedConfig.paAntiResonance)
        assertEquals(125f, decision.recommendedConfig.highPassCutoffHz, 0.1f)
        assertTrue(decision.recommendedConfig.maxGainDb <= 14f)
    }

    @Test
    fun diagnosesFastSpeakerAndEnablesShortSilenceFastSpeechMode() {
        val tuner = LiveAcousticAutoTuner()
        repeat(80) {
            tuner.recordFrame(inputDbfs = -28f, noiseFloorDbfs = -60f, clipRatio = 0f, isSpeech = true)
        }
        // Simulate 4 continuous long chunks with forced splits and fast speaking density (~6 chars/sec)
        repeat(4) {
            tuner.recordAsrChunk(
                durationSec = 10f,
                wasForcedSplit = true,
                acceptedText = "今天我们快速回顾一下线性代数中的奇异值分解与特征值分解在主成分分析中的应用场景以及具体推导过程和数值稳定性问题"
            )
        }
        val decision = tuner.evaluateAndTune(AcousticPreset.CLASSROOM_FAR_FIELD.config, minFrames = 40)
        assertNotNull(decision)
        assertEquals(DiagnosedAcousticScene.FAST_OR_SLURRED_SPEECH, decision!!.scene)
        assertTrue(decision.recommendedConfig.fastSpeechMode)
        assertTrue(decision.recommendedConfig.vadMinSilenceSec <= 0.40f)
        assertTrue(decision.recommendedConfig.vadMaxSpeechSec <= 10.0f)
    }

    @Test
    fun returnsNullWhenInsufficientTelemetryCollected() {
        val tuner = LiveAcousticAutoTuner()
        repeat(10) {
            tuner.recordFrame(-35f, -60f, 0f, true)
        }
        assertNull(tuner.evaluateAndTune(AcousticPreset.AUTO.config, minFrames = 40))
    }

    @Test
    fun diagnosesNoisyLowSnrWithHighEmptyAsrRate() {
        val tuner = LiveAcousticAutoTuner()
        repeat(80) {
            tuner.recordFrame(inputDbfs = -38f, noiseFloorDbfs = -44f, clipRatio = 0f, isSpeech = true)
        }
        repeat(5) { idx ->
            tuner.recordAsrChunk(durationSec = 2.0f, wasForcedSplit = false, acceptedText = if (idx == 0) "好的" else null)
        }
        val decision = tuner.evaluateAndTune(AcousticPreset.CLASSROOM_FAR_FIELD.config, minFrames = 40)
        assertNotNull(decision)
        assertEquals(DiagnosedAcousticScene.NOISY_LOW_SNR, decision!!.scene)
        assertEquals(DenoiseLevel.STANDARD, decision.recommendedConfig.denoiseLevel)
        assertFalse(decision.recommendedConfig.paAntiResonance)
    }
}
