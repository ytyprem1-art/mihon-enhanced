# Mihon Mod Development Rules

## Upstream safety

- Keep all upstream Mihon files minimally modified.
- Existing Mihon files must only act as small integration bridges for mod features.
- Before editing an upstream file, first check whether the implementation can live in a new mod-specific file.
- Prefer composable hosts, callbacks, adapters, extension functions, helper objects, and existing public APIs.
- Put mod-specific UI, ViewModels, state, interactors, helpers, navigation logic, workers, schedulers, preferences, and business logic in separate mod-specific files/packages.
- Do not modify SourceManager, core repositories, shared database models, backup internals, or upstream domain logic unless absolutely necessary.
- When an upstream file must be modified, keep the hook small, obvious, and easy to reapply after future Mihon updates.
- Do not perform unrelated refactoring, renaming, formatting, or import cleanup in upstream files.

## Upstream architecture

- Follow the CURRENT Mihon architecture instead of restoring obsolete upstream patterns.
- Prefer current `ViewModel` / `StateViewModel` patterns over legacy Voyager `ScreenModel`.
- When a ViewModel requires constructor arguments or dependencies, use the current upstream ViewModel factory / CreationExtras pattern.
- Do not add fake no-argument constructors just to satisfy Compose `viewModel()`.
- Use lifecycle-aware state collection where appropriate, following current upstream Mihon patterns.
- When upstream removes or replaces an API, adapt the mod to the new upstream API instead of reintroducing obsolete helpers unless absolutely necessary.

## Mod package structure

Prefer dedicated mod packages such as:

- eu.kanade.tachiyomi.ui.mod.historycategory
- eu.kanade.tachiyomi.ui.mod.historygroup
- eu.kanade.tachiyomi.ui.mod.linkedsource
- eu.kanade.tachiyomi.ui.mod.quicksourceswitcher
- eu.kanade.tachiyomi.ui.mod.updatewatch
- eu.kanade.tachiyomi.ui.mod.bookmarkimport
- eu.kanade.tachiyomi.ui.mod.onboarding

Keep feature-specific UI, ViewModels, state models, helpers, adapters, workers, and navigation together under the relevant mod package when practical.

## Database and repository safety

- Avoid database schema changes unless explicitly requested.
- Prefer mod-specific tables and queries instead of modifying upstream tables.
- Do not add mod-only fields to upstream database models unless there is no reasonable alternative.
- If an upstream repository needs mod functionality, prefer a small adapter/helper or narrowly scoped interface hook.
- Preserve stable manga identity using source + URL or the current canonical upstream equivalent instead of relying on transient local database IDs.
- When modifying SQLDelight queries, verify generated interfaces still compile and avoid duplicate query names.

## Backup and restore safety

- Treat backup/restore as a compatibility-sensitive area.
- Preserve compatibility with existing mod backups whenever possible.
- Do not change existing protobuf field numbers or serialized structures merely for UI changes.
- Mod relationships must never depend on stale local database IDs from the source device.
- Restore manga records before restoring mod relationships that reference them.
- Rebind restored relationships using stable manga identity.
- Do not silently skip missing mod data as a substitute for fixing restore ordering or identity mapping.
- If adding backup UI options, ensure every selectable option is actually honored by serialization and restore logic.
- Do not create cosmetic checkboxes that do not affect backup contents.
- After backup/restore changes, recommend or perform a full:
  backup -> clear app data -> restore
  runtime test.

## Migration and reconciliation safety

- Preserve existing history migration and chapter reconciliation behavior.
- Do not lose reading progress, read state, bookmarks, timestamps, history-category mappings, linked-source relationships, or Update Watch state during manga identity migration.
- Reuse existing canonical manga records when possible and avoid creating duplicates.

## Update Watch safety

- Preserve tracked manga state, Auto Refresh state, scheduling data, inbox/history data, diagnostics, and notification behavior.
- Keep workers/schedulers in mod-specific packages whenever possible.
- Do not change scheduling cadence, eligibility rules, HOT burst behavior, or background execution semantics unless explicitly requested.

## Preferences and UI state

- Persistent user choices must use the established Mihon preference pattern when they need to survive navigation/app recreation.
- Do not rely only on `remember` or `rememberSaveable` for settings that should persist across screens or app restarts.
- UI state and persisted preference state must have a single source of truth.
- Avoid duplicate state that can cause toggle/UI desynchronization.

## Implementation workflow

Before writing code:

1. Read this AGENTS.md.
2. Identify which files are upstream Mihon files and which are mod-specific.
3. Design the feature so most implementation lives in new or existing mod-specific files.
4. State which upstream files require integration hooks.
5. Check the old stable mod implementation when fixing regressions introduced by an upstream merge.
6. Avoid database/schema/backup-format changes unless they are actually required.

After writing code:

1. List every modified upstream Mihon file.
2. Explain why each upstream modification was unavoidable.
3. Confirm that no unrelated upstream code was changed.
4. List any new or modified mod-specific files.
5. Run:
   `./gradlew :app:compileDebugKotlin`
6. Do not treat successful compilation as proof of runtime correctness.
7. For navigation/ViewModel changes, explicitly identify runtime paths that need device testing.
8. For backup/restore changes, explicitly identify clean-data restore testing that still needs to be performed.
9. Do not commit or push unless explicitly requested.