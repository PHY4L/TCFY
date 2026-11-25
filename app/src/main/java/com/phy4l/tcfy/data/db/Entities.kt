package com.phy4l.tcfy.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.phy4l.tcfy.glossary.GlossaryTerm
import kotlinx.serialization.Serializable

@Entity(tableName = "glossary_packs")
data class GlossaryPackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val description: String = "",
    val isActive: Boolean = true,
    val createdAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "glossary_entries",
    foreignKeys = [
        ForeignKey(
            entity = GlossaryPackEntity::class,
            parentColumns = ["id"],
            childColumns = ["packId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("packId")]
)
data class GlossaryEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val packId: Long,
    val canonicalTerm: String,
    val translation: String = "",
    val aliasesJoined: String = "", // stored as "||" delimited
    val notes: String = ""
) {
    fun toDomain(): GlossaryTerm = GlossaryTerm(
        id = id,
        canonicalTerm = canonicalTerm,
        translation = translation,
        aliases = aliasesJoined.split("||").map { it.trim() }.filter { it.isNotEmpty() },
        notes = notes
    )

    companion object {
        fun fromDomain(packId: Long, term: GlossaryTerm): GlossaryEntryEntity = GlossaryEntryEntity(
            id = term.id,
            packId = packId,
            canonicalTerm = term.canonicalTerm.trim(),
            translation = term.translation.trim(),
            aliasesJoined = term.aliases.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("||"),
            notes = term.notes.trim()
        )
    }
}

@Serializable
enum class TranslationDirection(val displayName: String, val targetLangLabel: String) {
    AUTO_BILINGUAL("自动互译 (中↔英)", "自动"),
    EN_TO_ZH("英 → 中", "简体中文"),
    ZH_TO_EN("中 → 英", "English"),
    OFF("仅转写不翻译", "关闭")
}

object SegmentSyncStatus {
    const val LOCAL_ONLY = "LOCAL_ONLY"
    const val PENDING = "PENDING"
    const val REFINED = "REFINED"
    const val FAILED = "FAILED"
}

@Entity(tableName = "lecture_sessions")
data class LectureSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val audioFilePath: String,
    val startedAtMs: Long = System.currentTimeMillis(),
    val endedAtMs: Long = 0L,
    val durationMs: Long = 0L,
    val asrLanguageCode: String = "auto",
    val translationDirection: String = TranslationDirection.AUTO_BILINGUAL.name,
    val acousticPresetName: String = "CLASSROOM_FAR_FIELD",
    val isRecording: Boolean = false,
    val aiSummaryMarkdown: String = "",
    val glossarySnapshotName: String = ""
)

@Entity(
    tableName = "transcript_segments",
    foreignKeys = [
        ForeignKey(
            entity = LectureSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class TranscriptSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: Long,
    val indexInSession: Int,
    val startMs: Long,
    val endMs: Long,
    val rawText: String,
    val correctedText: String,
    val offlineTranslation: String = "",
    val refinedText: String = "",
    val refinedTranslation: String = "",
    val correctionSpansJson: String = "[]",
    val detectedLang: String = "",
    val isBookmarked: Boolean = false,
    val syncStatus: String = SegmentSyncStatus.PENDING
) {
    val displaySourceText: String
        get() = refinedText.ifBlank { correctedText }

    val displayTranslation: String
        get() = refinedTranslation.ifBlank { offlineTranslation }

    val isRefined: Boolean
        get() = syncStatus == SegmentSyncStatus.REFINED && refinedText.isNotBlank()
}
