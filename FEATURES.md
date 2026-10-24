# 🎙️ Eager to Listen — Comprehensive Feature List

> **The Intelligent Broadcast-Style Podcast Player for Commuters & Power Listeners**  
> *Engineered to eliminate queue micromanagement, binge fatigue, and stale news with public radio-style algorithmic scheduling.*

---

## 📻 1. Curated Airwaves™ Engine (Radio U: Algorithmic Scheduling)

*Turn chaotic episode backlogs into an effortless, perfectly balanced daily radio broadcast.*

- **Core Engine Invariants (Strictly Enforced)**:
  - **Invariant I1 (Never Replay Heard Content)**: High-water mark tracking guarantees older episodes are never erroneously re-enqueued.
  - **Invariant I2 (Durable Played State)**: Completed status is never cleared except by explicit user mark-unplayed action.
  - **Invariant I3 (User Settings Win)**: Local feed customizations always win over remote sync.
  - **Invariant I4 (Pinned Manual Survival)**: Pinned items survive automatic queue recalculations.
  - **Invariant I5 (Room Queue Single Source of Truth)**: The database queue state is the definitive playback sequence.
- **5-Tier Priority Matrix (P1 to P5)**:
  - Assign subscription priority from Essential Morning Must-Listens (P1) to casual weekend leisure (P5).
  - High-priority feeds automatically take precedence in the programmed broadcast queue.
- **The Hot List**:
  - Instant one-tap filter displaying all unplayed episodes from your Priority 1 feeds.
- **4 Autonomous Play Order Strategies**:
  1. **News Strategy (`NEWS`)**:
     - Automatically purges episodes older than your configured rolling retention window (e.g., 3 days).
     - Just-In-Time (JIT) feed refresh executes immediately before playback starts to ensure breaking headlines.
  2. **Chronological Strategy (`CHRONOLOGICAL`)**:
     - Plays strictly oldest unplayed first—essential for investigative serials, history arcs, and audio courses.
  3. **Reverse Chronological Strategy (`REVERSE_CHRONOLOGICAL`)**:
     - Plays newest releases first for standard episodic talk, culture, and comedy.
  4. **Evergreen Strategy (`EVERGREEN`)**:
     - Fairly interleaves deep catalog archives so you make steady progress without locking out other subscriptions.
- **Anti-Binge Diversity Limits**:
  - Configurable consecutive episode cap per feed (default: 1 episode).
  - Prevents an episodic series from monopolizing your 45-minute daily commute.
- **Single-Episode "Play Next"**:
  - Tap `"Play Next"` on any episode to inject it into the `#1 NEXT` slot.
  - The player automatically returns to your programmed broadcast schedule once finished.
- **Custom Intro & Outro Skipping**:
  - Per-feed millisecond-precise offsets to skip recurring intro themes (e.g. skip first 45s) and trailing credits/promos (e.g. skip final 60s).

---

## 🚗 2. Commuter Experience & Android Auto Suite

*Built for safe, seamless, hands-free automotive playback.*

- **Full Android Auto MediaLibraryService**:
  - Native integration with car head units and automotive steering wheel controls.
  - 4 structured car categories:
    1. **Pending Playlist**: Live numbered queue (`#1 NEXT`, `#2`, etc.).
    2. **Hot List**: Immediate access to Priority 1 shows.
    3. **Daily News**: Unplayed news releases within your retention window.
    4. **Podcasts**: Complete subscribed feed catalog with episode drill-down.
- **Seamless Vehicle Resumption**:
  - Automatically resumes your paused episode or boots up the top of the pending playlist as soon as your phone connects to the car.
- **Hardware Controls (+90s Skip / -15s Rewind)**:
  - Fast-forward buttons on steering wheels, headsets, and head-units execute a true +90s jump (`player.seekForward()`).
  - Rewind buttons execute a quick -15s replay (`player.seekBack()`).
  - Explicit guard ensures skipping forward does **not** falsely mark incomplete tracks as finished.
- **Google Assistant Voice Integration**:
  - Full voice query dispatch (`onSearch`, `onGetSearchResult`): ask Google Assistant to play specific podcasts or episodes hands-free while driving.

---

## 🎙️ 3. Native Podcasting 2.0 Superpowers

*Embracing the open, decentralized Podcasting 2.0 ecosystem.*

- **Word-Synchronized Interactive Transcripts (`<podcast:transcript>`)**:
  - Real-time closed-caption (CC) transcript viewer supporting WebVTT, SRT, and JSON formats.
  - Highlighted word tracking with live in-transcript keyword search.
  - One-tap tap-to-seek: tap any transcript sentence to jump audio playback directly to that exact second.
- **Rich Chapter Navigation (`<podcast:chapters>`)**:
  - Dynamic chapter drawer parsing remote JSON chapter files.
  - Displays chapter titles, individual chapter artwork, timestamps, and reference web links.
- **Live Stream Broadcasts (`<podcast:liveItem>`)**:
  - Real-time detection of live audio feeds with glowing `● LIVE` badges in the mini-player and full player sheet.
  - **90-Minute Inactivity Fallback**: If listening to a live stream and playback pauses or drops for $\ge 90$ minutes, the app automatically reverts to the top of your programmed playlist.
- **Lightning Value-for-Value (V4V / `<podcast:value>`)**:
  - Direct satoshi micropayments and Boostagram messages to podcast creators.
  - Proportional satoshi split math with fee handling (`V4VSplitMath`) ensuring the exact chosen amount is distributed across all recipients with remainders assigned to the primary host.
  - Podcasting 2.0 / bLIP-10 TLV boostagram record `7629169` metadata.
  - Nostr Wallet Connect (NIP-47) integration feature-flagged behind `FeatureFlags.V4V_NWC` with hardware KeyStore AES-256-GCM secret storage.
  - External wallet integration with `lightning:` URI protocol and clipboard payment request fallbacks.
- **Universal Creator Support (`<podcast:funding>`)**:
  - Native display for Patreon, PayPal, Stripe, and Buy Me a Coffee links.
  - Custom donation URL override for independent podcasters.

---

## 💡 4. Creator Inspiration Studio

*Capture ideas, quotes, and research bookmarks while on the go.*

- **One-Tap Audio Bookmarking**:
  - Dedicated Inspiration lightbulb button on Android Auto and the player sheet.
  - Instantly captures timestamp, episode metadata, and transcript snapshot.
- **In-App Studio Sheet**:
  - Review bookmarked audio moments with replay buttons.
  - Dictate or type thoughts and draft blog notes directly alongside the audio cue.
  - One-tap Markdown quote export for Obsidian, Notion, and newsletter publishing.
  - Export custom "Best-Of" OPML feeds to share curated episode collections with peers.

---

## 🔒 5. Zero-Server Google Drive Cloud Sync & Migration

*Your data belongs to you. Zero server middle-men, zero tracking.*

- **Format 2 Cloud & Local Sync**:
  - Format 2 JSON serializer with atomic write safety (`.tmp` -> validation -> `.bak` -> atomic rename).
  - Last-Write-Wins (LWW) feed rule merging with `settingsUpdatedAt` timestamps.
  - Soft-unsubscribe tombstones preserving played history while removing shows across devices.
  - Bidirectional sync over Google Drive AppData using Android Storage Access Framework (SAF).
- **Podcast Addict Migration Engine**:
  - Instant one-tap import for Podcast Addict `.backup` (zip), SQLite `.db`, and `.opml` files.
  - Migrates your full feed catalog, cover artwork, custom playback speeds, skip intro/outro offsets, and played history.
- **OPML 2.0 Import / Export**:
  - Full OPML 2.0 subscription export via SAF `CreateDocument`.
  - Seamless migration to and from Apple Podcasts, Pocket Casts, Overcast, and AntennaPod.

---

## 🛡️ 6. Audio Pipeline & Offline Resilience

*Broadcast-grade audio playback engine engineered for the real world.*

- **180-Second Deep Buffer & Network Reconnect Guard**:
  - Custom ExoPlayer `DefaultLoadControl` (30s min, 180s max buffer, 180s back-buffer).
  - Retains 3 minutes of back-audio so you can rewind or skip forward without triggering network fetches.
  - Cellular dead zones, tunnels, and subway commutes play seamlessly without stuttering or premature cutoff.
- **Offline Queue Ahead Buffer & WorkManager**:
  - `DownloadQueueWorker` runs with `FOREGROUND_SERVICE_DATA_SYNC` progress notifications.
  - Partial download HTTP `Range` resume using `.part` temporary files.
  - Configurable queue buffer (2h, 4h, 6h, 8h) with cellular download toggle and storage check.
  - Excludes `NEWS` feeds from pre-downloading to guarantee breaking news reports remain fresh.
  - Automated weekly orphan sweeper cleans unreferenced media files.
- **Scheduled Updates & Local DB Snapshot**:
  - `ScheduledUpdateWorker` runs periodic updates to refresh feeds, rebuild queue, top up offline downloads, and back up your database snapshot.

---

## 🚀 7. Memory, Battery & Multi-OEM Optimization

*Engineered to run cool, sip battery, and never get terminated by OEM task killers.*

- **Bounded Coil Bitmap Decoding**:
  - Hard image downsampling ensures cover artwork (frequently 3000×3000px in RSS feeds) never allocates full-resolution bitmaps into RAM.
  - Thumbnails: $168 \times 168\text{ px}$. Subscriptions: $180 \times 180\text{ px}$. Player Sheet: $720 \times 720\text{ px}$.
- **Zero Database Polling Churn**:
  - Eliminated redundant 500ms database polling (~120 queries/min saved during playback).
- **Socket & Thread Pool Leak Guard**:
  - Singleton `OkHttpClient` instance shared across repository, view models, and image decoders.
- **Multi-OEM Hardware Tested**:
  - **Google Pixel 10**: Validated with edge-to-edge window insets and gesture bar alignment.
  - **Samsung Galaxy S24**: Validated with One UI lockscreen media notification controls, Always-On-Display (AOD) wave widget, and Doze Sleeping Apps policy.
  - **Motorola Edge / Moto G**: Validated on tall $20:9$ screens ($1080 \times 2400$) with Hello UI Peek Display.
