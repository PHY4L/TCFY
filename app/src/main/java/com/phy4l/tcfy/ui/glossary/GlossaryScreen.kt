package com.phy4l.tcfy.ui.glossary

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phy4l.tcfy.data.db.GlossaryEntryEntity
import com.phy4l.tcfy.data.db.GlossaryPackEntity
import com.phy4l.tcfy.ui.TcfyViewModel

@Composable
fun GlossaryScreen(viewModel: TcfyViewModel) {
    val packs by viewModel.glossaryPacks.collectAsState()
    var selectedPackId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showCreatePackDialog by remember { mutableStateOf(false) }

    val importPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importGlossaryFromUri(uri)
        }
    }

    val currentPack = selectedPackId?.let { id -> packs.firstOrNull { it.id == id } }
    if (currentPack != null) {
        BackHandler {
            selectedPackId = null
        }
        PackTermsDetailView(
            pack = currentPack,
            viewModel = viewModel,
            onBack = { selectedPackId = null }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = "专业术语词库",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "开启对应课程的术语库后，离线转写与课后 AI 精修均会自动纠正易错词并保持统一译名。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { showCreatePackDialog = true },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("新建词库", maxLines = 1, softWrap = false)
            }
            OutlinedButton(
                onClick = { importPicker.launch(arrayOf("*/*")) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("导入词库", maxLines = 1, softWrap = false)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(packs, key = { it.id }) { pack ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedPackId = pack.id },
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
                            Text(
                                text = pack.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (pack.description.isNotBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = pack.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "点击查看 / 编辑词条",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = { viewModel.exportGlossaryPack(pack, "json") }) {
                            Icon(Icons.Default.Share, contentDescription = "分享词库")
                        }
                        Switch(
                            checked = pack.isActive,
                            onCheckedChange = { viewModel.togglePackActive(pack) }
                        )
                    }
                }
            }
        }
    }

    if (showCreatePackDialog) {
        var name by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreatePackDialog = false },
            title = { Text("新建课程术语库") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("课程名称（如 分子生物学 / 遗传学）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = desc,
                        onValueChange = { desc = it },
                        label = { Text("简介（可选）") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.createGlossaryPack(name, desc)
                        showCreatePackDialog = false
                    },
                    enabled = name.isNotBlank()
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePackDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun PackTermsDetailView(
    pack: GlossaryPackEntity,
    viewModel: TcfyViewModel,
    onBack: () -> Unit
) {
    val entries by viewModel.observeEntriesForPack(pack.id).collectAsState(initial = emptyList())
    var editingEntry by remember { mutableStateOf<GlossaryEntryEntity?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showDeletePackDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    pack.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "共 ${entries.size} 条术语",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { viewModel.exportGlossaryPack(pack, "csv") }) {
                Icon(Icons.Default.Share, contentDescription = "导出 CSV")
            }
            IconButton(onClick = { showDeletePackDialog = true }) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "删除词库")
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Button(
            onClick = { showAddDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text("添加专业术语")
        }
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(entries, key = { it.id }) { entry ->
                val domain = entry.toDomain()
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editingEntry = entry },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = buildString {
                                    append(domain.canonicalTerm)
                                    if (domain.translation.isNotBlank()) append("  →  ${domain.translation}")
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (domain.aliases.isNotEmpty()) {
                                Text(
                                    text = "易错/同音映射：${domain.aliases.joinToString("、")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (domain.notes.isNotBlank()) {
                                Text(
                                    text = domain.notes,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(onClick = { viewModel.deleteGlossaryEntry(entry) }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "删除词条")
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog || editingEntry != null) {
        val initDomain = editingEntry?.toDomain()
        var term by remember(editingEntry) { mutableStateOf(initDomain?.canonicalTerm.orEmpty()) }
        var trans by remember(editingEntry) { mutableStateOf(initDomain?.translation.orEmpty()) }
        var aliases by remember(editingEntry) { mutableStateOf(initDomain?.aliases?.joinToString(", ").orEmpty()) }
        var notes by remember(editingEntry) { mutableStateOf(initDomain?.notes.orEmpty()) }

        AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                editingEntry = null
            },
            title = { Text(if (editingEntry == null) "添加专业术语" else "编辑术语") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = term,
                        onValueChange = { term = it },
                        label = { Text("标准术语（英文或中文）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = trans,
                        onValueChange = { trans = it },
                        label = { Text("首选译名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = aliases,
                        onValueChange = { aliases = it },
                        label = { Text("易错词/同音词（逗号分隔）") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("简短说明（供 AI 理解上下文）") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.addOrUpdateGlossaryTerm(
                            packId = pack.id,
                            existingId = editingEntry?.id ?: 0L,
                            canonicalTerm = term,
                            translation = trans,
                            aliasesText = aliases,
                            notes = notes
                        )
                        showAddDialog = false
                        editingEntry = null
                    },
                    enabled = term.isNotBlank()
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddDialog = false
                    editingEntry = null
                }) { Text("取消") }
            }
        )
    }

    if (showDeletePackDialog) {
        AlertDialog(
            onDismissRequest = { showDeletePackDialog = false },
            title = { Text("删除术语库「${pack.name}」？") },
            text = { Text("词库下的所有术语条目将被一并删除。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteGlossaryPack(pack)
                    showDeletePackDialog = false
                    onBack()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeletePackDialog = false }) { Text("取消") }
            }
        )
    }
}
