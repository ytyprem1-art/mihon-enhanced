# Implementation Plan - Manga Merge UI Integration (Step 4A)

This plan outlines the steps to port the Manga Merge UI from TachiyomiSY to Mihon Enhanced, maintaining feature parity and following `AGENTS.md`.

## User Review Required

> [!IMPORTANT]
> The `EditMergedSettingsDialog` will be implemented using a `RecyclerView` inside an `AndroidView` wrapper in Compose, as in the original TachiyomiSY implementation. This ensures we preserve the complex drag-and-drop and header-item logic without a full Compose rewrite.

## Proposed Changes

### Resources

#### [MODIFY] [strings.xml](file:///D:/AndroidStudioProjects/mihon-update-test/i18n/src/commonMain/moko-resources/base/strings.xml)
- Add required SY merge strings: `allow_deduplication`, `deduplication_mode`, `deduplication_entry_info`, `no_dedupe`, `dedupe_priority`, `dedupe_most_chapters`, `dedupe_highest_chapter`, `fetch_chapter_updates`, `delete_merged_entry`, `delete_merged_entry_desc`, `chapter_updates_merged_entry`, `chapter_updates_merged_entry_desc`, `download_merged_entry`, `download_merged_entry_desc`, `merged_references_invalid`, `merged_chapter_updates_error`, `merged_toggle_download_chapters_error`, `merge_unknown_entry`, `merged_already`, `merge_duplicate`, `searching_source`, `could_not_find_entry`, `automatic_search_error`.

#### [NEW] [ic_delete_24dp.xml](file:///D:/AndroidStudioProjects/mihon-update-test/app/src/main/res/drawable/ic_delete_24dp.xml)
- Port the delete icon from TachiyomiSY.

#### [NEW] [edit_merged_settings_dialog.xml](file:///D:/AndroidStudioProjects/mihon-update-test/app/src/main/res/layout/edit_merged_settings_dialog.xml)
#### [NEW] [edit_merged_settings_header.xml](file:///D:/AndroidStudioProjects/mihon-update-test/app/src/main/res/layout/edit_merged_settings_header.xml)
#### [NEW] [edit_merged_settings_item.xml](file:///D:/AndroidStudioProjects/mihon-update-test/app/src/main/res/layout/edit_merged_settings_item.xml)
- Port the layouts for the merge settings dialog.

---

### Data Models & Logic

#### [NEW] [MergedMangaData.kt](file:///D:/AndroidStudioProjects/mihon-update-test/app/src/main/java/eu/kanade/tachiyomi/ui/mod/merge/MergedMangaData.kt)
- Define the `MergedMangaData` holder class used by the ViewModel and UI.

---

### UI Components (Mod Package)

#### [NEW] [SmartSearchScreen.kt](file:///D:/AndroidStudioProjects/mihon-update-test/app/src/main/java/eu/kanade/tachiyomi/ui/mod/merge/SmartSearchScreen.kt)
#### [NEW] [SmartSearchScreenModel.kt](file:///D:/AndroidStudioProjects/mihon-update-test/app/src/main/java/eu/kanade/tachiyomi/ui/mod/merge/SmartSearchScreenModel.kt)
- Port the SmartSearch logic used to find mangas to merge.

#### [NEW] [EditMergedSettingsDialog.kt](file:///D:/AndroidStudioProjects/mihon-update-test/app/src/main/java/eu/kanade/tachiyomi/ui/mod/merge/EditMergedSettingsDialog.kt)
#### [NEW] [EditMergedMangaAdapter.kt](file:///D:/AndroidStudioProjects/mihon-update-test/app/src/main/java/eu/kanade/tachiyomi/ui/mod/merge/EditMergedMangaAdapter.kt)
#### [NEW] [EditMergedMangaHolder.kt](file:///D:/AndroidStudioProjects/mihon-update-test/app/src/main/java/eu/kanade/tachiyomi/ui/mod/merge/EditMergedMangaHolder.kt)
#### [NEW] [EditMergedMangaItem.kt](file:///D:/AndroidStudioProjects/mihon-update-test/app/src/main/java/eu/kanade/tachiyomi/ui/mod/merge/EditMergedMangaItem.kt)
#### [NEW] [EditMergedSettingsHeaderAdapter.kt](file:///D:/AndroidStudioProjects/mihon-update-test/app/src/main/java/eu/kanade/tachiyomi/ui/mod/merge/EditMergedSettingsHeaderAdapter.kt)
- Port the dialog and its supporting classes.

---

### Upstream Integration Hooks

#### [MODIFY] [MangaViewModel.kt](file:///D:/AndroidStudioProjects/mihon-update-test/app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaViewModel.kt)
- Add `MergedMangaData` to `State.Success`.
- Inject merge interactors.
- Update `init` to subscribe to merged chapters and metadata.
- Implement `smartSearchMerge`, `updateMergeSettings`, and `deleteMerge`.

#### [MODIFY] [MangaScreen.kt](file:///D:/AndroidStudioProjects/mihon-update-test/app/src/main/java/eu/kanade/presentation/manga/MangaScreen.kt)
- Add callbacks for merge actions: `onMergedSettingsClicked`, `onMergeClicked`.
- Update `MangaToolbar` to show "Merge" / "Merge Settings" buttons.

#### [MODIFY] [MangaScreen.kt](file:///D:/AndroidStudioProjects/mihon-update-test/app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt)
- Pass view model actions to the presentation `MangaScreen`.
- Handle navigation to `SmartSearchScreen`.
- Display `EditMergedSettingsDialog`.

## Verification Plan

### Automated Tests
- Build project: `./gradlew assembleDebug`
- Check for SQLDelight and View Binding issues.

### Manual Verification
- **Merge**: Open a manga, tap "Merge", search for another manga, and confirm they are merged.
- **Deduplication**: Verify chapters from both sources are combined and sorted according to settings.
- **Unmerge**: Open "Merge Settings", remove one child, and verify it's no longer part of the merge.
- **Priority**: Change source priority in settings and verify chapter list updates correctly.
