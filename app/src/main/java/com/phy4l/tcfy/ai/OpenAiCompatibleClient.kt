package com.phy4l.tcfy.ai

import com.phy4l.tcfy.data.prefs.LlmApiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.2,
    @SerialName("max_tokens") val maxTokens: Int? = null
)

@Serializable
data class ChatCompletionResponse(
    val choices: List<Choice> = emptyList()
) {
    @Serializable
    data class Choice(
        val message: ChatMessage? = null
    )
}

/**
 * Lightweight OpenAI-compatible Chat Completions client built on OkHttp.
 */
class OpenAiCompatibleClient(
    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun chatCompletion(
        config: LlmApiConfig,
        messages: List<ChatMessage>,
        temperature: Double = 0.2,
        maxTokens: Int? = null,
        timeoutSeconds: Long? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            return@withContext Result.failure(IllegalStateException("尚未配置 API Key 或接口地址"))
        }

        val client = if (timeoutSeconds != null) {
            baseClient.newBuilder()
                .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .connectTimeout(minOf(timeoutSeconds, 8L), TimeUnit.SECONDS)
                .build()
        } else {
            baseClient
        }

        val endpoint = buildEndpointUrl(config.baseUrl)
        val payload = ChatCompletionRequest(
            model = config.modelName.trim(),
            messages = messages,
            temperature = temperature,
            maxTokens = maxTokens
        )

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${config.apiKey.trim()}")
            .header("Content-Type", "application/json")
            .post(json.encodeToString(payload).toRequestBody(mediaType))
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val snippet = bodyStr.take(240).ifBlank { response.message }
                    throw IOException("API 请求失败 (HTTP ${response.code}): $snippet")
                }
                val parsed = json.decodeFromString<ChatCompletionResponse>(bodyStr)
                val content = parsed.choices.firstOrNull()?.message?.content?.trim()
                if (content.isNullOrEmpty()) {
                    throw IOException("API 返回内容为空")
                }
                content
            }
        }
    }

    suspend fun testConnection(config: LlmApiConfig): Result<String> {
        return chatCompletion(
            config = config,
            messages = listOf(
                ChatMessage("user", "Reply with only: OK")
            ),
            temperature = 0.0,
            maxTokens = 16,
            timeoutSeconds = 12L
        )
    }

    companion object {
        fun buildEndpointUrl(baseUrl: String): String {
            val cleaned = baseUrl.trim().trimEnd('/')
            return if (cleaned.endsWith("/chat/completions")) {
                cleaned
            } else {
                "$cleaned/chat/completions"
            }
        }
    }
}
