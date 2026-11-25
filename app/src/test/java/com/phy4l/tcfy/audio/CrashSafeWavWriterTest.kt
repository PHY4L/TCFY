package com.phy4l.tcfy.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

class CrashSafeWavWriterTest {

    @Test
    fun writesValidWavAndReadsBackExactSamples() {
        val tmp = File.createTempFile("tcfy_test_", ".wav")
        try {
            val samples = ShortArray(1600) { idx -> ((idx % 100) * 200 - 10000).toShort() }
            CrashSafeWavWriter(tmp, sampleRate = 16000).use { writer ->
                writer.writePcm16(samples)
                assertEquals(100L, writer.durationMs())
            }

            val (sr, recovered) = CrashSafeWavWriter.readWavAsFloat(tmp)
            assertEquals(16000, sr)
            assertEquals(samples.size, recovered.size)
            for (i in samples.indices) {
                val expected = samples[i] / 32768f
                assertTrue(abs(expected - recovered[i]) < 1e-4f)
            }
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun repairsInterruptedWavWithZeroDataSizeHeader() {
        val tmp = File.createTempFile("tcfy_crash_", ".wav")
        try {
            // Write valid header with size=0, then append raw PCM simulating sudden power loss without close()
            val pcmSamples = ShortArray(800) { idx -> (idx * 10).toShort() }
            CrashSafeWavWriter(tmp, sampleRate = 16000, headerFlushIntervalMs = 60_000L).use { writer ->
                writer.writePcm16(pcmSamples)
            }
            // Intentionally corrupt header data size to 0 to simulate hard kill before final header flush
            RandomAccessFile(tmp, "rw").use { raf ->
                raf.seek(0L)
                raf.write(CrashSafeWavWriter.buildWavHeader(0L, 16000, 1, 16))
            }

            val repaired = CrashSafeWavWriter.repairHeaderIfNeeded(tmp)
            assertTrue(repaired)

            val (sr, recovered) = CrashSafeWavWriter.readWavAsFloat(tmp)
            assertEquals(16000, sr)
            assertEquals(800, recovered.size)
            assertTrue(abs(recovered[500] - (5000 / 32768f)) < 1e-4f)
        } finally {
            tmp.delete()
        }
    }
}
