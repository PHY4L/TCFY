package com.phy4l.tcfy.asr

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class MirrorLatencySelectorTest {

    @Test
    fun probesMirrorsConcurrentlyAndRanksLowestLatencyFirstWithBrokenLast() = runTest {
        val fastServer = MockWebServer()
        val slowServer = MockWebServer()
        val brokenServer = MockWebServer()
        fastServer.start()
        slowServer.start()
        brokenServer.start()

        try {
            fastServer.enqueue(MockResponse().setResponseCode(200))
            slowServer.enqueue(MockResponse().setResponseCode(200).setHeadersDelay(250, TimeUnit.MILLISECONDS))
            brokenServer.enqueue(MockResponse().setResponseCode(503))

            val selector = MirrorLatencySelector()
            val candidates = listOf(
                MirrorCandidateUrl(
                    MirrorEndpoint("broken", "故障源", brokenServer.url("/").toString(), MirrorKind.HUGGINGFACE_BASE, true),
                    brokenServer.url("/model.onnx").toString()
                ),
                MirrorCandidateUrl(
                    MirrorEndpoint("slow", "慢速源", slowServer.url("/").toString(), MirrorKind.HUGGINGFACE_BASE, true),
                    slowServer.url("/model.onnx").toString()
                ),
                MirrorCandidateUrl(
                    MirrorEndpoint("fast", "极速源", fastServer.url("/").toString(), MirrorKind.HUGGINGFACE_BASE, true),
                    fastServer.url("/model.onnx").toString()
                )
            )

            val ranked = selector.probeAndRank(candidates)
            assertEquals(3, ranked.size)
            assertEquals("fast", ranked[0].candidate.mirror.id)
            assertTrue(ranked[0].reachable)
            assertEquals("slow", ranked[1].candidate.mirror.id)
            assertTrue(ranked[1].reachable)
            assertTrue("Fast mirror should have lower latency than slow mirror", ranked[0].latencyMs < ranked[1].latencyMs)
            assertEquals("broken", ranked[2].candidate.mirror.id)
            assertFalse(ranked[2].reachable)
        } finally {
            fastServer.shutdown()
            slowServer.shutdown()
            brokenServer.shutdown()
        }
    }

    @Test
    fun fallsBackTo1ByteRangeGetWhenHeadReturns405() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(405)) // HEAD rejected
            server.enqueue(MockResponse().setResponseCode(206).setBody("x")) // Range GET accepted

            val selector = MirrorLatencySelector()
            val candidate = MirrorCandidateUrl(
                MirrorEndpoint("range_only", "Range镜像", server.url("/").toString(), MirrorKind.GITHUB_PREFIX, true),
                server.url("/archive.tar.bz2").toString()
            )
            val ranked = selector.probeAndRank(listOf(candidate))
            assertTrue(ranked.first().reachable)
            assertEquals(206, ranked.first().httpCode)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun catalogContainsSpecializedSingleLanguageAsrModelsAndBundledSupportModels() {
        assertTrue(ModelCatalog.ASR_MODELS.any { it.type == ModelType.ASR_SENSEVOICE })
        assertTrue(ModelCatalog.ASR_MODELS.any { it.type == ModelType.ASR_PARAFORMER_ZH })
        assertTrue(ModelCatalog.ASR_MODELS.any { it.type == ModelType.ASR_WHISPER_EN })
        assertTrue(ModelCatalog.ASR_MODELS.any { it.type == ModelType.ASR_WENET_YUE })
        assertTrue(ModelCatalog.SILERO_VAD.bundledAssetPath != null)
        assertTrue(ModelCatalog.GTCRN_DENOISER.bundledAssetPath != null)

        val selector = MirrorLatencySelector()
        val archiveUrls = selector.buildArchiveCandidates(ModelCatalog.PARAFORMER_ZH_SMALL_INT8.archiveDirectUrl, useDomesticMirror = true)
        assertTrue("Expected multiple domestic + official archive mirrors, got ${archiveUrls.size}", archiveUrls.size >= 5)
    }
}
