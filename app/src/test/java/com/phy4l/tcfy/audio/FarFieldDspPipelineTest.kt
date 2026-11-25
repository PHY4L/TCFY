package com.phy4l.tcfy.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class FarFieldDspPipelineTest {

    @Test
    fun highPassFilterAttenuatesLowFrequencyHumAndPreservesSpeechBand() {
        val dsp = FarFieldDspPipeline(
            sampleRate = 16000,
            initialConfig = FarFieldDspConfig(
                highPassCutoffHz = 90f,
                enableHighPass = true,
                enableAgc = false,
                denoiseLevel = DenoiseLevel.OFF
            )
        )

        // Generate 40Hz hum (200ms) and verify steady-state attenuation
        val hum = FloatArray(3200) { i -> 0.5f * sin(2.0 * PI * 40.0 * i / 16000.0).toFloat() }
        val humOut = dsp.processFloat(hum)
        val humRmsIn = FarFieldDspPipeline.computeRms(hum.copyOfRange(1600, 3200))
        val humRmsOut = FarFieldDspPipeline.computeRms(humOut.copyOfRange(1600, 3200))
        assertTrue("40Hz hum should be strongly attenuated: in=$humRmsIn out=$humRmsOut", humRmsOut < humRmsIn * 0.45f)

        // Reset and feed 1000Hz speech-band tone -> should pass almost untouched
        dsp.reset()
        val speech = FloatArray(3200) { i -> 0.3f * sin(2.0 * PI * 1000.0 * i / 16000.0).toFloat() }
        val speechOut = dsp.processFloat(speech)
        val speechRmsIn = FarFieldDspPipeline.computeRms(speech.copyOfRange(1600, 3200))
        val speechRmsOut = FarFieldDspPipeline.computeRms(speechOut.copyOfRange(1600, 3200))
        assertTrue("1kHz speech should be preserved: in=$speechRmsIn out=$speechRmsOut", speechRmsOut > speechRmsIn * 0.90f)
    }

    @Test
    fun adaptiveAgcBoostsWeakFarFieldSpeechWithoutClipping() {
        val dsp = FarFieldDspPipeline(
            sampleRate = 16000,
            initialConfig = FarFieldDspConfig(
                enableHighPass = true,
                enableAgc = true,
                targetRmsDbfs = -18f,
                maxGainDb = 24f,
                agcSilenceFloorDbfs = -54f,
                denoiseLevel = DenoiseLevel.OFF
            )
        )

        // Simulate weak far-field lecturer speech (~ -36 dBFS, 1kHz tone) over 30 frames (600ms)
        val frameSize = 320
        var lastOut = FloatArray(frameSize)
        var lastInRms = 0f
        for (f in 0 until 35) {
            val frame = FloatArray(frameSize) { i ->
                val sampleIdx = f * frameSize + i
                0.02f * sin(2.0 * PI * 800.0 * sampleIdx / 16000.0).toFloat()
            }
            lastInRms = FarFieldDspPipeline.computeRms(frame)
            lastOut = dsp.processFloat(frame)
        }
        val boostedRms = FarFieldDspPipeline.computeRms(lastOut)
        assertTrue("Weak speech should be boosted significantly: in=$lastInRms out=$boostedRms", boostedRms > lastInRms * 3.0f)

        // Sudden nearby cough/desk-knock spike should be soft-limited within [-1, 1]
        val spike = FloatArray(frameSize) { 0.95f }
        val limited = dsp.processFloat(spike)
        assertTrue("Soft limiter must keep samples <= 1.0", limited.all { abs(it) <= 1.0f })
    }

    @Test
    fun dryWetEnhancerBlendingWorksWhenEnhancerIsAttached() {
        val fakeEnhancer = object : SpeechEnhancer {
            override fun enhance(samples: FloatArray, sampleRate: Int): FloatArray =
                FloatArray(samples.size) { samples[it] * 0.5f }
        }
        val dsp = FarFieldDspPipeline(
            sampleRate = 16000,
            initialConfig = FarFieldDspConfig(
                enableHighPass = false,
                enableAgc = false,
                denoiseLevel = DenoiseLevel.LIGHT // wetMix = 0.55f
            ),
            speechEnhancer = fakeEnhancer
        )
        val input = FloatArray(160) { 0.4f }
        val output = dsp.processFloat(input)
        // dry(0.45 * 0.4) + wet(0.55 * 0.2) = 0.18 + 0.11 = 0.29
        assertTrue("Expected ~0.29 blended sample, got ${output[0]}", abs(output[0] - 0.29f) < 0.01f)
    }
}
