package com.phy4l.tcfy.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Captures 16kHz mono PCM16 audio tailored for 3-10m classroom far-field scenarios.
 *
 * Automatically chooses UNPROCESSED (when supported) or CAMCORDER (top far-field mic on Redmi)
 * so vendor near-field noise suppression does not mute distant lecturer speech.
 */
class ClassroomAudioCapturer(
    val sampleRate: Int = 16000,
    val frameSamples: Int = 320 // 20ms frame at 16kHz
) {
    data class CaptureSessionInfo(
        val requestedMode: AudioSourceMode,
        val activeAndroidSource: Int,
        val activeSourceLabel: String
    )

    @Volatile
    var activeSessionInfo: CaptureSessionInfo? = null
        private set

    @SuppressLint("MissingPermission")
    suspend fun captureLoop(
        sourceMode: AudioSourceMode,
        isPaused: () -> Boolean,
        shouldStop: () -> Boolean = { false },
        onPcmFrame: suspend (samples: ShortArray, count: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val minBufBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(frameSamples * 2 * 4)

        // Larger ring buffer (at least 400ms) avoids audio dropouts during GC or ASR bursts
        val bufferSizeBytes = max(minBufBytes * 2, sampleRate * 2 * 400 / 1000)

        val (recorder, info) = openBestAudioRecord(sourceMode, bufferSizeBytes)
        activeSessionInfo = info

        val readBuffer = ShortArray(frameSamples)
        try {
            recorder.startRecording()
            while (currentCoroutineContext().isActive && !shouldStop()) {
                val read = recorder.read(readBuffer, 0, readBuffer.size)
                if (shouldStop()) break
                if (read > 0) {
                    if (!isPaused()) {
                        onPcmFrame(readBuffer, read)
                    }
                } else if (read < 0) {
                    if (!currentCoroutineContext().isActive || shouldStop()) break
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } finally {
            runCatching {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
            }
            runCatching { recorder.release() }
            activeSessionInfo = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun openBestAudioRecord(
        mode: AudioSourceMode,
        bufferSizeBytes: Int
    ): Pair<AudioRecord, CaptureSessionInfo> {
        val candidates: List<Pair<Int, String>> = when (mode) {
            AudioSourceMode.FAR_FIELD_AUTO -> buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    add(MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED (原始远场)")
                }
                add(MediaRecorder.AudioSource.CAMCORDER to "CAMCORDER (顶部远场麦)")
                add(MediaRecorder.AudioSource.MIC to "MIC (标准麦)")
            }
            AudioSourceMode.UNPROCESSED -> buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    add(MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED (原始远场)")
                }
                add(MediaRecorder.AudioSource.CAMCORDER to "CAMCORDER (回退)")
                add(MediaRecorder.AudioSource.MIC to "MIC (回退)")
            }
            AudioSourceMode.CAMCORDER -> listOf(
                MediaRecorder.AudioSource.CAMCORDER to "CAMCORDER (顶部远场麦)",
                MediaRecorder.AudioSource.MIC to "MIC (回退)"
            )
            AudioSourceMode.MIC -> listOf(
                MediaRecorder.AudioSource.MIC to "MIC (标准麦)"
            )
        }

        var lastError: Throwable? = null
        for ((src, label) in candidates) {
            try {
                val record = AudioRecord(
                    src,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSizeBytes
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    return record to CaptureSessionInfo(mode, src, label)
                } else {
                    record.release()
                }
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw IllegalStateException("无法初始化麦克风音频采集器 (${mode.name})", lastError)
    }
}
