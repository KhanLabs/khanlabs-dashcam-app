package dev.khanlabs.dashcam.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per clip/track the user explicitly removed from the Gallery.
 * [dev.khanlabs.dashcam.data.repository.RealVideoRepository.observeClips]
 * filters these out, which is also what keeps SyncEngine from re-downloading
 * them on the next SYNC (it lists clips through the same repository) --
 * removal has to be persisted here rather than kept in memory, or both the
 * Gallery re-show and the re-download would happen again after a restart.
 */
@Entity(tableName = "removed_clips")
data class RemovedClipEntity(
    @PrimaryKey val filename: String,
    val removedAtEpochMillis: Long
)
