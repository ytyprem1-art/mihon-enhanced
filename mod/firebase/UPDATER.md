# Mihon Enhanced updater

The updater UI and download logic live in `ui/mod/appupdate`. Settings links to its screen;
MainActivity hosts a single shared update prompt. The original Mihon release checker stays disabled,
so this fork never offers an official Mihon APK over the enhanced app.

## Publishing a release

1. Build a signed **release universal APK** with the same application ID and signing key.
   Increment both the mod version and Android versionCode for each actual app release.
   The current baseline is **Mihon Enhanced 2.1**, versionCode **53**. The next release would
   normally be 2.2 / 54. Debug APKs carry a separate application ID/key and cannot replace release APKs.
2. Upload the universal APK to Google Drive and enable access for anyone with the link.
3. Open **Settings > Kelola update**, enter the admin password, fill version (e.g. `2.2`), Drive file
   link, and changelog. Review the confirmation and press **Terbitkan**.
4. All clients read `mod_updates/stable` when the main activity starts/returns to the foreground.
   A newer version produces an in-app prompt and an Android notification when notifications are allowed.
   **Nanti** dismisses only the current prompt; there is no permanent skip/don't-show-again setting.
5. Download runs only after a user taps Update sekarang and shows progress. **Pasang** opens the Android
   installer; allow installation from Mihon when Android asks, then return and tap Pasang again.
   Android still requires its installation confirmation. App data is retained; no uninstall is needed.

Changing a Drive folder alone does not announce a release. The publication metadata identifies the
specific APK file. Publishing works from an older installed version; the publisher can download their
own update through the same flow afterward. Same-version metadata corrections are permitted; server
rules reject lowering the published release number.

## One-time backend activation

Rules in `firestore.rules` retain existing chat/panel/presence permissions and add:

- `mod_updates/stable`: public single-document get; publication requires a fresh password proof in
  the same atomic batch, validated metadata, and a nondecreasing release number.
- `mod_update_config/admin`: the project owner sets a string field `pinHash` containing SHA-256 of
  the UTF-8 admin password. Android clients cannot read or modify this document.
- `mod_update_proofs/{anonymousUid}`: private, write-only password proof; only the corresponding
  authenticated anonymous UID can write it, and only when the password hash matches server config.
  Public release writes require a proof for the exact version/file/notes/number and the same server
  timestamp. Old proofs cannot be replayed. Neither public metadata nor APK source includes the verifier.

There is no email/admin registration UI. The publisher uses a separate anonymous Firebase SDK session,
which does not create a Global Chat presence entry. Password proofs use **memory-only** Firestore
caching, separate from existing chat caching. Unlock state stays in screen memory and is cleared when
the menu closes or is locked. Console/Admin SDK is required to initialize or rotate the server verifier.
The local setup page generates the verifier; the owner enters and saves credentials directly.

The owner explicitly approved publishing these rules on October 21, 2026. Firebase Console confirmed
the active version **Today, 9:02 AM (Jakarta)**. The owner then saved the verifier and confirmed that
unlocking the admin menu succeeds on LDPlayer. No release was published as a test.

## Download safeguards and tests

Drive links are restricted to HTTPS Google Drive file IDs. Downloads use a fresh anonymous cookie jar,
handle the Drive confirmation form, show progress, support cancellation, reject HTML/error responses,
cap APK size at 300 MiB, and remove failed/incomplete files. Successful installer cache files expire
after 24 hours when another download starts. PackageManager verifies package metadata before offering
installation: matching app ID, newer Android versionCode, matching published versionName, and identical
signing certificate set. Android performs its own final package verification/installation as well.

193 Android JVM tests passed, including 4 version/Drive-normalization/form-host/release-validation
tests. All 9 Firebase emulator tests passed, including password rejection, same-password publication
from another anonymous device, private credential reads denied, proof replay denied, and downgrade
rejection, while preserving all previous chat/panel tests. Tests use `demo-mihon-panels`, not production.

## Changelog history

Every publication atomically writes the password proof, latest pointer, and an archive document
`mod_update_releases/{version}`. Rules require the archive and pointer data to match. Archive metadata
is public; writes require the same fresh password verification, and deletion is denied to clients.
The history extension was published and verified in Firebase Console at **9:13 AM Jakarta**.

When a newer version exists, clients fetch notes for releases newer than their installed version up
to the newest available version, order them newest first, and always include the newest notes. For
example 2.1 -> 2.3 shows 2.3 and 2.2 changelogs, while downloading only the latest 2.3 APK. Publishing
2.3 does not overwrite the archived 2.2 notes. If history loading fails, the latest update remains
available with an explicit history-unavailable message instead of implying the older notes were read.

Final validation: **194 JVM tests passed**, including **5** updater tests. The updated **9 Firebase
rules tests passed**, now also verifying that 2.2 notes remain readable after publishing 2.3 and that
unauthorized history edits are rejected. Latest debug APK installed successfully on LDPlayer only.

Not yet exercised: real public Drive download of the next signed universal APK, installation, update
notification/pop-up, and the next-launch reminder on a release build. These need the actual next APK;
do not publish a fake higher version to the live group just for testing.
