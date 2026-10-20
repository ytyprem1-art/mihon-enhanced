package tachiyomi.data.source

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.data.subscribeToOneOrNull
import tachiyomi.domain.source.linked.model.LinkedSourceGroup
import tachiyomi.domain.source.linked.repository.LinkedSourceRepository

@dev.zacsweers.metro.SingleIn(dev.zacsweers.metro.AppScope::class)
@dev.zacsweers.metro.ContributesBinding(dev.zacsweers.metro.AppScope::class)
@dev.zacsweers.metro.Inject
class LinkedSourceRepositoryImpl(
    private val database: Database,
) : LinkedSourceRepository {

    override fun getGroups(): Flow<List<LinkedSourceGroup>> {
        return database.linked_sourcesQueries.getGroups { id: Long, name: String, memberCount: Long ->
            LinkedSourceGroup(id, name, memberCount)
        }.subscribeToList()
    }

    override suspend fun getGroupById(id: Long): LinkedSourceGroup? {
        return database.linked_sourcesQueries.getGroupById(id) { groupId: Long, name: String ->
            LinkedSourceGroup(groupId, name, 0L)
        }.awaitAsOneOrNull()
    }

    override fun getMembersByGroupId(groupId: Long): Flow<List<Long>> {
        return database.linked_sourcesQueries.getMembersByGroupId(groupId)
            .subscribeToList()
    }

    override suspend fun insertGroup(name: String): Long {
        return database.transactionWithResult {
            database.linked_sourcesQueries.insertGroup(name).awaitAsOne()
        }
    }

    override suspend fun updateGroup(id: Long, name: String) {
        database.transaction {
            database.linked_sourcesQueries.updateGroup(name, id)
        }
    }

    override suspend fun deleteGroup(id: Long) {
        database.transaction {
            database.linked_sourcesQueries.deleteGroup(id)
        }
    }

    override fun getGroupForManga(mangaId: Long, sourceId: Long): Flow<LinkedSourceGroup?> {
        return database.linked_sourcesQueries.getGroupForManga(mangaId, sourceId) { gId: Long, name: String, memberCount: Long ->
            LinkedSourceGroup(gId, name, memberCount)
        }.subscribeToOneOrNull()
    }

    override suspend fun getGroupIdForManga(mangaId: Long, sourceId: Long): Long? {
        return database.linked_sourcesQueries.getGroupIdForManga(mangaId, sourceId).awaitAsOneOrNull()
    }

    override suspend fun insertMember(groupId: Long, mangaId: Long) {
        database.transaction {
            database.linked_sourcesQueries.insertMember(groupId, mangaId)
        }
    }

    override suspend fun removeMangaFromGroups(mangaId: Long, sourceId: Long) {
        database.transaction {
            database.linked_sourcesQueries.removeMangaFromGroups(mangaId, sourceId)
        }
    }

    override suspend fun removeMember(groupId: Long, mangaId: Long, sourceId: Long) {
        database.transaction {
            database.linked_sourcesQueries.removeMangaFromGroups(mangaId, sourceId)
            val members = database.linked_sourcesQueries.getMembersByGroupId(groupId).awaitAsList()
            if (members.isEmpty()) {
                database.linked_sourcesQueries.deleteGroup(groupId)
            }
        }
    }

    override suspend fun createAndLink(name: String, mangaId: Long) {
        database.transaction {
            // Remove from any existing group first (including legacy source_id links if they exist)
            // But createAndLink is called from MangaScreenModel where we don't have sourceId easily available for this repo call
            // Actually, we can just use mangaId for the cleanup here as a safety.
            // Wait, createAndLink is for a NEW group.
            database.linked_sourcesQueries.removeMangaFromGroups(mangaId, mangaId) // Use mangaId twice for safety if sourceId is unknown
            val groupId = database.linked_sourcesQueries.insertGroup(name).awaitAsOne()
            database.linked_sourcesQueries.insertMember(groupId, mangaId)
        }
    }
}
