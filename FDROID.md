# 🤖 F-Droid Inclusion & Build Setup — Eager to Listen

This document details the configuration, build recipe, and submission procedure for distributing **Eager to Listen** on **F-Droid**, the premier free and open-source Android app repository.

---

## 📋 Compliance & FLOSS Verification

Eager to Listen is designed from the ground up to comply with [F-Droid's Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/):

1. **100% Free & Open Source Software (FLOSS)**:
   - All app dependencies are permissively licensed open-source libraries (Apache 2.0 / MIT).
2. **Zero Proprietary Google Play Services (GMS)**:
   - Does **not** require Google Play Services, Firebase, proprietary tracking SDKs, or closed-source binary blobs.
   - Cloud sync uses standard Android Storage Access Framework (SAF) DocumentFile access to the user's private Google Drive folder without closed-source SDKs.
3. **Reproducible Gradle Build**:
   - Compiles cleanly offline via `./gradlew assembleRelease` without non-standard native pre-builts.
4. **No Anti-Features**:
   - No advertising (`no-ads`), no tracking (`no-tracking`), no non-free network services required.

---

## 📁 Repository Structure for F-Droid

The following files and structures are configured in this repository:

### 1. F-Droid Metadata Recipe (`metadata/com.eagertolisten.app.yml`)
Contains the official build definition for the `fdroiddata` repository:
```yaml
Categories:
  - Multimedia
  - Internet
License: MIT
AuthorName: Michael Scott Eger
AuthorEmail: michael.eger@michaelegerconsulting.com
AuthorWebSite: https://eagertobehealthy.com
WebSite: https://eagertobehealthy.com/eager-to-listen
SourceCode: https://github.com/michaeleger/eager-to-listen
IssueTracker: https://github.com/michaeleger/eager-to-listen/issues
Changelog: https://github.com/michaeleger/eager-to-listen/blob/main/CHANGELOG.md

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 0.9.3-alpha
CurrentVersionCode: 12

Builds:
  - versionName: 0.9.3-alpha
    versionCode: 12
    commit: d33a8bba77b913686bc900d66ebcb556e86caf30
    subdir: android
    gradle:
      - yes
    output: app/build/outputs/apk/release/app-release-unsigned.apk
```

### 2. Fastlane Metadata (`fastlane/metadata/android/`)
F-Droid and Google Play automatically ingest app store descriptions, titles, icons, and localized changelogs from this standardized directory:
```text
fastlane/metadata/android/
└── en-US/
    ├── title.txt                     # App Title
    ├── short_description.txt         # 80-char Summary
    ├── full_description.txt          # Rich Markdown Store Listing
    ├── changelogs/
    │   └── 12.txt                    # Release notes for versionCode 12 (0.9.3-alpha)
    └── images/
        ├── icon.png                  # App icon
        └── phoneScreenshots/         # Showcase screenshots
            ├── 1_add_show.png
            ├── 2_queue_priority.png
            └── 3_show_detail.png
```

---

## 🛠️ How to Verify the Build Locally

To test the build recipe locally using the exact command F-Droid's builder executes:

```powershell
cd android
.\gradlew.bat assembleRelease
```

The resulting unsigned release APK will be located at:
`android/app/build/outputs/apk/release/app-release-unsigned.apk`

---

## 🚀 Submitting to the Official F-Droid Repository

To have Eager to Listen indexed and published on the official F-Droid catalog:

1. **Fork the F-Droid Data Repository**:
   - Fork [gitlab.com/fdroid/fdroiddata](https://gitlab.com/fdroid/fdroiddata).
2. **Add Metadata Recipe**:
   - Copy `metadata/com.eagertolisten.app.yml` into `metadata/com.eagertolisten.app.yml` in your forked `fdroiddata` repository.
3. **Test with `fdroid` CLI (optional)**:
   ```bash
   fdroid checkupdates com.eagertolisten.app
   fdroid lint com.eagertolisten.app
   fdroid build -v -s com.eagertolisten.app
   ```
4. **Open a Merge Request**:
   - Submit a Merge Request to `fdroid/fdroiddata` with title:
     `New App: com.eagertolisten.app (Eager to Listen)`
   - The F-Droid automated CI will verify the build and inclusion criteria.

---

## 🌐 Alternative: Hosting a Custom F-Droid Repository

If you prefer to host an independent F-Droid repository on your own domain (e.g. `repo.eagertobehealthy.com/fdroid`):

1. Install `fdroidserver`:
   ```bash
   pip install fdroidserver
   ```
2. Initialize repository directory:
   ```bash
   mkdir fdroid-repo && cd fdroid-repo
   fdroid init
   ```
3. Drop signed release APKs into `repo/` and run:
   ```bash
   fdroid update -c
   ```
4. Serve the `repo/` directory over HTTPS. Users can add the custom repository URL directly in their F-Droid client app.
