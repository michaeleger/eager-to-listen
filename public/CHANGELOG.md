# Changelog — Eager to Listen

All notable changes to the **Eager to Listen** podcast player are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
