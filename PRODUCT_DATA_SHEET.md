# 🎙️ Eager to Listen — Product Data Sheet & Technical Specification

> **The Intelligent Broadcast-Style Podcast Player for Power Listeners**  
> *Transforming chaotic podcast queues into a curated, public radio-style daily broadcast.*

---

## 📋 Executive Overview

**Eager to Listen** is an advanced, privacy-first audio platform designed for daily commuters, serial learners, and audio power-users. While mainstream players treat queues as dumb linear lists that force manual micromanagement or marathon binge listening, Eager to Listen introduces the **Curated Airwaves™ Engine (AKA Radio U)**—an algorithmic scheduler inspired by public radio broadcast clocks that dynamically blends breaking news, serial stories, and evergreen deep dives into an engaging, balanced listening rotation.

Built on **AndroidX Media3**, **Jetpack Compose**, and the open **Podcasting 2.0** namespace, Eager to Listen pairs broadcast-quality playback with interactive transcripts, live streams, Lightning Value-for-Value (V4V) micropayments, and zero-server Google Drive cloud synchronization.

---

## 🎯 The Problem vs. The Solution

| The Legacy Podcast Dilemma | The Eager to Listen Solution |
| :--- | :--- |
| **The Binge Trap**: Adding an episodic series floods your queue with 20 back-catalog episodes, monopolizing your audio commute. | **Anti-Binge Diversity Limits**: Automatically caps consecutive episodes per show (1–5 ep), interleaving diverse content like a curated radio station. |
| **Manual Queue Micromanagement**: Spending 10 minutes every morning dragging, reordering, and deleting episodes. | **Curated Airwaves™ (Radio U) Scheduling**: Shows are prioritized (P1–P5) and automatically assigned purpose-built play order strategies. |
| **Stale News Audio**: Daily news episodes linger for weeks after headlines have expired. | **Rolling News Retention Windows**: News shows automatically discard episodes older than $X$ days and refresh right before playing. |
| **Podcasting 1.0 Limitations**: Flat MP3s without transcripts, chapter context, live broadcasts, or creator support. | **Native Podcasting 2.0 Engine**: Real-time word-synchronized transcripts, chapter artwork, live stream alerts, and Lightning boosts. |
| **Privacy Invasion**: Proprietary server tracking, listening telemetry, and mandatory account lock-in. | **Zero-Server Cloud Sync**: Direct Google Drive AppData sync without third-party middleman servers. |

---

## ⚡ Core Feature Pillars

### 1. 📻 Curated Airwaves™ Engine (Radio U: NPR-Style Programmed Flow)
The successor to legacy priority queueing, modeled after public radio hourly programming clocks:

- **5-Tier Priority Matrix (P1 to P5)**: Categorize feeds from essential morning updates (P1) to casual weekend leisure (P5).
- **The Hot List**: Instant one-tap access to all unplayed episodes from your highest-priority (P1) subscriptions.
- **4 Autonomous Play Order Strategies**:
  1. **News Strategy (`NEWS`)**: Maintains a strict rolling retention window (e.g. 3 days) in chronological sequence, purging expired news and refreshing feeds just-in-time before playback.
  2. **Chronological Strategy (`CHRONOLOGICAL`)**: Plays strictly oldest unplayed first—essential for investigative serials, lecture series, and history arcs.
  3. **Reverse Chronological Strategy (`REVERSE_CHRONOLOGICAL`)**: Plays newest releases first for standard episodic banter.
  4. **Evergreen Strategy (`EVERGREEN`)**: Fairly interleaves catalog archives so you make steady progress without locking out other subscriptions.
- **Fair Interleaving & Diversity Limits**: User-customizable consecutive episode limit (default: 1 episode per feed) prevents queue monopolization.
- **Single-Episode "Play Next"**: Inject an urgent breaking episode directly into the `#1 NEXT` slot; the player seamlessly resumes the programmed broadcast flow afterward.
- **Automated Intro & Outro Trimming**: Configure custom per-feed offsets (e.g. skip first 45s intro theme, skip final 60s credits/ads).

---

### 2. 🚀 Native Podcasting 2.0 Superpowers
Full native support for the decentralized Podcasting 2.0 standard:

- **Word-Synchronized Interactive Transcripts**: Real-time highlighted transcripts (SRT, WebVTT, JSON cue formats) with one-tap tap-to-seek and in-transcript live search.
- **Enhanced Chapter Navigation**: Rich chapter drawer featuring episode chapter titles, custom artwork, timestamps, and reference hyperlinks.
- **Live Broadcast Feeds (`<podcast:liveItem>`)**: Real-time detection of live podcast streams with pulsing UI status badges and one-tap stream tuning.
- **90-Minute Live Inactivity Fallback**: If tuned into a live stream and playback remains inactive for $\ge 90$ minutes, the app automatically reverts to the top of the main programmed playlist.
- **Lightning Value-for-Value (`<podcast:value>`)**:
  - Send custom **Boostagram** messages and satoshi micro-tips.
  - Multi-recipient split breakdowns displaying exact percentage splits for hosts, producers, and apps.
  - Quick-tip satoshi chips (`⚡ 500`, `⚡ 1,000`, `⚡ 2,100`, `⚡ 5,000`, `⚡ 10,000`).
  - Seamless handoff to WebLN / external Lightning wallets with clipboard fallback.
- **Universal Creator Support (`<podcast:funding>`)**: One-tap access to creator donation links (Stripe, PayPal, Patreon, Buy Me a Coffee) with custom URL override support for independent feeds.

---

### 3. 🚗 Commuter Experience & Android Auto Suite
Built specifically for safe, frictionless hands-free driving:

- **180-Second Deep Audio Buffer & Reconnect Guard**: Configured ExoPlayer load control (30s minimum, 180s maximum buffer, 180s back-buffer) allows up to two track skips and shields playback from weak cellular dead zones without dropping or prematurely ending episodes.
- **Full Android Auto MediaBrowser**: Categorized into 4 car-optimized menus:
  - **Pending Playlist**: Programmed queue with numerical badges (`#1 NEXT`, `#2`, etc.).
  - **Hot List**: Immediate access to Priority 1 shows.
  - **Daily News**: Unplayed news releases within the retention window.
  - **Podcasts**: Subscription directory with episode drill-down.
- **Steering Wheel & Headset Controls**: 15-second rewind and 90-second forward jump.
- **Google Assistant Voice Integration**: Hands-free voice queries search and start podcasts or episodes on command.
- **Creator Inspiration Studio**: Tap a dedicated bookmark button from Android Auto or the player sheet to capture inspiration timestamps, dictate thoughts, draft blog notes, copy Markdown quotes, and export "Best-Of" OPML feeds.

---

### 4. 🔒 Privacy-First Zero-Server Infrastructure & Automation
- **Google Drive Cloud Sync**: Bidirectional sync of subscriptions, playback positions, custom feed rules, and queue state via the user's private Google Drive AppData folder. **Zero proprietary servers, zero advertising, zero behavioral telemetry.**
- **Scheduled 12-Hour Updates & Local DB Backup**: Background worker periodically refreshes feeds, purges stale news, rebuilds queue order, and exports a clean JSON snapshot to local storage and Google Drive.
- **4-Hour Queue Pre-Download**: Smart offline caching pre-fetches upcoming queue audio up to the target programming threshold (default: 4 hours; can exceed, never fall under), while strictly excluding news feeds to guarantee timely reporting.
- **Podcast Addict Migration Engine**: One-tap importer for Podcast Addict `.backup` (zip), SQLite `.db`, and standard `.opml` files, transferring feeds, artwork, playback positions, and custom playback speeds.

---

## 📐 Technical Specifications

| Parameter | Specification |
| :--- | :--- |
| **Operating System** | Android 8.0 (API 26) through Android 16 (API 36 / targetSdk 36, compileSdk 36) |
| **Target Architecture** | `arm64-v8a`, `armeabi-v7a`, `x86_64` |
| **Application Framework** | 100% Kotlin with Jetpack Compose & Material 3 |
| **Audio Pipeline** | AndroidX Media3 1.5.1 (`media3-exoplayer`, `media3-session`, `media3-ui`) |
| **Queue Engine** | Pure domain `QueueEngine` strictly preserving Invariants I1–I5 |
| **Database Engine** | Room SQLite v6 with schema validation and `MIGRATION_5_6` |
| **Buffer Management** | ExoPlayer `DefaultLoadControl` (30s min, 180s max buffer, 180s retain back-buffer) |
| **Sync Protocol** | Format 2 JSON serializer with atomic write safety (.tmp -> validation -> .bak) |
| **Offline Downloads** | WorkManager `DownloadQueueWorker` (`FOREGROUND_SERVICE_DATA_SYNC`) with HTTP Range resume |
| **Automotive Support** | `androidx.media3.session.MediaLibraryService` with Android Auto XML declarations |
| **V4V & Lightning** | Feature-flagged Nostr Wallet Connect (NIP-47), bLIP-10 TLV boostagrams, KeyStore encryption |
| **Import / Export** | OPML 2.0 (SAF export/import), Podcast Addict SQLite DB / Backup ZIP, Eager JSON Sync |
| **Color Theme** | Forest Green Darkest (`#0C1611`), Forest Green Surface (`#1C2E25`), Burnt Orange (`#EB5E17`) |

---

## 👥 Closed Testing Program & Contact

- **Application Name**: Eager to Listen
- **Current Version**: v0.9.1-alpha (Build 10)
- **Developer / Creator**: Michael Scott Eger
- **Direct Contact & Inquiries**: `michael.eger@michaelegerconsulting.com`
- **Documentation & Guides**: 
  - [Alpha Tester Recruitment & Guide (ALPHA_TESTERS.md)](./ALPHA_TESTERS.md)
  - [Alpha Outreach & Recruitment Templates (ALPHA_OUTREACH_TEMPLATES.md)](./ALPHA_OUTREACH_TEMPLATES.md)
  - [Technical Architecture & Build Guide (README.md)](./README.md)
  - [Architectural Decision Records (docs/adr/0001-nwc.md)](./docs/adr/0001-nwc.md)
