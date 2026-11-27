package com.phy4l.tcfy.ui.record

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phy4l.tcfy.audio.AcousticPreset
import com.phy4l.tcfy.data.db.TranscriptSegmentEntity
import com.phy4l.tcfy.data.db.TranslationDirection
import com.phy4l.tcfy.data.export.SessionExporter
import com.phy4l.tcfy.glossary.TermCorrectionSpan
import com.phy4l.tcfy.ui.TcfyViewModel
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

private val spanJson = Json { ignoreUnknownKeys = true }

@Composable
fun RecordScreen(
    viewModel: TcfyViewModel,
    listState: LazyListState = rememberLazyListState(),
    autoFollowLatest: Boolean = true,
    onAutoFollowChange: (Boolean) -> Unit = {},
    onRequestMicPermissionAndStart: () -> Unit,
    onNavigateToModels: () -> Unit
) {
    val recState by viewModel.recordingState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val segments by viewModel.liveSegments.collectAsState()
    val packs by viewModel.glossaryPacks.collectAsState()
    val modelStates by viewModel.modelStates.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    val coreReady = remember(modelStates) { viewModel.isCoreAsrReady() }
    var showDirectionMenu by remember { mutableStateOf(false) }
    var inspectedSegment by remember { mutableStateOf<TranscriptSegmentEntity?>(null) }
    val hasPartial = recState.partialTranscript.isNotBlank()
    val totalListItems = RecordScrollPolicy.totalListItems(segments.size, hasPartial)

    // Detect user manual scroll gestures to pause/resume auto-follow without interfering with tab return
    LaunchedEffect(listState) {
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            val total = listState.layoutInfo.totalItemsCount
            Triple(listState.isScrollInProgress, lastVisible, total)
        }.collect { (scrolling, lastVisible, total) ->
            if (scrolling && total > 0) {
                onAutoFollowChange(RecordScrollPolicy.isNearLatest(lastVisible, total))
            }
        }
    }

    // Returning from another tab: jump immediately to the latest transcript when following latest
    LaunchedEffect(Unit) {
        val target = RecordScrollPolicy.targetIndexOnTabEnter(totalListItems, autoFollowLatest)
        if (target != null) {
            listState.scrollToItem(target)
        }
    }

    // Smart auto-scroll when new segments or live partial preview arrive
    LaunchedEffect(totalListItems) {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
        val target = RecordScrollPolicy.targetIndexOnNewItems(totalListItems, autoFollowLatest, lastVisible)
        if (target != null) {
            listState.animateScrollToItem(target)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        // Top Header: Status + Direction + Font Size
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = "教室远场听译",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val activePackCount = packs.count { it.isActive }
                Text(
                    text = if (recState.isRecording) {
                        recState.asrModeLabel
                    } else {
                        "已启用 $activePackCount 个术语库 · 断网可用"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    AssistChip(
                        onClick = { showDirectionMenu = true },
                        label = { Text(settings.translationDirection.shortLabel, fontSize = 12.sp, maxLines = 1) },
                        leadingIcon = {
                            Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    )
                    DropdownMenu(
                        expanded = showDirectionMenu,
                        onDismissRequest = { showDirectionMenu = false }
                    ) {
                        TranslationDirection.entries.forEach { dir ->
                            DropdownMenuItem(
                                text = { Text(dir.displayName) },
                                onClick = {
                                    viewModel.updateTranslationDirection(dir)
                                    showDirectionMenu = false
                                }
                            )
                        }
                    }
                }
                IconButton(onClick = { viewModel.updateLargeFont(!settings.largeSubtitleFont) }) {
                    Icon(
                        imageVector = Icons.Default.FormatSize,
                        contentDescription = "大字号切换",
                        tint = if (settings.largeSubtitleFont) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Collapsible Acoustic Preset Bar (collapses after picking unless user pins it)
        var acousticBarExpanded by rememberSaveable { mutableStateOf(false) }
        val activePreset = remember(settings.acousticPresetName) {
            runCatching { AcousticPreset.valueOf(settings.acousticPresetName) }
                .getOrDefault(AcousticPreset.CLASSROOM_FAR_FIELD)
        }
        val showAcousticChips = settings.pinAcousticPresetBar || acousticBarExpanded

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            AssistChip(
                onClick = {
                    if (settings.pinAcousticPresetBar) {
                        viewModel.updatePinAcousticBar(false)
                        acousticBarExpanded = false
                    } else {
                        acousticBarExpanded = !acousticBarExpanded
                    }
                },
                leadingIcon = {
                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                label = {
                    Text(
                        text = "声学：${activePreset.displayName}",
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                trailingIcon = {
                    Icon(
                        imageVector = if (showAcousticChips) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (showAcousticChips) "收起声学模式" else "展开声学模式",
                        modifier = Modifier.size(16.dp)
                    )
                }
            )

            if (showAcousticChips) {
                TextButton(
                    onClick = {
                        val nextPinned = !settings.pinAcousticPresetBar
                        viewModel.updatePinAcousticBar(nextPinned)
                        if (!nextPinned) acousticBarExpanded = false
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (settings.pinAcousticPresetBar) "已常驻 · 点此改为选后收起" else "常驻显示",
                        fontSize = 11.sp
                    )
                }
            }
        }

        AnimatedVisibility(visible = showAcousticChips) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
            ) {
                items(AcousticPreset.entries) { preset ->
                    val selected = settings.acousticPresetName == preset.name
                    FilterChip(
                        selected = selected,
                        onClick = {
                            viewModel.applyAcousticPreset(preset)
                            if (!settings.pinAcousticPresetBar) {
                                acousticBarExpanded = false
                            }
                        },
                        label = { Text(preset.displayName, fontSize = 12.sp) }
                    )
                }
            }
        }

        // Model missing or runtime warning banner
        val warningText = recState.warningMessage ?: if (!coreReady) {
            "尚未下载离线语音模型，当前仅保留原始远场录音。"
        } else null

        if (warningText != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = warningText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    if (!coreReady) {
                        TextButton(
                            onClick = onNavigateToModels,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        ) {
                            Text("去下载")
                        }
                    }
                }
            }
        }

        // Error banner with accessible contrast in both light & dark themes
        recState.errorMessage?.let { err ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { viewModel.clearRecordingError() },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) { Text("关闭") }
                }
            }
        }

        // Real-Time Far-Field Signal Meter while recording
        if (recState.isRecording) {
            val normalizedLevel = ((recState.outputDbfs + 60f) / 60f).coerceIn(0f, 1f)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                        ) {
                            val dotColor by animateColorAsState(
                                targetValue = when {
                                    recState.isPaused -> Color.Gray
                                    recState.isSpeechActive -> Color(0xFF16A34A)
                                    else -> MaterialTheme.colorScheme.primary
                                },
                                label = "speechDot"
                            )
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = recState.activeAudioSourceLabel.ifBlank { "远场麦克风" },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "AGC +${recState.currentAgcGainDb.roundToInt()}dB · ${recState.outputDbfs.roundToInt()} dBFS",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { normalizedLevel },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Main Transcript Area
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (segments.isEmpty() && recState.partialTranscript.isBlank()) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (recState.isRecording) "正在监听教室演讲声音..." else "点击下方麦克风开始离线听课",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "提示：手机平放课桌并将顶部麦克风朝向讲台，效果最佳。\n断网时自动纯离线转写与录音，绝不丢字。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(segments, key = { it.id }) { seg ->
                        LiveSegmentCard(
                            segment = seg,
                            largeFont = settings.largeSubtitleFont,
                            onToggleBookmark = { viewModel.toggleSegmentBookmark(seg) },
                            onClick = { inspectedSegment = seg }
                        )
                    }
                    if (recState.partialTranscript.isNotBlank()) {
                        item(key = "partial_preview") {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "实时识别中...",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = recState.partialTranscript,
                                        fontSize = if (settings.largeSubtitleFont) 18.sp else 15.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
                if (!autoFollowLatest && totalListItems > 2) {
                    FilledTonalButton(
                        onClick = {
                            onAutoFollowChange(true)
                            coroutineScope.launch {
                                listState.animateScrollToItem((totalListItems - 1).coerceAtLeast(0))
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 4.dp, bottom = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text("回到最新", fontSize = 12.sp)
                    }
                }
            }
        }

        // Bottom Control Deck (tuned for 392dp width + MIUI large system fonts)
        Surface(
            tonalElevation = 3.dp,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.widthIn(min = 72.dp, max = 92.dp)) {
                    Text(
                        text = SessionExporter.formatTimestamp(recState.elapsedMs),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = when {
                            recState.isInitializing -> "加载中..."
                            recState.isPaused -> "已暂停"
                            recState.isRecording -> "离线保护"
                            else -> "就绪"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!recState.isRecording) {
                        Button(
                            onClick = onRequestMicPermissionAndStart,
                            enabled = !recState.isInitializing,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            shape = RoundedCornerShape(28.dp)
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (recState.isInitializing) "初始化..." else "开始听课",
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    } else {
                        FilledTonalIconButton(
                            onClick = { viewModel.togglePauseRecording() },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = if (recState.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (recState.isPaused) "继续" else "暂停"
                            )
                        }

                        FilledIconButton(
                            onClick = { viewModel.stopRecording() },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            modifier = Modifier.size(54.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "停止保存")
                        }
                    }
                }

                OutlinedButton(
                    onClick = { viewModel.bookmarkCurrent() },
                    enabled = recState.isRecording,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.BookmarkBorder, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("重点", fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
            }
        }
    }

    // Inspect / Quick-Add-Term Dialog when tapping any live transcript card
    inspectedSegment?.let { seg ->
        SegmentQuickFixDialog(
            segment = seg,
            onDismiss = { inspectedSegment = null },
            onSaveAll = { newText, term, trans, alias ->
                if (newText.isNotBlank() && newText.trim() != seg.displaySourceText) {
                    viewModel.updateSegmentManualText(seg.id, newText)
                }
                if (term.isNotBlank()) {
                    viewModel.quickAddTermToActiveGlossary(term, trans, alias)
                }
                inspectedSegment = null
            }
        )
    }
}

@Composable
private fun LiveSegmentCard(
    segment: TranscriptSegmentEntity,
    largeFont: Boolean,
    onToggleBookmark: () -> Unit,
    onClick: () -> Unit
) {
    val spans = remember(segment.correctionSpansJson) {
        runCatching { spanJson.decodeFromString<List<TermCorrectionSpan>>(segment.correctionSpansJson) }
            .getOrDefault(emptyList())
    }
    val highlightColor = MaterialTheme.colorScheme.primary

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = SessionExporter.formatTimestamp(segment.startMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (spans.isNotEmpty()) {
                        Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(
                                text = "已纠错 ${spans.size} 术语",
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }
                }
                IconButton(onClick = onToggleBookmark, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = if (segment.isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = "重点书签",
                        tint = if (segment.isBookmarked) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            val annotatedSource = remember(segment.displaySourceText, spans, highlightColor) {
                buildAnnotatedString {
                    val txt = segment.displaySourceText
                    append(txt)
                    if (!segment.isRefined) {
                        for (s in spans) {
                            val start = s.startIndex.coerceIn(0, txt.length)
                            val end = s.endIndex.coerceIn(start, txt.length)
                            if (end > start) {
                                addStyle(
                                    style = SpanStyle(
                                        color = highlightColor,
                                        fontWeight = FontWeight.SemiBold,
                                        textDecoration = TextDecoration.Underline
                                    ),
                                    start = start,
                                    end = end
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = annotatedSource,
                fontSize = if (largeFont) 19.sp else 16.sp,
                lineHeight = if (largeFont) 27.sp else 23.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (segment.displayTranslation.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = segment.displayTranslation,
                    fontSize = if (largeFont) 16.sp else 14.sp,
                    lineHeight = if (largeFont) 23.sp else 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun SegmentQuickFixDialog(
    segment: TranscriptSegmentEntity,
    onDismiss: () -> Unit,
    onSaveAll: (editedText: String, canonical: String, translation: String, alias: String) -> Unit
) {
    var editedText by remember { mutableStateOf(segment.displaySourceText) }
    var termCanonical by remember { mutableStateOf("") }
    var termTranslation by remember { mutableStateOf("") }
    var termAlias by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑段落 / 快捷加入术语库") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (segment.rawText != segment.correctedText) {
                    Text(
                        text = "离线原始识别：${segment.rawText}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = editedText,
                    onValueChange = { editedText = it },
                    label = { Text("修改当前段文字") },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text("将错词同步加入当前启用术语库（可选）", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = termCanonical,
                    onValueChange = { termCanonical = it },
                    label = { Text("标准术语（如 Telomerase）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = termTranslation,
                    onValueChange = { termTranslation = it },
                    label = { Text("译名（如 端粒酶）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = termAlias,
                    onValueChange = { termAlias = it },
                    label = { Text("误转易错词（如 端粒没）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSaveAll(editedText, termCanonical, termTranslation, termAlias) },
                enabled = editedText.isNotBlank() || termCanonical.isNotBlank()
            ) {
                Text(if (termCanonical.isNotBlank()) "保存并加入术语库" else "保存文字")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
