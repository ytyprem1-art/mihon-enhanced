<div align="center">
  <img src=".github/assets/logo.png" alt="Mihon Enhanced" width="80">

# Mihon Enhanced

**Read, organize, and share — your Mihon, with a few extra tools.**

[Releases](https://github.com/ytyprem1-art/mihon-enhanced/releases) · [Issues](https://github.com/ytyprem1-art/mihon-enhanced/issues) · [Original Mihon](https://github.com/mihonapp/mihon) · [Previous History Category Mod](https://github.com/ytyprem1-art/mihonHistory-category)

</div>

## About

Mihon Enhanced is an unofficial fork of [Mihon](https://github.com/mihonapp/mihon), based on **Mihon 0.21.2**. It started as **Mihon History Category Mod**, a personal project for organizing reading history, and grew to include source management, reader tools, chapter comments, optional Firebase chat, and an independent updater.

This is not an official Mihon release. Report Enhanced-specific issues here rather than to the original Mihon team. **Android 8.0 or newer** is required.

## Features

### Organize your reading

- **History Categories:** create, rename, delete, and reorder categories independently of Library; multi-select entries, move/delete them in batches, and keep each tab's scroll position.
- **Automatic source categories:** link sources to History categories for automatic categorization, while keeping manual organization available.
- **History Groups:** manually group related history entries.
- **Updates Source Categories:** separate the built-in Updates feed by linked sources. Existing and future updates from those sources appear automatically. Category names, order, and source links are backed up.
- **Personal Favorites:** star comics and see their marks in Library, History, and Browse.
- **Browse Library Appearance:** bright library covers, a colored outline, and an In Library badge while browsing sources; the original appearance remains available.
- **Custom Grid Columns:** choose Auto, 2, 3, or 4 cover columns.
- **External Bookmark Import:** import supported external bookmark data.

### Sources and chapter updates

- **Linked Sources and Quick Source Switcher:** connect equivalent entries across sources and switch between them from manga-related screens.
- **Merge Chapters:** combine chapter lists from multiple sources, with per-source chapter/download controls.
- **Update Watch:** adaptive refresh, Auto Refresh controls, notifications, and an inbox/history. Dismissed chapter updates stay cleared until newer chapters arrive.
- **History Migration and Reconciliation:** preserve progress and mod relationships when manga identities change.
- **MangaNato Cloudflare Handling:** challenge handling and temporary trust/cookie reuse.

### Reader and images

- **Panel Crop & Share:** adjustable crop handles, wallpaper ratios, local image filters, PNG saving, and cropped-panel sharing.
- **ImgUpscale 2× / 4×:** on-demand enhancement of a cropped image with a result preview. This online feature depends on its external service.
- **Chapter Comments & Reactions:** a shared reader UI with source-specific handlers, replies, available comment totals, image previews, and chapter reactions where supported. Expanded replies stay open while scrolling. Comments load only when opened.
- Handlers include Asura, Shinigami, Komiku, Komiku Asia, Ikiru, Kiryuu, Voratoon, Doujindesu, MangaDex, and supported HTML/forum backends. Availability, totals, login requirements, and voting depend on the website; not every source exposes every action.

### Optional Global Chat

- Messages, replies, presence, unread indicators, and read receipts.
- Share manga, chapters, and cropped panels; mark shared panels as spoilers.
- Full-screen image previews with zoom. Manga titles open the source-selection flow.
- Supported keyboard GIFs with an optional caption and a preview before sending.
- Profiles with a display name, app version, and an adjustable cropped photo.
- **Next 2.3 additions:** zoomable profile photos, a sharper 768px photo, and a profile dialog that stays open behind the viewer. Existing low-resolution photos need to be selected again once.
- Retains the newest **200 messages** across all types. Client cleanup deletes expired messages and their uploaded media together; cleanup waits if updated clients are offline.

**Forks must configure their own Firebase project.** This repository does not provide a shared public chat server or the maintainer's update-publishing credentials. See the setup guide below.

### Backup, sync, and app updates

- **Extended Backup & Restore:** includes supported mod data: personal favorites, History Categories/source links, History Groups, Updates Source Categories, merged chapters, and Update Watch state.
- **Google Drive Sync:** synchronize library/progress using your own account and a configured OAuth client.
- **Independent In-App Updater:** newer-version prompt and Android notification, changelogs for skipped releases, Google Drive APK downloads with progress, and Android installation confirmation.
- **Settings → Kelola update:** password-protected publishing of a version, public Drive APK link, and release notes. No shared default admin password.
- Telemetry/crash reporting to original Mihon and its official APK updater are disabled for this mod.

## Downloads and version status

Use [Releases](https://github.com/ytyprem1-art/mihon-enhanced/releases) for available downloads. A commit or newer source version does **not** mean a signed release has been published.

- Signed **2.2** was tested by the maintainer, including update notifications and the update flow.
- Current development source: **Mihon Enhanced 2.3**, Android version code **55**.
- 2.3 profile improvements were tested in debug; this README does not announce a 2.3 APK.

Enhanced uses a different package name from official Mihon, allowing installation alongside it. Debug and signed release builds are separate applications. Updating an existing installation without uninstalling requires its original package name/signing key and a higher Android version code.

## Build your own fork

1. Clone the repository and open it in Android Studio compatible with the project's Android Gradle Plugin. Use the Gradle wrapper and check `.github/.java-version` for the CI JDK.
2. Set your Android SDK path in the ignored `local.properties`.
3. Follow **[Firebase setup](mod/firebase/SETUP.md)** and supply your own local `app/google-services.json`. It is deliberately excluded from Git. The current app expects Firebase to be configured before building.
4. For release signing, use your own keystore and ignored `keystore.properties`. Never commit them.
5. Build: `./gradlew :app:assembleDebug` (Windows: `gradlew.bat :app:assembleDebug`).

No maintainer signing key, admin password/hash, service-account private key, or chat database export is included. Public client identifiers in older history are not an invitation to use someone else's backend.

### Firebase chat and update setup

Create your own Firebase project → register your Android package(s) → download your own `google-services.json` → enable **Anonymous Authentication** → create **Firestore** → deploy the supplied rules to **your project** → build.

Read **[the full setup guide](mod/firebase/SETUP.md)** for the rules, admin password, media limits, retention, and security model. Building/installing an APK does not deploy Firebase rules.

These features use **Firestore**, including bounded image/GIF documents, without Cloud Storage or Cloud Functions. Firestore storage/read/write/delete/network quotas still apply.

Anonymous login is **not private group membership**. The supplied rules allow authenticated anonymous participants of the same backend to access shared chat, presence, and photos. Add membership enforcement for an invite-only deployment. IDs differ by device/install: the same display name can appear as multiple profiles.

### Will another fork update my app?

```text
Your signed APK → Your Firebase project → Your release metadata → Your Drive APK
Another fork    → Its Firebase project → Its release metadata  → Its Drive APK
```

Forking, pushing to GitHub, and uploading to Drive do not announce an in-app update. Publishing release metadata to the configured Firebase does. Clients do not scan other forks.

A fork using its **own Firebase** cannot publish updates to your clients. Two builds deliberately using the **same Firebase** share its chat/update feed, so keep independent fork backends separate. Publishing requires that backend's admin password. Downloaded APKs must also match the installed package, signing certificate, expected version, and a newer Android version code. Android still requires installation confirmation.

### Google Drive library sync is separate

Public Drive APK downloads do not need a Drive account login or library-sync OAuth credentials.

For library sync, supply your own installed-app OAuth JSON in the ignored `app/src/main/assets/client_secrets.local.json`. Set the AppAuth redirect scheme in `app/src/main/AndroidManifest.xml` to match your OAuth client: `com.googleusercontent.apps.<client-id-without-.apps.googleusercontent.com>`. The checked-in `client_secrets.json` is a placeholder; without your own credentials, sync reports that it is not configured. Keep account tokens and private credentials out of Git.

## Technical notes and validation

- [Firebase setup for forks](mod/firebase/SETUP.md)
- [Panel sharing, retention, and rules](mod/firebase/README.md)
- [Keyboard GIFs](mod/firebase/CHAT_GIFS.md)
- [HD profile photos](mod/firebase/PROFILE_PHOTOS.md)
- [Updater publishing and changelog history](mod/firebase/UPDATER.md)
- [Development rules](AGENTS.md)

Feature logic primarily lives under `ui/mod/...` with integration hooks in existing screens. Firebase chat messages/photos are not embedded in the repository. Local reader backups cover supported reader/mod state; they do not export or restore the shared Firestore chat.

Recent validation includes Kotlin compilation, 197 JVM tests, 16 Firebase emulator rules tests, device tests for scrolling/GIF input/photo processing, and manual LDPlayer tests. These are recorded results, not a guarantee that every fork/device has been tested. Website APIs can change independently of the app.

## Why I made this

This started as something I built for myself. I wanted a better way to organize History, so I tried building it rather than waiting for someone else to add it.

I have very little programming experience, and this became a personal learning project. ChatGPT and Gemini helped with brainstorming, debugging, implementation ideas, and code generation. If these additions make your reading experience a little better, the project has done its job.

Feel free to reuse the implementation in your own experiments and forks under the project's license. No separate permission is needed; retain the required license notices and attribution. Credit is appreciated. Happy coding! 🍻

## Credits and license

- The Mihon team and upstream contributors for the reader and open-source foundation.
- The original Tachiyomi contributors.
- ChatGPT (OpenAI) and Gemini (Google) for development assistance.
- Friends who tested the mod and reported bugs.

Previous project: [Mihon History Category Mod](https://github.com/ytyprem1-art/mihonHistory-category). This project is not affiliated with content providers and does not bundle manga chapters or extension repositories.

Licensed under [Apache License 2.0](LICENSE). Retain inherited copyright/license notices, including the Tachiyomi and Mihon projects.
