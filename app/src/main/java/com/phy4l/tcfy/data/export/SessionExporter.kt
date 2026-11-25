package com.phy4l.tcfy.data.export

import com.phy4l.tcfy.data.db.LectureSessionEntity
import com.phy4l.tcfy.data.db.TranscriptSegmentEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports lecture transcripts, bilingual translations, bookmarks, and AI summaries to
 * Markdown (.md), plain bilingual text (.txt), and SubRip subtitles (.srt).
 */
object SessionExporter {

    fun toMarkdown(
        session: LectureSessionEntity,
        segments: List<TranscriptSegmentEntity>
    ): String {
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date(session.startedAtMs))
        val sb = StringBuilder()
        sb.appendLine("# ${session.title}")
        sb.appendLine()
        sb.appendLine("- **录制时间**: $dateStr")
        sb.appendLine("- **时长**: ${formatTimestamp(session.durationMs)}")
        if (session.glossarySnapshotName.isNotBlank()) {
            sb.appendLine("- **启用术语库**: ${session.glossarySnapshotName}")
        }
        sb.appendLine()

        if (session.aiSummaryMarkdown.isNotBlank()) {
            sb.appendLine("## AI 课堂总结")
            sb.appendLine()
            sb.appendLine(session.aiSummaryMarkdown.trim())
            sb.appendLine()
        }

        sb.appendLine("## 课堂转写与双语记录")
        sb.appendLine()
        for (seg in segments) {
            val bookmarkTag = if (seg.isBookmarked) " ⭐[重点]" else ""
            val refinedTag = if (seg.isRefined) " *(AI精修)*" else ""
            sb.appendLine("### [${formatTimestamp(seg.startMs)}]$bookmarkTag$refinedTag")
            sb.appendLine(seg.displaySourceText)
            if (seg.displayTranslation.isNotBlank()) {
                sb.appendLine("> ${seg.displayTranslation}")
            }
            sb.appendLine()
        }
        return sb.toString()
    }

    fun toPlainText(
        session: LectureSessionEntity,
        segments: List<TranscriptSegmentEntity>
    ): String {
        val sb = StringBuilder()
        sb.appendLine(session.title)
        sb.appendLine("=".repeat(40))
        for (seg in segments) {
            val star = if (seg.isBookmarked) "* " else ""
            sb.appendLine("[${formatTimestamp(seg.startMs)}] $star${seg.displaySourceText}")
            if (seg.displayTranslation.isNotBlank()) {
                sb.appendLine("    -> ${seg.displayTranslation}")
            }
        }
        return sb.toString()
    }

    fun toSrt(segments: List<TranscriptSegmentEntity>): String {
        val sb = StringBuilder()
        segments.forEachIndexed { idx, seg ->
            val end = if (seg.endMs > seg.startMs) seg.endMs else seg.startMs + 2000L
            sb.appendLine(idx + 1)
            sb.appendLine("${formatSrtTime(seg.startMs)} --> ${formatSrtTime(end)}")
            sb.appendLine(seg.displaySourceText)
            if (seg.displayTranslation.isNotBlank()) {
                sb.appendLine(seg.displayTranslation)
            }
            sb.appendLine()
        }
        return sb.toString()
    }

    fun formatTimestamp(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    fun formatSrtTime(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val hours = clamped / 3_600_000
        val minutes = (clamped % 3_600_000) / 60_000
        val seconds = (clamped % 60_000) / 1000
        val millis = clamped % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }
}
