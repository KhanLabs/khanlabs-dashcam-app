package dev.khanlabs.dashcam.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per file SyncEngine has downloaded -- filenames are unique per
 * clip/track (see SyncEngine's doc comment), so filename is a safe primary
 * key. Backs A3 (BACKLOG_2026-07-13.md): replaces plain file-existence
 * dedup and lets the Dashboard compute real new-clip/new-track counts by
 * diffing the dashcam's live listing against these rows.
 */
@Entity(tableName = "synced_files")
data class SyncedFileEntity(
    @PrimaryKey val filename: String,
    val sizeBytes: Long,
    val syncedAtEpochMillis: Long
)
