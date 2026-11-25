package com.phy4l.tcfy.ai

import com.phy4l.tcfy.data.db.LectureSessionEntity
import com.phy4l.tcfy.data.db.SegmentSyncStatus
import com.phy4l.tcfy.data.db.TranscriptSegmentEntity
import com.phy4l.tcfy.data.db.TranslationDirection
import com.phy4l.tcfy.data.export.SessionExporter
import com.phy4l.tcfy.data.prefs.LlmApiConfig
import com.phy4l.tcfy.glossary.GlossaryTerm
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationAndRefinerTest {

    private val terms = listOf(
        GlossaryTerm(canonicalTerm = "Hamiltonian", translation = "哈密顿量", aliases = listOf("哈密顿良")),
        GlossaryTerm(canonicalTerm = "Eigenvalue", translation = "特征值")
    )

    @Test
    fun glossaryPlaceholderProtectorPreservesDomainTerms() {
        val protected = GlossaryPlaceholderProtector.protect(
            sourceText = "Find the Eigenvalue of the Hamiltonian operator.",
            terms = terms,
            targetIsChinese = true
        )
        assertEquals(2, protected.matchedPairs.size)
        assertFalse(protected.protectedText.contains("Hamiltonian"))
        val restored = GlossaryPlaceholderProtector.restore(protected.protectedText, protected.tokenToTarget)
        assertTrue(restored.contains("哈密顿量"))
        assertTrue(restored.contains("特征值"))
    }

    @Test
    fun translationRouterFallsBackOfflineOnServerFailureWithoutDroppingData() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))
        server.start()
        try {
            val router = TranslationRouter(OpenAiCompatibleClient())
            val config = LlmApiConfig(
                baseUrl = server.url("/v1").toString(),
                apiKey = "test-key",
                modelName = "deepseek-chat",
                enableLiveOnlineTranslation = true
            )
            val outcome = router.translateLive(
                sourceText = "Consider the Hamiltonian eigenvalue problem.",
                direction = TranslationDirection.EN_TO_ZH,
                terms = terms,
                llmConfig = config,
                onlineTimeoutSec = 3L
            )
            assertFalse("Should fall back to offline on HTTP 500", outcome.usedOnline)
            assertTrue("Offline glossary note should contain 哈密顿量", outcome.translatedText.contains("哈密顿量"))
            assertTrue("Offline glossary note should contain 特征值", outcome.translatedText.contains("特征值"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun refinerProcessesBatchesResuminglyAndParsesFencedJson() = runTest {
        val server = MockWebServer()
        // Batch 1 succeeds with markdown fenced JSON; Batch 2 fails simulating Wi-Fi drop
        val batch1Json = """
            ```json
            [
              {"id": 1, "refined_text": "我们首先写出 Hamiltonian 算符。", "refined_translation": "First we write down the Hamiltonian operator."},
              {"id": 2, "refined_text": "它的 Eigenvalue 对应能量本征值。", "refined_translation": "Its eigenvalue corresponds to the energy eigenvalue."}
            ]
            ```
        """.trimIndent()
        val escapedBatch1 = batch1Json.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"choices":[{"message":{"role":"assistant","content":"$escapedBatch1"}}]}""")
        )
        server.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))
        server.start()

        try {
            val refiner = LectureRefinerAndSummarizer(OpenAiCompatibleClient())
            val segments = listOf(
                TranscriptSegmentEntity(id = 1, sessionId = 10, indexInSession = 0, startMs = 0, endMs = 3000, rawText = "我们首先写出哈密顿良算符", correctedText = "我们首先写出哈密顿量算符", syncStatus = SegmentSyncStatus.PENDING),
                TranscriptSegmentEntity(id = 2, sessionId = 10, indexInSession = 1, startMs = 3000, endMs = 6000, rawText = "它的艾根value对应能量", correctedText = "它的Eigenvalue对应能量", syncStatus = SegmentSyncStatus.PENDING),
                TranscriptSegmentEntity(id = 3, sessionId = 10, indexInSession = 2, startMs = 6000, endMs = 9000, rawText = "下一节讲微扰论", correctedText = "下一节讲微扰论", syncStatus = SegmentSyncStatus.PENDING)
            )
            val savedItems = mutableListOf<RefinedSegmentItem>()
            val config = LlmApiConfig(
                baseUrl = server.url("/v1").toString(),
                apiKey = "test-key",
                modelName = "deepseek-chat"
            )

            val result = refiner.refinePendingSegments(
                allSegments = segments,
                terms = terms,
                direction = TranslationDirection.ZH_TO_EN,
                llmConfig = config,
                batchSize = 2,
                onBatchSaved = { savedItems.addAll(it) }
            )

            // Batch 1 must be safely saved even though Batch 2 failed on network outage
            assertTrue(result.isFailure)
            assertEquals(2, savedItems.size)
            assertEquals("我们首先写出 Hamiltonian 算符。", savedItems[0].refinedText)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun sessionExporterProducesValidMarkdownAndSrt() {
        val session = LectureSessionEntity(
            id = 1,
            title = "量子力学第3讲",
            audioFilePath = "/tmp/a.wav",
            durationMs = 65_000L,
            aiSummaryMarkdown = "## 1. 核心摘要\n讨论了哈密顿量。"
        )
        val segs = listOf(
            TranscriptSegmentEntity(
                id = 1,
                sessionId = 1,
                indexInSession = 0,
                startMs = 5_000L,
                endMs = 9_500L,
                rawText = "Hamiltonian",
                correctedText = "Hamiltonian",
                refinedText = "Hamiltonian is Hermitian.",
                refinedTranslation = "哈密顿量是厄米算符。",
                isBookmarked = true,
                syncStatus = SegmentSyncStatus.REFINED
            )
        )

        val md = SessionExporter.toMarkdown(session, segs)
        assertTrue(md.contains("# 量子力学第3讲"))
        assertTrue(md.contains("⭐[重点]"))
        assertTrue(md.contains("哈密顿量是厄米算符。"))

        val srt = SessionExporter.toSrt(segs)
        assertTrue(srt.contains("00:00:05,000 --> 00:00:09,500"))
        assertTrue(srt.contains("Hamiltonian is Hermitian."))
    }
}
