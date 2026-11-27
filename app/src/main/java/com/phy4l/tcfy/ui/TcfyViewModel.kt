package com.phy4l.tcfy.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phy4l.tcfy.TcfyApplication
import com.phy4l.tcfy.ai.RefinementProgress
import com.phy4l.tcfy.asr.AsrLanguageMode
import com.phy4l.tcfy.asr.ModelDescriptor
import com.phy4l.tcfy.asr.ModelInstallState
import com.phy4l.tcfy.audio.AcousticPreset
import com.phy4l.tcfy.audio.FarFieldDspConfig
import com.phy4l.tcfy.data.db.GlossaryEntryEntity
import com.phy4l.tcfy.data.db.GlossaryPackEntity
import com.phy4l.tcfy.data.db.LectureSessionEntity
import com.phy4l.tcfy.data.db.SegmentSyncStatus
import com.phy4l.tcfy.data.db.TranscriptSegmentEntity
import com.phy4l.tcfy.data.db.TranslationDirection
import com.phy4l.tcfy.data.export.SessionExporter
import com.phy4l.tcfy.data.prefs.AppSettings
import com.phy4l.tcfy.data.prefs.LlmApiConfig
import com.phy4l.tcfy.glossary.GlossaryImporter
import com.phy4l.tcfy.glossary.GlossaryPackExport
import com.phy4l.tcfy.glossary.GlossaryTerm
import com.phy4l.tcfy.service.RecordingForegroundService
import com.phy4l.tcfy.service.RecordingUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class DetailTaskState(
    val isBusy: Boolean = false,
    val progress: Float = 0f,
    val statusText: String = "",
    val errorText: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class TcfyViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as TcfyApplication
    private val db = app.database
    private val settingsRepo = app.settingsRepository
    private val modelManager = app.modelManager
    private val coordinator = app.recordingCoordinator
    private val refiner = app.refinerAndSummarizer
    private val llmClient = app.llmClient

    val recordingState: StateFlow<RecordingUiState> = coordinator.state

    val settings: StateFlow<AppSettings> = settingsRepo.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val modelStates: StateFlow<Map<String, ModelInstallState>> = modelManager.states

    val glossaryPacks: StateFlow<List<GlossaryPackEntity>> = db.glossaryDao().observeAllPacks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeGlossaryEntries: StateFlow<List<GlossaryEntryEntity>> = db.glossaryDao().observeActiveEntries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val liveSegments: StateFlow<List<TranscriptSegmentEntity>> = recordingState
        .flatMapLatest { st ->
            val sid = st.activeSessionId
            if (sid != null) db.lectureDao().observeSegments(sid) else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val sessions: StateFlow<List<LectureSessionEntity>> = _searchQuery
        .flatMapLatest { q ->
            if (q.isBlank()) db.lectureDao().observeAllSessions()
            else db.lectureDao().searchSessions(q.trim())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _detailTaskStates = MutableStateFlow<Map<Long, DetailTaskState>>(emptyMap())
    val detailTaskStates: StateFlow<Map<Long, DetailTaskState>> = _detailTaskStates.asStateFlow()

    private val _toastChannel = Channel<String>(Channel.BUFFERED)
    val toastMessages: Flow<String> = _toastChannel.receiveAsFlow()

    private var lastStartClickMs: Long = 0L

    private fun postToast(msg: String) {
        _toastChannel.trySend(msg)
    }

    private fun updateTaskState(sessionId: Long, state: DetailTaskState) {
        _detailTaskStates.update { it + (sessionId to state) }
    }

    fun isCoreAsrReady(): Boolean = modelManager.isCoreAsrReady()

    // --- Recording Controls ---

    fun startRecording(customTitle: String? = null) {
        val now = System.currentTimeMillis()
        val cur = recordingState.value
        if (cur.isRecording || cur.isInitializing || now - lastStartClickMs < 800L) {
            return
        }
        lastStartClickMs = now
        RecordingForegroundService.start(app, customTitle)
    }

    fun togglePauseRecording() {
        RecordingForegroundService.togglePause(app)
    }

    fun stopRecording() {
        RecordingForegroundService.stop(app)
    }

    fun bookmarkCurrent() {
        coordinator.requestBookmarkCurrentOrNext()
        postToast("已标记课堂重点")
    }

    fun clearRecordingError() {
        coordinator.clearError()
    }

    fun notifyMovedToBackground() {
        postToast("录音已转入后台继续运行")
    }

    // --- Sessions & Detail ---

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun observeSession(sessionId: Long): Flow<LectureSessionEntity?> =
        db.lectureDao().observeSession(sessionId)

    fun observeSessionSegments(sessionId: Long): Flow<List<TranscriptSegmentEntity>> =
        db.lectureDao().observeSegments(sessionId)

    fun renameSession(sessionId: Long, newTitle: String) {
        if (newTitle.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            db.lectureDao().updateTitle(sessionId, newTitle.trim())
        }
    }

    fun deleteSession(session: LectureSessionEntity) {
        if (session.isRecording || session.id == recordingState.value.activeSessionId) {
            postToast("该课程正在录制中，请先停止录音后再删除")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            db.lectureDao().deleteSession(session)
            runCatching { File(session.audioFilePath).delete() }
            postToast("已删除课堂记录与音频")
        }
    }

    fun toggleSegmentBookmark(segment: TranscriptSegmentEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            db.lectureDao().setBookmarked(segment.id, !segment.isBookmarked)
        }
    }

    fun updateSegmentManualText(segmentId: Long, newText: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.lectureDao().updateSegmentManualText(segmentId, newText.trim())
        }
    }

    fun refineSessionWithAi(sessionId: Long) {
        if (_detailTaskStates.value[sessionId]?.isBusy == true) return
        viewModelScope.launch {
            updateTaskState(sessionId, DetailTaskState(isBusy = true, progress = 0.05f, statusText = "准备连接大模型进行术语精修..."))
            val session = withContext(Dispatchers.IO) { db.lectureDao().getSession(sessionId) }
            val segs = withContext(Dispatchers.IO) { db.lectureDao().getSegments(sessionId) }
            val terms = withContext(Dispatchers.IO) { db.glossaryDao().getActiveEntries().map { it.toDomain() } }
            val curSettings = settings.value

            if (session == null || segs.isEmpty()) {
                updateTaskState(sessionId, DetailTaskState(errorText = "没有可精修的转写段落"))
                return@launch
            }

            val dir = runCatching { TranslationDirection.valueOf(session.translationDirection) }
                .getOrDefault(curSettings.translationDirection)

            val res = refiner.refinePendingSegments(
                allSegments = segs,
                terms = terms,
                direction = dir,
                llmConfig = curSettings.llmConfig,
                onBatchSaved = { refinedItems ->
                    withContext(Dispatchers.IO) {
                        for (item in refinedItems) {
                            db.lectureDao().updateSegmentRefinement(
                                segmentId = item.id,
                                refinedText = item.refinedText,
                                refinedTranslation = item.refinedTranslation,
                                syncStatus = SegmentSyncStatus.REFINED
                            )
                        }
                    }
                },
                onProgress = { prog: RefinementProgress ->
                    val frac = if (prog.totalCount > 0) prog.completedCount.toFloat() / prog.totalCount else 0f
                    updateTaskState(
                        sessionId,
                        DetailTaskState(
                            isBusy = true,
                            progress = frac,
                            statusText = prog.statusMessage
                        )
                    )
                }
            )

            res.fold(
                onSuccess = { count ->
                    updateTaskState(sessionId, DetailTaskState())
                    postToast(if (count > 0) "AI 术语精修完成（更新 $count 段）" else "全部段落已精修完成")
                },
                onFailure = { err ->
                    updateTaskState(sessionId, DetailTaskState(errorText = err.message))
                }
            )
        }
    }

    fun generateAiSummary(sessionId: Long) {
        if (_detailTaskStates.value[sessionId]?.isBusy == true) return
        viewModelScope.launch {
            updateTaskState(sessionId, DetailTaskState(isBusy = true, progress = 0.2f, statusText = "正在生成 AI 课堂总结..."))
            val session = withContext(Dispatchers.IO) { db.lectureDao().getSession(sessionId) }
            val segs = withContext(Dispatchers.IO) { db.lectureDao().getSegments(sessionId) }
            val terms = withContext(Dispatchers.IO) { db.glossaryDao().getActiveEntries().map { it.toDomain() } }

            if (session == null) {
                updateTaskState(sessionId, DetailTaskState(errorText = "找不到课堂会话"))
                return@launch
            }

            val res = refiner.generateSummary(
                lectureTitle = session.title,
                segments = segs,
                terms = terms,
                llmConfig = settings.value.llmConfig,
                onStatus = { status ->
                    updateTaskState(sessionId, DetailTaskState(isBusy = true, progress = 0.6f, statusText = status))
                }
            )

            res.fold(
                onSuccess = { md ->
                    withContext(Dispatchers.IO) { db.lectureDao().updateSummary(sessionId, md) }
                    updateTaskState(sessionId, DetailTaskState())
                    postToast("AI 课堂总结已生成并保存")
                },
                onFailure = { err ->
                    updateTaskState(sessionId, DetailTaskState(errorText = err.message))
                }
            )
        }
    }

    fun retranscribeOffline(sessionId: Long) {
        if (_detailTaskStates.value[sessionId]?.isBusy == true) return
        viewModelScope.launch {
            updateTaskState(sessionId, DetailTaskState(isBusy = true, progress = 0.05f, statusText = "启动离线重转写..."))
            val res = coordinator.retranscribeSessionOffline(sessionId) { pct, msg ->
                updateTaskState(sessionId, DetailTaskState(isBusy = true, progress = pct, statusText = msg))
            }
            res.fold(
                onSuccess = { count ->
                    updateTaskState(sessionId, DetailTaskState())
                    postToast("离线重转写完成，共 $count 段")
                },
                onFailure = { err ->
                    updateTaskState(sessionId, DetailTaskState(errorText = err.message))
                }
            )
        }
    }

    fun clearDetailTaskError(sessionId: Long) {
        val cur = _detailTaskStates.value[sessionId] ?: return
        updateTaskState(sessionId, cur.copy(errorText = null))
    }

    fun exportAndShareSession(sessionId: Long, format: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val session = db.lectureDao().getSession(sessionId) ?: return@launch
            val segs = db.lectureDao().getSegments(sessionId)
            val exportsDir = File(app.filesDir, "exports").apply { mkdirs() }
            val safeName = session.title.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40)
            val (ext, mime, content) = when (format) {
                "srt" -> Triple("srt", "text/plain", SessionExporter.toSrt(segs))
                "txt" -> Triple("txt", "text/plain", SessionExporter.toPlainText(session, segs))
                else -> Triple("md", "text/markdown", SessionExporter.toMarkdown(session, segs))
            }
            val file = File(exportsDir, "${safeName}_${session.id}.$ext")
            file.writeText(content, Charsets.UTF_8)
            shareFile(file, mime, "分享课堂记录 (${ext.uppercase()})")
        }
    }

    // --- Glossary Management ---

    fun observeEntriesForPack(packId: Long): Flow<List<GlossaryEntryEntity>> =
        db.glossaryDao().observeEntriesByPack(packId)

    fun createGlossaryPack(name: String, description: String = "") {
        if (name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            db.glossaryDao().insertPack(
                GlossaryPackEntity(name = name.trim(), description = description.trim(), isActive = true)
            )
            postToast("已创建课程术语库：$name")
        }
    }

    fun togglePackActive(pack: GlossaryPackEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            db.glossaryDao().setPackActive(pack.id, !pack.isActive)
        }
    }

    fun deleteGlossaryPack(pack: GlossaryPackEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            db.glossaryDao().deletePack(pack)
            postToast("已删除术语库：${pack.name}")
        }
    }

    fun addOrUpdateGlossaryTerm(
        packId: Long,
        existingId: Long = 0L,
        canonicalTerm: String,
        translation: String,
        aliasesText: String,
        notes: String
    ) {
        if (canonicalTerm.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val aliases = aliasesText.split(',', '，', ';', '；', '、')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            val entry = GlossaryEntryEntity.fromDomain(
                packId = packId,
                term = GlossaryTerm(
                    id = existingId,
                    canonicalTerm = canonicalTerm,
                    translation = translation,
                    aliases = aliases,
                    notes = notes
                )
            )
            db.glossaryDao().insertEntry(entry)
            postToast("已保存术语：${canonicalTerm.trim()}")
        }
    }

    /**
     * Quick 1-tap add from transcript screen into the first active glossary pack.
     */
    fun quickAddTermToActiveGlossary(canonicalTerm: String, translation: String, misrecognizedAlias: String) {
        if (canonicalTerm.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val activePacks = db.glossaryDao().getActivePacks()
            val targetPackId = activePacks.firstOrNull()?.id ?: db.glossaryDao().insertPack(
                GlossaryPackEntity(name = "默认课堂术语库", isActive = true)
            )
            val aliases = listOf(misrecognizedAlias.trim()).filter { it.isNotEmpty() && it != canonicalTerm.trim() }
            db.glossaryDao().insertEntry(
                GlossaryEntryEntity.fromDomain(
                    targetPackId,
                    GlossaryTerm(canonicalTerm = canonicalTerm.trim(), translation = translation.trim(), aliases = aliases)
                )
            )
            postToast("已加入术语库：${canonicalTerm.trim()}")
        }
    }

    fun deleteGlossaryEntry(entry: GlossaryEntryEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            db.glossaryDao().deleteEntry(entry)
        }
    }

    fun importGlossaryFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val fileName = resolveFileName(uri) ?: "imported_glossary.txt"
                val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("无法读取文件")
                val text = GlossaryImporter.decodeBytesSmart(bytes)
                val baseName = fileName.substringBeforeLast('.')
                val imported = GlossaryImporter.parseByFileName(fileName, text, baseName)
                require(imported.terms.isNotEmpty()) { "文件中未解析到有效术语" }

                val packId = db.glossaryDao().insertPack(
                    GlossaryPackEntity(
                        name = imported.name.ifBlank { baseName },
                        description = imported.description,
                        isActive = true
                    )
                )
                db.glossaryDao().insertEntries(
                    imported.terms.map { GlossaryEntryEntity.fromDomain(packId, it) }
                )
                postToast("成功导入「${imported.name}」，共 ${imported.terms.size} 条术语")
            }.onFailure { err ->
                postToast("导入失败：${err.message}")
            }
        }
    }

    fun exportGlossaryPack(pack: GlossaryPackEntity, format: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val terms = db.glossaryDao().getEntriesByPack(pack.id).map { it.toDomain() }
            val exportsDir = File(app.filesDir, "exports").apply { mkdirs() }
            val safeName = pack.name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(32)
            val (ext, mime, content) = if (format == "csv") {
                Triple("csv", "text/csv", GlossaryImporter.exportCsv(terms))
            } else {
                Triple(
                    "json",
                    "application/json",
                    GlossaryImporter.exportJson(GlossaryPackExport(pack.name, pack.description, terms))
                )
            }
            val file = File(exportsDir, "glossary_${safeName}.$ext")
            file.writeText(content, Charsets.UTF_8)
            shareFile(file, mime, "分享术语库 (${pack.name})")
        }
    }

    // --- Settings & Model Manager ---

    fun applyAcousticPreset(preset: AcousticPreset) {
        viewModelScope.launch {
            settingsRepo.applyAcousticPreset(preset)
            coordinator.updateLiveDspConfig(preset.config)
            postToast("已切换声学模式：${preset.displayName}")
        }
    }

    fun updateDspConfig(dsp: FarFieldDspConfig) {
        viewModelScope.launch {
            settingsRepo.updateDspConfig(dsp)
            coordinator.updateLiveDspConfig(dsp)
        }
    }

    fun updateAsrLanguage(mode: AsrLanguageMode) {
        viewModelScope.launch { settingsRepo.updateAsrLanguage(mode) }
    }

    fun updateAsrThreads(threads: Int) {
        viewModelScope.launch { settingsRepo.updateAsrThreads(threads) }
    }

    fun updateTranslationDirection(direction: TranslationDirection) {
        viewModelScope.launch { settingsRepo.updateTranslationDirection(direction) }
    }

    fun updatePinyinFix(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.updatePinyinFix(enabled) }
    }

    fun updateLargeFont(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.updateLargeFont(enabled) }
    }

    fun updatePinAcousticBar(pinned: Boolean) {
        viewModelScope.launch { settingsRepo.updatePinAcousticBar(pinned) }
    }

    fun updateMirrorSettings(useDomestic: Boolean, customBase: String) {
        viewModelScope.launch { settingsRepo.updateMirrorSettings(useDomestic, customBase) }
    }

    fun updateLlmConfig(config: LlmApiConfig) {
        viewModelScope.launch {
            settingsRepo.updateLlmConfig(config)
            postToast("API 配置已保存")
        }
    }

    fun updateLiveOnlineTranslation(enabled: Boolean) {
        viewModelScope.launch {
            val current = settings.value.llmConfig
            settingsRepo.updateLlmConfig(current.copy(enableLiveOnlineTranslation = enabled))
        }
    }

    fun testLlmConnection(config: LlmApiConfig, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val res = llmClient.testConnection(config)
            res.fold(
                onSuccess = { reply -> onResult(true, "连接成功！模型返回: $reply") },
                onFailure = { err -> onResult(false, err.message ?: "连接失败") }
            )
        }
    }

    fun downloadModel(descriptor: ModelDescriptor) {
        viewModelScope.launch {
            val s = settings.value
            val res = modelManager.downloadModel(
                descriptor = descriptor,
                useDomesticMirror = s.useDomesticMirror,
                customMirrorBase = s.customMirrorBase
            )
            if (res.isSuccess) {
                postToast("${descriptor.displayName} 下载完成")
            }
        }
    }

    fun importLocalModel(descriptor: ModelDescriptor, uri: Uri) {
        viewModelScope.launch {
            val name = resolveFileName(uri) ?: "model.onnx"
            val res = modelManager.importLocalModel(descriptor, uri, name)
            if (res.isSuccess) {
                postToast("已导入 ${descriptor.displayName}")
            }
        }
    }

    fun deleteModel(descriptor: ModelDescriptor) {
        modelManager.deleteModel(descriptor)
        postToast("已移除 ${descriptor.displayName}")
    }

    private fun resolveFileName(uri: Uri): String? {
        return app.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        } ?: uri.lastPathSegment
    }

    private suspend fun shareFile(file: File, mime: String, chooserTitle: String) {
        withContext(Dispatchers.Main) {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, chooserTitle).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(chooser)
        }
    }
}
