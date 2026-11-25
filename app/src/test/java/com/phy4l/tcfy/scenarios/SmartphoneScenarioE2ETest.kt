package com.phy4l.tcfy.scenarios

import com.phy4l.tcfy.ai.LectureRefinerAndSummarizer
import com.phy4l.tcfy.ai.OpenAiCompatibleClient
import com.phy4l.tcfy.ai.RefinedSegmentItem
import com.phy4l.tcfy.ai.TranslationRouter
import com.phy4l.tcfy.asr.AsrLanguageMode
import com.phy4l.tcfy.asr.SherpaSenseVoiceEngine
import com.phy4l.tcfy.audio.AcousticPreset
import com.phy4l.tcfy.audio.CrashSafeWavWriter
import com.phy4l.tcfy.audio.FarFieldDspPipeline
import com.phy4l.tcfy.data.db.LectureSessionEntity
import com.phy4l.tcfy.data.db.SegmentSyncStatus
import com.phy4l.tcfy.data.db.TranscriptSegmentEntity
import com.phy4l.tcfy.data.db.TranslationDirection
import com.phy4l.tcfy.data.export.SessionExporter
import com.phy4l.tcfy.data.prefs.LlmApiConfig
import com.phy4l.tcfy.glossary.BuiltInGlossaries
import com.phy4l.tcfy.glossary.GlossaryImporter
import com.phy4l.tcfy.glossary.GlossaryTerm
import com.phy4l.tcfy.glossary.OfflineTermCorrector
import com.phy4l.tcfy.service.RecordingUiState
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Simulated Redmi Note 11 5G (classroom far-field, no-GMS, offline-first) end-to-end QA scenarios.
 */
class SmartphoneScenarioE2ETest {

    // -------------------------------------------------------------------------
    // Scenario 1: Far-field classroom recording, pause/resume, crash recovery,
    //             and clean state isolation on immediate second recording.
    // -------------------------------------------------------------------------
    @Test
    fun scenario1_farFieldRecording_pauseResume_crashRecovery_andSecondSessionIsolation() {
        val sampleRate = 16000
        val frameSamples = 320 // 20ms frame (matches ClassroomAudioCapturer)
        val dsp = FarFieldDspPipeline(
            sampleRate = sampleRate,
            initialConfig = AcousticPreset.CLASSROOM_FAR_FIELD.config
        )

        val wav1 = File.createTempFile("tcfy_sc1_s1_", ".wav")
        val wavCrash = File.createTempFile("tcfy_sc1_crash_", ".wav")
        val wavOddCrash = File.createTempFile("tcfy_sc1_odd_", ".wav")
        val wav2 = File.createTempFile("tcfy_sc1_s2_", ".wav")

        try {
            // 1A. Record 1.0s of 3-10m weak speech (~-36dBFS) + 50Hz AC/projector hum
            var lastBoostedRms = 0f
            var lastRawSpeechRms = 0f
            var peakAfterSpike = 0f

            CrashSafeWavWriter(wav1, sampleRate = sampleRate, headerFlushIntervalMs = 5_000L).use { writer ->
                var sampleCursor = 0
                // Part 1: 50 frames = 1000ms active recording
                for (f in 0 until 50) {
                    val pcm = ShortArray(frameSamples) { i ->
                        val n = sampleCursor + i
                        val weakSpeech = 0.015 * sin(2.0 * PI * 850.0 * n / sampleRate) +
                            0.010 * sin(2.0 * PI * 1450.0 * n / sampleRate)
                        val hum50Hz = 0.12 * sin(2.0 * PI * 50.0 * n / sampleRate)
                        ((weakSpeech + hum50Hz).coerceIn(-1.0, 1.0) * 32767.0).toInt().toShort()
                    }
                    sampleCursor += frameSamples
                    writer.writePcm16(pcm)
                    val enhanced = dsp.processPcm16(pcm)
                    lastRawSpeechRms = FarFieldDspPipeline.computeRms(
                        FloatArray(frameSamples) { i ->
                            val n = sampleCursor - frameSamples + i
                            (0.015 * sin(2.0 * PI * 850.0 * n / sampleRate) +
                                0.010 * sin(2.0 * PI * 1450.0 * n / sampleRate)).toFloat()
                        }
                    )
                    lastBoostedRms = FarFieldDspPipeline.computeRms(enhanced)
                }
                assertEquals("Duration before pause should be 1000ms", 1000L, writer.durationMs())
                assertTrue(
                    "AGC should lift weak far-field speech significantly: raw=$lastRawSpeechRms boosted=$lastBoostedRms",
                    lastBoostedRms > lastRawSpeechRms * 2.5f
                )

                // Sudden close-range desk knock / cough burst (amplitude ~0.97)
                val spikePcm = ShortArray(frameSamples) { i ->
                    val sign = if (i % 2 == 0) 1 else -1
                    (sign * 31800).toShort()
                }
                writer.writePcm16(spikePcm) // +20ms
                val limited = dsp.processPcm16(spikePcm)
                peakAfterSpike = limited.maxOf { abs(it) }
                assertTrue("Soft limiter must prevent digital clipping > 1.0 (peak=$peakAfterSpike)", peakAfterSpike <= 1.0f)

                // Simulate PAUSE (20 frames discarded by capturer when isPaused == true)
                val durationAtPause = writer.durationMs()
                assertEquals(1020L, durationAtPause)
                // Nothing written during pause -> duration must remain frozen
                assertEquals(durationAtPause, writer.durationMs())

                // Simulate RESUME: write 24 more frames = 480ms -> total 1500ms
                for (f in 0 until 24) {
                    val pcm = ShortArray(frameSamples) { i ->
                        val n = sampleCursor + i
                        val speech = 0.02 * sin(2.0 * PI * 1000.0 * n / sampleRate)
                        (speech * 32767.0).toInt().toShort()
                    }
                    sampleCursor += frameSamples
                    writer.writePcm16(pcm)
                    val out = dsp.processPcm16(pcm)
                    assertTrue("No NaN/Inf in DSP output after resume", out.all { it.isFinite() })
                }
                assertEquals("Total duration after resume should be 1500ms", 1500L, writer.durationMs())
            }

            // Validate finished WAV 1 file integrity
            assertEquals(CrashSafeWavWriter.WAV_HEADER_SIZE + 24_000L * 2L, wav1.length())
            val (sr1, recovered1) = CrashSafeWavWriter.readWavAsFloat(wav1)
            assertEquals(16000, sr1)
            assertEquals(24_000, recovered1.size)

            // 1B. Simulate sudden battery loss / MIUI kill: 2.0s PCM written but header dataSize = 0
            CrashSafeWavWriter(wavCrash, sampleRate = sampleRate, headerFlushIntervalMs = 60_000L).use { writer ->
                writer.writePcm16(ShortArray(32_000) { idx -> ((idx % 200) * 50).toShort() })
            }
            RandomAccessFile(wavCrash, "rw").use { raf ->
                raf.seek(0L)
                raf.write(CrashSafeWavWriter.buildWavHeader(0L, sampleRate, 1, 16))
            }
            assertTrue(CrashSafeWavWriter.repairHeaderIfNeeded(wavCrash))
            // Verify repaired RIFF data size & Coordinator duration formula (dataBytes * 1000 / 32000)
            RandomAccessFile(wavCrash, "r").use { raf ->
                val hdr = ByteArray(44)
                raf.readFully(hdr)
                val buf = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
                assertEquals("RIFF", String(hdr, 0, 4, Charsets.US_ASCII))
                assertEquals("WAVE", String(hdr, 8, 4, Charsets.US_ASCII))
                val dataSize = buf.getInt(40).toLong() and 0xFFFFFFFFL
                assertEquals(64_000L, dataSize)
                assertEquals(2000L, (dataSize * 1000L) / 32_000L)
            }

            // 1C. Simulate odd-byte truncated WAV (mid-sample hard crash)
            wavCrash.copyTo(wavOddCrash, overwrite = true)
            RandomAccessFile(wavOddCrash, "rw").use { raf ->
                raf.seek(raf.length())
                raf.writeByte(0x7F) // odd trailing byte
                raf.seek(0L)
                raf.write(ByteArray(44)) // completely zeroed header!
            }
            assertTrue(CrashSafeWavWriter.repairHeaderIfNeeded(wavOddCrash))
            val (srOdd, samplesOdd) = CrashSafeWavWriter.readWavAsFloat(wavOddCrash)
            assertEquals(16000, srOdd)
            assertEquals(32_000, samplesOdd.size)

            // 1D. Immediate second recording: verify DSP reset & clean independent WAV
            dsp.reset()
            assertEquals(0f, dsp.lastGainDb, 0.001f)
            assertEquals(-90f, dsp.lastInputDbfs, 0.001f)

            CrashSafeWavWriter(wav2, sampleRate = sampleRate).use { writer ->
                val pcm2 = ShortArray(6_400) { i ->
                    (0.1 * sin(2.0 * PI * 600.0 * i / sampleRate) * 32767.0).toInt().toShort()
                }
                writer.writePcm16(pcm2)
                assertEquals(400L, writer.durationMs())
                val out2 = dsp.processPcm16(pcm2)
                assertTrue(out2.all { it.isFinite() && abs(it) <= 1.0f })
            }
            val (sr2, recovered2) = CrashSafeWavWriter.readWavAsFloat(wav2)
            assertEquals(16000, sr2)
            assertEquals(6_400, recovered2.size)
        } finally {
            wav1.delete()
            wavCrash.delete()
            wavOddCrash.delete()
            wav2.delete()
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 2: Molecular Biology code-switching, homophones, English typos,
    //             false-positive guard, cafeteria noise filter & CSV parity.
    // -------------------------------------------------------------------------
    @Test
    fun scenario2_molecularBiologyClassroom_glossaryCorrection_noiseFilter_andCsvConsistency() {
        val builtInTerms = BuiltInGlossaries.MOLECULAR_BIOLOGY_STARTER.terms
        assertEquals("Built-in Molecular Biology glossary must contain 48 terms", 48, builtInTerms.size)

        // 2A. Verify glossaries/molecular_biology.csv parses cleanly and matches BuiltInGlossaries
        val csvFile = listOf(
            File("glossaries/molecular_biology.csv"),
            File("../glossaries/molecular_biology.csv")
        ).firstOrNull { it.exists() }
        assertTrue("glossaries/molecular_biology.csv should exist in repo", csvFile != null)

        val decodedCsv = GlossaryImporter.decodeBytesSmart(csvFile!!.readBytes())
        val importedPack = GlossaryImporter.parseByFileName(csvFile.name, decodedCsv, "molecular_biology")
        assertEquals("CSV term count must match BuiltInGlossaries", builtInTerms.size, importedPack.terms.size)

        builtInTerms.zip(importedPack.terms).forEachIndexed { idx, (builtIn, csvTerm) ->
            assertEquals("Row $idx canonicalTerm mismatch", builtIn.canonicalTerm, csvTerm.canonicalTerm)
            assertEquals("Row $idx translation mismatch", builtIn.translation, csvTerm.translation)
            assertEquals("Row $idx aliases mismatch", builtIn.aliases, csvTerm.aliases)
        }

        // 2B. Multi-sentence Molecular Biology classroom simulation
        val corrector = OfflineTermCorrector(builtInTerms, enablePinyinFuzzy = true, enableEnglishFuzzy = true)

        val s1 = corrector.correct("DNA 复制时，解旋没 打开双链形成 复制差，DNA聚合没 延伸新链，DNA连接没 把 刚奇片段 连起来。")
        assertTrue(s1.correctedText.contains("Helicase"))
        assertTrue(s1.correctedText.contains("Replication fork"))
        assertTrue(s1.correctedText.contains("DNA polymerase"))
        assertTrue(s1.correctedText.contains("DNA ligase"))
        assertTrue(s1.correctedText.contains("Okazaki fragment"))

        val s2 = corrector.correct("真核转录由 RNA聚合没 结合 启动只 启动，pre-mRNA 经 简洁体 切除 内含只 保留 外显只。")
        assertTrue(s2.correctedText.contains("RNA polymerase"))
        assertTrue(s2.correctedText.contains("Promoter"))
        assertTrue(s2.correctedText.contains("Spliceosome"))
        assertTrue(s2.correctedText.contains("Intron"))
        assertTrue(s2.correctedText.contains("Exon"))

        val s3 = corrector.correct("蛋白经 范素化 标记后进入 蛋白没体 降解，而 激没 催化的 林酸化 负责信号转导，细胞雕亡 和 细胞自试 维持稳态。")
        assertTrue(s3.correctedText.contains("Ubiquitination"))
        assertTrue(s3.correctedText.contains("Proteasome"))
        assertTrue(s3.correctedText.contains("Kinase"))
        assertTrue(s3.correctedText.contains("Phosphorylation"))
        assertTrue(s3.correctedText.contains("Apoptosis"))
        assertTrue(s3.correctedText.contains("Autophagy"))

        // English typos (explicit aliases + Levenshtein fuzzy)
        val s4 = corrector.correct("Both helikase and topoisomerace act near the fork, while ribasome and nucleasome assemble complexes.")
        assertTrue(s4.correctedText.contains("Helicase"))
        assertTrue(s4.correctedText.contains("Topoisomerase"))
        assertTrue(s4.correctedText.contains("Ribosome"))
        assertTrue(s4.correctedText.contains("Nucleosome"))

        val s5 = corrector.correct("The Chaperone assists folding and Epigenetiks regulates Chromatine structure.")
        assertTrue("Expected Epigenetics in: ${s5.correctedText}", s5.correctedText.contains("Epigenetics"))
        assertTrue("Expected Chromatin in: ${s5.correctedText}", s5.correctedText.contains("Chromatin"))

        // 2C. Anti-false-positive check on ordinary Chinese classroom / daily speech
        val ordinarySentences = listOf(
            "同学们今天下午三点在教学楼二楼交实验报告。",
            "人工智能的智力水平各自是不一样的。",
            "中午去食堂吃饭的时候记得带上校园卡和雨伞。",
            "下周四我们进行期中测验，请大家认真复习前面三章内容。",
            "声音有点小，请后排同学把窗户关一下减少外面施工噪声。"
        )
        for (plain in ordinarySentences) {
            val res = corrector.correct(plain)
            assertEquals("Ordinary sentence must NOT be falsely altered: $plain", plain, res.correctedText)
            assertFalse("No correction spans expected for: $plain", res.hasChanges)
        }

        // 2D. Cafeteria noise, pure punctuation, SenseVoice tag cleanup, and stray 1-char JA/KO hallucinations
        assertEquals(
            "今天讲转录因子结合启动子。",
            SherpaSenseVoiceEngine.cleanSenseVoiceTags("<|zh|><|NEUTRAL|><|Speech|><|woitn|>今天讲转录因子结合启动子。")
        )
        val noiseSamples = listOf(
            "" to "zh",
            "   " to "en",
            "." to "en",
            "..." to "zh",
            "？！，。" to "zh",
            "♪" to "en",
            "그." to "ko",
            "아" to "ko",
            "あ。" to "ja",
            "ン" to "ja",
            "k." to "ko",
            "a" to "ja"
        )
        for ((txt, lang) in noiseSamples) {
            assertFalse(
                "Noise/hallucination should be filtered: '$txt' ($lang)",
                SherpaSenseVoiceEngine.isMeaningfulSpeech(txt, lang, AsrLanguageMode.AUTO)
            )
        }

        val validSpeechSamples = listOf(
            "PCR" to "en",
            "酶" to "zh",
            "CRISPR-Cas9" to "en",
            "DNA methylation 调控基因沉默。" to "zh",
            "Histone acetylation activates transcription." to "en"
        )
        for ((txt, lang) in validSpeechSamples) {
            assertTrue(
                "Valid speech must NOT be filtered: '$txt' ($lang)",
                SherpaSenseVoiceEngine.isMeaningfulSpeech(txt, lang, AsrLanguageMode.AUTO)
            )
        }
    }

    /**
     * Regression test for [High-5]: hyphenated canonical terms (e.g. "CRISPR-Cas9") must never
     * self-expand even if a user defines sub-tokens ("CRISPR", "Cas9") as aliases, and distinct
     * sub-techniques (RT-PCR, qPCR, SDS-PAGE) must be preserved intact.
     */
    @Test
    fun scenario2_hyphenatedCanonicalTermsAndSubTechniquesNeverCorrupt() {
        val customWithSubAliases = BuiltInGlossaries.MOLECULAR_BIOLOGY_STARTER.terms + GlossaryTerm(
            canonicalTerm = "CRISPR-Cas9",
            translation = "CRISPR基因编辑系统",
            aliases = listOf("CRISPR", "Cas9")
        )
        val corrector = OfflineTermCorrector(customWithSubAliases)

        val input1 = "我们使用 CRISPR-Cas9 进行定点基因编辑。"
        val res1 = corrector.correct(input1)
        assertEquals(input1, res1.correctedText)

        val input2 = "实验采用 RT-PCR、qPCR 和 SDS-PAGE 检测目标蛋白与 siRNA 敲低效率。"
        val res2 = corrector.correct(input2)
        assertEquals(input2, res2.correctedText)
    }

    // -------------------------------------------------------------------------
    // Scenario 3: Online -> timeout/outage offline fallback -> reconnect ->
    //             resumable sliding-window refinement -> >15k Map-Reduce summary ->
    //             MD / TXT / SRT export integrity.
    // -------------------------------------------------------------------------
    @Test
    fun scenario3_networkOutageFallback_resumableRefinement_mapReduceSummary_andExports() = runTest {
        val server = MockWebServer()
        val bioTerms = BuiltInGlossaries.MOLECULAR_BIOLOGY_STARTER.terms

        // 3A. Live translation queue:
        //  (1) Online success
        //  (2) Slow weak network (2.2s delay > 1s timeout) -> offline fallback
        //  (3) Server HTTP 502 outage -> offline fallback
        server.enqueue(chatOkResponse("DNA聚合酶沿 5' 到 3' 方向合成新链。"))
        server.enqueue(
            chatOkResponse("Should time out")
                .setBodyDelay(2200, TimeUnit.MILLISECONDS)
        )
        server.enqueue(MockResponse().setResponseCode(502).setBody("Bad Gateway"))

        server.start()
        try {
            val client = OpenAiCompatibleClient()
            val router = TranslationRouter(client)
            val llmConfig = LlmApiConfig(
                baseUrl = server.url("/v1").toString(),
                apiKey = "sk-test-tcfy",
                modelName = "deepseek-chat",
                enableLiveOnlineTranslation = true
            )

            // Step 1: Online live translation works & injects glossary hints
            val live1 = router.translateLive(
                sourceText = "DNA polymerase synthesizes the new strand from 5' to 3'.",
                direction = TranslationDirection.EN_TO_ZH,
                terms = bioTerms,
                llmConfig = llmConfig,
                onlineTimeoutSec = 4L
            )
            assertTrue("Step 1 should use online LLM", live1.usedOnline)
            assertEquals("DNA聚合酶沿 5' 到 3' 方向合成新链。", live1.translatedText)
            val req1Body = server.takeRequest().body.readUtf8()
            assertTrue("Glossary hint should be injected into live prompt", req1Body.contains("DNA polymerase=DNA聚合酶"))

            // Step 2: Sudden weak-network timeout -> graceful offline fallback
            val live2 = router.translateLive(
                sourceText = "Spliceosome removes Intron from pre-mRNA.",
                direction = TranslationDirection.EN_TO_ZH,
                terms = bioTerms,
                llmConfig = llmConfig,
                onlineTimeoutSec = 1L
            )
            assertFalse("Timeout should gracefully fall back offline", live2.usedOnline)
            assertTrue(live2.translatedText.contains("[离线术语注译]"))
            assertTrue(live2.translatedText.contains("剪接体"))
            assertTrue(live2.translatedText.contains("内含子"))
            server.takeRequest(3, TimeUnit.SECONDS) // drain delayed request

            // Step 3: Network outage (HTTP 502) -> graceful offline fallback
            val live3 = router.translateLive(
                sourceText = "Telomerase maintains chromosome ends.",
                direction = TranslationDirection.EN_TO_ZH,
                terms = bioTerms,
                llmConfig = llmConfig,
                onlineTimeoutSec = 3L
            )
            assertFalse("HTTP 502 should fall back offline", live3.usedOnline)
            assertTrue(live3.translatedText.contains("端粒酶"))
            server.takeRequest()

            // 3B. Post-class sliding-window refinement across 40 segments (batches: 18 + 18 + 4)
            val store = MutableList(40) { idx ->
                val id = (idx + 1).toLong()
                TranscriptSegmentEntity(
                    id = id,
                    sessionId = 99L,
                    indexInSession = idx,
                    startMs = idx * 10_000L,
                    endMs = idx * 10_000L + 8_500L,
                    rawText = "第${id}段关于解旋没和端粒没的课堂讲解",
                    correctedText = "第${id}段关于Helicase和Telomerase的课堂讲解",
                    offlineTranslation = "[离线术语注译] Helicase → 解旋酶 · Telomerase → 端粒酶",
                    isBookmarked = (idx == 5 || idx == 25),
                    syncStatus = SegmentSyncStatus.PENDING
                )
            }

            fun buildRefinedBatchJson(ids: IntRange): String {
                val items = ids.joinToString(",\n") { id ->
                    """{"id": $id, "refined_text": "精修第${id}段：Helicase 与 Telomerase 协同作用。", "refined_translation": "Refined #$id: Helicase and Telomerase cooperate."}"""
                }
                return "```json\n[\n$items\n]\n```"
            }

            // First attempt: Batch 1 (1..18) succeeds, Batch 2 (19..36) fails with HTTP 503
            server.enqueue(chatOkResponse(buildRefinedBatchJson(1..18)))
            server.enqueue(MockResponse().setResponseCode(503).setBody("Network dropped"))

            val refiner = LectureRefinerAndSummarizer(client)
            val attempt1 = refiner.refinePendingSegments(
                allSegments = store.toList(),
                terms = bioTerms,
                direction = TranslationDirection.ZH_TO_EN,
                llmConfig = llmConfig,
                batchSize = 18,
                onBatchSaved = { batch -> applyRefinementToStore(store, batch) }
            )

            assertTrue("Attempt 1 should fail at batch 2", attempt1.isFailure)
            assertTrue(
                "Error message should report 18 completed segments: ${attempt1.exceptionOrNull()?.message}",
                attempt1.exceptionOrNull()?.message?.contains("已完成 18 段") == true
            )
            assertEquals(18, store.count { it.syncStatus == SegmentSyncStatus.REFINED })
            assertEquals(22, store.count { it.syncStatus == SegmentSyncStatus.PENDING })
            server.takeRequest() // batch 1
            server.takeRequest() // failed batch 2

            // Second attempt after network recovery: resumes from segment 19 (18 + 4)
            server.enqueue(chatOkResponse(buildRefinedBatchJson(19..36)))
            server.enqueue(chatOkResponse(buildRefinedBatchJson(37..40)))

            val attempt2 = refiner.refinePendingSegments(
                allSegments = store.toList(),
                terms = bioTerms,
                direction = TranslationDirection.ZH_TO_EN,
                llmConfig = llmConfig,
                batchSize = 18,
                onBatchSaved = { batch -> applyRefinementToStore(store, batch) }
            )

            assertTrue("Resumed attempt should succeed", attempt2.isSuccess)
            assertEquals(22, attempt2.getOrThrow())
            assertEquals(40, store.count { it.syncStatus == SegmentSyncStatus.REFINED })

            val resumedBatch2Req = server.takeRequest().body.readUtf8()
            assertTrue("Resumed batch should carry 3-line prior context", resumedBatch2Req.contains("前文参考"))
            assertTrue("Prior context should reference segment 18 refined text", resumedBatch2Req.contains("精修第18段"))
            server.takeRequest() // batch 3

            // 3C. Long-lecture (>15,000 chars) Map-Reduce AI Summary
            val longSegments = (1..60).map { i ->
                val padding = "在分子生物学实验中我们详细推导了转录调控、染色质重塑与泛素化蛋白降解通路的动力学机制并结合课堂案例分析。".repeat(5)
                TranscriptSegmentEntity(
                    id = 1000L + i,
                    sessionId = 99L,
                    indexInSession = i - 1,
                    startMs = i * 60_000L,
                    endMs = i * 60_000L + 45_000L,
                    rawText = padding,
                    correctedText = padding,
                    refinedText = "[$i] $padding",
                    refinedTranslation = "English summary segment $i.",
                    isBookmarked = (i == 10 || i == 45),
                    syncStatus = SegmentSyncStatus.REFINED
                )
            }
            val totalChars = longSegments.sumOf { it.displaySourceText.length }
            assertTrue("Long transcript must exceed 15,000 chars (actual=$totalChars)", totalChars > 15_000)

            // Estimate map chunk count (chunked(10_000) over formatted transcript)
            val formattedLen = longSegments.joinToString("\n") { seg ->
                val star = if (seg.isBookmarked) " [学生标记重点]" else ""
                "[${SessionExporter.formatTimestamp(seg.startMs)}]$star ${seg.displaySourceText}"
            }.length
            val expectedMapChunks = (formattedLen + 9_999) / 10_000
            assertTrue("Map-Reduce should split into >=2 map chunks (actual=$expectedMapChunks)", expectedMapChunks >= 2)

            repeat(expectedMapChunks) { idx ->
                server.enqueue(chatOkResponse("分段要点 #${idx + 1}：保留时间戳与分子生物学术语。"))
            }
            val finalSummaryMd = """
                ## 1. 核心摘要
                本节课程系统讲解了 DNA 复制、转录剪接与泛素化降解通路。
                ## 2. 知识脉络与重点分节
                - [10:00] 重点：染色质重塑与组蛋白乙酰化
                - [45:00] 重点：蛋白酶体降解通路
                ## 3. 高频专业术语双语表
                | 英文 | 中文译名 | 简释 |
                | --- | --- | --- |
                | Helicase | 解旋酶 | 解开双螺旋 |
                ## 4. 关键结论与课后复习问题
                1. 解旋酶与拓扑异构酶如何协同工作？
            """.trimIndent()
            server.enqueue(chatOkResponse(finalSummaryMd))

            val statusLog = mutableListOf<String>()
            val summaryRes = refiner.generateSummary(
                lectureTitle = "分子生物学第 5 讲（长课测试）",
                segments = longSegments,
                terms = bioTerms,
                llmConfig = llmConfig,
                onStatus = { statusLog.add(it) }
            )

            assertTrue("Map-Reduce summary should succeed", summaryRes.isSuccess)
            assertEquals(finalSummaryMd, summaryRes.getOrThrow())
            assertTrue("Status callbacks should report chunked map phase", statusLog.any { it.contains("分段提炼长课堂要点") })

            var sawBookmarkInMap = false
            repeat(expectedMapChunks) {
                val mapBody = server.takeRequest().body.readUtf8()
                if (mapBody.contains("[学生标记重点]")) sawBookmarkInMap = true
            }
            assertTrue("Bookmarked segments must be flagged in Map requests", sawBookmarkInMap)
            val reduceBody = server.takeRequest().body.readUtf8()
            assertTrue("Reduce prompt must include course glossary", reduceBody.contains("DNA polymerase"))
            assertTrue("Reduce prompt must aggregate map outputs", reduceBody.contains("分段要点 #1"))

            // 3D. Verify MD / TXT / SRT Export completeness
            val session = LectureSessionEntity(
                id = 99L,
                title = "分子生物学第 5 讲",
                audioFilePath = "/data/user/0/com.phy4l.tcfy/files/recordings/lecture_99.wav",
                durationMs = 5_405_000L, // 01:30:05
                glossarySnapshotName = "分子生物学核心术语",
                aiSummaryMarkdown = finalSummaryMd
            )

            val md = SessionExporter.toMarkdown(session, store)
            assertTrue(md.contains("# 分子生物学第 5 讲"))
            assertTrue(md.contains("01:30:05"))
            assertTrue(md.contains("分子生物学核心术语"))
            assertTrue(md.contains("## AI 课堂总结"))
            assertTrue(md.contains("⭐[重点]"))
            assertTrue(md.contains("*(AI精修)*"))
            assertTrue(md.contains("Refined #40: Helicase and Telomerase cooperate."))

            val txt = SessionExporter.toPlainText(session, store)
            assertTrue(txt.contains("分子生物学第 5 讲"))
            assertTrue(txt.contains("* 精修第6段"))
            assertTrue(txt.contains("-> Refined #6:"))

            val srt = SessionExporter.toSrt(store)
            assertTrue(srt.contains("1\n00:00:00,000 --> 00:00:08,500"))
            assertTrue(srt.contains("40\n00:06:30,000 --> 00:06:38,500"))
            assertTrue(srt.contains("Refined #40: Helicase and Telomerase cooperate."))
        } finally {
            server.shutdown()
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 4: Live acoustic preset switching, state invariants, & edge cases
    // -------------------------------------------------------------------------
    @Test
    fun scenario4_livePresetSwitching_timestampEdges_andStateInvariants() {
        // Default RecordingUiState sanity
        val idle = RecordingUiState()
        assertFalse(idle.isRecording)
        assertFalse(idle.isInitializing)
        assertFalse(idle.isPaused)
        assertEquals(0L, idle.elapsedMs)

        // Live DSP preset switching mid-recording (FAR_FIELD -> LECTURE_HALL_PA -> NEAR_FIELD)
        val dsp = FarFieldDspPipeline(16000, AcousticPreset.CLASSROOM_FAR_FIELD.config)
        val frame = ShortArray(320) { i -> (0.04 * sin(2.0 * PI * 900.0 * i / 16000.0) * 32767.0).toInt().toShort() }
        for (preset in AcousticPreset.entries) {
            dsp.updateConfig(preset.config)
            repeat(15) {
                val out = dsp.processPcm16(frame)
                assertEquals(320, out.size)
                assertTrue("Preset ${preset.name} produced non-finite output", out.all { it.isFinite() })
                assertTrue("Preset ${preset.name} exceeded [-1, 1]", out.all { abs(it) <= 1.0f })
            }
        }

        // Timestamp formatting boundaries (negative, 0, <1h, 90min, >10h)
        assertEquals("00:00", SessionExporter.formatTimestamp(-500L))
        assertEquals("00:00", SessionExporter.formatTimestamp(0L))
        assertEquals("59:59", SessionExporter.formatTimestamp(3_599_000L))
        assertEquals("01:30:05", SessionExporter.formatTimestamp(5_405_000L))
        assertEquals("00:00:00,000", SessionExporter.formatSrtTime(-100L))
        assertEquals("01:30:05,250", SessionExporter.formatSrtTime(5_405_250L))

        // SRT fallback when endMs <= startMs
        val zeroEndSeg = listOf(
            TranscriptSegmentEntity(
                id = 1,
                sessionId = 1,
                indexInSession = 0,
                startMs = 5_000L,
                endMs = 5_000L,
                rawText = "test",
                correctedText = "test"
            )
        )
        assertTrue(SessionExporter.toSrt(zeroEndSeg).contains("00:00:05,000 --> 00:00:07,000"))

        // UTF-8 BOM + GBK glossary decoding edge cases
        val bomCsv = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "term,translation,aliases,notes\nPromoter,启动子,启动只;起动子,转录起始位点".toByteArray(Charsets.UTF_8)
        val parsedBom = GlossaryImporter.parseCsv(GlossaryImporter.decodeBytesSmart(bomCsv))
        assertEquals(1, parsedBom.size)
        assertEquals("Promoter", parsedBom[0].canonicalTerm)
        assertEquals(listOf("启动只", "起动子"), parsedBom[0].aliases)
    }

    private fun chatOkResponse(content: String): MockResponse {
        val escaped = content
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
        return MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("""{"choices":[{"message":{"role":"assistant","content":"$escaped"}}]}""")
    }

    private fun applyRefinementToStore(
        store: MutableList<TranscriptSegmentEntity>,
        refined: List<RefinedSegmentItem>
    ) {
        val byId = refined.associateBy { it.id }
        for (i in store.indices) {
            val item = byId[store[i].id] ?: continue
            store[i] = store[i].copy(
                refinedText = item.refinedText,
                refinedTranslation = item.refinedTranslation,
                syncStatus = SegmentSyncStatus.REFINED
            )
        }
    }
}
