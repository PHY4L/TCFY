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

        // Simulate weak far-field lecturer speech (~ -36 dBFS, 1kHz tone) over 35 frames (700ms)
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
    fun ultraSoftVoiceBelowMinus50DbfsIsBoostedEvenWithNeuralDenoiser() {
        // Simulates a GTCRN denoiser that aggressively suppresses anything < -45 dBFS
        val strictDenoiser = object : SpeechEnhancer {
            override fun enhance(samples: FloatArray, sampleRate: Int): FloatArray {
                val rmsDb = FarFieldDspPipeline.computeRmsDbfs(samples)
                return if (rmsDb < -45f) FloatArray(samples.size) else samples.copyOf()
            }
        }
        val dsp = FarFieldDspPipeline(
            sampleRate = 16000,
            initialConfig = AcousticPreset.SOFT_VOICE_ULTRA.config,
            speechEnhancer = strictDenoiser
        )

        // Feed quiet room floor (~ -72 dBFS) for 15 frames, then ultra-soft speaker (~ -56 dBFS) for 35 frames
        val frameSize = 320
        for (f in 0 until 15) {
            val floor = FloatArray(frameSize) { i -> 0.0003f * sin(2.0 * PI * 300.0 * i / 16000.0).toFloat() }
            dsp.processFloat(floor)
        }
        var lastOut = FloatArray(frameSize)
        var inputDbfs = -90f
        for (f in 0 until 35) {
            val softVoice = FloatArray(frameSize) { i ->
                val idx = f * frameSize + i
                0.0022f * sin(2.0 * PI * 900.0 * idx / 16000.0).toFloat() // ~ -56 dBFS
            }
            inputDbfs = FarFieldDspPipeline.computeRmsDbfs(softVoice)
            lastOut = dsp.processFloat(softVoice)
        }
        val outputDbfs = FarFieldDspPipeline.computeRmsDbfs(lastOut)
        assertTrue("Input should be below -50 dBFS (was $inputDbfs)", inputDbfs < -50f)
        assertTrue(
            "Pre-denoiser leveling + adaptive AGC must lift <-50dBFS speech above -32 dBFS (was $outputDbfs)",
            outputDbfs > -32f
        )
    }

    @Test
    fun paAntiResonanceCuts280HzMudBoosts2600HzClarityAndTracksClipping() {
        val dsp = FarFieldDspPipeline(
            sampleRate = 16000,
            initialConfig = AcousticPreset.LECTURE_HALL_PA.config.copy(
                enableAgc = false,
                denoiseLevel = DenoiseLevel.OFF
            )
        )

        // 280 Hz PA boxiness should be attenuated
        val mud = FloatArray(3200) { i -> 0.25f * sin(2.0 * PI * 280.0 * i / 16000.0).toFloat() }
        val mudOut = dsp.processFloat(mud)
        val mudInRms = FarFieldDspPipeline.computeRms(mud.copyOfRange(1600, 3200))
        val mudOutRms = FarFieldDspPipeline.computeRms(mudOut.copyOfRange(1600, 3200))
        assertTrue("280Hz PA mud should be attenuated: in=$mudInRms out=$mudOutRms", mudOutRms < mudInRms * 0.85f)

        // 2600 Hz consonant presence band should be gently boosted
        dsp.reset()
        val pres = FloatArray(3200) { i -> 0.15f * sin(2.0 * PI * 2600.0 * i / 16000.0).toFloat() }
        val presOut = dsp.processFloat(pres)
        val presInRms = FarFieldDspPipeline.computeRms(pres.copyOfRange(1600, 3200))
        val presOutRms = FarFieldDspPipeline.computeRms(presOut.copyOfRange(1600, 3200))
        assertTrue("2.6kHz presence should be boosted: in=$presInRms out=$presOutRms", presOutRms > presInRms * 1.10f)

        // Loud clipping PA burst should report clipRatio > 0 and stay bounded <= 1.0
        val hotBurst = FloatArray(320) { if (it % 2 == 0) 0.98f else -0.98f }
        val burstOut = dsp.processFloat(hotBurst)
        assertTrue("Clipping telemetry should detect hot PA burst", dsp.lastClipRatio > 0.5f)
        assertTrue("Output must stay bounded <= 1.0", burstOut.all { abs(it) <= 1.0f })
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
                denoiseLevel = DenoiseLevel.LIGHT // wetMix = 0.48f
            ),
            speechEnhancer = fakeEnhancer
        )
        val input = FloatArray(160) { 0.4f }
        val output = dsp.processFloat(input)
        // dry(0.52 * 0.4) + wet(0.48 * 0.2) = 0.208 + 0.096 = 0.304
        assertTrue("Expected ~0.304 blended sample, got ${output[0]}", abs(output[0] - 0.304f) < 0.01f)
    }
}
