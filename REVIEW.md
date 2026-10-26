# Second-Opinion Review: Eager to Listen (Gemini output)

**Reviewed:** commit `e8e4496` ("Alpha 0.5.1"), October 24, 2026
**Scope:** native Android app (Kotlin, Compose, Room, Media3), web player in `public/`, `server.js`, and all docs and website copy.

## Verdict

The app has a solid structure, and many of the features are real: a programmed queue, play-order strategies, per-feed skip intro and outro, Android Auto browsing, Podcasting 2.0 parsing, the Inspiration Studio, and a feedback email. It is **not ready to hand to alpha testers yet**, though. Several bugs silently lose listening history or keep finished episodes in the queue, and those are the things a queue-first player most needs to get right. The website and marketing copy also claim features that don't exist, or work differently than described. A few of those claims (an "encrypted" backup, which search service is used) are in the privacy policy, and Google Play reviews that document.

### What I could and couldn't verify

- **No Android build.** This environment's network policy blocks Google's Maven and SDK hosts (`dl.google.com` / `maven.google.com`), so `./gradlew assembleDebug` can't run here. Every Android finding below comes from reading the code. The two Media3 threading findings should be confirmed in logcat on a device.
- **Web tests ran:** `public/tests.js` gives **23 of 26 passing**, not the "23/23 passing" in `task.md`. The Riverside plain-text transcript tests fail, and one failure crashes the rest of the parser group. The cause: `transcriptParser.js` only splits on blank lines, so speaker turns on consecutive lines get merged into one cue.
- **The Android unit tests are only the template stub** (`ExampleUnitTest`: `2 + 2 == 4`). "testDebugUnitTest (26 tasks passing)" in `task.md` counts Gradle tasks, not tests. The queue algorithm, parsers, migrations, and importer have no test coverage at all.
- **Live URLs** (feeds, Play listing, website) couldn't be fetched from this sandbox, so they're flagged below as "verify".

---

## 1. Must fix before testers

### 1.1 Episodes that finish naturally aren't marked played, and their progress gets overwritten
`service/PlaybackService.kt:213-227`, `:301`, `:436-447`

Since 0.5.1, the player is loaded with the current episode plus up to 5 upcoming ones. When an episode ends on its own, ExoPlayer moves to the next item with an *auto transition*. `STATE_ENDED` only fires at the end of the whole playlist, so `handleEpisodeEnded()` never runs for the finished episode. Instead, `onMediaItemTransition` calls `persistCurrentProgress()` *after* the transition. At that point `PlaybackStateManager.currentEpisode` still points to the previous episode, but `player.currentPosition` and `duration` already belong to the **new** one. The result: the finished episode gets saved with roughly 0 seconds and `isCompleted = false`. That even undoes the 98% completion that the 10-second tracker may have just written. The episode is removed from the queue, but because it's still "unplayed", the next queue rebuild (on app launch, the 12-hour worker, or any feed-rule change) puts it back.

**Fix:** in `onMediaItemTransition`, when `reason == MEDIA_ITEM_TRANSITION_REASON_AUTO`, mark the *previous* episode completed (the same work as `handleEpisodeEnded`, without starting new playback). Capture position and duration *before* the transition for the other reasons.

### 1.2 Player accessed from a background thread (likely crash on "Next" and on auto-advance)
`service/PlaybackService.kt:225`, `:739-742`, `:762-765`

`skipToNextEpisode()` reads `player.currentPosition` and `player.duration` inside `serviceScope.launch(Dispatchers.IO)`. `persistCurrentProgress()` reads the player too, and it gets called from an IO coroutine in `onMediaItemTransition`. Media3's `ExoPlayer` enforces single-thread access and throws `IllegalStateException: Player is accessed on the wrong thread` by default. `serviceScope` has no exception handler, so that would crash the service on the steering-wheel **Next** button, the Android Auto skip button, and every auto-advance.

**Fix:** read position and duration on the main thread first, then pass the values into the IO coroutine. **Verify** with `adb logcat | grep "wrong thread"`.

### 1.3 Importing an OPML or Podcast Addict backup wipes existing history
`importer/PodcastAddictImporter.kt:183`, `:292`, `data/dao/PodcastDao.kt:38-42`, `data/model/Episode.kt:11-16`

The importer writes podcasts with `@Insert(onConflict = REPLACE)`. In SQLite, REPLACE deletes the old row and inserts a new one, and the `ON DELETE CASCADE` foreign key then deletes **every episode** of that podcast (positions, played flags, download state) along with their queue items. It also resets that feed's priority, play order, and speed to the defaults. So importing a file that contains any show the user already follows erases that show's history without warning.

**Fix:** use `@Upsert`, or insert with `IGNORE` and then update, for podcasts. Don't use REPLACE on any parent table that has CASCADE children.

### 1.4 Imported play history never matches real episodes
`importer/PodcastAddictImporter.kt:199` vs `parser/FeedParser.kt:326`

Imported episodes get IDs of the form `UUID(podcastId:guid)`, but the feed parser uses the raw GUID (or the enclosure URL) as the ID. After the first feed refresh, every imported episode exists twice: the imported copy with its history, and a fresh unplayed copy from the feed. The queue then fills with episodes the user has already heard, which is the opposite of what an importer is for.

There are other problems in the same file. The column names (`url`/`link`, `read`/`played`/`downloaded`, …) are guesses that haven't been checked against a real Podcast Addict backup. `link` is usually the show's website rather than its feed. And a "downloaded" flag is treated as "played".

**Fix:** use the same ID scheme as `FeedParser` (the GUID, falling back to the enclosure URL), then test with a real `.backup` file.

### 1.5 Keyword-based play-order guessing marks whole back catalogs as played
`parser/FeedParser.kt:439-455`, `data/repository/PodcastRepository.kt:519-523`, `:333-337`, `data/dao/EpisodeDao.kt:176-199`

`inferPlayOrderStrategy` checks substrings of the title, description, and category:

- "news" matches "news**letter**", which appears in a very large number of show descriptions.
- "daily" matches *The Daily Stoic*.
- "story" matches "hi**story**" (so *Lex Fridman*, *Hardcore History*, and anything about "history" become CHRONOLOGICAL and start from episode 1).
- "class" matches "**class**ic".
- "course" matches "of course" and "dis**course**".

Any feed guessed as NEWS then has every episode older than 3 days **marked as played** (`isCompleted = 1`), not just hidden. This also happens when a user taps the NEWS chip by accident (the rules auto-save). Switching the feed back doesn't undo it, and the Played tab fills up with episodes nobody listened to.

**Fix:**
- Infer only from `itunes:type` (`serial` → chronological) and explicit categories, with whole-word matching. Default to EVERGREEN.
- Have NEWS *filter* old episodes out of the queue instead of writing `isCompleted`.

### 1.6 Latent crash on the next database upgrade
`data/EagerDatabase.kt:94-101`

Three extra indexes are created in `onOpen` but aren't declared on the entities. Room's migration check compares the actual indexes against the declared ones. On the next schema version bump (v5 → v6), every existing install will fail with `IllegalStateException: Migration didn't properly handle: episodes`. `fallbackToDestructiveMigration()` doesn't catch this, because it only covers *missing* migrations.

**Fix:** declare these as `@Index` on the entities and create them in a real migration, or have the next migration drop them first. Also turn on `exportSchema = true` so migrations can be tested.

### 1.7 Queue rebuilds throw away "Play Next", manual order, and manually added episodes
`data/repository/PodcastRepository.kt:593-603`, `:271`; `MainActivity.kt` (refresh on startup)

`generateProgrammedQueue()` clears the whole queue and rebuilds it only from feeds that have auto-add turned on. It runs on every app launch (the silent refresh), every 12 hours, after every subscribe, and after every change to a feed's rules. Three things get lost:

- A "Play Next" episode, unless it's already playing or partly played.
- Any episode added by hand from a feed with auto-add turned off.
- A drag-and-drop order. The MANUAL sort rule is saved but never reapplied.

The code comment says "playback naturally returns to the programmed order", but in practice the user's choices disappear the next time they open the app.

**Fix:** keep a `isManual`/`pinned` flag on `QueueItem` and keep those items, in their order, ahead of the programmed items.

### 1.8 Hard-coded overrides for two specific shows
`data/dao/PodcastDao.kt:86-101`, `data/repository/PodcastRepository.kt:456-457`, `parser/FeedParser.kt:423-461`

Every queue rebuild forces *No Agenda* back to NEWS and forces *Eager to be Healthy*'s category back to its hard-coded value. A user who changes either setting will see it silently revert. It also reads as self-dealing in a player that's pitched as neutral.

**Fix:** use these as defaults when the user first subscribes, not as rules applied on every rebuild.

### 1.9 Auto-download uses mobile data by default
`worker/ScheduledUpdateWorker.kt:44-46`, `:85`

The background worker only requires `NetworkType.CONNECTED`, and auto-download is on by default with a 4-hour target, which is roughly 200 to 400 MB per run. Testers on metered plans will pay for it. `task.md` lists the unmetered constraint as a Phase 25 to-do, but it needs to come before any outside testers.

**Fix:** require `UNMETERED` for downloads (with a setting to allow cellular), or keep refresh and download as separate jobs.

### 1.10 Flagship show's feed URL is almost certainly wrong
`data/podcastindex/PodcastIndexClient.kt:51`

The "Recommended" Eager to be Healthy entry points to `https://anchor.fm/s/eagertobehealthy/podcast/rss`. Anchor feeds use a hex ID rather than a slug, and the repo's own website copy and `riverside_sample.xml` use `https://api.riverside.fm/hosting/drlxDc7x.rss`. The artwork URL also looks made up, and the Huberman and Attia artwork URLs should be checked too.

**Fix:** use the Riverside feed URL and real artwork URLs.

---

## 2. Should fix

| # | Where | Issue |
|---|---|---|
| 2.1 | `PlaybackService.kt:348-364` | A playback error on a normal episode retries every 2.5 s **forever**. A dead enclosure (404) or unsupported format stalls the queue in the car with no way forward. Cap the retries, then skip the episode and show a toast. |
| 2.2 | `PlaybackService.kt:1584-1593` | `onDestroy` starts the final progress save on `serviceScope` and then cancels that scope right away, so the save usually never runs. Save synchronously, or on a scope that isn't being cancelled. |
| 2.3 | `SyncManager.kt:221` | Sync only exports **unplayed** episodes, so "played" status never reaches the other device, even though the merge code expects it. The queue is exported but never imported, and unsubscribes aren't synced. |
| 2.4 | `SyncManager.kt` (SAF tree picker) | "Google Drive sync" uses `ACTION_OPEN_DOCUMENT_TREE`. The Google Drive app has a history of not offering folder (tree) selection to other apps. **Verify on a device** that Drive actually shows up as a choice. If it doesn't, the feature only works with local folders. |
| 2.5 | `PodcastRepository.kt:657-659` | Unsubscribing deletes the rows but leaves the downloaded audio files on disk, where nothing will ever reference them again. |
| 2.6 | `BoostSheet.kt:96-120` | A "boost" opens a `lightning:` URI to the **first recipient only**: no value splits and no boostagram metadata (TLV 7629169). Podcasters won't receive these as boostagrams, and the split percentages the UI displays aren't paid. For a show that runs on Value for Value, this needs to work properly (for example through NWC, Alby, or Fountain-style keysend) or be labeled honestly. |
| 2.7 | `TranscriptParser.kt` (plain text) | Plain-text transcripts get evenly *spread-out, made-up* timings, but the UI highlights and seeks as if they were synced. Mark these as "approximate", or skip highlighting for them. |
| 2.8 | `PodcastRepository.kt:65` | The user agent pretends to be Chrome on a "Pixel 10". Podcast hosts and IAB/OP3 analytics will count downloads as browser traffic. Podcasting 2.0 norms expect an honest app UA, such as `EagerToListen/0.5.1 (Android)`. |
| 2.9 | `LiveSessionManager.kt:26` | The live-session timeout is **60 min** in code, but `task.md`, `FEATURES.md`, the CHANGELOG, and the product page all say **90 min**. |
| 2.10 | `PlaybackService.kt:129-133`, `:1146-1148` | Comments still say steering-wheel Next maps to +90 s, but the code (correctly, per 0.5.1) skips to the next episode. These are misleading for whoever maintains this next. |
| 2.11 | `server.js` | `/api/proxy` is an **open proxy**: any URL, no allowlist, no size limit, and it can reach internal addresses. Fine on localhost, but never deploy it publicly as-is. The startup banner also still says "DoggCatcher Clone server". |
| 2.12 | `FeedParser.kt:186-189` | When an item has several `<podcast:transcript>` tags, the last one wins, even if it's HTML. Prefer VTT, then SRT, then JSON. |

---

## 3. Website, marketing, and privacy copy

The website copy is the part you're about to publish. Here is each claim checked against the code:

| Claim (where) | Reality | Action |
|---|---|---|
| "**Word-synced** transcripts… follow along word-by-word… tap any word" (website, README, outreach) | Highlighting and tap-to-seek work per **cue** (sentence or segment). There is no word-level sync. | Say "synced transcripts: tap a line to jump there." |
| "**Search inside transcripts**" (website copy) | Not implemented. | Remove. |
| "Upload an **encrypted** backup" (**privacy policy §6**) | Sync writes plain-text JSON. | Remove "encrypted". This matters because the policy is reviewed by Google Play. |
| "Queries are resolved via the **Podcast Index** API" (privacy policy §5) | Unless the user enters their own PodcastIndex keys, search goes to **Apple's iTunes Search API**. | Disclose Apple iTunes Search. |
| Notifications "inform you when offline downloads complete" (privacy policy §3) | No such notification exists. | Remove. |
| "Episode ratings" (privacy policy §2) | There is no ratings feature. | Remove. |
| Contact `support@eagertolisten.com` (privacy policy) | Everything else uses `michael.eger@michaelegerconsulting.com`. | Confirm you own that domain and mailbox, or switch the address. |
| "Syncs your subscriptions, **play spots, queue**, and per-show rules" (website) | Played status and queue don't sync (see 2.3). Docs also say "Google Drive **AppData** folder", but Android uses a folder the user picks. | Soften the wording until 2.3 and 2.4 are fixed. |
| "Bitcoin Lightning boosts with custom boostagram notes", "**WebLN**" (website, FEATURES, README) | WebLN is a browser API the native app doesn't use, and boosts don't carry boostagram data (see 2.6). | Say "opens your Lightning wallet" until real boosting ships. |
| "Import… OPML from Pocket Casts, AntennaPod, Overcast, or **Apple Podcasts**" | Apple Podcasts has no built-in OPML export. | Drop Apple Podcasts. |
| "*Eager to be Healthy* is **pinned at the top of Health & Wellness**" | It's in a "Recommended (Featured)" list, and with the wrong feed URL (see 1.10). | Fix the URL, then describe it accurately. |
| "**Podcasting 2.0 Certified**" badge (README) | No such certification exists. | Remove the badge. |
| "Tested on Pixel 10…", "23/23 tests", "0 errors" (task.md, docs) | These can't be verified, and the web test claim is false (23/26). | Don't repeat these publicly. |
| Version: website says **0.5.1-alpha / build code 2**; README says **v0.8.0-alpha**; CHANGELOG lists 0.8.0 (Oct 14) **before** 0.5.1 (Oct 23); `build.gradle` says `versionName "0.5"`, `versionCode 2` | The version number went *backwards*. If any tester installed an earlier build with a higher `versionCode`, Android will refuse the update (`INSTALL_FAILED_VERSION_DOWNGRADE`). | Pick one scheme, e.g. `0.9.0-alpha`, with `versionCode` strictly increasing, and use it everywhere. |
| APK and ZIP download links (README, `product-info.html`) | The files aren't in the repo (they're ignored by `.gitignore`), so the links 404 on GitHub. | Link to the hosted APK or the Play testing page. |
| Google Play testing link `play.google.com/apps/testing/com.eagertolisten.app` | Couldn't check from here. | Confirm the track exists and is open before publishing. |

### Branding and legal

- **DoggCatcher** is named throughout: code comments, README ("Smart DoggCatcher queue engine"), CHANGELOG, ALPHA_TESTERS, outreach templates, the `server.js` banner, and `capacitor.config.json` (`"appId": "com.doggcatcher.app"`). Using a competitor's trademark in public material is avoidable risk. "Inspired by classic priority-based players" says the same thing. Change the Capacitor `appId` too, or delete the leftover Capacitor setup (see §4).
- "Curated Airwaves™": fine if you intend to claim it. Just be consistent.

---

## 4. Repo cleanup

- **Stray files at the root:** `android/should` (a one-line test note), `check_db.js` (reads a `device_db.sqlite` that isn't in the repo), `test_chapters.js`, `test_plain_transcript.js`, `test_transcripts.js`, and `riverside_sample.xml` (121 KB fixture). Move them under `tools/` or `fixtures/`, or delete them.
- **Duplicated docs:** `CHANGELOG.md`, `FEATURES.md`, `product-info.html`, and `GROK_WEBSITE_UPDATE_INSTRUCTIONS.md` exist both at the root and in `public/`. `public/` is the web root (and the Capacitor `webDir`), so internal bot instructions end up served publicly. Keep one copy and keep internal notes out of `public/`.
- **Leftover Capacitor setup:** `package.json` (`@capacitor/*` packages and the name `eager-to-be-healthy-podcast-player`), `capacitor.config.json`, `android/capacitor.settings.gradle`, `android/app/capacitor.build.gradle`, and the `com.getcapacitor.myapp` test package. The native app no longer uses Capacitor: `settings.gradle` includes only `:app`, and `app/build.gradle` never applies the Capacitor scripts.
- `variables.gradle` says `minSdkVersion = 24`, but the app actually uses `minSdk = 26`.
- The README architecture tree lists `playback/` and `ui/sheets/` folders and says "Schema v4". The real folders are `service/` and `ui/screens/`, and the schema is v5.

---

## 5. Suggested order of work

1. Fix 1.1 and 1.2 (completion and threading), then 1.3 and 1.4 (importer), then 1.5 (NEWS marking episodes played), then 1.7 (keeping manual queue items). These cause the data loss testers would notice first.
2. Fix 1.6 before **any** schema change, and 1.9 before sending the APK to anyone.
3. Fix the privacy policy rows in §3. Then rewrite the website copy so every claim matches the build, and unify the version number.
4. Add unit tests for `generateProgrammedQueue`, `FeedParser` (including the inference cases above), `TranscriptParser`, and a Room `MigrationTestHelper` test. Make CI run them.
5. Clean up the repo (§4).

The remaining claims (Android Auto behavior, voice search, live streams) need a real device and car head unit to verify, and none of that can be checked from this review.

---

# Round 2: Gemini's fixes (`7758d76`…`d293ce7` on `main`)

**Reviewed:** the 15 commits Gemini pushed after round 1 (phases 0.1–10.1 plus three follow-ups), October 26, 2026.

## Verdict

Most of what round 1 asked for is genuinely fixed: finished episodes are marked played, imports no longer wipe history, show-type guessing uses whole words, expired news is hidden rather than marked played, manual queue items survive rebuilds, the next migration is safe, and downloads wait for Wi-Fi. There are real unit tests now. However, **the rewrite introduced a playback regression** (fixed in this branch, below), and **the new Lightning wallet (NWC) client cannot work**.

## Verification

- **Gemini's unit tests:** I ran all 46 pure-JVM tests in a standalone Kotlin harness (Maven Central, with compile-only stubs for Room annotations, Media3 error codes, and `android.util.Xml`). All 46 pass, across QueueEngine, FeedParser, TranscriptParser, PlaybackCoordinator, V4V split math, boostagram payloads, the OPML exporter, and FeedUrl/EpisodeId. `SyncManagerTest`, `LiveSessionManagerTest` (Robolectric), and `MigrationTest` (device) couldn't run here.
- **Migration 5→6:** I built the v6 schema from `schemas/…/6.json` in SQLite next to the tables the migration's own SQL creates. Columns, types, nullability, primary keys, foreign keys, and indexes match for all five tables, so Room's post-migration validation should pass. The data remap itself (ID canonicalization) is only covered by the device-only `MigrationTest`. **Run it on a device before shipping**, because it touches every install.
- **Web player tests:** still 23 of 26. The web player is out of MVP scope, so this is deferred (see below).
- The Android app as a whole still can't be built here (Google Maven is blocked).

## Fixed in this branch

### R2.1 Playback no longer recovered from audio interruptions (the regression you noticed)
`service/PlaybackCoordinator.kt`, `service/PlaybackService.kt` (commit `4f542f1`)

Neither the original nor the new code has explicit audio-focus code; both let ExoPlayer handle focus (`setAudioAttributes(…, true)`). The recovery came from the original `onPlayerError`, which retried **every** non-live error (prepare and play again every 2.5 s). Phone calls, navigation prompts, and Bluetooth or car audio route changes often surface as AudioTrack or decoder-init errors, and that blanket retry carried playback through them.

Phase 2.1 replaced it with a classifier that retried only three network error codes. Everything else went to `DISMISS_UNPLAYABLE`, which marks the episode **dismissed** (hidden from the queue, and the dismissal syncs to other devices), removes it, and skips to the next one. Network retries also stopped after 5 tries (about 1 minute), so a tunnel or dead zone left playback paused.

The fix:
- **Audio-device errors** (AudioTrack 5xxx, decoder init/query) retry with a short backoff and **never dismiss**.
- **Only known-permanent errors dismiss:** HTTP 4xx except 408/429, parsing, unsupported format, decoding, file not found, no permission. Unknown errors retry, the way the original did.
- **Network retries** back off up to a 30 s cap for about 8.5 minutes before pausing.
- **The retry budget resets** after 30 s of healthy playback.
- **Retries respect the listener:** a pause or an episode change during the backoff cancels the retry, and stacked retries collapse into one.
- **A failing local download falls back to streaming** instead of dismissing the episode. This is decided from the URI that actually failed, because finishing at 98% deletes the file while the tail is still playing.
- **`play()` re-prepares an idle player**, so "tap play to retry" works after the retries give up.
- **Five new unit tests** cover the classification and backoff. All 14 coordinator tests pass.

**Still needs a device test:** a phone call, a navigation prompt, and a Bluetooth disconnect/reconnect, both in the car and on the phone.

### R2.2 Stale comments
`PlaybackService.kt` still said steering-wheel Next/Previous perform +90 s / −15 s. Corrected (commit `8f45650`).

## Still open

| # | Where | Issue |
|---|---|---|
| R2.3 | `data/v4v/NwcClient.kt:86-180` | **The NWC client can't work.** NIP-47 needs a signed Nostr event: `id` = sha256 of the serialized event, `pubkey` = the *client* key derived from the connection secret, and a Schnorr `sig`, with `content` NIP-04-encrypted to the wallet. This code sends a random UUID as the `id`, the *wallet's* pubkey as the sender, no signature, and plain-text JSON. The secret is never used. Relays and wallets will reject the request, and every boost will time out after 30 s. The wallet's (encrypted) reply also wouldn't parse. The feature flag keeps it out of release builds, but it's **on in debug builds**, which is what gets sideloaded to testers. Either implement it with a secp256k1/NIP-04 library or remove the UI. The ADR's "encrypted Nostr relay connection" claim is false today. |
| R2.4 | `data/v4v/NwcKeyStorage.kt:70-76` | If the Keystore fails, the wallet secret is saved in **plain text**. It should fail instead. (It is excluded from backups, which is good.) |
| R2.5 | `data/download/PodcastDownloadManager.kt:157-210` | Partial downloads resume with `Range` but no `If-Range`/ETag check. Hosts with dynamic ad insertion (Megaphone, Art19, Acast, Omny) serve different bytes per request, so a resumed file can be corrupt. R2.1 now streams instead of dismissing when that happens, but the resume should still be validated. |
| R2.6 | `domain/QueueEngine.kt:101-124` | **Behavior change to confirm:** Evergreen now plays new releases newest-first and then the back catalog **oldest-first**, so subscribing to a show with 300 episodes queues the newest one and then episode 1. The in-app description was updated to match, so it's intentional, but it's a product decision only you can make. |
| R2.7 | `data/sync/SyncSerializer.kt:710-730` | Manual queue items have no tombstones. If one device removes a pinned item while another still has it, the next sync puts it back. |
| R2.9 | `public/privacy-policy.html` | Still says the Drive backup is "**encrypted**" (it's plain JSON), and still doesn't disclose Apple iTunes Search. |
| R2.11 | docs | README, both website-update files, and ALPHA_TESTERS still contain some round-1 overclaims (word-by-word transcripts, "Certified", WebLN, DoggCatcher). The Eager to be Healthy feed host is `api.riverside.com` in the app (it matches the feed's own self-link) but `api.riverside.fm` in the website copy. Use whichever one resolves, in both places. |
| R2.12 | `PlaybackService.kt` `markEpisodeFinished` path | Every 10 s save at ≥98% calls `markEpisodeFinished`, which rebuilds the queue, runs Drive sync, and enqueues downloads each time. It also deletes the local file while the last 2% is still playing. Harmless now that R2.1 falls back to streaming, but it's wasted work. Mark finished once, on transition or end. |

## Deferred: web player (not in the MVP)

The web player (`public/`, `server.js`) is outside the Android MVP. These items wait for the desktop work:

| # | Where | Issue |
|---|---|---|
| R2.8 | `server.js:60-85` | The SSRF check validates only the first DNS lookup. Redirects (up to 5 are followed) and DNS rebinding can still reach internal addresses. Fine on localhost, but don't host it. |
| R2.10 | `public/transcriptParser.js` | 3 web tests fail: speaker turns on consecutive lines get merged into one cue. |

**Desktop and mobile sync, later:** the Android sync format (`SyncSerializer`, v2 JSON in the Drive folder) is what the desktop client should read and write, rather than a second format. Fix R2.7 (no tombstones for manual queue items) before then, because a second client makes reappearing items more likely. `privacy-policy.html` lives in `public/` but is part of the Android Play listing, so R2.9 stays in scope.

## Correction to round 1

1.10 (the "wrong" Eager to be Healthy feed URL) was itself partly wrong. The Riverside feed's own `atom:link rel="self"` is `https://api.riverside.com/hosting/drlxDc7x.rss`, which is what the app uses now.
