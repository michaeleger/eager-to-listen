# 🎙️ Eager to Listen — Alpha Tester Recruitment & Guide

> **Reimagining power-user podcast listening for the modern era.**  
> Seamlessly combining the beloved priority automation of classic power-players with our new **Curated Airwaves™ (Radio U)** NPR-style anti-binge scheduling, cutting-edge Podcasting 2.0 technology, and privacy-first cloud sync.

---

## 👋 Welcome Future Alpha Testers!

Are you frustrated by modern podcast apps that treat your queue like a dumb linear playlist, force you to manually micromanage episodes every morning, or ignore cutting-edge **Podcasting 2.0** innovations like synchronized transcripts, live broadcast feeds, and Lightning Value-for-Value boosts?

**Eager to Listen** was built for power listeners, daily commuters, knowledge seekers, and podcast enthusiasts who want complete control over how their audio is consumed.

We are opening our **Closed Alpha Testing Program** and looking for passionate listeners to test-drive early builds on **Android** and the **Web**, uncover edge cases, stress-test queue algorithms, and shape the future of this player!

---

## ⚡ Why Test Eager to Listen? What Sets It Apart

### 1. 📻 Curated Airwaves™ Engine (Radio U) — NPR-Style Flow vs. Binge Fatigue
- **5-Tier Priority Matrix (P1 to P5)**: Automatically categorize shows from breaking daily news to evergreen deep dives.
- **Hot List**: Instant one-tap access to unplayed episodes from your highest-priority (P1) shows.
- **4 Autonomous Play Order Strategies**:
  - **Chronological**: Serial narratives, audio courses, and lecture series play strictly oldest unplayed first.
  - **Reverse Chronological**: Standard shows play freshest releases first.
  - **News Strategy**: Keeps a rolling window of recent episodes (e.g., last 3 days) in chronological order, automatically purging stale news.
  - **Evergreen / Knowledge**: Smart interleaving that delivers fresh insights without losing your back-catalog place.
- **Automated Intro & Outro Skipping**: Trims annoying recurring intros (e.g., skip first 45s) and credits/ads per podcast.
- **Per-Feed Playback Speeds**: Speed up talk-heavy news to 1.5x while keeping audio dramas at 1.0x.

### 2. 🚀 Native Podcasting 2.0 Engine
- **Synchronized Transcripts**: Real-time word-by-word highlighted transcripts (SRT, VTT, JSON cues) with one-tap seeking and live text search.
- **Rich Chapters with Artwork**: Jump directly to discussion topics with embedded and remote chapter markers, custom artwork, and external reference links.
- **Live Stream Broadcasting (`<podcast:liveItem>`)**: Detects when shows go live in real-time with an active "LIVE NOW" banner and one-tap stream tuning.
- **Person & Host Tags**: See who is speaking, guest profiles, avatars, and roles.

### 3. ⚡ Creator Support & Value for Value (V4V)
- **Direct Funding (`<podcast:funding>`)**: Universal "❤️ Support Show" button opening creator-chosen donation channels (PayPal, Stripe, Patreon, Buy Me a Coffee).
- **Custom Donation URL Overrides**: Add and auto-save personal donation links for indie podcasts that don't yet emit RSS funding tags.
- **Podcasting 2.0 Lightning Boosts**: Send satoshi tips with custom **Boostagram** notes, interactive split breakdowns across show co-hosts, contributors, and apps, and automatic handoff to your favorite Lightning wallet (Alby, Breez, Phoenix, Zeus, Strike, WebLN).

### 4. 🔒 Privacy-First Google Drive Cloud Sync
- Sync your subscriptions, playback positions, queue state, and custom feed rules across Android and Web without creating an account on a third-party server.
- Uses your private Google Drive AppData folder—no tracking, no ads, no telemetry selling your listening habits.

---

## 📲 How to Install & Get Started

### Option A: Native Android App (Pixel, Samsung, OnePlus, etc.)
*Supports Android 8.0 (Oreo / API 26) through Android 16.*

1. **Download the Alpha APK (v0.9.2-alpha)**:
   - Download the latest `app-debug.apk` directly from your tester invite email or install via ADB.
2. **Enable Unknown Apps**:
   - When opening the APK on your device, allow your browser or file manager permission to *"Install unknown apps"* if prompted.
3. **Launch Eager to Listen**:
   - Open the app from your home screen or app drawer.
4. **Import or Export Your Feeds (OPML / Backup)**:
   - Go to **Settings** ➔ **Import Subscriptions (Backup / OPML)** to instantly import your subscriptions and history.
   - You can also tap **Export Subscriptions (OPML)** to export your full catalog at any time.
   - Or tap **Discover** to search for shows or paste any direct RSS feed URL!

### Option B: Web Player & Progressive Web App (PWA)
*Runs on Chrome, Firefox, Safari, Edge across Windows, macOS, Linux, and iOS.*

1. **Open the Web Player**:
   - Navigate to the hosted alpha URL in your modern browser.
2. **Install to Home Screen (PWA)**:
   - On Desktop (Chrome/Edge): Click the **Install** icon in the address bar.
   - On Mobile (Safari/Chrome): Tap **Share** / **Menu** ➔ **Add to Home Screen**.
3. **Enable Personal Cloud Sync**:
   - Navigate to **Settings** ➔ **Google Drive Sync** to connect your account and seamlessly sync your queue between your computer and mobile phone.

---

## 🎯 Alpha Testing Missions (What to Test)

To make testing fun and effective, we've broken the experience into 5 focused missions:

### 🧪 Mission 1: The Commuter Queue Stress-Test
- [ ] Subscribe to at least 5 different podcasts of varying categories (News, Serials, Long-form interviews).
- [ ] Assign different priorities (e.g. Set your favorite daily show to **P1**, history series to **P3**).
- [ ] Change the **Play Order Strategy** on a serial show to **Chronological** and a news show to **News (3 days retention)**.
- [ ] Set **Skip Intro** (e.g., 30s) and test whether the player starts cleanly past the show introduction.
- [ ] Observe the **Pending Playlist** and **Hot List**: Does the programmed queue play smoothly in your expected order?

### 🎙️ Mission 2: Podcasting 2.0 Interactive Media
- [ ] Subscribe to a modern Podcasting 2.0 feed (e.g., *No Agenda Show*, *Podnews Weekly Review*, *Eager to be Healthy*).
- [ ] Open the **Chapters** tab: Verify chapter markers and artwork display. Tap a chapter to jump to that timestamp.
- [ ] Open the **Transcripts** tab: Verify that transcript text auto-scrolls with the speaker's voice. Tap on any word or sentence to see if playback immediately seeks to that timestamp.
- [ ] Check for live streams: When a podcast is broadcasting live, does the red **"LIVE NOW"** badge appear with working audio?

### ⚡ Mission 3: Value for Value & Creator Support
- [ ] Open a Podcasting 2.0 show's detail sheet or full player: Check if the **"❤️ Support Show"** and **"⚡ Boost"** buttons appear.
- [ ] Tap **"❤️ Support Show"**: Does it open the creator's official donation website?
- [ ] Tap **"⚡ Boost"**:
  - Test selecting quick satoshi chips (500, 1000, 2100, 5000, etc.).
  - Enter a test **Boostagram Message** and sender handle.
  - Expand the **Value Recipients** list to inspect split percentages.
  - Tap **Copy Info** or **Send Boost**: Verify that your external Lightning wallet opens with the pre-filled keysend payment, or that the details are copied to your clipboard.
- [ ] Test **Feed Customizer Override**: In a feed with no funding tags, add your own custom donation link (e.g., `https://buymeacoffee.com/...`) and verify that it auto-saves and provides a working Support button!

### 🔄 Mission 4: Privacy-First Google Drive Sync
- [ ] Sign in to Google Drive Sync on Device A (or Web player).
- [ ] Add shows and adjust queue ordering.
- [ ] Trigger **Sync Now** (or wait for auto-sync).
- [ ] Open Device B (or phone), sign into the same Google account, and verify that your queue and subscriptions match!

### 🔋 Mission 5: Daily Driver Audio & Performance
- [ ] Listen while your screen is locked.
- [ ] Test lockscreen media notification controls (Play, Pause, Skip Back 15s, Skip Forward 90s).
- [ ] Test playback with Bluetooth headphones or Android Auto / car head units.
- [ ] Observe battery usage: Does the app drain excessive battery during background playback?

---

## 🐛 How to Report Bugs & Give Feedback

We value your honesty! If something looks off, crashes, feels clunky, or could be faster, we want to know.

### Bug Report Template
When reporting an issue, please include:
```markdown
**Summary**: Brief 1-line description of the bug
**Device & OS**: e.g., Google Pixel 10 running Android 15 (or Windows 11 Chrome)
**App Version**: e.g., Alpha 1.0 (Build 4)
**Podcast / Episode**: Name of the podcast or RSS feed URL
**Steps to Reproduce**:
1. Open...
2. Tap on...
3. Observe that...
**Expected Behavior**: What you expected to happen
**Actual Behavior**: What actually happened (or crash message)
**Screenshots / Screen Recordings**: (Optional, but super helpful!)
```

### Feedback Channels
- 💬 **Discord / Community Chat**: [Join our Alpha Testers Channel]
- 🐙 **GitHub Issue Tracker**: File bugs directly on our repository issue tracker
- 📧 **Direct Developer Email**: `michael.eger@michaelegerconsulting.com`

---

## 🎁 Alpha Tester Perks & Recognition

As an early supporter helping us build Eager to Listen:
1. **Hall of Fame Credit**: Your name or handle listed in the app's **"About & Early Contributors"** screen.
2. **Direct Product Influence**: Your feedback directly decides what features get built, tweaked, or removed.
3. **Permanent Free Access**: Alpha testers receive full, unlocked lifetime access to all future premium features and cloud syncing perks.

---

### Ready to Dive In?
Grab the build, plug in your headphones, and let us know what you think!  
*Thank you for being part of the journey to build the ultimate podcast player.* 🎧🚀
