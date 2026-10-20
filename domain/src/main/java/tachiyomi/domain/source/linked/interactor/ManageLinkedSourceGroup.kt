package tachiyomi.domain.source.linked.interactor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.linked.model.LinkedSourceGroup
import tachiyomi.domain.source.linked.repository.LinkedSourceRepository

@dev.zacsweers.metro.Inject
class ManageLinkedSourceGroup(
    private val repository: LinkedSourceRepository,
    private val mangaRepository: MangaRepository,
) {
    fun subscribe(): Flow<List<LinkedSourceGroup>> {
        return repository.getGroups()
    }

    fun subscribeMembers(groupId: Long): Flow<List<Manga>> {
        return repository.getMembersByGroupId(groupId).flatMapLatest { mangaIds ->
            mangaRepository.getMangaByIdsAsFlow(mangaIds)
        }
    }

    fun subscribeMemberIds(groupId: Long): Flow<List<Long>> {
        return repository.getMembersByGroupId(groupId)
    }

    suspend fun getGroupById(id: Long): LinkedSourceGroup? {
        return repository.getGroupById(id)
    }

    suspend fun create(name: String): Long {
        return repository.insertGroup(name)
    }

    suspend fun createAndLink(name: String, mangaId: Long) {
        repository.createAndLink(name, mangaId)
    }

    suspend fun joinGroup(groupId: Long, mangaId: Long, sourceId: Long) {
        repository.removeMangaFromGroups(mangaId, sourceId)
        repository.insertMember(groupId, mangaId)
    }

    suspend fun rename(id: Long, name: String) {
        repository.updateGroup(id, name)
    }

    suspend fun delete(id: Long) {
        repository.deleteGroup(id)
    }

    suspend fun removeMember(groupId: Long, mangaId: Long, sourceId: Long) {
        repository.removeMember(groupId, mangaId, sourceId)
    }

    fun subscribeGroupForManga(mangaId: Long, sourceId: Long): Flow<LinkedSourceGroup?> {
        return repository.getGroupForManga(mangaId, sourceId)
    }

    suspend fun getGroupForManga(mangaId: Long, sourceId: Long): LinkedSourceGroup? {
        val groupId = repository.getGroupIdForManga(mangaId, sourceId) ?: return null
        return repository.getGroupById(groupId)
    }
}
