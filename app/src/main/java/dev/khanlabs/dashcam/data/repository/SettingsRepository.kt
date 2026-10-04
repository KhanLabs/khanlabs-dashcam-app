package dev.khanlabs.dashcam.data.repository

import dev.khanlabs.dashcam.data.model.AppSettings
import dev.khanlabs.dashcam.data.model.SyncScope
import kotlinx.coroutines.flow.Flow

/**
 * Backed by DataStore (see RealSettingsRepository) -- settings survive app
 * restart. MockSettingsRepository remains for reference/fallback.
 */
interface SettingsRepository {
    fun observeSettings(): Flow<AppSettings>
    suspend fun setAutoSyncOnWifi(enabled: Boolean)
    suspend fun setNotifyOnNewClips(enabled: Boolean)
    suspend fun setKeepScreenOnDuringSync(enabled: Boolean)
    suspend fun setSyncScope(scope: SyncScope)
    suspend fun clearDownloadCache()
    suspend fun setPairedDashcamSsid(ssid: String?)
    suspend fun setSaveToPublicDownloads(enabled: Boolean)
}
