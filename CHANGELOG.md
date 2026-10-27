# Changelog — Eager to Listen

All notable changes to the **Eager to Listen** podcast player are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [0.9.1-alpha] - 2026-10-27

### 🔄 In-Car Track Navigation & Audio Interruption Recovery
- **Dedicated Track Navigation**: Re-mapped on-screen and hardware/steering-wheel Next Track (`>|`) to queue advance and Previous Track (`|<`) to episode restart (>5s) or history rewind (≤5s).
- **Audio Interruption & Device Recovery**: ExoPlayer error handler retries transient audio-device errors and route changes without dismissing unplayable episodes.
- **Queue History Navigation**: In-memory playback history queue enables reverse navigation across previous episodes.
- **Nostr Wallet Connect Security**: Switched NWC credentials to Android Keystore EncryptedSharedPreferences.
- **Evergreen Queue Strategy Alignment**: Programmed queue now orders Evergreen feeds newest-first followed by next newest.

---

## [0.9.0-alpha] - 2026-10-24

### 🚀 Google Play Closed Testing Milestone Release

#### 🔒 Data Integrity & Schema Migration (Phases 0 & 1)
- **Room SQLite Schema v6**: Implemented `MIGRATION_5_6` with Room schema JSON export and automated migration verification. Removed destructive fallback migration.
- **Feed URL Normalization**: Standardized feed URLs using `FeedUrl.normalize()` to strip tracking parameters and enforce lowercase schemes and hostnames.
- **Composite Episode IDs**: Implemented `EpisodeId.of(podcastId, guid, enclosureUrl)` with SHA-256 fallback to prevent collision between podcasts or duplicate episodes.
- **Atomic Operations**: Migrated insert queries to Room `@Upsert` to guarantee idempotent writes.

#### 🚗 In-Car Playback & Media Controls (Phase 2)
- **Centralized Playback Configuration**: Extracted `PlaybackConfig` defining dedicated +90s forward jump and -15s rewind seek intervals.
- **Dedicated Track Navigation**: Added dedicated on-screen Next Episode advance button alongside seek controls. Media button routing cleanly separates playlist advancement from seeking.
- **Per-Show Playback Speeds**: Audio playback speed is now persisted per-podcast and seamlessly restored on episode change.
- **Live Inactivity Timeout**: Automated 90-minute live broadcast stream inactivity timeout cleanly advances playback to the next programmed playlist episode.

#### 📻 Pure Queue Engine & Invariants (Phase 3)
- **Pure Domain Engine**: Extracted `QueueEngine.kt` decoupled from Android framework dependencies, fully verified with 28 automated unit tests.
- **Core Invariants Strictly Maintained**:
  - **Invariant I1**: Never replay heard content. High-water mark tracking guarantees older episodes are never erroneously re-enqueued.
  - **Invariant I2**: Played state is durable. Completed status is never cleared except by explicit user action.
  - **Invariant I3**: User settings win. Local feed customizations are never overridden by remote sync.
  - **Invariant I4**: Pinned manual items survive automatic queue recalculations.
  - **Invariant I5**: Room database queue is the single source of truth.
- **Radio U Rotation**: Implemented 4 play order strategies (`NEWS`, `CHRONOLOGICAL`, `REVERSE_CHRONOLOGICAL`, `EVERGREEN`), anti-binge consecutive caps, and round-robin fair interleaving.

#### 🔄 Format 2 Sync & Backup (Phase 4)
- **Format 2 JSON Serializer**: Supports comprehensive backup and two-device synchronization via `SyncSerializer.kt`.
- **Atomic Write Safety**: Backups write to `.tmp.json`, validate JSON integrity, backup previous file to `.bak.json`, and perform atomic rename.
- **LWW Rule Merging**: Feed rule merging using timestamped `settingsUpdatedAt` ensures user rule changes synchronize reliably across devices.
- **Tombstone Soft-Unsubscribes**: Soft-unsubscribing preserves episode played history and local rules while syncing removal across devices.
- **SAF Backup & Restore UI**: Added Export Backup, Restore Backup, and History Purge actions in Settings.

#### 💾 Background Offline Queue Ahead & WorkManager (Phase 5)
- **DownloadQueueWorker**: WorkManager `CoroutineWorker` running with `FOREGROUND_SERVICE_DATA_SYNC` progress notifications.
- **Partial Download Resume**: Implemented HTTP `Range` request resuming using `.part` temporary files.
- **Storage Protection & Cellular Toggle**: Checks device storage and respects the "Allow downloads over cellular" toggle.
- **Weekly Orphan Sweeper**: Automated cleanup removes stale or unreferenced downloaded media files.

#### 📤 OPML Subscription Export (Phase 6)
- **OPML 2.0 Exporter**: Added standard OPML 2.0 subscription export via Android Storage Access Framework (`CreateDocument`).
- **XML Sanitization**: Escapes XML entities in titles, feed URLs, and categories to guarantee compliance across all podcast players.

#### ⚡ Value-for-Value & Nostr Wallet Connect (Phase 7)
- **Feature-Flagged NWC**: Integrated Nostr Wallet Connect (NIP-47) behind `FeatureFlags.V4V_NWC` (active in debug builds, disabled in release builds).
- **Proportional Split Math**: Deterministic satoshi split calculations across creators, contributors, and hosting fees (`V4VSplitMath`). Remainder satoshis are assigned to the primary creator.
- **Podcasting 2.0 / bLIP-10 Boostagrams**: Generates TLV record `7629169` boostagram metadata (feed, episode, timestamp, speed, action, message, sender).
- **Hardware KeyStore Secret Storage**: Persists NWC connection secrets using hardware-backed AES-256-GCM encryption in Android KeyStore.
- **Architectural Decision Record**: Documented in `docs/adr/0001-nwc.md`.

#### 🛡️ Play Store Readiness & Security Hardening (Phases 8 & 9)
- **Security & Extraction Rules**: Added `network_security_config.xml` (cleartext permission for legacy podcast audio enclosures) and `data_extraction_rules.xml` (excludes downloads and secrets from cloud backups).
- **Transcript Parser Precedence Fix**: Corrected arithmetic precedence in timestamp parsing (`((val * 1000).toLong())`).
- **Release Verification**: Verified clean R8 / Proguard compilation and lint vital analysis for `assembleRelease`.
- **Web Proxy SSRF Hardening**: Enforced private/loopback/link-local IP filtering and 15MB response limits on Node.js proxy.
- **Web Client XSS Defense**: Sanitized shownotes and escaped HTML across all DOM injection points.

---

## [0.5.1-alpha] - 2026-10-23

### 🚗 In-Car & Steering Wheel Controls
- **Proper Separation of Track Navigation & Scrubbing**:
  - **Next Track** (`>>|` / `KEYCODE_MEDIA_NEXT`, `seekToNextMediaItem`): Properly advances to the next podcast episode in your playlist queue (`skipToNextEpisode()`).
  - **Previous Track** (`|<<` / `KEYCODE_MEDIA_PREVIOUS`, `seekToPreviousMediaItem`): Rewinds to the beginning of the current episode (if $> 5$ seconds into playback) or advances back to the previous episode (`skipToPreviousEpisode()`).
  - **Fast Forward** (`KEYCODE_MEDIA_FAST_FORWARD`): Seeks forward by +90 seconds.
  - **Rewind** (`KEYCODE_MEDIA_REWIND`): Seeks backward by -15 seconds.
  - **Restored Standard Playlist Item Selection**: Fixed `ForwardingPlayer` so clicking tracks on the head-unit or phone UI cleanly selects and starts the tapped track without being intercepted as a seek.

### 📋 Android Auto Pending Queue Display
- **3–5 Upcoming Tracks Visible on Head Unit**:
  - Updated `PlaybackService.kt` with dynamic timeline packaging (`buildPlaylistForCurrentEpisode`). ExoPlayer's playlist is loaded with the currently playing episode followed by up to 5 upcoming unplayed queue items.
  - Drivers can now view, browse, and tap to select from the next 3 to 5 scheduled shows directly on the Android Auto head-unit queue panel instead of seeing only a single track.

### 💾 Offline Download Indicators & Quick Actions
- **Visual Status Across the App & Car**:
  - **Android Auto**: Downloaded episodes display a `💾 ` icon in the title and `✓ Downloaded • ` prefix in the artist/podcast line.
  - **Queue Screen**: Visual `Downloaded` badge in the top badges row, `• 💾 Downloaded` in metadata, and a dedicated one-tap download icon button on each episode card.
  - **Subscriptions & Feed Details**: Added `• 💾 Downloaded` status tag and instant one-tap download action button next to Boost and Add to Queue.
  - **Now Playing Sheet**: Added prominent `DOWNLOADED FOR OFFLINE PLAY` status banner below the transport controls.
  - **MiniPlayer**: Instant `CheckCircle` downloaded indicator next to the episode title.

### 🛠️ Platform & Build Toolchain
- **KSP2 & Android 16 (API 36) Optimization**:
  - Migrated symbol processing to KSP2 for high-speed build and Room 2.7.2 integration.
  - Cleaned orphaned project dependencies and verified clean compilation for Android 16.

---

## [0.8.0-alpha] - 2026-10-14

### 🚗 Android Auto & Hardware Controls Fixes
- **Fixed Fast-Forward Mismapping to Skip/Done**:
  - Steering wheel controls, head-unit hardware buttons, and Bluetooth forward buttons sending `KEYCODE_MEDIA_FAST_FORWARD` or `seekToNext()` now properly seek forward by +90 seconds (`player.seekForward()`) instead of prematurely advancing or marking the current episode as completed.
  - Media session `skipToNextEpisode(markCompleted = false)` now preserves the listener's exact audio position (`player.currentPosition / 1000L`) and only marks an episode completed if the listener genuinely completed $\ge 98\%$ of the duration.
- **Hardware Remote Key Code Routing**:
  - Overrode `MediaSession.Callback.onMediaButtonEvent` to intercept `KeyEvent.KEYCODE_MEDIA_FAST_FORWARD` and trigger immediate +90s jump.

### 📋 Queue & Completed Tracks Workflow
- **Completed Track Re-Enqueuing**:
  - Removed `WHERE e.isCompleted = 0` query constraint from `QueueDao.kt`. Completed episodes manually added to the playlist now display correctly in the queue without being suppressed by SQLite.
  - Adding a played episode to the queue (`enqueueEpisode()` or `enqueueEpisodeTop()`) automatically resets `isCompleted = false` and `playbackPositionSeconds = 0`.
- **Played Filter Chip & History View**:
  - Added a dedicated `"Played"` filter chip to `QueueScreen.kt` alongside `"All"`, `"Hot List"`, and `"Unplayed"`.
  - Listeners can browse their listening history and tap to replay or re-enqueue completed episodes with one touch.
- **Subscriptions Screen Detail Sheet Actions**:
  - Added `"Play Next"` (`PlaylistAdd`) action button and visual `"✓ Played"` indicator chip to episode rows in `SubscriptionsScreen.kt`.

### ⚡ Memory & Performance Hardening
- **Coil Image Decoding Downsampling**:
  - Bounded memory allocation during RSS cover art decoding using `ImageRequest.Builder(context).size(...)`.
  - Episode row thumbnails in `EpisodeItem.kt` decode at a maximum of $168 \times 168\text{ px}$.
  - Subscription feed list artwork decodes at $180 \times 180\text{ px}$; detail sheet banner at $210 \times 210\text{ px}$.
  - Full-screen Now Playing artwork in `PlayerSheet.kt` decodes at $720 \times 720\text{ px}$, preventing multi-megabyte bitmap OOM spikes.
- **Database Polling Query Churn Elimination**:
  - Fixed 500ms playback polling loop in `PlayerViewModel.kt`. Moved `repository.getPodcastById(ep.podcastId)` inside an episode change guard, eliminating ~120 redundant SQLite queries per minute during active playback.
- **Singleton Network Architecture**:
  - Consolidated duplicate `OkHttpClient` instances across `PodcastRepository.kt` and `FeedViewModel.kt` into `PodcastRepository.Companion.getOkHttpClient()`, stopping socket and thread pool leaks.
- **Lifecycle-Bound Coroutines**:
  - Replaced un-scoped `CoroutineScope(Dispatchers.IO)` in `MainActivity.onResume()` with `lifecycleScope.launch(Dispatchers.IO)`.

### 📱 Multi-OEM Device Support
- **Samsung & Motorola Testing Profiles**:
  - Created Android Virtual Device configurations for **Samsung Galaxy S24** ($1080 \times 2340$, 420 dpi, One UI profile) and **Motorola Edge / Moto G** ($1080 \times 2400$, 400 dpi, Hello UI profile) to validate lockscreen media notification controls, Doze battery sleep policies, and gesture navigation insets.

---

## [0.7.0] - 2026-10-07

### Added
- **180-Second Deep Audio Buffer & Reconnect Guard**:
  - Configured ExoPlayer `DefaultLoadControl` with 30s minimum buffer, 180s maximum buffer, and 180s retained back-buffer.
  - Allows up to two consecutive forward skips without network reloads and protects playback against weak cellular dead zones.
  - Guarded `handleEpisodeEnded` to prevent network dropouts from prematurely marking episodes as finished.
- **4-Hour Queue Ahead Auto-Download**:
  - Built `PodcastDownloadManager.kt` to pre-cache upcoming programmed queue episodes up to a 4-hour threshold (never falls below; can exceed for full episodes).
  - Strictly excludes feeds with `playOrderStrategy == "NEWS"` to guarantee news freshness.
- **Creator Inspiration Studio**:
  - Room DB Version 5 migration (`InspirationClip.kt`).
  - One-tap audio bookmarking button in Android Auto and `PlayerSheet.kt` to capture inspirational timestamps.
  - In-app studio to review bookmarks, dictate notes, copy Markdown quotes, and export "Best-Of" OPML feeds.
- **Automated 12-Hour Updates & Local DB Snapshot**:
  - Background `ScheduledUpdateWorker.kt` periodically refreshes feeds, purges expired news, rebuilds queue order, and dumps snapshot JSON backups.
- **PodcastIndex.org Discovery & Category Chips**:
  - Integrated `PodcastIndexClient.kt` with SHA-1 authorization headers for podcast search and category browsing.

---

## [0.6.0] - 2026-09-30

### Added
- **Native Google Drive Cloud Sync (Zero API Keys)**:
  - Re-architected `SyncManager.kt` using Android Storage Access Framework (SAF) with `DocumentFile`.
  - Bidirectional state merge and export (`eagertolisten_sync.json`) for subscriptions, playback progress, and queue.
  - Permanent persistable URI permissions across device reboots and updates.
- **Podcast Addict Migration Engine**:
  - `PodcastAddictImporter.kt` supporting `.backup` (zip), SQLite `.db`, and `.opml` files.
  - Automatically migrates feeds, artwork, custom speeds, skip intro/outro offsets, and played/unplayed history.
- **SQLite Foreign Key & Feed Refresh Stability**:
  - Resolved `SQLiteConstraintException` by ensuring feed parser strictly maps parsed episodes to valid podcast IDs.
  - Added one-tap "Refresh All Feeds" with animated progress indicator in `SubscriptionsScreen.kt`.

---

## [0.5.0] - 2026-09-29

### Added
- **Android Auto MediaLibraryService**:
  - Automotive media metadata declarations in `AndroidManifest.xml` and `automotive_app_desc.xml`.
  - Android Auto Media Browser tree with 4 structured categories: **Pending Playlist**, **Hot List**, **Daily News**, and **Podcasts**.
  - Auto-resumption: resumes playback or starts top of pending playlist upon vehicle connection.
- **Google Assistant Voice Integration**:
  - Hands-free voice queries search and start podcasts or episodes on command.
- **Podcasting 2.0 Live Streams (`<podcast:liveItem>`)**:
  - Native parsing for live audio items with `status="live|pending|ended"`.
  - Visual glowing `● LIVE` badge in mini-player and full player.
  - **90-Minute Live Inactivity Fallback**: Inactivity for $\ge 90$ minutes automatically reverts to the top of the main programmed playlist.

---

## [0.4.0] - 2026-09-28

### Added
- **Curated Airwaves™ Engine (Radio U)**:
  - 4 Autonomous Play Order Strategies:
    1. **News (`NEWS`)**: Rolling retention window (e.g. 3 days), chronological, purged and refreshed just-in-time.
    2. **Chronological (`CHRONOLOGICAL`)**: Oldest unplayed first for serials and investigative reporting.
    3. **Reverse Chronological (`REVERSE_CHRONOLOGICAL`)**: Newest first for casual talk and banter.
    4. **Evergreen (`EVERGREEN`)**: Fairly interleaves catalog archives without queue monopolization.
  - **Anti-Binge Diversity Limit**: Caps consecutive episodes per feed (default: 1 episode) to prevent binge monopolization.
  - **Single-Episode "Play Next"**: Priority insertion into the `#1 NEXT` slot with seamless return to programmed order afterward.
- **Edge-to-Edge Design for Modern Devices**:
  - Window insets handling eliminating double bottom padding across all screens.

---

## [0.3.0] - 2026-09-27

### Added
- **Podcasting 2.0 Namespace Support**:
  - Word-synchronized CC transcripts (WebVTT, SRT, JSON) with live search and tap-to-seek.
  - Rich chapter navigation drawer with timestamps, titles, and chapter artwork.
  - Lightning Value-for-Value (`<podcast:value>`) with Boostagrams, satoshi split breakdowns, and WebLN/external wallet handoff.
  - Creator support (`<podcast:funding>`) with custom donation URL overrides.

---

## [0.2.0] - 2026-09-25

### Added
- **Native AndroidX Media3 Audio Architecture**:
  - `PlaybackService` extending `MediaSessionService`.
  - ExoPlayer configured with 15s rewind and 90s forward jump.
  - Per-feed Skip Intro (auto-seek on start) and Skip Outro (early advance).
  - Audio Focus, noisy-broadcast listener, and lockscreen media session controls.
- **Room Database Architecture**:
  - `Podcast`, `Episode`, and `QueueItem` Room entities with reactive `Flow` queries.
- **Jetpack Compose Theme**:
  - Eager to Listen palette: Forest Green Darkest (`#0C1611`), Forest Green Elevated (`#1C2E25`), Burnt Orange (`#EB5E17`).

---

## [0.1.0] - 2026-07-01

### Added
- Initial project prototype and algorithm design.
- Core queue priority sorting algorithms (Hot List, DoggCatcher priority matrix).
- Initial WebVTT parser and web player companion.
