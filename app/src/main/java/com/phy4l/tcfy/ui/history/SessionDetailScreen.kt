package com.phy4l.tcfy.ui.history

import android.media.MediaPlayer
import android.media.PlaybackParams
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phy4l.tcfy.data.db.TranscriptSegmentEntity
import com.phy4l.tcfy.data.export.SessionExporter
import com.phy4l.tcfy.ui.DetailTaskState
import com.phy4l.tcfy.ui.TcfyViewModel
import com.phy4l.tcfy.ui.record.SegmentQuickFixDialog
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun SessionDetailScreen(
    sessionId: Long,
    viewModel: TcfyViewModel,
    onBack: () -> Unit
) {
    val session by viewModel.observeSession(sessionId).collectAsState(initial = null)
    val segments by viewModel.observeSessionSegments(sessionId).collectAsState(initial = emptyList())
    val allTaskStates by viewModel.detailTaskStates.collectAsState()
    val taskState = allTaskStates[sessionId] ?: DetailTaskState()

    var selectedTab by remember { mutableIntStateOf(0) } // 0 = transcript, 1 = AI summary
    var showExportMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var editingSegment by remember { mutableStateOf<TranscriptSegmentEntity?>(null) }

    // Async MediaPlayer state (never blocks UI thread on 90-min WAV files)
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlayerPrepared by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var isDraggingSlider by remember { mutableStateOf(false) }
    var currentPosMs by remember { mutableLongStateOf(0L) }
    var totalDurationMs by remember { mutableLongStateOf(1L) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }

    val audioPath = session?.audioFilePath
    DisposableEffect(audioPath) {
        isPlayerPrepared = false
        isPlaying = false
        currentPosMs = 0L
        val mp = if (!audioPath.isNullOrBlank() && File(audioPath).exists()) {
            runCatching {
                MediaPlayer().apply {
                    setDataSource(audioPath)
                    setOnPreparedListener { readyMp ->
                        totalDurationMs = readyMp.duration.toLong().coerceAtLeast(1L)
                        isPlayerPrepared = true
                    }
                    setOnCompletionListener {
                        isPlaying = false
                    }
                    setOnErrorListener { _, _, _ ->
                        isPlaying = false
                        isPlayerPrepared = false
                        true
                    }
                    prepareAsync()
                }
            }.getOrNull()
        } else null
        player = mp
        onDispose {
            runCatching { mp?.stop() }
            runCatching { mp?.release() }
            player = null
            isPlayerPrepared = false
            isPlaying = false
        }
    }

    LaunchedEffect(isPlaying, isPlayerPrepared, isDraggingSlider, player) {
        while (isPlaying && isPlayerPrepared && !isDraggingSlider && player != null) {
            currentPosMs = runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
            delay(250L)
        }
    }

    fun seekAndPlay(targetMs: Long) {
        val mp = player ?: return
        if (!isPlayerPrepared) return
        runCatching {
            mp.seekTo(targetMs.toInt().coerceIn(0, mp.duration))
            if (!mp.isPlaying) {
                mp.playbackParams = PlaybackParams().setSpeed(playbackSpeed)
                mp.start()
                isPlaying = true
            }
            currentPosMs = targetMs
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top App Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = session?.title ?: "课堂详情",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showRenameDialog = true }) {
                Icon(Icons.Default.Edit, contentDescription = "重命名")
            }
            Box {
                IconButton(onClick = { showExportMenu = true }) {
                    Icon(Icons.Default.Share, contentDescription = "导出分享")
                }
                DropdownMenu(
                    expanded = showExportMenu,
                    onDismissRequest = { showExportMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("导出 Markdown 笔记 (.md)") },
                        onClick = {
                            showExportMenu = false
                            viewModel.exportAndShareSession(sessionId, "md")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("导出双语纯文本 (.txt)") },
                        onClick = {
                            showExportMenu = false
                            viewModel.exportAndShareSession(sessionId, "txt")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("导出双语字幕 (.srt)") },
                        onClick = {
                            showExportMenu = false
                            viewModel.exportAndShareSession(sessionId, "srt")
                        }
                    )
                }
            }
        }

        // Audio Playback Bar
        if (player != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        enabled = isPlayerPrepared,
                        onClick = {
                            val mp = player ?: return@IconButton
                            if (isPlaying) {
                                mp.pause()
                                isPlaying = false
                            } else {
                                runCatching { mp.playbackParams = PlaybackParams().setSpeed(playbackSpeed) }
                                mp.start()
                                isPlaying = true
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "播放/暂停"
                        )
                    }
                    Slider(
                        value = currentPosMs.toFloat().coerceIn(0f, totalDurationMs.toFloat()),
                        enabled = isPlayerPrepared,
                        onValueChange = { pos ->
                            isDraggingSlider = true
                            currentPosMs = pos.toLong()
                        },
                        onValueChangeFinished = {
                            runCatching { player?.seekTo(currentPosMs.toInt()) }
                            isDraggingSlider = false
                        },
                        valueRange = 0f..totalDurationMs.toFloat(),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = SessionExporter.formatTimestamp(currentPosMs),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    TextButton(
                        enabled = isPlayerPrepared,
                        onClick = {
                            playbackSpeed = when (playbackSpeed) {
                                1.0f -> 1.5f
                                1.5f -> 2.0f
                                else -> 1.0f
                            }
                            if (isPlaying) {
                                runCatching { player?.playbackParams = PlaybackParams().setSpeed(playbackSpeed) }
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Text("${playbackSpeed}x", fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        }

        // AI Actions Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { viewModel.refineSessionWithAi(sessionId) },
                enabled = !taskState.isBusy && segments.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("AI 精修", fontSize = 12.sp, maxLines = 1, softWrap = false)
            }
            FilledTonalButton(
                onClick = {
                    selectedTab = 1
                    viewModel.generateAiSummary(sessionId)
                },
                enabled = !taskState.isBusy && segments.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Summarize, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("AI 总结", fontSize = 12.sp, maxLines = 1, softWrap = false)
            }
            OutlinedButton(
                onClick = { viewModel.retranscribeOffline(sessionId) },
                enabled = !taskState.isBusy,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("离线重转", fontSize = 12.sp, maxLines = 1, softWrap = false)
            }
        }

        // Task Progress / Error Card (per-session isolated)
        if (taskState.isBusy) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                Text(taskState.statusText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { taskState.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        taskState.errorText?.let { err ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { viewModel.clearDetailTaskError(sessionId) },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) { Text("知道了") }
                }
            }
        }

        // Tabs: Bilingual Transcript vs AI Summary
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("双语转写 (${segments.size})") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("AI 课堂总结") }
            )
        }

        if (selectedTab == 0) {
            if (segments.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "暂无转写段落，可点击「离线重转」使用离线模型重新识别音频。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(segments, key = { it.id }) { seg ->
                        DetailSegmentCard(
                            segment = seg,
                            onJumpAudio = { seekAndPlay(seg.startMs) },
                            onEdit = { editingSegment = seg },
                            onToggleBookmark = { viewModel.toggleSegmentBookmark(seg) }
                        )
                    }
                }
            }
        } else {
            val summaryText = session?.aiSummaryMarkdown.orEmpty()
            if (summaryText.isBlank()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Text(
                            text = "尚未生成 AI 课堂总结",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "点击上方「AI 总结」，将结合本节转写、重点标记和课程术语库自动生成复习笔记。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    Text(
                        text = summaryText,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 24.sp
                    )
                }
            }
        }
    }

    if (showRenameDialog && session != null) {
        var newTitle by remember(session) { mutableStateOf(session!!.title) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("修改课堂标题") },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.renameSession(sessionId, newTitle)
                        showRenameDialog = false
                    },
                    enabled = newTitle.isNotBlank()
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("取消") }
            }
        )
    }

    editingSegment?.let { seg ->
        SegmentQuickFixDialog(
            segment = seg,
            onDismiss = { editingSegment = null },
            onSaveAll = { newText, term, trans, alias ->
                if (newText.isNotBlank() && newText.trim() != seg.displaySourceText) {
                    viewModel.updateSegmentManualText(seg.id, newText)
                }
                if (term.isNotBlank()) {
                    viewModel.quickAddTermToActiveGlossary(term, trans, alias)
                }
                editingSegment = null
            }
        )
    }
}

@Composable
private fun DetailSegmentCard(
    segment: TranscriptSegmentEntity,
    onJumpAudio: () -> Unit,
    onEdit: () -> Unit,
    onToggleBookmark: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onJumpAudio),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "▶ ${SessionExporter.formatTimestamp(segment.startMs)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (segment.isRefined) {
                        Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                            Text(
                                text = "AI 精修",
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }
                }
                Row {
                    IconButton(onClick = onToggleBookmark, modifier = Modifier.size(30.dp)) {
                        Icon(
                            imageVector = if (segment.isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = "重点",
                            tint = if (segment.isBookmarked) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onEdit, modifier = Modifier.size(30.dp)) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "编辑或加术语",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = segment.displaySourceText,
                style = MaterialTheme.typography.bodyLarge,
                lineHeight = 24.sp
            )
            if (segment.displayTranslation.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = segment.displayTranslation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
