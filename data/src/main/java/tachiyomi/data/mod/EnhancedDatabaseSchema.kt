package tachiyomi.data.mod

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import tachiyomi.data.Database

/** Keeps the released mod's migration numbers and repairs its historical development variants. */
object EnhancedDatabaseSchema : SqlSchema<QueryResult.AsyncValue<Unit>> {
    override val version: Long get() = Database.Schema.version

    override fun create(driver: SqlDriver) = Database.Schema.create(driver)

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ) = QueryResult.AsyncValue {
        val boundary = minOf(29L, newVersion)
        if (oldVersion < boundary) {
            Database.Schema.migrate(driver, oldVersion, boundary, *callbacks).await()
        }
        if (oldVersion <= 29 && newVersion > 29) {
            val linkedColumns = columns(driver, "linked_source_members")
            if ("source_id" in linkedColumns && "manga_id" !in linkedColumns) {
                driver.execute(null, "ALTER TABLE linked_source_members RENAME COLUMN source_id TO manga_id", 0).await()
            }
            val inboxColumns = columns(driver, "update_watch_inbox")
            if ("latest_chapter_upload_at" !in inboxColumns) {
                driver.execute(
                    null,
                    "ALTER TABLE update_watch_inbox ADD COLUMN latest_chapter_upload_at INTEGER NOT NULL DEFAULT 0",
                    0,
                ).await()
            }
            if ("chapter_ids" !in inboxColumns) {
                driver.execute(
                    null,
                    "ALTER TABLE update_watch_inbox ADD COLUMN chapter_ids TEXT NOT NULL DEFAULT ''",
                    0,
                ).await()
            }
        }
        if (newVersion > boundary) {
            Database.Schema.migrate(driver, maxOf(oldVersion, boundary), newVersion, *callbacks).await()
        }
    }

    private suspend fun columns(driver: SqlDriver, table: String): Set<String> = driver.executeQuery(
        identifier = null,
        sql = "PRAGMA table_info($table)",
        mapper = { cursor ->
            QueryResult.AsyncValue {
                buildSet {
                    while (cursor.next().await()) add(checkNotNull(cursor.getString(1)))
                }
            }
        },
        parameters = 0,
    ).await()
}
