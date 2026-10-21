package tachiyomi.domain.history.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.history.model.UpdateWatch

interface UpdateWatchRepository {
    fun subscribeAll(): Flow<List<UpdateWatch>>
    suspend fun getAll(): List<UpdateWatch>
    suspend fun getById(mangaId: Long): UpdateWatch?
    suspend fun insert(updateWatch: UpdateWatch)
    suspend fun delete(mangaId: Long)
    suspend fun updatePaused(mangaId: Long, isPaused: Boolean)
    suspend fun updateBackgroundRefresh(
        mangaId: Long,
        enabled: Boolean,
        interval: Int,
        profile: UpdateWatch.RefreshProfile,
    )
    suspend fun updateLastBackgroundCheckAt(mangaId: Long, timestamp: Long)
    suspend fun updateStaleMilestone(mangaId: Long, milestone: Int)
    suspend fun resetStaleMilestone(mangaId: Long)
}
