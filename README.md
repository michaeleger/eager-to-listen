# 🎙️ Eager to Listen (Eager to be Healthy Podcast Player)

[![Platform Android](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)
[![Platform Web](https://img.shields.io/badge/Platform-Web%20%2F%20PWA-blue.svg)](https://developer.mozilla.org/en-US/docs/Web/Progressive_web_apps)
[![Podcasting 2.0](https://img.shields.io/badge/Podcasting%202.0-Certified-orange.svg)](https://podcastindex.org)
[![Lightning V4V](https://img.shields.io/badge/Value%20for%20Value-Lightning%20%E2%9A%A1-yellow.svg)](https://lightning.network)
[![Sync](https://img.shields.io/badge/Cloud%20Sync-Google%20Drive-brightgreen.svg)](https://developers.google.com/drive)

> **The power-user podcast player designed for daily commuters, serial learners, and Podcasting 2.0 pioneers.**  
> Featuring the **Curated Airwaves™ Engine (AKA Radio U)**: an NPR-style algorithmic scheduler that programs your day's listening with anti-binge fair rotation, modern Jetpack Compose, Room SQLite, Media3 ExoPlayer, and cutting-edge Podcasting 2.0 standards.

---

## 📢 Calling Alpha Testers!
Are you looking for a podcast player that actually manages your queue automatically instead of making you swipe and reorder dozens of episodes every day?

* 📲 **[Download Direct APK (v0.9.0-alpha)](./android/app/build/outputs/apk/debug/app-debug.apk)** — Direct `.apk` installer for Android.
* ⚡ **[Complete Feature List (FEATURES.md)](./FEATURES.md)** — In-depth breakdown of all feature pillars and engine invariants.
* 📝 **[Changelog (CHANGELOG.md)](./CHANGELOG.md)** — Version-by-version release history from v0.1.0 to v0.9.0-alpha.
* 📋 **[Alpha Tester Guide (ALPHA_TESTERS.md)](./ALPHA_TESTERS.md)** — Step-by-step setup, feedback channels, and test scenarios.
* 📊 **[Product Data Sheet (PRODUCT_DATA_SHEET.md)](./PRODUCT_DATA_SHEET.md)** — Full technical specifications.
* 🌐 **[Interactive Product Website (product-info.html)](./public/product-info.html)** — Comprehensive landing page with screenshots and feature breakdowns.

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
* **Lightning Boostagrams (`<podcast:value>`)**:
  * Quick satoshi chips (`⚡ 500`, `⚡ 1,000`, `⚡ 2,100`, `⚡ 5,000`, `⚡ 10,000`, `⚡ 25,000`) and custom satoshi amounts.
  * Personal boostagram note and sender handle.
  * Interactive recipient split breakdowns showing exact percentages for hosts, producers, and apps.
  * Seamless handoff to Lightning wallets (`lightning:` URI / WebLN) with clipboard keysend fallback.

### 🔒 4. Zero-Server Privacy-First Cloud Sync
* Sync your subscriptions, playback positions, queue state, and custom feed rules between devices using your personal Google Drive AppData folder.
* **No proprietary backend, no tracking, no ads, no telemetry**. Your data remains strictly yours.

---

## 🏗️ Architecture & Project Structure

```text
├── android/                             # Native Android App
│   ├── app/src/main/
│   │   ├── java/com/eagertolisten/app/
│   │   │   ├── data/                   # Room SQLite Database & Daos (Schema v6)
│   │   │   │   ├── model/              # Podcast, Episode, Queue, Podcasting20 models
│   │   │   │   ├── dao/                # PodcastDao, EpisodeDao, QueueDao, PendingRemoteEpisodeDao
│   │   │   │   ├── download/           # DownloadQueueWorker, PodcastDownloadManager
│   │   │   │   ├── sync/               # SyncManager, SyncSerializer (Format 2), SyncDatabaseOps
│   │   │   │   ├── v4v/                # V4VSplitMath, BoostagramPayload, NwcClient, NwcKeyStorage
│   │   │   │   └── repository/         # PodcastRepository (parser, sync, queue, cache)
│   │   │   ├── domain/                 # Pure QueueEngine (Invariants I1-I5, round-robin, evergreen)
│   │   │   ├── importer/               # OpmlExporter (OPML 2.0), PodcastAddictImporter
│   │   │   ├── parser/                 # XML Pull Parser (Podcasting 2.0 tags, V4V, Live)
│   │   │   ├── playback/               # PlaybackCoordinator, PlaybackConfig (+90s/-15s)
│   │   │   ├── service/                # Media3 MediaLibraryService & PlaybackService
│   │   │   ├── worker/                 # ScheduledUpdateWorker
│   │   │   └── ui/                     # Jetpack Compose UI
│   │   │       ├── screens/            # QueueScreen, SubscriptionsScreen, DiscoverScreen, SettingsScreen
│   │   │       ├── sheets/             # PlayerSheet, FeedCustomizerSheet
│   │   │       ├── components/         # BoostSheet, MiniPlayer, PriorityBadge
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
├── server.js                            # Node.js / Express proxy server (SSRF protected)
├── docs/adr/                            # Architectural Decision Records (ADR 0001: NWC)
├── ALPHA_TESTERS.md                     # Dedicated Alpha Tester Recruitment Guide
├── PRODUCT_DATA_SHEET.md                # Full technical specifications
├── FEATURES.md                          # Comprehensive feature list
└── README.md                            # Main project overview & documentation
```

---

## 🛠️ Getting Started & Local Development

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

---

## 🧪 Testing & Verification

- **Pure JVM Unit Tests**: Decoupled unit tests running without Robolectric instrumentation overhead (`QueueEngineTest`, `SyncManagerTest`, `OpmlExporterTest`, `V4VSplitMathTest`, `BoostagramPayloadTest`, `PlaybackCoordinatorTest`, `TranscriptParserTest`, `FeedParserTest`).
- **Room SQLite Migrations**: Room schema version 6 (`MIGRATION_5_6`) verified with Room schema export.
- **Web App Tests & Hardening**: SSRF-hardened Node.js proxy and XSS-sanitized HTML rendering.
- **Live Device Tested**: Verified on Android devices testing priority re-ordering, V4V boostagrams, offline download queue ahead, OPML export, and car steering wheel controls (+90s/-15s).

---

## 📄 License & Community
Built with ❤️ for the open podcasting ecosystem and the Podcasting 2.0 movement.  
For feedback, bug reports, and alpha tester inquiries, contact **michael.eger@michaelegerconsulting.com** or see [ALPHA_TESTERS.md](./ALPHA_TESTERS.md).
