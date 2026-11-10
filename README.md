# 🎙️ Eager to Listen (Eager to be Healthy Podcast Player)

[![Platform Android](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)
[![Platform Web](https://img.shields.io/badge/Platform-Web%20%2F%20PWA-blue.svg)](https://developer.mozilla.org/en-US/docs/Web/Progressive_web_apps)
[![Podcasting 2.0](https://img.shields.io/badge/Podcasting%202.0-Supported-orange.svg)](https://podcastindex.org)
[![Value for Value](https://img.shields.io/badge/Value%20for%20Value-Support%20Show-yellow.svg)](https://value4value.info)
[![Sync](https://img.shields.io/badge/Cloud%20Sync-Google%20Drive-brightgreen.svg)](https://developers.google.com/drive)

> **The power-user podcast player designed for daily commuters, serial learners, and Podcasting 2.0 pioneers.**  
> Featuring the **Curated Airwaves™ Engine (AKA Radio U)**: an NPR-style algorithmic scheduler that programs your day's listening with anti-binge fair rotation, modern Jetpack Compose, Room SQLite, Media3 ExoPlayer, and cutting-edge Podcasting 2.0 standards.

---

## 📢 Calling Alpha Testers!
Are you looking for a podcast player that actually manages your queue automatically instead of making you swipe and reorder dozens of episodes every day?

* 📲 **[Download Eager to Listen (v0.9.3-alpha)](https://eagertobehealthy.com/eager-to-listen)** — Signed Android APKs, Google Play testing opt-in, install steps, and release notes live on the app page.
* 🤖 **[F-Droid Build Setup (FDROID.md)](./FDROID.md)** — F-Droid metadata recipe and reproducible build instructions.
* ⚡ **[Complete Feature List (FEATURES.md)](./FEATURES.md)** — In-depth breakdown of all feature pillars and engine invariants.
* 📝 **[Changelog (CHANGELOG.md)](./CHANGELOG.md)** — Version-by-version release history from v0.1.0 to v0.9.3-alpha.
* 📋 **[Google Play & Alpha Tester Guide (ALPHA_TESTERS.md)](./ALPHA_TESTERS.md)** — Google Play Closed Testing guide, feedback channels, and test scenarios.
* 📊 **[Product Data Sheet (PRODUCT_DATA_SHEET.md)](./PRODUCT_DATA_SHEET.md)** — Full technical specifications.
* 🌐 **[Interactive Product Website (product-info.html)](./public/product-info.html)** — Comprehensive landing page with screenshots and feature breakdowns.

### 🆕 What's new in 0.9.3-alpha
* **Open podcast and RSS links in the app**: `feed://`, `podcast://`, `pcast://`, `itpc://`, RSS/Atom links, "Subscribe on Android" buttons, and downloaded `.rss` feed files open an Add Show preview sheet with P2 default.
* **Unified parallel search**: Podcast Index and Apple Podcasts are queried simultaneously and merged with duplicates collapsed, latest episode dates, and source attribution badges.
* **Resilient playback recovery**: streaming dead zones wait for signal return instead of skipping episodes; corrupt downloads transparently fall back to streaming at your exact spot; moved RSS links are re-read.
* **Creator Inspiration Studio**: bookmark moments (-15s), export Markdown summaries, and maintain a permanent archive in Google Drive (`eagertolisten_bookmarks.jsonl`).
* **Database Version 8**: Room database migration 7→8 upgrading in place.

Full details: **[CHANGELOG.md](./CHANGELOG.md)**. Download: **[eagertobehealthy.com/eager-to-listen](https://eagertobehealthy.com/eager-to-listen)**.

---

## 🌟 Highlights & Key Features

### 📻 1. Curated Airwaves™ Engine (Radio U: NPR-Style Flow vs. Binge Fatigue)
* **5-Tier Priority Matrix (P1 to P5)**: Shows are weighted by importance, so your critical morning news automatically leapfrogs casual weekend banter.
* **The Hot List**: Instantly play all unplayed episodes from Priority 1 shows in one tap.
* **4 Autonomous Play Order Strategies**:
  * `CHRONOLOGICAL`: Oldest unplayed first (indispensable for serials, audio courses, and story arcs).
  * `REVERSE_CHRONOLOGICAL`: Standard newest unplayed first.
  * `NEWS`: Dynamic rolling retention window (e.g., last 3 days) played in chronological order, automatically discarding stale news.
  * `EVERGREEN`: Smart interleaving that delivers new episodes while smoothly continuing your back-catalog.
* **Automated Intro & Outro Skipping**: Configure individual show offsets to skip recurring theme music, disclaimers, or post-show promos.
* **Per-Feed Playback Speeds**: Listen to talk shows at 1.4x while keeping audio dramas at 1.0x.
* **Auto-Save Feed Rules**: Every tweak in the Feed Customizer auto-saves instantly.

### 🚀 2. Native Podcasting 2.0 Superpowers
* **Synchronized Interactive Transcripts**: Real-time word-by-word highlighted transcripts supporting SRT, WebVTT, and JSON cue formats. Tap any word to seek playback immediately.
* **Rich Chapters with Artwork**: Browse podcast chapters with dedicated title art, duration marks, and external web links.
* **Live Broadcast Feeds (`<podcast:liveItem>`)**: Detects live broadcasts with an active red **"LIVE NOW"** banner and one-tap live audio stream tuning.
* **Person & Soundbite Tags**: Full support for `<podcast:person>` (hosts, co-hosts, guests) and highlighted audio soundbites.
* **Rich Show Notes**: Full HTML description parsing with clickable links and chapter timestamps.

### ⚡ 3. Creator Support & Value for Value (V4V)
* **Direct Funding (`<podcast:funding>`)**: Universal **"❤️ Support Show"** action button opening the creator's preferred donation channels (PayPal, Stripe, Patreon, Buy Me a Coffee).
* **Custom Donation Link Overrides**: Set a custom donation URL per podcast in feed settings with auto-save (ideal for Riverside, Substack, and indie feeds that don't yet emit RSS funding tags).
* **Lightning Boostagrams (`<podcast:value>`)** *(hidden in 0.9.2 until boost payments can be sent; Support Show works today)*:
  * Quick satoshi chips (`⚡ 500`, `⚡ 1,000`, `⚡ 2,100`, `⚡ 5,000`, `⚡ 10,000`, `⚡ 25,000`) and custom satoshi amounts.
  * Personal boostagram note and sender handle.
  * Interactive recipient split breakdowns showing exact percentages for hosts, producers, and apps.
  * Seamless handoff to Lightning wallets (`lightning:` URI / WebLN) with clipboard keysend fallback.

### 🚗 4. Built for the Car
* **Steering-wheel & headset buttons**: Next/Previous jump within the episode by default, so a stray press never drops what you're hearing. Switch to "Skip episode" in Settings → Player Controls.
* **Android Auto**: browse your programmed playlist, Hot List, Daily News and shows; "Up next" follows your queue across all shows.
* **Adjustable skip times** shared by the screen, the car and the lock screen.

### 📥 5. Bring Your Shows With You
* **Podcast Addict** full backup (`.backup`) and **AntennaPod** database export (`.db`): subscriptions plus played episodes, resume points and per-show speed.
* **OPML** from any app (Podverse, Pocket Casts, Castbox, Player FM, ...): subscriptions.
* **Local folder shows**: a folder of audio files on your phone plays as a show, in filename order. Read-only: your files are never changed or deleted.
* Imports merge into shows you already have; nothing gets added twice.

### 🔒 6. Zero-Server Privacy-First Cloud Sync
* Sync your subscriptions, playback positions, queue state, and custom feed rules between devices using your personal Google Drive AppData folder.
* **No proprietary backend, no tracking, no ads, no telemetry**. Your data remains strictly yours.

---

## 🏗️ Architecture & Project Structure

```text
├── android/                             # Native Android App
│   ├── app/src/main/
│   │   ├── java/com/eagertolisten/app/
│   │   │   ├── data/                   # Room SQLite Database & Daos (Schema v7)
│   │   │   │   ├── model/              # Podcast, Episode, Queue, Podcasting20 models, FeedUrl, ShowMatcher
│   │   │   │   ├── local/              # LocalFolderScanner, LocalFolderFormat (local folder shows)
│   │   │   │   ├── dao/                # PodcastDao, EpisodeDao, QueueDao, PendingRemoteEpisodeDao
│   │   │   │   ├── download/           # DownloadQueueWorker, PodcastDownloadManager
│   │   │   │   ├── sync/               # SyncManager, SyncSerializer (Format 2), SyncDatabaseOps
│   │   │   │   ├── v4v/                # V4VSplitMath, BoostagramPayload, NwcClient, NwcKeyStorage
│   │   │   │   └── repository/         # PodcastRepository (parser, sync, queue, cache)
│   │   │   ├── domain/                 # Pure QueueEngine (Invariants I1-I5, round-robin, evergreen)
│   │   │   ├── diagnostics/            # CrashReporter (opt-in emailed crash reports)
│   │   │   ├── importer/               # PodcastAddictImporter (Podcast Addict, AntennaPod, OPML), OpmlExporter, OpmlRecovery
│   │   │   ├── parser/                 # XML Pull Parser (Podcasting 2.0 tags, V4V, Live)
│   │   │   ├── service/                # Media3 PlaybackService, PlaybackCoordinator, PlaybackController
│   │   │   ├── settings/               # PlayerSettings (skip times, car/headset buttons, Bookmark toggle)
│   │   │   ├── worker/                 # ScheduledUpdateWorker
│   │   │   └── ui/                     # Jetpack Compose UI
│   │   │       ├── screens/            # Queue, Subscriptions, Discover, Settings, FeedCustomizer & InspirationStudio sheets
│   │   │       ├── components/         # PlayerSheet, MiniPlayer, EpisodeItem, EpisodeOverflowMenu, BoostSheet
│   │   │       └── theme/              # Forest Green (#0C1611) & Burnt Orange (#EB5E17)
│   │   ├── res/xml/                    # network_security_config, data_extraction_rules, backup_rules
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── public/                              # Web Player & Progressive Web App (PWA)
│   ├── app.js                          # Web player app controller (XSS sanitized)
│   ├── queueManager.js                 # Smart queue engine for Web
│   ├── driveSync.js                    # Google Drive AppData sync client
│   ├── transcriptParser.js             # VTT, SRT, and JSON transcript parser
│   ├── chapterParser.js                # PSC and description chapter parser
│   ├── index.html                      # Semantic responsive player interface
│   └── style.css                       # Modern dark mode theme styling
├── desktop/                             # Desktop app for Linux (Windows later), Tauri; syncs with Android. See desktop/README.md
├── server.js                            # Node.js / Express proxy server (SSRF protected)
├── docs/adr/                            # Architectural Decision Records (ADR 0001: NWC)
├── ALPHA_TESTERS.md                     # Dedicated Alpha Tester Recruitment Guide
├── PRODUCT_DATA_SHEET.md                # Full technical specifications
├── FEATURES.md                          # Comprehensive feature list
└── README.md                            # Main project overview & documentation
```

---

## 🛠️ Getting Started & Local Development

### 🖥 Desktop App (Linux)
See [desktop/README.md](desktop/README.md): build steps, rclone setup for phone sync, and what's in the alpha.

### 🌐 Running the Web Player Locally
1. **Prerequisites**: Ensure [Node.js](https://nodejs.org) (v18+) is installed.
2. **Install Dependencies**:
   ```bash
   npm install
   ```
3. **Start the Proxy & Static Server**:
   ```bash
   npm start
   ```
4. **Open in Browser**:
   Open [http://localhost:3000](http://localhost:3000) in your web browser.

---

### 📱 Building the Native Android App
1. **Prerequisites**:
   * Android SDK (API 36 / compileSdk 36, minSdk 26).
   * Java Development Kit (JDK 21+).
   * Android device or emulator with Developer Options & USB Debugging enabled.
2. **Assemble Debug APK**:
   ```bash
   cd android
   ./gradlew assembleDebug
   ```
   *(On Windows PowerShell: `cd android; ./gradlew assembleDebug`)*
3. **Install to Connected Device**:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. **Launch the Application**:
   ```bash
   adb shell am start -n com.eagertolisten.app/.MainActivity
   ```
5. **Run the unit tests**:
   ```bash
   ./gradlew testDebugUnitTest
   ```
6. **Database changes**: Room exports its schema to `android/app/schemas/` on build. When the schema version changes, commit the new JSON file with your change.

> **Just want to install it?** Signed release APKs are on the app page: **[eagertobehealthy.com/eager-to-listen](https://eagertobehealthy.com/eager-to-listen)**.

---

## 🧪 Testing & Verification

- **Pure JVM Unit Tests** (`./gradlew testDebugUnitTest`): `QueueEngineTest`, `SyncManagerTest`, `OpmlExporterTest`, `V4VSplitMathTest`, `BoostagramPayloadTest`, `PlaybackCoordinatorTest`, `TranscriptParserTest`, `FeedParserTest`, plus 0.9.2's `PlayerSettingsTest`, `CrashReporterTest`, `FeedUrlSameFeedTest`, `ShowMatcherTest`, `PodcastAddictFormatTest` (values from a real Podcast Addict backup), `AntennaPodAndOpmlRecoveryTest` and `LocalFolderFormatTest`.
- **Room SQLite Migrations**: schema version 7 (`MIGRATION_6_7`, adds `podcastGuid`), with `MigrationTest` covering 5→6 and 6→7 (instrumented; needs a device or emulator).
- **Web App Tests & Hardening**: SSRF-hardened Node.js proxy and XSS-sanitized HTML rendering.
- **Device Testing**: alpha testers on Pixel phones and tablets, plus in-car testing over Bluetooth and Android Auto. Found a problem? Use **Settings → Send Alpha Feedback Email**, or the crash report the app offers after a crash.

---

## 📄 License & Community
Eager to Listen is free software, licensed under the **GNU General Public License v3.0** ([LICENSE](./LICENSE)).
Copyright © 2026 Michael Scott Eger. You can use, study, share and change it; versions you
share must stay under the same license.

Built with ❤️ for the open podcasting ecosystem and the Podcasting 2.0 movement.  
App page & downloads: **[eagertobehealthy.com/eager-to-listen](https://eagertobehealthy.com/eager-to-listen)**  
For feedback, bug reports, and alpha tester inquiries, contact **michael.eger@michaelegerconsulting.com** or see [ALPHA_TESTERS.md](./ALPHA_TESTERS.md).
