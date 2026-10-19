# Reader panel sharing on Firebase Spark

The Android implementation lives in `ui/mod/panelshare`. The initial chat listener starts at 50
messages and expands by 50 when the user scrolls to older messages, up to 200. The expanded query
keeps loaded messages and their read receipts live; reopening Chat resets the window to 50.
Expanding the query can re-read the existing window, a deliberate tradeoff for this small group.
The server retains 200 latest messages in total, across all senders and message types.
`ChatPanelStore` saves a panel blob in `global_chat_panels/{messageId}` and metadata in
`global_chat/{messageId}` atomically. Images are WebP, at most 800 px on the longest side and
150,000 bytes. Small crops are never upscaled. No Cloud Storage, Functions or billing is used.

## Rules deployment

`firestore.rules` replaces the September 9, 2026 production rules, preserving
the existing authenticated reads, sender checks, per-user read receipts and presence permissions.
It additionally validates paired panel creation and paired deletion, and allows authenticated chat
users to delete messages for retention. **The rules cannot verify a count of the newest 200
documents; that policy is enforced by the client. Authenticated clients are trusted to run cleanup.**
Anonymous authentication is inherited from the existing chat; it does not restrict membership to
the four friends. Do not treat these rules as a private-group membership boundary.

The project owner approved deployment on October 19, 2026. Firebase Console confirmed publication
at 16:42 Jakarta time. A subsequent aggregate count confirmed exactly 200 messages after cleanup;
the owner successfully sent a cropped panel both without and with a caption from the debug app.
Publishing future permission changes still needs the project owner's approval. Building/installing
the APK does not deploy any rules.
Do not test irreversible cleanup against production chat data without the owner's authorization.

For storage efficiency, add a single-field index exemption for `global_chat_panels.data` in the
console, preserving other existing index settings. No query uses the blob field. This is optional
for correctness; excluding this field avoids unnecessary indexing.

## Retention behavior

Cleanup runs after successful sends and on fresh server snapshots, including reconnection. It
queries the newest 200 using server timestamps, then deletes older messages together with their
panels in batches of 10 messages, within Rules' 20 access-call limit. Equal timestamps are ordered
by Firestore's implicit document-ID ordering. Concurrent new sends are preserved; their cleanup
pass brings the count back to 200. This is eventual client-driven cleanup, not a server scheduler.
If all updated clients are offline, cleanup waits. A failed cleanup shows a retry notice in Chat.

The first successful cleanup also removes any existing chat history beyond 200. There is no trash.
Reply previews inside retained messages may still quote text from an expired message.
The app's in-memory bitmap cache is bounded to 8 MiB; Firestore manages eviction of its disk cache.

## Tests

Use JDK 21+ for the Firebase emulator. Tests target `demo-mihon-panels` on localhost port 8094,
never the live project.

```powershell
npm install --package-lock=false
npm test
```

Android JVM tests: `PanelGeometryTest`, `ChatRetentionTest`, `ChatHistoryPagingTest`, and existing
`HistorySourceLinksTest`. Build with `:app:compileDebugKotlin :app:assembleDebug`.

## Upstream integration hooks

- `ReaderActivity.kt`: owns the controller, hosts its dialog, and calls its bridge from the toolbar.
- `ReaderAppBars.kt`: forwards one optional callback.
- `ReaderBottomBar.kt`: displays the direct Crop & Share button.
- `ReaderViewModel.kt` and `MangaViewModel.kt`: existing share sends call the common store so they
  also trigger retention. Their payloads and existing share behavior are otherwise unchanged.

Existing mod files `ChatManager`, `GlobalChatViewModel`, and `GlobalChatScreen` add the panel flag,
render the new panel card, invoke cleanup and show errors. No local database, backup or history
schema changes are involved.
