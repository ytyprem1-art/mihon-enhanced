package tachiyomi.domain.history.repository

import kotlinx.coroutines.flow.Flow

data class HistoryCategory(
    val id: Long,
    val name: String,
    val sort: Int,
)

interface HistoryCategoryRepository {
    fun getHistoryCategories(): Flow<List<HistoryCategory>>
    suspend fun insertHistoryCategory(name: String, sort: Int)
    suspend fun updateHistoryCategory(id: Long, name: String)
    suspend fun updateHistoryCategorySort(id: Long, sort: Int)
    suspend fun deleteHistoryCategory(id: Long)
    suspend fun insertMangaMapping(mangaId: Long, categoryId: Long)
    suspend fun deleteMangaMapping(mangaId: Long)
    suspend fun getCategoryForManga(mangaId: Long): Long?
}
