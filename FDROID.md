# Eager to Listen on F-Droid

Where things stand and what's left to do. The recipe F-Droid builds from is
[`fdroid/com.eagertolisten.app.yml`](fdroid/com.eagertolisten.app.yml); the store listing (text, icon,
screenshots, per-version changelog) is in [`fastlane/metadata/android/en-US/`](fastlane/metadata/android/en-US/),
which F-Droid reads straight from this repo.

## Checked

- **License:** GPL-3.0-only ([LICENSE](LICENSE)). Every library the app uses is Apache-2.0 or MIT,
  which can be combined with GPL-3.0.
- **No Google Play Services, Firebase or other closed libraries.** The `com.google.android.gms.car...`
  and `...actions.SEARCH_ACTION` entries in the manifest are plain names Android Auto and Google
  Assistant look for, not libraries. AntennaPod has the same ones.
- **Build cleanup:** the unused `google-services` Gradle plugin and the `foojay` toolchain plugin were
  removed, and `dependenciesInfo` is off, so the APK has no Google-encrypted dependency list.
- **`fdroid scanner`** (fdroidserver 2.4.5) on the source: 0 problems. **`fdroid lint`**: clean.
  **`fdroid rewritemeta`**: recipe already in canonical form.
- **Network services:** Podcast Index (open). Apple Podcasts search is optional: Settings → Podcast
  Index → *Also search Apple Podcasts* (on by default). Off, the app never contacts Apple. If a reviewer
  still wants the `NonFreeNet` anti-feature, add it to the recipe; it's a label, not a rejection.
- **Sync** goes through a folder the user picks with Android's own file picker (Google Drive's
  storage provider, or any other). No Google SDK, no developer keys, no server of ours.
- **Crash reports** are only sent if the user chooses to email one. No tracking.

## Still to do

1. **Build the release the way F-Droid will**, on the Windows PC, with no `keystore.properties` present
   (rename it for the test): `cd android; .\gradlew.bat clean assembleRelease`. It must produce
   `app\build\outputs\apk\release\app-release-unsigned.apk`. Install it on the emulator and open it.
2. **Screenshots** (3 to 6, real app screens) into
   `fastlane/metadata/android/en-US/images/phoneScreenshots/` as `1_queue.png`, `2_player.png`, and
   so on. Take them with `adb shell screencap -p /sdcard/s.png` then `adb pull /sdcard/s.png <name>.png`.
   Don't use `adb exec-out screencap -p > file` in PowerShell: it saves the image as text and breaks it.
   No real Podcast Index key or personal details on screen.
3. **Tag the release** once 1 and 2 are done and committed:
   `git tag -a v0.9.4-alpha -m "0.9.4-alpha"` and `git push origin v0.9.4-alpha`.
4. **Put the tag's full commit hash in the recipe**: `git rev-list -n1 v0.9.4-alpha`, replace
   `REPLACE_WITH_FULL_COMMIT_HASH_OF_v0.9.4-alpha`, commit, push.
5. **Submit to F-Droid** (needs Michael's GitLab account):
   1. Sign in at gitlab.com and fork <https://gitlab.com/fdroid/fdroiddata>.
   2. In the fork, add `metadata/com.eagertolisten.app.yml` with the contents of our recipe.
   3. Optional check, on a Linux box with fdroidserver and the Android SDK:
      `fdroid lint com.eagertolisten.app`, `fdroid build -v -l com.eagertolisten.app`.
   4. Open a merge request to `fdroid/fdroiddata` titled **New app: Eager to Listen
      (com.eagertolisten.app)** and fill in the "new app" template's checklist. Their CI builds it;
      reviewers may ask for changes in the MR. Approval usually takes days to a few weeks.

## Things to know

- **F-Droid signs the app with its own key.** The F-Droid copy and the APK from eagertobehealthy.com
  can't update each other: switching means uninstalling, which wipes the app's data unless you sync or
  export first. Later we can set up reproducible builds so F-Droid ships the APK signed with our key.
- **New versions:** with `UpdateCheckMode: Tags` and `AutoUpdateMode: Version`, F-Droid picks up a new
  release on its own when a new `v*` tag is pushed and `versionCode`/`versionName` in
  `android/app/build.gradle` match it. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`
  (500 characters max) before tagging.
- **Store text** lives in the fastlane folder, not the recipe. Short description: 80 characters max.
