package tachiyomi.domain.history.interactor

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.history.model.UpdateWatchInboxItem
import tachiyomi.domain.history.repository.UpdateWatchInboxRepository

@dev.zacsweers.metro.Inject
class GetUpdateWatchInbox(
    private val repository: UpdateWatchInboxRepository,
) {
    fun subscribe(): Flow<List<UpdateWatchInboxItem>> {
        return repository.subscribeAll()
    }

    suspend fun await(): List<UpdateWatchInboxItem> {
        return repository.getAll()
    }
}
