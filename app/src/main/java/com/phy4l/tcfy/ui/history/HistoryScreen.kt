package com.phy4l.tcfy.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phy4l.tcfy.data.db.LectureSessionEntity
import com.phy4l.tcfy.data.export.SessionExporter
import com.phy4l.tcfy.ui.TcfyViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    viewModel: TcfyViewModel,
    onOpenSession: (Long) -> Unit
) {
    val sessions by viewModel.sessions.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val recState by viewModel.recordingState.collectAsState()
    var sessionToDelete by remember { mutableStateOf<LectureSessionEntity?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = "课堂记录库",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.updateSearchQuery(it) },
            placeholder = { Text("搜索课程名、转写正文或 AI 总结...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(10.dp))

        if (sessions.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    modifier = Modifier.size(54.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (query.isBlank()) "还没有保存的课堂记录" else "未找到匹配的课堂记录",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "所有原始录音、离线转写、AI 精修与总结都会保存在本机。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sessions, key = { it.id }) { session ->
                    val isActivelyRecording = session.isRecording || session.id == recState.activeSessionId
                    SessionListCard(
                        session = session,
                        isActivelyRecording = isActivelyRecording,
                        onClick = { onOpenSession(session.id) },
                        onDeleteClick = { sessionToDelete = session }
                    )
                }
            }
        }
    }

    sessionToDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = { Text("删除课堂记录？") },
            text = { Text("将永久删除「${s.title}」的原始录音文件、转写文本与 AI 总结。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSession(s)
                    sessionToDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SessionListCard(
    session: LectureSessionEntity,
    isActivelyRecording: Boolean,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val dateStr = remember(session.startedAtMs) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(session.startedAtMs))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = session.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isActivelyRecording) {
                        Badge(containerColor = MaterialTheme.colorScheme.errorContainer) {
                            Text(
                                text = "录制中",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isActivelyRecording) {
                        "$dateStr · 正在录制..."
                    } else {
                        "$dateStr · 时长 ${SessionExporter.formatTimestamp(session.durationMs)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (session.glossarySnapshotName.isNotBlank()) {
                    Text(
                        text = "术语库：${session.glossarySnapshotName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (session.aiSummaryMarkdown.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    AssistChip(
                        onClick = onClick,
                        label = { Text("已生成 AI 总结") },
                        leadingIcon = {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(15.dp))
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            IconButton(
                onClick = onDeleteClick,
                enabled = !isActivelyRecording
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "删除",
                    tint = if (isActivelyRecording) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}
