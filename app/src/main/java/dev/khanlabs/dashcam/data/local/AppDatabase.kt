package dev.khanlabs.dashcam.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [SyncedFileEntity::class, RemovedClipEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun syncedFileDao(): SyncedFileDao
    abstract fun removedClipDao(): RemovedClipDao
}
