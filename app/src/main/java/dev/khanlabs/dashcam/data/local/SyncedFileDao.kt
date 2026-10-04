package dev.khanlabs.dashcam.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncedFileDao {
    @Query("SELECT filename FROM synced_files")
    suspend fun allFilenames(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SyncedFileEntity)

    @Query("DELETE FROM synced_files")
    suspend fun deleteAll()
}
