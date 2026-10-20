package tachiyomi.data.history

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.history.model.UpdateWatchInboxItem
import tachiyomi.domain.history.repository.UpdateWatchInboxRepository

@dev.zacsweers.metro.SingleIn(dev.zacsweers.metro.AppScope::class)
@dev.zacsweers.metro.ContributesBinding(dev.zacsweers.metro.AppScope::class)
@dev.zacsweers.metro.Inject
class UpdateWatchInboxRepositoryImpl(
    private val database: Database,
) : UpdateWatchInboxRepository {

    override fun subscribeAll(): Flow<List<UpdateWatchInboxItem>> {
        return database.update_watch_inboxQueries.getInboxItems(::mapUpdateWatchInboxItem).subscribeToList()
    }

    override suspend fun getAll(): List<UpdateWatchInboxItem> {
        return database.update_watch_inboxQueries.getInboxItems(::mapUpdateWatchInboxItem).awaitAsList()
    }

    override suspend fun getByMangaId(mangaId: Long): UpdateWatchInboxItem? {
        return database.update_watch_inboxQueries.getInboxItemById(mangaId, ::mapUpdateWatchInboxItem).awaitAsOneOrNull()
    }

    override suspend fun insert(item: UpdateWatchInboxItem) {
        database.transaction {
            database.update_watch_inboxQueries.insert(
                mangaId = item.mangaId,
                mangaTitle = item.mangaTitle,
                sourceId = item.sourceId,
                sourceName = item.sourceName,
                chapterCount = item.chapterCount.toLong(),
                chapterRange = item.chapterRange,
                firstFoundAt = item.firstFoundAt,
                lastFoundAt = item.lastFoundAt,
                latestChapterId = item.latestChapterId,
                latestChapterNumber = item.latestChapterNumber,
                latestChapterUploadAt = item.latestChapterUploadAt,
                chapterIds = item.chapterIds,
                type = item.type.toLong(),
                milestone = item.milestone.toLong(),
            )
        }
    }

    override suspend fun delete(mangaId: Long) {
        database.transaction {
            database.update_watch_inboxQueries.delete(mangaId)
        }
    }

    override suspend fun deleteAll() {
        database.transaction {
            database.update_watch_inboxQueries.deleteAll()
        }
    }

    private fun mapUpdateWatchInboxItem(
        mangaId: Long,
        mangaTitle: String,
        sourceId: Long,
        sourceName: String,
        chapterCount: Long,
        chapterRange: String,
        firstFoundAt: Long,
        lastFoundAt: Long,
        latestChapterId: Long,
        latestChapterNumber: Double,
        latestChapterUploadAt: Long,
        chapterIds: List<Long>,
        type: Long,
        milestone: Long,
    ): UpdateWatchInboxItem {
        return UpdateWatchInboxItem(
            mangaId = mangaId,
            mangaTitle = mangaTitle,
            sourceId = sourceId,
            sourceName = sourceName,
            chapterCount = chapterCount.toInt(),
            chapterRange = chapterRange,
            firstFoundAt = firstFoundAt,
            lastFoundAt = lastFoundAt,
            latestChapterId = latestChapterId,
            latestChapterNumber = latestChapterNumber,
            latestChapterUploadAt = latestChapterUploadAt,
            chapterIds = chapterIds,
            type = type.toInt(),
            milestone = milestone.toInt(),
        )
    }
}
