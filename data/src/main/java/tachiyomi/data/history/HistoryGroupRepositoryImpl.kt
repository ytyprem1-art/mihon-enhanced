package tachiyomi.data.history

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.data.subscribeToOneOrNull
import tachiyomi.domain.history.group.model.HistoryGroup
import tachiyomi.domain.history.group.repository.HistoryGroupRepository

@dev.zacsweers.metro.SingleIn(dev.zacsweers.metro.AppScope::class)
@dev.zacsweers.metro.ContributesBinding(dev.zacsweers.metro.AppScope::class)
@dev.zacsweers.metro.Inject
class HistoryGroupRepositoryImpl(
    private val database: Database,
) : HistoryGroupRepository {

    override fun subscribe(): Flow<List<HistoryGroup>> {
        return database.history_groupsQueries.getGroups { id, name ->
            HistoryGroup(id, name)
        }.subscribeToList()
    }

    override fun subscribeMembers(groupId: Long): Flow<List<Long>> {
        return database.history_groupsQueries.getMembersByGroupId(groupId)
            .subscribeToList()
    }

    override suspend fun getGroups(): List<HistoryGroup> {
        return database.history_groupsQueries.getGroups { id, name ->
            HistoryGroup(id, name)
        }.awaitAsList()
    }

    override suspend fun getGroupById(id: Long): HistoryGroup? {
        return database.history_groupsQueries.getGroupById(id) { groupId, name ->
            HistoryGroup(groupId, name)
        }.awaitAsOneOrNull()
    }

    override suspend fun getGroupForManga(mangaId: Long): HistoryGroup? {
        return database.history_groupsQueries.getGroupForManga(mangaId) { id, name ->
            HistoryGroup(id, name)
        }.awaitAsOneOrNull()
    }

    override fun subscribeGroupForManga(mangaId: Long): Flow<HistoryGroup?> {
        return database.history_groupsQueries.getGroupForManga(mangaId) { id, name ->
            HistoryGroup(id, name)
        }.subscribeToOneOrNull()
    }

    override suspend fun insertGroup(name: String): Long {
        return database.transactionWithResult {
            database.history_groupsQueries.insertGroup(name).awaitAsOne()
        }
    }

    override suspend fun updateGroup(id: Long, name: String) {
        database.transaction {
            database.history_groupsQueries.updateGroup(name, id)
        }
    }

    override suspend fun deleteGroup(id: Long) {
        database.transaction {
            database.history_groupsQueries.deleteGroup(id)
        }
    }

    override suspend fun assignMangaToGroup(mangaId: Long, groupId: Long) {
        database.transaction {
            database.history_groupsQueries.insertMember(mangaId, groupId)
        }
    }

    override suspend fun createGroupWithMembers(name: String, mangaIds: List<Long>) {
        database.transaction {
            val groupId = database.history_groupsQueries.insertGroup(name).awaitAsOne()
            mangaIds.forEach { mangaId ->
                database.history_groupsQueries.insertMember(mangaId, groupId)
            }
        }
    }

    override suspend fun removeMangaFromGroup(mangaId: Long) {
        database.transaction {
            database.history_groupsQueries.removeMember(mangaId)
        }
    }

    override fun subscribeAllMemberships(): Flow<Map<Long, Long>> {
        return database.history_groupsQueries.getAllMemberships { mangaId, groupId ->
            mangaId to groupId
        }.subscribeToList()
            .map { list -> list.toMap() }
    }
}
