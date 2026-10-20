package tachiyomi.domain.history.interactor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import tachiyomi.domain.history.repository.HistoryCategory
import tachiyomi.domain.history.repository.HistoryCategoryRepository

@dev.zacsweers.metro.Inject
class ManageHistoryCategory(
    private val repository: HistoryCategoryRepository
) {
    fun subscribe(): Flow<List<HistoryCategory>> {
        return repository.getHistoryCategories()
    }

    suspend fun create(name: String, sort: Int? = null) {
        val nextSort = sort ?: ((repository.getHistoryCategories().first().maxOfOrNull { it.sort } ?: 0) + 1)
        repository.insertHistoryCategory(name, nextSort)
    }

    suspend fun rename(id: Long, name: String) {
        repository.updateHistoryCategory(id, name)
    }

    suspend fun updateSort(id: Long, sort: Int) {
        repository.updateHistoryCategorySort(id, sort)
    }

    suspend fun delete(id: Long) {
        repository.deleteHistoryCategory(id)
    }

    suspend fun moveLeft(category: HistoryCategory) {
        var categories = repository.getHistoryCategories().first()

        // Detect duplicates or all zeros (e.g. after migration)
        val hasDuplicates = categories.map { it.sort }.distinct().size != categories.size
        if (hasDuplicates) {
            categories.forEachIndexed { index, cat ->
                repository.updateHistoryCategorySort(cat.id, index + 1)
            }
            categories = repository.getHistoryCategories().first()
        }

        val index = categories.indexOfFirst { it.id == category.id }
        if (index > 0) {
            val prevCategory = categories[index - 1]
            val currentSort = categories[index].sort
            val prevSort = prevCategory.sort

            // Swap sort values
            repository.updateHistoryCategorySort(category.id, prevSort)
            repository.updateHistoryCategorySort(prevCategory.id, currentSort)
        }
    }

    suspend fun moveRight(category: HistoryCategory) {
        var categories = repository.getHistoryCategories().first()

        // Detect duplicates or all zeros (e.g. after migration)
        val hasDuplicates = categories.map { it.sort }.distinct().size != categories.size
        if (hasDuplicates) {
            categories.forEachIndexed { index, cat ->
                repository.updateHistoryCategorySort(cat.id, index + 1)
            }
            categories = repository.getHistoryCategories().first()
        }

        val index = categories.indexOfFirst { it.id == category.id }
        if (index != -1 && index < categories.size - 1) {
            val nextCategory = categories[index + 1]
            val currentSort = categories[index].sort
            val nextSort = nextCategory.sort

            // Swap sort values
            repository.updateHistoryCategorySort(category.id, nextSort)
            repository.updateHistoryCategorySort(nextCategory.id, currentSort)
        }
    }

    suspend fun moveToCategory(mangaId: Long, categoryId: Long) {
        repository.deleteMangaMapping(mangaId)
        if (categoryId != 0L) {
            repository.insertMangaMapping(mangaId, categoryId)
        }
    }

    suspend fun getMangaCategory(mangaId: Long): Long? {
        return repository.getCategoryForManga(mangaId)
    }
}
