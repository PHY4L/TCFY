package com.phy4l.tcfy.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.phy4l.tcfy.glossary.BuiltInGlossaries

@Database(
    entities = [
        GlossaryPackEntity::class,
        GlossaryEntryEntity::class,
        LectureSessionEntity::class,
        TranscriptSegmentEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class TcfyDatabase : RoomDatabase() {
    abstract fun glossaryDao(): GlossaryDao
    abstract fun lectureDao(): LectureDao

    companion object {
        @Volatile
        private var INSTANCE: TcfyDatabase? = null

        fun getInstance(context: Context): TcfyDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TcfyDatabase::class.java,
                    "tcfy_lectures.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }

        suspend fun seedStarterGlossariesIfEmpty(db: TcfyDatabase) {
            val dao = db.glossaryDao()
            val existingNames = dao.getAllPacksOnce().map { it.name }.toSet()
            val isFreshInstall = existingNames.isEmpty()

            BuiltInGlossaries.ALL_STARTERS.forEach { starter ->
                if (starter.name !in existingNames) {
                    val shouldActivate = starter.name == BuiltInGlossaries.MOLECULAR_BIOLOGY_STARTER.name ||
                        (isFreshInstall && starter.name == BuiltInGlossaries.PHYSICS_STARTER.name)
                    val packId = dao.insertPack(
                        GlossaryPackEntity(
                            name = starter.name,
                            description = starter.description,
                            isActive = shouldActivate
                        )
                    )
                    val entries = starter.terms.map { GlossaryEntryEntity.fromDomain(packId, it) }
                    dao.insertEntries(entries)
                }
            }
        }
    }
}
