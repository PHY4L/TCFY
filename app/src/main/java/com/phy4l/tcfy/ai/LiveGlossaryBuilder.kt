package com.phy4l.tcfy.ai

import com.phy4l.tcfy.data.db.TranscriptSegmentEntity
import com.phy4l.tcfy.data.prefs.LlmApiConfig
import com.phy4l.tcfy.glossary.GlossaryTerm
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GeneratedGlossaryItem(
    @SerialName("canonical_term") val canonicalTerm: String = "",
    val translation: String = "",
    val aliases: List<String> = emptyList(),
    val notes: String = ""
)

@Serializable
data class LiveGlossaryPayload(
    val topic: String = "",
    val discipline: String = "",
    val terms: List<GeneratedGlossaryItem> = emptyList()
)

data class LiveGlossaryBuildResult(
    val topic: String,
    val discipline: String,
    val packName: String,
    val description: String,
    val terms: List<GlossaryTerm>
)

/**
 * Pure JVM on-the-fly lecture glossary builder.
 *
 * Reads the first few minutes of live transcript, asks the configured OpenAI-compatible LLM
 * to infer the lecture topic/discipline and generate 15-30 high-frequency domain terms
 * (including likely far-field ASR misrecognition aliases), then returns a deduplicated
 * glossary ready to be persisted into Room and used immediately by OfflineTermCorrector.
 *
 * Strictly optional & non-blocking: offline transcription never depends on this builder.
 */
class LiveGlossaryBuilder(
    private val llmClient: OpenAiCompatibleClient
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun buildFromTranscript(
        segments: List<TranscriptSegmentEntity>,
        existingActiveTerms: List<GlossaryTerm>,
        llmConfig: LlmApiConfig,
        timeoutSeconds: Long = 28L
    ): Result<LiveGlossaryBuildResult> {
        if (!llmConfig.isConfigured) {
            return Result.failure(IllegalStateException("请先在「模型设置」中配置大模型 API Key，才能使用 AI 现场建词库"))
        }

        val combined = segments
            .joinToString("\n") { it.displaySourceText.trim() }
            .trim()
            .take(3600)

        val cleanLen = combined.replace(Regex("[\\p{P}\\p{S}\\s]+"), "").length
        if (cleanLen < 25) {
            return Result.failure(IllegalStateException("当前转写内容过少（不足 25 字），请稍等讲师多讲几句后再构建术语库"))
        }

        val systemPrompt = """
你是高校课堂实时语音转写的专业领域术语工程专家。
下面提供的是某场课程/学术汇报前几分钟的教室远场离线语音识别文本（可能存在同音错别字、中英混杂误转）。
请你完成以下任务：
1. 判断这场课/汇报的学科领域与具体主题（topic 控制在 12 字以内，例如「量子力学·含时微扰论」或「CRISPR基因编辑」）；
2. 基于该主题与上下文，提炼并拓展 18~28 个「本堂课及后续讲解极高概率出现」的核心专业术语（不仅限于前几分钟已出现的词，请主动补全该专题最常用专业词汇、公式名、英文缩写与核心概念）；
3. 为每个术语提供：
   - canonical_term：标准写法（中英混杂课堂常用英文标准词或中文标准词）
   - translation：对应中/英文规范译名
   - aliases：2~4 个远场语音识别常见同音错别字或中英谐音误转词（例如 "端粒酶" -> ["端粒没","端力梅"]，"Hamiltonian" -> ["哈米顿","hamitonian"]）
   - notes：简短学科释义（15 字以内）
4. 严格只输出 JSON 对象，禁止任何多余解释文字。格式：
{"topic":"主题","discipline":"学科","terms":[{"canonical_term":"...","translation":"...","aliases":["..."],"notes":"..."}]}
""".trimIndent()

        val userPrompt = "【前几分钟课堂转写】\n$combined"

        val replyRes = llmClient.chatCompletion(
            config = llmConfig,
            messages = listOf(
                ChatMessage("system", systemPrompt),
                ChatMessage("user", userPrompt)
            ),
            temperature = 0.25,
            timeoutSeconds = timeoutSeconds
        )

        val raw = replyRes.getOrElse { err ->
            return Result.failure(IllegalStateException("AI 现场建词库请求失败：${err.message}", err))
        }

        val payload = parsePayload(raw)
            ?: return Result.failure(IllegalStateException("AI 返回的术语 JSON 格式无法解析，请稍后重试"))

        val existingKeys = existingActiveTerms
            .map { it.canonicalTerm.trim().lowercase() }
            .toMutableSet()

        val cleanedTerms = mutableListOf<GlossaryTerm>()
        for (item in payload.terms) {
            val canonical = item.canonicalTerm.trim()
            if (canonical.length < 2) continue
            val key = canonical.lowercase()
            if (!existingKeys.add(key)) continue

            val aliases = item.aliases
                .map { it.trim() }
                .filter { it.length >= 2 && !it.equals(canonical, ignoreCase = true) }
                .distinct()
                .take(6)

            cleanedTerms.add(
                GlossaryTerm(
                    canonicalTerm = canonical,
                    translation = item.translation.trim(),
                    aliases = aliases,
                    notes = item.notes.trim()
                )
            )
        }

        if (cleanedTerms.isEmpty()) {
            return Result.failure(IllegalStateException("未提炼出新的有效专业术语（可能已全部在当前术语库中）"))
        }

        val topic = payload.topic.trim().ifBlank { "课堂专题" }.take(20)
        val discipline = payload.discipline.trim().ifBlank { "通用学术" }.take(16)
        return Result.success(
            LiveGlossaryBuildResult(
                topic = topic,
                discipline = discipline,
                packName = "AI现场 · $topic",
                description = "由大模型根据本课前几分钟转写自动推断（$discipline），共 ${cleanedTerms.size} 条高频术语",
                terms = cleanedTerms
            )
        )
    }

    internal fun parsePayload(raw: String): LiveGlossaryPayload? {
        val trimmed = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val candidate = trimmed.substring(start, end + 1)
        return runCatching { json.decodeFromString<LiveGlossaryPayload>(candidate) }.getOrNull()
    }

    companion object {
        /**
         * Policy for automatic live glossary triggering after the first ~1.5-3 minutes of lecture.
         */
        fun shouldAutoTrigger(
            elapsedMs: Long,
            totalCleanChars: Int,
            alreadyBuiltForSession: Boolean
        ): Boolean {
            if (alreadyBuiltForSession) return false
            return (elapsedMs >= 90_000L && totalCleanChars >= 160) ||
                (elapsedMs >= 180_000L && totalCleanChars >= 60)
        }
    }
}
