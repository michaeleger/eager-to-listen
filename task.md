# Implementation Tasks: Eager to Listen Podcast Player

- [x] **Phase 1 to 10: Initial Web/Capacitor Prototype & Rebranding**
  - [x] Core queue priority algorithms & tests (23/23 passing)
  - [x] WebVTT/SRT transcript parser & iTunes search
  - [x] Google Drive AppData Sync engine (3-way merge)
  - [x] App rebranded to **Eager to Listen** with Forest Green & Burnt Orange theme
  - [x] One-handed UI optimizations (bottom toasts, non-blocking subscribe, bottom confirmation sheets)
  - [x] Capacitor Android debug APK verified

- [x] **Phase 11: Native Android Dependencies & Gradle Setup**
  - [x] Add Kotlin Android plugin, Compose compiler, and KSP to `build.gradle`
  - [x] Add AndroidX Media3 (`media3-exoplayer`, `media3-session`, `media3-ui`)
  - [x] Add Room SQLite DB (`androidx.room:room-runtime`, `androidx.room:room-ktx`, `androidx.room:room-compiler`)
  - [x] Add Jetpack Compose BOM, Material3, Navigation Compose, and Coil Compose
  - [x] Add OkHttp and Coroutines dependencies
  - [x] Verify clean Gradle build synchronization

- [x] **Phase 12: Room Database & DoggCatcher Data Models**
  - [x] Create `Podcast` entity with per-feed settings (Skip Intro, Skip Outro, Speed, Priority 1–5, Auto-Add, Max Episodes)
  - [x] Create `Episode` entity with playback positions, completion status, and Podcasting 2.0 metadata
  - [x] Create `QueueItem` entity with priority rank and custom display order
  - [x] Implement `PodcastDao`, `EpisodeDao`, and `QueueDao` with reactive Flow queries
  - [x] Set up `EagerDatabase` singleton with SQLite builders and converters

- [x] **Phase 13: Podcasting 2.0 Feed & Transcript Parser**
  - [x] Native XML Pull Parser for RSS 2.0 feeds and Podcasting 2.0 tags (`<podcast:transcript>`, `<podcast:chapters>`, `<podcast:person>`, `<podcast:soundbite>`, `<podcast:funding>`)
  - [x] `TranscriptParser.kt` for WebVTT, SRT, and JSON transcripts converting cues into timestamped data models
  - [x] `ChapterParser.kt` for remote JSON chapters format with artwork and titles

- [x] **Phase 14: AndroidX Media3 Playback Service**
  - [x] Implement `PlaybackService` extending `MediaSessionService`
  - [x] Configure ExoPlayer with **15s rewind** and **90s forward skip**
  - [x] Implement per-feed Skip Intro (auto-seek on start) and Skip Outro (early advance)
  - [x] Setup Audio Focus, becoming noisy (headphone disconnect pause), and Bluetooth/lock screen controls
  - [x] Expose reactive StateFlow via `PlaybackStateManager` and control helper `PlaybackController`

- [x] **Phase 15: Jetpack Compose UI (Forest Green & Burnt Orange)**
  - [x] Build Material3 Theme with Eager to Listen palette (`#0C1611`, `#1C2E25`, `#EB5E17`)
  - [x] One-handed Bottom Navigation Bar (Queue, Subscriptions, Discover, Settings)
  - [x] `QueueScreen`: DoggCatcher priority sorting, Hot List (Priority 1) filter, bulk actions
  - [x] `SubscriptionsScreen`: Feed listing with priority badges and quick refresh
  - [x] `FeedCustomizerSheet`: Per-feed DoggCatcher settings (Priority 1–5, skip intro/outro, speed, auto-add, max retention)
  - [x] `DiscoverScreen`: iTunes podcast search, direct RSS feed input, curated feeds, duplicate prevention
  - [x] `MiniPlayer`: Docked bottom player with 15s rewind, 90s forward skip, play/pause
  - [x] `PlayerSheet`: Full Now Playing screen with scrubber, speed picker, Podcasting 2.0 chapters drawer, and synced transcript viewer

- [x] **Phase 16: Build Verification & Testing**
  - [x] Compile debug APK via `./gradlew assembleDebug`
  - [x] Successfully verified `app-debug.apk` (23.2 MB) builds cleanly with 0 errors

- [x] **Phase 17: Pixel Edge-to-Edge, 4 Play Orders, Diversity Limit, & Play Next**
  - [x] Enable edge-to-edge in `MainActivity.kt` and eliminate double bottom padding across all screens for full Pixel screen filling
  - [x] Add Pending Playlist view in `QueueScreen.kt` with order indices, duration, and strategy badges
  - [x] Add "Up Next" pending queue tab in `PlayerSheet.kt` for in-playback queue viewing
  - [x] Implement the 4 Play Order Strategies (Chronological, Reverse Chronological, News with X-day window, Evergreen)
  - [x] Add smart strategy inference on feed import (News -> News; Serial -> Chronological; default -> Evergreen)
  - [x] Implement Fair Interleaving algorithm with Feed Play Limit above importance (anti-binge diversity)
  - [x] Implement Single-Episode "Play Next" priority insertion at top of playlist with seamless return to programmed order
  - [x] Room DB Version 2 migration with `MIGRATION_1_2`
  - [x] Verify clean build via `./gradlew assembleDebug` (0 errors)
- [x] **Phase 18: Handsfree (Voice) & Android Auto MediaLibraryService**
  - [x] Declare automotive media metadata in `AndroidManifest.xml` and create `automotive_app_desc.xml`
  - [x] Upgrade `PlaybackService` to `MediaLibraryService` with `MediaLibrarySession`
  - [x] Implement Android Auto Media Browser tree with 4 structured categories:
    - **Pending Playlist**: Programmed queue with exact order numbers (`#1 NEXT`, `#2`, etc.)
    - **Hot List**: Unplayed episodes from Priority 1 podcasts
    - **Daily News**: Unplayed news episodes within retention window
    - **Podcasts**: All subscribed feeds with clickable episode drill-down
  - [x] Seamless Playback Resumption: Auto-resumes paused episode or starts top of pending playlist on car connection
  - [x] Google Assistant Voice Support (`onSearch`, `onGetSearchResult`, `onSetMediaItems`, `onAddMediaItems`): Matches spoken podcast and episode queries
  - [x] Automatic sync of per-feed playback speed, skip intro (skips first X seconds), and skip outro across all transitions
  - [x] Hardware / steering wheel controls: 15s rewind & 90s forward skip preserved
  - [x] Add synchronous and search query methods to `EpisodeDao` and `PodcastDao`
  - [x] Full build verification with `./gradlew assembleDebug` (0 errors, BUILD SUCCESSFUL)
- [x] **Phase 19: Podcast Addict DB / Backup Importer & Device Testing**
  - [x] Built `PodcastAddictImporter.kt` supporting `.backup` (zip), SQLite `.db`, and `.opml` files
  - [x] Extracts feeds, artwork, per-feed speeds, skip intro/outro offsets, and played/unplayed episode histories
  - [x] Integrated system document file picker into `SettingsScreen.kt` with live progress indicator
  - [x] Installed latest build directly onto connected Google Pixel 10 (`59021FDCR004FC`) via ADB
  - [x] Verified running process on Pixel 10 ready for drive-home test with Android Auto
- [x] **Phase 20: Native Google Drive Cloud Sync (Zero API Keys)**
  - [x] Removed manual OAuth Client ID configuration screens and Google Cloud dependencies
  - [x] Re-architected `SyncManager.kt` to use Android Storage Access Framework (SAF) with `DocumentFile`
  - [x] Implemented bidirectional state merge & export (`eagertolisten_sync.json`) for subscriptions, playback progress, and queue
  - [x] Acquired permanent persistable URI permissions so connection persists across reboots and updates
  - [x] Redesigned `GoogleDriveSyncSheet.kt` and `SettingsScreen.kt` with one-tap Drive folder picker, auto-sync toggle, and instant sync
  - [x] Verified build and installed updated APK to Google Pixel 10 via ADB

- [x] **Phase 21: Feed Refresh & SQLite Foreign Key Resolution**
  - [x] Fixed fatal `SQLiteConstraintException: FOREIGN KEY constraint failed`: `FeedParser` and `PodcastRepository` now strictly ensure all parsed episodes are mapped to `podcast.id`.
  - [x] Added `getPodcastByFeedUrlSync` to `PodcastDao` for robust ID/URL bidirectional resolution.
  - [x] Automatic feed metadata updates: artwork, description, author, and title are refreshed on feed sync.
  - [x] Added one-tap "Refresh All Feeds" button with spinning progress indicator to `SubscriptionsScreen.kt`.
  - [x] Concurrently parallelized feed fetching via coroutines with single end-of-batch queue regeneration.
  - [x] Added silent auto-refresh on app launch in `MainActivity.kt`.
  - [x] Verified clean Gradle build (`assembleDebug`, 0 errors).

- [x] **Phase 22: Feed Settings Auto-Save & Podcasting 2.0 Live Streams with 90-Min Playlist Reversion**
  - [x] Removed manual "Save Feed Rules" button from `FeedCustomizerSheet.kt`.
  - [x] Wired instant auto-save across priority, skip intro/outro, playback speed, auto-add, play order strategy, news retention window, and diversity limit.
  - [x] Added Room Database Version 3 migration (`MIGRATION_2_3`) adding `isLive` and `liveStatus` to `Episode`.
  - [x] Enhanced `FeedParser.kt` to natively parse Podcasting 2.0 `<podcast:liveItem>` tags with `status="live|pending|ended"` and stream URLs.
  - [x] Created `LiveSessionManager.kt` to track active live listening sessions and heartbeats.
  - [x] Implemented 90-minute inactivity auto-reversion: when restarting the app or resuming after not listening for $\ge 90$ minutes while tuned into a live stream, playback automatically reverts to the top of the main programmed playlist.
  - [x] Added UI indicators: glowing `● LIVE` badge in `EpisodeItem`, `MiniPlayer`, and `● LIVE STREAM` indicator with live timer in `PlayerSheet`.
  - [x] Verified complete Gradle build and debug APK packaging (`assembleDebug`, BUILD SUCCESSFUL).

- [x] **Phase 23: 180s ExoPlayer Buffer, Android Auto Controls, 12h Updates & Backup, 4h Auto-Download, JIT News, Creator Studio & Discovery**
  - [x] **180s Deep Audio Buffer & Reconnect Guard**: Configured ExoPlayer `DefaultLoadControl` (30s min, 180s max, 180s back-buffer for 2x skips without network reload); guarded `handleEpisodeEnded` so network hiccups do not prematurely mark completed or advance.
  - [x] **Android Auto Controls**: Added Fast Forward (+90s), Skip Episode button, and Save Inspiration timestamp bookmark button to Android Auto interface and in-app PlayerSheet.
  - [x] **PodcastIndex.org & Category Discovery**: Built `PodcastIndexClient.kt` with SHA-1 authorization headers; category discovery chips on DiscoverScreen; 5 hardcoded promoted shows (Eager to be Healthy, No Agenda, Podcasting 2.0, Huberman Lab, Peter Attia Drive).
  - [x] **Scheduled Updates & Automated Backup**: Added `ScheduledUpdateWorker.kt` (user-adjustable, default 12h) to sync feeds, purge stale news, rebuild queue, and dump local DB snapshot JSON to `filesDir/backups/eagertolisten_backup_latest.json` with Google Drive sync.
  - [x] **Auto-Download Queue Ahead**: Built `PodcastDownloadManager.kt` to cache upcoming queue episodes up to target threshold (default 4h; can go over, never under); strictly excludes feeds with `playOrderStrategy == "NEWS"`.
  - [x] **Just-In-Time News Refresh**: `PlaybackService.kt` detects upcoming news podcasts in the queue and refreshes the feed immediately before starting playback.
  - [x] **Creator Inspiration Studio**: Room database Migration 4 -> 5 (`InspirationClip.kt`); bookmark audio timestamps from Android Auto or player; view timestamps, draft blog notes, copy Markdown quotes, and export Best-Of OPML feeds.
  - [x] Verified build and deployed to Pixel 10 (`59021FDCR004FC`).

- [x] **Phase 24: Memory Hardening, Android Auto Fixes & Completed Track Playlist Workflow**
  - [x] **Android Auto Fast-Forward vs. Skip/Done Fix**: Fixed `PlaybackService.kt` so `skipToNextEpisode(markCompleted = false)` preserves actual progress and does not falsely mark skipped episodes completed. Handled `KEYCODE_MEDIA_FAST_FORWARD` directly in `onMediaButtonEvent` to invoke `player.seekForward()` (+90s).
  - [x] **Completed Tracks Playlist Re-Queueing Fix**: Removed `WHERE e.isCompleted = 0` from `QueueDao.kt` active queue queries; updated `enqueueEpisode` and `enqueueEpisodeTop` in `PodcastRepository.kt` to reset `isCompleted = false` and `positionSec = 0`.
  - [x] **Subscriptions Queue Actions**: Added "Play Next" (`PlaylistAdd`) action button and "✓ Played" indicator to episode rows in `SubscriptionsScreen.kt`.
  - [x] **Played / Completed Filter Tab**: Added "Played" filter chip to `QueueScreen.kt` with a smooth horizontal scroll row, enabling one-tap re-enqueuing of completed tracks.
  - [x] **500ms Database Polling Churn Fix**: Moved `getPodcastById` inside the episode-change guard in `PlayerViewModel.kt`, eliminating 120 redundant SQLite queries per minute during playback.
  - [x] **Shared Singleton OkHttpClient**: Consolidated OkHttpClient into `PodcastRepository.Companion.getOkHttpClient()` to prevent thread pool and socket leaks.
  - [x] **Lifecycle-Bound Coroutines**: Replaced un-scoped `CoroutineScope(Dispatchers.IO)` in `MainActivity.onResume()` with `lifecycleScope.launch`.
  - [x] **Coil Bitmap Memory Optimization**: Downsampled bitmap decoding via `ImageRequest.Builder` with bounded dimensions (168×168 for episode thumbnails, 180×180 for subscriptions, 720×720 for now-playing artwork) to eliminate OOM spikes on multi-megabyte feed artwork.
  - [x] **Samsung & Motorola Testing Profiles**: Configured native Android Virtual Devices (`Samsung_Galaxy_S24` at 1080×2340 / 420 dpi, `Motorola_Moto_G` at 1080×2400 / 400 dpi) verified with `emulator -list-avds`.
  - [x] **Build & Test Verification**: Successfully passed `./gradlew assembleDebug` (0 errors) and `./gradlew testDebugUnitTest` (26 tasks passing).

- [ ] **Phase 25: Pre-Release Production Readiness & PodcastIndex Static DB Architecture (Before Going Live)**
  - [ ] **PodcastIndex Static DB Ingestion Script**:
    - Build automated ingestion script (Python/Node.js) to pull weekly static SQLite database dumps (`podcastindex_feeds.db.tgz`) from `podcastindex.org/datasets` over IPFS (or HTTP).
    - Parse and index active feeds using SQLite FTS5 / Meilisearch.
    - Host lightweight, read-only search/category mirror for client apps, fully shielding PodcastIndex donation-funded servers from bulk traffic as encouraged by ToS Section 2 & 3.
  - [ ] **Curated Seed SQLite Asset for Mobile Client (~2–3 MB)**:
    - Pre-package top 5,000–10,000 active podcasts across 10 categories into `android/app/src/main/assets/` for zero-bandwidth, instant offline category discovery out of the box.
  - [ ] **Client Differential Updates & Bandwidth Constraints**:
    - Implement HTTP `If-Modified-Since` / `ETag` conditional checks before catalog downloads.
    - Enforce `WorkManager` Wi-Fi unmetered (`NetworkType.UNMETERED`) and charging constraints to protect user mobile data.



