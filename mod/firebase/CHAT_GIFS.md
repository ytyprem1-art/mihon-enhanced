# Keyboard GIFs in Enhanced 2.2

The chat composer uses the state-based Compose text field and `contentReceiver`, so Android IMEs
can commit GIF content. Selecting a GIF prepares a local animated preview; the send button publishes
it explicitly, with an optional caption and the existing reply metadata. Plain text input remains
available. Keyboard PNG stickers, videos, and other formats are not advertised as supported GIFs.

When the keyboard supplies a direct HTTPS GIF URL on a supported Tenor/Giphy media CDN, the app
validates the downloaded GIF and stores only its URL in message metadata. Optional URLs that cannot
be used fall back to the keyboard's content URI. Local GIFs are stored in `global_chat_gifs/{messageId}`,
separate from `global_chat` metadata and read receipts. IME URI permission is held until the local
file has been read, then released. No Cloud Storage bucket or paid storage migration is required.

Limits: 1,000,000 encoded bytes, canvas side at most 1200 pixels, at most 300 frames, and at most
12 million canvas-pixels across frames. GIF structure, sub-block bounds, and frame dimensions are
checked before preview or upload. The extra frame budget limits decoded memory for heavily compressed
animations. GIF bytes are preserved instead of converting to a still JPEG. Oversized/unsupported
input gets an error and does not produce a chat message. Drafts are retained on a failed send.

Encoded GIF memory cache and Coil decoded memory cache are each bounded to 8 MiB. The GIF image
loader has an explicit animated decoder. Offscreen LazyColumn rows are disposed normally; stable
message keys and the bounded byte cache avoid unnecessary network reloads. Tapping a chat GIF opens
a larger animated preview. Old clients show the `[GIF]` text fallback; updating is required to render
the media.

Retention uses the existing timestamp-ordered newest-200 policy across text, manga shares, panels,
and GIFs. Local GIF blobs are deleted in the same batch as expired messages; URL-based messages have
no uploaded blob. The rules reject orphaned GIF creation and unpaired deletion. Clients from before
2.2 cannot clean up a GIF blob themselves, but a 2.2 client's existing cleanup hook retries it.

The tested rules were published to the existing `mihon-enhanced` Firestore project at 10:20 AM Jakarta
on 21 October 2026. No test messages, private photos, or fake update releases were posted to production.
Emulator tests cover upload size/ownership, public links, paired cleanup, access-call limits, and mixed
205-message retention. Device tests commit a local GIF through the real Android input connection,
verify draft/removal/plain text behavior, and check animated decoding. Physical Gboard still needs
the user's release-device test; compatibility depends on the MIME type and payload the keyboard sends.

For the first update test, install signed Enhanced 2.1 (code 53), build signed 2.2 (code 54) with the
same keystore, upload the universal APK with public-link access, and publish 2.2 through Settings.
Do not publish fake higher versions to test against friends' clients.
