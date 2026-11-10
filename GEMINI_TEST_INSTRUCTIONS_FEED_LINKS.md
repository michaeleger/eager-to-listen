# Test instructions for Gemini: podcast/RSS links and show search

What changed (commit `04a278b`, see CHANGELOG "Adding shows" and "Show search"):

1. **Links open the app.** `feed://`, `podcast://`, `pcast://`, `itpc://`, RSS/Atom links, "Subscribe on
   Android" links, Apple Podcasts and Overcast show links, a show's website, links shared to the app,
   links pasted into search, and feed files opened from Downloads/Files all open an **Add show** sheet:
   artwork, episode count, latest episode date, and priority **P2** selected. Nothing subscribes until
   **Subscribe** is tapped.
2. **Search asks Podcast Index and Apple Podcasts at the same time** and merges the results. Before,
   Apple was only asked when Podcast Index found nothing, so new shows were missing. Results now show
   the latest episode date and the source.

None of the new Android code has been compiled yet. Step 1 is the first build.

## Ground rules

- **Emulator only** (`Pixel_10`). Do not install on or touch Michael's phone.
- Do not change anything in the repo except to report results. If a build or test fails, stop and
  report the error text; do not try to fix the code.
- Commands below are PowerShell on the Windows PC, run from the repo root unless they say otherwise.
- Take a screenshot for every check that involves the screen (make the `shots` folder first, name them by step number):
  `adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png shots\NN-name.png`
  Don't use `adb exec-out screencap -p > file` in PowerShell: it saves the image as text and breaks it.
- For any FAIL, attach the log:
  `adb logcat -d | Select-String -Pattern "eagertolisten|PodcastIndexClient|PodcastRepository|AndroidRuntime" | Select-Object -Last 80`

## 1. Build and unit tests

```powershell
git switch main
git pull
git log --oneline -1            # must show 04a278b or later
cd desktop; npm install; npm test; cd ..
cd android
.\gradlew.bat testDebugUnitTest assembleDebug
```

Expect: desktop `# pass 68`, `# fail 0`. Android BUILD SUCCESSFUL. Confirm these Android test classes ran
and passed (reports are in `android\app\build\reports\tests\testDebugUnitTest\index.html`):
`FeedLinkTest`, `ShowSearchTest`, `AppInfoTest`, `CrashReporterTest`, `PlaybackCoordinatorTest`.

## 2. Database upgrade check (still outstanding from 0.9.3)

With the emulator running:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

Expect `MigrationTest` passes, including `migrate7To8`. Then install the new build over the old one:

1. `adb uninstall com.eagertolisten.app`
2. `git stash -u` if anything is uncommitted, `git checkout c124225`, `.\gradlew.bat assembleDebug`,
   `adb install app\build\outputs\apk\debug\app-debug.apk` (this is 0.9.3, database version 7).
3. In the app: subscribe to one show, play an episode for a minute, save a bookmark (star in the player).
4. `git checkout main`, `.\gradlew.bat assembleDebug`, `adb install -r app\build\outputs\apk\debug\app-debug.apk` (no uninstall).
5. Expect: the show, the playback position and the bookmark are all still there, no crash, and
   Settings → About shows database version 8.

Keep this install for the rest of the tests.

## 3. Links open Add show

For each link, run the command, wait about 5 seconds, screenshot, then close the sheet (Not now)
unless the step says otherwise. `-p com.eagertolisten.app` makes Android send the link to this app
even where it would normally ask which app to use.

| # | Command | Expect |
|---|---------|--------|
| 3.1 | `adb shell am start -a android.intent.action.VIEW -d "feed://feeds.megaphone.fm/hubermanlab" -p com.eagertolisten.app` | Add show sheet: **Huberman Lab**, artwork, episode count, "latest" date, **P2 selected** |
| 3.2 | `adb shell am start -a android.intent.action.VIEW -d "pcast://feeds.megaphone.fm/hubermanlab" -p com.eagertolisten.app` | Same as 3.1 |
| 3.3 | `adb shell am start -a android.intent.action.VIEW -d "itpc://feeds.noagendaassets.com/noagenda.xml" -p com.eagertolisten.app` | Sheet for **No Agenda Show** |
| 3.4 | `adb shell am start -a android.intent.action.VIEW -d "feed:https://feeds.podcastindex.org/pc20.xml" -p com.eagertolisten.app` | Sheet for **Podcasting 2.0** |
| 3.5 | `adb shell am start -a android.intent.action.VIEW -d "https://podcasts.apple.com/us/podcast/huberman-lab/id1545953110" -p com.eagertolisten.app` | Sheet for **Huberman Lab** (found through Apple) |
| 3.6 | `adb shell am start -a android.intent.action.VIEW -d "https://subscribeonandroid.com/feeds.megaphone.fm/hubermanlab" -p com.eagertolisten.app` | Sheet for **Huberman Lab** |
| 3.7 | `adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "'Listen to this: https://peterattiadrive.libsyn.com/rss'" -n com.eagertolisten.app/.AddShowShareTarget` | Sheet for **The Peter Attia Drive** (link found inside the text) |
| 3.8 | `adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "'just some words'" -n com.eagertolisten.app/.AddShowShareTarget` | No sheet; a message "That doesn't look like a podcast link" |
| 3.9 | `adb shell am start -a android.intent.action.VIEW -d "feed://example.com/not-a-feed" -p com.eagertolisten.app` | Sheet shows an error message and **Try again** / **Close**; no crash |

3.10 **Share menu.** Open Chrome on the emulator, go to `https://feeds.megaphone.fm/hubermanlab`, use
Chrome's menu → Share, and confirm **Add show to Eager to Listen** is in the share list. Pick it.
Expect the 3.1 sheet.

3.11 **Feed file.** Download a feed and open it as a file:
```powershell
curl.exe -L -o hubermanlab.rss https://feeds.megaphone.fm/hubermanlab
adb push hubermanlab.rss /sdcard/Download/hubermanlab.rss
```
Open the emulator's **Files** app → Downloads → tap `hubermanlab.rss`. Expect **Eager to Listen** to be
offered (or to open directly), then the 3.1 sheet. If Files offers no app, note which app it opened and
whether the file showed as type RSS. Report it as a note, not a failure.

3.12 **Paste into search.** In the app, Discover/Add, paste `https://feeds.megaphone.fm/hubermanlab`
into the search box and search. Expect the 3.1 sheet instead of search results. Also paste it into the
**Add by link** box and tap the button: same sheet.

## 4. Subscribing from the sheet

4.1 Run 3.1 again, leave **P2**, tap **Subscribe**. Expect "Subscribed to Huberman Lab at priority 2",
the sheet closes, the show is in your shows, and its settings show priority 2.

4.2 Run 3.3, tap **P4**, tap **Subscribe**. Expect No Agenda subscribed at priority 4.

4.3 Run 3.1 again (already subscribed). Expect "You already follow this show." and only a **Close** button.

4.4 Unfollow Huberman Lab in the app, then run 3.1. Expect **Follow again**, no priority buttons, and the
note that history and settings are kept "(priority 2)". Tap it. Expect "Following Huberman Lab again" and
its priority is still 2.

4.5 Run 3.4, then press Back while it says "Reading the feed…". Expect the sheet closes and nothing is subscribed.

4.6 Rotate the emulator while a sheet is open. Expect the sheet stays, or closes cleanly. It must not
open twice or subscribe on its own.

## 5. Search

Ask Michael for the Podcast Index key and secret, or use what's already in Settings if present.

5.1 **With the key.** Search `huberman`. Expect Huberman Lab near the top, listed **once**, with a
"Latest <date>" line and a source (Podcast Index or Apple Podcasts).

5.2 **New show.** Find a podcast that launched in the last 60 days. Apple Podcasts' "New & Noteworthy"
or a news story about a new show is fine; write down its name. Search for it. Expect it in the results.
Before this change it was usually missing when Podcast Index didn't have it yet.

5.3 Search `eager to be healthy`. Expect **Eager to Be Healthy** on top, once.

5.4 **Without the key.** Clear the Podcast Index key in Settings, search `huberman`. Expect results (Apple only).

5.5 **Wrong key.** Put `bad` in both key and secret, search `huberman`. Expect results (Apple). The log
should contain `Podcast Index search failed: HTTP 401`. Restore the real key afterwards.

5.6 **Offline.** `adb shell cmd connectivity airplane-mode enable`, search `huberman`. Expect "No shows found.
Check your connection…" and no crash. Then `adb shell cmd connectivity airplane-mode disable`.

5.7 **Categories.** Tap two category buttons (for example News and Health & Fitness). Expect results in both,
with No Agenda pinned on News and Eager to Be Healthy pinned on Health.

## 6. Quick regression

6.1 Play an episode from the queue for 30 seconds, pause, close the app, reopen. Expect it resumes there.

6.2 `adb shell monkey -p com.eagertolisten.app --throttle 200 1500`. Expect no crash (the monkey run ends
with `Events injected: 1500`).

## Report

Reply with:

1. A table: step number, PASS / FAIL / NOTE, one line on what you saw.
2. For each FAIL: the screenshot name and the log excerpt.
3. The show you used for 5.2, and whether it showed up.
4. The build and test summary from step 1 and the migration result from step 2.
