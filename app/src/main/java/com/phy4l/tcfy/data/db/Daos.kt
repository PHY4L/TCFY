package com.phy4l.tcfy.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface GlossaryDao {

    @Query("SELECT * FROM glossary_packs ORDER BY isActive DESC, createdAtMs DESC")
    fun observeAllPacks(): Flow<List<GlossaryPackEntity>>

    @Query("SELECT * FROM glossary_packs WHERE isActive = 1")
    suspend fun getActivePacks(): List<GlossaryPackEntity>

    @Query("SELECT COUNT(*) FROM glossary_packs")
    suspend fun countPacks(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPack(pack: GlossaryPackEntity): Long

    @Update
    suspend fun updatePack(pack: GlossaryPackEntity)

    @Delete
    suspend fun deletePack(pack: GlossaryPackEntity)

    @Query("UPDATE glossary_packs SET isActive = :active WHERE id = :packId")
    suspend fun setPackActive(packId: Long, active: Boolean)

    @Query("SELECT * FROM glossary_entries WHERE packId = :packId ORDER BY canonicalTerm COLLATE NOCASE ASC")
    fun observeEntriesByPack(packId: Long): Flow<List<GlossaryEntryEntity>>

    @Query("SELECT * FROM glossary_entries WHERE packId = :packId ORDER BY canonicalTerm COLLATE NOCASE ASC")
    suspend fun getEntriesByPack(packId: Long): List<GlossaryEntryEntity>

    @Query(
        """
        SELECT e.* FROM glossary_entries e
        INNER JOIN glossary_packs p ON e.packId = p.id
        WHERE p.isActive = 1
        ORDER BY LENGTH(e.canonicalTerm) DESC
        """
    )
    fun observeActiveEntries(): Flow<List<GlossaryEntryEntity>>

    @Query(
        """
        SELECT e.* FROM glossary_entries e
        INNER JOIN glossary_packs p ON e.packId = p.id
        WHERE p.isActive = 1
        ORDER BY LENGTH(e.canonicalTerm) DESC
        """
    )
    suspend fun getActiveEntries(): List<GlossaryEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: GlossaryEntryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<GlossaryEntryEntity>)

    @Update
    suspend fun updateEntry(entry: GlossaryEntryEntity)

    @Delete
    suspend fun deleteEntry(entry: GlossaryEntryEntity)
}

@Dao
interface LectureDao {

    @Query("SELECT * FROM lecture_sessions ORDER BY startedAtMs DESC")
    fun observeAllSessions(): Flow<List<LectureSessionEntity>>

    @Query(
        """
        SELECT DISTINCT s.* FROM lecture_sessions s
        LEFT JOIN transcript_segments seg ON s.id = seg.sessionId
        WHERE s.title LIKE '%' || :query || '%'
           OR s.aiSummaryMarkdown LIKE '%' || :query || '%'
           OR seg.correctedText LIKE '%' || :query || '%'
           OR seg.refinedText LIKE '%' || :query || '%'
           OR seg.offlineTranslation LIKE '%' || :query || '%'
           OR seg.refinedTranslation LIKE '%' || :query || '%'
        ORDER BY s.startedAtMs DESC
        """
    )
    fun searchSessions(query: String): Flow<List<LectureSessionEntity>>

    @Query("SELECT * FROM lecture_sessions WHERE id = :sessionId")
    fun observeSession(sessionId: Long): Flow<LectureSessionEntity?>

    @Query("SELECT * FROM lecture_sessions WHERE id = :sessionId")
    suspend fun getSession(sessionId: Long): LectureSessionEntity?

    @Insert
    suspend fun insertSession(session: LectureSessionEntity): Long

    @Update
    suspend fun updateSession(session: LectureSessionEntity)

    @Delete
    suspend fun deleteSession(session: LectureSessionEntity)

    @Query("UPDATE lecture_sessions SET isRecording = 0, endedAtMs = :endedAtMs, durationMs = :durationMs WHERE id = :sessionId")
    suspend fun finalizeSession(sessionId: Long, endedAtMs: Long, durationMs: Long)

    @Query("UPDATE lecture_sessions SET aiSummaryMarkdown = :summary WHERE id = :sessionId")
    suspend fun updateSummary(sessionId: Long, summary: String)

    @Query("UPDATE lecture_sessions SET title = :title WHERE id = :sessionId")
    suspend fun updateTitle(sessionId: Long, title: String)

    @Query("SELECT * FROM transcript_segments WHERE sessionId = :sessionId ORDER BY indexInSession ASC, startMs ASC")
    fun observeSegments(sessionId: Long): Flow<List<TranscriptSegmentEntity>>

    @Query("SELECT * FROM transcript_segments WHERE sessionId = :sessionId ORDER BY indexInSession ASC, startMs ASC")
    suspend fun getSegments(sessionId: Long): List<TranscriptSegmentEntity>

    @Insert
    suspend fun insertSegment(segment: TranscriptSegmentEntity): Long

    @Update
    suspend fun updateSegment(segment: TranscriptSegmentEntity)

    @Query("UPDATE transcript_segments SET isBookmarked = :bookmarked WHERE id = :segmentId")
    suspend fun setBookmarked(segmentId: Long, bookmarked: Boolean)

    @Query("UPDATE transcript_segments SET correctedText = :newText, refinedText = '' WHERE id = :segmentId")
    suspend fun updateSegmentManualText(segmentId: Long, newText: String)

    @Query(
        """
        UPDATE transcript_segments
        SET refinedText = :refinedText,
            refinedTranslation = :refinedTranslation,
            syncStatus = :syncStatus
        WHERE id = :segmentId
        """
    )
    suspend fun updateSegmentRefinement(
        segmentId: Long,
        refinedText: String,
        refinedTranslation: String,
        syncStatus: String
    )

    @Query("DELETE FROM transcript_segments WHERE sessionId = :sessionId")
    suspend fun deleteSegmentsForSession(sessionId: Long)
}
