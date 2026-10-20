package tachiyomi.domain.history.interactor

import tachiyomi.domain.history.model.UpdateWatchHistory
import tachiyomi.domain.history.repository.UpdateWatchHistoryRepository

@dev.zacsweers.metro.Inject
class ManageUpdateWatchHistory(
    private val repository: UpdateWatchHistoryRepository,
) {
    suspend fun insert(history: UpdateWatchHistory) {
        repository.insert(history)
    }
}
