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
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

sealed class ModelInstallState {
    data object NotInstalled : ModelInstallState()
    data class Downloading(val progress: Float, val statusText: String) : ModelInstallState()
    data class Installed(val totalBytes: Long) : ModelInstallState()
    data class Error(val message: String) : ModelInstallState()
}

/**
 * Manages on-device offline speech models (SenseVoice, Silero VAD, GTCRN).
 *
 * Designed for mainland China Android phones without GMS:
 * - Supports domestic mirrors (hf-mirror.com) and direct sources
 * - Supports local file import (.onnx / .zip / .tar.bz2) shared via WeChat/QQ/USB
 */
class ModelManager(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    private val modelsRootDir: File = File(context.filesDir, "models").apply { mkdirs() }

    private val _states = MutableStateFlow<Map<String, ModelInstallState>>(emptyMap())
    val states: StateFlow<Map<String, ModelInstallState>> = _states.asStateFlow()

    init {
        refreshAllStates()
    }

    fun getModelDir(descriptor: ModelDescriptor): File = File(modelsRootDir, descriptor.folderName)

    fun getPrimaryModelFile(descriptor: ModelDescriptor): File =
        File(getModelDir(descriptor), descriptor.primaryFileName)

    fun getTokensFile(descriptor: ModelDescriptor): File =
        File(getModelDir(descriptor), "tokens.txt")

    fun isInstalled(descriptor: ModelDescriptor): Boolean {
        val dir = getModelDir(descriptor)
        if (!File(dir, descriptor.primaryFileName).exists()) return false
        return descriptor.extraRequiredFiles.all { File(dir, it).exists() }
    }

    fun isCoreAsrReady(): Boolean =
        isInstalled(ModelCatalog.SENSEVOICE_INT8) && isInstalled(ModelCatalog.SILERO_VAD)

    fun refreshAllStates() {
        val map = ModelCatalog.ALL_MODELS.associate { model ->
            model.id to if (isInstalled(model)) {
                val size = getModelDir(model).walkTopDown().filter { it.isFile }.sumOf { it.length() }
                ModelInstallState.Installed(size)
            } else {
                ModelInstallState.NotInstalled
            }
        }
        _states.value = map
    }

    private fun updateState(modelId: String, state: ModelInstallState) {
        _states.value = _states.value.toMutableMap().apply { put(modelId, state) }
    }

    suspend fun downloadModel(
        descriptor: ModelDescriptor,
        useDomesticMirror: Boolean = true,
        customMirrorBase: String = ""
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val targetDir = getModelDir(descriptor).apply { mkdirs() }
            val files = descriptor.directFiles
            require(files.isNotEmpty()) { "模型未配置下载链接" }

            files.forEachIndexed { idx, entry ->
                val url = resolveUrl(entry, useDomesticMirror, customMirrorBase)
                val destFile = File(targetDir, entry.relativePath)
                val tempFile = File(targetDir, "${entry.relativePath}.part")
                val baseFraction = idx.toFloat() / files.size
                val fileWeight = 1f / files.size

                updateState(
                    descriptor.id,
                    ModelInstallState.Downloading(
                        progress = baseFraction,
                        statusText = "正在下载 ${entry.relativePath} (${idx + 1}/${files.size})..."
                    )
                )
                downloadFileWithProgress(url, tempFile) { filePct ->
                    val overall = (baseFraction + filePct * fileWeight).coerceIn(0f, 0.99f)
                    updateState(
                        descriptor.id,
                        ModelInstallState.Downloading(
                            progress = overall,
                            statusText = "下载 ${entry.relativePath} ${(filePct * 100).toInt()}%"
                        )
                    )
                }
                if (destFile.exists()) destFile.delete()
                if (!tempFile.renameTo(destFile)) {
                    tempFile.copyTo(destFile, overwrite = true)
                    tempFile.delete()
                }
            }

            require(isInstalled(descriptor)) { "模型校验失败，文件不完整" }
            val totalSize = targetDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            updateState(descriptor.id, ModelInstallState.Installed(totalSize))
        }.onFailure { err ->
            updateState(descriptor.id, ModelInstallState.Error(err.message ?: "下载失败"))
        }
    }

    /**
     * Imports a local model file (.onnx / .zip / .tar.bz2 / tokens.txt) selected via Android SAF.
     * Classmates can share downloaded SenseVoice archives via WeChat/QQ/USB and import offline!
     */
    suspend fun importLocalModel(
        descriptor: ModelDescriptor,
        uri: Uri,
        fileNameHint: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val targetDir = getModelDir(descriptor).apply { mkdirs() }
            val lower = fileNameHint.lowercase()
            updateState(descriptor.id, ModelInstallState.Downloading(0.2f, "正在导入本地文件..."))

            context.contentResolver.openInputStream(uri).use { rawStream ->
                requireNotNull(rawStream) { "无法读取所选文件" }
                when {
                    lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") -> {
                        extractTarBz2Flat(rawStream, targetDir)
                    }
                    lower.endsWith(".zip") -> {
                        extractZipFlat(rawStream, targetDir)
                    }
                    lower.endsWith(".onnx") -> {
                        val dest = File(targetDir, descriptor.primaryFileName)
                        FileOutputStream(dest).use { out -> rawStream.copyTo(out) }
                    }
                    lower == "tokens.txt" -> {
                        val dest = File(targetDir, "tokens.txt")
                        FileOutputStream(dest).use { out -> rawStream.copyTo(out) }
                    }
                    else -> {
                        throw IllegalArgumentException("不支持的文件格式: $fileNameHint (请导入 .tar.bz2 / .zip / .onnx / tokens.txt)")
                    }
                }
            }

            if (isInstalled(descriptor)) {
                val totalSize = targetDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                updateState(descriptor.id, ModelInstallState.Installed(totalSize))
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
        getModelDir(descriptor).deleteRecursively()
        refreshAllStates()
    }

    private fun resolveUrl(
        entry: RemoteFileEntry,
        useDomesticMirror: Boolean,
        customMirrorBase: String
    ): String {
        val trimmedCustom = customMirrorBase.trim().trimEnd('/')
        if (trimmedCustom.isNotEmpty() && entry.directUrl.startsWith("https://huggingface.co/")) {
            return entry.directUrl.replace("https://huggingface.co", trimmedCustom)
        }
        return if (useDomesticMirror) entry.mirrorUrl else entry.directUrl
    }

    private fun downloadFileWithProgress(
        url: String,
        destFile: File,
        onProgress: (Float) -> Unit
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "TCFY-Android/1.0")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} 下载失败: $url")
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

    private fun extractTarBz2Flat(input: InputStream, targetDir: File) {
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(input))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                if (entry.isDirectory) continue
                val baseName = File(entry.name).name
                if (baseName.endsWith(".onnx") || baseName == "tokens.txt") {
                    val outFile = File(targetDir, baseName)
                    FileOutputStream(outFile).use { out -> tar.copyTo(out) }
                }
            }
        }
    }

    private fun extractZipFlat(input: InputStream, targetDir: File) {
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val baseName = File(entry.name).name
                if (baseName.endsWith(".onnx") || baseName == "tokens.txt") {
                    val outFile = File(targetDir, baseName)
                    FileOutputStream(outFile).use { out -> zip.copyTo(out) }
                }
                zip.closeEntry()
            }
        }
    }
}
