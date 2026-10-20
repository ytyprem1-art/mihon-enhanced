package tachiyomi.domain.history.interactor

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.history.model.UpdateWatchHistory
import tachiyomi.domain.history.repository.UpdateWatchHistoryRepository

@dev.zacsweers.metro.Inject
class GetUpdateWatchHistory(
    private val repository: UpdateWatchHistoryRepository,
) {
    suspend fun awaitLatest(mangaId: Long): UpdateWatchHistory? {
        return repository.getLatestByMangaId(mangaId)
    }

    suspend fun awaitAll(): List<UpdateWatchHistory> {
        return repository.getAll()
    }

    fun subscribeLatest(mangaId: Long): Flow<UpdateWatchHistory?> {
        return repository.subscribeLatestByMangaId(mangaId)
    }

    fun subscribeLatest5(mangaId: Long): Flow<List<UpdateWatchHistory>> {
        return repository.subscribeLatest5ByMangaId(mangaId)
    }

    fun subscribeLatestForMultiple(mangaIds: List<Long>): Flow<List<UpdateWatchHistory>> {
        return repository.subscribeLatestByMangaIds(mangaIds)
    }
}
