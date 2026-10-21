# Set up your own Firebase backend

Chat, profile photos, and the in-app update feed use the configured Firebase project. A GitHub fork does not automatically create a separate backend.

## Android configuration

1. Create a Firebase project you own.
2. Register your release Android application ID. This source uses `dev.vishkel.mihon`; use your own ID for an independent branded fork. Debug adds `.dev`, so register that variant too when needed. Register any other variant IDs you actually build.
3. Download your Android `google-services.json` to `app/google-services.json`. The example file documents its shape only; placeholder values do not connect to a real backend.
4. Enable **Authentication → Anonymous** sign-in.
5. Create a Cloud Firestore database and deploy the supplied rules before using chat/updater.
6. Build your APK. Keep the real client config local; `.gitignore` excludes it and variant-specific configs.

Do not put service-account private keys in the Android app. Client config identifies a project; access is enforced by Security Rules. The current chat expects a configured Firebase app: setup is a build prerequisite, not a runtime server selector.

## Deploy rules

Review `firestore.rules`. In your own Firebase Console, open Firestore → Rules, paste it, and publish. Alternatively, using the official Firebase CLI authenticated with suitable permissions:

```sh
cd mod/firebase
firebase deploy --only firestore:rules --project YOUR_FIREBASE_PROJECT_ID
```

Always name your own project explicitly. The checked-in `firebase.json` contains rule/emulator paths, not deployment credentials. Compiling an APK does not deploy rules.

| Collection/document | Purpose |
| --- | --- |
| `global_chat` | Messages, replies, shares, read receipts |
| `global_chat_panels` | Uploaded cropped panels |
| `global_chat_gifs` | Uploaded GIFs without usable public links |
| `presence` | Per-device profiles, status, app versions, small avatars |
| `chat_profile_photos` | One current HD photo per user ID |
| `mod_updates/stable` | Latest published update |
| `mod_update_releases` | Archived changelogs |
| `mod_update_config/admin` | Private update-password verifier |
| `mod_update_proofs` | Password verification for publishing |

No production database dump is needed to start your chat. Cloud Storage/Functions are not used by these media features. Firestore quotas still apply.

## Set your publishing password

1. Choose a strong password for your own backend. There is no default `1234` password.
2. Compute SHA-256 over its UTF-8 bytes using a trusted local tool, not an online password-hashing website. Python can prompt without displaying your password:

   ```sh
   python -c "import getpass, hashlib; print(hashlib.sha256(getpass.getpass('Admin password: ').encode('utf-8')).hexdigest())"
   ```

3. As project owner, create `mod_update_config/admin` in Firestore with a **string** field named `pinHash` containing the lowercase hexadecimal hash.
4. In your app, open Settings → Kelola update and enter the **original password**, not its hash.

The hash also permits publishing, so keep both private. Do not commit them or place them in release notes. Rules deny clients access to reading/changing the admin document; initialize/rotate it through the Console or a trusted administrative environment. The password gate is not a replacement for a full abuse-resistant administrative service or strong account security.

## Publish an update

1. Increase mod version and Android version code.
2. Build a **signed universal release APK** with the same package/signing key as your existing app.
3. Upload the APK to Drive and enable download access for anyone with that file's link.
4. Open Settings → Kelola update; enter version, Drive file link, and changelog; confirm publishing.
5. Clients check Firebase when the main activity starts/returns to foreground. Notifications require Android permission. The prompt offers Update sekarang/Nanti and reappears on later app opens while an update is pending.
6. Download starts after the user's action, shows progress, validates the APK, then opens Android's installer. Android still needs installation confirmation; there is no silent install.

Changing GitHub code, forking, or uploading to Drive alone does not announce an update. Other forks on different Firebase projects cannot publish to your clients. Reusing the same Firebase shares its chat/feed. A different signing key cannot replace an existing signed installation through the updater.

Each publication archives its notes. For installed 2.1 → latest 2.5, the client can show published notes for 2.5/2.4/2.3/2.2 and download only 2.5. Notes never published to the archive cannot appear. Do not publish fake high versions to a real group's backend for testing.

## Privacy and storage limits

- Anonymous login is **not private group membership**. Authenticated anonymous users of the same backend can read chat/presence/photos. Add membership rules if an invite-only deployment is needed. Display names are not identity or admin authority.
- Anonymous IDs differ between devices/installs; equal display names are not merged automatically.
- Retention is the newest 200 messages across all types, ordered by timestamp. Cleanup is client-driven and eventual, not a server timer or hard storage cap. Updated clients must be online; old clients may not clean newer media types.
- Panels: longest side at most 800px / 150,000 bytes. GIFs: at most 1,000,000 bytes plus dimension/frame/decode bounds. Public GIF links are restricted to supported hosts.
- Profiles: a 96px avatar plus a separate 768px JPEG capped at 200 KiB. Replacement overwrites the same HD document; removal deletes it. Old versions do not accumulate as separate photo files.
- Expired message deletion is irreversible through the app. Local reader backups do not export the shared chat. Test cleanup only with data you can discard.
- Media `data` blob fields do not need querying; optionally exempt them from single-field indexing, preserving actual query indexes.

## Tests

Use a separate development project or Firebase emulator. Rules tests use `demo-mihon-panels` on localhost port 8094, not production. Install Node.js, the declared npm dependencies, and JDK 21+:

```sh
cd mod/firebase
npm install --package-lock=false
npm test
```

More detail: [retention/panels](README.md), [GIFs](CHAT_GIFS.md), [profile photos](PROFILE_PHOTOS.md), and [updater](UPDATER.md).
