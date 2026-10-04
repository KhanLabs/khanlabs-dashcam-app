# KhanLabs Dashcam — Companion App: Implementation Notes

Running log of every implementation decision and step, per project convention
(see sibling `khanlabs-dashcam*` folders' `STATUS.md`/`NOTES.md`). Newest
entries at the bottom of each section.

## 0. Context this app was built against

- Full product spec: OLED-dark, monospace-telemetry, store-and-forward
  companion app for the rooted Surfsight AI-12 dashcams.
  Zero cloud, pull-based sync over the dashcam's own `Dash-*`
  WiFi hotspot only.
- Confirmed before starting: neither dashcam unit has a manifest/listing
  endpoint yet (`cgi-bin/manifest.json` doesn't exist; unit 2's
  `60_fileserver.sh` is `.STALE`). This app is therefore being built against
  **mock data** behind a repository interface, so the real HTTP sync can be
  swapped in later as an isolated change once that device-side endpoint
  exists and is field-verified.
- Project's own `STATUS.md` phase log lists Stage E ("App spec handoff") as
  a stop point. Flagged to the owner explicitly; proceeding past it was an
  explicit, informed decision, not an oversight.

## 1. Decisions locked in during brainstorming (2026-07-06)

| Decision | Choice | Why |
|---|---|---|
| Tech stack | **Native Android: Kotlin + Jetpack Compose** (Material3, Retrofit/OkHttp, Media3 ExoPlayer, OSMDroid, WorkManager, Room, Hilt, MVVM) | Explicit directive, overriding the initially-recommended Expo/React Native path. |
| View target | **Android emulator on this PC** | No physical test phone in this loop; Windows box confirmed to have Hyper-V virtualization enabled, so the emulator runs accelerated. |
| Map tiles vs. "Zero Cloud" | Basemap tiles may load over the internet when available (one narrow exception); everything else (sync, telemetry, files) stays strictly local/offline. | The spec's "never connects to the internet" rule is about telemetry/cloud services, not slippy-map tile images; OSMDroid needs at least first-load tile fetches to show a real basemap. |
| Data source for v0.1 | Realistic mock/fixture data behind a repository abstraction | The device-side manifest endpoint doesn't exist yet; this keeps every screen interactive today without blocking on dashcam-side work. |

## 2. Dev environment bootstrap (this machine had none of this installed)

Checked first: Node v26/npm 11 present, but **no** JDK, **no** Android SDK,
**no** Android Studio, **no** `adb` on PATH (a copy of `adb.exe` did already
exist buried in `toolkit\platform-tools\` from the dashcam EDL work, but a
fresh SDK install was done instead of reusing it, to avoid version skew).

Installed, in order:
1. **Eclipse Temurin JDK 17** via `winget install --id EclipseAdoptium.Temurin.17.JDK`.
   `JAVA_HOME` and `%JAVA_HOME%\bin` persisted to the **User** environment
   (not machine-wide) via `SetEnvironmentVariable(..., "User")`.
2. **Android SDK command-line tools** — downloaded
   `commandlinetools-win-11076708_latest.zip` directly from
   `dl.google.com/android/repository/` (no Android Studio installer used),
   extracted to `C:\Android\cmdline-tools\latest\` (the exact layout
   `sdkmanager` expects). `ANDROID_HOME` / `ANDROID_SDK_ROOT` persisted to
   `C:\Android`.
3. Accepted all SDK licenses non-interactively (`yes | sdkmanager --licenses`).
4. Installed via `sdkmanager`: `platform-tools`, `platforms;android-34`,
   `build-tools;34.0.0`, `emulator`, `system-images;android-34;google_apis;x86_64`.
5. **Gradle 8.7** — downloaded the official binary distribution directly
   from `services.gradle.org` (not via winget/choco, to get an exact known
   version), extracted to `C:\Android\gradle-dist\extracted\gradle-8.7\`,
   then ran `gradle wrapper --gradle-version 8.7` once inside the new
   project to generate the standard `gradlew`/`gradlew.bat` + wrapper jar —
   this is the only use of the manually-installed Gradle; all subsequent
   builds go through the project's own wrapper, per normal Android project
   convention.
6. Created AVD `KhanLabs_Pixel_API_34` (Pixel-class profile, API 34,
   `google_apis` x86_64 image) via `avdmanager`.

All of the above only needed **CLI downloads + `sdkmanager`/`avdmanager`**;
no Android Studio GUI was installed or required at any point.

## 3. Project layout

```
khanlabs-dashcam-app/
  app/
    src/main/java/dev/khanlabs/dashcam/
      MainActivity.kt
      ui/theme/            Color.kt, Type.kt, Theme.kt (OLED dark, Electric Cyan / KhanLabs Orange)
    src/main/res/           themes.xml, colors.xml, strings.xml, adaptive launcher icon
    build.gradle.kts
  build.gradle.kts          (root)
  settings.gradle.kts
  gradlew / gradlew.bat     (generated, see above)
```

Package name: `dev.khanlabs.dashcam`. minSdk 26 (Android 8.0 — chosen so the
adaptive launcher icon format alone is sufficient, no legacy PNG fallback
needed), targetSdk/compileSdk 34.

**Dependency staging:** the first build intentionally ships with only the
minimum Compose/activity/navigation dependencies. Hilt, Room, Retrofit/OkHttp,
Media3 ExoPlayer, and OSMDroid are added incrementally as the specific screen
that needs them is built (Gallery → Media3; Map → OSMDroid + Retrofit; sync
→ WorkManager/Room/Hilt) rather than declared all at once, so the very first
build+install (proving the toolchain works end-to-end) has the smallest
possible dependency graph and smallest chance of failing on something
unrelated to "does the loop work at all."

## 4. The "watch it build live" loop — how it actually works here

Native Android has no Expo-style instant fast-refresh from a CLI agent.
The loop used instead: edit Kotlin/resource files → `gradlew installDebug`
→ app (re)installs and launches on the running emulator, typically
seconds-to-low-minutes per cycle depending on what changed. Flagged to the owner
before starting; true type-and-see Live Edit would require Android Studio's
own IDE session, which isn't driven from here.

## 5. Build log

- **2026-07-06 17:12** — Toolchain proven end-to-end: created AVD
  `KhanLabs_Pixel_API_34` (Pixel 6 profile, API 34 google_apis x86_64),
  booted it (`emulator.exe -avd KhanLabs_Pixel_API_34 -no-snapshot -gpu auto`),
  waited for `sys.boot_completed=1`, then `gradlew installDebug` + `adb shell
  am start` to launch `MainActivity`. Boot screen confirmed on-device: "KL"
  wordmark in Electric Cyan, "KhanLabs Dashcam" title, tagline, and a
  monospace "build/install loop: OK" line, all on true black — matches the
  brand spec. Fixed one build warning (unused `darkTheme` param in
  `Theme.kt`, since this app only ever has one OLED-dark theme).
  From here on: `gradlew installDebug && adb shell am start -n
  dev.khanlabs.dashcam/.MainActivity` is the per-milestone loop.

- **2026-07-06 17:22** — Dashboard screen (Screen 1) built and confirmed on
  the emulator. Introduced the full MVVM+Hilt architecture at this point
  (deferred from the bootstrap build to keep that first build's dependency
  graph minimal, per section 3): `@HiltAndroidApp` application class,
  `DashboardViewModel` (`@HiltViewModel`, exposes `StateFlow<DeviceStatus?>`),
  `RepositoryModule` binding `MockDashcamRepository` to the `DashcamRepository`
  interface. First `kaptDebugKotlin`/`hiltJavaCompileDebug` run added ~2m to
  the build (annotation processing cold start) — expected, not a problem.
  Also stood up the bottom-nav `NavHost` shell (`Destination` enum: Dashboard/
  Videos/Map/Settings) that the remaining screens plug into; Videos/Map/
  Settings currently show a `ComingSoonScreen` placeholder until their
  milestones land.

- **2026-07-06 17:5x** — Video Gallery + Player screens (Screen 2) built and
  confirmed on the emulator. Added `VideoClip`/`CameraIndex` model,
  `VideoFilenameParser` (implements the exact regex from the spec, section
  6B), and `MockVideoRepository`/`VideoRepository` (built by running
  real-shaped filenames, including a `<imei>_..._2009-01-
  01_..._1230760835.mp4` example in the device's real naming format,
  through the real parser -- not hand-built fixtures). `GalleryViewModel`
  derives the dual-cam grouping (matching epoch suffix with both a `_0_` and
  `_1_` file) and the tab filter (All/Road/Cabin) as a single combined
  `StateFlow`. Gallery UI matches spec section 4 Screen 2: tabs, Recent (GPS
  Synced) vs Legacy/Unsynced sections, inline amber warning on unsynced
  clips (kept inline rather than tooltip-only, since a screenshot/glance
  needs to show it without a long-press), cyan "Dual-Cam Event" badge,
  Play/Share/Delete row (Delete has a confirm `AlertDialog` and actually
  removes the clip from the mock repository's `StateFlow`; Share is a
  documented no-op in mock mode since there's no real synced file yet).
  Added Media3 ExoPlayer (`media3-exoplayer`/`media3-ui`) for the Player
  screen; since there's no real device to pull footage from yet, generated
  two small synthetic H.264 clips locally with `ffmpeg` (`testsrc2` pattern,
  hue-shifted for the cabin one) and bundled them as `res/raw/sample_road.mp4`
  / `sample_cabin.mp4` so playback is a real decode, not a placeholder icon.
  Player screen shows the tapped clip's filename, and for a dual-cam event
  exposes the spec's "Switch to Dual-View" button (`PlayerViewModel` looks
  up all clips sharing the tapped clip's epoch).

  **Bug fixed during this milestone:** the filename subtitle
  (`Text("File: ${clip.filename}", maxLines = 1, ...)`) rendered completely
  blank on-device even though the string was confirmed non-empty (verified
  via `uiautomator dump`'s accessibility-tree text). Root cause not fully
  isolated, but adding an explicit `Modifier.fillMaxWidth()` +
  `overflow = TextOverflow.Ellipsis` resolved it immediately, so it's kept
  as the pattern for any future long single-line `Text` in this codebase --
  don't rely on `maxLines = 1` alone without an explicit width + overflow.

  **Emulator-only limitation found (not an app bug):** concurrent
  side-by-side Dual-View playback renders both `PlayerView`s black, while
  single-view playback of either clip works fine. `adb logcat` shows the
  AVD's software decoder (`c2.goldfish.h264.decoder`) assigning two output
  surface generations and then immediately logging `Codec2-OutputBufferQueue:
  receiving stale buffer` / `MediaCodec: rendring output error -32` for both
  -- a known category of goldfish/software-codec flakiness with more than
  one concurrent decode+render session, not something the app controls. The
  dual-view UI, state toggle, and per-clip lookup are all confirmed correct
  (button label flips, layout splits into two panes); this should be
  re-verified on a real physical device (hardware MediaCodec handles
  concurrent sessions far more reliably) before treating it as resolved.

  **Tooling note:** `adb shell uiautomator dump` was found to reliably pop
  the current screen back to the previous nav destination on this AVD
  (API 34 google_apis x86_64) -- reproduced twice. Suspected cause: toggling
  the UiAutomation accessibility service interacts badly with Android 14's
  predictive-back handling. Avoided for the rest of this session; screenshots
  + known layout coordinates used for navigation instead of accessibility
  dumps.

- **2026-07-06 18:0x** — GPS Trip Map screen (Screen 3) built and confirmed
  on the emulator. Added `GpsPoint`/`GpsTrack`/`TripStats` model plus
  haversine-based distance/speed/duration calculation (`computeStats()`,
  `speedAt()`), and `GpxParser` -- a real streaming `XmlPullParser` reader
  (per spec section 5's explicit "don't load as DOM" guidance) over a
  bundled `res/raw/sample_trip.gpx` fixture (12 synthetic trackpoints, at GPSLogger's real
  ~60s default log interval). `MockTripRepository` parses it once via Hilt's
  `@ApplicationContext`.

  Map rendering uses OSMDroid (`org.osmdroid:osmdroid-android`), per the
  locked-in stack decision -- no API key needed. Screen is a Material3
  `BottomSheetScaffold` (a real draggable sheet, not a hand-rolled one) with
  the map as main content: cyan `Polyline` for the route, green/red
  `GradientDrawable` dot markers for start/end (avoided referencing
  osmdroid's internal default-marker resource id, which is fragile across
  library versions), a third cyan dot marker tracking the replay position,
  tap-on-polyline-to-drop-a-pin (nearest-point search against the tapped
  `GeoPoint`), a date-selector dropdown (wired for multi-day even though
  today's fixture only has one date), Distance/Duration/Max Speed stats, and
  a replay `Slider` + play/pause that animates the marker along the route
  (confirmed: dragging the slider moves the marker and updates the live
  "time • speed" readout).

  **Brand-spec fix applied:** OSMDroid's default tile source (`Mapnik`) is
  light-only, which conflicts with the spec's "dark-themed map" requirement.
  Switched to CartoDB's free `dark_all` basemap via a custom `XYTileSource`
  (`https://{a,b,c}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png`, verified
  reachable and returning real tiles both from the host machine via `curl`
  and from the emulator). This is the app's one deliberate exception to
  "zero cloud" (see the brainstorming decision log) -- confirmed the AVD has
  outbound internet before relying on it. Only tradeoff found: the dark
  tiles take noticeably longer to first-paint than Mapnik's (~8-10s vs ~3s
  cold) -- not a bug, just a slower CDN path from this network; tiles do
  load correctly, just be patient before concluding a map is broken.

- **2026-07-06 18:1x** — Settings screen (Screen 4) built and confirmed on
  the emulator, completing all four screens from the spec. Added
  `AppSettings` model + `SettingsRepository`/`MockSettingsRepository`
  (in-memory `StateFlow`, same repository-interface staging as the other
  three screens -- a later DataStore-backed implementation is a one-line
  swap). `SettingsScreen`: Sync Settings toggles (auto-sync on WiFi,
  download original quality), a Storage Settings toggle for auto-delete
  that requires an `AlertDialog` confirmation *only when turning on*
  (turning it back off is immediate -- disabling a dangerous behavior isn't
  itself dangerous), and an About section (app version via `BuildConfig`,
  a tappable `khanlabs.dev` link opening the system browser, and an
  "E-WASTE REPURPOSED" badge). Enabled `buildConfig = true` in
  `app/build.gradle.kts` (required explicitly under AGP 8, off by default).
  Deleted the now-unused `ComingSoonScreen` placeholder composable -- all
  four bottom-nav destinations are real screens now, nothing left to stub.

## 6. Current state / what's next

All four screens from the spec (Dashboard, Video Gallery + Player, GPS Trip
Map, Settings) are built, installed, and confirmed working on-device via
screenshots this session. Everything is backed by mock repositories behind
interfaces (`DashcamRepository`, `VideoRepository`, `TripRepository`,
`SettingsRepository`) so the real sync/manifest work can slot in later
without touching UI code. Not yet done, deliberately out of scope for this
session:

- Real HTTP sync against a dashcam (`Retrofit`/`OkHttp`/`WorkManager` are
  directed by the architecture but not yet wired to anything, since the
  device-side manifest/listing endpoint doesn't exist yet -- see section 0).
- `Room` for tracking downloaded files / sync state (needs the above first).
- `WifiNetworkSuggestion` auto-join of `Dash-*` hotspots.
- Physical-device verification of the dual-cam side-by-side player (emulator
  decoder limitation noted above) and of real map/GPS behavior outdoors.
- App icon is a placeholder geometric mark (Electric Cyan ring), not a
  final logo.

## 7. Real-device testing session (2026-07-06, later same day)

Connected a real test phone (OnePlus 8T, Android 14) via USB with debugging enabled -- first time testing on real
hardware instead of the emulator. Immediately surfaced two real bugs and
drove several real feature additions the emulator couldn't have exercised
(no real WiFi network to join, no permission-grant flow worth testing on a
fake network).

**New: real (non-mock) WiFi/network layer**
- `WifiConnectionObserver` (`data/network/`) -- observes the actual
  currently-connected WiFi SSID via `ConnectivityManager.NetworkCallback`,
  matches it against `Dash-*`, and reads the gateway IP via
  `WifiManager.dhcpInfo`. Requires `ACCESS_FINE_LOCATION` at runtime (an
  Android platform restriction on reading SSIDs, not something this app
  wants) -- `DashboardScreen` has the permission-request UI.
- `DashcamProbe` -- a real reachability check (OkHttp GET to
  `http://<gateway>:8080/`, the dashcam's confirmed busybox httpd) used by
  the Dashboard's SYNC button. Since there's still no manifest/listing
  endpoint on the device, this can only honestly confirm "is the file
  server up", not enumerate or download anything -- and says so.
- Dashboard now shows a live Signal card (RSSI + qualitative label) and the
  TopBar's connection line reflects real state: granted-but-not-dashcam,
  ungranted, or genuinely connected to a `Dash-*` network.
- Settings gained a "Connection" info card (live SSID/gateway/signal) and
  two more toggles (`notifyOnNewClips`, `keepScreenOnDuringSync`).

**Bug #1 (real, permission-timing):** granting the location permission
*mid-session* didn't update the displayed SSID -- `WifiConnectionObserver`'s
flow only re-emits on actual `NetworkCallback` events (network changes),
and granting a permission isn't one. Fixed by adding `refresh()`, called
from both the in-app permission-launcher callback and an `ON_RESUME`
lifecycle observer (covers granting via system Settings while the app was
paused). If a live value looks stale after a permission change, this is the
first place to check.

**Bug #2 (real, non-obvious, real-hardware-only):**
`NetworkCapabilities.transportInfo` -- the "modern" documented way to pull
`WifiInfo` out of a `NetworkCallback` -- came back location-*redacted* on
this real device (OnePlus 8T / Android 14) **even with
`ACCESS_FINE_LOCATION` granted**: SSID stayed `<unknown ssid>`, BSSID masked
to `02:00:00:00:00:00`. Confirmed via temporary `Log.d` diagnostics
comparing it side-by-side against the classic (deprecated)
`WifiManager.connectionInfo`, which returned the real SSID (`"HomeWiFi"`) and
BSSID under the *exact same* permission state in the same process. Fixed by
deliberately using the deprecated `WifiManager.connectionInfo` API instead
-- see the doc comment on `WifiConnectionObserver` before "cleaning up"
that deprecation warning. RSSI was unaffected by this bug either way (it
doesn't require location permission).

**Confirmed dashcam is not reachable this session:** the physical dashcam
was reported connected, but neither `adb devices` nor Windows' present-USB-device
list showed it (checked both `-l` output and `Get-PnpDevice -PresentOnly`)
-- likely a cable/port issue or a pending on-device "allow USB debugging"
authorization. Not yet resolved as of this note.

**Tooling note:** installing to a specific device when multiple are
attached (phone + emulator) needs `adb -s <serial> install -r <apk>` --
plain `gradlew installDebug` fails/ambiguous-targets when more than one
device is connected.

## Section 8: "Opus intake" -- Dashboard/Settings expansion (2026-07-06)

Requested design advice on Dashboard content and Settings options, taken
from a review of this session's real-device findings (see the dashcam
project's own memory for the device-side investigation -- a second AI-12
unit came online mid-session with a real `Dash-0154`
hotspot and a working httpd file server, which grounded these decisions
in what the real device actually exposes rather than guesses).

**Dashboard: replaced the "Uptime" stat card with "Last Sync".** Uptime
was a fake, unchanging mock string with no action attached. Last Sync is
real: `DashboardViewModel` now tracks `lastSuccessfulSyncAt` separately
from the existing `syncStatus` (which reflects the outcome of the *last*
attempt, success or failure) -- it only advances on an actual
`ProbeResult.Reachable`, so it means what it says. Verified on-device: a
probe while off the dashcam's network correctly left it at "Never".
Storage mock (`MockDashcamRepository`) changed from an implausible
45/119GB to 8.5/119GB -- 119GB already matched the real SD card's size.

**Settings: three new, non-decorative additions** (each wired to a real,
observable state change, not just a switch that does nothing):
1. **Sync scope** (Both cameras / Road only / Cabin only) -- a 3-way
   choice row, new `SyncScope` enum in `AppSettings`. `VideoClip` already
   has a `CameraIndex` (ROAD/CABIN) matching the real device's `_0_`/`_1_`
   filename convention, so this concept already existed in the data model.
2. **Download location + clearable cache** -- `DownloadLocation` enum
   (App storage / Public Movies folder) plus a `cachedDownloadsBytes`
   mock stat (seeded at 340MB) with a real "Clear" action that zeroes it
   -- verified on-device (340MB -> 0MB, button visually disables at 0).
3. **Manage Dashcam** -- `SettingsViewModel` now auto-remembers whichever
   `Dash-*` SSID the phone actually joins (`pairedDashcamSsid`, updated
   reactively off `WifiConnectionObserver`), shown with a "Forget this
   dashcam" action.

New reusable `SettingChoiceRow` composable (generic over the enum type)
replaces what would otherwise be three near-duplicate selector UIs.

All of this stays in the same in-memory-repository staging pattern as
the rest of the app (settings reset on app restart) -- deliberately not
adding DataStore persistence for just these fields while the rest of the
app's settings are still session-only; that would be an inconsistent,
half-done persistence layer rather than a real one.

Build verified (`assembleDebug` succeeds) and manually tested end-to-end
on the real OnePlus 8T: Dashboard's Last Sync card, the SYNC button's
real probe result, all three new Settings rows, and the Clear-cache
button's live before/after state -- all confirmed via screenshots.

## Section 9: Real Video Gallery + GPS Map data (2026-07-06, continued)

Connected the app to the dashcam for real, past the reachability-only
probe: Video Gallery and GPS Trip Map now read live data from the
`cgi-bin/browse` endpoint (Section 8's device-side work) instead of mock
fixtures.

**Transport check came first, per advice, before writing any repository
code.** Joined the test phone to `Dash-0154` via
`adb shell cmd wifi connect-network`, then tapped SYNC in the app.
**It worked on the first real try** -- "File server reachable at
192.168.43.27:8080" -- despite cellular being the phone's system-default
validated network. The theorized failure mode (an unbound OkHttpClient
routing over cellular instead of WiFi) did not materialize: Android
routes traffic to a directly-attached subnet (here, the WiFi's own
192.168.43.0/24) via the interface that subnet belongs to regardless of
which network is the overall "default," so no `bindProcessToNetwork`
workaround was needed. Confirmed by reading `dumpsys connectivity`'s
route tables side by side, not just assumed.

**New: `DashcamManifestClient`** (`data/network/`) -- the shared client
for `list()` (calls `cgi-bin/browse`, parses the JSON array) and
`fetchBytes()` (whole-file fetch, used for GPX). Fails soft (empty
list / null) on any error rather than throwing, since "no data" is
already a normal state every caller handles.

**New: `RealVideoRepository`** -- lists `recordings/`, recurses into each
date subfolder, filters to `*.mp4`, and parses filenames through the
existing `VideoFilenameParser` (unchanged -- the real device's filenames
match the pattern exactly, confirmed against live data). The listing
endpoint doesn't expose clip duration, so duration is estimated from the
gap to the next clip on the same camera stream (this dashcam records in
roughly fixed-length segments) -- a real, measured stand-in rather than a
hardcoded guess or a misleading "0s". `deleteClip` hides the clip locally
for the session (a `Set<String>` filter) rather than silently no-op'ing,
since the dashcam's manifest endpoint is read-only by design -- real
on-device deletion needs a write-capable endpoint that doesn't exist yet.

**New: `RealTripRepository`** -- lists `gpx/files/GPSLogger/`, fetches
each `.gpx` whole, and parses it with the existing (unchanged)
`GpxParser`. Currently returns an empty list on this unit because of the
GPX bind-mount bug found earlier in this session (see the dashcam
project's own memory) -- confirmed via direct `cgi-bin/browse` query
returning `[]`. This is the correct, honest behavior for the current
device state, not a bug in this repository.

**`VideoClip` gained a `sourceUrl: String? = null` field** -- null for
mock/demo clips (which still play from the bundled raw resources), a
real `http://<gateway>:8080/recordings/<date>/<filename>` URL for clips
that came from a real listing. `PlayerScreen` now resolves each
`VideoSurface`'s URI from the selected clip's `sourceUrl` if present,
falling back to the bundled sample clip otherwise -- Media3 ExoPlayer
plays HTTP sources natively, so no download step was needed to make
playback real, only the URL. No `PlayerViewModel` changes were needed;
event-grouping by `epochSeconds` already works identically for real
filenames.

**`RepositoryModule` now binds `RealVideoRepository`/`RealTripRepository`**
instead of the Mock versions. `DashcamRepository` (aggregate
storage/GPS/uptime stats) and `SettingsRepository` deliberately stay
mock-bound -- no device-side endpoint exposes that aggregate status, and
building one wasn't in scope here. The Mock* classes are kept in the
codebase (not deleted) as a reference and in case a fallback is ever
useful; only the Hilt binding changed, per the original one-file-per-
repository design.

**Verification:** build succeeds (`assembleDebug`); the real transport
was proven live via the SYNC probe; the real `cgi-bin/browse` JSON shapes
were checked directly against the parsing code's assumptions (date
folders, a stray `logs/` folder with a non-mp4 file inside it, real
filenames matching `VideoFilenameParser`'s pattern exactly, an empty GPX
listing) -- all handled correctly. Full on-device Gallery/Map screenshots
against live data are still pending (the test phone dropped off USB
mid-session); the code path itself is verified sound and ready to check
visually next time the phone's available.

## Section 10: Real Dashboard status + download sync (2026-07-13)

Dashcam-side work (WiFi/hotspot/reboot-loop fixes, and a new `cgi-bin/status`
endpoint alongside the existing `cgi-bin/browse`) wrapped up in the sibling
firmware project -- see `khanlabs-dashcam-firmware`'s `BACKLOG_2026-07-13.md`
for the consolidated cross-project task list this and future sessions are
working from (items here are its `A1`/`A2`).

**`RealDashcamRepository` (A1)** -- binds in place of `MockDashcamRepository`.
Reads `cgi-bin/status` for storage/uptime, combined with the existing
`WifiConnectionObserver` for ssid/isConnected (the device endpoint
deliberately doesn't serve those -- they're already known client-side).
`newClipCount`/`newTrackCount`/`readyToSyncGb` stay hardcoded at 0 --
computing them for real needs persisted sync-state (Room), out of scope
here (backlog item A3), and deliberately not backed into by having this
repository make an extra `cgi-bin/browse` call on every status poll.
`GpsLockState.Active.satelliteCount` changed from `Int` to `Int?`: the
device's `gps_fix` is a file-write-recency heuristic, not a real satellite
read, and `status` already reports `gps_satellites: null` rather than a
fabricated number -- the model needed to be able to carry that honestly.

**Caught during review, not by compiling:** the Dashboard's storage
progress bar computed `storageUsedGb / storageTotalGb` directly. The mock
fixture's `storageTotalGb` was always `119.0`, so this path never divided
by zero; the real repository legitimately reports `0.0` off-network,
which is `0.0/0.0 = NaN` fed straight into `LinearProgressIndicator`.
Guarded in `DashboardScreen` (falls back to `0f` when total is 0) and
switched the storage text from a raw `Double.toString()` to `%.1f`
formatting. This is exactly the class of bug a mock->real repository swap
introduces that a compile check can't see -- the value *space* changed,
not just the value source.

**`SyncEngine` (A2)** -- the SYNC button now does a real download of new
clips/tracks to local storage, not just a reachability probe. Scoped
deliberately tight, decided against building more:
- **OkHttp, not Retrofit**, despite Retrofit being named in the original
  architecture decision log (`## 1`). The codebase already diverged from
  that plan (the manifest client is hand-rolled OkHttp + manual JSON
  parsing) once a real endpoint existed to build against, and Retrofit
  buys nothing for streaming a binary file to disk.
- **Not WorkManager-backed.** The SYNC button only ever runs while the app
  is open and foregrounded -- a plain suspend function in the existing
  `viewModelScope` covers that completely. WorkManager's actual value
  (surviving process death, running without the app open) only matters
  for the *auto*-sync-on-WiFi trigger, which isn't built yet (the
  `autoSyncOnWifi` setting exists in `AppSettings` but nothing reads it).
  Building that later needs `@HiltWorker` + a `Configuration.Provider` on
  the `Application` class -- real, not-yet-tested wiring, deliberately not
  taken on this session with no physical test phone connected to verify
  against.
- **Dedup by local filename existence**, not persisted state -- good
  enough since filenames are already unique per clip/track; Room-backed
  tracking (A3) is the real version of this.
- **App-private storage only** (`getExternalFilesDir(DIRECTORY_MOVIES)`).
  `DownloadLocation.PUBLIC_MOVIES` falls back to the same directory --
  MediaStore is its own scope on a scoped-storage-era target SDK and
  wasn't worth taking on alongside everything else here.
- **Honors `syncScope`** (both/road-only/cabin-only), filtering clips by
  `CameraIndex` before downloading -- cheap and honest to include.
- **Does not honor** `downloadOriginalQuality` (the device only ever
  serves one file per clip -- there's no alternate quality to switch to)
  or `autoDeleteAfterDownload` (no delete-capable device endpoint exists,
  same limitation `RealVideoRepository.deleteClip` already documents).
  Both are silent no-ops for now, not attempted.
- Downloads write to a `.filename.part` sibling and rename on success, so
  an interrupted download never leaves a corrupt file at the real path.

**Dashboard UI**: added a `Syncing` state to `SyncStatus` (distinct from
the existing `Probing` reachability check) and a `downloadSummary` on
`Done`, so the SYNC result text distinguishes "up to date" / "synced N
clips, M tracks" / "N failed" / "reachable but sync itself failed" --
the last case matters because a probe succeeding doesn't guarantee the
subsequent download attempt did. `lastSuccessfulSyncAt` now only updates
when the sync attempt actually completed (`summary != null`), not merely
on a reachable probe, since SYNC now means more than a ping.

**Verification:** `assembleDebug` succeeds, including `kaptDebugKotlin` --
meaningful signal specifically because Hilt/Dagger validates the whole DI
graph at that step (would have failed if `SyncEngine`'s constructor
dependencies didn't resolve). What compiling can't verify, and wasn't
verified this session: no test phone was connected, so the actual
download path (a live GET against a real dashcam hotspot, writing real
files to a real device's storage, the SD-card-not-mounted-yet race
`khanlabs_httpd.sh` already had to handle on the device side) has not
been run for real. Treat A2 as implemented and reviewed, not confirmed
working end-to-end -- the phone-on-`Dash-*` test is the actual completion
bar, same as it was for A1's Dashboard wiring.

**Real gap this doesn't close, flagged during review (not caught by
compiling):** nothing reads the downloaded files back yet.
`RealVideoRepository`/`RealTripRepository` still enumerate from
`cgi-bin/browse`, and `PlayerScreen` plays from `clip.sourceUrl` (the
dashcam's own HTTP URL) -- neither ever looks in `SyncEngine`'s download
directory. Concretely: tap SYNC on the dashcam's hotspot, drive off
-network, open Gallery -- it's still empty, because the listing itself
is remote-only, not because the files aren't there. That's the actual
gap in the store-and-forward promise from `## 0`'s context, not just a
"nice to have." Deliberately not fixed alongside A2 (would need
`PlayerScreen`/`RealVideoRepository` to check local-file existence first,
more untested-without-a-phone surface) -- named explicitly here and in
`BACKLOG_2026-07-13.md` (new item **A2b**) rather than left implicit, so
a future session doesn't mistake "A2 done" for "offline playback works."

Also verified, not just assumed: filenames
(`{imei}_{cameraIndex}_{yyyy-MM-dd}_{HH-mm-ss}_{unixEpoch}.mp4`, see
`VideoFilenameParser`) embed the absolute unix epoch down to the second,
so flat-directory dedup by filename alone (no per-date subfolder) is
safe even given the dashcam's clock-reset behavior -- two clips can only
collide if they share the exact same recorded second, which the
recording process can't produce.

## Section 11: Real hardware verification -- status → sync → offline playback (2026-07-13, continued)

Connected both the dashcam and the OnePlus 8T (the same
test phone from Section 7) in the same session as Section 10's work,
enabling the first real end-to-end run of A1/A2/A2b together -- the
"verification debt" flagged at the end of Section 10 got paid off the
same day, not deferred.

**Setup, done entirely via adb (no manual taps needed for network join)**:
`adb shell cmd wifi connect-network "Dash-0154" wpa2 "Shelter8"` joined
the phone to the dashcam's hotspot (confirmed via `ip route` showing a
real `192.168.43.0/24 dev wlan0` link route and a successful
`curl http://<dashcam-ip>:8080/cgi-bin/status` from the phone itself).
Installed the debug APK via `adb install -r`, launched via `monkey -c
android.intent.category.LAUNCHER`, and drove the UI via `input tap` +
`exec-out screencap` (coordinate math: screenshots come back at the
device's real resolution, e.g. 1080x2400 on this phone -- no scaling
needed for `input tap`, a mistake made once this session by tapping at
a downscaled preview's coordinates instead of the real ones).

**A1 confirmed live, first try**: Dashboard showed "Connected to
Dash-0154", Storage "7.2 / 119 GB" -- matching a direct `curl` of
`cgi-bin/status` byte-for-byte -- and an honest "GPS: Searching..."
(matches the known F2 gap, not a bug). No permission-grant dialog was
needed; location permission was apparently still granted on this phone
from Section 7's earlier testing.

**Real bug #3, found and fixed live**: tapping SYNC immediately showed
"Couldn't reach `<gateway>`:8080 -- HTTP 404" -- `DashcamProbe` GETs the
bare webroot (`http://<gateway>:8080/`) and required a 2xx response, but
busybox httpd 404s there by design (no autoindex/index.html, a
documented limitation since `khanlabs_httpd.sh` was first written).
This meant the reachability check -- the very first gate in the SYNC
flow -- failed 100% of the time on real hardware, something no amount
of static review or compiling could have caught, since the mock probe
never exercised a real HTTP response at all. Fixed: any real HTTP
response (even 404) now counts as reachable, only a genuine
`IOException` means unreachable. Rebuilt, reinstalled, retested --
SYNC immediately progressed to "Downloading new clips and tracks...".

**A2 confirmed downloading real data at real throughput**: the
dashcam had 396 real clips (7.2GB total, confirmed via `find`/`du` on
the device side) queued to sync -- too much to wait out fully in one
session. Verified real transfer instead of just "no crash" via two
independent measurements: `/proc/net/dev`'s wlan0 RX counter climbing
by real amounts between checks (~38MB over one 5-second window, ~7.6
MB/s), and the phone's own "FlowBytes" data-usage widget independently
reporting "Today: WiFi 2.94 GB" climbing to "3.40 GB" within about a
minute -- two different measurement sources agreeing rules out a
fluke. No exceptions in logcat throughout.

**A2b confirmed working exactly as designed, including its intended
trigger condition happening for real**: mid-sync, the phone's WiFi
auto-roamed away from the dashcam's hotspot onto a known "HomeWiFi" home
network with real internet (Android deprioritizing a no-internet AP in
favor of a validated one -- not a bug in this app, just how Android's
network scorer behaves, and a preview of exactly the challenge the
now-BLOCKED A4 (`WifiNetworkSuggestion`) would have to fight). With the
phone confirmed on "HomeWiFi" (Dashboard correctly read "On 'HomeWiFi' (not a
dashcam)"), opened the Video Gallery -- **real synced clips appeared**
(`Road Cam • 04:18:57 • 1s`, `04:18:30 • 26s`, etc., filenames starting
with the real device IMEI, correctly flagged "System clock was unset"
under a "LEGACY / UNSYNCED" heading) -- the exact scenario A2b was built
for: footage surviving off the dashcam's network. Tapped play on one:
logcat showed a real `OMX.qcom.video.decoder.avc` MediaCodec instance
created and cleanly released (consistent with a very short 1s clip
finishing), no `ExoPlaybackException`/source errors anywhere -- genuine
hardware-decoded local `file://` playback, not just a listing that
happens to show entries.

**New minor gap found, not fixed**: `SyncEngine.syncNow()` captures the
gateway IP once at the start and has no mid-flight awareness of the
WiFi state changing (unlike `RealVideoRepository`/`RealDashcamRepository`,
which use `flatMapLatest` over `observeWifiState()` and so cancel/restart
cleanly on their own). When the phone roamed to "HomeWiFi" mid-sync, the
original `syncNow()` coroutine kept running, failing each remaining
download against the now-unreachable old gateway IP one by one instead
of stopping early. Doesn't crash or corrupt state (failures just count
in the final `DownloadSummary`), just wastes time on a large sync that
gets interrupted by a real network change -- worth fixing whenever
`SyncEngine` is next touched (e.g. cancel/restart the sync coroutine on
a wifi-state change, mirroring the pattern the other repositories
already use), added to `BACKLOG_2026-07-13.md`.

**Net**: this is the first time A1/A2/A2b have been proven correct
together against real hardware rather than reasoned through via review
-- the "verification debt" is paid off. Two real code bugs were found
this way (the reachability probe, the mock-vs-real value-space NaN from
Section 10) that no amount of additional static review would have
caught; a third (mid-sync network-loss handling) was found and
deliberately left as a documented follow-up rather than an unverified
fix bolted on in the same pass.

## Section 12: Closing the Done-state gap + A2c mid-sync WiFi-change detection (2026-07-13, continued)

Advisor review of Section 11 caught something real: despite marking
A1/A2/A2b "VERIFIED," no screenshot anywhere actually showed `SyncStatus`
reaching `Done` with a real `DownloadSummary` rendered on screen -- every
observation had been either the pre-fix 404 error or the mid-download
"Downloading new clips and tracks..." state, because the WiFi roam had
interrupted the one real run before it finished. "Files landed and play
back" (proven in Section 11) is not the same claim as "the sync UI
correctly reaches completion," and the backlog had been calling both
VERIFIED without distinguishing them.

**Closing the gap.** With the dashcam and phone both still connected,
held the phone on `Dash-0154` at the OS level instead of touching
"HomeWiFi" (the real home network) or its saved credentials:
`adb shell cmd wifi set-network-selection-config enabled enabled -a 2`
sets `ASSOCIATED_NETWORK_SELECTION_OVERRIDE_DISABLED`, which stops
Android's WiFi framework from switching away from whatever network is
currently associated. Non-destructive, systemwide, and trivially
reversible (`-a 0` restores default behavior) -- no saved network
credentials were touched.

**Found along the way**: the dashcam's hotspot had gone to sleep/torn
down entirely since the Section 11 session -- `dumpsys wifi` showed no
`SoftApManager` dump at all, and `Dash-0154` didn't even appear in the
phone's scan results. Not a bug, just a real device behavior worth
remembering: the hotspot doesn't stay up indefinitely on its own and
needs `am broadcast -a com.surfsolutions.hotspot --ez ENABLED true --es
SSID Dash-0154 --es PASS Shelter8` re-sent to come back.

Reconnected, force-stopped and relaunched the app fresh, tapped SYNC.
This run pulled real new footage recorded since Section 11 (sustained
~7.6 MB/s for several minutes, not a fast dedup-skip pass) and reached:

```
Synced (16:00:34) — 334 clips, 0 tracks
```

rendered in the success color with "Last Sync" populated -- confirmed
by screenshot. The Done-state gap is closed; A1/A2/A2b's "VERIFIED"
label now has the evidence to match it.

**A2c: mid-sync WiFi-change detection, built and live-verified.**
`SyncEngine.syncNow()` now launches a watcher coroutine alongside the
download loops that collects `wifiConnectionObserver.observeWifiState()`
and flips an `AtomicBoolean` the instant the phone leaves the gateway
(`!isDashcamNetwork || gatewayIp != gatewayIp`); both the clip loop and
the gpx-track loop check the flag before every single download and
`break` if it's set, instead of only reacting between whole batches.
`downloadToFile`'s existing 3s connect / 8s read OkHttp timeouts bound
how long any single in-flight download can hang after the network
actually changes, so the check-between-iterations approach (rather than
`select`-racing coroutines) is enough -- no download can block the flag
check indefinitely.

Compiled clean (`kaptDebugKotlin`, `assembleDebug`), then **live-tested
on real hardware, not just reasoned through**:
1. Used `adb shell run-as`/plain shell `ls`/`rm` on the app's
   `getExternalFilesDir(Movies)` directory (readable/writable without
   root on this device -- `sdcard_rw` group) to delete 40 of the 400
   already-synced local clips, forcing a real re-download batch on the
   next SYNC rather than an instant all-dedup-skip no-op.
2. Reinstalled the A2c-fixed APK, relaunched, tapped SYNC. Confirmed via
   polling the local file count that real downloads were landing (360 →
   378 files within ~25s).
3. Forced an immediate, deterministic network exit --
   `adb shell cmd wifi forget-network 7` (network id for `Dash-0154`) --
   partway through the batch, rather than waiting for an organic roam
   (tried restoring the default network-selection config and issuing
   `start-scan` first to prompt a natural roam like Section 11's; it
   didn't happen within a ~30s window, so switched to the deterministic
   `forget-network` approach instead, which disconnects immediately and
   is trivially reversible by reconnecting with the known SSID/password).
4. Phone landed on "HomeWiFi" instantly. Local file count flatlined at 379
   within ~3-6 seconds of the disconnect (only the download that was
   already in flight completed/failed) and never moved again.
5. The app's own summary confirmed it: **`Synced (16:04:44) — 18 clips,
   0 tracks, 1 failed`** -- 18 real downloads succeeded, exactly 1 failed
   (the in-flight one, killed by the disconnect), and the loop stopped
   there. Before this fix, the same interruption point would have shown
   something like "1 clip, ~21 failed" -- the old code kept grinding
   through every remaining file against the now-unreachable gateway IP.
   Dashboard also correctly flipped to "On 'HomeWiFi' (not a dashcam)" and
   storage back to "0.0 / 0 GB", consistent with A1's off-network
   behavior.
6. Reconnected to `Dash-0154` (`connect-network` with the known
   password) and re-ran SYNC to restore the remaining deleted files,
   leaving the phone's local cache back in a complete state.

**A2c fixes the symptom, not the cause.** The watcher makes the app stop
promptly once the network is already gone -- it does not stop Android
from roaming away from a no-internet AP in the first place, which
Section 11 already showed happens organically on a real drive-away.
The deeper, complementary fix would be the app actively holding/pinning
the dashcam's network for the sync's duration (`ConnectivityManager`/
`NetworkRequest` territory -- e.g. requesting the network and calling
`bindProcessToNetwork` for the sync's lifetime, or an equivalent held
`NetworkRequest`). Not built this session -- flagged by advisor as its
own follow-up design question with an API surface not yet verified, not
a prescription to implement blindly. Noted in `BACKLOG_2026-07-13.md`
next to A2c, and also relevant context for why A4 (`WifiNetworkSuggestion`
auto-join) stays blocked: it's the same underlying "hold the connection"
problem in a different guise.

**Net for this pass**: two things that were previously assumed-good got
actually watched happen on real hardware (a sync reaching a true Done
state with a correct summary; A2c stopping a loop it was specifically
built to stop), and the fix that was "small, concrete" on paper turned
out to need a bit of real improvisation (host-side path translation
issues with `run-as`, an organic-roam attempt that didn't cooperate on
schedule, falling back to a deterministic `forget-network` trigger)
before it could be verified rather than merely compiled.

## Section 13: A7 -- real DataStore-backed settings persistence (2026-07-13, continued)

Last item picked up this session while the dashcam and phone were still
connected. `SettingsRepository`'s own doc comment had long called the
mock-to-real swap "a one-line change" -- true for the boolean/enum
preferences, but building `RealSettingsRepository` forced one real
design decision the one-liner framing glossed over: what happens to
`AppSettings.cachedDownloadsBytes`.

**The decision (made inline, not deferred to advisor -- the analysis was
already complete by the time it came up, and the user's standing
"keep moving" feedback this session was specifically about not pausing
between finished tasks):** `cachedDownloadsBytes` is a derived fact about
disk, not a user preference, so persisting it in DataStore would just
make a stale/fixture number durable. Instead it's computed fresh on
every `observeSettings()` read by summing real file sizes in
`SyncStorage.downloadDir(context)` -- the same directory `SyncEngine`
(A2) and `RealVideoRepository`/`RealTripRepository` (A2b) already read
from. That single decision cascaded: once the displayed size is real,
`clearDownloadCache()` zeroing an in-memory number instead of actually
deleting those files would visibly lie (the number would snap back to
the real size on the very next read). So `clearDownloadCache()` now
really deletes every file in that directory. Not treated as a
destructive/go-ahead action like Format SD or Factory Reset -- it's the
app's own re-downloadable local cache, the same category of action as
any "clear cache" button in any Android app.

**Other real-boundary hardening**: `SyncScope`/`DownloadLocation` are
stored as their enum `.name` strings and read back via `valueOf()`
wrapped in `runCatching { }.getOrNull() ?: default` -- a renamed or
removed enum constant in some future version reads back as the default
instead of crashing on launch. `DataStore.data`'s `IOException` case is
caught and mapped to `emptyPreferences()` (the canonical pattern) rather
than left to propagate and break the settings screen if the underlying
file ever gets corrupted.

**Live-verified on the OnePlus 8T, not just compiled**: opened Settings,
confirmed all fields showed correct defaults on first read (no crash,
no stuck loading state) -- meaningful because this was the very first
time this DataStore file had ever been read on this device. Toggled
"Auto-sync on WiFi connect" on and switched "What to sync" to "Road
camera only," then `am force-stop` + relaunch: both settings correctly
survived, exactly as A7 was meant to fix. Scrolled to Storage Settings
and confirmed "Downloaded cache" showed a real, live "7.4 GB" matching
the actual 400-file synced set on disk from the Section 12 sync (not
the old 340 MB mock fixture). `clearDownloadCache`'s real-delete path
was code-reviewed but deliberately not exercised live -- doing so would
force a genuine ~2 GB re-download of the entire synced set for no
additional confidence beyond what the one-line `forEach { it.delete() }`
already gives on inspection.

**Net**: A7 went from READY to VERIFIED in the same pass, the third item
this session to get that treatment (after A2/A2b's Done-state gap and
A2c). All three followed the same shape: a change that looked small on
paper surfaced one real design question once it met actual disk/network
state, and got checked on the actual phone rather than left at
"compiles clean."

## Section 14: A3 -- Room-backed sync-state tracking (2026-07-13, continued)

Last backlog item picked up this session, still riding the same hardware
connection. Added `SyncedFileEntity`/`SyncedFileDao`/`AppDatabase` (one
table, filename primary key -- filenames are unique per clip/track, same
fact `SyncEngine`'s original file-existence dedup already relied on).

**The design question this one forced**: `SyncEngine`'s actual
download-skip decision stayed local-file-existence-first rather than
switching to a pure Room lookup. Reasoning: a Room-only check would mean
a file deleted outside the app (or by A7's `clearDownloadCache`) gets
silently treated as "already synced" forever, even though it's actually
gone from disk -- exactly the scenario the A2c live test manufactured on
purpose (deleting 40 clips via shell to force real re-downloads). Keeping
disk-existence as the primary gate means that scenario still works
correctly; Room is additive bookkeeping on top, backfilled from every
file the loop finds already present. This is also why `clearDownloadCache`
(A7) needed a matching update: it now clears the Room table too, for the
same coherence reason A7's own cache-size fix existed -- stale "already
synced" rows left behind after a real clear would make the Dashboard
undercount new clips until the next sync happened to overwrite them.

`RealDashcamRepository.computePendingSync()` diffs the dashcam's live
listing (clips via the already-injected `VideoRepository`, gpx tracks via
a direct `manifestClient.list()` call mirroring `SyncEngine`'s own,
since `GpsTrack` doesn't carry a filename/size) against Room rows
**unioned with local disk filenames** -- the union specifically exists
to prevent a one-time false regression: this build's Room table starts
completely empty, but the phone already has 400 real files on disk from
Section 12/13's sync passes. Without the union, first launch after this
update would have shown "400 New Clips" even though nothing is actually
new -- a Room-only diff can't tell "never synced" apart from "synced
before Room existed."

**Live-verified on the OnePlus 8T, in stages**:
1. Installed over the existing 400-file local state (Room DB brand new,
   completely empty). Dashboard correctly showed "0 New Clips" on first
   load -- the union/backfill logic worked exactly as designed, no false
   regression.
2. Tapped SYNC to force the backfill loop to run explicitly: result was
   `Up to date (16:44:24)`, 0 downloaded either way -- correct, since
   every one of the 400 remote files already existed locally. (Briefly
   worried this meant genuinely new dashcam footage from the ~35 minutes
   since the last real sync wasn't being detected; ruled that out by
   checking the dashcam's own recordings folder directly over adb --
   file count was exactly 400, matching, so there simply wasn't anything
   new to find. A `find -mmin` recency check on the dashcam side was
   briefly considered as a second check but rejected -- the device's
   system clock is unset with no RTC battery, documented extensively
   earlier in this project, so file mtimes there aren't trustworthy for
   a recency argument; the remote/local file-count match is the real
   evidence.)
3. To actually prove the "detects genuinely new content" path (the part
   that matters most and that a passing "stays at zero" test doesn't
   cover), planted a synthetic 50 KB dummy file directly on the
   dashcam's SD card via adb/su, using the same real filename convention
   (`{imei}_{cameraIndex}_{date}_{time}_{epoch}.mp4`) as genuine clips.
   Force-stopped and relaunched the app: Dashboard correctly read
   `1 New Clips • 0 New Track • 4.76837158203125E-5 GB Ready` -- the
   size math was byte-exact for 51200 bytes, and the diffing logic
   worked precisely as intended.
4. That same result surfaced a real, directly-caused display bug: the
   scientific-notation rendering of a tiny real `Double`. Harmless with
   the old always-`0.0` fixture, only visible once the field carried a
   real fractional value -- exactly the same bug class as the storage
   progress bar's NaN fix from Section 10 (a mock fixture happens to
   avoid the edge case a real value space doesn't). Fixed with `%.1f`
   formatting in `DashboardScreen.kt`.
5. Removed the synthetic file from the dashcam immediately after
   confirming detection, rebuilt with the formatting fix, reinstalled,
   and confirmed the Dashboard correctly returned to
   `0 New Clips • 0 New Track • 0.0 GB Ready`.

**Noted, not fixed**: `computePendingSync()` now does a full remote
clip+track listing (two-level directory traversal for clips, a flat gpx
listing) on every Dashboard reload, not just on SYNC -- a real few-second
cost at the current ~400-file scale, observed directly (Dashboard body
stayed blank noticeably longer on each cold load this section, initially
mistaken for a possible hang before logcat and patience ruled that out).
Not urgent at this library size; flagged in the backlog as a follow-up
if the synced set grows much larger.

**Net**: the fourth and last item this session to go from READY/planned
straight to VERIFIED-on-hardware, following the same pattern as A2/A2b,
A2c, and A7 -- each looked like a contained, well-understood change on
paper, and each surfaced at least one real coherence question or bug
only visible once it ran against actual disk state and actual dashcam
content, not mock fixtures.

## Section 15: Gallery/Dashboard/Settings UX + feature-honesty pass (2026-07-13, continued)

User feedback, verbatim in spirit: no video thumbnails, the Gallery's
remove/share buttons don't work, "many other features," the Videos tabs
don't work, and the Dashboard feels empty with one weird sync button.
Every complaint was traced to an actual line of code (not guessed at)
before any fix was written -- see the plan this session was built from for
the full grounded-findings writeup. Built with the dashcam connected but
**the test phone left partway through** (the user was heading out and took
it with them), which reframed the back half of this session as build-blind:
compile-green became the verification floor instead of live screenshots.

**Delete button was dishonest, not just broken (highest severity fix)**:
the confirm dialog claimed "This removes `<filename>` from the SD card.
This cannot be undone," but `RealVideoRepository.deleteClip` only added the
filename to an in-memory `Set` -- nothing was ever deleted anywhere, and it
reset on restart. Renamed the whole concept to **Remove**: `removeClip` now
actually deletes the local synced copy if one exists (real disk space
reclaimed -- something the button can honestly promise) and persists the
removal in a new `RemovedClipEntity`/`RemovedClipDao` Room table (bumped
`AppDatabase` to v2, `fallbackToDestructiveMigration()` -- pre-1.0
single-device app, no real migration to preserve). Filtering removed
clips happens once, inside `RealVideoRepository.observeClips()` itself --
since `SyncEngine` lists clips through that same repository, a removed
clip is automatically excluded from re-download on the next SYNC too,
with no separate wiring needed in `SyncEngine`. Dialog copy now says
exactly what happens: removes it from this list and this phone; the
dashcam's SD card is untouched (still no write-capable endpoint there).

**Share was a literal no-op** (`onClick = { /* no-op in mock mode */ }`,
stale comment from when this really was mock-only). Built for real:
`VideoRepository.prepareForShare(clip)` returns the local file if already
synced, or downloads it on demand first via the same
`DashcamManifestClient.downloadToFile` SyncEngine already uses (no second
download pathway). `GalleryScreen`'s Share button shows an inline spinner
while preparing, then opens a real `ACTION_SEND` chooser via a new
`FileProvider` (`dev.khanlabs.dashcam.fileprovider`, scoped to
`SyncStorage`'s own download dir via a new `res/xml/file_paths.xml`).

**Gallery tabs: reproduced live before assuming a fix, and the real bug
wasn't what it looked like.** Pulled real filenames straight off the
dashcam's SD card (`find /data/khanlabs-webroot/recordings`) to check
whether the camera-index parsing was somehow misclassifying clips -- it
wasn't (218 road / 214 cabin, a clean, balanced real split). Installed the
pre-fix build on the test phone and tapped through All → Road → Cabin: the
selection indicator *did* move, but every tab label rendered in the exact
same cyan, so there was no visible way to tell which tab was active without
staring at a 2dp underline. Combined with the phone having **zero locally
synced clips** at the time (verified: its download dir was empty), every
tab showed the same generic "No clips for this camera." -- reads exactly
like "the tabs don't work" even though the filter logic was correct the
whole time. Fixed both real causes: `Tab` now sets explicit
`selectedContentColor`/`unselectedContentColor`, and the empty state is
now split in two -- "No footage synced yet" with a connect/SYNC
call-to-action when there's nothing at all, vs. the original per-camera
message when other footage exists but this filter has none. `GalleryViewModel`
now exposes an unfiltered `totalClipCount` to tell the two cases apart.

**Thumbnails didn't exist at all** -- confirmed via `grep`, zero hits. Built
`ClipThumbnailRepository`: extracts one frame per clip on demand (as
Gallery rows scroll into view, never a bulk pass) via
`MediaMetadataRetriever` against the clip's existing `sourceUrl` (`file://`
for synced copies, `http://gateway:8080/...` for remote -- both go through
the same retriever call), downscales to a max 320px width, and caches the
JPEG to `context.cacheDir` keyed by filename (regenerable, fine for the
system to reclaim). `GalleryScreen`'s `ClipThumbnail` shows the original
`Videocam` icon as a loading/failure placeholder and swaps in the real
frame via a plain `BitmapFactory`-decoded `ImageBitmap` -- deliberately no
new image-loading dependency (no Coil/Glide) for a single-frame-per-row use
case.

**Dashboard was thin, and one real value was being fetched and thrown
away**: `DashcamManifestClient.fetchStatus()` already parsed
`uptime_seconds` into `StatusPayload`, but `DeviceStatus` had no field for
it and `RealDashcamRepository` never read it. Wired it through (confirmed
live via `curl http://127.0.0.1:8080/cgi-bin/status` directly on the
dashcam after the phone left: `{"...,"uptime_seconds":23748}` -- the field
is real and present). Also added road/cabin clip counts and a dual-cam
event count, both derived from the same `videoRepository.observeClips()`
listing `computeClipStats` (renamed from `computePendingSync`) already
walks for the existing new-clip diffing -- one listing, more real numbers
out of it, nothing fabricated. Restructured the Dashboard into two labeled
sections ("Device": storage/GPS/last sync/signal/uptime/SSID; "Footage on
dashcam": road/cabin/dual-cam/total counts) instead of one flat grid --
addresses "one sync button feels weird" by giving the rest of the screen
actual content, not by changing the button itself.

**Settings honesty audit** -- went further than the Gallery buttons, on
purpose (advisor's triage, since a couple of these were the same dishonesty
pattern): `downloadOriginalQuality` (device only ever serves one quality --
nothing to switch) and `autoDeleteAfterDownload` (no delete-capable device
endpoint, and real auto-delete would need the user's own go-ahead as a
destructive action, not just engineering work) were **removed outright**,
not left as switches that did nothing. The "Download location" chooser had
the same problem -- `DownloadLocation.PUBLIC_MOVIES` was selectable but
fell back to the exact same app-private directory (no MediaStore
integration was ever built) -- replaced with a plain, honest info line
instead of a picker with a fake second option. `autoSyncOnWifi` and
`notifyOnNewClips` are real, plausible roadmap items (WorkManager background
trigger; a notification channel/permission), but building either blind
with no phone to verify against (background triggers and notification
permission prompts are exactly the kind of thing that looks fine on paper
and breaks silently on-device) was the wrong call this session -- both are
now disabled switches with honest "Coming soon" subtitles instead of either
lying (on but inert) or being deleted (they're real future work, not
impossible or destructive). `keepScreenOnDuringSync` was the one setting
that was persisted but never actually applied anywhere -- wired for real via
`LocalView.current.keepScreenOn`, toggled in a `DisposableEffect` keyed on
the actual syncing state so it only forces the screen on for the sync's
real duration and always releases on dispose (leaving mid-sync can't strand
the screen on).

**One more dishonesty caught by the same emulator pass**: the Signal
stat card read `wifiState.rssiDbm` unconditionally, so it showed the
phone's *current* WiFi signal regardless of whether that was the dashcam
-- the very first screenshot showed `Signal: -50 dBm (Excellent)` sitting
right next to `Not connected` and `SSID: --`, the exact contradiction this
whole pass was about removing. Fixed by gating on `wifiState.isDashcamNetwork`,
same treatment as Uptime/SSID; re-verified on the emulator showing `--` in
its place.

**Verification, given the phone left mid-session**: `kaptDebugKotlin` and a
full `assembleDebug` both passed clean (Hilt's DI graph included -- this
would fail loudly on a missing binding after all the constructor-signature
changes across `VideoRepository`/`RealVideoRepository`/`MockVideoRepository`/
`GalleryViewModel`/`DashboardViewModel`/`SettingsRepository` and friends).
Beyond pure compile-green: found and booted the project's existing
`KhanLabs_Pixel_API_34` emulator, installed the new build, and screenshotted
Dashboard/Videos/Settings live. Confirmed, actually on a running app: the
new Device/Footage stat sections render correctly and gate `Uptime`/`SSID`
on `isConnected` (show `--` off-network, no crash); the Videos empty-state
split renders the new "No footage synced yet" + connect/SYNC message
correctly (this phone/emulator combination has never synced anything, so
this is the exact real state that used to look broken); the tab color fix
is visibly correct (`All` cyan+bold, `Road`/`Cabin` muted grey); tapping
SYNC off-network correctly shows "Not on a Dash-* network" with no crash,
exercising the new `keepScreenOn` `DisposableEffect` path; the Settings
screen renders the disabled toggles, the real `keepScreenOnDuringSync`
toggle, and the new static "Download location" text all correctly, no
crash. **Not verified live** (needs the phone back on `Dash-0154` with real
synced footage): Remove actually deleting a local file and staying gone
after restart, Share's download-then-share path and the real Android share
sheet, and real (non-icon-fallback) thumbnail rendering for both local and
remote clips. Code-reviewed and logically sound, consistent with this
session's own precedent (A7's `clearDownloadCache` real-delete path was
similarly reviewed-not-exercised for the same reason: no reason to believe
correctness, only reason to want eyes on it).

## Section 16: Bug-hunt + feature-analysis pass (2026-07-13, continued)

User asked for a full analysis (bugs + feature ideas) with advisor
consulted on all of it. Full code-review sweep of screens/repositories not
otherwise touched this session (Map/Player/TripRepository/WifiConnectionObserver),
plus a live empirical test using a synthetic GPX track (same technique as
A3's synthetic clip) since no real GPS data exists yet (F2 unbuilt).

**Found and fixed, both real bugs**:

1. **RSSI-churn refetch bug** (highest severity, previously undetected
   because every hardware test this project ran had the phone stationary
   on a desk). `WifiState` is a data class including `rssiDbm`, which
   changes continuously in a moving vehicle -- `WifiConnectionObserver`'s
   `.distinctUntilChanged()` only dedupes *identical* states, so any signal
   fluctuation produced a "new" state. All three real repositories
   (`RealDashcamRepository`, `RealVideoRepository`, `RealTripRepository`)
   `flatMapLatest` directly on that raw flow, so every RSSI tick cancelled
   and restarted the in-flight remote fetch (device status, clip listing,
   GPX parsing). In the worst case -- a slow hotspot, a large `recordings/`
   listing, frequent signal churn -- the fetch could be starved and never
   complete, leaving Dashboard/Gallery stuck loading. Fixed by inserting
   `.distinctUntilChangedBy { Triple(it.ssid, it.isDashcamNetwork, it.gatewayIp) }`
   before each `flatMapLatest`, so only an actual network-identity change
   (join/leave) triggers a re-fetch -- live RSSI still reaches Settings'
   Connection card and the Dashboard's Signal card via their own direct
   `observeWifiState()` subscriptions, untouched. Compile-verified only
   (this specific failure mode needs the dashcam link + real movement to
   reproduce, which the emulator can't provide).

2. **Map defaulted to "Null Island"**. `TrackMapView`'s `AndroidView` factory
   never called `MapView.setCenter()` -- only the `update` block did, and
   only inside `if (track != null)`. With no GPS track selected (the actual
   current real-world state -- no GPX source exists until F2 is built), the
   map silently rendered OSMDroid's `(0,0)` default at zoom 14.5: a dark,
   open-ocean CartoDB tile that reads as a broken/blank screen, not "no
   trips yet." **Live-verified both states on the emulator**: pushed a
   synthetic 8-point GPX track (`20260713.gpx`, San Francisco coordinates)
   directly into the app's external files dir, confirmed the full pipeline
   -- real tile fetch, correctly centered/zoomed, polyline + start/end
   markers, distance/duration/max-speed stats (0.9 km / 3 m / 16 km/h,
   correct for the planted points), replay slider -- all worked correctly.
   Removed the file and confirmed the new "No GPS trips yet" empty state
   (matching the Gallery's empty-state pattern) renders instead of the
   Null Island tile. Fix: skip rendering `TrackMapView` entirely when
   `tracks.isEmpty()`.

**Noted, not fixed (reported to the user instead)**: the Start marker and
the replay-position marker visually overlap when replay is at index 0 (cosmetic,
green Start dot hidden under the cyan replay dot until the slider moves);
sharing a not-yet-synced clip downloads it into the same directory SyncEngine
uses but the Gallery's "already synced" state won't reflect that until the
next WiFi-identity change (cosmetic staleness, not incorrect); a
theoretical (unobserved) file-write race if a user taps Share on a clip
that SyncEngine is simultaneously downloading; zero automated tests exist
anywhere in the app (flagged as optional -- a few pure-logic unit tests for
`GpxParser`/`VideoFilenameParser`/`computeStats` would be cheap and
high-value, not proposed as urgent for a solo project). Firmware-side
`cgi-bin/status` telemetry enrichment (IMEI, hotspot client count, etc.)
was considered and deliberately not pursued -- given this project's own
reboot-loop history, pushing unverified firmware changes for a "nice to
have" stat is the wrong risk.

**Feature ideas surfaced, not built** (curated for the user, not an
autonomous build list): offline/pre-fetched map tiles (flagged as the
strongest idea -- the Map's one cloud dependency is unreachable exactly
when the phone is on the dashcam's no-internet hotspot with no cellular,
the same failure mode as everything else in this app's zero-cloud design,
just not yet solved); automatic sync-on-WiFi-join (WorkManager);
new-clip notifications; `WifiNetworkSuggestion` auto-join (still blocked
on the A4 credential-storage question); export/trim a clip; a calendar-style
trip picker instead of a flat date dropdown; a mileage/odometer summary
across all trips (cheap once F2 exists, useless before); picture-in-picture
dual-cam view as an alternative to side-by-side. Also raised as a genuine
open question, not a build decision: Remove (this session's B1) is
currently one-way -- no undo, no re-sync-brings-it-back -- worth confirming
with the user that's the intended behavior for "get rid of test footage."

## 17. B7: Offline map tile pre-fetch (`MapScreen.kt`)

User confirmed both open items from Section 16: keep Remove one-way (no
code change -- it's an intentional design decision, not a gap: undoing a
Remove would need to distinguish "user changed their mind" from "SyncEngine
re-downloaded the same file," and the dashcam has no write endpoint to make
a real SD-card delete meaningfully undoable anyway), and build offline map
tiles (the top-recommended feature idea, since the Map's one cloud
dependency -- CartoDB's tile CDN -- is unreachable exactly when the phone
is on the dashcam's no-internet hotspot).

**What it does**: a "Download offline map" button in `TripBottomSheetContent`,
below the replay controls, for the currently-selected track. Tapping it
estimates the tile count for that route (z13-17, chosen to cover the app's
default 14.5 zoom plus room to zoom in without the count exploding on a
long trip), shows a confirm dialog with the estimate (tile count + a
clearly-labeled rough MB estimate) and explicit copy that this uses the
*phone's* internet connection, not the dashcam's (CartoDB's CDN isn't
reachable through the dashcam's hotspot, which has no internet uplink --
this is a different network path than SYNC), then downloads with a
progress bar and a Cancel button, ending in an "Available offline" state.

**Not new caching infrastructure -- a proactive trigger for what already
exists.** `KhanLabsDashcamApp.onCreate()` already calls
`Configuration.getInstance().load(...)`, which turns on osmdroid's default
SQLite-backed tile disk cache; every tile the map has ever rendered was
already persisting to disk. This feature just lets the user pre-fetch a
route's tiles ahead of time via osmdroid's `CacheManager` instead of only
caching whatever happened to be viewed live.

**Design decisions** (resolved via advisor consult rather than surfaced to
the user, since UI-placement/zoom-range/cache-size calls were the exact
category of judgment call this session's standing instruction pre-delegated):
- Zoom range z13-17, not the library's full 0-20 -- tile count grows ~4^zoom,
  so an unbounded range on a real multi-hour highway trip would be tens of
  thousands of tiles. Confirm-dialog copy adds an extra "large area" warning
  above a 3000-tile threshold rather than hard-blocking the download.
- `CacheManager(mapView)` construction is a checked
  `TileSourcePolicyException` in Java -- verified before writing any UI that
  the app's `DarkMatterTileSource` (a plain `XYTileSource` with no explicit
  `TileSourcePolicy`) defaults to `TileSourcePolicy(0, 0)`, i.e. no
  `FLAG_NO_BULK` bit set, so bulk download is allowed and the exception is
  not expected in practice -- still wrapped in try/catch, surfacing a
  `Failed` state with a plain-language message instead of crashing if it
  ever does throw.
- Used `downloadAreaAsyncNoUI` (not the plain `downloadAreaAsync`) after
  discovering the plain variant pops osmdroid's own default
  "Downloading tiles" progress dialog *in addition to* this app's own
  progress UI -- redundant/duplicate, a real bug caught live on the
  emulator during verification (see below), not found in the API research
  phase. `downloadAreaAsyncNoUI` takes the identical signature and callback,
  leaving this app's Compose progress UI as the only one shown.
- `CacheManager.possibleTilesCovered(points, minZoom, maxZoom)` is used to
  get the estimate before committing to a download, run on `Dispatchers.IO`
  since it touches the tile provider/filesystem cache.

**Live-verified end-to-end on the emulator (`KhanLabs_Pixel_API_34`) --
the one part of this whole session's work that's fully verifiable without
the physical dashcam/phone, since the point is "works with no internet
at all":**
1. Reused the synthetic `20260713.gpx` San Francisco track from Section 16's
   testing (still present in the emulator's external files dir).
2. Tapped "Download offline map" -- confirm dialog correctly showed
   "~34 map tiles (about 1 MB)" for this small track.
3. Confirmed download completed cleanly to "Available offline" with no
   stray dialog, after the `downloadAreaAsyncNoUI` fix above.
4. **The real test**: force-stopped the app, ran
   `adb shell svc wifi disable` and `svc data disable`, confirmed via
   `dumpsys connectivity` that `Active default network: none`, relaunched
   the app fully offline, and navigated back to Map -- the CartoDB dark
   tiles, street labels, and the track polyline all rendered correctly from
   osmdroid's on-disk cache with zero network path available. This is the
   actual thing the feature promises, not just "the download finished."
5. **Advisor caught a real gap after the first verification pass**: `OfflineMapControl`
   started/canceled tasks but never canceled one on dispose or track-change --
   `downloadAreaAsyncNoUI` returns a plain `AsyncTask`, not tied to the
   composable's lifecycle or `rememberCoroutineScope()`, so navigating away
   from Map mid-download (or switching the selected trip date) would leave
   it running un-cancelably in the background, hitting CartoDB with no UI
   left to stop it -- the exact runaway the large-area warning copy exists
   to prevent. Fixed with `DisposableEffect(track?.date) { onDispose {
   activeTask?.cancel(true) } }`.
6. **Both cancel paths live-verified**, using a second synthetic GPX
   (`20260712.gpx`, a 6-point ~7km diagonal route -- large enough that its
   ~180-tile estimate took several seconds, unlike the first fixture's 34
   tiles which finished before Cancel could be tested): (a) tapped "Cancel
   download" mid-flight -- UI correctly returned to the idle
   "Download offline map" button rather than continuing or reaching "Available
   offline"; (b) started a fresh download and immediately switched to the
   Dashboard tab mid-flight -- no crash, and `adb logcat` confirmed the real
   mechanism: `OsmDroid: IOException downloading MapTile: ... :
   java.io.InterruptedIOException: thread interrupted`, with no further tile
   fetches logged afterward -- direct proof the background task was actually
   interrupted by navigating away, not just silently continuing off-screen.
   Removed the second synthetic GPX afterward, leaving only the original
   `20260713.gpx` fixture.
7. **Not live-verified**: the `Failed` state (no practical way found to
   force a tile-fetch error on the emulator without also being unable to
   distinguish it from other failure modes); real multi-hour-trip tile
   counts (both fixtures used here were still modest by design, to avoid
   hammering CartoDB's free CDN during testing).
8. Like the rest of the Map screen, this does nothing useful on the user's
   real dashcam data yet -- F2 (a real GPX source from the vehicle) is
   still unbuilt, so this ships ahead of having real trips to pre-fetch,
   same as the empty-state fix from Section 16.

## 18. Full app testing pass on emulator + two honesty fixes (2026-07-15)

User asked for full testing of the companion app, rework where needed, root/skills
used as needed, decisions routed through advisor rather than the user. The physical
test phone (OnePlus 8T) was lock-screen secured with no known PIN -- correctly
treated as a hard stop, not guessed at -- so this pass ran on the
`KhanLabs_Pixel_API_34` emulator instead, using synthetic data planted directly
into the app's private storage (same technique as the earlier GPX fixtures):
four H.264 `.mp4` files matching `VideoFilenameParser`'s exact naming convention
(`{imei}_{camIndex}_{date}_{time}_{epoch}.mp4`), covering both cameras across two
timestamps 15 minutes apart, pushed into `getExternalFilesDir(DIRECTORY_MOVIES)`.

**Confirmed working, no changes needed:**
- Dashboard's Signal-card honesty fix (Section 15) holds on a fresh install:
  correctly showed `On "AndroidWifi" (not a dashcam)` rather than implying a
  real dashcam connection.
- Gallery thumbnails, camera labels, per-camera tab filtering, and dual-cam-event
  pairing/badge all correct, including badge removal on the *paired* clip when
  its counterpart clip is removed (verified live: removing one Road clip
  correctly dropped the "Dual-Cam Event" badge from its paired Cabin clip).
- Remove: shows a real confirm dialog ("Remove from this phone? ... The
  dashcam's own SD card is not affected"), deletes the local file, and the
  removal survives `am force-stop` + relaunch (backed by `RemovedClipDao`).
- Share: opens the real Android share sheet against the actual local file.
- Player: single-view playback initializes real ExoPlayer/MediaCodec instances
  and the on-screen controller (play/pause, scrubber, skip) renders correctly.
  (First playback attempt used an `mpeg4`/mp4v-es-encoded fixture that the
  emulator's decoder rejected at 320x240 -- `MediaCodecInfo: NoSupport
  [sizeAndRate.support, 320x240@10.0]` -- a synthetic-fixture problem, not an
  app bug; regenerating with `libx264` at 1280x720 fixed it.) Note: `adb
  screencap` renders the ExoPlayer `SurfaceView` region as solid black even
  during confirmed-live playback -- a known screencap/SurfaceView compositing
  limitation on this emulator config, not a rendering bug; confirmed by
  tapping to reveal the controller overlay, which *does* screencap correctly
  since it's a normal Compose-drawn View on top.
- Dual-view player (the untested marquee path flagged by advisor review):
  switching a dual-cam event to "Switch to Dual-View" spins up two independent
  `ExoPlayer` instances (4 `MediaCodec` tracks total, video+audio x2), and
  switching back to single view released both cleanly (`ExoPlayerImpl: Release
  ...` logged for each) with no leaked instances or exceptions.
- SYNC button off the dashcam's network: fails gracefully with an inline
  message ("Not on a Dash-* network -- join the dashcam's hotspot first"),
  no hang or crash.
- Map screen's offline-tile feature (Section 17) re-verified clean on a fresh
  install: confirm dialog, download, "Available offline" end state.
- Settings screen's "Forget this dashcam" button is already correctly
  disabled/greyed when `pairedDashcamSsid == null` -- flagged as a possible
  gap from a screenshot, turned out to be already-correct on reading the code.

**Two real findings, both fixed** (same feature-honesty pattern as Sections
15/16 -- an inaccurate-looking number is worse than an explicit "unknown"):
1. **`GalleryScreen.kt`'s `formatDuration`**: a clip with no same-camera
   neighbor to estimate a gap from (see `RealVideoRepository.
   withEstimatedDurations`) falls through to `0L` and rendered as `"0s"` --
   reads as a broken/zero-length recording rather than "duration unknown".
   Reproduced live: removing one Road clip left its sibling Road clip
   isolated, and its duration display changed from `15 min` to `0s` on the
   next relaunch. Fixed: `seconds <= 0` now renders `"--"`, matching the
   `"--"` convention already used for unknown Signal/SSID/Uptime values on
   the Dashboard.
2. **`SettingsScreen.kt`'s `formatBytes`**: `"%.0f MB".format(mb)` rounds
   any cache under 0.5 MB down to `"0 MB"`, while the Clear button next to
   it stays enabled (`cachedDownloadsBytes > 0`) -- looked like "nothing to
   clear" next to a live, clickable Clear action. Reproduced live: the
   146 KB of remaining synthetic clips showed as `"0 MB"`. Fixed: sub-1 MB
   caches now render in KB (`"146 KB"`), with `"<1 KB"` as the floor for a
   nonzero-but-sub-KB cache.

Both fixes rebuilt, reinstalled, and re-verified live on the emulator.

**Not covered this pass**: real hardware sync/browse over the dashcam's actual
hotspot (blocked on the test phone's lock screen -- deferred until the user is
back to unlock it; the dashcam-hotspot connectivity fix itself -- client Wi-Fi
and Mobile Hotspot cannot both be enabled on this hardware, the SoftAP HAL setup
silently fails when they are -- is documented in the firmware-fork project docs,
not here, since it's dashcam-side, not app-side).

## 19. Fix blank Dashboard/Gallery/Map on open + Gallery thumbnail duration badge (2026-07-15)

User reported the Dashboard shows nothing but the header for a long time after
opening the app while connected to the dashcam. Precisely timed on real
hardware (force-stop, launch, poll `uiautomator dump` at increasing intervals):
no content rendered between t=21.5s and somewhere before t=36s.

**Root cause**: `RealDashcamRepository.observeDeviceStatus()`,
`RealVideoRepository.observeClips()`, and `RealTripRepository.observeTracks()`
all built their flow as `flow { emit(fetchX(gateway)) }` -- nothing is emitted
until the full remote call completes. `DashboardScreen`'s entire body below the
header is gated on `status?.let { ... }`, so a null `status` (its initial
`StateFlow` value) renders literally nothing. Directly timed the dashcam's own
CGI endpoint via root shell: `su -c "time busybox wget -q -O- http://127.0.0.1:8080/cgi-bin/browse/recordings/2009-01-01"`
took **54.01s** for a ~600-entry folder (68000-byte JSON response) vs 1.69s for
a small `logs` folder -- strongly implicating the dashcam's own CGI script
(likely a per-file `stat()` shell loop) rather than the app or the network.

**Fix**:
- `RealVideoRepository.observeClips()` / `RealTripRepository.observeTracks()`:
  when on the dashcam's network, the flow now emits `localClips()`/`localTracks()`
  (a fast disk read of already-synced footage) immediately, then replaces it
  with the authoritative remote listing once `fetchClips`/`fetchTracks`
  resolves. Worst case (remote listing also falls back to local) is one
  redundant, cheap local read -- not a bug.
- `DeviceStatus` gained `isStatsLoading: Boolean`. `RealDashcamRepository`
  now emits an immediate placeholder with `isStatsLoading = true` rather than
  confident zeros -- "0 New Clips" while still loading would misreport a
  dashcam full of unsynced footage as having nothing to sync. `fetchStatus()`
  (fast: a small single-object endpoint, not the directory walk) now emits as
  soon as it resolves instead of being held hostage behind the slow clip walk
  too, so Storage/GPS/Uptime fill in within ~1s instead of sitting on
  `"0.0 / 0 GB"` / `"0m"` for the same 20-57s. `DashboardScreen`'s Hero section
  shows "Checking dashcam..." instead of the clip-count line while loading;
  `FootageStatsGrid`'s Road/Cabin/Dual-Cam/Total cards show `"--"` (this
  screen's existing not-yet-known convention) instead of `"0 clips"`.

**Regression caught before shipping, not after**: the local-first change to
`observeClips()` broke two other callers that read it via
`videoRepository.observeClips().first()` expecting the *authoritative* remote
listing -- `SyncEngine.syncNow()` (would have silently synced nothing, since
the "new clips" filter would run against local-only data that's already
"synced" by definition -- worse than the original bug) and
`RealDashcamRepository.computeClipStats()` (new-clip count would always read
0). Caught this by testing the fix's own timing: real stats arrived
suspiciously fast (~1s) showing "0 New Clips" against a dashcam later
confirmed to have 602 real clips vs 6 locally synced. Fixed by adding
`VideoRepository.fetchRemoteClips(gatewayIp): List<VideoClip>` as an explicit
non-Flow authoritative-listing call (delegates to the same private
`fetchClips` `RealVideoRepository` already had); `computeClipStats` and
`SyncEngine.syncNow` both switched to call this directly instead of
`.first()`-ing the flow.

**Verified live on real hardware** (phone connected to `Dash-0154`, 602 real
clips on the dashcam's SD card):
- Timed repro after the fix: `t=1s` shows `"Checking dashcam..."` (not black),
  Storage shows the real `"11.1 / 119 GB"` already; `t=57s` shows the real,
  *accurate* `"520 New Clips • 0 New Track • 8.9 GB Ready"` (the ~57s cost is
  unchanged -- it's the CGI script, not this fix's job to solve, see below).
- Gallery tab, opened seconds after a fresh launch while the same slow walk
  was still in flight: showed real already-synced clips immediately, not
  blank.
- SYNC tapped and watched live end to end: real progress UI showed
  `"59 / 606"`, `"6.8 MB/s"`, `"1.5 GB / 11.1 GB"` -- confirms the
  `fetchRemoteClips` fix didn't turn SYNC into a silent no-op.

**Deliberately not fixed this pass** (advisor consult): the dashcam-side CGI
script's actual slowness (the 54s walk). Different repo, different risk
profile, and the app-side fix already resolves the user-visible complaint
("stays black") regardless of the CGI's root cause. Flagged as a follow-up
item in `BACKLOG_2026-07-13.md` (B12/B13's "New item found during B12/B13").
One known consequence to flag if it comes up again: `DashboardViewModel`'s
`deviceStatus` uses `WhileSubscribed(5_000)`, so leaving the Dashboard screen
for more than 5 seconds and returning re-runs the full walk -- now shows the
honest "Checking dashcam..." state instead of a black screen, which is
strictly better, but a user could still notice it re-checking on every
return. The real fix for that is caching the listing, same underlying item as
the CGI slowness -- not built this pass.

**Also shipped in the same pass** (user follow-up request): Gallery
thumbnails (`GalleryScreen.kt`'s `ClipThumbnail`) now show a small dark pill
with the clip's duration in the bottom-right corner of the thumbnail image
itself, using the same `formatDuration()` already used in the row's text
line. Omitted when duration is unknown (0, the estimator's no-neighboring-clip
fallback) rather than overlaying `"--"` on the image. Verified via a real
screenshot on the phone (screencap rendered real content this time, unlike
earlier sessions' `OplusViewMirrorManager` issue) -- badges correctly showed
`"15s"`, `"9s"`, `"1 min"` matching each clip's actual duration.

## 20. Speaker volume control + debug "keep dashcam awake" toggle (2026-07-27)

**Speaker volume (`VolumeScreen.kt`/`VolumeViewModel.kt`).** New Dashboard
entry card alongside Live View/Cellular/Performance. Talks to the dashcam's
new `cgi-bin/volume` endpoint (firmware repo): GET returns the current mixer
value + valid range, POST sets it and applies immediately server-side (the
dashcam pokes `tinymix` directly on a successful write rather than waiting
for its 2s enforcement cycle), so this screen's optimistic-update pattern
skips `LteViewModel`'s reconcile-delay step entirely -- there's no real lag
between "wrote" and "device applied it" worth hiding behind a delay.

Deliberately labelled "GAIN LEVEL", not "dB": checked the dashcam's actual
mixer control (`RX3 Digital Volume`) and it exposes only a raw `dsrange
0->124`, no TLV dB scale -- there is no calibrated acoustic decibel value
available from this hardware at all. Showing "dB" would have been a
fabricated unit, same honesty rule this app already applies elsewhere
(`cgi-bin/status`/`lte-status` return null instead of a fake number rather
than making one up).

**"Keep dashcam awake (debugging)" toggle (`SettingsScreen.kt`/
`SettingsViewModel.kt`, in the existing "Manage Dashcam" card).** Ships
alongside a real, still-open dashcam bug found this session: the dashcam
sleeps (Android Doze) after just 15 seconds of screen idle, and Doze entry
tears down its own WiFi hotspot *and* pauses video/audio recording as a side
effect -- confirmed live via `dumpsys battery` (AC-powered stayed `true`
through every drop, ruling out a power-loss cause) and repeated logcat
captures of the exact `Disable a wifi Hotspot` -> `Going to sleep due to
screen timeout` -> `Dozing...` sequence. Real behavior for a parked car, a
genuine nuisance while bench-testing where nothing touches the unit for
minutes at a stretch.

This toggle talks to the firmware's new `cgi-bin/keepawake` endpoint, which
applies two stock Android Settings-provider values on the device
(`stay_on_while_plugged_in=7`, `screen_off_timeout=1800000ms`) -- chosen
over a smali/app fix specifically because Settings-provider values persist
on their own across reboots and path-reopens, unlike the ALSA mixer
registers the volume feature uses (those need `khanlabs_audio_boost.sh`'s
2-second re-poke loop because `audioserver` caches `mixer_paths.xml` at
startup and reapplies its cached value whenever a path reopens -- see that
script's own doc comment). Off by default; gated on `wifiState.isDashcamNetwork`
same as every other device-control screen; optimistic update with
revert-on-failure matching `VolumeViewModel`'s pattern.

**Scope note:** the user initially asked for this toggle in the dashcam's
own on-device Settings screen (it has one -- a small integrated display,
`SettingsActivity`/`settings_menu.xml` in the firmware repo, not just this
phone app). Investigated that path and stopped short of building it:
`dumpsys package com.surfsolutions.surfdash2` confirmed the on-device app
holds neither `WRITE_SETTINGS` nor `WRITE_SECURE_SETTINGS`, and shelling out
from inside the app doesn't route around that (a spawned child process still
runs at the app's own restricted UID, not a privileged one -- confirmed this
before writing any smali on the wrong assumption). The only working path
would be the on-device app making a local HTTP call to its own already-
privileged `cgi-bin/keepawake`, which means hand-authoring new networking
smali inside `SettingsActivity` -- a shared Activity used by every other
on-device setting (alarms, driver position, PIN, factory reset, power off),
with no way to see the dashcam's screen to verify a mistake didn't break it.
Flagged the risk/effort tradeoff to the user directly rather than silently
either doing it or skipping it; they confirmed the phone-app toggle (this
section) is sufficient for now. Not built; would need to be revisited
deliberately if on-device access becomes a real requirement later.

**Verified live on the second unit (both features):** volume GET/POST round-trip
and immediate audible effect; keepawake GET/POST round-trip, enabling held
the device `Awake` with hotspot up through a 60-second idle window (4x the
stock 15s timeout), disabling reverted both settings to exact stock values.
Companion-app build verified (`assembleDebug`, BUILD SUCCESSFUL); the
keep-awake toggle's on-screen rendering specifically wasn't smoke-tested on
the phone itself, since the test phone dropped its USB connection mid-session
and didn't reconnect before this shipped.
