package eu.kanade.tachiyomi.ui.mod.updatewatch.worker

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.workDataOf
import eu.kanade.tachiyomi.ui.mod.updatewatch.helper.UpdateWatchRefreshHelper
import eu.kanade.tachiyomi.ui.mod.util.dateUploadMillis
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.ManageUpdateWatch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object UpdateWatchRefreshScheduler {
    const val WORK_NAME_SCHEDULE = "UpdateWatchRefreshSchedule"
    const val WORK_NAME_RUN = "UpdateWatchRefreshRun"
    const val MANUAL_WORK_NAME = "UpdateWatchRefreshManual"
    const val KEY_SCHEDULED_AT = "scheduled_at"
    const val KEY_RUN_ORIGIN = "run_origin"
    const val ORIGIN_SCHEDULED = "scheduled"
    const val ORIGIN_MANUAL = "manual"

    private val schedulerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun setupTask(context: Context, skipRunCheck: Boolean = false) {
        schedulerScope.launch {
            setupTaskSuspend(context, skipRunCheck)
        }
    }

    suspend fun setupTaskSuspend(context: Context, skipRunCheck: Boolean = false): Long? = withContext(Dispatchers.IO) {
        if (!isMainProcess(context)) return@withContext null

        if (!skipRunCheck) {
            val workInfos = context.workManager.getWorkInfosForUniqueWork(WORK_NAME_RUN).get()
            val isActive = workInfos.any { !it.state.isFinished }
            if (isActive) {
                UpdateWatchDiagnosticsManager.logEvent("Schedule/Launcher ignored: a run is already active")
                return@withContext null
            }
        }

        val manageUpdateWatch: ManageUpdateWatch = Injekt.get()
        val chapterRepository: ChapterRepository = Injekt.get()

        val trackedManga = manageUpdateWatch.subscribeAll().first()
        if (trackedManga.none { it.backgroundRefreshEnabled && !it.isPaused }) {
            context.workManager.cancelUniqueWork(WORK_NAME_SCHEDULE)
            UpdateWatchDiagnosticsManager.logEvent("Schedule cancelled: no active Auto Refresh manga")
            return@withContext null
        }

        val latestChapterDates = trackedManga.associate { tracking ->
            val chapters = chapterRepository.getChapterByMangaId(tracking.mangaId)
            val latest = chapters.filter { it.dateUploadMillis > 0 }.maxByOrNull { it.dateUploadMillis }
            tracking.mangaId to (latest?.dateUploadMillis ?: 0L)
        }

        val now = System.currentTimeMillis()
        val zoneId = java.time.ZoneId.systemDefault()
        val earliest = UpdateWatchRefreshHelper.getEarliestNextEligibleAt(trackedManga, latestChapterDates, now, zoneId)

        if (earliest == null) {
            context.workManager.cancelUniqueWork(WORK_NAME_SCHEDULE)
            UpdateWatchDiagnosticsManager.logEvent("Schedule cancelled: no future eligible timestamp")
            return@withContext null
        }

        val diagPrefs = Injekt.get<UpdateWatchDiagnosticsPreferences>()
        val lastEarliest = diagPrefs.lastEarliestEligibleAt.get()
        val lastScheduled = diagPrefs.lastScheduledAt.get()
        val lastMargin = diagPrefs.lastMarginMinutes.get()

        val existingWork = context.workManager.getWorkInfosForUniqueWork(WORK_NAME_SCHEDULE).get()
            .firstOrNull { !it.state.isFinished }

        val canKeep = existingWork != null && lastEarliest == earliest && lastScheduled > 0

        if (canKeep) {
            val isOverdue = lastScheduled <= now
            val logMsg = buildString {
                if (isOverdue) {
                    append("Schedule kept: launcher is overdue but active")
                } else {
                    append("Schedule kept: existing target is valid")
                }
                append("\nBase slot: ${UpdateWatchDiagnosticsManager.formatTimestamp(earliest)}")
                append("\nMargin: +$lastMargin min")
                append("\nFinal target: ${UpdateWatchDiagnosticsManager.formatTimestamp(lastScheduled)}")
                append("\nWork state: ${existingWork.state}")
                append("\nZone: ${zoneId.id}")
            }
            UpdateWatchDiagnosticsManager.log(
                UpdateWatchSchedulerDiagnostic(
                    type = UpdateWatchSchedulerDiagnostic.RunType.SCHEDULER_EVENT,
                    eventName = logMsg,
                    scheduledAt = lastScheduled,
                    earliestNextEligibleAt = earliest,
                    wallClockBaseSlot = earliest,
                    nextWorkerTargetAt = lastScheduled,
                    safetyMarginMinutes = lastMargin,
                    timezone = zoneId.id,
                ),
            )
            return@withContext lastScheduled
        }

        val marginMillis = Random.nextLong(5, 9) * 60 * 1000L
        val delayMillis = UpdateWatchRefreshHelper.calculateRescheduleDelay(earliest, now, marginMillis)
        if (delayMillis == null) return@withContext null

        val scheduledAt = now + delayMillis
        val isRecovery = earliest <= now
        val marginMin = (marginMillis / (60 * 1000)).toInt()

        val logMsg = buildString {
            append(if (skipRunCheck) "Schedule created (self-reschedule)" else "Schedule replaced")
            if (isRecovery) append(" [RECOVERY]")
            append("\nBase slot: ${UpdateWatchDiagnosticsManager.formatTimestamp(earliest)}")
            append("\nMargin: +$marginMin min")
            append("\nNew target: ${UpdateWatchDiagnosticsManager.formatTimestamp(scheduledAt)}")
            if (lastScheduled > now && lastScheduled != scheduledAt) {
                append("\nOld target: ${UpdateWatchDiagnosticsManager.formatTimestamp(lastScheduled)}")
            }
            append("\nZone: ${zoneId.id}")
        }

        UpdateWatchDiagnosticsManager.log(
            UpdateWatchSchedulerDiagnostic(
                type = UpdateWatchSchedulerDiagnostic.RunType.SCHEDULER_EVENT,
                eventName = logMsg,
                scheduledAt = scheduledAt,
                earliestNextEligibleAt = earliest,
                wallClockBaseSlot = earliest,
                nextWorkerTargetAt = scheduledAt,
                isRecoveryRun = isRecovery,
                safetyMarginMinutes = marginMin,
                timezone = zoneId.id,
            ),
        )

        diagPrefs.lastEarliestEligibleAt.set(earliest)
        diagPrefs.lastScheduledAt.set(scheduledAt)
        diagPrefs.lastMarginMinutes.set(marginMin)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = OneTimeWorkRequestBuilder<UpdateWatchScheduleWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .addTag(WORK_NAME_SCHEDULE)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
            .setInputData(workDataOf(KEY_SCHEDULED_AT to scheduledAt))
            .build()

        context.workManager.enqueueUniqueWork(
            WORK_NAME_SCHEDULE,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        return@withContext scheduledAt
    }

    fun launchRunNow(context: Context, scheduledAt: Long? = null) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = OneTimeWorkRequestBuilder<UpdateWatchRefreshWorker>()
            .addTag(WORK_NAME_RUN)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
            .apply {
                setInputData(workDataOf(KEY_RUN_ORIGIN to ORIGIN_SCHEDULED))
                if (scheduledAt != null) {
                    setInputData(
                        workDataOf(
                            KEY_SCHEDULED_AT to scheduledAt,
                            KEY_RUN_ORIGIN to ORIGIN_SCHEDULED,
                        ),
                    )
                }
            }
            .build()

        context.workManager.enqueueUniqueWork(
            WORK_NAME_RUN,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun runNow(context: Context, simulationMode: Int = UpdateWatchRefreshWorker.SIM_NONE) {
        val request = OneTimeWorkRequestBuilder<UpdateWatchRefreshWorker>()
            .addTag(MANUAL_WORK_NAME)
            .setInputData(
                workDataOf(
                    UpdateWatchRefreshWorker.KEY_SIMULATION_MODE to simulationMode,
                    KEY_RUN_ORIGIN to ORIGIN_MANUAL,
                ),
            )
            .build()

        context.workManager.enqueueUniqueWork(
            MANUAL_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun isMainProcess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageName == Application.getProcessName()
        } else {
            val pid = android.os.Process.myPid()
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.runningAppProcesses?.any { it.pid == pid && it.processName == context.packageName } == true
        }
    }
}
