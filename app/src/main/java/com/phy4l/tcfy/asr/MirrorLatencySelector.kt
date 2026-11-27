package com.phy4l.tcfy.asr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

enum class MirrorKind {
    HUGGINGFACE_BASE,
    GITHUB_PREFIX
}

data class MirrorEndpoint(
    val id: String,
    val label: String,
    val baseUrl: String,
    val kind: MirrorKind,
    val isDomestic: Boolean
)

data class MirrorCandidateUrl(
    val mirror: MirrorEndpoint,
    val url: String,
    val isArchive: Boolean = false
)

data class MirrorProbeResult(
    val candidate: MirrorCandidateUrl,
    val reachable: Boolean,
    val latencyMs: Long,
    val httpCode: Int = 0,
    val error: String? = null
)

/**
 * Pure JVM multi-mirror latency prober and priority ranker.
 *
 * Before downloading a large offline ASR model, concurrently probes all configured domestic
 * and global mirrors via lightweight HTTP HEAD / 1-byte Range GET requests, then orders
 * candidates strictly from lowest latency (ms) to highest latency for priority + automatic fallback.
 */
class MirrorLatencySelector(
    private val probeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3500, TimeUnit.MILLISECONDS)
        .readTimeout(3500, TimeUnit.MILLISECONDS)
        .callTimeout(4500, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()
) {
    /**
     * Builds all candidate mirror URLs for a single [RemoteFileEntry] plus optional archive fallback.
     */
    fun buildCandidatesForFile(
        entry: RemoteFileEntry,
        useDomesticMirror: Boolean = true,
        customMirrorBase: String = "",
        mirrors: List<MirrorEndpoint> = DEFAULT_MIRRORS
    ): List<MirrorCandidateUrl> {
        val out = mutableListOf<MirrorCandidateUrl>()
        val trimmedCustom = customMirrorBase.trim().trimEnd('/')

        if (entry.directUrl.startsWith(HF_OFFICIAL_BASE)) {
            val suffix = entry.directUrl.removePrefix(HF_OFFICIAL_BASE)
            if (trimmedCustom.isNotEmpty()) {
                out.add(
                    MirrorCandidateUrl(
                        mirror = MirrorEndpoint("custom", "自定义镜像", trimmedCustom, MirrorKind.HUGGINGFACE_BASE, true),
                        url = "$trimmedCustom$suffix"
                    )
                )
            }
            mirrors.filter { it.kind == MirrorKind.HUGGINGFACE_BASE }
                .filter { useDomesticMirror || !it.isDomestic }
                .forEach { m ->
                    out.add(MirrorCandidateUrl(m, "${m.baseUrl.trimEnd('/')}$suffix"))
                }
        } else if (entry.directUrl.startsWith(GH_OFFICIAL_BASE)) {
            if (entry.mirrorUrl.startsWith(HF_MIRROR_BASE) && useDomesticMirror) {
                val hfMirror = mirrors.firstOrNull { it.id == "hf_mirror" }
                    ?: MirrorEndpoint("hf_mirror", "HF-Mirror 国内", HF_MIRROR_BASE, MirrorKind.HUGGINGFACE_BASE, true)
                out.add(MirrorCandidateUrl(hfMirror, entry.mirrorUrl))
            }
            mirrors.filter { it.kind == MirrorKind.GITHUB_PREFIX }
                .filter { useDomesticMirror || !it.isDomestic }
                .forEach { m ->
                    val resolved = if (m.baseUrl.isEmpty()) entry.directUrl else "${m.baseUrl.trimEnd('/')}/${entry.directUrl}"
                    out.add(MirrorCandidateUrl(m, resolved))
                }
        } else {
            out.add(
                MirrorCandidateUrl(
                    mirror = MirrorEndpoint("direct", "官方直连", "", MirrorKind.HUGGINGFACE_BASE, false),
                    url = if (useDomesticMirror) entry.mirrorUrl else entry.directUrl
                )
            )
        }

        // Always keep official direct URL as final fallback if not already included
        if (out.none { it.url == entry.directUrl }) {
            out.add(
                MirrorCandidateUrl(
                    mirror = MirrorEndpoint("official_fallback", "官方直连", "", MirrorKind.HUGGINGFACE_BASE, false),
                    url = entry.directUrl
                )
            )
        }
        return out.distinctBy { it.url }
    }

    /**
     * Builds all GitHub-Release archive mirror URLs for a model's `.tar.bz2` fallback.
     */
    fun buildArchiveCandidates(
        archiveDirectUrl: String?,
        useDomesticMirror: Boolean = true,
        mirrors: List<MirrorEndpoint> = DEFAULT_MIRRORS
    ): List<MirrorCandidateUrl> {
        if (archiveDirectUrl.isNullOrBlank()) return emptyList()
        val out = mutableListOf<MirrorCandidateUrl>()
        mirrors.filter { it.kind == MirrorKind.GITHUB_PREFIX }
            .filter { useDomesticMirror || !it.isDomestic }
            .forEach { m ->
                val resolved = if (m.baseUrl.isEmpty()) archiveDirectUrl else "${m.baseUrl.trimEnd('/')}/$archiveDirectUrl"
                out.add(MirrorCandidateUrl(m, resolved, isArchive = true))
            }
        if (out.none { it.url == archiveDirectUrl }) {
            out.add(
                MirrorCandidateUrl(
                    mirror = MirrorEndpoint("github_direct", "GitHub 官方", "", MirrorKind.GITHUB_PREFIX, false),
                    url = archiveDirectUrl,
                    isArchive = true
                )
            )
        }
        return out.distinctBy { it.url }
    }

    /**
     * Concurrently pings/probes all [candidates] and returns results sorted with reachable
     * mirrors first in ascending latency order (fastest -> slowest), followed by unreachable ones.
     */
    suspend fun probeAndRank(candidates: List<MirrorCandidateUrl>): List<MirrorProbeResult> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                candidates.map { candidate ->
                    async { probeSingle(candidate) }
                }.awaitAll()
            }.sortedWith(
                compareByDescending<MirrorProbeResult> { it.reachable }
                    .thenBy { it.latencyMs }
            )
        }

    private fun probeSingle(candidate: MirrorCandidateUrl): MirrorProbeResult {
        val t0 = System.nanoTime()
        return try {
            // Prefer lightweight HEAD; if server rejects HEAD (405/403), retry 1-byte Range GET
            val headReq = Request.Builder()
                .url(candidate.url)
                .head()
                .header("User-Agent", "TCFY-Android/1.1")
                .build()
            probeClient.newCall(headReq).execute().use { resp ->
                val elapsedMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                if (resp.code in 200..399) {
                    return MirrorProbeResult(candidate, reachable = true, latencyMs = elapsedMs, httpCode = resp.code)
                }
                if (resp.code != 405 && resp.code != 403 && resp.code != 400) {
                    return MirrorProbeResult(
                        candidate,
                        reachable = false,
                        latencyMs = Long.MAX_VALUE,
                        httpCode = resp.code,
                        error = "HTTP ${resp.code}"
                    )
                }
            }

            val t1 = System.nanoTime()
            val rangeReq = Request.Builder()
                .url(candidate.url)
                .get()
                .header("Range", "bytes=0-0")
                .header("User-Agent", "TCFY-Android/1.1")
                .build()
            probeClient.newCall(rangeReq).execute().use { resp ->
                val elapsedMs = ((System.nanoTime() - t1) / 1_000_000L).coerceAtLeast(1L)
                val ok = resp.code == 200 || resp.code == 206
                MirrorProbeResult(
                    candidate = candidate,
                    reachable = ok,
                    latencyMs = if (ok) elapsedMs else Long.MAX_VALUE,
                    httpCode = resp.code,
                    error = if (ok) null else "HTTP ${resp.code}"
                )
            }
        } catch (t: Throwable) {
            MirrorProbeResult(
                candidate = candidate,
                reachable = false,
                latencyMs = Long.MAX_VALUE,
                error = t.message?.take(60) ?: "连接超时"
            )
        }
    }

    companion object {
        const val HF_OFFICIAL_BASE = "https://huggingface.co"
        const val HF_MIRROR_BASE = "https://hf-mirror.com"
        const val GH_OFFICIAL_BASE = "https://github.com"

        val DEFAULT_MIRRORS: List<MirrorEndpoint> = listOf(
            MirrorEndpoint("hf_mirror", "HF-Mirror 国内主站", "https://hf-mirror.com", MirrorKind.HUGGINGFACE_BASE, isDomestic = true),
            MirrorEndpoint("hf_official", "HuggingFace 官方", "https://huggingface.co", MirrorKind.HUGGINGFACE_BASE, isDomestic = false),
            MirrorEndpoint("gh_proxy_org", "GH-Proxy.org 加速", "https://gh-proxy.org", MirrorKind.GITHUB_PREFIX, isDomestic = true),
            MirrorEndpoint("gh_proxy_com", "GH-Proxy.com 加速", "https://gh-proxy.com", MirrorKind.GITHUB_PREFIX, isDomestic = true),
            MirrorEndpoint("gh_proxy_cdn", "GH-Proxy CDN 加速", "https://cdn.gh-proxy.com", MirrorKind.GITHUB_PREFIX, isDomestic = true),
            MirrorEndpoint("gh_proxy_edgeone", "EdgeOne 国内加速", "https://edgeone.gh-proxy.com", MirrorKind.GITHUB_PREFIX, isDomestic = true),
            MirrorEndpoint("gh_proxy_hk", "GH-Proxy 香港节点", "https://hk.gh-proxy.com", MirrorKind.GITHUB_PREFIX, isDomestic = true),
            MirrorEndpoint("gh_official", "GitHub 官方直连", "", MirrorKind.GITHUB_PREFIX, isDomestic = false)
        )
    }
}
