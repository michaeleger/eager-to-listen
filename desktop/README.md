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

## Install on Linux without sudo (AppImage)

Test builds ship as a single AppImage. It carries its own WebKit and audio codecs, so nothing
needs installing system-wide and no sudo is needed.

```bash
mkdir -p ~/Apps && mv ~/Downloads/Eager*.AppImage ~/Apps/
chmod +x ~/Apps/Eager*.AppImage
~/Apps/Eager*.AppImage
# If it says FUSE is missing (common on locked-down boxes), run it this way instead:
~/Apps/Eager*.AppImage --appimage-extract-and-run
```

Your library lives in `~/.local/share/com.eagertolisten.desktop/` (`library.json`, a `.bak`
copy, and `downloads/`).

## Phone sync on Linux (rclone, no sudo)

Linux has no Google Drive app, so the desktop reaches your Drive through
[rclone](https://rclone.org). rclone is a single program; it can live in your home folder:

```bash
mkdir -p ~/.local/bin && cd /tmp
curl -LO https://downloads.rclone.org/rclone-current-linux-amd64.zip
python3 -m zipfile -e rclone-current-linux-amd64.zip .
cp rclone-*-linux-amd64/rclone ~/.local/bin/ && chmod +x ~/.local/bin/rclone
~/.local/bin/rclone config     # n (new remote) -> any name -> storage: drive -> follow the prompts
```

The app finds rclone in `~/.local/bin` even when that folder isn't on the PATH. The remote can
have any name; nothing in the app assumes one. (Signing in opens a browser on the same machine;
over remote desktop that works. On a box with no browser at all, answer "n" to auto config and
follow rclone's instructions to authorize from another computer.)

Then in the app, **Settings → Phone sync → Find my sync folder**. It searches your Drive for the
phone's `eagertolisten_sync.json`, wherever you saved it, and lists the folders that have it,
newest first. Pick one. You can also **Browse** your Drive and pick a folder by hand (for example
before the phone has synced even once), or type the folder in yourself as `remote:Path/To/Folder`.
A plain folder path works too, if some other tool already mirrors that Drive folder locally.

The app uses the phone's exact steps when it syncs: it merges every `eagertolisten_sync*.json`
copy, writes `.tmp.json`, reads it back, keeps a `.bak.json`, then writes the main file and removes
the conflict copies.

## Build from source

This needs the WebKitGTK development libraries, which do need sudo, so build on a machine where
you have it (or in the cloud workspace) and copy the AppImage over:

```bash
sudo apt install libwebkit2gtk-4.1-dev libsoup-3.0-dev libjavascriptcoregtk-4.1-dev \
  librsvg2-dev libayatana-appindicator3-dev build-essential pkg-config \
  gstreamer1.0-plugins-good gstreamer1.0-plugins-bad gstreamer1.0-libav
cd desktop
npm install
npm test             # shared-rule tests (JS); no system libraries needed for these
npm run dev          # opens the app
npm run build        # AppImage in src-tauri/target/release/bundle/appimage/
npm run build:deb    # a .deb, for machines where you can install packages
```

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
