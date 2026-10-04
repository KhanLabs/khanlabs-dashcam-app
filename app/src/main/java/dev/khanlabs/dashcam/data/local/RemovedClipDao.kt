package dev.khanlabs.dashcam.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RemovedClipDao {
    @Query("SELECT filename FROM removed_clips")
    fun observeFilenames(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RemovedClipEntity)
}
