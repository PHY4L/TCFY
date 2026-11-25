package com.phy4l.tcfy.service

import android.content.Context
import com.phy4l.tcfy.ai.TranslationRouter
import com.phy4l.tcfy.asr.ModelCatalog
import com.phy4l.tcfy.asr.ModelManager
import com.phy4l.tcfy.asr.OfflineAsrEngine
import com.phy4l.tcfy.asr.SherpaGtcrnEnhancer
import com.phy4l.tcfy.asr.SherpaSenseVoiceEngine
import com.phy4l.tcfy.asr.SherpaSileroVadEngine
import com.phy4l.tcfy.asr.VadSegmenter
import com.phy4l.tcfy.asr.VadSpeechChunk
import com.phy4l.tcfy.audio.ClassroomAudioCapturer
import com.phy4l.tcfy.audio.CrashSafeWavWriter
import com.phy4l.tcfy.audio.DenoiseLevel
import com.phy4l.tcfy.audio.FarFieldDspConfig
import com.phy4l.tcfy.audio.FarFieldDspPipeline
import com.phy4l.tcfy.audio.SpeechEnhancer
import com.phy4l.tcfy.data.db.LectureSessionEntity
import com.phy4l.tcfy.data.db.SegmentSyncStatus
import com.phy4l.tcfy.data.db.TcfyDatabase
import com.phy4l.tcfy.data.db.TranscriptSegmentEntity
import com.phy4l.tcfy.data.prefs.AppSettings
import com.phy4l.tcfy.data.prefs.AppSettingsRepository
import com.phy4l.tcfy.glossary.GlossaryTerm
import com.phy4l.tcfy.glossary.OfflineTermCorrector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Coordinates the full local-first classroom recording pipeline:
 *
 * AudioRecord (UNPROCESSED/CAMCORDER)
 *   ├─► CrashSafeWavWriter (raw 16kHz PCM continuously persisted to disk)
 *   └─► FarFieldDspPipeline (HPF + GTCRN + Adaptive AGC + Soft Limiter)
 *         └─► Silero VAD -> bounded Channel -> SenseVoice-Small int8
 *               └─► OfflineTermCorrector (aliases + pinyin/fuzzy)
 *                     └─► TranslationRouter (offline-safe + optional online)
 *                           └─► Room SQLite (TranscriptSegmentEntity)
 */
class RecordingCoordinator(
    private val context: Context,
    private val database: TcfyDatabase,
    private val settingsRepo: AppSettingsRepository,
    private val modelManager: ModelManager,
    private val translationRouter: TranslationRouter
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { encodeDefaults = true }

    private val _state = MutableStateFlow(RecordingUiState())
    val state: StateFlow<RecordingUiState> = _state.asStateFlow()

    private val lifecycleMutex = Mutex()
    private val isPausedFlag = AtomicBoolean(false)
    private val isStoppingFlag = AtomicBoolean(false)
    private val isMainAsrBusy = AtomicBoolean(false)
    private val bookmarkNextFlag = AtomicBoolean(false)
    private val segmentIndexCounter = AtomicInteger(0)

    private var captureJob: Job? = null
    private var asrWorkerJob: Job? = null
    private var partialPreviewJob: Job? = null
    private var timerJob: Job? = null
    private var dspPipeline: FarFieldDspPipeline? = null

    private val recordingsDir: File = File(context.filesDir, "recordings").apply { mkdirs() }

    /**
     * Scans any previously interrupted WAV recordings on startup, fixes their RIFF headers,
     * and backfills durationMs in SQLite if a previous recording was stopped abruptly.
     */
    fun repairInterruptedRecordingsAsync() {
        scope.launch(Dispatchers.IO) {
            recordingsDir.listFiles { f -> f.extension.equals("wav", ignoreCase = true) }
                ?.forEach { wav ->
                    runCatching { CrashSafeWavWriter.repairHeaderIfNeeded(wav) }
                }

            runCatching {
                val sessions = database.lectureDao().getAllSessionsOnce()
                for (session in sessions) {
                    if (session.durationMs <= 0L || session.isRecording) {
                        val wavFile = File(session.audioFilePath)
                        if (wavFile.exists() && wavFile.length() > CrashSafeWavWriter.WAV_HEADER_SIZE) {
                            CrashSafeWavWriter.repairHeaderIfNeeded(wavFile)
                            val dataBytes = (wavFile.length() - CrashSafeWavWriter.WAV_HEADER_SIZE).coerceAtLeast(0L)
                            // 16kHz * 1ch * 2 bytes = 32,000 bytes/sec
                            val computedMs = (dataBytes * 1000L) / 32_000L
                            database.lectureDao().finalizeSession(
                                sessionId = session.id,
                                endedAtMs = if (session.endedAtMs > 0L) session.endedAtMs else session.startedAtMs + computedMs,
                                durationMs = computedMs
                            )
                        } else if (session.isRecording) {
                            database.lectureDao().finalizeSession(
                                sessionId = session.id,
                                endedAtMs = System.currentTimeMillis(),
                                durationMs = session.durationMs
                            )
                        }
                    }
                }
            }
        }
    }

    fun updateLiveDspConfig(newConfig: FarFieldDspConfig) {
        dspPipeline?.updateConfig(newConfig)
    }

    fun requestBookmarkCurrentOrNext() {
        val sessionId = _state.value.activeSessionId ?: return
        scope.launch(Dispatchers.IO) {
            val segments = database.lectureDao().getSegments(sessionId)
            val latest = segments.lastOrNull()
            if (latest != null && !latest.isBookmarked) {
                database.lectureDao().setBookmarked(latest.id, true)
            } else {
                bookmarkNextFlag.set(true)
            }
        }
    }

    fun togglePause() {
        if (!_state.value.isRecording) return
        val nextPaused = !isPausedFlag.get()
        isPausedFlag.set(nextPaused)
        _state.update { it.copy(isPaused = nextPaused) }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    suspend fun startSession(customTitle: String? = null): Result<Long> = lifecycleMutex.withLock {
        if (_state.value.isRecording || _state.value.isInitializing) {
            return@withLock Result.failure(IllegalStateException("正在录制中"))
        }

        _state.value = RecordingUiState(isInitializing = true)
        isPausedFlag.set(false)
        isStoppingFlag.set(false)
        isMainAsrBusy.set(false)
        bookmarkNextFlag.set(false)
        segmentIndexCounter.set(0)

        var asrEngine: OfflineAsrEngine? = null
        var vadEngine: VadSegmenter? = null
        var gtcrnEnhancer: SpeechEnhancer? = null

        return@withLock runCatching {
            val settings = settingsRepo.settingsFlow.first()
            val activePacks = database.glossaryDao().getActivePacks()
            val glossaryNames = activePacks.joinToString("、") { it.name }

            val now = System.currentTimeMillis()
            val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(now))
            val fileStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(now))
            val title = customTitle?.takeIf { it.isNotBlank() } ?: "课堂记录 $stamp"
            val wavFile = File(recordingsDir, "lecture_$fileStamp.wav")

            var warningMsg: String? = null
            var asrLabel = "仅录音保底（待下载离线模型）"

            if (modelManager.isCoreAsrReady()) {
                asrEngine = withContext(Dispatchers.IO) {
                    SherpaSenseVoiceEngine(
                        modelFile = modelManager.getPrimaryModelFile(ModelCatalog.SENSEVOICE_INT8),
                        tokensFile = modelManager.getTokensFile(ModelCatalog.SENSEVOICE_INT8),
                        languageMode = settings.asrLanguageMode,
                        numThreads = settings.asrThreads
                    )
                }
                if (isStoppingFlag.get()) {
                    throw CancellationException("初始化期间用户已取消录音")
                }
                vadEngine = withContext(Dispatchers.IO) {
                    SherpaSileroVadEngine(
                        sileroModelFile = modelManager.getPrimaryModelFile(ModelCatalog.SILERO_VAD),
                        threshold = settings.dspConfig.vadThreshold,
                        minSilenceDurationSec = settings.dspConfig.vadMinSilenceSec,
                        maxSpeechDurationSec = settings.dspConfig.vadMaxSpeechSec
                    )
                }
                if (isStoppingFlag.get()) {
                    throw CancellationException("初始化期间用户已取消录音")
                }
                if (settings.dspConfig.denoiseLevel != DenoiseLevel.OFF &&
                    modelManager.isInstalled(ModelCatalog.GTCRN_DENOISER)
                ) {
                    gtcrnEnhancer = runCatching {
                        withContext(Dispatchers.IO) {
                            SherpaGtcrnEnhancer(modelManager.getPrimaryModelFile(ModelCatalog.GTCRN_DENOISER))
                        }
                    }.getOrNull()
                }
                asrLabel = if (gtcrnEnhancer != null) {
                    "离线 SenseVoice + GTCRN 远场增强"
                } else {
                    "离线 SenseVoice (DSP 远场增益)"
                }
            } else {
                warningMsg = "未安装离线语音模型：当前已开启原始远场录音保底，课后在「模型设置」下载模型即可一键离线转写。"
            }

            if (isStoppingFlag.get()) {
                throw CancellationException("初始化期间用户已取消录音")
            }

            val sessionId = database.lectureDao().insertSession(
                LectureSessionEntity(
                    title = title,
                    audioFilePath = wavFile.absolutePath,
                    startedAtMs = now,
                    asrLanguageCode = settings.asrLanguageMode.code,
                    translationDirection = settings.translationDirection.name,
                    acousticPresetName = settings.acousticPresetName,
                    isRecording = true,
                    glossarySnapshotName = glossaryNames
                )
            )

            val loadedAsr = asrEngine
            val loadedVad = vadEngine
            val loadedGtcrn = gtcrnEnhancer
            val wavWriter = CrashSafeWavWriter(wavFile)
            val pipeline = FarFieldDspPipeline(
                sampleRate = 16000,
                initialConfig = settings.dspConfig,
                speechEnhancer = loadedGtcrn
            )
            dspPipeline = pipeline

            val chunkChannel = Channel<VadSpeechChunk>(capacity = 32)
            val previewChannel = Channel<FloatArray>(capacity = Channel.CONFLATED)
            val capturer = ClassroomAudioCapturer(sampleRate = 16000, frameSamples = 320)

            _state.value = RecordingUiState(
                isRecording = true,
                isPaused = false,
                isInitializing = false,
                activeSessionId = sessionId,
                elapsedMs = 0L,
                asrModeLabel = asrLabel,
                warningMessage = warningMsg
            )

            // Elapsed timer
            timerJob = scope.launch {
                while (isActive) {
                    delay(500L)
                    if (!isPausedFlag.get()) {
                        _state.update { it.copy(elapsedMs = wavWriter.durationMs()) }
                    }
                }
            }

            // Dedicated low-priority partial preview worker so AudioRecord capture thread NEVER blocks
            partialPreviewJob = scope.launch(Dispatchers.Default) {
                if (loadedAsr == null) return@launch
                for (previewSlice in previewChannel) {
                    if (isStoppingFlag.get() || isMainAsrBusy.get()) continue
                    val previewRes = runCatching { loadedAsr.transcribe(previewSlice, 16000) }.getOrNull()
                    if (previewRes != null &&
                        !isStoppingFlag.get() &&
                        _state.value.isSpeechActive &&
                        SherpaSenseVoiceEngine.isMeaningfulSpeech(
                            previewRes.text,
                            previewRes.detectedLanguage,
                            settings.asrLanguageMode
                        )
                    ) {
                        _state.update { it.copy(partialTranscript = previewRes.text) }
                    }
                }
            }

            // ASR + Glossary + Translation consumer coroutine
            asrWorkerJob = scope.launch(Dispatchers.Default) {
                try {
                    if (loadedAsr != null) {
                        for (chunk in chunkChannel) {
                            isMainAsrBusy.set(true)
                            try {
                                processSpeechChunk(
                                    sessionId = sessionId,
                                    chunk = chunk,
                                    asrEngine = loadedAsr,
                                    settingsSnapshot = settingsRepo.settingsFlow.first(),
                                    activeTerms = database.glossaryDao().getActiveEntries().map { it.toDomain() }
                                )
                            } finally {
                                isMainAsrBusy.set(false)
                            }
                        }
                    }
                } finally {
                    isMainAsrBusy.set(false)
                    withTimeoutOrNull(1_500L) {
                        runCatching { partialPreviewJob?.join() }
                    }
                    runCatching { loadedAsr?.close() }
                }
            }

            // Audio capture + WAV persistence + DSP + Silero VAD producer coroutine
            captureJob = scope.launch(Dispatchers.IO) {
                val partialBuffer = FloatArray(16000 * 6)
                var partialCount = 0
                var lastPartialDispatchMs = 0L
                var frameCounter = 0

                try {
                    capturer.captureLoop(
                        sourceMode = settings.dspConfig.audioSourceMode,
                        isPaused = { isPausedFlag.get() },
                        shouldStop = { isStoppingFlag.get() },
                        onPcmFrame = { pcm, count ->
                            // 1. Always persist raw untouched audio to disk FIRST
                            wavWriter.writePcm16(pcm, count)

                            // 2. Run software far-field DSP + optional GTCRN
                            val enhanced = pipeline.processPcm16(pcm, count)

                            frameCounter++
                            if (frameCounter % 5 == 0) {
                                val srcLabel = capturer.activeSessionInfo?.activeSourceLabel.orEmpty()
                                _state.update {
                                    it.copy(
                                        inputDbfs = pipeline.lastInputDbfs,
                                        outputDbfs = pipeline.lastOutputDbfs,
                                        currentAgcGainDb = pipeline.lastGainDb,
                                        activeAudioSourceLabel = srcLabel
                                    )
                                }
                            }

                            // 3. Feed Silero VAD
                            if (loadedVad != null) {
                                loadedVad.acceptSamples(enhanced)
                                val speechNow = loadedVad.isSpeechDetected()
                                if (speechNow != _state.value.isSpeechActive) {
                                    _state.update { it.copy(isSpeechActive = speechNow) }
                                }

                                if (speechNow) {
                                    val copyLen = minOf(enhanced.size, partialBuffer.size - partialCount)
                                    if (copyLen > 0) {
                                        enhanced.copyInto(partialBuffer, partialCount, 0, copyLen)
                                        partialCount += copyLen
                                    }
                                    val nowMs = System.currentTimeMillis()
                                    if (partialCount >= 16000 * 2 && nowMs - lastPartialDispatchMs > 1800L && loadedAsr != null) {
                                        lastPartialDispatchMs = nowMs
                                        // Non-blocking send to CONFLATED channel (zero stalls on audio thread)
                                        previewChannel.trySend(partialBuffer.copyOf(partialCount))
                                    }
                                } else {
                                    partialCount = 0
                                }

                                while (true) {
                                    val seg = loadedVad.pollSegment() ?: break
                                    partialCount = 0
                                    _state.update { it.copy(partialTranscript = "") }
                                    chunkChannel.trySend(seg)
                                }
                            }
                        }
                    )
                    if (loadedVad != null) {
                        val remaining = runCatching { loadedVad.flush() }.getOrDefault(emptyList())
                        remaining.forEach { chunkChannel.trySend(it) }
                    }
                } catch (ce: CancellationException) {
                    if (loadedVad != null) {
                        val remaining = runCatching { loadedVad.flush() }.getOrDefault(emptyList())
                        remaining.forEach { chunkChannel.trySend(it) }
                    }
                    throw ce
                } catch (t: Throwable) {
                    _state.update { it.copy(errorMessage = "录音异常: ${t.message}") }
                } finally {
                    // CRITICAL: Must run in NonCancellable so cancelling captureJob never aborts
                    // DB duration persistence, error preservation, or UI state reset!
                    withContext(NonCancellable) {
                        previewChannel.close()
                        partialPreviewJob?.cancel()
                        chunkChannel.close()
                        val finalDuration = wavWriter.durationMs()
                        runCatching { wavWriter.close() }
                        runCatching { loadedVad?.close() }
                        runCatching { loadedGtcrn?.close() }
                        dspPipeline = null

                        // Wait briefly for remaining queued ASR segments to finish writing to DB
                        withTimeoutOrNull(4_000L) {
                            runCatching { asrWorkerJob?.join() }
                        }
                        runCatching {
                            database.lectureDao().finalizeSession(
                                sessionId = sessionId,
                                endedAtMs = System.currentTimeMillis(),
                                durationMs = finalDuration
                            )
                        }
                        timerJob?.cancel()
                        timerJob = null
                        partialPreviewJob = null
                        isPausedFlag.set(false)
                        isStoppingFlag.set(false)
                        val preservedError = _state.value.errorMessage
                        _state.value = RecordingUiState(errorMessage = preservedError)
                    }
                }
            }

            sessionId
        }.onFailure { err ->
            // Clean up any native models loaded before init cancellation or failure
            runCatching { asrEngine?.close() }
            runCatching { vadEngine?.close() }
            runCatching { gtcrnEnhancer?.close() }
            isStoppingFlag.set(false)
            if (err is CancellationException) {
                _state.value = RecordingUiState()
            } else {
                _state.value = RecordingUiState(errorMessage = err.message ?: "启动录音失败")
            }
        }
    }

    suspend fun stopSession() {
        // Set stop flag immediately BEFORE acquiring mutex so any in-progress startSession() aborts cleanly
        isStoppingFlag.set(true)
        lifecycleMutex.withLock {
            timerJob?.cancel()
            timerJob = null
            val job = captureJob
            captureJob = null
            try {
                if (job != null) {
                    val finishedCleanly = withTimeoutOrNull(2_500L) {
                        job.join()
                        true
                    } ?: false
                    if (!finishedCleanly) {
                        job.cancel()
                        withTimeoutOrNull(3_000L) {
                            runCatching { job.join() }
                        }
                    }
                }
            } finally {
                partialPreviewJob?.cancel()
                partialPreviewJob = null
                asrWorkerJob = null
                isPausedFlag.set(false)
                isStoppingFlag.set(false)
                val preservedError = _state.value.errorMessage
                _state.value = RecordingUiState(errorMessage = preservedError)
            }
        }
    }

    /**
     * Re-transcribes an already recorded WAV file 100% offline using the current DSP preset,
     * SenseVoice model, and active course glossary.
     */
    suspend fun retranscribeSessionOffline(
        sessionId: Long,
        onProgress: (Float, String) -> Unit
    ): Result<Int> = withContext(Dispatchers.Default) {
        if (!modelManager.isCoreAsrReady()) {
            return@withContext Result.failure(IllegalStateException("请先在「模型与设置」下载 SenseVoice 与 Silero VAD 离线模型"))
        }

        runCatching {
            val session = database.lectureDao().getSession(sessionId)
                ?: throw IllegalStateException("找不到录音记录")
            val wavFile = File(session.audioFilePath)
            require(wavFile.exists()) { "原始音频文件不存在" }

            val settings = settingsRepo.settingsFlow.first()
            val activeTerms = database.glossaryDao().getActiveEntries().map { it.toDomain() }

            onProgress(0.05f, "正在读取原始课堂音频...")
            val (sampleRate, rawSamples) = CrashSafeWavWriter.readWavAsFloat(wavFile)
            require(sampleRate == 16000) { "仅支持 16kHz 音频重转写 (当前 ${sampleRate}Hz)" }

            onProgress(0.12f, "正在加载离线语音模型...")
            val gtcrn = if (settings.dspConfig.denoiseLevel != DenoiseLevel.OFF &&
                modelManager.isInstalled(ModelCatalog.GTCRN_DENOISER)
            ) {
                runCatching { SherpaGtcrnEnhancer(modelManager.getPrimaryModelFile(ModelCatalog.GTCRN_DENOISER)) }.getOrNull()
            } else null

            val pipeline = FarFieldDspPipeline(16000, settings.dspConfig, gtcrn)
            val vad = SherpaSileroVadEngine(
                sileroModelFile = modelManager.getPrimaryModelFile(ModelCatalog.SILERO_VAD),
                threshold = settings.dspConfig.vadThreshold,
                minSilenceDurationSec = settings.dspConfig.vadMinSilenceSec,
                maxSpeechDurationSec = settings.dspConfig.vadMaxSpeechSec
            )
            val asr = SherpaSenseVoiceEngine(
                modelFile = modelManager.getPrimaryModelFile(ModelCatalog.SENSEVOICE_INT8),
                tokensFile = modelManager.getTokensFile(ModelCatalog.SENSEVOICE_INT8),
                languageMode = settings.asrLanguageMode,
                numThreads = settings.asrThreads
            )

            try {
                // Step 1: DSP + VAD segmentation
                onProgress(0.20f, "正在进行远场增强与智能分句...")
                val chunks = mutableListOf<VadSpeechChunk>()
                val step = 512
                var pos = 0
                while (pos < rawSamples.size) {
                    val end = minOf(pos + step, rawSamples.size)
                    val frame = rawSamples.copyOfRange(pos, end)
                    val enhanced = pipeline.processFloat(frame)
                    vad.acceptSamples(enhanced)
                    while (true) {
                        val c = vad.pollSegment() ?: break
                        chunks.add(c)
                    }
                    pos = end
                }
                chunks.addAll(vad.flush())

                // Step 2: Replace old segments and transcribe all chunks
                database.lectureDao().deleteSegmentsForSession(sessionId)
                val corrector = OfflineTermCorrector(
                    terms = activeTerms,
                    enablePinyinFuzzy = settings.enablePinyinTermCorrection
                )

                var written = 0
                chunks.forEachIndexed { idx, chunk ->
                    val pct = 0.25f + 0.75f * (idx.toFloat() / maxOf(1, chunks.size))
                    onProgress(pct, "离线重转写中 (${idx + 1}/${chunks.size})...")

                    val asrRes = asr.transcribe(chunk.samples, 16000)
                    if (!SherpaSenseVoiceEngine.isMeaningfulSpeech(
                            asrRes.text,
                            asrRes.detectedLanguage,
                            settings.asrLanguageMode
                        )
                    ) return@forEachIndexed

                    val correction = corrector.correct(asrRes.text)
                    val offlineTrans = translationRouter.translateLive(
                        sourceText = correction.correctedText,
                        direction = settings.translationDirection,
                        terms = activeTerms,
                        llmConfig = settings.llmConfig.copy(enableLiveOnlineTranslation = false)
                    )
                    val startMs = (chunk.startSampleIndex * 1000L) / 16000L
                    val endMs = startMs + (chunk.samples.size * 1000L) / 16000L

                    database.lectureDao().insertSegment(
                        TranscriptSegmentEntity(
                            sessionId = sessionId,
                            indexInSession = written++,
                            startMs = startMs,
                            endMs = endMs,
                            rawText = asrRes.text,
                            correctedText = correction.correctedText,
                            offlineTranslation = offlineTrans.translatedText,
                            correctionSpansJson = json.encodeToString(correction.spans),
                            detectedLang = asrRes.detectedLanguage,
                            syncStatus = SegmentSyncStatus.PENDING
                        )
                    )
                }
                onProgress(1f, "离线重转写完成（共 $written 段）")
                written
            } finally {
                runCatching { asr.close() }
                runCatching { vad.close() }
                runCatching { gtcrn?.close() }
            }
        }
    }

    private suspend fun processSpeechChunk(
        sessionId: Long,
        chunk: VadSpeechChunk,
        asrEngine: OfflineAsrEngine,
        settingsSnapshot: AppSettings,
        activeTerms: List<GlossaryTerm>
    ) {
        val asrRes = runCatching { asrEngine.transcribe(chunk.samples, 16000) }.getOrNull() ?: return
        if (!SherpaSenseVoiceEngine.isMeaningfulSpeech(
                asrRes.text,
                asrRes.detectedLanguage,
                settingsSnapshot.asrLanguageMode
            )
        ) return

        _state.update { it.copy(lastInferenceMs = asrRes.inferenceTimeMs) }

        val corrector = OfflineTermCorrector(
            terms = activeTerms,
            enablePinyinFuzzy = settingsSnapshot.enablePinyinTermCorrection
        )
        val correction = corrector.correct(asrRes.text)

        val transOutcome = translationRouter.translateLive(
            sourceText = correction.correctedText,
            direction = settingsSnapshot.translationDirection,
            terms = activeTerms,
            llmConfig = settingsSnapshot.llmConfig
        )

        val startMs = (chunk.startSampleIndex * 1000L) / 16000L
        val endMs = startMs + (chunk.samples.size * 1000L) / 16000L
        val idx = segmentIndexCounter.getAndIncrement()
        val bookmarked = bookmarkNextFlag.getAndSet(false)

        database.lectureDao().insertSegment(
            TranscriptSegmentEntity(
                sessionId = sessionId,
                indexInSession = idx,
                startMs = startMs,
                endMs = endMs,
                rawText = asrRes.text,
                correctedText = correction.correctedText,
                offlineTranslation = transOutcome.translatedText,
                correctionSpansJson = json.encodeToString(correction.spans),
                detectedLang = asrRes.detectedLanguage,
                isBookmarked = bookmarked,
                syncStatus = SegmentSyncStatus.PENDING
            )
        )
    }
}
