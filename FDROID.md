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

## Checklist from here

**1. Gemini (Windows PC)** — follow [GEMINI_FDROID_INSTRUCTIONS.md](GEMINI_FDROID_INSTRUCTIONS.md):
- pull `main`, run the tests
- build unsigned the way F-Droid will; check the Apple Podcasts switch on the emulator
- take 6 store screenshots, commit and push them
- tag `v0.9.4-alpha` and push the tag
- put the tag's full commit hash in `fdroid/com.eagertolisten.app.yml`, commit and push

**2. Michael (one time)**
1. Sign in to gitlab.com (free account).
2. Fork <https://gitlab.com/fdroid/fdroiddata>. This forks F-Droid's recipe collection, not the app:
   the app's code stays on GitHub and F-Droid builds it from there.
3. In the fork, add `metadata/com.eagertolisten.app.yml`: a copy of our
   `fdroid/com.eagertolisten.app.yml`, with the hash filled in.
4. Open a merge request to `fdroid/fdroiddata` titled
   **New app: Eager to Listen (com.eagertolisten.app)** and fill in its template checklist.
5. Answer reviewer questions in the merge request.
6. Once it's merged, the fork can be deleted. The app shows up on F-Droid a few days later.

**3. Every release after that**
1. `android/app/build.gradle`: `versionCode` + 1, new `versionName`.
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (500 characters max).
3. Commit, tag `v<versionName>` (for example `v0.9.5-alpha`), push the tag.
4. F-Droid picks it up by itself, usually within 2 to 7 days. Nothing to trigger.
5. It skips the release if the tag doesn't match `versionName`, `versionCode` didn't go up, or the
   build fails on their server. For the first few releases, check <https://monitor.f-droid.org> that
   the build went through; if it failed, fix it and push a new tag.

## Things to know

- **F-Droid signs the app with its own key.** The F-Droid copy and the APK from eagertobehealthy.com
  can't update each other: switching means uninstalling, which wipes the app's data unless you sync or
  export first. Later we can set up reproducible builds so F-Droid ships the APK signed with our key.
- **Store text** lives in the fastlane folder, not the recipe. Short description: 80 characters max.
