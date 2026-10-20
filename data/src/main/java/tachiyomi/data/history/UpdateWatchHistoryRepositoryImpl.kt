package tachiyomi.data.history

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.data.subscribeToOneOrNull
import tachiyomi.domain.history.model.UpdateWatchHistory
import tachiyomi.domain.history.repository.UpdateWatchHistoryRepository

@dev.zacsweers.metro.SingleIn(dev.zacsweers.metro.AppScope::class)
@dev.zacsweers.metro.ContributesBinding(dev.zacsweers.metro.AppScope::class)
@dev.zacsweers.metro.Inject
class UpdateWatchHistoryRepositoryImpl(
    private val database: Database,
) : UpdateWatchHistoryRepository {

    override suspend fun insert(history: UpdateWatchHistory) {
        database.transaction {
            database.update_watch_historyQueries.insert(
                mangaId = history.mangaId,
                timestamp = history.timestamp,
                success = if (history.success) 1L else 0L,
                newChapters = history.newChapters.toLong(),
                category = history.category.name,
                detail = history.detail,
            )
            database.update_watch_historyQueries.deleteOldest(history.mangaId)
        }
    }

    override suspend fun getLatestByMangaId(mangaId: Long): UpdateWatchHistory? {
        return database.update_watch_historyQueries.getLatestByMangaId(mangaId, ::mapUpdateWatchHistory)
            .awaitAsOneOrNull()
    }

    override suspend fun getAll(): List<UpdateWatchHistory> {
        return database.update_watch_historyQueries.getAll(::mapUpdateWatchHistory)
            .awaitAsList()
    }

    override fun subscribeLatestByMangaId(mangaId: Long): Flow<UpdateWatchHistory?> {
        return database.update_watch_historyQueries.getLatestByMangaId(mangaId, ::mapUpdateWatchHistory)
            .subscribeToOneOrNull()
    }

    override fun subscribeLatest5ByMangaId(mangaId: Long): Flow<List<UpdateWatchHistory>> {
        return database.update_watch_historyQueries.getLatest5ByMangaId(mangaId, ::mapUpdateWatchHistory)
            .subscribeToList()
    }

    override fun subscribeLatestByMangaIds(mangaIds: List<Long>): Flow<List<UpdateWatchHistory>> {
        return database.update_watch_historyQueries.getLatestByMangaIds(mangaIds, ::mapUpdateWatchHistory)
            .subscribeToList()
    }

    private fun mapUpdateWatchHistory(
        mangaId: Long,
        timestamp: Long,
        success: Long,
        newChapters: Long,
        category: String,
        detail: String?,
    ): UpdateWatchHistory {
        return UpdateWatchHistory(
            mangaId = mangaId,
            timestamp = timestamp,
            success = success == 1L,
            newChapters = newChapters.toInt(),
            category = UpdateWatchHistory.FailureCategory.valueOf(category),
            detail = detail,
        )
    }
}
