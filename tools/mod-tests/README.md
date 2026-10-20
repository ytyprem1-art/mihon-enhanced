# Enhanced upgrade regression checks

From the repository root, after generating the SQLDelight debug interface:

```
python tools/mod-tests/test_upgrade.py
python tools/mod-tests/test_generated_sql.py
```

The fixture contains only the released Enhanced schema, with synthetic manga and chapter records added during tests. It contains no user data. Checks cover duplicate identity reconciliation, progress/history, all mod relationships, inbox chapter ID lists, legacy Drive counters and restore suppression. The second check executes emitted SQL as well as the clean-install schema; this catches SQLDelight rewriting `SELECT *` against an incomplete historical schema.

`EnhancedBackupDeviceTest` runs on Android with uniquely named database files and preferences. It does not clear or restore the user's main database. It covers clean manga restore, protobuf serialization, categories, groups, Update Watch inbox/history, merge creation/addition, per-source download settings, merge restore and sync counters. Live extension network requests, reader page rendering, Drive authentication and background scheduling still require end-to-end testing.
