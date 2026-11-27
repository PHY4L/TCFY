package com.phy4l.tcfy.asr

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

sealed class ModelInstallState {
    data object NotInstalled : ModelInstallState()
    data class Downloading(val progress: Float, val statusText: String) : ModelInstallState()
    data class Installed(val totalBytes: Long, val isBundled: Boolean = false) : ModelInstallState()
    data class Error(val message: String) : ModelInstallState()
}

/**
 * Manages on-device offline speech models (SenseVoice, Paraformer-ZH, Whisper-EN, Wenet-Yue,
 * plus built-in <1MB Silero VAD & GTCRN).
 *
 * Designed for mainland China Android phones without GMS:
 * - Automatically extracts built-in <1MB VAD & GTCRN models from APK assets on first launch
 * - Concurrently probes all domestic + global mirrors before downloading and sorts fastest-first
 * - Automatically falls back across HF direct mirrors & GitHub Release proxy archives on failure
 * - Supports local file import (.onnx / .zip / .tar.bz2 / *tokens.txt) shared via WeChat/QQ/USB
 */
class ModelManager(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
    private val mirrorSelector: MirrorLatencySelector = MirrorLatencySelector()
) {
    private val modelsRootDir: File = File(context.filesDir, "models").apply { mkdirs() }

    private val _states = MutableStateFlow<Map<String, ModelInstallState>>(emptyMap())
    val states: StateFlow<Map<String, ModelInstallState>> = _states.asStateFlow()

    init {
        ensureBundledModelsInstalled()
        refreshAllStates()
    }

    /**
     * Copies the two <1MB support models (Silero VAD + GTCRN) bundled inside APK assets
     * into app local storage if missing, making them 100% zero-download out-of-the-box.
     */
    fun ensureBundledModelsInstalled() {
        for (descriptor in ModelCatalog.SUPPORT_MODELS) {
            val assetPath = descriptor.bundledAssetPath ?: continue
            val destFile = getPrimaryModelFile(descriptor)
            if (destFile.exists() && destFile.length() > 1024L) continue
            runCatching {
                getModelDir(descriptor).mkdirs()
                val tmp = File(destFile.parentFile, "${destFile.name}.tmp")
                context.assets.open(assetPath).use { input ->
                    FileOutputStream(tmp).use { output -> input.copyTo(output) }
                }
                if (destFile.exists()) destFile.delete()
                if (!tmp.renameTo(destFile)) {
                    tmp.copyTo(destFile, overwrite = true)
                    tmp.delete()
                }
            }
        }
    }

    fun getModelDir(descriptor: ModelDescriptor): File = File(modelsRootDir, descriptor.folderName)

    fun getPrimaryModelFile(descriptor: ModelDescriptor): File =
        File(getModelDir(descriptor), descriptor.primaryFileName)

    fun getTokensFile(descriptor: ModelDescriptor): File =
        File(getModelDir(descriptor), descriptor.tokensFileName)

    fun isInstalled(descriptor: ModelDescriptor): Boolean {
        val dir = getModelDir(descriptor)
        if (!File(dir, descriptor.primaryFileName).exists()) return false
        return descriptor.extraRequiredFiles.all { File(dir, it).exists() }
    }

    /**
     * Ready as soon as Silero VAD (bundled) AND at least one offline ASR model are installed.
     */
    fun isCoreAsrReady(): Boolean =
        isInstalled(ModelCatalog.SILERO_VAD) && ModelCatalog.ASR_MODELS.any { isInstalled(it) }

    /**
     * Resolves the active offline ASR model: prefers [preferredModelId] if installed,
     * otherwise falls back to the first installed ASR model in [ModelCatalog.ASR_MODELS].
     */
    fun resolveActiveAsrModel(preferredModelId: String): ModelDescriptor? {
        val preferred = ModelCatalog.findById(preferredModelId)
        if (preferred != null && preferred.type.isAsrModel && isInstalled(preferred)) {
            return preferred
        }
        return ModelCatalog.ASR_MODELS.firstOrNull { isInstalled(it) }
    }

    fun refreshAllStates() {
        val map = ModelCatalog.ALL_MODELS.associate { model ->
            model.id to if (isInstalled(model)) {
                val size = getModelDir(model).walkTopDown().filter { it.isFile }.sumOf { it.length() }
                ModelInstallState.Installed(size, isBundled = model.bundledAssetPath != null)
            } else {
                ModelInstallState.NotInstalled
            }
        }
        _states.value = map
    }

    private fun updateState(modelId: String, state: ModelInstallState) {
        _states.value = _states.value.toMutableMap().apply { put(modelId, state) }
    }

    /**
     * Probes all built-in + custom mirror endpoints against lightweight files so the UI can show
     * live RTT latencies sorted from fastest to slowest.
     */
    suspend fun probeMirrorLatencies(
        useDomesticMirror: Boolean = true,
        customMirrorBase: String = ""
    ): List<MirrorProbeResult> {
        val hfCandidates = mirrorSelector.buildCandidatesForFile(
            entry = ModelCatalog.PARAFORMER_ZH_SMALL_INT8.directFiles.last(), // tiny tokens.txt
            useDomesticMirror = useDomesticMirror,
            customMirrorBase = customMirrorBase
        )
        val ghCandidates = mirrorSelector.buildArchiveCandidates(
            archiveDirectUrl = ModelCatalog.SILERO_VAD.directFiles.first().directUrl,
            useDomesticMirror = useDomesticMirror
        ).map { it.copy(isArchive = false) }
        return mirrorSelector.probeAndRank((hfCandidates + ghCandidates).distinctBy { it.mirror.id })
    }

    suspend fun downloadModel(
        descriptor: ModelDescriptor,
        useDomesticMirror: Boolean = true,
        customMirrorBase: String = ""
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // If this is a bundled <1MB model, restore instantly from APK assets!
            if (descriptor.bundledAssetPath != null) {
                ensureBundledModelsInstalled()
                if (isInstalled(descriptor)) {
                    val totalSize = getModelDir(descriptor).walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    updateState(descriptor.id, ModelInstallState.Installed(totalSize, isBundled = true))
                    return@runCatching
                }
            }

            val targetDir = getModelDir(descriptor).apply { mkdirs() }
            val files = descriptor.directFiles
            require(files.isNotEmpty() || !descriptor.archiveDirectUrl.isNullOrBlank()) { "模型未配置下载链接" }

            updateState(
                descriptor.id,
                ModelInstallState.Downloading(0.02f, "正在并发测速可用镜像源...")
            )

            // Probe primary file HF mirrors + GitHub archive mirrors concurrently
            val primaryFileCandidates = if (files.isNotEmpty()) {
                mirrorSelector.buildCandidatesForFile(files.first(), useDomesticMirror, customMirrorBase)
            } else emptyList()
            val archiveCandidates = mirrorSelector.buildArchiveCandidates(descriptor.archiveDirectUrl, useDomesticMirror)

            val rankedProbes = mirrorSelector.probeAndRank(primaryFileCandidates + archiveCandidates)
            val reachableSummary = rankedProbes.filter { it.reachable }
                .take(3)
                .joinToString(" / ") { "${it.candidate.mirror.label}(${it.latencyMs}ms)" }

            if (reachableSummary.isNotEmpty()) {
                updateState(
                    descriptor.id,
                    ModelInstallState.Downloading(0.05f, "测速优选：$reachableSummary")
                )
            }

            val failureLog = mutableListOf<String>()
            var completed = false

            // Try each ranked source strictly from lowest latency to highest latency
            for (probe in rankedProbes) {
                val mirror = probe.candidate.mirror
                val latencyHint = if (probe.reachable) "${probe.latencyMs}ms" else "兜底尝试"
                val attempt = runCatching {
                    if (probe.candidate.isArchive) {
                        downloadAndExtractArchive(
                            descriptor = descriptor,
                            archiveUrl = probe.candidate.url,
                            mirrorLabel = "${mirror.label} ($latencyHint)",
                            targetDir = targetDir
                        )
                    } else {
                        downloadDirectFilesFromMirror(
                            descriptor = descriptor,
                            mirror = mirror,
                            latencyHint = latencyHint,
                            targetDir = targetDir
                        )
                    }
                    require(isInstalled(descriptor)) { "校验失败，缺少必需模型文件" }
                }
                if (attempt.isSuccess) {
                    completed = true
                    break
                } else {
                    failureLog.add("${mirror.label}: ${attempt.exceptionOrNull()?.message?.take(48) ?: "失败"}")
                }
            }

            if (!completed) {
                val detail = failureLog.take(3).joinToString("；")
                throw IllegalStateException("所有镜像源下载均失败（可改用「本地导入」）：$detail")
            }

            val totalSize = targetDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            updateState(descriptor.id, ModelInstallState.Installed(totalSize, isBundled = descriptor.bundledAssetPath != null))
        }.onFailure { err ->
            updateState(descriptor.id, ModelInstallState.Error(err.message ?: "下载失败"))
        }
    }

    private fun downloadDirectFilesFromMirror(
        descriptor: ModelDescriptor,
        mirror: MirrorEndpoint,
        latencyHint: String,
        targetDir: File
    ) {
        val files = descriptor.directFiles
        files.forEachIndexed { idx, entry ->
            val resolvedUrl = when {
                mirror.kind == MirrorKind.HUGGINGFACE_BASE && mirror.baseUrl.isNotEmpty() &&
                    entry.directUrl.startsWith(MirrorLatencySelector.HF_OFFICIAL_BASE) ->
                    entry.directUrl.replace(MirrorLatencySelector.HF_OFFICIAL_BASE, mirror.baseUrl.trimEnd('/'))
                mirror.kind == MirrorKind.GITHUB_PREFIX &&
                    entry.directUrl.startsWith(MirrorLatencySelector.GH_OFFICIAL_BASE) ->
                    if (mirror.baseUrl.isEmpty()) entry.directUrl else "${mirror.baseUrl.trimEnd('/')}/${entry.directUrl}"
                else -> entry.directUrl
            }

            val destFile = File(targetDir, entry.relativePath)
            val tempFile = File(targetDir, "${entry.relativePath}.part")
            val baseFraction = 0.06f + 0.92f * (idx.toFloat() / files.size)
            val fileWeight = 0.92f / files.size

            updateState(
                descriptor.id,
                ModelInstallState.Downloading(
                    progress = baseFraction,
                    statusText = "[${mirror.label} · $latencyHint] 下载 ${entry.relativePath} (${idx + 1}/${files.size})..."
                )
            )
            downloadFileWithProgress(resolvedUrl, tempFile) { filePct ->
                val overall = (baseFraction + filePct * fileWeight).coerceIn(0f, 0.99f)
                updateState(
                    descriptor.id,
                    ModelInstallState.Downloading(
                        progress = overall,
                        statusText = "[${mirror.label}] ${entry.relativePath} ${(filePct * 100).toInt()}%"
                    )
                )
            }
            if (destFile.exists()) destFile.delete()
            if (!tempFile.renameTo(destFile)) {
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()
            }
        }
    }

    private fun downloadAndExtractArchive(
        descriptor: ModelDescriptor,
        archiveUrl: String,
        mirrorLabel: String,
        targetDir: File
    ) {
        val archiveTmp = File(targetDir, "archive_download.tar.bz2.part")
        try {
            updateState(
                descriptor.id,
                ModelInstallState.Downloading(0.06f, "[$mirrorLabel] 下载压缩包...")
            )
            downloadFileWithProgress(archiveUrl, archiveTmp) { pct ->
                updateState(
                    descriptor.id,
                    ModelInstallState.Downloading(
                        progress = (0.06f + pct * 0.78f).coerceIn(0.06f, 0.85f),
                        statusText = "[$mirrorLabel] 下载模型包 ${(pct * 100).toInt()}%"
                    )
                )
            }
            updateState(
                descriptor.id,
                ModelInstallState.Downloading(0.88f, "正在解压模型文件...")
            )
            val allowedNames = (listOf(descriptor.primaryFileName) + descriptor.extraRequiredFiles).toSet()
            FileInputStream(archiveTmp).use { fis ->
                extractTarBz2Flat(fis, targetDir, allowedNames)
            }
        } finally {
            runCatching { archiveTmp.delete() }
        }
    }

    /**
     * Imports a local model file (.onnx / .zip / .tar.bz2 / *tokens.txt) selected via Android SAF.
     * Classmates can share downloaded model archives via WeChat/QQ/USB and import offline!
     */
    suspend fun importLocalModel(
        descriptor: ModelDescriptor,
        uri: Uri,
        fileNameHint: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val targetDir = getModelDir(descriptor).apply { mkdirs() }
            val lower = fileNameHint.lowercase()
            val allowedNames = (listOf(descriptor.primaryFileName) + descriptor.extraRequiredFiles).toSet()
            updateState(descriptor.id, ModelInstallState.Downloading(0.2f, "正在导入本地文件..."))

            context.contentResolver.openInputStream(uri).use { rawStream ->
                requireNotNull(rawStream) { "无法读取所选文件" }
                when {
                    lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") -> {
                        extractTarBz2Flat(rawStream, targetDir, allowedNames)
                    }
                    lower.endsWith(".zip") -> {
                        extractZipFlat(rawStream, targetDir, allowedNames)
                    }
                    lower.endsWith(".onnx") -> {
                        val targetName = if (fileNameHint in allowedNames) fileNameHint else descriptor.primaryFileName
                        val dest = File(targetDir, targetName)
                        FileOutputStream(dest).use { out -> rawStream.copyTo(out) }
                    }
                    lower.endsWith("tokens.txt") -> {
                        val dest = File(targetDir, descriptor.tokensFileName)
                        FileOutputStream(dest).use { out -> rawStream.copyTo(out) }
                    }
                    else -> {
                        throw IllegalArgumentException("不支持的文件格式: $fileNameHint (请导入 .tar.bz2 / .zip / .onnx / tokens.txt)")
                    }
                }
            }

            if (isInstalled(descriptor)) {
                val totalSize = targetDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                updateState(descriptor.id, ModelInstallState.Installed(totalSize, isBundled = descriptor.bundledAssetPath != null))
            } else {
                val missing = (listOf(descriptor.primaryFileName) + descriptor.extraRequiredFiles)
                    .filterNot { File(targetDir, it).exists() }
                throw IllegalStateException("已导入部分文件，还缺少: ${missing.joinToString(", ")}")
            }
        }.onFailure { err ->
            updateState(descriptor.id, ModelInstallState.Error(err.message ?: "导入失败"))
        }
    }

    fun deleteModel(descriptor: ModelDescriptor) {
        if (descriptor.bundledAssetPath != null) {
            // Re-extract bundled model instead of breaking built-in support
            ensureBundledModelsInstalled()
            refreshAllStates()
            return
        }
        getModelDir(descriptor).deleteRecursively()
        refreshAllStates()
    }

    private fun downloadFileWithProgress(
        url: String,
        destFile: File,
        onProgress: (Float) -> Unit
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "TCFY-Android/1.1")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} 下载失败")
            }
            val body = response.body ?: throw IllegalStateException("空响应体")
            val contentLength = body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var downloaded = 0L
                    var lastReport = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (contentLength > 0 && downloaded - lastReport > 256 * 1024) {
                            onProgress((downloaded.toFloat() / contentLength).coerceIn(0f, 1f))
                            lastReport = downloaded
                        }
                    }
                    output.flush()
                    onProgress(1f)
                }
            }
        }
    }

    private fun extractTarBz2Flat(input: InputStream, targetDir: File, allowedNames: Set<String>) {
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(input))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                if (entry.isDirectory) continue
                val baseName = File(entry.name).name
                val shouldKeep = if (allowedNames.isNotEmpty()) {
                    baseName in allowedNames
                } else {
                    baseName.endsWith(".onnx") || baseName.endsWith("tokens.txt")
                }
                if (shouldKeep) {
                    val outFile = File(targetDir, baseName)
                    FileOutputStream(outFile).use { out -> tar.copyTo(out) }
                }
            }
        }
    }

    private fun extractZipFlat(input: InputStream, targetDir: File, allowedNames: Set<String>) {
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val baseName = File(entry.name).name
                val shouldKeep = if (allowedNames.isNotEmpty()) {
                    baseName in allowedNames
                } else {
                    baseName.endsWith(".onnx") || baseName.endsWith("tokens.txt")
                }
                if (shouldKeep) {
                    val outFile = File(targetDir, baseName)
                    FileOutputStream(outFile).use { out -> zip.copyTo(out) }
                }
                zip.closeEntry()
            }
        }
    }
}
