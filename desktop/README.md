# Eager to Listen for the desktop (Linux first, Windows later)

Alpha. Built with [Tauri 2](https://tauri.app): a small native app with the UI in plain HTML/JS.
It shares your shows, queue, progress and bookmarks with the Android app through the same
`eagertolisten_sync.json` the phone keeps in your Google Drive sync folder.

## What's in here

```
desktop/
├── src/                     # The app window (no build step: plain ES modules)
│   ├── index.html, styles.css, app.js
│   └── core/                # Shared rules: ports of the Android code, kept rule-for-rule identical
│       ├── feedUrl.js       #   FeedUrl.kt + EpisodeId.kt (IDs must match or progress won't line up)
│       ├── feedParser.js    #   parser/FeedParser.kt
│       ├── queueEngine.js   #   domain/QueueEngine.kt (fair interleaving, 4 play orders)
│       ├── library.js       #   DAO + PodcastRepository behavior (finish, skip, pins, rebuild)
│       ├── sync.js          #   data/sync/SyncSerializer.kt (format 2 parse/serialize/merge)
│       └── syncRunner.js    #   SyncManager.performSync (merge all copies, write one, clean up)
├── src-tauri/               # Native side (Rust): saves the library, fetches feeds,
│   └── src/lib.rs           #   reads/writes the sync file via rclone or a folder, downloads
└── test/                    # node --test, includes the Android tests ported to JS
```

**If you change a sync or queue rule on Android, change the matching file in `src/core/` in the
same commit.** The tests in `test/` are ported from the Kotlin tests, so a rule that drifts on one
side shows up as a failing test once the other side is updated.

## Phone sync on Linux (rclone)

Linux has no Google Drive app, so the desktop reaches your Drive folder through
[rclone](https://rclone.org). One-time setup:

```bash
sudo apt install rclone          # Ubuntu/Debian; or see rclone.org/install
rclone config                    # n (new remote) -> name: gdrive -> storage: drive -> follow the prompts
rclone lsf gdrive:               # should list your Drive's top folders
```

Then in the app: **Settings → Phone sync → Sync folder** = `gdrive:<the folder you picked on the
phone>`, for example `gdrive:EagerToListen`. Folder names with spaces work as typed.

A plain folder path (for example a folder that some other tool already mirrors from Drive) works
too. The app uses the phone's exact steps: it merges every `eagertolisten_sync*.json` copy, writes
`.tmp.json`, reads it back, keeps a `.bak.json`, then writes the main file and removes the
conflict copies.

## Run it from source

Needs Node 18+, Rust (rustup), and the WebKitGTK build libraries:

```bash
sudo apt install libwebkit2gtk-4.1-dev libsoup-3.0-dev libjavascriptcoregtk-4.1-dev \
  librsvg2-dev libayatana-appindicator3-dev build-essential pkg-config \
  gstreamer1.0-plugins-good gstreamer1.0-plugins-bad gstreamer1.0-libav
cd desktop
npm install
npm test          # shared-rule tests (JS)
npm run dev       # opens the app
npm run build     # .deb + AppImage in src-tauri/target/release/bundle/
```

The `gstreamer1.0-*` packages are what WebKitGTK uses to play MP3/AAC. Without them episodes
won't play. The `.deb` lists them as dependencies, so `sudo apt install ./Eager*.deb` pulls them in.

Your library lives in `~/.local/share/com.eagertolisten.desktop/` (`library.json`, a `.bak`
copy, and `downloads/`).

## Windows (later)

The same code builds an installer with `npm run build -- --bundles nsis` on a Windows machine
(with Rust, Node and the WebView2 runtime, which Windows 11 already has). For sync, point the sync
folder at your Google Drive for Desktop folder (for example `G:\My Drive\EagerToListen`), or use
rclone for Windows. Installers are unsigned for now, so Windows shows "Windows protected your PC"
once: **More info → Run anyway**.

## Not in this alpha yet

- Transcripts and chapters (the parsers exist in `public/`, not wired in yet)
- Importing OPML or Podcast Addict / AntennaPod backups on the desktop (sync from the phone instead)
- Local folder shows (they're device-only on the phone too)
- Auto-download, auto-update, signed Windows installer, Google sign-in for testers without rclone
- Media keys / desktop media controls depend on WebKitGTK's MediaSession support; the in-app
  keys work everywhere: Space play/pause, Left rewind, Right forward
