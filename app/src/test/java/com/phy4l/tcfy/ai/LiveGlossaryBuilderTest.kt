package com.phy4l.tcfy.ai

import com.phy4l.tcfy.data.db.TranscriptSegmentEntity
import com.phy4l.tcfy.data.prefs.LlmApiConfig
import com.phy4l.tcfy.glossary.GlossaryTerm
import com.phy4l.tcfy.glossary.OfflineTermCorrector
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveGlossaryBuilderTest {

    @Test
    fun buildsLiveGlossaryFromOpeningTranscriptAndDeduplicatesAgainstActivePack() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val llmJson = """
                ```json
                {
                  "topic": "神经科学·突触可塑性",
                  "discipline": "神经生物学",
                  "terms": [
                    {
                      "canonical_term": "Long-Term Potentiation",
                      "translation": "长时程增强",
                      "aliases": ["长时程曾强", "LTP", "长时城增强"],
                      "notes": "突触传递效率持久增强"
                    },
                    {
                      "canonical_term": "NMDA receptor",
                      "translation": "NMDA受体",
                      "aliases": ["恩姆达受体", "NMDA受梯"],
                      "notes": "谷氨酸门控离子通道"
                    },
                    {
                      "canonical_term": "CRISPR-Cas9",
                      "translation": "基因编辑系统",
                      "aliases": ["克里斯珀"],
                      "notes": "已有词条测试去重"
                    }
                  ]
                }
                ```
            """.trimIndent()

            val wrapped = """
                {"choices":[{"message":{"role":"assistant","content":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer<String>(), llmJson)}}}]}
            """.trimIndent()
            server.enqueue(MockResponse().setResponseCode(200).setBody(wrapped))

            val client = OpenAiCompatibleClient()
            val builder = LiveGlossaryBuilder(client)
            val cfg = LlmApiConfig(
                providerName = "Mock",
                baseUrl = server.url("/v1").toString(),
                apiKey = "sk-test",
                modelName = "mock-chat"
            )
            val opening = listOf(
                TranscriptSegmentEntity(
                    sessionId = 1L,
                    indexInSession = 0,
                    startMs = 0L,
                    endMs = 10_000L,
                    rawText = "今天我们讨论海马体中的突触可塑性机制，特别是长时程曾强和谷氨酸受体通道。",
                    correctedText = "今天我们讨论海马体中的突触可塑性机制，特别是长时程曾强和谷氨酸受体通道。"
                )
            )
            val existing = listOf(GlossaryTerm(canonicalTerm = "CRISPR-Cas9", translation = "基因编辑"))

            val res = builder.buildFromTranscript(opening, existing, cfg)
            assertTrue("Expected success, got $res", res.isSuccess)
            val built = res.getOrThrow()
            assertEquals("AI现场 · 神经科学·突触可塑性", built.packName)
            assertEquals("CRISPR-Cas9 should be deduplicated", 2, built.terms.size)

            // Verify generated terms immediately correct subsequent far-field ASR text!
            val corrector = OfflineTermCorrector(built.terms, enablePinyinFuzzy = true)
            val fixed = corrector.correct("钙离子内流通过恩姆达受体触发长时程曾强。")
            assertTrue(fixed.correctedText.contains("NMDA receptor"))
            assertTrue(fixed.correctedText.contains("Long-Term Potentiation"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun autoTriggerPolicyWaitsForSufficientDurationAndText() {
        assertFalse(LiveGlossaryBuilder.shouldAutoTrigger(elapsedMs = 30_000L, totalCleanChars = 200, alreadyBuiltForSession = false))
        assertFalse(LiveGlossaryBuilder.shouldAutoTrigger(elapsedMs = 120_000L, totalCleanChars = 50, alreadyBuiltForSession = false))
        assertTrue(LiveGlossaryBuilder.shouldAutoTrigger(elapsedMs = 95_000L, totalCleanChars = 180, alreadyBuiltForSession = false))
        assertTrue(LiveGlossaryBuilder.shouldAutoTrigger(elapsedMs = 185_000L, totalCleanChars = 70, alreadyBuiltForSession = false))
        assertFalse(LiveGlossaryBuilder.shouldAutoTrigger(elapsedMs = 200_000L, totalCleanChars = 300, alreadyBuiltForSession = true))
    }

    @Test
    fun failsGracefullyWithoutApiKeyOrEnoughText() = runTest {
        val builder = LiveGlossaryBuilder(OpenAiCompatibleClient())
        val noKey = builder.buildFromTranscript(emptyList(), emptyList(), LlmApiConfig(apiKey = ""))
        assertTrue(noKey.isFailure)
        assertTrue(noKey.exceptionOrNull()?.message?.contains("API Key") == true)
    }
}
