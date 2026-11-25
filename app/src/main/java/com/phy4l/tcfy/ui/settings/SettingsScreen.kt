package com.phy4l.tcfy.ui.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.phy4l.tcfy.ai.LlmPresets
import com.phy4l.tcfy.asr.AsrLanguageMode
import com.phy4l.tcfy.asr.ModelCatalog
import com.phy4l.tcfy.asr.ModelDescriptor
import com.phy4l.tcfy.asr.ModelInstallState
import com.phy4l.tcfy.audio.AudioSourceMode
import com.phy4l.tcfy.audio.DenoiseLevel
import com.phy4l.tcfy.ui.TcfyViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(viewModel: TcfyViewModel) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val modelStates by viewModel.modelStates.collectAsState()

    var pendingImportModel by remember { mutableStateOf<ModelDescriptor?>(null) }
    val modelFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val target = pendingImportModel
        if (uri != null && target != null) {
            viewModel.importLocalModel(target, uri)
        }
        pendingImportModel = null
    }

    // Local editable state for LLM API form
    var providerName by remember(settings.llmConfig.providerName) { mutableStateOf(settings.llmConfig.providerName) }
    var baseUrl by remember(settings.llmConfig.baseUrl) { mutableStateOf(settings.llmConfig.baseUrl) }
    var apiKey by remember(settings.llmConfig.apiKey) { mutableStateOf(settings.llmConfig.apiKey) }
    var modelName by remember(settings.llmConfig.modelName) { mutableStateOf(settings.llmConfig.modelName) }
    var liveOnlineTrans by remember(settings.llmConfig.enableLiveOnlineTranslation) {
        mutableStateOf(settings.llmConfig.enableLiveOnlineTranslation)
    }
    var testResultText by remember { mutableStateOf<String?>(null) }
    var isTestingApi by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "离线模型与设置",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        // Section 1: Offline Speech Models Manager
        SectionCard(title = "1. 本地离线语音模型（断网核心）") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("使用国内加速镜像 (hf-mirror.com)", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "国行手机直连更快；也可点击「本地导入」直接导入同学传的模型包",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.useDomesticMirror,
                    onCheckedChange = { viewModel.updateMirrorSettings(it, settings.customMirrorBase) }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            ModelCatalog.ALL_MODELS.forEach { model ->
                val state = modelStates[model.id] ?: ModelInstallState.NotInstalled
                ModelItemRow(
                    descriptor = model,
                    state = state,
                    onDownload = { viewModel.downloadModel(model) },
                    onImportLocal = {
                        pendingImportModel = model
                        modelFilePicker.launch(arrayOf("*/*"))
                    },
                    onDelete = { viewModel.deleteModel(model) }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            }
        }

        // Section 2: Far-Field Classroom Acoustic & ASR Tuning
        SectionCard(title = "2. 教室远场声学与识别调参 (适配天玑 810)") {
            Text("麦克风音源模式", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AudioSourceMode.entries.forEach { src ->
                    FilterChip(
                        selected = settings.dspConfig.audioSourceMode == src,
                        onClick = { viewModel.updateDspConfig(settings.dspConfig.copy(audioSourceMode = src)) },
                        label = { Text(src.displayName) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text("GTCRN 神经网络降噪强度", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DenoiseLevel.entries.forEach { level ->
                    FilterChip(
                        selected = settings.dspConfig.denoiseLevel == level,
                        onClick = { viewModel.updateDspConfig(settings.dspConfig.copy(denoiseLevel = level)) },
                        label = { Text(level.displayName) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "远场最大增益上限 (AGC): +${settings.dspConfig.maxGainDb.roundToInt()} dB",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = settings.dspConfig.maxGainDb,
                onValueChange = { viewModel.updateDspConfig(settings.dspConfig.copy(maxGainDb = it)) },
                valueRange = 6f..30f
            )

            Text(
                "Silero VAD 人声检测灵敏度: ${"%.2f".format(settings.dspConfig.vadThreshold)}（越低越适合 8-10m 后排）",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = settings.dspConfig.vadThreshold,
                onValueChange = { viewModel.updateDspConfig(settings.dspConfig.copy(vadThreshold = it)) },
                valueRange = 0.20f..0.60f
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("离线拼音音近术语纠错", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "自动将远场同音误词（如「学定额」→「薛定谔」）实时还原为术语库标准词",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.enablePinyinTermCorrection,
                    onCheckedChange = { viewModel.updatePinyinFix(it) }
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text("语音识别语种偏好", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AsrLanguageMode.entries.forEach { lang ->
                    FilterChip(
                        selected = settings.asrLanguageMode == lang,
                        onClick = { viewModel.updateAsrLanguage(lang) },
                        label = { Text(lang.displayName) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text("离线推理 CPU 线程数（红米 Note 11 5G 推荐 4 线程）", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2, 4, 6).forEach { t ->
                    FilterChip(
                        selected = settings.asrThreads == t,
                        onClick = { viewModel.updateAsrThreads(t) },
                        label = { Text("${t} 线程") }
                    )
                }
            }
        }

        // Section 3: OpenAI-Compatible AI API Settings
        SectionCard(title = "3. 课后 AI 术语精修与总结接口 (OpenAI 兼容)") {
            Text("一键套用常见接口预设", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                LlmPresets.ALL.forEach { preset ->
                    FilterChip(
                        selected = providerName == preset.name,
                        onClick = {
                            providerName = preset.name
                            baseUrl = preset.baseUrl
                            modelName = preset.defaultModel
                        },
                        label = { Text(preset.name) }
                    )
                }
            }

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key（仅存本机，不上传第三方）") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = modelName,
                onValueChange = { modelName = it },
                label = { Text("模型名称（如 deepseek-chat / qwen-plus）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("有网时开启实时 AI 翻译", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "超时 4 秒或断网会自动无缝降级为离线术语注译，绝不丢字",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = liveOnlineTrans,
                    onCheckedChange = { liveOnlineTrans = it }
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        viewModel.updateLlmConfig(
                            settings.llmConfig.copy(
                                providerName = providerName,
                                baseUrl = baseUrl,
                                apiKey = apiKey,
                                modelName = modelName,
                                enableLiveOnlineTranslation = liveOnlineTrans
                            )
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("保存接口配置")
                }
                OutlinedButton(
                    onClick = {
                        isTestingApi = true
                        testResultText = "测试连接中..."
                        viewModel.testLlmConnection(
                            settings.llmConfig.copy(
                                providerName = providerName,
                                baseUrl = baseUrl,
                                apiKey = apiKey,
                                modelName = modelName
                            )
                        ) { _, msg ->
                            isTestingApi = false
                            testResultText = msg
                        }
                    },
                    enabled = !isTestingApi,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("测试连接")
                }
            }
            testResultText?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Section 4: Redmi / MIUI / HyperOS Keep-Alive Helper
        SectionCard(title = "4. 红米 MIUI / HyperOS 息屏防杀设置") {
            val pm = context.getSystemService(PowerManager::class.java)
            val isIgnoring = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
            Text(
                text = if (isIgnoring) {
                    "已加入系统电池优化白名单，可放心息屏录制 90 分钟大课。"
                } else {
                    "建议将「省电策略」设为「无限制」，避免 MIUI 在息屏听课 10 分钟后冻结录音。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { openBatteryExemptionSettings(context) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (isIgnoring) "查看电池优化设置" else "一键前往设置省电白名单")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun ModelItemRow(
    descriptor: ModelDescriptor,
    state: ModelInstallState,
    onDownload: () -> Unit,
    onImportLocal: () -> Unit,
    onDelete: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${descriptor.displayName} (~${descriptor.approxSizeMb}MB)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = descriptor.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state is ModelInstallState.Installed) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "已安装",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        when (state) {
            is ModelInstallState.Downloading -> {
                Text(state.statusText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                LinearProgressIndicator(
                    progress = { state.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            is ModelInstallState.Installed -> {
                val mb = state.totalBytes / (1024f * 1024f)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "已就绪 (${"%.1f".format(mb)} MB · 纯离线运行)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "删除模型", modifier = Modifier.size(18.dp))
                    }
                }
            }
            is ModelInstallState.Error -> {
                Text(state.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onDownload) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("重试下载")
                    }
                    OutlinedButton(onClick = onImportLocal) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("本地导入")
                    }
                }
            }
            ModelInstallState.NotInstalled -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onDownload) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("下载模型")
                    }
                    OutlinedButton(onClick = onImportLocal) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("本地导入")
                    }
                }
            }
        }
    }
}

@SuppressLint("BatteryLife")
private fun openBatteryExemptionSettings(context: android.content.Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }
    runCatching {
        context.startActivity(intent)
    }.onFailure {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
