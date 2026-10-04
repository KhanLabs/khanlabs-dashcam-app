package dev.khanlabs.dashcam.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.khanlabs.dashcam.data.local.SyncedFileDao
import dev.khanlabs.dashcam.data.model.AppSettings
import dev.khanlabs.dashcam.data.model.SyncScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")

private object Keys {
    val AUTO_SYNC_ON_WIFI = booleanPreferencesKey("auto_sync_on_wifi")
    val NOTIFY_ON_NEW_CLIPS = booleanPreferencesKey("notify_on_new_clips")
    val KEEP_SCREEN_ON_DURING_SYNC = booleanPreferencesKey("keep_screen_on_during_sync")
    val SYNC_SCOPE = stringPreferencesKey("sync_scope")
    val PAIRED_DASHCAM_SSID = stringPreferencesKey("paired_dashcam_ssid")
    val SAVE_TO_PUBLIC_DOWNLOADS = booleanPreferencesKey("save_to_public_downloads")
}

/**
 * Real (DataStore-backed) settings persistence -- see SettingsRepository's
 * doc comment for why this was always meant to be a small swap.
 *
 * [AppSettings.cachedDownloadsBytes] is deliberately NOT a persisted
 * preference: it's a derived fact about disk (how much [SyncStorage] has
 * actually downloaded), not a user choice, so it's computed fresh from the
 * real download directory on every read instead of drifting out of sync
 * with what SyncEngine has actually written. For the same reason,
 * [clearDownloadCache] really deletes those files rather than just
 * zeroing a number -- once the size is real, a fake clear would visibly
 * lie (the number would snap back on the next read). It also clears
 * [SyncedFileDao]'s rows for the same reason A3 keeps them in sync with
 * disk everywhere else: a cleared cache with stale "already synced" rows
 * left behind would make the Dashboard undercount new clips until the
 * next sync happened to overwrite them.
 */
@Singleton
class RealSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncedFileDao: SyncedFileDao,
    private val thumbnailRepository: ClipThumbnailRepository
) : SettingsRepository {

    override fun observeSettings(): Flow<AppSettings> =
        context.settingsDataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map { prefs ->
                AppSettings(
                    autoSyncOnWifi = prefs[Keys.AUTO_SYNC_ON_WIFI] ?: false,
                    notifyOnNewClips = prefs[Keys.NOTIFY_ON_NEW_CLIPS] ?: true,
                    keepScreenOnDuringSync = prefs[Keys.KEEP_SCREEN_ON_DURING_SYNC] ?: true,
                    syncScope = prefs[Keys.SYNC_SCOPE].toSyncScopeOrDefault(),
                    cachedDownloadsBytes = downloadDirSizeBytes(),
                    pairedDashcamSsid = prefs[Keys.PAIRED_DASHCAM_SSID],
                    saveToPublicDownloads = prefs[Keys.SAVE_TO_PUBLIC_DOWNLOADS] ?: false
                )
            }

    override suspend fun setAutoSyncOnWifi(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_SYNC_ON_WIFI] = enabled }
    }

    override suspend fun setNotifyOnNewClips(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.NOTIFY_ON_NEW_CLIPS] = enabled }
    }

    override suspend fun setKeepScreenOnDuringSync(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.KEEP_SCREEN_ON_DURING_SYNC] = enabled }
    }

    override suspend fun setSyncScope(scope: SyncScope) {
        context.settingsDataStore.edit { it[Keys.SYNC_SCOPE] = scope.name }
    }

    override suspend fun setPairedDashcamSsid(ssid: String?) {
        context.settingsDataStore.edit { prefs ->
            if (ssid == null) prefs.remove(Keys.PAIRED_DASHCAM_SSID) else prefs[Keys.PAIRED_DASHCAM_SSID] = ssid
        }
    }

    override suspend fun setSaveToPublicDownloads(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.SAVE_TO_PUBLIC_DOWNLOADS] = enabled }
    }

    override suspend fun clearDownloadCache() {
        withContext(Dispatchers.IO) {
            SyncStorage.downloadDir(context).listFiles()?.forEach { it.delete() }
        }
        thumbnailRepository.clearAll()
        syncedFileDao.deleteAll()
    }

    private suspend fun downloadDirSizeBytes(): Long = withContext(Dispatchers.IO) {
        SyncStorage.downloadDir(context).listFiles()?.sumOf { it.length() } ?: 0L
    }

    private fun String?.toSyncScopeOrDefault(): SyncScope =
        this?.let { runCatching { SyncScope.valueOf(it) }.getOrNull() } ?: SyncScope.BOTH_CAMERAS
}
