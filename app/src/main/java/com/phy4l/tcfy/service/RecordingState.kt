package com.phy4l.tcfy.service

data class RecordingUiState(
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val isInitializing: Boolean = false,
    val activeSessionId: Long? = null,
    val elapsedMs: Long = 0L,
    val inputDbfs: Float = -90f,
    val outputDbfs: Float = -90f,
    val currentAgcGainDb: Float = 0f,
    val isSpeechActive: Boolean = false,
    val partialTranscript: String = "",
    val activeAudioSourceLabel: String = "",
    val asrModeLabel: String = "",
    val lastInferenceMs: Long = 0L,
    val autoTuneSummary: String? = null,
    val isBuildingLiveGlossary: Boolean = false,
    val liveGlossaryStatus: String? = null,
    val errorMessage: String? = null,
    val warningMessage: String? = null
)
