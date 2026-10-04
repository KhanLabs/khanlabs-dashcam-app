package dev.khanlabs.dashcam.data.repository

import dev.khanlabs.dashcam.data.model.AppSettings
import dev.khanlabs.dashcam.data.model.SyncScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

class MockSettingsRepository @Inject constructor() : SettingsRepository {

    private val settings = MutableStateFlow(AppSettings(cachedDownloadsBytes = 340L * 1024 * 1024))

    override fun observeSettings(): StateFlow<AppSettings> = settings

    override suspend fun setAutoSyncOnWifi(enabled: Boolean) {
        settings.update { it.copy(autoSyncOnWifi = enabled) }
    }

    override suspend fun setNotifyOnNewClips(enabled: Boolean) {
        settings.update { it.copy(notifyOnNewClips = enabled) }
    }

    override suspend fun setKeepScreenOnDuringSync(enabled: Boolean) {
        settings.update { it.copy(keepScreenOnDuringSync = enabled) }
    }

    override suspend fun setSyncScope(scope: SyncScope) {
        settings.update { it.copy(syncScope = scope) }
    }

    override suspend fun clearDownloadCache() {
        settings.update { it.copy(cachedDownloadsBytes = 0L) }
    }

    override suspend fun setPairedDashcamSsid(ssid: String?) {
        settings.update { it.copy(pairedDashcamSsid = ssid) }
    }

    override suspend fun setSaveToPublicDownloads(enabled: Boolean) {
        settings.update { it.copy(saveToPublicDownloads = enabled) }
    }
}
