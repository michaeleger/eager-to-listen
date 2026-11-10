# Instructions for Gemini: get Eager to Listen 0.9.4-alpha ready for F-Droid

Read `FDROID.md` first for the background. Your job is steps 1 to 4 of its "Still to do" list on the
Windows PC. Michael submits to F-Droid himself (step 5).

## Ground rules

- Emulator only (`Pixel_10`). Don't install anything on Michael's phone.
- If a build or test fails, stop and report the error text. Don't change app code.
- Screenshots: `adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png <file>.png`.
  Never `adb exec-out screencap -p > file` in PowerShell: it breaks the image.
- Don't create the tag until every check in steps 1 to 3 passes.

## 1. Pull and test

```powershell
git switch main
git pull
cd desktop; npm install; npm test; cd ..
cd android
.\gradlew.bat clean testDebugUnitTest
```

Expect desktop `# fail 0` and Android BUILD SUCCESSFUL.

## 2. Build it the way F-Droid will

F-Droid builds without our signing key. Move the key file aside for this build only:

```powershell
if (Test-Path keystore.properties) { Rename-Item keystore.properties keystore.properties.off }
.\gradlew.bat clean assembleRelease
Get-ChildItem app\build\outputs\apk\release\
if (Test-Path keystore.properties.off) { Rename-Item keystore.properties.off keystore.properties }
```

Expect BUILD SUCCESSFUL and `app-release-unsigned.apk`. An unsigned APK won't install, so check the app
with the debug build: `.\gradlew.bat assembleDebug` and
`adb install -r app\build\outputs\apk\debug\app-debug.apk`.

Then on the emulator:

1. Settings → About shows **0.9.4-alpha** (build 13).
2. Settings → Podcast Index card has a switch **Also search Apple Podcasts**, on.
3. With the switch **on** and no Podcast Index key: search `huberman` gives results.
4. Switch **off**, no key: search `huberman` shows "Search has nowhere to look…". No crash.
5. Switch **off**, with Michael's key entered: search `huberman` gives results.
6. Switch **off**: `adb shell am start -a android.intent.action.VIEW -d "https://podcasts.apple.com/us/podcast/huberman-lab/id1545953110" -p com.eagertolisten.app`
   shows a message that Apple Podcasts search is off. Turn the switch back **on** and run it again:
   the Add show sheet for Huberman Lab appears.
7. Remove the key again before the screenshots.

## 3. Screenshots for the store listing

Set the emulator up so it looks lived-in: subscribe to 5 or 6 shows (Huberman Lab, No Agenda,
Podcasting 2.0, The Peter Attia Drive, Eager to Be Healthy, plus one news show), give them different
priorities, and play a minute of an episode that has a transcript (Podcasting 2.0 episodes do).
No API keys, emails or anything personal on screen.

Take these, portrait, and save them in `fastlane\metadata\android\en-US\images\phoneScreenshots\`:

| File | Screen |
|------|--------|
| `1_queue.png` | Queue (Radio U) with several shows mixed |
| `2_player.png` | Full player with artwork and controls |
| `3_transcript.png` | Player with the transcript open, following along |
| `4_add_show.png` | Add show sheet (from a `feed://` link, see GEMINI_TEST_INSTRUCTIONS_FEED_LINKS.md 3.1) with P2 picked |
| `5_show.png` | One show's page or its settings (priority, play order, speed) |
| `6_studio.png` | Creator Inspiration Studio with two or three bookmarks |

Open each saved file to make sure it shows the image. Then:

```powershell
git add fastlane\metadata\android\en-US\images\phoneScreenshots
git commit -m "F-Droid: store screenshots"
git push
```

## 4. Tag and finish the recipe

Only if steps 1 to 3 all passed:

```powershell
git tag -a v0.9.4-alpha -m "0.9.4-alpha"
git push origin v0.9.4-alpha
git rev-list -n1 v0.9.4-alpha
```

In `fdroid\com.eagertolisten.app.yml`, replace `REPLACE_WITH_FULL_COMMIT_HASH_OF_v0.9.4-alpha` with that
full 40-character hash. Then:

```powershell
git add fdroid\com.eagertolisten.app.yml
git commit -m "F-Droid: recipe builds v0.9.4-alpha"
git push
```

## Report

1. Step 1 and 2 results (pass/fail, error text if any).
2. Checks 2.1 to 2.6: PASS / FAIL with one line each.
3. The list of screenshots committed.
4. The tag hash you put in the recipe.

## 5. Re-tagging 0.9.4-alpha after later changes

0.9.4-alpha hasn't shipped, and more went into it after the first tag (audit fixes, faster sync).
F-Droid builds exactly the commit the tag names, so the tag has to move to the final commit.
Do this only after the branch with those changes is merged to `main` and steps 1 and 2 pass again
(`.\gradlew.bat clean testDebugUnitTest`, then the unsigned `assembleRelease`).

Extra checks on the emulator for this round:

1. Sync twice in a row with the Sync button, then leave the app and come back twice. Settings → sync
   status should read **Already up to date** after the second time away.
2. Play an episode for a minute, leave the app, come back: the status shows a normal "Synced…" line.
3. Download an episode, play it, lock the screen for 3 minutes while streaming a different one:
   playback keeps going.

Then move the tag and update the recipe:

```powershell
git switch main
git pull
git tag -d v0.9.4-alpha
git push origin :refs/tags/v0.9.4-alpha
git tag -a v0.9.4-alpha -m "0.9.4-alpha"
git push origin v0.9.4-alpha
git rev-list -n1 v0.9.4-alpha
```

Put that new 40-character hash in `fdroid\com.eagertolisten.app.yml` (the `commit:` line), commit and
push. If the F-Droid merge request is already open, the same hash has to be updated in the copy of
the recipe there too: tell Michael.
