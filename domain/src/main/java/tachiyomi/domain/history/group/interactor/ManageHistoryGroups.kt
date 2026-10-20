package tachiyomi.domain.history.group.interactor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import tachiyomi.domain.history.group.model.HistoryGroup
import tachiyomi.domain.history.group.repository.HistoryGroupRepository

@dev.zacsweers.metro.Inject
class ManageHistoryGroups(
    private val repository: HistoryGroupRepository,
) {
    fun subscribe(): Flow<List<HistoryGroup>> {
        return repository.subscribe()
    }

    fun subscribeMembers(groupId: Long): Flow<List<Long>> {
        return repository.subscribeMembers(groupId)
    }

    suspend fun getGroups(): List<HistoryGroup> {
        return repository.getGroups()
    }

    suspend fun createGroup(name: String): Long {
        return repository.insertGroup(name)
    }

    suspend fun renameGroup(id: Long, name: String) {
        repository.updateGroup(id, name)
    }

    suspend fun deleteGroup(id: Long) {
        repository.deleteGroup(id)
    }

    suspend fun assignMangaToGroup(mangaId: Long, groupId: Long) {
        repository.assignMangaToGroup(mangaId, groupId)
    }

    suspend fun createGroupWithMembers(name: String, mangaIds: List<Long>) {
        repository.createGroupWithMembers(name, mangaIds)
    }

    suspend fun removeMangaFromGroup(mangaId: Long) {
        repository.removeMangaFromGroup(mangaId)
    }

    suspend fun getGroupForManga(mangaId: Long): HistoryGroup? {
        return repository.getGroupForManga(mangaId)
    }

    fun subscribeGroupForManga(mangaId: Long): Flow<HistoryGroup?> {
        return repository.subscribeGroupForManga(mangaId)
    }

    fun subscribeAllMemberships(): Flow<Map<Long, Long>> {
        return repository.subscribeAllMemberships()
    }

    suspend fun getAllMemberships(): Map<Long, Long> {
        return repository.subscribeAllMemberships().first()
    }
}
