package dev.khanlabs.dashcam.data.model

enum class SyncScope(val label: String) {
    BOTH_CAMERAS("Both cameras"),
    ROAD_ONLY("Road camera only"),
    CABIN_ONLY("Cabin camera only")
}

// downloadOriginalQuality and autoDeleteAfterDownload settings, and the
// DownloadLocation chooser, were removed 2026-07-13 (Gallery/Dashboard UX
// pass): the device only ever serves one quality (nothing to switch), the
// dashcam has no delete-capable endpoint (auto-delete would also need the
// user's explicit go-ahead as a destructive action, not just an engineering
// task), and downloads only ever land in app-private storage today
// (MediaStore public-folder support was never built) -- a control that
// can't do what it claims gets removed, not left showing as an option.
//
// [AppSettings.saveToPublicDownloads] (added 2026-07-16) is that MediaStore
// support, finally built: additive, not a replacement -- see
// PublicDownloadStorage's doc comment for why the app-private copy stays
// the source of truth and this is a second, opt-in copy.

data class AppSettings(
    val autoSyncOnWifi: Boolean = false,
    val notifyOnNewClips: Boolean = true,
    val keepScreenOnDuringSync: Boolean = true,
    val syncScope: SyncScope = SyncScope.BOTH_CAMERAS,
    val cachedDownloadsBytes: Long = 0L,
    val pairedDashcamSsid: String? = null,
    val saveToPublicDownloads: Boolean = false
)
