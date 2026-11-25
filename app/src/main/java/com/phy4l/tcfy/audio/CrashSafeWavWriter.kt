package com.phy4l.tcfy.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Crash-safe 16kHz 16-bit mono WAV writer.
 *
 * Writes raw PCM continuously to local storage BEFORE any ASR or network logic, and
 * periodically updates the 44-byte RIFF/WAV header + fsyncs so sudden battery loss or
 * MIUI background kills never lose recorded classroom audio.
 */
class CrashSafeWavWriter(
    val outputFile: File,
    val sampleRate: Int = 16000,
    val channels: Int = 1,
    val bitsPerSample: Int = 16,
    private val headerFlushIntervalMs: Long = 5_000L
) : Closeable {

    private val raf: RandomAccessFile
    private var totalDataBytes: Long = 0L
    private var lastHeaderFlushMs: Long = System.currentTimeMillis()
    private var isClosed = false

    init {
        outputFile.parentFile?.mkdirs()
        raf = RandomAccessFile(outputFile, "rw")
        raf.setLength(0L)
        writeWavHeader(0L)
        raf.fd.sync()
    }

    @Synchronized
    fun writePcm16(samples: ShortArray, length: Int = samples.size) {
        if (isClosed || length <= 0) return
        val byteBuffer = ByteBuffer.allocate(length * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until length) {
            byteBuffer.putShort(samples[i])
        }
        val bytes = byteBuffer.array()
        raf.seek(WAV_HEADER_SIZE + totalDataBytes)
        raf.write(bytes)
        totalDataBytes += bytes.size

        val now = System.currentTimeMillis()
        if (now - lastHeaderFlushMs >= headerFlushIntervalMs) {
            flushHeaderInternal(syncDisk = true)
            lastHeaderFlushMs = now
        }
    }

    @Synchronized
    fun flushHeader(syncDisk: Boolean = true) {
        if (isClosed) return
        flushHeaderInternal(syncDisk)
    }

    fun durationMs(): Long {
        val bytesPerSecond = sampleRate.toLong() * channels * (bitsPerSample / 8)
        return if (bytesPerSecond == 0L) 0L else (totalDataBytes * 1000L) / bytesPerSecond
    }

    @Synchronized
    override fun close() {
        if (isClosed) return
        try {
            flushHeaderInternal(syncDisk = true)
        } finally {
            isClosed = true
            raf.close()
        }
    }

    private fun flushHeaderInternal(syncDisk: Boolean) {
        writeWavHeader(totalDataBytes)
        if (syncDisk) {
            raf.fd.sync()
        }
    }

    private fun writeWavHeader(dataSize: Long) {
        val header = buildWavHeader(dataSize, sampleRate, channels, bitsPerSample)
        raf.seek(0L)
        raf.write(header)
    }

    companion object {
        const val WAV_HEADER_SIZE = 44L

        fun buildWavHeader(
            dataSize: Long,
            sampleRate: Int = 16000,
            channels: Int = 1,
            bitsPerSample: Int = 16
        ): ByteArray {
            val byteRate = sampleRate * channels * (bitsPerSample / 8)
            val blockAlign = (channels * (bitsPerSample / 8)).toShort()
            val chunkSize = (36L + dataSize).coerceAtMost(0xFFFFFFFFL).toInt()
            val subchunk2Size = dataSize.coerceAtMost(0xFFFFFFFFL).toInt()

            val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
            buffer.putInt(chunkSize)
            buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
            buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
            buffer.putInt(16) // PCM fmt chunk size
            buffer.putShort(1) // AudioFormat = 1 (PCM)
            buffer.putShort(channels.toShort())
            buffer.putInt(sampleRate)
            buffer.putInt(byteRate)
            buffer.putShort(blockAlign)
            buffer.putShort(bitsPerSample.toShort())
            buffer.put("data".toByteArray(Charsets.US_ASCII))
            buffer.putInt(subchunk2Size)
            return buffer.array()
        }

        /**
         * Repairs an interrupted WAV file whose RIFF/data sizes were not finalized before a crash.
         * Returns true if repaired or already valid, false if file is corrupt/too small.
         */
        fun repairHeaderIfNeeded(
            file: File,
            defaultSampleRate: Int = 16000,
            defaultChannels: Int = 1,
            defaultBitsPerSample: Int = 16
        ): Boolean {
            if (!file.exists() || file.length() < WAV_HEADER_SIZE) return false
            val actualDataSize = (file.length() - WAV_HEADER_SIZE) and 1L.inv() // align to 2-byte PCM16
            RandomAccessFile(file, "rw").use { raf ->
                val headerBytes = ByteArray(44)
                raf.seek(0L)
                raf.readFully(headerBytes)
                val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
                val riff = String(headerBytes, 0, 4, Charsets.US_ASCII)
                val wave = String(headerBytes, 8, 4, Charsets.US_ASCII)
                val recordedDataSize = buf.getInt(40).toLong() and 0xFFFFFFFFL

                if (riff != "RIFF" || wave != "WAVE" || recordedDataSize != actualDataSize) {
                    val sampleRate = if (riff == "RIFF" && wave == "WAVE") {
                        buf.getInt(24).takeIf { it in 8000..48000 } ?: defaultSampleRate
                    } else defaultSampleRate
                    val channels = if (riff == "RIFF" && wave == "WAVE") {
                        buf.getShort(22).toInt().takeIf { it in 1..2 } ?: defaultChannels
                    } else defaultChannels
                    val bps = if (riff == "RIFF" && wave == "WAVE") {
                        buf.getShort(34).toInt().takeIf { it == 16 } ?: defaultBitsPerSample
                    } else defaultBitsPerSample

                    val fixed = buildWavHeader(actualDataSize, sampleRate, channels, bps)
                    raf.seek(0L)
                    raf.write(fixed)
                    raf.fd.sync()
                }
            }
            return true
        }

        /**
         * Reads 16-bit PCM WAV samples as normalized float array [-1, 1] for offline re-transcription.
         */
        fun readWavAsFloat(file: File): Pair<Int, FloatArray> {
            repairHeaderIfNeeded(file)
            val bytes = file.readBytes()
            require(bytes.size >= WAV_HEADER_SIZE) { "WAV file too small: ${file.name}" }
            val headerBuf = ByteBuffer.wrap(bytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
            val sampleRate = headerBuf.getInt(24)
            val dataBytes = bytes.size - 44
            val sampleCount = dataBytes / 2
            val pcmBuf = ByteBuffer.wrap(bytes, 44, sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
            val floats = FloatArray(sampleCount) { pcmBuf.short / 32768f }
            return sampleRate to floats
        }
    }
}
