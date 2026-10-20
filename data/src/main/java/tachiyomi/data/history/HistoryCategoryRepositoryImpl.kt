package tachiyomi.data.history

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.history.repository.HistoryCategory
import tachiyomi.domain.history.repository.HistoryCategoryRepository


@dev.zacsweers.metro.SingleIn(dev.zacsweers.metro.AppScope::class)
@dev.zacsweers.metro.ContributesBinding(dev.zacsweers.metro.AppScope::class)
@dev.zacsweers.metro.Inject
class HistoryCategoryRepositoryImpl(
    private val database: Database,
) : HistoryCategoryRepository {

    override fun getHistoryCategories(): Flow<List<HistoryCategory>> {
        return database.historycategoriesQueries.getHistoryCategories()
            .subscribeToList()
            .map { list ->
                list.map { HistoryCategory(it._id, it.name, it.sort.toInt()) }
            }
    }

    override suspend fun insertHistoryCategory(name: String, sort: Int) {
        database.historycategoriesQueries.insertHistoryCategory(name, sort.toLong())
    }

    override suspend fun updateHistoryCategory(id: Long, name: String) {
        database.historycategoriesQueries.updateHistoryCategory(name, id)
    }

    override suspend fun updateHistoryCategorySort(id: Long, sort: Int) {
        database.historycategoriesQueries.updateHistoryCategorySort(sort.toLong(), id)
    }

    override suspend fun deleteHistoryCategory(id: Long) {
        database.historycategoriesQueries.deleteHistoryCategory(id)
    }

    override suspend fun insertMangaMapping(mangaId: Long, categoryId: Long) {
        database.historycategoriesQueries.insertMangaMapping(mangaId, categoryId)
    }

    override suspend fun deleteMangaMapping(mangaId: Long) {
        database.historycategoriesQueries.deleteMangaMapping(mangaId)
    }

    override suspend fun getCategoryForManga(mangaId: Long): Long? {
        return database.historycategoriesQueries.getCategoryForManga(mangaId).awaitAsOneOrNull()
    }
}
