package com.phy4l.tcfy.ai

import com.phy4l.tcfy.data.db.TranslationDirection
import com.phy4l.tcfy.data.prefs.LlmApiConfig
import com.phy4l.tcfy.glossary.GlossaryTerm
import com.phy4l.tcfy.glossary.PhoneticUtils

data class TranslationOutcome(
    val translatedText: String,
    val usedOnline: Boolean,
    val matchedGlossaryPairs: List<Pair<String, String>> = emptyList()
)

/**
 * Protects domain glossary terms with deterministic placeholder tokens prior to machine translation,
 * then restores the exact user-specified target translations so general translators never mistranslate jargon.
 */
object GlossaryPlaceholderProtector {

    data class ProtectedPayload(
        val protectedText: String,
        val tokenToTarget: Map<String, String>,
        val matchedPairs: List<Pair<String, String>>
    )

    fun protect(
        sourceText: String,
        terms: List<GlossaryTerm>,
        targetIsChinese: Boolean
    ): ProtectedPayload {
        if (sourceText.isBlank() || terms.isEmpty()) {
            return ProtectedPayload(sourceText, emptyMap(), emptyList())
        }

        // Build directional lookup pairs: (sourcePhrase -> preferredTargetPhrase)
        val pairs = mutableListOf<Pair<String, String>>()
        for (term in terms) {
            val canon = term.canonicalTerm.trim()
            val trans = term.translation.trim()
            if (canon.isEmpty() || trans.isEmpty()) continue
            val canonIsCjk = PhoneticUtils.containsCjk(canon)
            val transIsCjk = PhoneticUtils.containsCjk(trans)
            when {
                targetIsChinese && !canonIsCjk && transIsCjk -> pairs.add(canon to trans)
                targetIsChinese && canonIsCjk && !transIsCjk -> pairs.add(trans to canon)
                !targetIsChinese && canonIsCjk && !transIsCjk -> pairs.add(canon to trans)
                !targetIsChinese && !canonIsCjk && transIsCjk -> pairs.add(trans to canon)
                else -> pairs.add(canon to trans)
            }
        }

        val sortedPairs = pairs.distinctBy { it.first.lowercase() }
            .sortedByDescending { it.first.length }

        var working = sourceText
        val tokenMap = linkedMapOf<String, String>()
        val matched = mutableListOf<Pair<String, String>>()
        var tokenIdx = 0

        for ((src, dst) in sortedPairs) {
            val isLatin = src.none { PhoneticUtils.isCjkChar(it) }
            val regex = if (isLatin) {
                Regex("(?i)(?<![A-Za-z0-9])${Regex.escape(src)}(?![A-Za-z0-9])")
            } else {
                Regex(Regex.escape(src))
            }
            if (regex.containsMatchIn(working)) {
                val token = "[[TCFY_TERM_${tokenIdx++}]]"
                working = regex.replace(working, token)
                tokenMap[token] = dst
                matched.add(src to dst)
            }
        }

        return ProtectedPayload(working, tokenMap, matched)
    }

    fun restore(translatedText: String, tokenToTarget: Map<String, String>): String {
        var result = translatedText
        for ((token, target) in tokenToTarget) {
            result = result.replace(token, target)
        }
        return result
    }
}

/**
 * Zero-network offline glossary annotator & fallback translator.
 * Works immediately without internet or GMS, surfacing exact bilingual terminology pairs inline.
 */
class OfflineGlossaryTranslator {

    fun translateOffline(
        sourceText: String,
        direction: TranslationDirection,
        terms: List<GlossaryTerm>
    ): TranslationOutcome {
        if (direction == TranslationDirection.OFF || sourceText.isBlank()) {
            return TranslationOutcome("", usedOnline = false)
        }

        val targetIsChinese = resolveTargetIsChinese(sourceText, direction)
        val protected = GlossaryPlaceholderProtector.protect(sourceText, terms, targetIsChinese)
        if (protected.matchedPairs.isEmpty()) {
            return TranslationOutcome(
                translatedText = "",
                usedOnline = false,
                matchedGlossaryPairs = emptyList()
            )
        }

        val annotations = protected.matchedPairs.joinToString(" · ") { (src, dst) -> "$src → $dst" }
        return TranslationOutcome(
            translatedText = "[离线术语注译] $annotations",
            usedOnline = false,
            matchedGlossaryPairs = protected.matchedPairs
        )
    }

    companion object {
        fun resolveTargetIsChinese(text: String, direction: TranslationDirection): Boolean {
            return when (direction) {
                TranslationDirection.EN_TO_ZH -> true
                TranslationDirection.ZH_TO_EN -> false
                TranslationDirection.OFF -> true
                TranslationDirection.AUTO_BILINGUAL -> {
                    val cjkCount = text.count { PhoneticUtils.isCjkChar(it) }
                    val latinCount = text.count { it in 'a'..'z' || it in 'A'..'Z' }
                    // If mostly English (e.g. English lecture or mixed heavy English), translate to Chinese;
                    // if mostly Chinese, translate to English.
                    latinCount >= cjkCount * 2
                }
            }
        }
    }
}

/**
 * Resilient live translation router:
 * - If online live translation is enabled & API configured, attempts fast LLM translation with a strict timeout.
 * - On ANY network outage, timeout, or API error, immediately falls back to OfflineGlossaryTranslator
 *   so live classroom transcription NEVER stalls or loses data.
 */
class TranslationRouter(
    private val llmClient: OpenAiCompatibleClient,
    private val offlineTranslator: OfflineGlossaryTranslator = OfflineGlossaryTranslator()
) {

    suspend fun translateLive(
        sourceText: String,
        direction: TranslationDirection,
        terms: List<GlossaryTerm>,
        llmConfig: LlmApiConfig,
        onlineTimeoutSec: Long = 4L
    ): TranslationOutcome {
        if (direction == TranslationDirection.OFF || sourceText.isBlank()) {
            return TranslationOutcome("", usedOnline = false)
        }

        val targetIsChinese = OfflineGlossaryTranslator.resolveTargetIsChinese(sourceText, direction)
        val offlineResult = offlineTranslator.translateOffline(sourceText, direction, terms)

        if (!llmConfig.enableLiveOnlineTranslation || !llmConfig.isConfigured) {
            return offlineResult
        }

        val targetLangDesc = if (targetIsChinese) "简体中文" else "natural academic English"
        val glossaryHint = offlineResult.matchedGlossaryPairs
            .take(12)
            .joinToString("; ") { (src, dst) -> "$src=$dst" }

        val systemPrompt = buildString {
            append("You are a concise real-time classroom lecture translator. ")
            append("Translate the user's utterance into $targetLangDesc. ")
            append("Output ONLY the translation without explanations or quotes.")
            if (glossaryHint.isNotEmpty()) {
                append(" Strictly use these domain term mappings: $glossaryHint.")
            }
        }

        val online = llmClient.chatCompletion(
            config = llmConfig,
            messages = listOf(
                ChatMessage("system", systemPrompt),
                ChatMessage("user", sourceText)
            ),
            temperature = 0.1,
            maxTokens = 256,
            timeoutSeconds = onlineTimeoutSec
        )

        return online.fold(
            onSuccess = { text ->
                TranslationOutcome(
                    translatedText = text.trim(),
                    usedOnline = true,
                    matchedGlossaryPairs = offlineResult.matchedGlossaryPairs
                )
            },
            onFailure = {
                offlineResult
            }
        )
    }
}
