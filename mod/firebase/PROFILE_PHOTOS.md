# Profile photos in Enhanced 2.3

Enhanced 2.3 (Android version code 55) adds full-screen profile-photo zoom and a clearer photo for
that viewer. This is pending release alongside the user's next features; no 2.3 update announcement
was published as part of implementation or testing.

## Storage and compatibility

The 96×96 avatar (at most 8 KiB before Base64) stays in the existing presence document. The list and
read-receipt avatars therefore keep using the same small image as before. A separately encoded
768×768 JPEG (at most 200 KiB) lives in `chat_profile_photos/{uid}`. Both sizes come from the same
movable crop. The higher quality JPEG is used when it fits, with progressively smaller JPEG quality
only if needed to stay below the bound.

Photo changes atomically overwrite the same HD document and update the thumbnail plus a fresh
`avatarPhotoVersion` in presence. There is one current HD document per anonymous UID, with no growing
collection of old files. Remove photo atomically deletes the HD document and both presence photo
fields. Owner-only writes and authenticated reads retain the existing profile-photo audience.

The HD image is fetched only when its profile photo is opened, then cached in a bounded 8 MiB bitmap
cache by UID/version. Presence/typing/status updates do not download it. The viewer reuses the shared
PhotoView zoom surface and supports pinch/double tap. Existing 2.1/2.2 photos still render using their
small avatar; the original photo must be picked again to supply real detail for the larger viewer.
Old clients remain compatible with the small avatar and ignore the additional version metadata.

The profile AlertDialog stays composed while its photo viewer is open. The viewer uses one Dialog
throughout loading, displaying, retrying and closing, avoiding the previous close/reopen flash.

## Files and integration

Mod files: `ChatAvatar.kt`, `ChatAvatarCrop.kt`, `ChatUserProfile.kt`, new `ChatProfilePhotoStore.kt`
and `ChatProfilePhotoPreview.kt` under `ui/mod/chatprofile`; the reusable zoom surface is extracted
within `ui/mod/panelshare/ChatPanelImagePreview.kt`.

The existing `GlobalChatViewModel.kt` only reads/carries the optional photo-version metadata;
`GlobalChatScreen.kt` forwards it at its two profile-dialog call sites. No new MainActivity or Settings
hook is needed. No core database schema, backup serialization, manga identity, source handlers,
Update Watch scheduling or unrelated upstream behavior changed. The Gradle version and welcome
feature text mark the next 2.3 release.

## Validation

- Debug Kotlin compilation, JVM suite (197 passing tests), debug APK and Android-test APK builds pass.
- The HD device test verifies a real 768px JPEG and a compatible 96px thumbnail with matching crop,
  encoded byte limits, detailed/noisy input compression and rejection of invalid/oversized payloads.
- Firebase emulator: all 16 rules tests pass, including HD ownership/read access, 200 KiB limit,
  rejection of unmatched photo/presence versions, overwriting a fixed UID document and atomic removal.
- The tested rules were published and verified at 11:19 AM Jakarta on 21 October 2026. No test images
  were uploaded to production by the agent. The user reselected their real photo in LDPlayer and
  confirmed the larger preview is clearer.
- After the final dialog fix, the user confirmed opening/closing the photo is smooth and the profile
  remains open. Latest debug is installed only on emulator-5554; this is not a signed-release test.

The user confirmed the signed 2.2 release and updater notification work. The 2.3 additions remain pending release; no 2.3 announcement has been published.
