package tachiyomi.data.history

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.history.model.UpdateWatch
import tachiyomi.domain.history.repository.UpdateWatchRepository

@dev.zacsweers.metro.SingleIn(dev.zacsweers.metro.AppScope::class)
@dev.zacsweers.metro.ContributesBinding(dev.zacsweers.metro.AppScope::class)
@dev.zacsweers.metro.Inject
class UpdateWatchRepositoryImpl(
    private val database: Database,
) : UpdateWatchRepository {

    override fun subscribeAll(): Flow<List<UpdateWatch>> {
        return database.update_watchQueries.getTrackedManga {
                mangaId,
                isPaused,
                enabled,
                interval,
                profile,
                lastCheck,
                lastMilestone,
            ->
            UpdateWatch(
                mangaId = mangaId,
                isPaused = isPaused == 1L,
                backgroundRefreshEnabled = enabled == 1L,
                expectedIntervalDays = interval.toInt(),
                refreshProfile = UpdateWatch.RefreshProfile.valueOf(profile),
                lastBackgroundCheckAt = lastCheck,
                lastWarnedMilestone = lastMilestone.toInt(),
            )
        }.subscribeToList()
    }

    override suspend fun getAll(): List<UpdateWatch> {
        return database.update_watchQueries.getTrackedManga {
                mangaId,
                isPaused,
                enabled,
                interval,
                profile,
                lastCheck,
                lastMilestone,
            ->
            UpdateWatch(
                mangaId = mangaId,
                isPaused = isPaused == 1L,
                backgroundRefreshEnabled = enabled == 1L,
                expectedIntervalDays = interval.toInt(),
                refreshProfile = UpdateWatch.RefreshProfile.valueOf(profile),
                lastBackgroundCheckAt = lastCheck,
                lastWarnedMilestone = lastMilestone.toInt(),
            )
        }.awaitAsList()
    }

    override suspend fun getById(mangaId: Long): UpdateWatch? {
        return database.update_watchQueries.getTrackingState(mangaId) {
                mId,
                isPaused,
                enabled,
                interval,
                profile,
                lastCheck,
                lastMilestone,
            ->
            UpdateWatch(
                mangaId = mId,
                isPaused = isPaused == 1L,
                backgroundRefreshEnabled = enabled == 1L,
                expectedIntervalDays = interval.toInt(),
                refreshProfile = UpdateWatch.RefreshProfile.valueOf(profile),
                lastBackgroundCheckAt = lastCheck,
                lastWarnedMilestone = lastMilestone.toInt(),
            )
        }.awaitAsOneOrNull()
    }

    override suspend fun insert(updateWatch: UpdateWatch) {
        database.transaction {
            database.update_watchQueries.insert(
                updateWatch.mangaId,
                if (updateWatch.isPaused) 1L else 0L,
                if (updateWatch.backgroundRefreshEnabled) 1L else 0L,
                updateWatch.expectedIntervalDays.toLong(),
                updateWatch.refreshProfile.name,
                updateWatch.lastBackgroundCheckAt,
                updateWatch.lastWarnedMilestone.toLong(),
            )
        }
    }

    override suspend fun delete(mangaId: Long) {
        database.transaction {
            database.update_watchQueries.delete(mangaId)
        }
    }

    override suspend fun updatePaused(mangaId: Long, isPaused: Boolean) {
        database.transaction {
            database.update_watchQueries.updatePaused(if (isPaused) 1L else 0L, mangaId)
        }
    }

    override suspend fun updateBackgroundRefresh(
        mangaId: Long,
        enabled: Boolean,
        interval: Int,
        profile: UpdateWatch.RefreshProfile,
    ) {
        database.transaction {
            database.update_watchQueries.updateBackgroundRefresh(
                if (enabled) 1L else 0L,
                interval.toLong(),
                profile.name,
                mangaId,
            )
        }
    }

    override suspend fun updateLastBackgroundCheckAt(mangaId: Long, timestamp: Long) {
        database.transaction {
            database.update_watchQueries.updateLastBackgroundCheckAt(timestamp, mangaId)
        }
    }

    override suspend fun updateStaleMilestone(mangaId: Long, milestone: Int) {
        database.transaction {
            database.update_watchQueries.updateStaleMilestone(milestone.toLong(), mangaId)
        }
    }

    override suspend fun resetStaleMilestone(mangaId: Long) {
        database.transaction {
            database.update_watchQueries.resetStaleMilestone(mangaId)
        }
    }
}
